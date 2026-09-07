"""发现 MCP 服务并将其能力映射为大模型工具。"""

from __future__ import annotations

import asyncio
import json
import os
from dataclasses import dataclass, field
from datetime import timedelta
from typing import Any

from langchain_core.tools import BaseTool, StructuredTool
from langchain_mcp_adapters.client import MultiServerMCPClient
from Agent.Interface.BackendClient import RunContext


@dataclass(frozen=True)
class MCPServerConfig:
    name: str
    transport: str
    url: str | None = None
    command: str | None = None
    args: tuple[str, ...] = ()
    env: dict[str, str] = field(default_factory=dict)
    internal_token: str | None = None
    timeout: float = 20.0

    def connection(self, context: RunContext) -> dict[str, Any]:
        if self.transport == "streamable_http":
            if not self.url:
                raise ValueError(f"MCP 服务 {self.name} 缺少 url")
            if not self.internal_token:
                raise ValueError(f"MCP 服务 {self.name} 缺少 internal_token")
            return {
                "transport": "streamable_http",
                "url": self.url,
                "headers": {
                    "Authorization": f"Bearer {self.internal_token}",
                    **context.headers(),
                },
                "timeout": timedelta(seconds=self.timeout),
                "sse_read_timeout": timedelta(seconds=self.timeout),
                "terminate_on_close": True,
            }
        if self.transport == "stdio":
            if not self.command:
                raise ValueError(f"MCP 服务 {self.name} 缺少 command")
            env = {
                **self.env,
                "MCP_STDIO_TRUSTED": "1",
                "MCP_USER_ID": context.user_id,
                "MCP_SESSION_ID": context.session_id,
                "MCP_REQUEST_ID": context.request_id,
            }
            if context.message_id:
                env["MCP_MESSAGE_ID"] = context.message_id
            return {
                "transport": "stdio",
                "command": self.command,
                "args": list(self.args),
                "env": env,
            }
        raise ValueError(f"MCP 服务 {self.name} 使用了不支持的 transport: {self.transport}")


class MCPClient:
    """保存工具服务配置，并为一次运行创建带身份的 MCP 客户端。"""

    def __init__(self, servers: list[MCPServerConfig]) -> None:
        if not servers:
            raise ValueError("至少需要配置一个 MCP 服务")
        names = [server.name for server in servers]
        if len(names) != len(set(names)):
            raise ValueError("MCP 服务名不能重复")
        self._servers = tuple(servers)

    @classmethod
    def from_env(cls) -> "MCPClient":
        token = os.getenv("MCP_INTERNAL_TOKEN")
        return cls([
            MCPServerConfig(
                name="rag",
                transport="streamable_http",
                url=os.getenv("RAG_MCP_URL", "http://127.0.0.1:8801/mcp"),
                internal_token=token,
            ),
            MCPServerConfig(
                name="memory",
                transport="streamable_http",
                url=os.getenv("MEMORY_MCP_URL", "http://127.0.0.1:8802/mcp"),
                internal_token=token,
            ),
        ])

    def bind(self, context: RunContext) -> "BoundMCPClient":
        return BoundMCPClient(self._servers, context)


class BoundMCPClient:
    """一次智能体运行使用的 MCP 工具集合。"""

    def __init__(self, servers: tuple[MCPServerConfig, ...], context: RunContext) -> None:
        self.context = context
        self._configs = {server.name: server for server in servers}
        self._client = MultiServerMCPClient({
            server.name: server.connection(context) for server in servers
        })
        self._tools: list[BaseTool] | None = None
        self._closed = False

    async def get_tools(self) -> list[BaseTool]:
        if self._closed:
            raise RuntimeError("MCPClient 已关闭")
        if self._tools is not None:
            return list(self._tools)

        server_names = list(self._configs)
        tool_groups = await asyncio.gather(*(
            self._client.get_tools(server_name=name) for name in server_names
        ))
        discovered = [
            _prefix_tool(server_name, tool, self._configs[server_name].timeout)
            for server_name, tools in zip(server_names, tool_groups)
            for tool in tools
        ]
        names = [tool.name for tool in discovered]
        if len(names) != len(set(names)):
            raise RuntimeError("MCP 工具加前缀后仍存在重名")
        self._tools = discovered
        return list(discovered)

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        for tool in await self.get_tools():
            if tool.name == name:
                return await tool.ainvoke(arguments)
        raise ValueError(f"未发现 MCP 工具 {name!r}")

    async def close(self) -> None:
        self._tools = None
        self._closed = True

    async def __aenter__(self) -> "BoundMCPClient":
        return self

    async def __aexit__(self, *_exc_info: Any) -> None:
        await self.close()


def _prefix_tool(server_name: str, tool: BaseTool, timeout: float) -> StructuredTool:
    prefixed_name = f"{server_name}__{tool.name}"

    async def invoke(**arguments: Any) -> Any:
        try:
            if tool.coroutine is None:
                raise RuntimeError("MCP 适配工具缺少异步调用入口")
            return await asyncio.wait_for(tool.coroutine(**arguments), timeout=timeout)
        except TimeoutError:
            status = "unknown" if tool.name in {"memory_store", "memory_forget"} else "error"
            content = json.dumps({
                "status": status,
                "message": f"工具 {prefixed_name} 调用超时；写操作结果可能无法确认。"
                if status == "unknown"
                else f"工具 {prefixed_name} 调用超时。",
            }, ensure_ascii=False)
            return (content, None) if tool.response_format == "content_and_artifact" else content
        except Exception as exc:
            content = json.dumps({
                "status": "error",
                "message": f"工具 {prefixed_name} 调用失败: {exc}",
            }, ensure_ascii=False)
            return (content, None) if tool.response_format == "content_and_artifact" else content

    return StructuredTool(
        name=prefixed_name,
        description=f"[{server_name}] {tool.description or tool.name}",
        args_schema=tool.args_schema,
        coroutine=invoke,
        response_format=tool.response_format,
    )

"""将 Memory 集成层封装为 MCP 工具。"""

from __future__ import annotations

import argparse
import hmac
import os
import sys
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated, Any, Literal

PROJECT_ROOT = Path(__file__).resolve().parents[3]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from mcp.server.auth.provider import AccessToken
from mcp.server.auth.settings import AuthSettings
from mcp.server.fastmcp import Context, FastMCP
from pydantic import Field

from Agent.Interface.BackendClient import RunContext
from Agent.Tools.Memory.memory import MemoryService

_service: MemoryService | None = None
_host = os.getenv("MEMORY_MCP_HOST", "127.0.0.1")
_port = int(os.getenv("MEMORY_MCP_PORT", "8802"))


class _InternalTokenVerifier:
    async def verify_token(self, token: str) -> AccessToken | None:
        expected = os.getenv("MCP_INTERNAL_TOKEN", "")
        if not expected or not hmac.compare_digest(token, expected):
            return None
        return AccessToken(token=token, client_id="agent-runtime", scopes=[])


def _auth_settings() -> AuthSettings:
    public_host = "127.0.0.1" if _host in {"0.0.0.0", "::"} else _host
    return AuthSettings(
        issuer_url="http://internal.invalid",
        resource_server_url=f"http://{public_host}:{_port}/mcp",
    )


def _run_context(ctx: Context) -> RunContext:
    request = ctx.request_context.request
    headers = getattr(request, "headers", None)
    if headers is not None:
        def required(name: str) -> str:
            value = headers.get(name, "")
            if not value.strip():
                raise ValueError(f"缺少内部请求头 {name}")
            return value

        return RunContext(
            user_id=required("x-user-id"),
            session_id=required("x-session-id"),
            request_id=required("x-request-id"),
            message_id=headers.get("x-message-id") or None,
        )

    if os.getenv("MCP_STDIO_TRUSTED") != "1":
        raise PermissionError("stdio MCP 必须由受信任的本地客户端启动")

    def required(name: str) -> str:
        value = os.getenv(name, "")
        if not value.strip():
            raise ValueError(f"缺少环境变量 {name}")
        return value

    return RunContext(
        user_id=required("MCP_USER_ID"),
        session_id=required("MCP_SESSION_ID"),
        request_id=required("MCP_REQUEST_ID"),
        message_id=os.getenv("MCP_MESSAGE_ID") or None,
    )


@asynccontextmanager
async def _lifespan(_server: FastMCP):
    try:
        yield
    finally:
        if _service is not None:
            await _service.aclose()


mcp = FastMCP(
    "memory",
    host=_host,
    port=_port,
    stateless_http=True,
    json_response=True,
    lifespan=_lifespan,
    auth=_auth_settings(),
    token_verifier=_InternalTokenVerifier(),
)


def _get_service() -> MemoryService:
    global _service
    if _service is None:
        _service = MemoryService()
    return _service


@mcp.tool(structured_output=True)
async def memory_query(
    query: str,
    ctx: Context,
    memory_type: Literal["all", "working", "semantic", "episodic"] = "all",
    limit: int = 5,
) -> dict[str, Any]:
    """查询记忆。

    working 查询当前会话临时信息；semantic 查询稳定事实和偏好；episodic 查询
    重要经历；all 同时查询三类记忆。工作记忆可用内容或返回的 memory_id 查询。
    """
    return await _get_service().query(
        _run_context(ctx),
        query,
        memory_type=memory_type,
        limit=limit,
    )


@mcp.tool(structured_output=True)
async def memory_store(
    content: str,
    ctx: Context,
    memory_type: Literal["working", "semantic", "episodic"] = "semantic",
    memory_id: str | None = None,
    importance: Annotated[float | None, Field(ge=0, le=1)] = None,
) -> dict[str, Any]:
    """按分类保存记忆。

    working 用于当前会话的临时结论和中间状态；semantic 用于稳定事实与偏好；
    episodic 用于重要经历。semantic 和 episodic 必须提供 0 到 1 的 importance，
    数值越高表示未来复用价值越高，可按重要程度使用 0.1、0.2 等小数。working
    不需要 importance。传入 memory_id 可更新同一工作记忆或明确纠正长期记忆。
    """
    context = _run_context(ctx)
    return await _get_service().store(
        context,
        content,
        operation_id=f"mcp:{context.request_id}:{ctx.request_id}",
        memory_type=memory_type,
        memory_id=memory_id,
        importance=importance,
    )


@mcp.tool(structured_output=True)
async def memory_forget(
    memory_id: str,
    memory_type: Literal["working", "semantic", "episodic"],
    ctx: Context,
) -> dict[str, Any]:
    """根据 memory_type 删除指定的工作、语义或情景记忆。"""
    context = _run_context(ctx)
    return await _get_service().forget(
        context,
        memory_id,
        operation_id=f"mcp:{context.request_id}:{ctx.request_id}",
        memory_type=memory_type,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Memory MCP Server")
    parser.add_argument(
        "--transport",
        choices=["streamable-http", "stdio"],
        default=os.getenv("MCP_TRANSPORT", "streamable-http"),
    )
    args = parser.parse_args()
    mcp.run(transport=args.transport)


if __name__ == "__main__":
    main()

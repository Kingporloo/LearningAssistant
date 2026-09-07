"""将 RAG 集成层封装为只读 MCP 工具。"""

from __future__ import annotations

import argparse
import hmac
import os
import sys
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

PROJECT_ROOT = Path(__file__).resolve().parents[3]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from mcp.server.auth.provider import AccessToken
from mcp.server.auth.settings import AuthSettings
from mcp.server.fastmcp import Context, FastMCP

from Agent.Interface.BackendClient import RunContext
from Agent.Tools.RAG.rag import RAGSystem

_system: RAGSystem | None = None
_host = os.getenv("RAG_MCP_HOST", "127.0.0.1")
_port = int(os.getenv("RAG_MCP_PORT", "8801"))


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
        if _system is not None:
            await _system.aclose()


mcp = FastMCP(
    "rag",
    host=_host,
    port=_port,
    stateless_http=True,
    json_response=True,
    lifespan=_lifespan,
    auth=_auth_settings(),
    token_verifier=_InternalTokenVerifier(),
)


def _get_system() -> RAGSystem:
    global _system
    if _system is None:
        _system = RAGSystem(
            candidate_limit=int(os.getenv("RAG_CANDIDATE_LIMIT", "20")),
            relevance_threshold=float(os.getenv("RAG_RELEVANCE_THRESHOLD", "0.45")),
            use_graph=os.getenv("RAG_USE_GRAPH", "true").lower() in {"1", "true", "yes", "on"},
        )
    return _system


@mcp.tool(structured_output=True)
async def rag_search(
    query: str,
    ctx: Context,
    expanded_queries: list[str] | None = None,
    hypothetical_doc: str | None = None,
) -> dict[str, Any]:
    """检索当前用户已就绪的私有知识库并返回状态、证据和来源。

    empty/no_match 表示没有知识库依据，可以按用户要求使用一般知识回答；
    error 表示查询失败，不能解释为没有相关内容。
    """
    return await _get_system().search(
        _run_context(ctx),
        query,
        expanded_queries=expanded_queries,
        hypothetical_doc=hypothetical_doc,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="RAG MCP Server")
    parser.add_argument(
        "--transport",
        choices=["streamable-http", "stdio"],
        default=os.getenv("MCP_TRANSPORT", "streamable-http"),
    )
    args = parser.parse_args()
    mcp.run(transport=args.transport)


if __name__ == "__main__":
    main()

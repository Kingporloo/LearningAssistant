"""Java 后端调用 Python Agent 的内部 HTTP/SSE 入口。"""

from __future__ import annotations

import hmac
import json
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from functools import lru_cache
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse

from Agent.AgentLoop import AgentLoop
from Agent.Assistant import Assistant
from Agent.Interface.AgentSchemas import AgentRunRequest
from Agent.Interface.BackendClient import BackendClient, RunContext
from Agent.Loop.Models import AgentEvent, AgentRunInput
from Agent.Tools.MCP.MCPClient import MCPClient
from Agent.Tools.RAG.dataPraperation import EmbeddingModel


@asynccontextmanager
async def _lifespan(_app: FastAPI):
    yield
    if _backend_client.cache_info().currsize:
        await _backend_client().aclose()
    _backend_client.cache_clear()


app = FastAPI(
    title="Agent Internal API",
    docs_url=None,
    redoc_url=None,
    lifespan=_lifespan,
)


def require_trusted_run(
    payload: AgentRunRequest,
    authorization: Annotated[str | None, Header()] = None,
    x_user_id: Annotated[str | None, Header(alias="X-User-ID")] = None,
    x_session_id: Annotated[str | None, Header(alias="X-Session-ID")] = None,
    x_request_id: Annotated[str | None, Header(alias="X-Request-ID")] = None,
    x_message_id: Annotated[str | None, Header(alias="X-Message-ID")] = None,
) -> RunContext:
    """只确认内部调用方及运行标识；用户登录态和会话归属由 Java 负责。"""

    token = os.getenv("PYTHON_INTERNAL_TOKEN", "")
    if not token:
        raise HTTPException(status_code=503, detail="PYTHON_INTERNAL_TOKEN 未配置")
    if not hmac.compare_digest(authorization or "", f"Bearer {token}"):
        raise HTTPException(status_code=401, detail="内部服务认证失败")

    expected = {
        "X-User-ID": payload.user_id,
        "X-Session-ID": payload.session_id,
        "X-Request-ID": payload.request_id,
        "X-Message-ID": payload.message_id,
    }
    actual = {
        "X-User-ID": x_user_id,
        "X-Session-ID": x_session_id,
        "X-Request-ID": x_request_id,
        "X-Message-ID": x_message_id,
    }
    for name, value in actual.items():
        if value is None:
            raise HTTPException(status_code=400, detail=f"缺少 {name} 请求头")
        if not hmac.compare_digest(value, expected[name]):
            raise HTTPException(status_code=400, detail=f"{name} 与请求体不一致")
    return RunContext(
        user_id=payload.user_id,
        session_id=payload.session_id,
        request_id=payload.request_id,
        message_id=payload.message_id,
    )


def get_agent_loop(
    payload: AgentRunRequest,
    _context: Annotated[RunContext, Depends(require_trusted_run)],
) -> AgentLoop:
    """为本次请求绑定配置；模型、MCP 配置和嵌入模型在进程内复用。"""

    try:
        return AgentLoop(
            assistant=_assistant(),
            mcp_client=_mcp_client(),
            context_config=payload.agent_config.to_context_config(),
            embed_texts=_embedding_model().embed_documents,
            backend_client=_backend_client(),
            max_tool_rounds=int(os.getenv("AGENT_MAX_TOOL_ROUNDS", "5")),
        )
    except (TypeError, ValueError, RuntimeError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@app.post("/internal/agent/runs")
async def run_agent(
    payload: AgentRunRequest,
    context: Annotated[RunContext, Depends(require_trusted_run)],
    agent_loop: Annotated[AgentLoop, Depends(get_agent_loop)],
) -> StreamingResponse:
    try:
        run_input = payload.to_agent_run_input(
            context,
            agent_loop.assistant.count_tokens,
        )
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    return StreamingResponse(
        _event_stream(agent_loop, run_input),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


async def _event_stream(
    agent_loop: AgentLoop,
    run_input: AgentRunInput,
) -> AsyncIterator[str]:
    stream = agent_loop.astream(run_input)
    try:
        async for event in stream:
            yield encode_sse(event)
    finally:
        await stream.aclose()


def encode_sse(event: AgentEvent) -> str:
    data = json.dumps(
        event.as_dict(),
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return f"event: {event.type}\ndata: {data}\n\n"


@lru_cache(maxsize=1)
def _assistant() -> Assistant:
    return Assistant.from_openai_compatible()


@lru_cache(maxsize=1)
def _mcp_client() -> MCPClient:
    return MCPClient.from_env()


@lru_cache(maxsize=1)
def _backend_client() -> BackendClient:
    return BackendClient.from_env()


@lru_cache(maxsize=1)
def _embedding_model() -> EmbeddingModel:
    return EmbeddingModel(os.getenv("AGENT_EMBEDDING_MODEL", "jinaai/jina-embeddings-v2-base-zh"))


__all__ = [
    "app",
    "encode_sse",
    "get_agent_loop",
    "require_trusted_run",
    "run_agent",
]

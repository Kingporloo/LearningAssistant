"""Java 后端调用 Python Agent 的内部 HTTP/SSE 入口。"""

from __future__ import annotations

import hmac
import json
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import datetime
from functools import lru_cache
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse

from Agent.AgentLoop import AgentLoop
from Agent.Assistant import Assistant
from Agent.Context.ContextBuider import ContextBuildResult, ContextBuilder
from Agent.Interface.AgentSchemas import (
    AgentCompactRequest,
    AgentRunRequest,
    AgentSessionRequest,
)
from Agent.Interface.BackendClient import BackendClient, RunContext
from Agent.Loop.Models import AgentEvent, AgentRunInput
from Agent.SystemPrompt import SYSTEM_PROMPT
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


def require_trusted_user(
    payload: AgentSessionRequest,
    authorization: Annotated[str | None, Header()] = None,
    x_user_id: Annotated[str | None, Header(alias="X-User-ID")] = None,
) -> str:
    _require_internal_token(authorization)
    _require_matching_header("X-User-ID", x_user_id, payload.user_id)
    return payload.user_id


def require_trusted_compact(
    payload: AgentCompactRequest,
    authorization: Annotated[str | None, Header()] = None,
    x_user_id: Annotated[str | None, Header(alias="X-User-ID")] = None,
    x_session_id: Annotated[str | None, Header(alias="X-Session-ID")] = None,
    x_request_id: Annotated[str | None, Header(alias="X-Request-ID")] = None,
) -> RunContext:
    _require_internal_token(authorization)
    _require_matching_header("X-User-ID", x_user_id, payload.user_id)
    _require_matching_header("X-Session-ID", x_session_id, payload.session_id)
    _require_matching_header("X-Request-ID", x_request_id, payload.request_id)
    return RunContext(
        user_id=payload.user_id,
        session_id=payload.session_id,
        request_id=payload.request_id,
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

    _require_internal_token(authorization)

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
        _require_matching_header(name, value, expected[name])
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


def get_compact_builder(
    payload: AgentCompactRequest,
    _context: Annotated[RunContext, Depends(require_trusted_compact)],
) -> ContextBuilder:
    try:
        assistant = _assistant()
        return ContextBuilder(
            config=payload.agent_config.to_context_config(),
            mcp_client=None,
            count_tokens=assistant.count_tokens,
            count_request=assistant.count_request,
            embed_texts=_embed_texts,
            summary_model=assistant.model,
            backend_client=_backend_client(),
        )
    except (TypeError, ValueError, RuntimeError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@app.post("/internal/agent/sessions", status_code=201)
def create_agent_session(
    payload: AgentSessionRequest,
    user_id: Annotated[str, Depends(require_trusted_user)],
) -> dict[str, str]:
    return {
        "user_id": user_id,
        "session_id": create_session_id(user_id),
    }


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


@app.post("/internal/agent/context/compact")
async def compact_agent_context(
    payload: AgentCompactRequest,
    context: Annotated[RunContext, Depends(require_trusted_compact)],
    builder: Annotated[ContextBuilder, Depends(get_compact_builder)],
) -> dict[str, object]:
    try:
        state = payload.to_context_state(
            context,
            builder.count_tokens,
            system_prompt=SYSTEM_PROMPT,
        )
        result = await builder.compact_context(state)
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return _compact_response(result)


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


def create_session_id(user_id: str, now: datetime | None = None) -> str:
    timestamp = now or datetime.now()
    return f"session_{timestamp.strftime('%Y%m%d_%H%M%S')}_{user_id}"


def _compact_response(result: ContextBuildResult) -> dict[str, object]:
    attempt = result.compact_result
    if result.summary_save_status == "saved":
        status = "saved"
    elif result.summary_save_status == "unknown":
        status = "unknown"
    elif result.summary_save_status == "failed":
        status = "failed"
    elif attempt is not None and attempt.status == "skipped":
        status = "skipped"
    else:
        status = "failed"

    return {
        "status": status,
        "trigger": "manual",
        "reason": result.reason or (attempt.reason if attempt is not None else None),
        "before_tokens": result.before_compact_tokens,
        "after_tokens": result.input_tokens,
        "compact_trigger_tokens": result.budget.compact_trigger_tokens,
        "below_trigger": not result.budget.reaches_compact_threshold(
            result.input_tokens
        ),
        "summary_save_status": result.summary_save_status,
        "session_summary": _summary_data(result.session_summary),
        "compact": (
            {
                "status": attempt.status,
                "replaced_tokens": attempt.replaced_tokens,
                "input_tokens": attempt.input_tokens,
                "output_tokens": attempt.output_tokens,
                "reason": attempt.reason,
                "usage": attempt.usage_metadata,
            }
            if attempt is not None
            else None
        ),
    }


def _summary_data(summary) -> dict[str, object] | None:
    if summary is None:
        return None
    return {
        "version": summary.version,
        "text": summary.text,
        "through_message_id": summary.through_message_id,
        "source_refs": [
            {
                "kind": ref.kind.value,
                "ref_id": ref.ref_id,
                "session_id": ref.session_id,
                "version": ref.version,
                "exists": ref.exists,
                "metadata": dict(ref.metadata),
            }
            for ref in summary.source_refs
        ],
    }


def _require_internal_token(authorization: str | None) -> None:
    token = os.getenv("PYTHON_INTERNAL_TOKEN", "")
    if not token:
        raise HTTPException(status_code=503, detail="PYTHON_INTERNAL_TOKEN 未配置")
    if not hmac.compare_digest(authorization or "", f"Bearer {token}"):
        raise HTTPException(status_code=401, detail="内部服务认证失败")


def _require_matching_header(
    name: str,
    actual: str | None,
    expected: str,
) -> None:
    if actual is None:
        raise HTTPException(status_code=400, detail=f"缺少 {name} 请求头")
    if not hmac.compare_digest(actual, expected):
        raise HTTPException(status_code=400, detail=f"{name} 与请求体不一致")


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


def _embed_texts(texts: list[str]) -> list[list[float]]:
    return _embedding_model().embed_documents(texts)


__all__ = [
    "app",
    "compact_agent_context",
    "create_agent_session",
    "create_session_id",
    "encode_sse",
    "get_agent_loop",
    "get_compact_builder",
    "require_trusted_compact",
    "require_trusted_run",
    "require_trusted_user",
    "run_agent",
]

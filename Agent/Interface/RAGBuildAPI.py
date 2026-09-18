"""Java 后端调用 Agent RAG 构建能力的 HTTP 接口。"""

from __future__ import annotations

import asyncio
import hmac
import os
from functools import lru_cache
from pathlib import Path
from threading import Lock
from typing import Annotated, Any

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from PDF2Markdown import convert_file
from Agent.Tools.RAG.rag import RAGBuilder

app = FastAPI(title="RAG Build API", docs_url=None, redoc_url=None)
_build_lock = Lock()


class BuildRequest(BaseModel):
    user_id: str = Field(min_length=1)
    document_id: str = Field(min_length=1)
    request_id: str = Field(min_length=1)
    file_ref: str = Field(min_length=1, description="Java 管理的原文件或已转换 Markdown 路径")
    markdown_ref: str | None = Field(
        default=None,
        description="Java 管理的 Markdown 输出路径；提供时先转换原文件",
    )
    source_name: str | None = None


@app.get("/health/live")
async def health_live() -> dict[str, str]:
    return {"status": "alive"}


@app.get("/health/ready")
async def health_ready() -> JSONResponse:
    allowed_root = os.getenv("RAG_ALLOWED_ROOT")
    token = os.getenv("PYTHON_INTERNAL_TOKEN")
    ready = bool(
        allowed_root
        and token
        and Path(allowed_root).is_dir()
        and os.access(allowed_root, os.R_OK | os.W_OK)
    )
    return JSONResponse(
        {"status": "ready" if ready else "not_ready"},
        status_code=200 if ready else 503,
    )


def _authenticate(authorization: str | None) -> None:
    token = os.getenv("PYTHON_INTERNAL_TOKEN", "")
    if not token:
        raise HTTPException(status_code=503, detail="PYTHON_INTERNAL_TOKEN 未配置")
    if not hmac.compare_digest(authorization or "", f"Bearer {token}"):
        raise HTTPException(status_code=401, detail="内部服务认证失败")


@lru_cache(maxsize=1)
def _builder() -> RAGBuilder:
    allowed_root = os.getenv("RAG_ALLOWED_ROOT")
    if not allowed_root:
        raise RuntimeError("RAG_ALLOWED_ROOT 未配置")
    return RAGBuilder(allowed_root)


@app.post("/internal/rag/build")
async def build_document(
    request: BuildRequest,
    authorization: Annotated[str | None, Header()] = None,
) -> dict[str, Any]:
    _authenticate(authorization)
    try:
        result = await asyncio.to_thread(_build, request)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return {
        "user_id": request.user_id,
        "request_id": request.request_id,
        **result,
    }


def _build(request: BuildRequest) -> dict[str, Any]:
    # Docling 与嵌入模型由进程复用，同一进程内串行执行构建。
    with _build_lock:
        file_ref = request.file_ref
        if request.markdown_ref is not None:
            source = _managed_path(request.file_ref, must_exist=True)
            markdown = _managed_path(request.markdown_ref, must_exist=False)
            if markdown.suffix.lower() != ".md":
                raise ValueError("markdown_ref 必须使用 .md 扩展名")
            convert_file(source, markdown)
            file_ref = str(markdown)
        return _builder().build(
            document_id=request.document_id,
            file_ref=file_ref,
            source_name=request.source_name,
        )


def _managed_path(file_ref: str, *, must_exist: bool) -> Path:
    allowed_root = os.getenv("RAG_ALLOWED_ROOT")
    if not allowed_root:
        raise RuntimeError("RAG_ALLOWED_ROOT 未配置")
    root = Path(allowed_root).resolve(strict=True)
    path = Path(file_ref).resolve(strict=must_exist)
    if not path.is_relative_to(root):
        raise ValueError("文件引用不在 RAG_ALLOWED_ROOT 中")
    if must_exist and not path.is_file():
        raise ValueError("file_ref 必须指向文件")
    return path

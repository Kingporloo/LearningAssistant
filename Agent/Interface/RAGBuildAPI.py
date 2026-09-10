"""Java 后端调用 Agent RAG 构建能力的 HTTP 接口。"""

from __future__ import annotations

import hmac
import os
from functools import lru_cache
from typing import Annotated, Any

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from Agent.Tools.RAG.rag import RAGBuilder

app = FastAPI(title="RAG Build API", docs_url=None, redoc_url=None)


class BuildRequest(BaseModel):
    user_id: str = Field(min_length=1)
    document_id: str = Field(min_length=1)
    request_id: str = Field(min_length=1)
    file_ref: str = Field(min_length=1, description="Java 授权的已转换 .md 文件路径")
    source_name: str | None = None


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
        result = _builder().build(
            document_id=request.document_id,
            file_ref=request.file_ref,
            source_name=request.source_name,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return {
        "user_id": request.user_id,
        "request_id": request.request_id,
        **result,
    }

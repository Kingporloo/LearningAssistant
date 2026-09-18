"""Agent 调用 Java 后端数据服务的统一接口。"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any

import httpx


class BackendError(RuntimeError):
    """Java 后端未能给出可信结果。"""


class BackendOutcomeUnknown(BackendError):
    """写操作超时，Java 后端是否已经执行无法确认。"""


@dataclass(frozen=True)
class RunContext:
    """Java 为一次智能体运行绑定的可信上下文。"""

    user_id: str
    session_id: str
    request_id: str
    message_id: str | None = None

    def __post_init__(self) -> None:
        for name in ("user_id", "session_id", "request_id"):
            if not str(getattr(self, name)).strip():
                raise ValueError(f"{name} 不能为空")

    def headers(self) -> dict[str, str]:
        headers = {
            "X-User-ID": self.user_id,
            "X-Session-ID": self.session_id,
            "X-Request-ID": self.request_id,
        }
        if self.message_id:
            headers["X-Message-ID"] = self.message_id
        return headers

    def payload(self) -> dict[str, str]:
        payload = {
            "user_id": self.user_id,
            "session_id": self.session_id,
            "request_id": self.request_id,
        }
        if self.message_id:
            payload["message_id"] = self.message_id
        return payload


class BackendClient:
    """将 RAG、Memory 的数据请求发送给 Java 后端。"""

    def __init__(
        self,
        base_url: str,
        *,
        internal_token: str,
        timeout: float = 15.0,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        if not base_url.strip():
            raise ValueError("Java 后端 base_url 不能为空")
        if not internal_token.strip():
            raise ValueError("Java 内部服务 token 不能为空")
        self._token = internal_token
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            timeout=timeout,
            trust_env=False,
        )

    @classmethod
    def from_env(cls) -> "BackendClient":
        return cls(
            os.getenv("JAVA_STORAGE_BASE_URL", ""),
            internal_token=os.getenv("JAVA_INTERNAL_TOKEN", ""),
            timeout=float(os.getenv("JAVA_STORAGE_TIMEOUT", "15")),
        )

    async def _post(
        self,
        path: str,
        context: RunContext,
        data: dict[str, Any],
        *,
        write: bool = False,
    ) -> dict[str, Any]:
        headers = {
            "Authorization": f"Bearer {self._token}",
            **context.headers(),
        }
        try:
            response = await self._client.post(
                path,
                headers=headers,
                json={**data, **context.payload()},
            )
            response.raise_for_status()
        except httpx.TimeoutException as exc:
            error = BackendOutcomeUnknown if write else BackendError
            raise error(f"Java 后端调用超时: {path}") from exc
        except httpx.HTTPError as exc:
            raise BackendError(f"Java 后端调用失败: {path}: {exc}") from exc

        try:
            result = response.json()
        except ValueError as exc:
            raise BackendError(f"Java 后端返回了无效 JSON: {path}") from exc
        if not isinstance(result, dict):
            raise BackendError(f"Java 后端响应必须是 JSON 对象: {path}")
        return result

    async def _post_service(
        self,
        path: str,
        data: dict[str, Any],
        *,
        write: bool = False,
    ) -> dict[str, Any]:
        try:
            response = await self._client.post(
                path,
                headers={"Authorization": f"Bearer {self._token}"},
                json=data,
            )
            response.raise_for_status()
        except httpx.TimeoutException as exc:
            error = BackendOutcomeUnknown if write else BackendError
            raise error(f"Java 后端调用超时: {path}") from exc
        except httpx.HTTPError as exc:
            raise BackendError(f"Java 后端调用失败: {path}: {exc}") from exc

        try:
            result = response.json()
        except ValueError as exc:
            raise BackendError(f"Java 后端返回了无效 JSON: {path}") from exc
        if not isinstance(result, dict):
            raise BackendError(f"Java 后端响应必须是 JSON 对象: {path}")
        return result

    async def health(self) -> dict[str, Any]:
        try:
            response = await self._client.get("/health/ready")
            response.raise_for_status()
            result = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise BackendError("Java 数据服务未就绪") from exc
        if not isinstance(result, dict) or result.get("status") != "ready":
            raise BackendError("Java 数据服务未就绪")
        return result

    async def rag_search(
        self,
        context: RunContext,
        *,
        query_vector: list[float],
        candidate_limit: int,
    ) -> dict[str, Any]:
        return await self._post(
            "/internal/storage/rag/search",
            context,
            {"query_vector": query_vector, "limit": candidate_limit},
        )

    async def rag_graph(
        self,
        context: RunContext,
        *,
        seed_chunk_ids: list[str],
        query_vector: list[float],
        candidate_limit: int,
    ) -> dict[str, Any]:
        return await self._post(
            "/internal/storage/rag/graph",
            context,
            {
                "seed_chunk_ids": seed_chunk_ids,
                "query_vector": query_vector,
                "limit": candidate_limit,
            },
        )

    async def memory_query(
        self,
        context: RunContext,
        *,
        memory_type: str,
        query_vector: list[float],
        limit: int,
    ) -> dict[str, Any]:
        return await self._post(
            "/internal/storage/memory/query",
            context,
            {
                "memory_type": memory_type,
                "scope": "user",
                "query_vector": query_vector,
                "limit": limit,
            },
        )

    async def memory_store(
        self,
        context: RunContext,
        *,
        operation_id: str,
        data: dict[str, Any],
    ) -> dict[str, Any]:
        return await self._post(
            "/internal/storage/memory/store",
            context,
            {"operation_id": operation_id, **data},
            write=True,
        )

    async def memory_forget(
        self,
        context: RunContext,
        *,
        operation_id: str,
        memory_type: str,
        memory_id: str,
    ) -> dict[str, Any]:
        return await self._post(
            "/internal/storage/memory/forget",
            context,
            {
                "operation_id": operation_id,
                "memory_type": memory_type,
                "memory_id": memory_id,
            },
            write=True,
        )

    async def memory_graph_claim(self) -> dict[str, Any]:
        return await self._post_service(
            "/internal/storage/memory/graph/claim",
            {},
            write=True,
        )

    async def memory_graph_complete(
        self,
        *,
        user_id: str,
        memory_id: str,
        revision: int,
        graph_status: str,
        graph: dict[str, Any],
        graph_error: str | None = None,
    ) -> dict[str, Any]:
        return await self._post_service(
            "/internal/storage/memory/graph/complete",
            {
                "user_id": user_id,
                "memory_id": memory_id,
                "revision": revision,
                "graph_status": graph_status,
                "graph_error": graph_error,
                "graph": graph,
            },
            write=True,
        )

    async def memory_graph_recover(self) -> dict[str, Any]:
        return await self._post_service(
            "/internal/storage/memory/graph/recover",
            {},
            write=True,
        )

    async def history_search(
        self,
        context: RunContext,
        *,
        history_cursor: str,
        query: str,
        top_k: int = 20,
    ) -> dict[str, Any]:
        return await self._post(
            "/internal/storage/history/search",
            context,
            {
                "history_cursor": history_cursor,
                "query": query,
                "top_k": top_k,
            },
        )

    async def history_read(
        self,
        context: RunContext,
        *,
        history_cursor: str,
        refs: list[dict[str, str]],
    ) -> dict[str, Any]:
        return await self._post(
            "/internal/storage/history/read",
            context,
            {
                "history_cursor": history_cursor,
                "refs": refs,
            },
        )

    async def context_summary_store(
        self,
        context: RunContext,
        *,
        operation_id: str,
        base_version: int,
        history_cursor: str | None,
        through_message_id: str | None,
        source_refs: list[dict[str, Any]],
        text: str,
    ) -> dict[str, Any]:
        return await self._post(
            "/internal/storage/context/summary",
            context,
            {
                "operation_id": operation_id,
                "base_version": base_version,
                "history_cursor": history_cursor,
                "through_message_id": through_message_id,
                "source_refs": source_refs,
                "text": text,
            },
            write=True,
        )

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

"""工作、语义和情景记忆的统一集成入口。"""

from __future__ import annotations

from typing import Any, Literal
from uuid import uuid4

from Agent.Tools.Memory.episodic import prepare_episodic_memory
from Agent.Tools.Memory.semantic import SemanticProcessor
from Agent.Tools.Memory.working import WorkingMemory
from Agent.Tools.RAG.dataPraperation import EmbeddingModel
from Agent.Interface.BackendClient import (
    BackendClient,
    BackendError,
    BackendOutcomeUnknown,
    RunContext,
)

MemoryType = Literal["working", "semantic", "episodic"]
QueryMemoryType = Literal["all", "working", "semantic", "episodic"]
MEMORY_TYPES = {"working", "semantic", "episodic"}


class MemoryService:
    """根据记忆分类路由到工作、语义或情景记忆。"""

    def __init__(
        self,
        backend: BackendClient | None = None,
        *,
        embeddings: EmbeddingModel | None = None,
        semantic: SemanticProcessor | None = None,
        working: WorkingMemory | None = None,
    ) -> None:
        self.backend = backend or BackendClient.from_env()
        self.embeddings = embeddings or EmbeddingModel()
        self.semantic = semantic or SemanticProcessor()
        self.working = working or WorkingMemory()

    async def query(
        self,
        context: RunContext,
        query: str,
        *,
        memory_type: QueryMemoryType = "all",
        limit: int = 5,
    ) -> dict[str, Any]:
        if memory_type not in {*MEMORY_TYPES, "all"}:
            return _error("memory_type 必须是 all、working、semantic 或 episodic。")
        if memory_type == "working":
            return self._query_working(context, query, limit)
        if not query.strip():
            return {"status": "ok", "results": [], "message": "查询内容为空。"}
        if limit < 1 or limit > 20:
            return _error("limit 必须在 1 到 20 之间。")

        working_results = self._working_results(context, query, limit) if memory_type == "all" else []
        vector = self.embeddings.embed_query(query)
        try:
            response = await self.backend.memory_query(
                context,
                memory_type=memory_type,
                query_vector=vector,
                limit=limit,
            )
        except BackendError as exc:
            return _error(str(exc))
        result = _normalise(response, default_message="未找到相关记忆。")
        if working_results and result["status"] in {"ok", "not_found"}:
            result["status"] = "ok"
            result["results"] = working_results + result.get("results", [])
            result["message"] = f"找到 {len(result['results'])} 条相关记忆。"
        return result

    async def store(
        self,
        context: RunContext,
        content: str,
        *,
        operation_id: str,
        memory_type: MemoryType = "semantic",
        memory_id: str | None = None,
        importance: float | None = None,
    ) -> dict[str, Any]:
        content = content.strip()
        if memory_type not in MEMORY_TYPES:
            return _error("memory_type 必须是 working、semantic 或 episodic。")
        if not content:
            return _error("记忆内容不能为空。")
        if memory_id is not None:
            memory_id = memory_id.strip()
            if not memory_id:
                return _error("memory_id 不能为空字符串。")
        if memory_type == "working":
            key = memory_id or f"work-{uuid4().hex}"
            self.working.set(context.user_id, context.session_id, key, content)
            return {
                "status": "ok",
                "memory_id": key,
                "memory_type": "working",
                "message": "工作记忆已保存到当前会话。",
            }
        if (
            isinstance(importance, bool)
            or not isinstance(importance, (int, float))
            or not 0 <= importance <= 1
        ):
            return _error("semantic 和 episodic 记忆的 importance 必须是 0 到 1 的数值。")
        importance = float(importance)
        if not context.message_id:
            return _error("缺少来源 message_id，不能写入长期记忆。")

        if memory_type == "semantic":
            data = self.semantic.prepare(
                content,
                source_session_id=context.session_id,
                source_message_id=context.message_id,
            )
        else:
            data = prepare_episodic_memory(
                content,
                source_session_id=context.session_id,
                source_message_id=context.message_id,
                include_event_time=memory_id is None,
            )
        data.update({
            "memory_type": memory_type,
            "memory_id": memory_id,
            "importance": importance,
            "vector": self.embeddings.embed_query(content),
        })
        try:
            response = await self.backend.memory_store(
                context,
                operation_id=operation_id,
                data=data,
            )
        except BackendOutcomeUnknown as exc:
            return {"status": "unknown", "memory_id": memory_id, "message": str(exc)}
        except BackendError as exc:
            return _error(str(exc))
        return _normalise(response, default_message="长期记忆已保存。")

    async def forget(
        self,
        context: RunContext,
        memory_id: str,
        *,
        operation_id: str,
        memory_type: MemoryType,
    ) -> dict[str, Any]:
        if memory_type not in MEMORY_TYPES:
            return _error("memory_type 必须是 working、semantic 或 episodic。")
        if not memory_id.strip():
            return _error("memory_id 不能为空。")
        if memory_type == "working":
            deleted = self.working.forget(context.user_id, context.session_id, memory_id)
            return {
                "status": "ok" if deleted else "not_found",
                "memory_id": memory_id,
                "memory_type": "working",
                "message": "工作记忆已删除。" if deleted else "未找到指定的工作记忆。",
            }
        try:
            response = await self.backend.memory_forget(
                context,
                operation_id=operation_id,
                memory_type=memory_type,
                memory_id=memory_id,
            )
        except BackendOutcomeUnknown as exc:
            return {"status": "unknown", "memory_id": memory_id, "message": str(exc)}
        except BackendError as exc:
            return _error(str(exc))
        return _normalise(response, default_message="长期记忆删除请求已完成。")

    async def aclose(self) -> None:
        await self.backend.aclose()

    def _query_working(
        self,
        context: RunContext,
        query: str,
        limit: int,
    ) -> dict[str, Any]:
        if limit < 1 or limit > 20:
            return _error("limit 必须在 1 到 20 之间。")
        results = self._working_results(context, query, limit)
        return {
            "status": "ok" if results else "not_found",
            "results": results,
            "message": f"找到 {len(results)} 条工作记忆。" if results else "未找到相关工作记忆。",
        }

    def _working_results(
        self,
        context: RunContext,
        query: str,
        limit: int,
    ) -> list[dict[str, Any]]:
        term = query.strip().casefold()
        results = []
        for item in self.working.items(context.user_id, context.session_id):
            if term and term not in item.key.casefold() and term not in str(item.content).casefold():
                continue
            results.append({
                "memory_id": item.key,
                "memory_type": "working",
                "content": item.content,
            })
            if len(results) >= limit:
                break
        return results


def _normalise(response: dict[str, Any], *, default_message: str) -> dict[str, Any]:
    status = str(response.get("status", "error"))
    if status not in {"ok", "error", "unknown", "not_found"}:
        return _error("Memory 数据服务返回了未知状态。")
    result = dict(response)
    result["status"] = status
    result["message"] = str(response.get("message") or default_message)
    if "results" in result and not isinstance(result["results"], list):
        return _error("Memory 数据服务的 results 必须是列表。")
    return result


def _error(message: str) -> dict[str, Any]:
    return {"status": "error", "message": message}

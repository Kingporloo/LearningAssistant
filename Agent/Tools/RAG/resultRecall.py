"""通过 Java 数据服务完成用户范围内的 RAG 召回与融合。"""

from __future__ import annotations

from collections import defaultdict
from typing import Any

from Agent.Tools.RAG.dataPraperation import EmbeddingModel
from Agent.Interface.BackendClient import BackendClient, BackendError, RunContext

RAG_STATUSES = {"ok", "empty", "no_match", "not_ready", "error"}


def _rrf(rankings: list[tuple[list[str], float]], rrf_k: int) -> dict[str, float]:
    scores: dict[str, float] = defaultdict(float)
    for ids, weight in rankings:
        for rank, chunk_id in enumerate(ids, start=1):
            scores[chunk_id] += weight / (rrf_k + rank)
    return dict(scores)


def _score(result: dict[str, Any]) -> float | None:
    for key in ("score", "similarity"):
        value = result.get(key)
        if isinstance(value, (int, float)):
            return float(value)
    return None


class Retriever:
    """生成查询向量，Java 负责用户过滤、候选读取与正文归属回查。"""

    def __init__(
        self,
        backend: BackendClient,
        *,
        embeddings: EmbeddingModel | None = None,
        candidate_limit: int = 20,
        relevance_threshold: float = 0.45,
        rrf_k: int = 60,
        use_graph: bool = True,
    ) -> None:
        self.backend = backend
        self.embeddings = embeddings or EmbeddingModel()
        self.candidate_limit = candidate_limit
        self.relevance_threshold = relevance_threshold
        self.rrf_k = rrf_k
        self.use_graph = use_graph

    async def recall(
        self,
        context: RunContext,
        query: str,
        *,
        expanded_queries: list[str] | None = None,
        hypothetical_doc: str | None = None,
    ) -> dict[str, Any]:
        query = query.strip()
        if not query:
            return {"status": "no_match", "results": [], "message": "查询内容为空。"}

        routes: list[tuple[str, str]] = [("query", query)]
        routes.extend(
            ("expanded", item.strip())
            for item in (expanded_queries or [])
            if item and item.strip() and item.strip() != query
        )
        if hypothetical_doc and hypothetical_doc.strip():
            routes.append(("hyde", hypothetical_doc.strip()))

        vectors = self.embeddings.embed_documents([text for _, text in routes])
        query_vector = vectors[0]
        responses: list[tuple[str, dict[str, Any]]] = []
        try:
            for (label, _), vector in zip(routes, vectors):
                response = await self.backend.rag_search(
                    context,
                    query_vector=vector,
                    candidate_limit=self.candidate_limit,
                )
                responses.append((label, response))
        except BackendError as exc:
            return {"status": "error", "results": [], "message": str(exc)}

        primary_status = str(responses[0][1].get("status", "error"))
        if primary_status not in RAG_STATUSES:
            return {"status": "error", "results": [], "message": "RAG 数据服务返回了未知状态。"}
        if primary_status in {"empty", "not_ready", "error"}:
            return _normalise_response(responses[0][1], primary_status)

        details: dict[str, dict[str, Any]] = {}
        rankings: list[tuple[list[str], float]] = []
        routes_hit: dict[str, set[str]] = defaultdict(set)
        for label, response in responses:
            status = str(response.get("status", "error"))
            if status not in {"ok", "no_match"}:
                continue
            ids: list[str] = []
            for item in response.get("results") or []:
                if not isinstance(item, dict) or not item.get("chunk_id"):
                    continue
                score = _score(item)
                if score is None or score < self.relevance_threshold:
                    continue
                chunk_id = str(item["chunk_id"])
                ids.append(chunk_id)
                details.setdefault(chunk_id, dict(item))
                routes_hit[chunk_id].add(label)
            if ids:
                rankings.append((ids, 1.0))

        if self.use_graph and rankings:
            initial = _rrf(rankings, self.rrf_k)
            seeds = [chunk_id for chunk_id, _ in sorted(initial.items(), key=lambda pair: -pair[1])[:3]]
            try:
                graph = await self.backend.rag_graph(
                    context,
                    seed_chunk_ids=seeds,
                    query_vector=query_vector,
                    candidate_limit=self.candidate_limit,
                )
            except BackendError:
                graph = {"status": "error", "results": []}
            graph_ids: list[str] = []
            if graph.get("status") == "ok":
                for item in graph.get("results") or []:
                    if not isinstance(item, dict) or not item.get("chunk_id"):
                        continue
                    score = _score(item)
                    if score is None or score < self.relevance_threshold:
                        continue
                    chunk_id = str(item["chunk_id"])
                    graph_ids.append(chunk_id)
                    details.setdefault(chunk_id, dict(item))
                    routes_hit[chunk_id].add("graph")
                if graph_ids:
                    rankings.append((graph_ids, 0.6))

        scores = _rrf(rankings, self.rrf_k)
        ranked_ids = sorted(scores, key=scores.get, reverse=True)
        results = []
        for chunk_id in ranked_ids:
            item = details[chunk_id]
            item["rrf_score"] = round(scores[chunk_id], 6)
            item["routes"] = sorted(routes_hit[chunk_id])
            results.append(item)

        if not results:
            return {
                "status": "no_match",
                "results": [],
                "message": "知识库中没有找到与本次问题相关的内容。",
            }
        return {
            "status": "ok",
            "results": results,
            "message": f"找到 {len(results)} 条候选证据。",
        }


def _normalise_response(response: dict[str, Any], status: str) -> dict[str, Any]:
    defaults = {
        "empty": "当前用户的知识库尚无可检索内容。",
        "not_ready": "知识库仍在构建，当前尚无可检索内容。",
        "error": "知识库查询失败。",
    }
    return {
        "status": status,
        "results": response.get("results") if isinstance(response.get("results"), list) else [],
        "message": str(response.get("message") or defaults[status]),
    }


def format_context(response: dict[str, Any], max_chars: int = 1200) -> str:
    """便于调试展示；MCP 工具直接返回结构化响应。"""
    if response.get("status") != "ok":
        return str(response.get("message", ""))
    blocks = []
    for index, item in enumerate(response.get("results") or [], start=1):
        source = item.get("source") or item.get("document_id") or "未知来源"
        location = f"第 {item['page']} 页" if item.get("page") is not None else item.get("section")
        title = f"[{index}] {source}" + (f" · {location}" if location else "")
        blocks.append(f"{title}\n{str(item.get('text', ''))[:max_chars]}")
    return "\n\n".join(blocks)

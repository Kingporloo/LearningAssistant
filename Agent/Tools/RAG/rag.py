"""RAG 的建库算法入口与只读查询入口。

Python 生成向量和图结构；Java 负责文件管理、状态与数据库读写。
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from Agent.Tools.RAG.dataPraperation import EmbeddingModel, prepare_document
from Agent.Tools.RAG.indexBuding import build as build_graph
from Agent.Tools.RAG.resultRecall import Retriever
from Agent.Interface.BackendClient import BackendClient, RunContext


class RAGBuilder:
    """把 Java 提供的 Markdown 构建为可持久化的分块、向量和图。"""

    def __init__(
        self,
        allowed_root: str | Path,
        *,
        embeddings: EmbeddingModel | None = None,
        chunk_size: int = 2048,
        chunk_overlap: int = 256,
        similarity_topk: int = 3,
        similarity_threshold: float = 0.5,
    ) -> None:
        self.allowed_root = Path(allowed_root)
        self.embeddings = embeddings or EmbeddingModel()
        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap
        self.similarity_topk = similarity_topk
        self.similarity_threshold = similarity_threshold

    def build(
        self,
        *,
        document_id: str,
        file_ref: str,
        source_name: str | None = None,
    ) -> dict[str, Any]:
        rows = prepare_document(
            file_ref,
            allowed_root=self.allowed_root,
            document_id=document_id,
            source_name=source_name,
            chunk_size=self.chunk_size,
            chunk_overlap=self.chunk_overlap,
            embeddings=self.embeddings,
        )
        if not rows:
            return {
                "status": "empty",
                "document_id": document_id,
                "chunks": [],
                "graph": {"chunks": [], "next_chunk": [], "similar_to": []},
                "message": "文档中没有可构建的有效文本。",
            }
        return {
            "status": "ok",
            "document_id": document_id,
            "chunks": rows,
            "graph": build_graph(
                rows,
                similarity_topk=self.similarity_topk,
                similarity_threshold=self.similarity_threshold,
            ),
            "message": f"完成 {len(rows)} 个分块。",
        }


class RAGSystem:
    """面向 MCP Server 的用户范围内只读查询服务。"""

    def __init__(
        self,
        backend: BackendClient | None = None,
        *,
        embeddings: EmbeddingModel | None = None,
        candidate_limit: int = 20,
        relevance_threshold: float = 0.45,
        use_graph: bool = True,
    ) -> None:
        self.retriever = Retriever(
            backend or BackendClient.from_env(),
            embeddings=embeddings,
            candidate_limit=candidate_limit,
            relevance_threshold=relevance_threshold,
            use_graph=use_graph,
        )

    async def search(
        self,
        context: RunContext,
        query: str,
        *,
        expanded_queries: list[str] | None = None,
        hypothetical_doc: str | None = None,
    ) -> dict[str, Any]:
        return await self.retriever.recall(
            context,
            query,
            expanded_queries=expanded_queries,
            hypothetical_doc=hypothetical_doc,
        )

    async def aclose(self) -> None:
        await self.retriever.backend.aclose()

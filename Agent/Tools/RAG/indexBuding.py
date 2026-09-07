"""由 RAG 分块构造交给 Java 持久化的图数据。"""

from __future__ import annotations

from typing import Any

import numpy as np


def section_path(row: dict[str, Any]) -> str | None:
    parts = [str(row[key]) for key in ("h1", "h2", "h3") if row.get(key)]
    return " > ".join(parts) if parts else None


def build_graph(rows: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    """构造文档内部的结构关系；不执行任何数据库写入。"""
    chunks = sorted(rows, key=lambda row: int(row["chunk_index"]))
    nodes = [
        {
            "chunk_id": row["chunk_id"],
            "document_id": row["document_id"],
            "chunk_index": row["chunk_index"],
            "page": row.get("page"),
            "section": section_path(row),
        }
        for row in chunks
    ]
    next_chunk = [
        {"from": left["chunk_id"], "to": right["chunk_id"]}
        for left, right in zip(chunks, chunks[1:])
        if left["document_id"] == right["document_id"]
    ]
    return {"chunks": nodes, "next_chunk": next_chunk}


def compute_similarities(
    rows: list[dict[str, Any]],
    *,
    topk: int = 3,
    threshold: float = 0.5,
) -> list[dict[str, Any]]:
    """只在当前文档的分块之间生成相似关系。"""
    if len(rows) < 2 or topk < 1:
        return []
    matrix = np.asarray([row["vector"] for row in rows], dtype=np.float32)
    similarities = matrix @ matrix.T
    edges: dict[tuple[str, str], dict[str, Any]] = {}
    for left_index, left in enumerate(rows):
        order = np.argsort(-similarities[left_index])
        accepted = 0
        for right_index in order:
            if right_index == left_index:
                continue
            score = float(similarities[left_index, right_index])
            if score < threshold:
                continue
            right = rows[int(right_index)]
            key = tuple(sorted((str(left["chunk_id"]), str(right["chunk_id"]))))
            edges[key] = {"from": key[0], "to": key[1], "score": round(score, 6)}
            accepted += 1
            if accepted >= topk:
                break
    return list(edges.values())


def build(
    rows: list[dict[str, Any]],
    *,
    similarity_topk: int = 3,
    similarity_threshold: float = 0.5,
) -> dict[str, list[dict[str, Any]]]:
    graph = build_graph(rows)
    graph["similar_to"] = compute_similarities(
        rows,
        topk=similarity_topk,
        threshold=similarity_threshold,
    )
    return graph


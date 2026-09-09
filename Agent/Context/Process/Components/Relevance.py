"""Select 使用的统一向量与双路相关性计算。"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from math import isfinite, sqrt

from Agent.Context.Schemas.ContextUnit import ContextUnit, ContextUnitType


Vector = Sequence[float]
EmbeddingFunction = Callable[[Sequence[str]], Sequence[Vector]]
RelevanceFunction = Callable[[str, Sequence[str]], Sequence[float]]

_LONG_TERM_MEMORY = {
    ContextUnitType.SEMANTIC_MEMORY,
    ContextUnitType.EPISODIC_MEMORY,
}


@dataclass(slots=True)
class RelevanceResult:
    vectors: dict[str, list[float]]
    task_scores: dict[str, float]
    response_scores: dict[str, float]
    mode: str
    message: str | None = None


def calculate_relevance(
    *,
    candidates: Sequence[ContextUnit],
    mandatory: Sequence[ContextUnit],
    task_query: str,
    response_query: str | None,
    embed_texts: EmbeddingFunction,
    score_relevance: RelevanceFunction | None,
) -> RelevanceResult:
    units = list(candidates) + [
        unit for unit in mandatory if unit.id not in {item.id for item in candidates}
    ]
    query_texts = [task_query]
    if response_query:
        query_texts.append(response_query)
    encoded = _encode(
        embed_texts,
        [*query_texts, *(unit.text for unit in units)],
    )
    task_vector = encoded[0]
    response_vector = encoded[1] if response_query else None
    offset = len(query_texts)
    vectors = {
        unit.id: vector for unit, vector in zip(units, encoded[offset:])
    }

    texts = [unit.text for unit in candidates]
    mode = "dense"
    message: str | None = None
    if score_relevance is None:
        task_scores = {
            unit.id: normalised_cosine(task_vector, vectors[unit.id])
            for unit in candidates
        }
    else:
        try:
            scores = _scores(score_relevance(task_query, texts), len(texts))
            task_scores = dict(zip((unit.id for unit in candidates), scores))
            mode = "reranker"
        except Exception as exc:
            task_scores = {
                unit.id: normalised_cosine(task_vector, vectors[unit.id])
                for unit in candidates
            }
            mode = "dense_fallback"
            message = f"Reranker 不可用，已使用 Dense relevance: {exc}"

    preference_units = [unit for unit in candidates if _is_preference_memory(unit)]
    response_scores: dict[str, float] = {}
    if response_query and preference_units:
        if score_relevance is not None and mode == "reranker":
            try:
                scores = _scores(
                    score_relevance(
                        response_query,
                        [unit.text for unit in preference_units],
                    ),
                    len(preference_units),
                )
                response_scores = dict(
                    zip((unit.id for unit in preference_units), scores)
                )
            except Exception as exc:
                response_scores = {
                    unit.id: normalised_cosine(response_vector, vectors[unit.id])
                    for unit in preference_units
                }
                mode = "reranker+dense_fallback"
                message = f"Reranker 不可用，已使用 Dense relevance: {exc}"
        else:
            response_scores = {
                unit.id: normalised_cosine(response_vector, vectors[unit.id])
                for unit in preference_units
            }

    return RelevanceResult(
        vectors=vectors,
        task_scores=task_scores,
        response_scores=response_scores,
        mode=mode,
        message=message,
    )


def cosine(left: Vector, right: Vector) -> float:
    if len(left) != len(right):
        raise ValueError("参与相似度计算的 embedding 维度必须一致")
    left_norm = sqrt(sum(value * value for value in left))
    right_norm = sqrt(sum(value * value for value in right))
    if left_norm == 0 or right_norm == 0:
        return 0.0
    return max(
        -1.0,
        min(1.0, sum(a * b for a, b in zip(left, right)) / (left_norm * right_norm)),
    )


def normalised_cosine(left: Vector, right: Vector) -> float:
    return max(0.0, min(1.0, (1.0 + cosine(left, right)) / 2.0))


def _is_preference_memory(unit: ContextUnit) -> bool:
    if unit.type not in _LONG_TERM_MEMORY:
        return False
    routes = unit.source_ref.metadata.get("recall_routes", [])
    return isinstance(routes, list) and "preference" in routes


def _encode(embed_texts: EmbeddingFunction, texts: Sequence[str]) -> list[list[float]]:
    raw = embed_texts(list(texts))
    if isinstance(raw, (str, bytes)):
        raise TypeError("embed_texts 必须返回向量数组")
    try:
        rows = list(raw)
    except TypeError as exc:
        raise TypeError("embed_texts 必须返回向量数组") from exc
    if len(rows) != len(texts):
        raise ValueError("embedding 数量与输入文本数量不一致")

    vectors: list[list[float]] = []
    dimension: int | None = None
    for vector in rows:
        if isinstance(vector, (str, bytes)):
            raise TypeError("每个 embedding 必须是数值数组")
        try:
            values = list(vector)
        except TypeError as exc:
            raise TypeError("每个 embedding 必须是数值数组") from exc
        if not values or any(
            isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not isfinite(value)
            for value in values
        ):
            raise ValueError("embedding 必须包含有限数值")
        if dimension is None:
            dimension = len(values)
        elif len(values) != dimension:
            raise ValueError("embedding 维度必须一致")
        vectors.append([float(value) for value in values])
    return vectors


def _scores(raw: Sequence[float], expected: int) -> list[float]:
    if isinstance(raw, (str, bytes)):
        raise TypeError("Reranker 必须返回分数数组")
    try:
        scores = list(raw)
    except TypeError as exc:
        raise TypeError("Reranker 必须返回分数数组") from exc
    if len(scores) != expected:
        raise ValueError("Reranker 分数数量与候选数量不一致")
    if any(
        isinstance(score, bool)
        or not isinstance(score, (int, float))
        or not isfinite(score)
        or not 0 <= score <= 1
        for score in scores
    ):
        raise ValueError("Reranker 分数必须是 0 到 1 的有限数值")
    return [float(score) for score in scores]


__all__ = [
    "EmbeddingFunction",
    "RelevanceFunction",
    "RelevanceResult",
    "Vector",
    "calculate_relevance",
    "cosine",
    "normalised_cosine",
]

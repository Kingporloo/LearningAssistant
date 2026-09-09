"""Select 使用的 Facility Location 覆盖计算。"""

from __future__ import annotations

from collections.abc import Sequence

from Agent.Context.Process.Components.Relevance import Vector, cosine
from Agent.Context.Schemas.ContextUnit import ContextUnit


def initial_coverage(
    targets: Sequence[ContextUnit],
    mandatory: Sequence[ContextUnit],
    vectors: dict[str, list[float]],
) -> dict[str, float]:
    return {
        target.id: max(
            (
                similarity(vectors[target.id], vectors[unit.id])
                for unit in mandatory
                if unit.id in vectors
            ),
            default=0.0,
        )
        for target in targets
    }


def marginal_gain(
    targets: Sequence[ContextUnit],
    additions: Sequence[ContextUnit],
    vectors: dict[str, list[float]],
    coverage: dict[str, float],
    total_quality: float,
) -> float:
    return sum(
        (target.quality or 0.0)
        * max(
            0.0,
            max(
                similarity(vectors[target.id], vectors[unit.id])
                for unit in additions
            )
            - coverage[target.id],
        )
        for target in targets
    ) / total_quality


def update_coverage(
    targets: Sequence[ContextUnit],
    additions: Sequence[ContextUnit],
    vectors: dict[str, list[float]],
    coverage: dict[str, float],
) -> None:
    for target in targets:
        coverage[target.id] = max(
            coverage[target.id],
            max(
                similarity(vectors[target.id], vectors[unit.id])
                for unit in additions
            ),
        )


def similarity(left: Vector, right: Vector) -> float:
    return max(0.0, cosine(left, right))


__all__ = ["initial_coverage", "marginal_gain", "similarity", "update_coverage"]

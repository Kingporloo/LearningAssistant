"""Select 使用的候选 Importance、Freshness 与质量计算。"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from math import exp

from Agent.Context.Schemas.ContextUnit import ContextUnit, ContextUnitType


_LONG_TERM_MEMORY = {
    ContextUnitType.SEMANTIC_MEMORY,
    ContextUnitType.EPISODIC_MEMORY,
}


@dataclass(frozen=True, slots=True)
class QualityScore:
    relevance: float
    relevance_source: str
    importance: float
    importance_missing: bool
    freshness: float
    event_time_used: datetime | None
    quality: float


def calculate_quality(
    unit: ContextUnit,
    *,
    r_task: float,
    r_response: float | None,
    task_threshold: float,
    response_threshold: float,
    now: datetime,
    episodic_tau_days: float,
) -> QualityScore | None:
    passed: list[tuple[str, float]] = []
    if r_task >= task_threshold:
        passed.append(("task", r_task))
    if r_response is not None and r_response >= response_threshold:
        passed.append(("response", r_response))
    if not passed:
        return None

    relevance_source, relevance = max(passed, key=lambda item: item[1])
    importance, importance_missing = _importance(unit)
    freshness, event_time_used = _freshness(unit, now, episodic_tau_days)
    quality = relevance ** 0.6 * importance ** 0.3 * freshness ** 0.1
    return QualityScore(
        relevance=relevance,
        relevance_source=relevance_source,
        importance=importance,
        importance_missing=importance_missing,
        freshness=freshness,
        event_time_used=event_time_used,
        quality=quality,
    )


def _importance(unit: ContextUnit) -> tuple[float, bool]:
    if unit.type not in _LONG_TERM_MEMORY:
        return 1.0, False
    if unit.importance is None:
        return 0.6, True
    return unit.importance, False


def _freshness(
    unit: ContextUnit,
    now: datetime,
    episodic_tau_days: float,
) -> tuple[float, datetime | None]:
    if unit.type is not ContextUnitType.EPISODIC_MEMORY:
        return 1.0, None
    event_time = unit.event_time or unit.created_at
    if event_time is None:
        return 1.0, None
    age_seconds = max(0.0, (now - event_time).total_seconds())
    exponent = -age_seconds / (episodic_tau_days * 86_400.0)
    return exp(max(exponent, -745.0)), event_time


__all__ = ["QualityScore", "calculate_quality"]

"""在 token 预算内选择高相关、低重复的背景上下文。"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from math import isfinite

from Agent.Context.Process.Components.CandidateQuality import calculate_quality
from Agent.Context.Process.Components.Coverage import (
    initial_coverage,
    marginal_gain,
    update_coverage,
)
from Agent.Context.Process.Components.Relevance import (
    EmbeddingFunction,
    RelevanceFunction,
    calculate_relevance,
)

from Agent.Context.Schemas.ContextUnit import (
    ContextStatus,
    ContextUnit,
    ContextUnitType,
)


_SESSION_SCOPED = {
    ContextUnitType.DIALOGUE,
    ContextUnitType.SESSION_LEDGER,
    ContextUnitType.WORKING_STATE,
    ContextUnitType.TOOL_OBSERVATION,
    ContextUnitType.CODE_OR_FORMULA,
}


@dataclass(slots=True)
class CandidateTrace:
    candidate_id: str
    gate_result: bool = False
    rejection_reason: str | None = None
    importance: float | None = None
    importance_missing: bool = False
    r_task: float | None = None
    r_response: float | None = None
    relevance_source: str | None = None
    freshness: float | None = None
    event_time_used: datetime | None = None
    quality: float | None = None
    coverage_before: float | None = None
    marginal_gain: float | None = None
    token_cost: int | None = None
    efficiency: float | None = None
    selected: bool = False


@dataclass(slots=True)
class SelectResult:
    selected: list[ContextUnit]
    traces: dict[str, CandidateTrace]
    selected_tokens: int
    coverage_gain: float
    stop_reason: str
    relevance_mode: str | None = None
    message: str | None = None


def select(
    *,
    candidates: Sequence[ContextUnit],
    mandatory: Sequence[ContextUnit],
    user_id: str,
    session_id: str,
    task_query: str,
    token_budget: int,
    embed_texts: EmbeddingFunction,
    response_query: str | None = None,
    score_relevance: RelevanceFunction | None = None,
    now: datetime | None = None,
    task_threshold: float = 0.45,
    response_threshold: float = 0.45,
    min_gain_per_1k_tokens: float = 0.01,
    episodic_tau_days: float = 30.0,
) -> SelectResult:
    """过滤并选择背景候选，不修改 Mandatory，也不调用生成式模型。"""

    _validate_inputs(
        user_id=user_id,
        session_id=session_id,
        task_query=task_query,
        response_query=response_query,
        token_budget=token_budget,
        task_threshold=task_threshold,
        response_threshold=response_threshold,
        min_gain_per_1k_tokens=min_gain_per_1k_tokens,
        episodic_tau_days=episodic_tau_days,
    )
    current_time = now or datetime.now(timezone.utc)
    if current_time.tzinfo is None or current_time.utcoffset() is None:
        raise ValueError("now 必须包含时区")

    candidate_units = _unique_units(candidates)
    mandatory_units = _unique_units(mandatory)
    traces = {
        unit.id: CandidateTrace(candidate_id=unit.id) for unit in candidate_units
    }
    if not candidate_units:
        return _result(traces, "no_candidates")

    mandatory_ids = {unit.id for unit in mandatory_units}
    valid: dict[str, ContextUnit] = {}
    all_candidates = {unit.id: unit for unit in candidate_units}
    for unit in candidate_units:
        trace = traces[unit.id]
        if unit.id in mandatory_ids:
            trace.rejection_reason = "already_mandatory"
            continue
        reason = _gate_reason(unit, user_id, session_id, current_time)
        if reason is not None:
            trace.rejection_reason = reason
            continue
        trace.gate_result = True
        valid[unit.id] = unit

    if not valid:
        return _result(traces, "no_valid_candidates")

    try:
        relevance = calculate_relevance(
            candidates=list(valid.values()),
            mandatory=mandatory_units,
            task_query=task_query,
            response_query=response_query,
            embed_texts=embed_texts,
            score_relevance=score_relevance,
        )
    except Exception as exc:
        for unit in valid.values():
            traces[unit.id].rejection_reason = "embedding_unavailable"
        return _result(
            traces,
            "selection_failed",
            message=f"无法计算统一 embedding: {exc}",
        )

    for unit in valid.values():
        unit.embedding = list(relevance.vectors[unit.id])

    eligible: list[ContextUnit] = []
    for unit in valid.values():
        trace = traces[unit.id]
        trace.r_task = relevance.task_scores[unit.id]
        trace.r_response = relevance.response_scores.get(unit.id)
        score = calculate_quality(
            unit,
            r_task=trace.r_task,
            r_response=trace.r_response,
            task_threshold=task_threshold,
            response_threshold=response_threshold,
            now=current_time,
            episodic_tau_days=episodic_tau_days,
        )
        if score is None:
            trace.rejection_reason = "below_relevance_threshold"
            continue

        unit.relevance = score.relevance
        unit.freshness = score.freshness
        unit.quality = score.quality
        trace.importance = score.importance
        trace.importance_missing = score.importance_missing
        trace.relevance_source = score.relevance_source
        trace.freshness = score.freshness
        trace.event_time_used = score.event_time_used
        trace.quality = score.quality
        eligible.append(unit)

    if not eligible:
        return _result(
            traces,
            "no_relevant_candidates",
            relevance_mode=relevance.mode,
            message=relevance.message,
        )

    roots = _deduplicate(eligible, traces)
    group_members = _atomic_groups(candidate_units)
    complete_roots: list[ContextUnit] = []
    for root in roots:
        if _additions(
            root,
            all_candidates=all_candidates,
            valid=valid,
            group_members=group_members,
            included_ids=mandatory_ids,
        ) is None:
            traces[root.id].rejection_reason = "incomplete_dependency"
        else:
            complete_roots.append(root)
    roots = complete_roots
    if not roots:
        return _result(
            traces,
            "no_complete_candidate",
            relevance_mode=relevance.mode,
            message=relevance.message,
        )
    total_quality = sum(unit.quality or 0.0 for unit in roots)
    if total_quality <= 0:
        return _result(
            traces,
            "zero_quality",
            relevance_mode=relevance.mode,
            message=relevance.message,
        )

    coverage = initial_coverage(roots, mandatory_units, relevance.vectors)
    selected: list[ContextUnit] = []
    selected_ids: set[str] = set()
    remaining = token_budget
    total_gain = 0.0
    stop_reason = "all_candidates_covered"

    while True:
        best: tuple[float, float, int, ContextUnit, list[ContextUnit]] | None = None
        had_unselected = False
        had_complete = False
        had_fitting = False

        for order, root in enumerate(roots):
            if root.id in selected_ids:
                continue
            had_unselected = True
            additions = _additions(
                root,
                all_candidates=all_candidates,
                valid=valid,
                group_members=group_members,
                included_ids=mandatory_ids | selected_ids,
            )
            if additions is None:
                if traces[root.id].rejection_reason is None:
                    traces[root.id].rejection_reason = "incomplete_dependency"
                continue
            had_complete = True
            cost = sum(unit.token_count for unit in additions)
            if not additions or cost <= 0:
                continue
            if cost > remaining:
                continue
            had_fitting = True
            gain = marginal_gain(
                roots,
                additions,
                relevance.vectors,
                coverage,
                total_quality,
            )
            efficiency = 1000.0 * gain / cost
            proposal = (efficiency, gain, -order, root, additions)
            if best is None or proposal[:3] > best[:3]:
                best = proposal

        if not had_unselected:
            break
        if not had_complete:
            stop_reason = "no_complete_candidate"
            break
        if not had_fitting:
            stop_reason = "budget_exhausted"
            break
        if best is None or best[1] <= 0:
            stop_reason = "no_positive_gain"
            break
        if best[0] <= min_gain_per_1k_tokens:
            stop_reason = "low_gain"
            break

        efficiency, gain, _order, root, additions = best
        trace = traces[root.id]
        trace.coverage_before = coverage[root.id]
        trace.marginal_gain = gain
        trace.token_cost = sum(unit.token_count for unit in additions)
        trace.efficiency = efficiency
        for unit in additions:
            if unit.id not in selected_ids:
                selected.append(unit)
                selected_ids.add(unit.id)
                traces[unit.id].selected = True
                traces[unit.id].rejection_reason = None
        remaining -= trace.token_cost
        total_gain += gain
        update_coverage(roots, additions, relevance.vectors, coverage)

    for unit in roots:
        trace = traces[unit.id]
        if not trace.selected and trace.rejection_reason is None:
            trace.rejection_reason = stop_reason
    selected = [unit for unit in candidate_units if unit.id in selected_ids]
    return SelectResult(
        selected=selected,
        traces=traces,
        selected_tokens=sum(unit.token_count for unit in selected),
        coverage_gain=total_gain,
        stop_reason=stop_reason,
        relevance_mode=relevance.mode,
        message=relevance.message,
    )


def _gate_reason(
    unit: ContextUnit,
    user_id: str,
    session_id: str,
    now: datetime,
) -> str | None:
    if unit.type is ContextUnitType.TOOL_OBSERVATION:
        return "tool_result_not_selectable"
    if unit.status is not ContextStatus.ACTIVE:
        return f"status_{unit.status.value}"
    if unit.user_id != user_id:
        return "user_mismatch"
    if not unit.source_ref.exists:
        return "source_missing"
    if unit.type in _SESSION_SCOPED and unit.session_id != session_id:
        return "session_mismatch"

    metadata = unit.source_ref.metadata
    if metadata.get("version_valid") is False:
        return "version_stale"
    current_version = metadata.get("current_version")
    if current_version is not None:
        if (
            isinstance(current_version, bool)
            or not isinstance(current_version, int)
            or unit.source_ref.version is None
            or unit.source_ref.version != current_version
        ):
            return "version_stale"

    if unit.type is ContextUnitType.WORKING_STATE:
        expires_at = metadata.get("expires_at")
        if expires_at is not None:
            try:
                expires = _datetime(expires_at)
            except (TypeError, ValueError):
                return "invalid_expiry"
            if expires <= now:
                return "expired"
    return None


def _deduplicate(
    units: Sequence[ContextUnit],
    traces: dict[str, CandidateTrace],
) -> list[ContextUnit]:
    ranked = sorted(
        enumerate(units),
        key=lambda item: (-(item[1].quality or 0.0), item[0]),
    )
    source_keys: set[tuple[str, str, int | None]] = set()
    texts: set[str] = set()
    kept_units: list[ContextUnit] = []
    kept: list[tuple[int, ContextUnit]] = []
    for order, unit in ranked:
        source_key = (
            unit.source_ref.kind.value,
            unit.source_ref.ref_id,
            unit.source_ref.version,
        )
        text_key = " ".join(unit.text.split()).casefold()
        explicit_duplicate = any(
            kept_unit.id in unit.duplicates or unit.id in kept_unit.duplicates
            for kept_unit in kept_units
        )
        if source_key in source_keys or text_key in texts or explicit_duplicate:
            traces[unit.id].rejection_reason = "duplicate"
            continue
        source_keys.add(source_key)
        texts.add(text_key)
        kept_units.append(unit)
        kept.append((order, unit))
    return [unit for _order, unit in sorted(kept)]


def _atomic_groups(units: Sequence[ContextUnit]) -> dict[str, list[str]]:
    groups: dict[str, list[str]] = {}
    for unit in units:
        if unit.atomic_group_id is not None:
            groups.setdefault(unit.atomic_group_id, []).append(unit.id)
    return groups


def _additions(
    root: ContextUnit,
    *,
    all_candidates: dict[str, ContextUnit],
    valid: dict[str, ContextUnit],
    group_members: dict[str, list[str]],
    included_ids: set[str],
) -> list[ContextUnit] | None:
    required: set[str] = set()
    pending = [root.id]
    while pending:
        unit_id = pending.pop()
        if unit_id in included_ids or unit_id in required:
            continue
        unit = all_candidates.get(unit_id)
        if unit is None or unit_id not in valid:
            return None
        required.add(unit_id)
        pending.extend(unit.dependencies)
        if unit.atomic_group_id is not None:
            pending.extend(group_members.get(unit.atomic_group_id, ()))
    return [unit for unit in all_candidates.values() if unit.id in required]


def _datetime(value: object) -> datetime:
    if isinstance(value, str):
        value = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if not isinstance(value, datetime):
        raise TypeError("expires_at 必须是 datetime 或 ISO 8601 字符串")
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("expires_at 必须包含时区")
    return value


def _unique_units(units: Sequence[ContextUnit]) -> list[ContextUnit]:
    result: list[ContextUnit] = []
    seen: set[str] = set()
    for unit in units:
        if unit.id not in seen:
            result.append(unit)
            seen.add(unit.id)
    return result


def _validate_inputs(
    *,
    user_id: str,
    session_id: str,
    task_query: str,
    response_query: str | None,
    token_budget: int,
    task_threshold: float,
    response_threshold: float,
    min_gain_per_1k_tokens: float,
    episodic_tau_days: float,
) -> None:
    for value, name in (
        (user_id, "user_id"),
        (session_id, "session_id"),
        (task_query, "task_query"),
    ):
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"{name} 不能为空")
    if response_query is not None and (
        not isinstance(response_query, str) or not response_query.strip()
    ):
        raise ValueError("response_query 必须是非空字符串或 None")
    if isinstance(token_budget, bool) or not isinstance(token_budget, int):
        raise TypeError("token_budget 必须是整数")
    if token_budget < 0:
        raise ValueError("token_budget 不能小于 0")
    for value, name in (
        (task_threshold, "task_threshold"),
        (response_threshold, "response_threshold"),
    ):
        if (
            isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not isfinite(value)
            or not 0 <= value <= 1
        ):
            raise ValueError(f"{name} 必须是 0 到 1 的有限数值")
    for value, name in (
        (min_gain_per_1k_tokens, "min_gain_per_1k_tokens"),
        (episodic_tau_days, "episodic_tau_days"),
    ):
        if (
            isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not isfinite(value)
            or value <= 0
        ):
            raise ValueError(f"{name} 必须是正有限数值")


def _result(
    traces: dict[str, CandidateTrace],
    stop_reason: str,
    *,
    relevance_mode: str | None = None,
    message: str | None = None,
) -> SelectResult:
    return SelectResult(
        selected=[],
        traces=traces,
        selected_tokens=0,
        coverage_gain=0.0,
        stop_reason=stop_reason,
        relevance_mode=relevance_mode,
        message=message,
    )


__all__ = ["CandidateTrace", "SelectResult", "select"]

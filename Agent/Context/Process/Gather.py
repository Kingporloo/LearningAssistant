"""收集一次模型调用所需的上下文单元。"""

from __future__ import annotations

from collections.abc import Callable, Collection, Sequence
from dataclasses import dataclass
from typing import Any

from Agent.Context.Process.Components.MemoryRecall import MemoryRecall, gather_memory
from Agent.Context.Process.Components.ReferenceResolver import resolve_reference_ids
from Agent.Context.Schemas.ContextUnit import (
    Authority,
    ContextUnit,
    ContextUnitType,
    Fidelity,
    SessionSummary,
    SourceKind,
    SourceRef,
)
from Agent.Context.Schemas.Ledger import (
    LedgerEntry,
    LedgerEntryType,
    LedgerStatus,
    SessionLedger,
)


_MANDATORY_LEDGER_TYPES = {
    LedgerEntryType.CONSTRAINT,
    LedgerEntryType.ACTIVE_EXAMPLE,
    LedgerEntryType.TOOL_STATE,
}


@dataclass(slots=True)
class GatherResult:
    mandatory: list[ContextUnit]
    candidates: list[ContextUnit]
    compactable: list[ContextUnit]
    protected_ids: set[str]
    reference_ids: list[str]
    memory: MemoryRecall


async def gather(
    *,
    current_query: ContextUnit,
    mcp_client: Any,
    count_tokens: Callable[[str], int],
    dialogue_turns: Sequence[tuple[ContextUnit, ContextUnit]] = (),
    execution_units: Sequence[ContextUnit] = (),
    protected_execution_ids: Collection[str] = (),
    session_summary: SessionSummary | None = None,
    session_ledger: SessionLedger | None = None,
    keep_recent_turns: int = 5,
    cached_memory: MemoryRecall | None = None,
) -> GatherResult:
    """收集上下文，不执行选择、压缩、持久化或模型生成。"""

    _validate_current_query(current_query)
    if isinstance(keep_recent_turns, bool) or not isinstance(keep_recent_turns, int):
        raise TypeError("keep_recent_turns 必须是整数")
    if keep_recent_turns < 1:
        raise ValueError("keep_recent_turns 必须大于 0")

    mandatory: list[ContextUnit] = []
    candidates: list[ContextUnit] = []
    compactable: list[ContextUnit] = []
    protected_ids = {current_query.id}

    if session_summary is not None:
        summary_unit = _summary_unit(session_summary, current_query, count_tokens)
        mandatory.append(summary_unit)
        compactable.append(summary_unit)

    older_count = max(0, len(dialogue_turns) - keep_recent_turns)
    history_units: list[ContextUnit] = []
    for index, turn in enumerate(dialogue_turns):
        user_message, assistant_message = _validate_dialogue_turn(turn)
        _bind_dialogue_turn(user_message, assistant_message)
        history_units.extend((user_message, assistant_message))
        if index < older_count:
            candidates.extend((user_message, assistant_message))
            compactable.extend((user_message, assistant_message))
        else:
            mandatory.extend((user_message, assistant_message))
            protected_ids.update((user_message.id, assistant_message.id))

    reference_ids = resolve_reference_ids(current_query.text, history_units)
    if reference_ids:
        reference_set = set(reference_ids)
        promoted = [unit for unit in candidates if unit.id in reference_set]
        mandatory.extend(promoted)
        protected_ids.update(reference_set)
        candidates = [unit for unit in candidates if unit.id not in reference_set]

    if session_ledger is not None:
        for entry in session_ledger.entries:
            if entry.status is not LedgerStatus.ACTIVE:
                continue
            unit = _ledger_unit(entry, current_query, count_tokens)
            if entry.type in _MANDATORY_LEDGER_TYPES:
                mandatory.append(unit)
                protected_ids.add(unit.id)
            else:
                candidates.append(unit)

    protected_execution = set(protected_execution_ids)
    for unit in execution_units:
        mandatory.append(unit)
        if unit.id in protected_execution:
            protected_ids.add(unit.id)
        else:
            compactable.append(unit)

    mandatory.append(current_query)
    memory = await gather_memory(
        current_query,
        mcp_client,
        count_tokens,
        session_ledger=session_ledger,
        cached=cached_memory,
    )
    candidates.extend(memory.units)
    compactable.extend(memory.units)

    mandatory = _unique_units(mandatory)
    mandatory_ids = {unit.id for unit in mandatory}
    candidates = [
        unit for unit in _unique_units(candidates) if unit.id not in mandatory_ids
    ]
    compactable = [
        unit
        for unit in _unique_units(compactable)
        if unit.id not in protected_ids
    ]
    return GatherResult(
        mandatory=mandatory,
        candidates=candidates,
        compactable=compactable,
        protected_ids=protected_ids,
        reference_ids=reference_ids,
        memory=memory,
    )


def _summary_unit(
    summary: SessionSummary,
    current_query: ContextUnit,
    count_tokens: Callable[[str], int],
) -> ContextUnit:
    return ContextUnit(
        id=f"session-summary:{summary.version}",
        type=ContextUnitType.DIALOGUE,
        text=summary.text,
        source_ref=SourceRef(
            kind=SourceKind.DERIVED,
            ref_id=f"session-summary:{summary.version}",
            session_id=current_query.session_id,
            version=summary.version,
            metadata={
                "through_message_id": summary.through_message_id,
                "source_refs": [_source_ref_data(ref) for ref in summary.source_refs],
            },
        ),
        token_count=_count(summary.text, count_tokens),
        user_id=current_query.user_id,
        session_id=current_query.session_id,
        authority=Authority.DERIVED,
        fidelity=Fidelity.SUMMARIZED,
    )


def _ledger_unit(
    entry: LedgerEntry,
    current_query: ContextUnit,
    count_tokens: Callable[[str], int],
) -> ContextUnit:
    return ContextUnit(
        id=f"ledger:{entry.id}",
        type=ContextUnitType.SESSION_LEDGER,
        text=entry.content,
        source_ref=SourceRef(
            kind=SourceKind.LEDGER_ENTRY,
            ref_id=entry.id,
            session_id=current_query.session_id,
            metadata={
                "entry_type": entry.type.value,
                "source_refs": list(entry.source_refs),
                "exact_payload": entry.exact_payload,
            },
        ),
        token_count=_count(entry.content, count_tokens),
        user_id=current_query.user_id,
        session_id=current_query.session_id,
        created_at=entry.created_at,
        event_time=entry.updated_at,
        authority=Authority.DERIVED,
        fidelity=(
            Fidelity.EXACT if entry.exact_payload is not None else Fidelity.EXTRACTED
        ),
    )


def _validate_current_query(unit: ContextUnit) -> None:
    if unit.type is not ContextUnitType.DIALOGUE or unit.authority is not Authority.USER:
        raise ValueError("current_query 必须是 authority=user 的 dialogue ContextUnit")
    if unit.session_id is None:
        raise ValueError("current_query 必须包含 session_id")


def _validate_dialogue_turn(
    turn: tuple[ContextUnit, ContextUnit],
) -> tuple[ContextUnit, ContextUnit]:
    if not isinstance(turn, tuple) or len(turn) != 2:
        raise ValueError("每轮对话必须是 (用户查询, 最终答案) 二元组")
    user_message, assistant_message = turn
    if (
        not isinstance(user_message, ContextUnit)
        or user_message.type is not ContextUnitType.DIALOGUE
        or user_message.authority is not Authority.USER
        or not isinstance(assistant_message, ContextUnit)
        or assistant_message.type is not ContextUnitType.DIALOGUE
        or assistant_message.authority is not Authority.ASSISTANT
    ):
        raise ValueError("对话轮次必须由用户 dialogue 和助手最终 dialogue 组成")
    return user_message, assistant_message


def _bind_dialogue_turn(
    user_message: ContextUnit,
    assistant_message: ContextUnit,
) -> None:
    groups = {
        group
        for group in (user_message.atomic_group_id, assistant_message.atomic_group_id)
        if group is not None
    }
    if len(groups) > 1:
        raise ValueError("同一轮问答不能属于不同的 atomic_group_id")
    group_id = next(iter(groups), f"dialogue-turn:{user_message.id}:{assistant_message.id}")
    user_message.atomic_group_id = group_id
    assistant_message.atomic_group_id = group_id


def _unique_units(units: Sequence[ContextUnit]) -> list[ContextUnit]:
    result: list[ContextUnit] = []
    seen: set[str] = set()
    for unit in units:
        if unit.id not in seen:
            result.append(unit)
            seen.add(unit.id)
    return result


def _source_ref_data(ref: SourceRef) -> dict[str, Any]:
    return {
        "kind": ref.kind.value,
        "ref_id": ref.ref_id,
        "session_id": ref.session_id,
        "version": ref.version,
        "exists": ref.exists,
        "metadata": dict(ref.metadata),
    }


def _count(text: str, count_tokens: Callable[[str], int]) -> int:
    value = count_tokens(text)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("count_tokens 必须返回非负整数")
    return value


__all__ = ["GatherResult", "MemoryRecall", "gather"]

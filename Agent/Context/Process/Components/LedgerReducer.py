"""应用已有结构化 Ledger 操作，不从自然语言推断会话状态。"""

from __future__ import annotations

import hashlib
import json
from dataclasses import replace
from datetime import datetime, timezone
from typing import Any

from Agent.Context.Schemas.Ledger import (
    LedgerEntry,
    LedgerOperation,
    LedgerOperationType,
    LedgerScope,
    LedgerStatus,
    SessionLedger,
)


def operations_from_tool_result(
    result: Any,
    *,
    source_ref: str,
    now: datetime | None = None,
) -> list[LedgerOperation]:
    """读取工具明确返回的 ledger_operations；不存在时不产生任何状态。"""

    if not isinstance(result, dict) or "ledger_operations" not in result:
        return []
    raw_operations = result["ledger_operations"]
    if not isinstance(raw_operations, list):
        raise ValueError("ledger_operations 必须是数组")
    timestamp = now or datetime.now(timezone.utc)
    operations: list[LedgerOperation] = []
    for index, raw in enumerate(raw_operations):
        if not isinstance(raw, dict):
            raise ValueError("ledger_operations 只能包含对象")
        operation_type = LedgerOperationType(raw.get("op"))
        if operation_type is LedgerOperationType.ADD:
            operations.append(LedgerOperation(
                op=operation_type,
                entry=_entry(raw.get("entry"), source_ref, index, timestamp),
            ))
        elif operation_type is LedgerOperationType.UPDATE:
            operations.append(LedgerOperation(
                op=operation_type,
                entry_id=_text(raw.get("entry_id"), "entry_id"),
                content=raw.get("content"),
                exact_payload=raw.get("exact_payload"),
                source_refs=[source_ref],
            ))
        elif operation_type is LedgerOperationType.RESOLVE:
            operations.append(LedgerOperation(
                op=operation_type,
                entry_id=_text(raw.get("entry_id"), "entry_id"),
                source_refs=[source_ref],
            ))
        else:
            entry_id = _text(raw.get("entry_id"), "entry_id")
            entry = _entry(raw.get("entry"), source_ref, index, timestamp)
            if entry_id not in entry.supersedes:
                entry.supersedes.append(entry_id)
            operations.append(LedgerOperation(
                op=operation_type,
                entry_id=entry_id,
                entry=entry,
                source_refs=[source_ref],
            ))
    return operations


def reduce_ledger(
    ledger: SessionLedger,
    operations: list[LedgerOperation],
    *,
    now: datetime | None = None,
) -> SessionLedger:
    timestamp = now or datetime.now(timezone.utc)
    entries = {entry.id: replace(entry) for entry in ledger.entries}
    order = [entry.id for entry in ledger.entries]
    for operation in operations:
        if operation.op is LedgerOperationType.ADD:
            assert operation.entry is not None
            if operation.entry.id in entries:
                raise ValueError("Ledger ADD 的条目 id 已存在")
            entries[operation.entry.id] = replace(operation.entry)
            order.append(operation.entry.id)
            continue

        assert operation.entry_id is not None
        current = entries.get(operation.entry_id)
        if current is None:
            raise ValueError("Ledger 操作引用了不存在的条目")
        if operation.op is LedgerOperationType.UPDATE:
            entries[current.id] = replace(
                current,
                content=operation.content or current.content,
                exact_payload=(
                    operation.exact_payload
                    if operation.exact_payload is not None
                    else current.exact_payload
                ),
                source_refs=_merge_refs(current.source_refs, operation.source_refs),
                updated_at=timestamp,
            )
        elif operation.op is LedgerOperationType.RESOLVE:
            entries[current.id] = replace(
                current,
                status=LedgerStatus.RESOLVED,
                source_refs=_merge_refs(current.source_refs, operation.source_refs),
                updated_at=timestamp,
            )
        else:
            assert operation.entry is not None
            if operation.entry.id in entries:
                raise ValueError("Ledger SUPERSEDE 的新条目 id 已存在")
            entries[current.id] = replace(
                current,
                status=LedgerStatus.SUPERSEDED,
                source_refs=_merge_refs(current.source_refs, operation.source_refs),
                updated_at=timestamp,
            )
            entries[operation.entry.id] = replace(operation.entry)
            order.append(operation.entry.id)
    return SessionLedger(
        version=ledger.version,
        compacted_through_message_id=ledger.compacted_through_message_id,
        entries=[entries[entry_id] for entry_id in order],
    )


def operation_data(operation: LedgerOperation) -> dict[str, Any]:
    value: dict[str, Any] = {"op": operation.op.value}
    if operation.entry_id is not None:
        value["entry_id"] = operation.entry_id
    if operation.entry is not None:
        value["entry"] = _entry_data(operation.entry)
    if operation.content is not None:
        value["content"] = operation.content
    if operation.exact_payload is not None:
        value["exact_payload"] = operation.exact_payload
    if operation.source_refs:
        value["source_refs"] = list(operation.source_refs)
    return value


def _entry(
    raw: Any,
    source_ref: str,
    index: int,
    timestamp: datetime,
) -> LedgerEntry:
    if not isinstance(raw, dict):
        raise ValueError("Ledger entry 必须是对象")
    entry_type = _text(raw.get("type"), "entry.type")
    content = _text(raw.get("content"), "entry.content")
    entry_id = raw.get("id")
    if entry_id is None:
        canonical = json.dumps(raw, ensure_ascii=False, sort_keys=True, default=str)
        digest = hashlib.sha256(f"{source_ref}:{index}:{canonical}".encode()).hexdigest()[:24]
        entry_id = f"ledger:{digest}"
    return LedgerEntry(
        id=_text(entry_id, "entry.id"),
        type=entry_type,
        content=content,
        source_refs=[source_ref],
        exact_payload=raw.get("exact_payload"),
        scope=LedgerScope(raw.get("scope", LedgerScope.SESSION.value)),
        supersedes=[str(value) for value in raw.get("supersedes", [])],
        created_at=timestamp,
        updated_at=timestamp,
    )


def _entry_data(entry: LedgerEntry) -> dict[str, Any]:
    return {
        "id": entry.id,
        "type": entry.type.value,
        "content": entry.content,
        "source_refs": list(entry.source_refs),
        "exact_payload": entry.exact_payload,
        "status": entry.status.value,
        "scope": entry.scope.value,
        "supersedes": list(entry.supersedes),
        "created_at": entry.created_at.isoformat(),
        "updated_at": entry.updated_at.isoformat(),
    }


def _merge_refs(left: list[str], right: list[str]) -> list[str]:
    return list(dict.fromkeys([*left, *right]))


def _text(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} 不能为空")
    return value.strip()


__all__ = ["operation_data", "operations_from_tool_result", "reduce_ledger"]

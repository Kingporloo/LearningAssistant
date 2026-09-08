"""Session Ledger、条目和增量补丁的数据模型。"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import StrEnum
from typing import Any


class LedgerEntryType(StrEnum):
    GOAL = "goal"
    CONSTRAINT = "constraint"
    DECISION = "decision"
    EXPLAINED = "explained"
    USER_FEEDBACK = "user_feedback"
    ACTIVE_EXAMPLE = "active_example"
    OPEN_QUESTION = "open_question"
    RESOLVED_QUESTION = "resolved_question"
    TOOL_STATE = "tool_state"
    POINTER = "pointer"


class LedgerStatus(StrEnum):
    ACTIVE = "active"
    RESOLVED = "resolved"
    SUPERSEDED = "superseded"


class LedgerScope(StrEnum):
    SESSION = "session"
    THREAD = "thread"
    TASK = "task"


class LedgerOperationType(StrEnum):
    ADD = "add"
    UPDATE = "update"
    RESOLVE = "resolve"
    SUPERSEDE = "supersede"


@dataclass(slots=True)
class LedgerEntry:
    id: str
    type: LedgerEntryType
    content: str
    source_refs: list[str]
    exact_payload: dict[str, Any] | None = None
    status: LedgerStatus = LedgerStatus.ACTIVE
    scope: LedgerScope = LedgerScope.SESSION
    supersedes: list[str] = field(default_factory=list)
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def __post_init__(self) -> None:
        self.id = _required_text(self.id, "entry.id")
        self.type = LedgerEntryType(self.type)
        self.content = _required_text(self.content, "entry.content")
        self.status = LedgerStatus(self.status)
        self.scope = LedgerScope(self.scope)
        self.source_refs = _unique_refs(self.source_refs, "entry.source_refs", required=True)
        self.supersedes = _unique_refs(self.supersedes, "entry.supersedes")
        if self.id in self.supersedes:
            raise ValueError("entry.supersedes 不能包含条目自身")
        if self.exact_payload is not None:
            if not isinstance(self.exact_payload, dict):
                raise TypeError("entry.exact_payload 必须是 dict 或 None")
            self.exact_payload = dict(self.exact_payload)
        _aware_datetime(self.created_at, "entry.created_at")
        _aware_datetime(self.updated_at, "entry.updated_at")
        if self.updated_at < self.created_at:
            raise ValueError("entry.updated_at 不能早于 created_at")


@dataclass(slots=True)
class SessionLedger:
    version: int = 0
    compacted_through_message_id: str | None = None
    entries: list[LedgerEntry] = field(default_factory=list)

    def __post_init__(self) -> None:
        if isinstance(self.version, bool) or not isinstance(self.version, int):
            raise TypeError("ledger.version 必须是整数")
        if self.version < 0:
            raise ValueError("ledger.version 不能小于 0")
        self.compacted_through_message_id = _optional_text(
            self.compacted_through_message_id,
            "ledger.compacted_through_message_id",
        )
        if not isinstance(self.entries, list):
            raise TypeError("ledger.entries 必须是 list")
        entries = list(self.entries)
        if any(not isinstance(entry, LedgerEntry) for entry in entries):
            raise TypeError("ledger.entries 只能包含 LedgerEntry")
        ids = [entry.id for entry in entries]
        if len(ids) != len(set(ids)):
            raise ValueError("ledger.entries 的 id 不能重复")
        self.entries = entries


@dataclass(slots=True)
class LedgerOperation:
    op: LedgerOperationType
    entry_id: str | None = None
    entry: LedgerEntry | None = None
    content: str | None = None
    exact_payload: dict[str, Any] | None = None
    source_refs: list[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        self.op = LedgerOperationType(self.op)
        self.entry_id = _optional_text(self.entry_id, "operation.entry_id")
        self.content = _optional_text(self.content, "operation.content")
        if self.entry is not None and not isinstance(self.entry, LedgerEntry):
            raise TypeError("operation.entry 必须是 LedgerEntry 或 None")
        if self.exact_payload is not None:
            if not isinstance(self.exact_payload, dict):
                raise TypeError("operation.exact_payload 必须是 dict 或 None")
            self.exact_payload = dict(self.exact_payload)
        self.source_refs = _unique_refs(self.source_refs, "operation.source_refs")

        if self.op is LedgerOperationType.ADD:
            if (
                self.entry is None
                or self.entry_id is not None
                or self.content is not None
                or self.exact_payload is not None
            ):
                raise ValueError("ADD 只提供 entry")
            return

        if self.entry_id is None:
            raise ValueError(f"{self.op.value.upper()} 必须提供 entry_id")
        if self.op is LedgerOperationType.UPDATE:
            if self.entry is not None:
                raise ValueError("UPDATE 不能提供 entry")
            if self.content is None and self.exact_payload is None:
                raise ValueError("UPDATE 必须提供 content 或 exact_payload")
            if not self.source_refs:
                raise ValueError("UPDATE 必须提供 source_refs")
            return
        if self.op is LedgerOperationType.RESOLVE:
            if (
                self.entry is not None
                or self.content is not None
                or self.exact_payload is not None
            ):
                raise ValueError("RESOLVE 只提供 entry_id 和 source_refs")
            if not self.source_refs:
                raise ValueError("RESOLVE 必须提供 source_refs")
            return

        if self.entry is None:
            raise ValueError(f"{self.op.value.upper()} 必须提供 entry")
        if self.content is not None or self.exact_payload is not None:
            raise ValueError("SUPERSEDE 只提供旧 entry_id、新 entry 和可选 source_refs")
        if (
            self.op is LedgerOperationType.SUPERSEDE
            and self.entry_id not in self.entry.supersedes
        ):
            raise ValueError("SUPERSEDE 的新 entry 必须在 supersedes 中引用旧 entry_id")


@dataclass(slots=True)
class LedgerPatch:
    base_version: int
    operations: list[LedgerOperation] = field(default_factory=list)
    compacted_through_message_id: str | None = None

    def __post_init__(self) -> None:
        if isinstance(self.base_version, bool) or not isinstance(self.base_version, int):
            raise TypeError("patch.base_version 必须是整数")
        if self.base_version < 0:
            raise ValueError("patch.base_version 不能小于 0")
        if not isinstance(self.operations, list):
            raise TypeError("patch.operations 必须是 list")
        operations = list(self.operations)
        if any(not isinstance(operation, LedgerOperation) for operation in operations):
            raise TypeError("patch.operations 只能包含 LedgerOperation")
        self.operations = operations
        self.compacted_through_message_id = _optional_text(
            self.compacted_through_message_id,
            "patch.compacted_through_message_id",
        )
        if not self.operations and self.compacted_through_message_id is None:
            raise ValueError("LedgerPatch 必须包含操作或覆盖位置更新")


def _required_text(value: str, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} 不能为空")
    return value.strip()


def _optional_text(value: str | None, name: str) -> str | None:
    if value is None:
        return None
    return _required_text(value, name)


def _unique_refs(values: list[str], name: str, *, required: bool = False) -> list[str]:
    if not isinstance(values, list):
        raise TypeError(f"{name} 必须是 list")
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        ref = _required_text(value, name)
        if ref not in seen:
            result.append(ref)
            seen.add(ref)
    if required and not result:
        raise ValueError(f"{name} 不能为空")
    return result


def _aware_datetime(value: datetime, name: str) -> None:
    if not isinstance(value, datetime):
        raise TypeError(f"{name} 必须是 datetime")
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError(f"{name} 必须包含时区")


__all__ = [
    "LedgerEntry",
    "LedgerEntryType",
    "LedgerOperation",
    "LedgerOperationType",
    "LedgerPatch",
    "LedgerScope",
    "LedgerStatus",
    "SessionLedger",
]

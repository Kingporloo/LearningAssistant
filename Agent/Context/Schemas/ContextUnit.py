"""上下文候选的统一数据模型。"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import StrEnum
from math import isfinite
from typing import Any


class ContextUnitType(StrEnum):
    DIALOGUE = "dialogue"
    SESSION_LEDGER = "session_ledger"
    SEMANTIC_MEMORY = "semantic_memory"
    EPISODIC_MEMORY = "episodic_memory"
    DOCUMENT_EVIDENCE = "document_evidence"
    TOOL_OBSERVATION = "tool_observation"
    WORKING_STATE = "working_state"
    CODE_OR_FORMULA = "code_or_formula"


class SourceKind(StrEnum):
    MESSAGE = "message"
    LEDGER_ENTRY = "ledger_entry"
    MEMORY = "memory"
    DOCUMENT_CHUNK = "document_chunk"
    TOOL_EVENT = "tool_event"
    WORKING_STATE = "working_state"
    DERIVED = "derived"


class Authority(StrEnum):
    USER = "user"
    DOCUMENT = "document"
    TOOL = "tool"
    ASSISTANT = "assistant"
    DERIVED = "derived"


class Fidelity(StrEnum):
    EXACT = "exact"
    EXTRACTED = "extracted"
    SUMMARIZED = "summarized"
    INFERRED = "inferred"


class ContextStatus(StrEnum):
    ACTIVE = "active"
    DELETED = "deleted"
    SUPERSEDED = "superseded"
    STALE = "stale"
    ERROR = "error"
    UNKNOWN = "unknown"


@dataclass(slots=True)
class SourceRef:
    """指向权威来源；ref_id 使用来源自身的稳定标识。"""

    kind: SourceKind
    ref_id: str
    session_id: str | None = None
    version: int | None = None
    exists: bool = True
    metadata: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        self.kind = SourceKind(self.kind)
        self.ref_id = _required_text(self.ref_id, "source_ref.ref_id")
        self.session_id = _optional_text(self.session_id, "source_ref.session_id")
        if self.version is not None:
            if isinstance(self.version, bool) or not isinstance(self.version, int):
                raise TypeError("source_ref.version 必须是整数或 None")
            if self.version < 0:
                raise ValueError("source_ref.version 不能小于 0")
        if not isinstance(self.exists, bool):
            raise TypeError("source_ref.exists 必须是 bool")
        if not isinstance(self.metadata, dict):
            raise TypeError("source_ref.metadata 必须是 dict")
        self.metadata = dict(self.metadata)


@dataclass(slots=True)
class SessionSummary:
    """Java 已保存并可供 ContextBuilder 使用的会话摘要。"""

    version: int
    text: str
    through_message_id: str | None = None
    source_refs: list[SourceRef] = field(default_factory=list)

    def __post_init__(self) -> None:
        if isinstance(self.version, bool) or not isinstance(self.version, int):
            raise TypeError("summary.version 必须是整数")
        if self.version < 0:
            raise ValueError("summary.version 不能小于 0")
        self.text = _required_text(self.text, "summary.text")
        self.through_message_id = _optional_text(
            self.through_message_id,
            "summary.through_message_id",
        )
        if not isinstance(self.source_refs, list):
            raise TypeError("summary.source_refs 必须是 list")
        refs = list(self.source_refs)
        if any(not isinstance(ref, SourceRef) for ref in refs):
            raise TypeError("summary.source_refs 只能包含 SourceRef")
        self.source_refs = refs


@dataclass(slots=True)
class ContextUnit:
    """Gather、Select、Structure 和 Compact 共享的最小信息单元。"""

    id: str
    type: ContextUnitType
    text: str
    source_ref: SourceRef
    token_count: int
    user_id: str
    authority: Authority
    fidelity: Fidelity
    status: ContextStatus = ContextStatus.ACTIVE
    session_id: str | None = None
    created_at: datetime | None = None
    event_time: datetime | None = None
    importance: float | None = None
    atomic_group_id: str | None = None
    dependencies: list[str] = field(default_factory=list)
    supersedes: list[str] = field(default_factory=list)
    duplicates: list[str] = field(default_factory=list)
    embedding: list[float] | None = None
    retrieval_scores: dict[str, float] = field(default_factory=dict)
    relevance: float | None = None
    freshness: float | None = None
    quality: float | None = None

    def __post_init__(self) -> None:
        self.id = _required_text(self.id, "id")
        self.type = ContextUnitType(self.type)
        self.text = _required_text(self.text, "text")
        if not isinstance(self.source_ref, SourceRef):
            raise TypeError("source_ref 必须是 SourceRef")
        if isinstance(self.token_count, bool) or not isinstance(self.token_count, int):
            raise TypeError("token_count 必须是整数")
        if self.token_count < 0:
            raise ValueError("token_count 不能小于 0")
        self.user_id = _required_text(self.user_id, "user_id")
        self.authority = Authority(self.authority)
        self.fidelity = Fidelity(self.fidelity)
        self.status = ContextStatus(self.status)
        self.session_id = _optional_text(self.session_id, "session_id")
        self.atomic_group_id = _optional_text(self.atomic_group_id, "atomic_group_id")
        _aware_datetime(self.created_at, "created_at")
        _aware_datetime(self.event_time, "event_time")

        if self.importance is not None:
            _unit_score(self.importance, "importance")
            if self.type not in {
                ContextUnitType.SEMANTIC_MEMORY,
                ContextUnitType.EPISODIC_MEMORY,
            }:
                raise ValueError("importance 只属于 semantic_memory 或 episodic_memory")
            self.importance = float(self.importance)

        self.dependencies = _ids(self.dependencies, "dependencies", self.id)
        self.supersedes = _ids(self.supersedes, "supersedes", self.id)
        self.duplicates = _ids(self.duplicates, "duplicates", self.id)

        if self.embedding is not None:
            if not self.embedding:
                raise ValueError("embedding 不能为空数组")
            if any(
                isinstance(value, bool)
                or not isinstance(value, (int, float))
                or not isfinite(value)
                for value in self.embedding
            ):
                raise ValueError("embedding 只能包含有限数值")
            self.embedding = [float(value) for value in self.embedding]

        if not isinstance(self.retrieval_scores, dict):
            raise TypeError("retrieval_scores 必须是 dict")
        scores: dict[str, float] = {}
        for name, value in self.retrieval_scores.items():
            key = _required_text(name, "retrieval_scores key")
            if (
                isinstance(value, bool)
                or not isinstance(value, (int, float))
                or not isfinite(value)
            ):
                raise ValueError("retrieval_scores 只能包含有限数值")
            scores[key] = float(value)
        self.retrieval_scores = scores

        for name in ("relevance", "freshness", "quality"):
            value = getattr(self, name)
            if value is not None:
                _unit_score(value, name)
                setattr(self, name, float(value))


def _required_text(value: str, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} 不能为空")
    return value.strip()


def _optional_text(value: str | None, name: str) -> str | None:
    if value is None:
        return None
    return _required_text(value, name)


def _aware_datetime(value: datetime | None, name: str) -> None:
    if value is not None and (value.tzinfo is None or value.utcoffset() is None):
        raise ValueError(f"{name} 必须包含时区")


def _unit_score(value: float, name: str) -> None:
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not isfinite(value)
        or not 0 <= value <= 1
    ):
        raise ValueError(f"{name} 必须是 0 到 1 的有限数值")


def _ids(values: list[str], name: str, own_id: str) -> list[str]:
    if not isinstance(values, list):
        raise TypeError(f"{name} 必须是 list")
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        item = _required_text(value, name)
        if item == own_id:
            raise ValueError(f"{name} 不能引用 ContextUnit 自身")
        if item not in seen:
            result.append(item)
            seen.add(item)
    return result


__all__ = [
    "Authority",
    "ContextStatus",
    "ContextUnit",
    "ContextUnitType",
    "Fidelity",
    "SessionSummary",
    "SourceKind",
    "SourceRef",
]

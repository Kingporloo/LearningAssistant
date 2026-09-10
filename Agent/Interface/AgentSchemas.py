"""Java 调用 Agent 运行接口时使用的网络数据模型。"""

from __future__ import annotations

from collections.abc import Callable
from datetime import datetime
from typing import Annotated, Any, Literal

from langchain_core.messages import AIMessage, BaseMessage, ToolMessage
from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

from Agent.Context.ContextBuider import ContextState
from Agent.Context.Schemas.Config import ContextConfig
from Agent.Context.Schemas.ContextUnit import (
    Authority,
    ContextStatus,
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
    LedgerScope,
    LedgerStatus,
    SessionLedger,
)
from Agent.Interface.BackendClient import RunContext
from Agent.Loop.Models import AgentRunInput


NonEmptyString = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]
ToolOutcome = Literal["completed", "error", "unknown", "skipped"]


class _RequestModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class AgentSessionRequest(_RequestModel):
    user_id: NonEmptyString


class AgentConfigRequest(_RequestModel):
    model_window: int = Field(gt=0)
    max_context_tokens: int = Field(gt=0)
    output_reserve: int = Field(ge=0)
    safety_margin: int = Field(ge=0)
    tool_result_reserve: int = Field(default=0, ge=0)
    compact_trigger_ratio: float = Field(default=0.92, gt=0, lt=1)
    summary_max_tokens: int = Field(default=2_000, gt=0)
    keep_recent_turns: int = Field(default=5, gt=0)

    @model_validator(mode="after")
    def validate_budget(self) -> "AgentConfigRequest":
        self.to_context_config()
        return self

    def to_context_config(self) -> ContextConfig:
        return ContextConfig(**self.model_dump())


class MessageSnapshot(_RequestModel):
    message_id: NonEmptyString
    content: NonEmptyString
    created_at: datetime | None = None


class DialogueTurnSnapshot(_RequestModel):
    user_message: MessageSnapshot
    assistant_message: MessageSnapshot


class ToolCallSnapshot(_RequestModel):
    tool_call_id: NonEmptyString
    name: NonEmptyString
    arguments: dict[str, Any] = Field(default_factory=dict)


class ToolResultSnapshot(_RequestModel):
    tool_call_id: NonEmptyString
    name: NonEmptyString
    content: str
    outcome: ToolOutcome = "completed"
    business_status: str | None = None


class ExecutionGroupSnapshot(_RequestModel):
    group_id: NonEmptyString
    assistant_content: str = ""
    tool_calls: list[ToolCallSnapshot] = Field(min_length=1)
    tool_results: list[ToolResultSnapshot] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_complete_group(self) -> "ExecutionGroupSnapshot":
        calls = {item.tool_call_id: item.name for item in self.tool_calls}
        results = {item.tool_call_id: item.name for item in self.tool_results}
        if len(calls) != len(self.tool_calls):
            raise ValueError("execution_history 的工具调用 id 不能重复")
        if len(results) != len(self.tool_results):
            raise ValueError("execution_history 的工具结果 id 不能重复")
        if calls != results:
            raise ValueError("每个历史工具调用必须有且只有一个同名工具结果")
        return self


class SourceRefSnapshot(_RequestModel):
    kind: SourceKind
    ref_id: NonEmptyString
    session_id: NonEmptyString | None = None
    version: int | None = Field(default=None, ge=0)
    exists: bool = True
    metadata: dict[str, Any] = Field(default_factory=dict)

    def to_source_ref(self) -> SourceRef:
        return SourceRef(
            kind=self.kind,
            ref_id=self.ref_id,
            session_id=self.session_id,
            version=self.version,
            exists=self.exists,
            metadata=self.metadata,
        )


class SessionSummarySnapshot(_RequestModel):
    version: int = Field(ge=0)
    text: NonEmptyString
    through_message_id: NonEmptyString | None = None
    source_refs: list[SourceRefSnapshot] = Field(default_factory=list)

    def to_session_summary(self) -> SessionSummary:
        return SessionSummary(
            version=self.version,
            text=self.text,
            through_message_id=self.through_message_id,
            source_refs=[item.to_source_ref() for item in self.source_refs],
        )


class LedgerEntrySnapshot(_RequestModel):
    id: NonEmptyString
    type: LedgerEntryType
    content: NonEmptyString
    source_refs: list[NonEmptyString] = Field(min_length=1)
    exact_payload: dict[str, Any] | None = None
    status: LedgerStatus = LedgerStatus.ACTIVE
    scope: LedgerScope = LedgerScope.SESSION
    supersedes: list[NonEmptyString] = Field(default_factory=list)
    created_at: datetime
    updated_at: datetime

    def to_ledger_entry(self) -> LedgerEntry:
        return LedgerEntry(
            id=self.id,
            type=self.type,
            content=self.content,
            source_refs=self.source_refs,
            exact_payload=self.exact_payload,
            status=self.status,
            scope=self.scope,
            supersedes=self.supersedes,
            created_at=self.created_at,
            updated_at=self.updated_at,
        )


class SessionLedgerSnapshot(_RequestModel):
    version: int = Field(default=0, ge=0)
    compacted_through_message_id: NonEmptyString | None = None
    entries: list[LedgerEntrySnapshot] = Field(default_factory=list)

    def to_session_ledger(self) -> SessionLedger:
        return SessionLedger(
            version=self.version,
            compacted_through_message_id=self.compacted_through_message_id,
            entries=[item.to_ledger_entry() for item in self.entries],
        )


class AgentRunRequest(_RequestModel):
    user_id: NonEmptyString
    session_id: NonEmptyString
    request_id: NonEmptyString
    message_id: NonEmptyString
    message: NonEmptyString
    recent_history: list[DialogueTurnSnapshot] = Field(default_factory=list)
    execution_history: list[ExecutionGroupSnapshot] = Field(default_factory=list)
    history_cursor: NonEmptyString | None = None
    session_summary: SessionSummarySnapshot | None = None
    session_ledger: SessionLedgerSnapshot | None = None
    agent_config: AgentConfigRequest

    @model_validator(mode="after")
    def validate_stable_ids(self) -> "AgentRunRequest":
        message_ids = [
            message.message_id
            for turn in self.recent_history
            for message in (turn.user_message, turn.assistant_message)
        ]
        if self.message_id in message_ids or len(message_ids) != len(set(message_ids)):
            raise ValueError("当前消息和 recent_history 的 message_id 不能重复")

        group_ids = [group.group_id for group in self.execution_history]
        if len(group_ids) != len(set(group_ids)):
            raise ValueError("execution_history 的 group_id 不能重复")
        call_ids = [
            call.tool_call_id
            for group in self.execution_history
            for call in group.tool_calls
        ]
        if len(call_ids) != len(set(call_ids)):
            raise ValueError("execution_history 的 tool_call_id 必须全局唯一")
        return self

    def to_agent_run_input(
        self,
        context: RunContext,
        count_tokens: Callable[[str], int],
    ) -> AgentRunInput:
        dialogue_turns, execution, execution_units = _context_snapshot(
            self.recent_history,
            self.execution_history,
            context,
            count_tokens,
        )
        return AgentRunInput(
            run_context=context,
            message=self.message,
            dialogue_turns=dialogue_turns,
            execution_history=execution,
            execution_units=execution_units,
            session_summary=(
                self.session_summary.to_session_summary()
                if self.session_summary is not None
                else None
            ),
            session_ledger=(
                self.session_ledger.to_session_ledger()
                if self.session_ledger is not None
                else None
            ),
            history_cursor=self.history_cursor,
        )


class AgentCompactRequest(_RequestModel):
    user_id: NonEmptyString
    session_id: NonEmptyString
    request_id: NonEmptyString
    recent_history: list[DialogueTurnSnapshot] = Field(default_factory=list)
    execution_history: list[ExecutionGroupSnapshot] = Field(default_factory=list)
    history_cursor: NonEmptyString | None = None
    session_summary: SessionSummarySnapshot | None = None
    session_ledger: SessionLedgerSnapshot | None = None
    agent_config: AgentConfigRequest

    @model_validator(mode="after")
    def validate_stable_ids(self) -> "AgentCompactRequest":
        _validate_history_ids(self.recent_history, self.execution_history)
        return self

    def to_context_state(
        self,
        context: RunContext,
        count_tokens: Callable[[str], int],
        *,
        system_prompt: str,
    ) -> ContextState:
        dialogue_turns, execution, execution_units = _context_snapshot(
            self.recent_history,
            self.execution_history,
            context,
            count_tokens,
        )
        control_text = "用户请求手动压缩当前会话上下文。"
        control_id = f"manual-compact:{context.request_id}"
        current_query = ContextUnit(
            id=control_id,
            type=ContextUnitType.DIALOGUE,
            text=control_text,
            source_ref=SourceRef(
                kind=SourceKind.DERIVED,
                ref_id=control_id,
                session_id=context.session_id,
            ),
            token_count=_count(control_text, count_tokens),
            user_id=context.user_id,
            session_id=context.session_id,
            authority=Authority.USER,
            fidelity=Fidelity.EXACT,
        )
        return ContextState(
            run_context=context,
            current_query=current_query,
            system_prompt=system_prompt,
            dialogue_turns=dialogue_turns,
            execution_units=execution_units,
            execution=execution,
            session_summary=(
                self.session_summary.to_session_summary()
                if self.session_summary is not None
                else None
            ),
            session_ledger=(
                self.session_ledger.to_session_ledger()
                if self.session_ledger is not None
                else None
            ),
            history_cursor=self.history_cursor,
            compact_operation_id=f"compact:{context.request_id}",
            recall_memory=False,
        )


def _context_snapshot(
    dialogue: list[DialogueTurnSnapshot],
    groups: list[ExecutionGroupSnapshot],
    context: RunContext,
    count_tokens: Callable[[str], int],
) -> tuple[
    list[tuple[ContextUnit, ContextUnit]],
    list[BaseMessage],
    list[ContextUnit],
]:
    dialogue_turns = [
        _dialogue_turn(turn, context, count_tokens)
        for turn in dialogue
    ]
    execution: list[BaseMessage] = []
    execution_units: list[ContextUnit] = []
    for group in groups:
        messages, units = _execution_group(group, context, count_tokens)
        execution.extend(messages)
        execution_units.extend(units)
    return dialogue_turns, execution, execution_units


def _validate_history_ids(
    dialogue: list[DialogueTurnSnapshot],
    groups: list[ExecutionGroupSnapshot],
) -> None:
    message_ids = [
        message.message_id
        for turn in dialogue
        for message in (turn.user_message, turn.assistant_message)
    ]
    if len(message_ids) != len(set(message_ids)):
        raise ValueError("recent_history 的 message_id 不能重复")

    group_ids = [group.group_id for group in groups]
    if len(group_ids) != len(set(group_ids)):
        raise ValueError("execution_history 的 group_id 不能重复")
    call_ids = [call.tool_call_id for group in groups for call in group.tool_calls]
    if len(call_ids) != len(set(call_ids)):
        raise ValueError("execution_history 的 tool_call_id 必须全局唯一")


def _dialogue_turn(
    turn: DialogueTurnSnapshot,
    context: RunContext,
    count_tokens: Callable[[str], int],
) -> tuple[ContextUnit, ContextUnit]:
    group_id = (
        f"dialogue-turn:{turn.user_message.message_id}:"
        f"{turn.assistant_message.message_id}"
    )
    return (
        _dialogue_unit(
            turn.user_message,
            context,
            count_tokens,
            Authority.USER,
            group_id,
        ),
        _dialogue_unit(
            turn.assistant_message,
            context,
            count_tokens,
            Authority.ASSISTANT,
            group_id,
        ),
    )


def _dialogue_unit(
    message: MessageSnapshot,
    context: RunContext,
    count_tokens: Callable[[str], int],
    authority: Authority,
    group_id: str,
) -> ContextUnit:
    return ContextUnit(
        id=f"message:{message.message_id}",
        type=ContextUnitType.DIALOGUE,
        text=message.content,
        source_ref=SourceRef(
            kind=SourceKind.MESSAGE,
            ref_id=message.message_id,
            session_id=context.session_id,
        ),
        token_count=_count(message.content, count_tokens),
        user_id=context.user_id,
        session_id=context.session_id,
        created_at=message.created_at,
        authority=authority,
        fidelity=Fidelity.EXACT,
        atomic_group_id=group_id,
    )


def _execution_group(
    group: ExecutionGroupSnapshot,
    context: RunContext,
    count_tokens: Callable[[str], int],
) -> tuple[list[BaseMessage], list[ContextUnit]]:
    calls = [
        {
            "id": call.tool_call_id,
            "name": call.name,
            "args": call.arguments,
            "type": "tool_call",
        }
        for call in group.tool_calls
    ]
    messages: list[BaseMessage] = [
        AIMessage(content=group.assistant_content, tool_calls=calls)
    ]
    units: list[ContextUnit] = []
    atomic_group_id = f"tool-group:{group.group_id}"
    for result in group.tool_results:
        message_status = (
            "success"
            if result.outcome == "completed"
            and (result.business_status or "").lower() not in {"error", "unknown"}
            else "error"
        )
        messages.append(ToolMessage(
            content=result.content,
            tool_call_id=result.tool_call_id,
            name=result.name,
            status=message_status,
        ))
        status = ContextStatus.ACTIVE
        if result.outcome == "unknown" or (result.business_status or "").lower() == "unknown":
            status = ContextStatus.UNKNOWN
        elif message_status == "error":
            status = ContextStatus.ERROR
        units.append(ContextUnit(
            id=f"tool-result:{group.group_id}:{result.tool_call_id}",
            type=ContextUnitType.TOOL_OBSERVATION,
            text=result.content,
            source_ref=SourceRef(
                kind=SourceKind.TOOL_EVENT,
                ref_id=result.tool_call_id,
                session_id=context.session_id,
                metadata={
                    "group_id": group.group_id,
                    "tool_name": result.name,
                    "business_status": result.business_status,
                    "outcome": result.outcome,
                },
            ),
            token_count=_count(result.content, count_tokens),
            user_id=context.user_id,
            session_id=context.session_id,
            authority=Authority.TOOL,
            fidelity=Fidelity.EXACT,
            status=status,
            atomic_group_id=atomic_group_id,
        ))
    return messages, units


def _count(text: str, count_tokens: Callable[[str], int]) -> int:
    value = count_tokens(text)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("count_tokens 必须返回非负整数")
    return value


__all__ = [
    "AgentCompactRequest",
    "AgentConfigRequest",
    "AgentRunRequest",
    "AgentSessionRequest",
    "DialogueTurnSnapshot",
    "ExecutionGroupSnapshot",
    "LedgerEntrySnapshot",
    "MessageSnapshot",
    "SessionLedgerSnapshot",
    "SessionSummarySnapshot",
    "SourceRefSnapshot",
    "ToolCallSnapshot",
    "ToolResultSnapshot",
]

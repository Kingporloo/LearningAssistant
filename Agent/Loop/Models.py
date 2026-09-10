"""AgentLoop 的输入、运行依赖和图状态。"""

from __future__ import annotations

from collections.abc import Collection, Sequence
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Literal, TypedDict

from langchain_core.messages import AIMessage, BaseMessage

from Agent.Context.ContextBuider import ContextBuildResult, ContextBuilder
from Agent.Context.Process.Components.MemoryRecall import MemoryRecall
from Agent.Context.Schemas.ContextUnit import ContextUnit, SessionSummary
from Agent.Context.Schemas.Ledger import SessionLedger
from Agent.Interface.BackendClient import RunContext

if TYPE_CHECKING:
    from Agent.Assistant import Assistant
    from Agent.Tools.MCP.MCPClient import BoundMCPClient


@dataclass(frozen=True, slots=True)
class AgentRunInput:
    """Java 已绑定身份后交给一次 Agent 运行的数据。"""

    run_context: RunContext
    message: str
    dialogue_turns: Sequence[tuple[ContextUnit, ContextUnit]] = ()
    execution_history: Sequence[BaseMessage] = ()
    execution_units: Sequence[ContextUnit] = ()
    protected_execution_ids: Collection[str] = ()
    session_summary: SessionSummary | None = None
    session_ledger: SessionLedger | None = None
    history_cursor: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.run_context, RunContext):
            raise TypeError("run_context 必须是 RunContext")
        if (
            not isinstance(self.run_context.message_id, str)
            or not self.run_context.message_id.strip()
        ):
            raise ValueError("AgentRunInput 要求 Java 提供 message_id")
        if not isinstance(self.message, str) or not self.message.strip():
            raise ValueError("message 不能为空")

        object.__setattr__(self, "message", self.message.strip())
        object.__setattr__(self, "dialogue_turns", tuple(self.dialogue_turns))
        object.__setattr__(self, "execution_history", tuple(self.execution_history))
        object.__setattr__(self, "execution_units", tuple(self.execution_units))
        object.__setattr__(
            self,
            "protected_execution_ids",
            frozenset(self.protected_execution_ids),
        )


@dataclass(frozen=True, slots=True)
class AgentEvent:
    """Interface 可直接编码为 SSE 的运行事件。"""

    type: str
    request_id: str
    session_id: str
    event_seq: int
    payload: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        return {
            "type": self.type,
            "request_id": self.request_id,
            "session_id": self.session_id,
            "event_seq": self.event_seq,
            "payload": dict(self.payload),
        }


@dataclass(slots=True)
class RunDependencies:
    assistant: Assistant
    context_builder: ContextBuilder
    mcp_client: BoundMCPClient
    tools: tuple[Any, ...]
    max_tool_rounds: int
    system_prompt: str


RunStatus = Literal["running", "completed", "failed"]
NextAction = Literal[
    "call_model",
    "execute_tools",
    "reject_tool_calls",
    "finish_run",
    "fail_run",
]


class AgentRunState(TypedDict):
    current_query: ContextUnit
    dialogue_turns: tuple[tuple[ContextUnit, ContextUnit], ...]
    execution: list[BaseMessage]
    execution_units: list[ContextUnit]
    protected_execution_ids: set[str]
    unread_result_ids: set[str]
    protocol_required_ids: set[str]
    cached_memory: MemoryRecall | None
    session_summary: SessionSummary | None
    session_ledger: SessionLedger | None
    history_cursor: str | None
    context_revision: int
    context_result: ContextBuildResult | None
    request_consumed_result_ids: set[str]
    assistant_message: AIMessage | None
    model_step: int
    tool_rounds: int
    tools_disabled: bool
    final_answer: str | None
    status: RunStatus
    next_action: NextAction
    error_code: str | None
    error_message: str | None
    error_phase: str | None
    retryable: bool
    usage: dict[str, int]


__all__ = [
    "AgentEvent",
    "AgentRunInput",
    "AgentRunState",
    "NextAction",
    "RunDependencies",
    "RunStatus",
]

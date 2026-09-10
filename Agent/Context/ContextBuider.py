"""GSSC 上下文构建的唯一顶层入口。"""

from __future__ import annotations

from collections.abc import Callable, Collection, Sequence
from dataclasses import dataclass, replace
from typing import Any, Literal

from langchain_core.messages import BaseMessage
from langchain_core.runnables import Runnable

from Agent.Context.Process.Compact import CompactResult, compact
from Agent.Context.Process.Components.MemoryRecall import MemoryRecall
from Agent.Context.Process.Components.PromptRenderer import (
    DEFAULT_REACT_TEMPLATE,
    render_tools,
)
from Agent.Context.Process.Components.Relevance import (
    EmbeddingFunction,
    RelevanceFunction,
)
from Agent.Context.Process.Gather import GatherResult, gather
from Agent.Context.Process.Select import SelectResult, select
from Agent.Context.Process.Structure import (
    StructureMode,
    StructureResult,
    structure,
)
from Agent.Context.Schemas.Budget import ContextBudget
from Agent.Context.Schemas.Config import ContextConfig
from Agent.Context.Schemas.ContextUnit import ContextUnit, SessionSummary, SourceRef
from Agent.Context.Schemas.Ledger import SessionLedger
from Agent.Interface.BackendClient import (
    BackendClient,
    BackendError,
    BackendOutcomeUnknown,
    RunContext,
)


BuildStatus = Literal["ready", "overflow"]
SummarySaveStatus = Literal["not_attempted", "saved", "failed", "unknown"]
CountRequest = Callable[[StructureResult], int]


@dataclass(slots=True)
class ContextState:
    run_context: RunContext
    current_query: ContextUnit
    system_prompt: str
    dialogue_turns: Sequence[tuple[ContextUnit, ContextUnit]] = ()
    execution_units: Sequence[ContextUnit] = ()
    protected_execution_ids: Collection[str] = ()
    execution: Sequence[BaseMessage] | Sequence[str] = ()
    session_summary: SessionSummary | None = None
    session_ledger: SessionLedger | None = None
    cached_memory: MemoryRecall | None = None
    tools: Sequence[Any] = ()
    mode: StructureMode = "native"
    react_template: str = DEFAULT_REACT_TEMPLATE
    history_cursor: str | None = None
    compact_operation_id: str | None = None
    recall_memory: bool = True

    def __post_init__(self) -> None:
        if self.current_query.user_id != self.run_context.user_id:
            raise ValueError("current_query.user_id 与 RunContext 不一致")
        if self.current_query.session_id != self.run_context.session_id:
            raise ValueError("current_query.session_id 与 RunContext 不一致")


@dataclass(slots=True)
class ContextBuildResult:
    status: BuildStatus
    request: StructureResult | None
    input_tokens: int
    before_compact_tokens: int
    budget: ContextBudget
    gathered: GatherResult
    selection: SelectResult
    compact_result: CompactResult | None = None
    summary_save_status: SummarySaveStatus = "not_attempted"
    session_summary: SessionSummary | None = None
    reason: str | None = None


@dataclass(slots=True)
class _PreparedContext:
    request: StructureResult
    input_tokens: int
    budget: ContextBudget
    gathered: GatherResult
    selection: SelectResult


class ContextBuilder:
    """Call Gather、Select、Structure 并在需要时协调 Compact。"""

    def __init__(
        self,
        *,
        config: ContextConfig,
        mcp_client: Any,
        count_tokens: Callable[[str], int],
        count_request: CountRequest,
        embed_texts: EmbeddingFunction,
        summary_model: Runnable[Any, BaseMessage] | None = None,
        backend_client: BackendClient | None = None,
        summary_model_window: int | None = None,
        score_relevance: RelevanceFunction | None = None,
    ) -> None:
        self.config = config
        self.mcp_client = mcp_client
        self.count_tokens = count_tokens
        self.count_request = count_request
        self.embed_texts = embed_texts
        self.summary_model = summary_model
        self.backend_client = backend_client
        self.summary_model_window = (
            config.model_window if summary_model_window is None else summary_model_window
        )
        if (
            isinstance(self.summary_model_window, bool)
            or not isinstance(self.summary_model_window, int)
            or self.summary_model_window <= 0
        ):
            raise ValueError("summary_model_window 必须是正整数")
        self.score_relevance = score_relevance

    async def build_context(
        self,
        state: ContextState,
        *,
        force_compact: bool = False,
    ) -> ContextBuildResult:
        prepared = await self._prepare(state)
        before_compact_tokens = prepared.input_tokens
        should_compact = (
            force_compact
            or prepared.budget.reaches_compact_threshold(prepared.input_tokens)
            or not prepared.budget.fits_input(prepared.input_tokens)
        )
        if not should_compact:
            return self._finish(
                prepared,
                before_compact_tokens,
                session_summary=state.session_summary,
            )

        compactable = self._active_compactable(prepared, state)
        attempt, save_status, saved_summary, reason = await self._compact_and_save(
            state,
            prepared,
            compactable,
        )
        if save_status == "saved" and saved_summary is not None:
            state = replace(
                state,
                session_summary=saved_summary,
                cached_memory=prepared.gathered.memory,
            )
            prepared = await self._prepare(state)

        return self._finish(
            prepared,
            before_compact_tokens,
            compact_result=attempt,
            save_status=save_status,
            session_summary=saved_summary or state.session_summary,
            reason=reason,
        )

    async def compact_context(self, state: ContextState) -> ContextBuildResult:
        """手动压缩入口，使用与自动压缩相同的保护与保存规则。"""

        return await self.build_context(state, force_compact=True)

    async def _prepare(self, state: ContextState) -> _PreparedContext:
        gathered = await gather(
            current_query=state.current_query,
            mcp_client=self.mcp_client,
            count_tokens=self.count_tokens,
            dialogue_turns=state.dialogue_turns,
            execution_units=state.execution_units,
            protected_execution_ids=state.protected_execution_ids,
            session_summary=state.session_summary,
            session_ledger=state.session_ledger,
            keep_recent_turns=self.config.keep_recent_turns,
            cached_memory=state.cached_memory,
            recall_memory=state.recall_memory,
        )
        budget = self._budget(state, gathered.mandatory)
        selection = select(
            candidates=gathered.candidates,
            mandatory=gathered.mandatory,
            user_id=state.run_context.user_id,
            session_id=state.run_context.session_id,
            task_query=gathered.memory.queries.get("task", state.current_query.text),
            response_query=gathered.memory.queries.get("preference"),
            token_budget=max(0, budget.selectable_budget),
            embed_texts=self.embed_texts,
            score_relevance=self.score_relevance,
        )
        request = structure(
            mode=state.mode,
            system_prompt=state.system_prompt,
            current_query=state.current_query,
            mandatory=gathered.mandatory,
            selected=selection.selected,
            tools=state.tools,
            execution=state.execution,
            react_template=state.react_template,
        )
        input_tokens = self.count_request(request)
        _token_count(input_tokens, "count_request")
        return _PreparedContext(
            request=request,
            input_tokens=input_tokens,
            budget=budget,
            gathered=gathered,
            selection=selection,
        )

    def _budget(
        self,
        state: ContextState,
        mandatory: Sequence[ContextUnit],
    ) -> ContextBudget:
        system_tokens = _count(state.system_prompt, self.count_tokens)
        tool_schema_tokens = (
            _count(render_tools(state.tools), self.count_tokens) if state.tools else 0
        )
        mandatory_tokens = sum(
            unit.token_count for unit in _unique_units(mandatory)
        )
        return ContextBudget(
            model_window=self.config.model_window,
            max_context_tokens=self.config.max_context_tokens,
            output_reserve=self.config.output_reserve,
            system_tokens=system_tokens,
            tool_schema_tokens=tool_schema_tokens,
            tool_result_reserve=(
                self.config.tool_result_reserve if state.tools else 0
            ),
            safety_margin=self.config.safety_margin,
            mandatory_tokens=mandatory_tokens,
            compact_trigger_ratio=self.config.compact_trigger_ratio,
        )

    def _active_compactable(
        self,
        prepared: _PreparedContext,
        state: ContextState,
    ) -> list[ContextUnit]:
        included = set(prepared.request.included_unit_ids)
        execution_ids = {unit.id for unit in state.execution_units}
        return [
            unit
            for unit in prepared.gathered.compactable
            if unit.id in included and unit.id not in execution_ids
        ]

    async def _compact_and_save(
        self,
        state: ContextState,
        prepared: _PreparedContext,
        compactable: Sequence[ContextUnit],
    ) -> tuple[
        CompactResult | None,
        SummarySaveStatus,
        SessionSummary | None,
        str | None,
    ]:
        if not compactable:
            result = CompactResult(
                status="skipped",
                text=None,
                source_refs=[],
                replaced_tokens=0,
                input_tokens=0,
                output_tokens=0,
                reason="no_active_compactable_content",
            )
            return result, "not_attempted", None, result.reason
        if self.summary_model is None:
            return None, "not_attempted", None, "summary_model_unavailable"
        if self.backend_client is None:
            return None, "not_attempted", None, "summary_store_unavailable"
        if not state.compact_operation_id:
            return None, "not_attempted", None, "compact_operation_id_required"

        preserved_tokens = max(
            0,
            prepared.input_tokens - sum(unit.token_count for unit in compactable),
        )
        available_output = prepared.budget.input_limit - preserved_tokens
        if available_output <= 0:
            result = CompactResult(
                status="skipped",
                text=None,
                source_refs=[],
                replaced_tokens=sum(unit.token_count for unit in compactable),
                input_tokens=0,
                output_tokens=0,
                reason="no_summary_output_budget",
            )
            return result, "not_attempted", None, result.reason

        result = await compact(
            model=self.summary_model,
            units=compactable,
            count_tokens=self.count_tokens,
            max_output_tokens=min(self.config.summary_max_tokens, available_output),
            model_window=self.summary_model_window,
            safety_margin=self.config.safety_margin,
        )
        if result.status != "success" or result.text is None:
            return result, "not_attempted", None, result.reason

        through_message_id = _compacted_dialogue_cursor(
            state,
            {unit.id for unit in compactable},
        )
        try:
            response = await self.backend_client.context_summary_store(
                state.run_context,
                operation_id=state.compact_operation_id,
                base_version=(state.session_summary.version if state.session_summary else 0),
                history_cursor=state.history_cursor,
                through_message_id=through_message_id,
                source_refs=[_source_ref_data(ref) for ref in result.source_refs],
                text=result.text,
            )
        except BackendOutcomeUnknown as exc:
            return result, "unknown", None, str(exc)
        except BackendError as exc:
            return result, "failed", None, str(exc)

        if response.get("status") != "saved":
            status = "unknown" if response.get("status") == "unknown" else "failed"
            return result, status, None, str(response.get("message") or "summary_save_failed")
        try:
            saved = _session_summary(response.get("session_summary"))
        except (TypeError, ValueError) as exc:
            return result, "failed", None, f"invalid_saved_summary: {exc}"
        return result, "saved", saved, None

    def _finish(
        self,
        prepared: _PreparedContext,
        before_compact_tokens: int,
        *,
        compact_result: CompactResult | None = None,
        save_status: SummarySaveStatus = "not_attempted",
        session_summary: SessionSummary | None = None,
        reason: str | None = None,
    ) -> ContextBuildResult:
        fits = prepared.budget.fits_input(prepared.input_tokens)
        return ContextBuildResult(
            status="ready" if fits else "overflow",
            request=prepared.request if fits else None,
            input_tokens=prepared.input_tokens,
            before_compact_tokens=before_compact_tokens,
            budget=prepared.budget,
            gathered=prepared.gathered,
            selection=prepared.selection,
            compact_result=compact_result,
            summary_save_status=save_status,
            session_summary=session_summary,
            reason=reason if fits else reason or "context_input_overflow",
        )


def _compacted_dialogue_cursor(
    state: ContextState,
    compacted_ids: set[str],
) -> str | None:
    cursor = (
        state.session_summary.through_message_id
        if state.session_summary is not None
        else None
    )
    for user_message, assistant_message in state.dialogue_turns:
        if {user_message.id, assistant_message.id} <= compacted_ids:
            cursor = assistant_message.source_ref.ref_id
        else:
            break
    return cursor


def _session_summary(raw: Any) -> SessionSummary:
    if not isinstance(raw, dict):
        raise TypeError("session_summary 必须是对象")
    refs = raw.get("source_refs", [])
    if not isinstance(refs, list):
        raise TypeError("session_summary.source_refs 必须是数组")
    return SessionSummary(
        version=raw.get("version"),
        text=raw.get("text"),
        through_message_id=raw.get("through_message_id"),
        source_refs=[SourceRef(**ref) for ref in refs],
    )


def _source_ref_data(ref: SourceRef) -> dict[str, Any]:
    return {
        "kind": ref.kind.value,
        "ref_id": ref.ref_id,
        "session_id": ref.session_id,
        "version": ref.version,
        "exists": ref.exists,
        "metadata": dict(ref.metadata),
    }


def _unique_units(units: Sequence[ContextUnit]) -> list[ContextUnit]:
    result: list[ContextUnit] = []
    seen: set[str] = set()
    for unit in units:
        if unit.id not in seen:
            result.append(unit)
            seen.add(unit.id)
    return result


def _count(text: str, count_tokens: Callable[[str], int]) -> int:
    value = count_tokens(text)
    _token_count(value, "count_tokens")
    return value


def _token_count(value: int, name: str) -> None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{name} 必须返回非负整数")


__all__ = [
    "BuildStatus",
    "ContextBuildResult",
    "ContextBuilder",
    "ContextState",
    "CountRequest",
    "SummarySaveStatus",
]

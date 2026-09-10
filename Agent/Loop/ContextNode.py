"""AgentLoop 的上下文构建节点。"""

from __future__ import annotations

from typing import Any

from langgraph.runtime import Runtime

from Agent.Context.ContextBuider import ContextState
from Agent.Loop.Models import AgentRunState, RunDependencies


async def build_context(
    state: AgentRunState,
    runtime: Runtime[RunDependencies],
) -> dict[str, Any]:
    dependencies = runtime.context
    protected_ids = (
        state["protected_execution_ids"]
        | state["unread_result_ids"]
        | state["protocol_required_ids"]
    )
    context_state = ContextState(
        run_context=dependencies.mcp_client.context,
        current_query=state["current_query"],
        system_prompt=dependencies.system_prompt,
        dialogue_turns=state["dialogue_turns"],
        execution_units=state["execution_units"],
        protected_execution_ids=protected_ids,
        execution=state["execution"],
        session_summary=state["session_summary"],
        session_ledger=state["session_ledger"],
        cached_memory=state["cached_memory"],
        tools=() if state["tools_disabled"] else dependencies.tools,
        mode="native",
        history_cursor=state["history_cursor"],
        compact_operation_id=(
            f"compact:{dependencies.mcp_client.context.request_id}:"
            f"{state['context_revision']}"
        ),
    )
    try:
        result = await dependencies.context_builder.build_context(context_state)
    except Exception as exc:
        return _failure(
            code="context_build_failed",
            message=str(exc),
            retryable=False,
        )

    memory = result.gathered.memory
    for route, status in memory.statuses.items():
        runtime.stream_writer({
            "type": "memory_recall_status",
            "payload": {
                "route": route,
                "status": status,
                "reused": memory.reused,
            },
        })

    compact_attempted = (
        result.compact_result is not None
        or result.budget.reaches_compact_threshold(result.before_compact_tokens)
    )
    if compact_attempted:
        runtime.stream_writer({
            "type": "compact_finished",
            "payload": {
                "status": (
                    result.compact_result.status
                    if result.compact_result is not None
                    else "failed"
                ),
                "reason": result.reason,
                "before_tokens": result.before_compact_tokens,
                "after_tokens": result.input_tokens,
                "summary_save_status": result.summary_save_status,
            },
        })

    common = {
        "context_result": result,
        "cached_memory": memory,
        "session_summary": result.session_summary,
    }
    if result.status == "overflow" or result.request is None:
        return {
            **common,
            **_failure(
                code="context_overflow",
                message=result.reason or "受保护的上下文超过输入上限",
                retryable=False,
            ),
        }

    consumed = state["unread_result_ids"] & set(result.request.included_unit_ids)
    return {
        **common,
        "request_consumed_result_ids": consumed,
        "next_action": "call_model",
    }


def _failure(*, code: str, message: str, retryable: bool) -> dict[str, Any]:
    return {
        "status": "failed",
        "next_action": "fail_run",
        "error_code": code,
        "error_message": message,
        "error_phase": "context",
        "retryable": retryable,
    }


__all__ = ["build_context"]

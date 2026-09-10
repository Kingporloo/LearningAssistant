"""AgentLoop 的工具执行和轮数限制节点。"""

from __future__ import annotations

from typing import Any

from langchain_core.messages import AIMessage
from langgraph.runtime import Runtime

from Agent.Loop.Models import AgentRunState, RunDependencies
from Agent.Loop.ToolResults import (
    ToolExecutionRecord,
    build_tool_record,
    tool_error_result,
    tool_round_limit_result,
)


_MEMORY_WRITE_TOOLS = {
    "memory__memory_store",
    "memory__memory_forget",
}


async def execute_tools(
    state: AgentRunState,
    runtime: Runtime[RunDependencies],
) -> dict[str, Any]:
    message = state["assistant_message"]
    if message is None or not message.tool_calls:
        return _failure("工具节点没有收到待执行的调用")

    records: list[ToolExecutionRecord] = []
    memory_changed = False
    for call in message.tool_calls:
        call_id = call["id"]
        name = call["name"]
        arguments = call["args"]
        runtime.stream_writer({
            "type": "tool_started",
            "payload": {
                "model_step": state["model_step"],
                "tool_call_id": call_id,
                "name": name,
                "arguments": arguments,
            },
        })
        forced_outcome = None
        try:
            raw_result = await runtime.context.mcp_client.call_tool(name, arguments)
        except Exception as exc:
            raw_result = tool_error_result(name, exc)
            forced_outcome = "error"

        record = build_tool_record(
            raw_result=raw_result,
            tool_call_id=call_id,
            name=name,
            run_context=runtime.context.mcp_client.context,
            model_step=state["model_step"],
            count_tokens=runtime.context.assistant.count_tokens,
            forced_outcome=forced_outcome,
        )
        records.append(record)
        memory_changed = memory_changed or (
            name in _MEMORY_WRITE_TOOLS and record.business_status == "ok"
        )
        _write_finished(runtime, call_id, name, record)

    updates = _append_group(state, message, records)
    updates.update({
        "cached_memory": None if memory_changed else state["cached_memory"],
        "tool_rounds": state["tool_rounds"] + 1,
        "context_revision": state["context_revision"] + 1,
        "next_action": "call_model",
    })
    return updates


async def reject_tool_calls(
    state: AgentRunState,
    runtime: Runtime[RunDependencies],
) -> dict[str, Any]:
    message = state["assistant_message"]
    if message is None or not message.tool_calls:
        return _failure("限制节点没有收到待拒绝的工具调用")

    records: list[ToolExecutionRecord] = []
    for call in message.tool_calls:
        runtime.stream_writer({
            "type": "tool_started",
            "payload": {
                "model_step": state["model_step"],
                "tool_call_id": call["id"],
                "name": call["name"],
                "arguments": call["args"],
                "outcome": "skipped",
            },
        })
        record = build_tool_record(
            raw_result=tool_round_limit_result(),
            tool_call_id=call["id"],
            name=call["name"],
            run_context=runtime.context.mcp_client.context,
            model_step=state["model_step"],
            count_tokens=runtime.context.assistant.count_tokens,
            forced_outcome="skipped",
        )
        records.append(record)
        _write_finished(runtime, call["id"], call["name"], record)

    updates = _append_group(state, message, records)
    updates.update({
        "tools_disabled": True,
        "context_revision": state["context_revision"] + 1,
        "next_action": "call_model",
    })
    return updates


def _append_group(
    state: AgentRunState,
    message: AIMessage,
    records: list[ToolExecutionRecord],
) -> dict[str, Any]:
    result_ids = {record.unit.id for record in records}
    return {
        "execution": [
            *state["execution"],
            message,
            *(record.message for record in records),
        ],
        "execution_units": [
            *state["execution_units"],
            *(record.unit for record in records),
        ],
        "unread_result_ids": state["unread_result_ids"] | result_ids,
        "protocol_required_ids": state["protocol_required_ids"] | result_ids,
    }


def _write_finished(
    runtime: Runtime[RunDependencies],
    call_id: str,
    name: str,
    record: ToolExecutionRecord,
) -> None:
    runtime.stream_writer({
        "type": "tool_finished",
        "payload": {
            "tool_call_id": call_id,
            "name": name,
            "outcome": record.outcome,
            "business_status": record.business_status,
            "result": record.result,
        },
    })


def _failure(message: str) -> dict[str, Any]:
    return {
        "status": "failed",
        "next_action": "fail_run",
        "error_code": "missing_tool_calls",
        "error_message": message,
        "error_phase": "tool",
        "retryable": False,
    }


__all__ = ["execute_tools", "reject_tool_calls"]

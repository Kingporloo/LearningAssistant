"""AgentLoop 的流式模型调用节点。"""

from __future__ import annotations

from typing import Any

from langchain_core.messages import (
    AIMessage,
    AIMessageChunk,
    BaseMessage,
    message_chunk_to_message,
)
from langgraph.runtime import Runtime

from Agent.Loop.Models import AgentRunState, RunDependencies


_TRUNCATED_FINISH_REASONS = {
    "length",
    "max_tokens",
    "max_output_tokens",
    "max_completion_tokens",
}


async def call_model(
    state: AgentRunState,
    runtime: Runtime[RunDependencies],
) -> dict[str, Any]:
    context_result = state["context_result"]
    if context_result is None or context_result.request is None:
        return _failure(
            code="missing_model_request",
            message="ContextBuilder 没有生成可用的模型请求",
            retryable=False,
        )

    model_step = state["model_step"] + 1
    runtime.stream_writer({
        "type": "model_step_started",
        "payload": {
            "model_step": model_step,
            "input_tokens": context_result.input_tokens,
            "tools_enabled": not state["tools_disabled"],
        },
    })

    combined: AIMessageChunk | None = None
    try:
        async for chunk in runtime.context.assistant.astream(context_result.request):
            combined = chunk if combined is None else combined + chunk
            delta = chunk.text()
            if delta:
                runtime.stream_writer({
                    "type": "text_delta",
                    "payload": {"model_step": model_step, "delta": delta},
                })
    except Exception as exc:
        return {
            "model_step": model_step,
            **_failure(
                code="model_call_failed",
                message=str(exc),
                retryable=True,
            ),
        }

    if combined is None:
        return {
            "model_step": model_step,
            **_failure(
                code="empty_model_stream",
                message="模型流没有返回任何消息块",
                retryable=True,
            ),
        }

    message = message_chunk_to_message(combined)
    if not isinstance(message, AIMessage):
        return {
            "model_step": model_step,
            **_failure(
                code="invalid_model_message",
                message="模型流没有合并为 AIMessage",
                retryable=False,
            ),
        }

    step_usage = _usage(message)
    consumed = set(state["request_consumed_result_ids"])
    common: dict[str, Any] = {
        "assistant_message": message,
        "model_step": model_step,
        "usage": _merge_usage(state["usage"], step_usage),
        "unread_result_ids": state["unread_result_ids"] - consumed,
    }

    finish_reason = _finish_reason(message)
    if finish_reason in _TRUNCATED_FINISH_REASONS:
        _write_finished(runtime, model_step, "truncated", consumed, step_usage)
        return {
            **common,
            **_failure(
                code="model_output_truncated",
                message="模型输出因长度限制被截断",
                retryable=True,
            ),
        }

    if message.invalid_tool_calls:
        _write_finished(runtime, model_step, "invalid_tool_call", consumed, step_usage)
        return {
            **common,
            **_failure(
                code="invalid_tool_call",
                message="模型返回了无法解析的工具调用参数",
                retryable=True,
            ),
        }

    call_error = _tool_call_error(message, state["execution"])
    if call_error is not None:
        _write_finished(runtime, model_step, "invalid_tool_call", consumed, step_usage)
        return {
            **common,
            **_failure(
                code="invalid_tool_call",
                message=call_error,
                retryable=True,
            ),
        }

    if message.tool_calls:
        if state["tools_disabled"]:
            _write_finished(
                runtime,
                model_step,
                "tool_call_after_disabled",
                consumed,
                step_usage,
            )
            return {
                **common,
                **_failure(
                    code="tool_call_after_disabled",
                    message="模型在工具已关闭后仍请求调用工具",
                    retryable=False,
                ),
            }

        _write_finished(runtime, model_step, "tool_request", consumed, step_usage)
        next_action = (
            "reject_tool_calls"
            if state["tool_rounds"] >= runtime.context.max_tool_rounds
            else "execute_tools"
        )
        return {**common, "next_action": next_action}

    answer = message.text()
    if not answer.strip():
        _write_finished(runtime, model_step, "empty_response", consumed, step_usage)
        return {
            **common,
            **_failure(
                code="empty_model_response",
                message="模型没有返回可见文本或工具调用",
                retryable=True,
            ),
        }

    _write_finished(runtime, model_step, "final_answer", consumed, step_usage)
    return {
        **common,
        "final_answer": answer,
        "next_action": "finish_run",
    }


def _write_finished(
    runtime: Runtime[RunDependencies],
    model_step: int,
    outcome: str,
    consumed: set[str],
    usage: dict[str, int],
) -> None:
    runtime.stream_writer({
        "type": "model_step_finished",
        "payload": {
            "model_step": model_step,
            "outcome": outcome,
            "consumed_result_ids": sorted(consumed),
            "usage": usage,
        },
    })


def _tool_call_error(
    message: AIMessage,
    execution: list[BaseMessage],
) -> str | None:
    known_ids = {
        call["id"]
        for item in execution
        if isinstance(item, AIMessage)
        for call in item.tool_calls
        if isinstance(call.get("id"), str)
    }
    current_ids: set[str] = set()
    for call in message.tool_calls:
        call_id = call.get("id")
        if not isinstance(call_id, str) or not call_id:
            return "工具调用缺少有效 id"
        if call_id in known_ids or call_id in current_ids:
            return f"工具调用 id 重复: {call_id}"
        current_ids.add(call_id)
        if not isinstance(call.get("name"), str) or not call["name"].strip():
            return f"工具调用 {call_id} 缺少有效名称"
        if not isinstance(call.get("args"), dict):
            return f"工具调用 {call_id} 的参数不是 JSON 对象"
    return None


def _finish_reason(message: AIMessage) -> str | None:
    for key in ("finish_reason", "stop_reason"):
        value = message.response_metadata.get(key)
        if isinstance(value, str):
            return value.strip().lower()
    return None


def _usage(message: AIMessage) -> dict[str, int]:
    raw = message.usage_metadata or {}
    result: dict[str, int] = {}
    for key in ("input_tokens", "output_tokens", "total_tokens"):
        value = raw.get(key)
        if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
            result[key] = value
    return result


def _merge_usage(
    current: dict[str, int],
    step: dict[str, int],
) -> dict[str, int]:
    keys = current.keys() | step.keys()
    return {key: current.get(key, 0) + step.get(key, 0) for key in keys}


def _failure(*, code: str, message: str, retryable: bool) -> dict[str, Any]:
    return {
        "status": "failed",
        "next_action": "fail_run",
        "error_code": code,
        "error_message": message,
        "error_phase": "model",
        "retryable": retryable,
    }


__all__ = ["call_model"]

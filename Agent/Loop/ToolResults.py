"""把外部工具结果转换为完整 ToolMessage 和上下文来源。"""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Literal

from langchain_core.messages import ToolMessage

from Agent.Context.Schemas.ContextUnit import (
    Authority,
    ContextStatus,
    ContextUnit,
    ContextUnitType,
    Fidelity,
    SourceKind,
    SourceRef,
)
from Agent.Interface.BackendClient import RunContext


ToolOutcome = Literal["completed", "error", "unknown", "skipped"]


@dataclass(frozen=True, slots=True)
class ToolExecutionRecord:
    message: ToolMessage
    unit: ContextUnit
    result: Any
    business_status: str | None
    outcome: ToolOutcome


def build_tool_record(
    *,
    raw_result: Any,
    tool_call_id: str,
    name: str,
    run_context: RunContext,
    model_step: int,
    count_tokens: Callable[[str], int],
    forced_outcome: ToolOutcome | None = None,
) -> ToolExecutionRecord:
    """保留完整业务结果，并生成一条可配对的工具观察。"""

    content, result = _normalise_result(raw_result)
    business_status = _business_status(result)
    outcome = forced_outcome or _outcome(business_status)
    message_status = (
        "error" if business_status in {"error", "unknown"} or outcome != "completed"
        else "success"
    )
    context_status = {
        "error": ContextStatus.ERROR,
        "unknown": ContextStatus.UNKNOWN,
    }.get(business_status, ContextStatus.ACTIVE)
    if outcome == "error":
        context_status = ContextStatus.ERROR
    elif outcome == "unknown":
        context_status = ContextStatus.UNKNOWN

    message = ToolMessage(
        content=content,
        tool_call_id=tool_call_id,
        name=name,
        status=message_status,
    )
    unit_id = f"tool-result:{run_context.request_id}:{tool_call_id}"
    unit = ContextUnit(
        id=unit_id,
        type=ContextUnitType.TOOL_OBSERVATION,
        text=content,
        source_ref=SourceRef(
            kind=SourceKind.TOOL_EVENT,
            ref_id=tool_call_id,
            session_id=run_context.session_id,
            metadata={
                "tool_name": name,
                "model_step": model_step,
                "business_status": business_status,
                "outcome": outcome,
            },
        ),
        token_count=_token_count(content, count_tokens),
        user_id=run_context.user_id,
        session_id=run_context.session_id,
        authority=Authority.TOOL,
        fidelity=Fidelity.EXACT,
        status=context_status,
        atomic_group_id=f"tool-group:{run_context.request_id}:{model_step}",
    )
    return ToolExecutionRecord(
        message=message,
        unit=unit,
        result=result,
        business_status=business_status,
        outcome=outcome,
    )


def tool_error_result(name: str, error: Exception) -> dict[str, str]:
    return {
        "status": "error",
        "code": "tool_execution_failed",
        "message": f"工具 {name} 调用失败: {error}",
    }


def tool_round_limit_result() -> dict[str, str]:
    return {
        "status": "error",
        "code": "tool_round_limit",
        "message": "已达到本次运行的工具调用轮数上限。",
    }


def _normalise_result(raw: Any) -> tuple[str, Any]:
    if isinstance(raw, tuple) and len(raw) == 2:
        content, artifact = raw
        raw = artifact if isinstance(artifact, (dict, list)) else content

    if isinstance(raw, str):
        if not raw.strip():
            raw = {
                "status": "error",
                "code": "empty_tool_result",
                "message": "工具返回了空结果。",
            }
        else:
            try:
                return raw, json.loads(raw)
            except json.JSONDecodeError:
                return raw, raw

    content = json.dumps(
        raw,
        ensure_ascii=False,
        separators=(",", ":"),
        default=str,
    )
    return content, json.loads(content)


def _business_status(result: Any) -> str | None:
    if not isinstance(result, dict) or not isinstance(result.get("status"), str):
        return None
    return result["status"].strip().lower() or None


def _outcome(status: str | None) -> ToolOutcome:
    if status == "error":
        return "error"
    if status == "unknown":
        return "unknown"
    return "completed"


def _token_count(text: str, count_tokens: Callable[[str], int]) -> int:
    value = count_tokens(text)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("count_tokens 必须返回非负整数")
    return value


__all__ = [
    "ToolExecutionRecord",
    "ToolOutcome",
    "build_tool_record",
    "tool_error_result",
    "tool_round_limit_result",
]

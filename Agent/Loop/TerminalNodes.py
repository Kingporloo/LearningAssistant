"""AgentLoop 的成功与失败终态节点。"""

from __future__ import annotations

from typing import Any

from langgraph.runtime import Runtime

from Agent.Loop.Models import AgentRunState, RunDependencies


async def finish_run(
    state: AgentRunState,
    runtime: Runtime[RunDependencies],
) -> dict[str, Any]:
    answer = state["final_answer"]
    assert answer is not None
    runtime.stream_writer({
        "type": "message_completed",
        "payload": {
            "model_step": state["model_step"],
            "content": answer,
            "usage": state["usage"],
        },
    })
    runtime.stream_writer({
        "type": "run_finished",
        "payload": {
            "status": "completed",
            "model_steps": state["model_step"],
            "tool_rounds": state["tool_rounds"],
            "usage": state["usage"],
        },
    })
    return {"status": "completed"}


async def fail_run(
    state: AgentRunState,
    runtime: Runtime[RunDependencies],
) -> dict[str, Any]:
    runtime.stream_writer({
        "type": "error",
        "payload": {
            "code": state["error_code"] or "agent_run_failed",
            "message": state["error_message"] or "智能体运行失败",
            "phase": state["error_phase"] or "agent",
            "retryable": state["retryable"],
        },
    })
    runtime.stream_writer({
        "type": "run_finished",
        "payload": {
            "status": "failed",
            "model_steps": state["model_step"],
            "tool_rounds": state["tool_rounds"],
            "usage": state["usage"],
        },
    })
    return {"status": "failed"}


__all__ = ["fail_run", "finish_run"]

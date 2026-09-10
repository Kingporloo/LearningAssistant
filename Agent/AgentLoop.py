"""一次智能体运行的 LangGraph 工具调用编排。"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from typing import Any

from langchain_core.messages import BaseMessage
from langchain_core.runnables import Runnable
from langgraph.graph import END, START, StateGraph

from Agent.Assistant import Assistant
from Agent.Context.ContextBuider import ContextBuilder
from Agent.Context.Process.Components.Relevance import (
    EmbeddingFunction,
    RelevanceFunction,
)
from Agent.Context.Schemas.Config import ContextConfig
from Agent.Context.Schemas.ContextUnit import (
    Authority,
    ContextUnit,
    ContextUnitType,
    Fidelity,
    SourceKind,
    SourceRef,
)
from Agent.Interface.BackendClient import BackendClient
from Agent.Loop.ContextNode import build_context
from Agent.Loop.ModelNode import call_model
from Agent.Loop.Models import (
    AgentEvent,
    AgentRunInput,
    AgentRunState,
    RunDependencies,
)
from Agent.Loop.TerminalNodes import fail_run, finish_run
from Agent.Loop.ToolNodes import execute_tools, reject_tool_calls
from Agent.SystemPrompt import SYSTEM_PROMPT
from Agent.Tools.MCP.MCPClient import MCPClient


class AgentLoop:
    """协调 ContextBuilder、Assistant 和 MCPClient 完成一次 run。"""

    def __init__(
        self,
        *,
        assistant: Assistant,
        mcp_client: MCPClient,
        context_config: ContextConfig,
        embed_texts: EmbeddingFunction,
        summary_model: Runnable[Any, BaseMessage] | None = None,
        backend_client: BackendClient | None = None,
        summary_model_window: int | None = None,
        score_relevance: RelevanceFunction | None = None,
        system_prompt: str = SYSTEM_PROMPT,
        max_tool_rounds: int = 5,
    ) -> None:
        if not system_prompt.strip():
            raise ValueError("system_prompt 不能为空")
        if (
            isinstance(max_tool_rounds, bool)
            or not isinstance(max_tool_rounds, int)
            or max_tool_rounds <= 0
        ):
            raise ValueError("max_tool_rounds 必须是正整数")

        self.assistant = assistant
        self.mcp_client = mcp_client
        self.context_config = context_config
        self.embed_texts = embed_texts
        self.summary_model = summary_model or assistant.model
        self.backend_client = backend_client
        self.summary_model_window = summary_model_window
        self.score_relevance = score_relevance
        self.system_prompt = system_prompt
        self.max_tool_rounds = max_tool_rounds
        self._graph = _compile_graph()

    async def astream(self, run: AgentRunInput) -> AsyncIterator[AgentEvent]:
        """执行一次 run，并按发生顺序输出可持久化事件。"""

        if not isinstance(run, AgentRunInput):
            raise TypeError("run 必须是 AgentRunInput")

        sequence = 0
        terminal_emitted = False

        def event(raw: dict[str, Any]) -> AgentEvent:
            nonlocal sequence, terminal_emitted
            sequence += 1
            event_type = str(raw["type"])
            terminal_emitted = terminal_emitted or event_type == "run_finished"
            return AgentEvent(
                type=event_type,
                request_id=run.run_context.request_id,
                session_id=run.run_context.session_id,
                event_seq=sequence,
                payload=dict(raw.get("payload") or {}),
            )

        yield event({
            "type": "run_started",
            "payload": {"message_id": run.run_context.message_id},
        })

        try:
            async with self.mcp_client.bind(run.run_context) as bound_client:
                tools = await bound_client.get_tools()
                if not tools:
                    raise RuntimeError("MCP 工具发现完成，但没有返回任何工具")

                context_builder = ContextBuilder(
                    config=self.context_config,
                    mcp_client=bound_client,
                    count_tokens=self.assistant.count_tokens,
                    count_request=self.assistant.count_request,
                    embed_texts=self.embed_texts,
                    summary_model=self.summary_model,
                    backend_client=self.backend_client,
                    summary_model_window=self.summary_model_window,
                    score_relevance=self.score_relevance,
                )
                dependencies = RunDependencies(
                    assistant=self.assistant,
                    context_builder=context_builder,
                    mcp_client=bound_client,
                    tools=tuple(tools),
                    max_tool_rounds=self.max_tool_rounds,
                    system_prompt=self.system_prompt,
                )
                config = {"recursion_limit": self.max_tool_rounds * 3 + 10}
                async for raw in self._graph.astream(
                    self._initial_state(run),
                    context=dependencies,
                    config=config,
                    stream_mode="custom",
                ):
                    yield event(raw)
                if not terminal_emitted:
                    raise asyncio.CancelledError
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            if not terminal_emitted:
                yield event({
                    "type": "error",
                    "payload": {
                        "code": "agent_run_failed",
                        "message": str(exc),
                        "phase": "setup",
                        "retryable": True,
                    },
                })
                yield event({
                    "type": "run_finished",
                    "payload": {
                        "status": "failed",
                        "model_steps": 0,
                        "tool_rounds": 0,
                        "usage": {},
                    },
                })

    def _initial_state(self, run: AgentRunInput) -> AgentRunState:
        context = run.run_context
        message_id = context.message_id
        assert message_id is not None
        current_query = ContextUnit(
            id=f"message:{message_id}",
            type=ContextUnitType.DIALOGUE,
            text=run.message,
            source_ref=SourceRef(
                kind=SourceKind.MESSAGE,
                ref_id=message_id,
                session_id=context.session_id,
            ),
            token_count=self.assistant.count_tokens(run.message),
            user_id=context.user_id,
            session_id=context.session_id,
            authority=Authority.USER,
            fidelity=Fidelity.EXACT,
        )
        protected_ids = {
            *(unit.id for unit in run.execution_units),
            *run.protected_execution_ids,
        }
        return AgentRunState(
            current_query=current_query,
            dialogue_turns=tuple(run.dialogue_turns),
            execution=list(run.execution_history),
            execution_units=list(run.execution_units),
            protected_execution_ids=protected_ids,
            unread_result_ids=set(),
            protocol_required_ids=set(protected_ids),
            cached_memory=None,
            session_summary=run.session_summary,
            session_ledger=run.session_ledger,
            history_cursor=run.history_cursor,
            context_revision=0,
            context_result=None,
            request_consumed_result_ids=set(),
            assistant_message=None,
            model_step=0,
            tool_rounds=0,
            tools_disabled=False,
            final_answer=None,
            status="running",
            next_action="call_model",
            error_code=None,
            error_message=None,
            error_phase=None,
            retryable=False,
            usage={},
        )


def _compile_graph():
    graph = StateGraph(AgentRunState, context_schema=RunDependencies)
    graph.add_node("build_context", build_context)
    graph.add_node("call_model", call_model)
    graph.add_node("execute_tools", execute_tools)
    graph.add_node("reject_tool_calls", reject_tool_calls)
    graph.add_node("finish_run", finish_run)
    graph.add_node("fail_run", fail_run)

    graph.add_edge(START, "build_context")
    graph.add_conditional_edges(
        "build_context",
        _next_action,
        {"call_model": "call_model", "fail_run": "fail_run"},
    )
    graph.add_conditional_edges(
        "call_model",
        _next_action,
        {
            "execute_tools": "execute_tools",
            "reject_tool_calls": "reject_tool_calls",
            "finish_run": "finish_run",
            "fail_run": "fail_run",
        },
    )
    graph.add_edge("execute_tools", "build_context")
    graph.add_edge("reject_tool_calls", "build_context")
    graph.add_edge("finish_run", END)
    graph.add_edge("fail_run", END)
    return graph.compile(name="agent_loop")


async def _next_action(state: AgentRunState) -> str:
    return state["next_action"]


__all__ = ["AgentLoop", "AgentEvent", "AgentRunInput"]

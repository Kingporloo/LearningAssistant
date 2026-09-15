"""把 Gather 与 Select 的结果组织为最终模型输入。"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any, Literal

from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)

from Agent.Context.Process.Components.PromptRenderer import (
    DEFAULT_REACT_TEMPLATE,
    render_context_block,
    render_react_prompt,
)
from Agent.Context.Schemas.ContextUnit import (
    Authority,
    ContextUnit,
    ContextUnitType,
    SourceKind,
)


StructureMode = Literal["native", "react"]


@dataclass(slots=True)
class StructureResult:
    mode: StructureMode
    messages: list[BaseMessage]
    prompt: str | None
    tools: list[Any]
    included_unit_ids: list[str]


def structure(
    *,
    mode: StructureMode,
    system_prompt: str,
    current_query: ContextUnit,
    mandatory: Sequence[ContextUnit],
    selected: Sequence[ContextUnit] = (),
    tools: Sequence[Any] = (),
    execution: Sequence[BaseMessage] | Sequence[str] = (),
    react_template: str = DEFAULT_REACT_TEMPLATE,
) -> StructureResult:
    """组装原生 tool calling 消息或文本 ReAct prompt。"""

    if mode not in {"native", "react"}:
        raise ValueError("mode 必须是 native 或 react")
    if not isinstance(system_prompt, str):
        raise TypeError("system_prompt 必须是字符串")
    _validate_current_query(current_query)

    mandatory_units = _unique_units(mandatory)
    mandatory_ids = {unit.id for unit in mandatory_units}
    selected_units = [
        unit for unit in _unique_units(selected) if unit.id not in mandatory_ids
    ]
    covered = _summary_covered(mandatory_units)
    mandatory_units = [
        unit
        for unit in mandatory_units
        if unit.source_ref.kind is SourceKind.DERIVED or _source_key(unit) not in covered
    ]
    selected_units = [unit for unit in selected_units if _source_key(unit) not in covered]

    current_id = current_query.id
    mandatory_units = [unit for unit in mandatory_units if unit.id != current_id]
    selected_units = [unit for unit in selected_units if unit.id != current_id]
    tool_units = [
        unit
        for unit in (*mandatory_units, *selected_units)
        if unit.type is ContextUnitType.TOOL_OBSERVATION
    ]
    if tool_units and not execution:
        raise ValueError("存在工具观察时必须传入 AgentLoop 保存的完整 execution")

    mandatory_background = [
        unit
        for unit in mandatory_units
        if not _is_dialogue_message(unit)
        and unit.type is not ContextUnitType.TOOL_OBSERVATION
    ]
    selected_background = [
        unit
        for unit in selected_units
        if not _is_dialogue_message(unit)
        and unit.type is not ContextUnitType.TOOL_OBSERVATION
    ]
    history = [
        unit
        for unit in selected_units
        if _is_dialogue_message(unit)
    ] + [
        unit
        for unit in mandatory_units
        if _is_dialogue_message(unit)
    ]
    history = _unique_units(history)

    included_ids = [
        *(unit.id for unit in mandatory_background),
        *(unit.id for unit in history),
        *(unit.id for unit in selected_background),
        current_query.id,
        *(unit.id for unit in tool_units),
    ]
    included_ids = list(dict.fromkeys(included_ids))

    if mode == "native":
        native_execution = _selected_native_execution(
            _native_execution(execution),
            tool_units,
        )
        messages = _native_messages(
            system_prompt=system_prompt,
            mandatory_background=mandatory_background,
            history=history,
            selected_background=selected_background,
            current_query=current_query,
            execution=native_execution,
        )
        return StructureResult(
            mode="native",
            messages=messages,
            prompt=None,
            tools=list(tools),
            included_unit_ids=included_ids,
        )

    react_execution = _react_execution(execution)
    history_text = _react_history(
        mandatory_background=mandatory_background,
        history=history,
        selected_background=selected_background,
        execution=react_execution,
    )
    prompt = render_react_prompt(
        system_prompt=system_prompt,
        tools=tools,
        question=current_query.text,
        history=history_text,
        template=react_template,
    )
    return StructureResult(
        mode="react",
        messages=[],
        prompt=prompt,
        tools=[],
        included_unit_ids=included_ids,
    )


def _native_messages(
    *,
    system_prompt: str,
    mandatory_background: Sequence[ContextUnit],
    history: Sequence[ContextUnit],
    selected_background: Sequence[ContextUnit],
    current_query: ContextUnit,
    execution: Sequence[BaseMessage],
) -> list[BaseMessage]:
    messages: list[BaseMessage] = []
    if system_prompt.strip():
        messages.append(SystemMessage(content=system_prompt))

    mandatory_text = render_context_block(
        "必要的会话摘要与状态",
        mandatory_background,
    )
    if mandatory_text:
        messages.append(HumanMessage(content=mandatory_text))
    messages.extend(_dialogue_message(unit) for unit in history)

    selected_text = render_context_block("本轮选中的相关背景", selected_background)
    if selected_text:
        messages.append(HumanMessage(content=selected_text))
    messages.append(HumanMessage(content=current_query.text))
    messages.extend(execution)
    return messages


def _react_history(
    *,
    mandatory_background: Sequence[ContextUnit],
    history: Sequence[ContextUnit],
    selected_background: Sequence[ContextUnit],
    execution: Sequence[str],
) -> str:
    parts: list[str] = []
    mandatory_text = render_context_block(
        "必要的会话摘要与状态",
        mandatory_background,
    )
    if mandatory_text:
        parts.append(mandatory_text)
    if history:
        parts.append("\n".join(
            f"{'User' if unit.authority is Authority.USER else 'Assistant'}: {unit.text}"
            for unit in history
        ))
    selected_text = render_context_block("本轮选中的相关背景", selected_background)
    if selected_text:
        parts.append(selected_text)
    parts.extend(execution)
    return "\n\n".join(parts)


def _native_execution(
    execution: Sequence[BaseMessage] | Sequence[str],
) -> list[BaseMessage]:
    messages = list(execution)
    if any(not isinstance(message, BaseMessage) for message in messages):
        raise TypeError("native 模式的 execution 只能包含 LangChain BaseMessage")

    pending: set[str] = set()
    seen_calls: set[str] = set()
    for message in messages:
        if isinstance(message, (HumanMessage, SystemMessage)):
            raise ValueError("execution 不能包含新的 HumanMessage 或 SystemMessage")
        if isinstance(message, AIMessage):
            if pending:
                raise ValueError("新的 Assistant 消息前存在未配对的工具调用")
            for call in message.tool_calls:
                call_id = call.get("id")
                if not isinstance(call_id, str) or not call_id:
                    raise ValueError("Assistant 工具调用必须包含 id")
                if call_id in seen_calls:
                    raise ValueError("execution 中的工具调用 id 不能重复")
                pending.add(call_id)
                seen_calls.add(call_id)
            continue
        if isinstance(message, ToolMessage):
            if message.tool_call_id not in pending:
                raise ValueError("ToolMessage 缺少对应的 Assistant 工具调用")
            pending.remove(message.tool_call_id)
            continue
        raise TypeError("execution 只能包含 AIMessage 和 ToolMessage")
    if pending:
        raise ValueError("execution 中存在没有 ToolMessage 的工具调用")
    return messages


def _react_execution(
    execution: Sequence[BaseMessage] | Sequence[str],
) -> list[str]:
    steps = list(execution)
    if any(not isinstance(step, str) or not step.strip() for step in steps):
        raise TypeError("react 模式的 execution 只能包含非空字符串")
    return steps


def _dialogue_message(unit: ContextUnit) -> BaseMessage:
    if unit.authority is Authority.USER:
        return HumanMessage(content=unit.text)
    return AIMessage(content=unit.text)


def _is_dialogue_message(unit: ContextUnit) -> bool:
    return (
        unit.type is ContextUnitType.DIALOGUE
        and unit.authority in {Authority.USER, Authority.ASSISTANT}
        and unit.source_ref.kind is SourceKind.MESSAGE
    )


def _summary_covered(mandatory: Sequence[ContextUnit]) -> set[tuple[str, str, int | None]]:
    covered: set[tuple[str, str, int | None]] = set()
    for unit in mandatory:
        if unit.source_ref.kind is not SourceKind.DERIVED:
            continue
        refs = unit.source_ref.metadata.get("source_refs", [])
        if not isinstance(refs, list):
            continue
        for ref in refs:
            if isinstance(ref, dict) and isinstance(ref.get("kind"), str) and isinstance(
                ref.get("ref_id"), str
            ):
                covered.add((ref["kind"], ref["ref_id"], ref.get("version")))
    return covered


def _source_key(unit: ContextUnit) -> tuple[str, str, int | None]:
    return (
        unit.source_ref.kind.value,
        unit.source_ref.ref_id,
        unit.source_ref.version,
    )


def _selected_native_execution(
    messages: Sequence[BaseMessage],
    tool_units: Sequence[ContextUnit],
) -> list[BaseMessage]:
    selected_call_ids = {
        str(unit.source_ref.metadata.get("tool_call_id") or unit.source_ref.ref_id)
        for unit in tool_units
    }
    if not selected_call_ids:
        return []

    selected: list[BaseMessage] = []
    group: list[BaseMessage] = []
    for message in messages:
        if isinstance(message, AIMessage) and group:
            _append_selected_group(selected, group, selected_call_ids)
            group = []
        group.append(message)
    if group:
        _append_selected_group(selected, group, selected_call_ids)
    return selected


def _append_selected_group(
    selected: list[BaseMessage],
    group: list[BaseMessage],
    selected_call_ids: set[str],
) -> None:
    first = group[0]
    if not isinstance(first, AIMessage):
        raise ValueError("execution 工具组必须以 AIMessage 开始")
    call_ids = {str(call["id"]) for call in first.tool_calls}
    overlap = call_ids & selected_call_ids
    if overlap and not call_ids <= selected_call_ids:
        raise ValueError("不能只选择历史工具调用组的一部分")
    if call_ids and call_ids <= selected_call_ids:
        selected.extend(group)


def _unique_units(units: Sequence[ContextUnit]) -> list[ContextUnit]:
    result: list[ContextUnit] = []
    seen: set[str] = set()
    for unit in units:
        if unit.id not in seen:
            result.append(unit)
            seen.add(unit.id)
    return result


def _validate_current_query(unit: ContextUnit) -> None:
    if unit.type is not ContextUnitType.DIALOGUE or unit.authority is not Authority.USER:
        raise ValueError("current_query 必须是 authority=user 的 dialogue ContextUnit")


__all__ = ["StructureMode", "StructureResult", "structure"]

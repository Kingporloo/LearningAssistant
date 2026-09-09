"""Gather 使用的系统记忆召回组件。"""

from __future__ import annotations

import asyncio
import json
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from datetime import datetime
from math import isfinite
from typing import Any

from Agent.Context.Schemas.ContextUnit import (
    Authority,
    ContextStatus,
    ContextUnit,
    ContextUnitType,
    Fidelity,
    SourceKind,
    SourceRef,
)
from Agent.Context.Schemas.Ledger import (
    LedgerEntryType,
    LedgerStatus,
    SessionLedger,
)


MEMORY_QUERY_TOOL = "memory__memory_query"


@dataclass(slots=True)
class MemoryRecall:
    units: list[ContextUnit] = field(default_factory=list)
    statuses: dict[str, str] = field(default_factory=dict)
    messages: dict[str, str] = field(default_factory=dict)
    queries: dict[str, str] = field(default_factory=dict)
    reused: bool = False


async def gather_memory(
    current_query: ContextUnit,
    mcp_client: Any,
    count_tokens: Callable[[str], int],
    *,
    session_ledger: SessionLedger | None,
    cached: MemoryRecall | None,
) -> MemoryRecall:
    task_query = _task_query(current_query.text, session_ledger)
    if cached is not None:
        status, message, results = await _call_memory_query(
            mcp_client,
            query=task_query,
            memory_type="working",
        )
        working, invalid_count = _memory_units(
            results,
            route="working",
            current_query=current_query,
            count_tokens=count_tokens,
        )
        status, message = _record_invalid_results(status, message, invalid_count)
        long_term = [
            unit
            for unit in cached.units
            if unit.type
            in {ContextUnitType.SEMANTIC_MEMORY, ContextUnitType.EPISODIC_MEMORY}
        ]
        return MemoryRecall(
            units=_merge_memory_units(long_term, working),
            statuses={**cached.statuses, "working": status},
            messages={**cached.messages, "working": message},
            queries={**cached.queries, "working": task_query},
            reused=True,
        )

    preference_query = _preference_query(current_query.text)
    get_tools = getattr(mcp_client, "get_tools", None)
    if callable(get_tools):
        try:
            await get_tools()
        except Exception as exc:
            message = f"系统记忆工具发现失败: {exc}"
            return MemoryRecall(
                statuses={"task": "error", "preference": "error"},
                messages={"task": message, "preference": message},
                queries={"task": task_query, "preference": preference_query},
            )

    task_response, preference_response = await asyncio.gather(
        _call_memory_query(mcp_client, query=task_query, memory_type="all"),
        _call_memory_query(
            mcp_client,
            query=preference_query,
            memory_type="semantic",
        ),
    )
    task_status, task_message, task_results = task_response
    preference_status, preference_message, preference_results = preference_response
    task_units, task_invalid = _memory_units(
        task_results,
        route="task",
        current_query=current_query,
        count_tokens=count_tokens,
    )
    preference_units, preference_invalid = _memory_units(
        preference_results,
        route="preference",
        current_query=current_query,
        count_tokens=count_tokens,
    )
    task_status, task_message = _record_invalid_results(
        task_status,
        task_message,
        task_invalid,
    )
    preference_status, preference_message = _record_invalid_results(
        preference_status,
        preference_message,
        preference_invalid,
    )
    return MemoryRecall(
        units=_merge_memory_units(task_units, preference_units),
        statuses={"task": task_status, "preference": preference_status},
        messages={"task": task_message, "preference": preference_message},
        queries={"task": task_query, "preference": preference_query},
    )


async def _call_memory_query(
    mcp_client: Any,
    *,
    query: str,
    memory_type: str,
) -> tuple[str, str, list[dict[str, Any]]]:
    try:
        raw = await mcp_client.call_tool(
            MEMORY_QUERY_TOOL,
            {"query": query, "memory_type": memory_type, "limit": 20},
        )
        response = _decode_tool_result(raw)
    except Exception as exc:
        return "error", f"系统记忆查询失败: {exc}", []

    status = str(response.get("status", "error"))
    message = str(response.get("message") or "")
    results = response.get("results", [])
    if status not in {"ok", "not_found", "error", "unknown"}:
        return "error", "Memory 返回了未知状态。", []
    if not isinstance(results, list) or any(not isinstance(item, dict) for item in results):
        return "error", "Memory 返回的 results 必须是对象数组。", []
    return status, message, results


def _decode_tool_result(raw: Any) -> dict[str, Any]:
    if isinstance(raw, tuple) and len(raw) == 2:
        content, artifact = raw
        raw = artifact if isinstance(artifact, dict) else content
    if isinstance(raw, str):
        raw = json.loads(raw)
    if not isinstance(raw, dict):
        raise ValueError("Memory 工具结果必须是 JSON 对象")
    return raw


def _memory_units(
    results: list[dict[str, Any]],
    *,
    route: str,
    current_query: ContextUnit,
    count_tokens: Callable[[str], int],
) -> tuple[list[ContextUnit], int]:
    units: list[ContextUnit] = []
    invalid_count = 0
    for item in results:
        try:
            units.append(
                _memory_unit(
                    item,
                    route=route,
                    current_query=current_query,
                    count_tokens=count_tokens,
                )
            )
        except (TypeError, ValueError):
            invalid_count += 1
    return units, invalid_count


def _record_invalid_results(
    status: str,
    message: str,
    invalid_count: int,
) -> tuple[str, str]:
    if invalid_count == 0:
        return status, message
    detail = f"Memory 返回了 {invalid_count} 条无效记录，已跳过。"
    return "error", f"{message}；{detail}" if message else detail


def _memory_unit(
    item: dict[str, Any],
    *,
    route: str,
    current_query: ContextUnit,
    count_tokens: Callable[[str], int],
) -> ContextUnit:
    memory_id = _required_text(item.get("memory_id"), "memory_id")
    memory_type = _required_text(item.get("memory_type"), "memory_type")
    content = _required_text(item.get("content"), "content")
    type_map = {
        "working": ContextUnitType.WORKING_STATE,
        "semantic": ContextUnitType.SEMANTIC_MEMORY,
        "episodic": ContextUnitType.EPISODIC_MEMORY,
    }
    if memory_type not in type_map:
        raise ValueError("memory_type 不受支持")

    version = item.get("version")
    source = item.get("source") or {}
    if not isinstance(source, dict):
        raise TypeError("source 必须是对象")
    source_session_id = source.get("session_id")
    if source_session_id is not None:
        source_session_id = _required_text(source_session_id, "source.session_id")

    score = item.get("score")
    retrieval_scores: dict[str, float] = {}
    if score is not None:
        if (
            isinstance(score, bool)
            or not isinstance(score, (int, float))
            or not isfinite(score)
        ):
            raise ValueError("score 必须是有限数值")
        retrieval_scores["memory"] = float(score)

    unit_type = type_map[memory_type]
    source_kind = (
        SourceKind.WORKING_STATE
        if unit_type is ContextUnitType.WORKING_STATE
        else SourceKind.MEMORY
    )
    metadata = {
        "memory_type": memory_type,
        "message_id": source.get("message_id"),
        "recall_routes": [route],
    }
    for name in ("expires_at", "content_hash"):
        if name in item:
            metadata[name] = item[name]

    return ContextUnit(
        id=f"memory:{memory_type}:{memory_id}:{version if version is not None else 'current'}",
        type=unit_type,
        text=content,
        source_ref=SourceRef(
            kind=source_kind,
            ref_id=memory_id,
            session_id=(
                current_query.session_id
                if unit_type is ContextUnitType.WORKING_STATE
                else source_session_id
            ),
            version=version,
            metadata=metadata,
        ),
        token_count=_count(content, count_tokens),
        user_id=current_query.user_id,
        session_id=(
            current_query.session_id
            if unit_type is ContextUnitType.WORKING_STATE
            else None
        ),
        created_at=_datetime(item.get("created_at")),
        event_time=_datetime(item.get("event_time")),
        importance=item.get("importance"),
        authority=Authority.USER,
        fidelity=Fidelity.EXTRACTED,
        status=_context_status(item.get("status")),
        retrieval_scores=retrieval_scores,
    )


def _merge_memory_units(*groups: Sequence[ContextUnit]) -> list[ContextUnit]:
    merged: dict[tuple[str, str, int | None], ContextUnit] = {}
    for unit in (unit for group in groups for unit in group):
        key = (unit.type.value, unit.source_ref.ref_id, unit.source_ref.version)
        existing = merged.get(key)
        if existing is None:
            merged[key] = unit
            continue
        routes = existing.source_ref.metadata.setdefault("recall_routes", [])
        for route in unit.source_ref.metadata.get("recall_routes", []):
            if route not in routes:
                routes.append(route)
        for name, score in unit.retrieval_scores.items():
            existing.retrieval_scores[name] = max(
                score,
                existing.retrieval_scores.get(name, score),
            )
    return list(merged.values())


def _task_query(query: str, ledger: SessionLedger | None) -> str:
    state = [] if ledger is None else [
        entry.content
        for entry in ledger.entries
        if entry.status is LedgerStatus.ACTIVE
        and entry.type
        in {
            LedgerEntryType.GOAL,
            LedgerEntryType.CONSTRAINT,
            LedgerEntryType.OPEN_QUESTION,
            LedgerEntryType.TOOL_STATE,
        }
    ]
    if not state:
        return query
    return f"{query}\n当前任务状态：{'；'.join(state)}"


def _preference_query(query: str) -> str:
    return (
        "查询用户对回答语言、详略、组织方式、举例和公式使用的稳定偏好；"
        f"仅返回适用于当前问题的内容。当前问题：{query}"
    )


def _context_status(value: Any) -> ContextStatus:
    if value is None:
        return ContextStatus.ACTIVE
    return ContextStatus(value)


def _datetime(value: Any) -> datetime | None:
    if value is None or isinstance(value, datetime):
        result = value
    elif isinstance(value, str):
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    else:
        raise TypeError("时间字段必须是 ISO 8601 字符串或 datetime")
    if result is not None and (result.tzinfo is None or result.utcoffset() is None):
        raise ValueError("时间字段必须包含时区")
    return result


def _count(text: str, count_tokens: Callable[[str], int]) -> int:
    value = count_tokens(text)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("count_tokens 必须返回非负整数")
    return value


def _required_text(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} 不能为空")
    return value.strip()


__all__ = ["MemoryRecall", "gather_memory"]

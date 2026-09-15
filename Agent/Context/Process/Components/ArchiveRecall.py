"""从 Java Session Archive 搜索并回读已退出活动窗口的原文。"""

from __future__ import annotations

from collections.abc import Callable, Collection
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from Agent.Context.Schemas.ContextUnit import (
    Authority,
    ContextUnit,
    ContextUnitType,
    Fidelity,
    SessionSummary,
    SourceKind,
    SourceRef,
)
from Agent.Interface.BackendClient import BackendClient, BackendError, RunContext


@dataclass(slots=True)
class ArchiveRecall:
    units: list[ContextUnit] = field(default_factory=list)
    status: str = "skipped"
    message: str = ""


async def gather_archive(
    *,
    current_query: ContextUnit,
    run_context: RunContext,
    backend: BackendClient | None,
    history_cursor: str | None,
    session_summary: SessionSummary | None,
    visible_message_ids: Collection[str],
    count_tokens: Callable[[str], int],
) -> ArchiveRecall:
    if (
        backend is None
        or history_cursor is None
        or session_summary is None
        or not session_summary.usable
        or session_summary.through_message_id is None
    ):
        return ArchiveRecall()

    try:
        search = await backend.history_search(
            run_context,
            history_cursor=history_cursor,
            query=current_query.text,
            top_k=20,
        )
        status = str(search.get("status", "error"))
        if status == "not_found":
            return ArchiveRecall(status="not_found", message=str(search.get("message") or ""))
        raw_results = search.get("results")
        if status != "ok" or not isinstance(raw_results, list):
            return ArchiveRecall(status="error", message="Archive 搜索返回了无效结果。")

        visible = set(visible_message_ids)
        refs = [
            {"message_id": str(item["message_id"]), "span_id": str(item["span_id"])}
            for item in raw_results
            if isinstance(item, dict)
            and isinstance(item.get("message_id"), str)
            and isinstance(item.get("span_id"), str)
            and item["message_id"] not in visible
        ]
        if not refs:
            return ArchiveRecall(status="not_found", message="没有命中活动窗口之外的历史原文。")

        read = await backend.history_read(
            run_context,
            history_cursor=history_cursor,
            refs=refs,
        )
    except BackendError as exc:
        return ArchiveRecall(status="error", message=str(exc))

    results = read.get("results")
    if read.get("status") not in {"ok", "not_found"} or not isinstance(results, list):
        return ArchiveRecall(status="error", message="Archive 回读返回了无效结果。")

    units: list[ContextUnit] = []
    for item in results:
        try:
            units.append(_archive_unit(item, current_query, count_tokens))
        except (TypeError, ValueError):
            continue
    return ArchiveRecall(
        units=units,
        status="ok" if units else "not_found",
        message=str(read.get("message") or ""),
    )


def _archive_unit(
    item: Any,
    current_query: ContextUnit,
    count_tokens: Callable[[str], int],
) -> ContextUnit:
    if not isinstance(item, dict):
        raise TypeError("Archive 结果必须是对象")
    message_id = _text(item.get("message_id"), "message_id")
    span_id = _text(item.get("span_id"), "span_id")
    content = _text(item.get("content"), "content")
    role = _text(item.get("role"), "role")
    if role not in {"user", "assistant"}:
        raise ValueError("Archive role 不受支持")
    return ContextUnit(
        id=f"archive:{message_id}:{span_id}",
        type=ContextUnitType.DOCUMENT_EVIDENCE,
        text=content,
        source_ref=SourceRef(
            kind=SourceKind.MESSAGE,
            ref_id=message_id,
            session_id=current_query.session_id,
            metadata={"span_id": span_id, "archive": True},
        ),
        token_count=_count(content, count_tokens),
        user_id=current_query.user_id,
        session_id=current_query.session_id,
        created_at=_datetime(item.get("created_at")),
        authority=Authority.USER if role == "user" else Authority.ASSISTANT,
        fidelity=Fidelity.EXACT,
    )


def _text(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} 不能为空")
    return value.strip()


def _count(text: str, count_tokens: Callable[[str], int]) -> int:
    value = count_tokens(text)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("count_tokens 必须返回非负整数")
    return value


def _datetime(value: Any) -> datetime | None:
    if value is None:
        return None
    result = value if isinstance(value, datetime) else datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    if result.tzinfo is None or result.utcoffset() is None:
        raise ValueError("created_at 必须包含时区")
    return result


__all__ = ["ArchiveRecall", "gather_archive"]

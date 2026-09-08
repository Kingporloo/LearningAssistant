"""情景记忆写入数据的构造规则。"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any


def prepare_episodic_memory(
    content: str,
    *,
    source_session_id: str,
    source_message_id: str | None,
    include_event_time: bool = True,
) -> dict[str, Any]:
    """情景记忆跨会话检索，session_id 只作为来源字段。"""
    result = {
        "content": content,
        "source": {
            "session_id": source_session_id,
            "message_id": source_message_id,
        },
    }
    if include_event_time:
        result["event_time"] = datetime.now(timezone.utc).isoformat()
    return result

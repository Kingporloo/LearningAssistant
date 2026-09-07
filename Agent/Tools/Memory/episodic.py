"""情景记忆写入数据的构造规则。"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any


def prepare_episodic_memory(
    content: str,
    *,
    source_session_id: str,
    source_message_id: str | None,
) -> dict[str, Any]:
    """情景记忆跨会话检索，session_id 只作为来源字段。"""
    return {
        "content": content,
        "event_time": datetime.now(timezone.utc).isoformat(),
        "source": {
            "session_id": source_session_id,
            "message_id": source_message_id,
        },
    }


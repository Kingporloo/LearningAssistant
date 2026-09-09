"""解析当前问题对历史消息的直接或位置指代。"""

from __future__ import annotations

import re
from collections.abc import Sequence

from Agent.Context.Schemas.ContextUnit import Authority, ContextUnit


_POSITIONAL_REFERENCE = re.compile(
    r"第[一二三四五六七八九十百\d]+[点条项]|"
    r"(?:上一|上面|上述|前面|刚才)(?:的)?(?:一段|段落|代码|公式|回答|内容|例子)"
)


def resolve_reference_ids(query: str, history: Sequence[ContextUnit]) -> list[str]:
    direct = [
        unit.id
        for unit in history
        if unit.id in query or unit.source_ref.ref_id in query
    ]
    if direct:
        return list(dict.fromkeys(direct))
    if not _POSITIONAL_REFERENCE.search(query):
        return []
    for unit in reversed(history):
        if unit.authority is Authority.ASSISTANT:
            return [unit.id]
    return []


__all__ = ["resolve_reference_ids"]

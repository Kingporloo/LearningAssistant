"""按用户与会话隔离的进程内工作记忆。"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from threading import RLock
from typing import Any


@dataclass
class MemoryItem:
    key: str
    content: Any
    metadata: dict[str, Any] = field(default_factory=dict)
    expires_at: float | None = None

    def expired(self) -> bool:
        return self.expires_at is not None and self.expires_at <= time.time()


class WorkingMemory:
    """当前会话的临时记忆；每次存、读、删时清理所有会话的过期项。"""

    def __init__(self, default_ttl: float | None = 300.0, max_items: int = 100) -> None:
        self.default_ttl = default_ttl
        self.max_items = max_items
        self._items: dict[tuple[str, str], dict[str, MemoryItem]] = {}
        self._lock = RLock()

    @staticmethod
    def _scope(user_id: str, session_id: str) -> tuple[str, str]:
        if not user_id.strip() or not session_id.strip():
            raise ValueError("user_id 和 session_id 不能为空")
        return user_id, session_id

    def set(
        self,
        user_id: str,
        session_id: str,
        key: str,
        content: Any,
        *,
        ttl: float | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> MemoryItem:
        if not key.strip():
            raise ValueError("工作记忆 key 不能为空")
        scope = self._scope(user_id, session_id)
        effective_ttl = self.default_ttl if ttl is None else ttl
        expires_at = None if effective_ttl is None or effective_ttl <= 0 else time.time() + effective_ttl
        item = MemoryItem(key, content, metadata or {}, expires_at)
        with self._lock:
            self._purge_expired()
            bucket = self._items.setdefault(scope, {})
            if key not in bucket and len(bucket) >= self.max_items:
                oldest = next(iter(bucket))
                bucket.pop(oldest)
            bucket[key] = item
        return item

    def get(self, user_id: str, session_id: str, key: str) -> Any | None:
        scope = self._scope(user_id, session_id)
        with self._lock:
            self._purge_expired()
            bucket = self._items.get(scope)
            if not bucket:
                return None
            item = bucket.get(key)
            return item.content if item else None

    def items(self, user_id: str, session_id: str) -> list[MemoryItem]:
        scope = self._scope(user_id, session_id)
        with self._lock:
            self._purge_expired()
            bucket = self._items.get(scope, {})
            return list(bucket.values())

    def forget(self, user_id: str, session_id: str, key: str | None = None) -> int:
        scope = self._scope(user_id, session_id)
        with self._lock:
            self._purge_expired()
            if key is None:
                return len(self._items.pop(scope, {}))
            bucket = self._items.get(scope, {})
            removed = int(bucket.pop(key, None) is not None)
            if not bucket:
                self._items.pop(scope, None)
            return removed

    def _purge_expired(self) -> None:
        # 调用方持有 _lock，避免清理与其他会话的写入互相覆盖。
        for scope, bucket in list(self._items.items()):
            for key in [key for key, item in bucket.items() if item.expired()]:
                bucket.pop(key, None)
            if not bucket:
                self._items.pop(scope, None)

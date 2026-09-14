"""异步补全语义记忆图谱的单进程工作队列。"""

from __future__ import annotations

import asyncio
import logging
from contextlib import suppress
from typing import Any

from Agent.Interface.BackendClient import BackendClient, BackendError
from Agent.Tools.Memory.semantic import SemanticProcessor

logger = logging.getLogger(__name__)

EMPTY_GRAPH = {"entities": [], "relations": []}


class SemanticMemoryWorker:
    def __init__(
        self,
        backend: BackendClient,
        processor: SemanticProcessor,
        *,
        poll_interval: float = 30.0,
    ) -> None:
        self.backend = backend
        self.processor = processor
        self.poll_interval = poll_interval
        self._wakeups: asyncio.Queue[None] = asyncio.Queue(maxsize=1)
        self._task: asyncio.Task[None] | None = None
        self._recovered = False

    def start(self) -> None:
        if self._task is None:
            self._task = asyncio.create_task(self._run())

    def notify(self) -> None:
        if self._wakeups.empty():
            self._wakeups.put_nowait(None)

    async def aclose(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with suppress(asyncio.CancelledError):
                await self._task
            self._task = None
        await self.backend.aclose()

    async def process_pending(self) -> int:
        if not await self._recover_once():
            return 0

        completed = 0
        while True:
            try:
                response = await self.backend.memory_graph_claim()
            except BackendError as exc:
                self._recovered = False
                logger.warning("领取语义记忆图谱任务失败: %s", exc)
                return completed

            if response.get("status") == "not_found":
                return completed
            job = response.get("job")
            if response.get("status") != "ok" or not isinstance(job, dict):
                logger.warning("语义记忆图谱任务响应无效: %r", response)
                return completed

            if not await self._process_job(job):
                self._recovered = False
                return completed
            completed += 1

    async def _run(self) -> None:
        while True:
            await self.process_pending()
            try:
                await asyncio.wait_for(self._wakeups.get(), timeout=self.poll_interval)
            except TimeoutError:
                continue
            self._wakeups.task_done()

    async def _recover_once(self) -> bool:
        if self._recovered:
            return True
        try:
            response = await self.backend.memory_graph_recover()
        except BackendError as exc:
            logger.warning("恢复中断的语义记忆图谱任务失败: %s", exc)
            return False
        if response.get("status") != "ok":
            logger.warning("恢复语义记忆图谱任务响应无效: %r", response)
            return False
        self._recovered = True
        return True

    async def _process_job(self, job: dict[str, Any]) -> bool:
        try:
            user_id = str(job["user_id"])
            memory_id = str(job["memory_id"])
            content = str(job["content"])
            revision = int(job["revision"])
        except (KeyError, TypeError, ValueError):
            logger.warning("语义记忆图谱任务字段无效: %r", job)
            return False

        try:
            graph, graph_status = await self.processor.extract(content)
        except Exception as exc:
            return await self._complete(
                user_id,
                memory_id,
                revision,
                "error",
                EMPTY_GRAPH,
                str(exc),
            )

        try:
            response = await self.backend.memory_graph_complete(
                user_id=user_id,
                memory_id=memory_id,
                revision=revision,
                graph_status=graph_status,
                graph=graph,
            )
        except BackendError as exc:
            return await self._complete(
                user_id,
                memory_id,
                revision,
                "error",
                EMPTY_GRAPH,
                f"图谱写入失败: {exc}",
            )
        return self._completion_accepted(response)

    async def _complete(
        self,
        user_id: str,
        memory_id: str,
        revision: int,
        graph_status: str,
        graph: dict[str, Any],
        graph_error: str | None,
    ) -> bool:
        try:
            response = await self.backend.memory_graph_complete(
                user_id=user_id,
                memory_id=memory_id,
                revision=revision,
                graph_status=graph_status,
                graph=graph,
                graph_error=graph_error,
            )
        except BackendError as exc:
            logger.warning("保存语义记忆图谱任务结果失败: %s", exc)
            return False
        return self._completion_accepted(response)

    @staticmethod
    def _completion_accepted(response: dict[str, Any]) -> bool:
        if response.get("status") in {"ok", "stale"}:
            return True
        logger.warning("语义记忆图谱任务完成响应无效: %r", response)
        return False

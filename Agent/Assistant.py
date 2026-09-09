"""无状态的 LangChain 模型调用器。"""

from __future__ import annotations

import json
import os
from collections.abc import AsyncIterator
from typing import Any
from urllib.parse import urlparse

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, AIMessageChunk, BaseMessage
from langchain_core.utils.function_calling import convert_to_openai_tool
from langchain_openai import ChatOpenAI

from Agent.Context.Process.Structure import StructureResult


class Assistant:
    """调用聊天模型；不保存历史、不执行工具、不管理会话。"""

    def __init__(self, model: BaseChatModel) -> None:
        self.model = model

    @classmethod
    def from_openai_compatible(
        cls,
        *,
        model: str | None = None,
        api_key: str | None = None,
        base_url: str | None = None,
        temperature: float | None = None,
        **model_kwargs: Any,
    ) -> "Assistant":
        """从明确配置创建 OpenAI 兼容模型，不自动探测或选择模型。"""

        model_name = model or os.getenv("AGENT_MODEL")
        endpoint = base_url or os.getenv("AGENT_BASE_URL") or None
        key = api_key or os.getenv("AGENT_API_KEY") or os.getenv("OPENAI_API_KEY")
        if not model_name:
            raise ValueError("缺少 model：请传入参数或设置 AGENT_MODEL")
        if not key:
            if _is_local_endpoint(endpoint):
                key = "EMPTY"
            else:
                raise ValueError("缺少 api_key：请传入参数或设置 AGENT_API_KEY")

        options = dict(model_kwargs)
        if temperature is not None:
            options["temperature"] = temperature
        return cls(
            ChatOpenAI(
                model=model_name,
                api_key=key,
                base_url=endpoint,
                **options,
            )
        )

    async def ainvoke(
        self,
        request: StructureResult,
        **model_kwargs: Any,
    ) -> AIMessage:
        """执行一次完整模型调用，工具循环由 AgentLoop 负责。"""

        response = await self._runnable(request, model_kwargs).ainvoke(
            _model_input(request)
        )
        if not isinstance(response, AIMessage):
            raise TypeError("聊天模型必须返回 AIMessage")
        return response

    async def astream(
        self,
        request: StructureResult,
        **model_kwargs: Any,
    ) -> AsyncIterator[AIMessageChunk]:
        """流式返回模型原始 chunk，由 AgentLoop 合并文本与工具调用。"""

        async for chunk in self._runnable(request, model_kwargs).astream(
            _model_input(request)
        ):
            if not isinstance(chunk, AIMessageChunk):
                raise TypeError("聊天模型流必须返回 AIMessageChunk")
            yield chunk

    def count_tokens(self, text: str) -> int:
        """使用当前模型的 tokenizer 计算文本 token。"""

        return self.model.get_num_tokens(text)

    def count_request(self, request: StructureResult) -> int:
        """计算 Structure 生成的完整请求，包含原生工具 schema。"""

        if request.mode == "react":
            return self.model.get_num_tokens(request.prompt or "")

        message_tokens = self.model.get_num_tokens_from_messages(request.messages)
        if not request.tools:
            return message_tokens
        schemas = [convert_to_openai_tool(tool) for tool in request.tools]
        schema_text = json.dumps(
            schemas,
            ensure_ascii=False,
            separators=(",", ":"),
            default=str,
        )
        return message_tokens + self.model.get_num_tokens(schema_text)

    def _runnable(
        self,
        request: StructureResult,
        model_kwargs: dict[str, Any],
    ) -> Any:
        runnable: Any = self.model
        if request.mode == "native" and request.tools:
            runnable = runnable.bind_tools(request.tools)
        if model_kwargs:
            runnable = runnable.bind(**model_kwargs)
        return runnable


def _model_input(request: StructureResult) -> list[BaseMessage] | str:
    if request.mode == "native":
        return request.messages
    return request.prompt or ""


def _is_local_endpoint(base_url: str | None) -> bool:
    if not base_url:
        return False
    parsed = urlparse(base_url if "://" in base_url else f"http://{base_url}")
    return parsed.hostname in {"localhost", "127.0.0.1", "::1"}


__all__ = ["Assistant"]

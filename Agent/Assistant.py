"""基于 LangChain 的智能体客户端定义。

多接口兼容：任何 OpenAI 兼容服务都能接（OpenAI、DashScope 百炼、DeepSeek、
智谱 GLM、Kimi、硅基流动、Ollama / vLLM 本地服务等），只需配置三件套：
  - AGENT_API_KEY   服务商 API Key（本地 Ollama 可不设）
  - AGENT_BASE_URL  接口地址（如 https://dashscope.aliyuncs.com/compatible-mode/v1）
  - AGENT_MODEL     模型名（缺省时自动向服务端查询可用模型）

自动检测机制：
  1. 根据 base_url 识别服务商，本地服务无 key 时自动使用占位 key
  2. 未指定模型时调用 /models 接口自动获取第一个可用模型
  3. auto_check=True 时启动即做一次低开销连通性探测并报告延迟

工具调用能力：
  - 构造时传入 LangChain 工具列表（@tool 装饰器定义），或用 register_tool() 注册
  - chat() 内置工具执行循环：模型请求调用 -> 本地执行 -> 回传结果 -> 继续生成
  - max_tool_rounds 限制单轮对话的工具调用次数，超出后强制模型直接作答

配置优先级：显式传入参数 > 环境变量
"""

import os
import time
from typing import Any, Iterator, Optional
from urllib.parse import urlparse

from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)
from langchain_core.tools import BaseTool
from langchain_openai import ChatOpenAI

# base_url 关键词 -> 服务商名称（用于识别与提示）
_KNOWN_PROVIDERS: list[tuple[str, str]] = [
    ("api.openai.com", "OpenAI"),
    ("dashscope.aliyuncs.com", "阿里云百炼 DashScope"),
    ("api.deepseek.com", "DeepSeek"),
    ("open.bigmodel.cn", "智谱 GLM"),
    ("api.moonshot.cn", "月之暗面 Kimi"),
    ("api.siliconflow.cn", "硅基流动 SiliconFlow"),
    ("localhost", "本地服务 (Ollama / vLLM 等)"),
    ("127.0.0.1", "本地服务 (Ollama / vLLM 等)"),
]


class Assistant:
    """基于 LangChain 的多接口兼容智能体客户端。"""

    def __init__(
        self,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        model: Optional[str] = None,
        system_prompt: Optional[str] = None,
        temperature: Optional[float] = None,
        auto_check: bool = True,
        tools: Optional[list[BaseTool]] = None,
        max_tool_rounds: int = 5,
    ):
        api_key = api_key or os.getenv("AGENT_API_KEY")
        base_url = base_url or os.getenv("AGENT_BASE_URL") or None
        model = model or os.getenv("AGENT_MODEL") or None

        # ---- 自动检测 ----
        provider = self._detect_provider(base_url)
        is_local = "本地服务" in provider

        if not api_key:
            if is_local:
                api_key = "EMPTY"  # Ollama / vLLM 等本地服务不需要真实 key
            else:
                raise ValueError("缺少 api_key：请传入参数或设置环境变量 AGENT_API_KEY")

        if not model:
            model = self._autodetect_model(api_key, base_url, provider)

        self.model = model
        self.provider = provider
        self.llm = ChatOpenAI(
            model=model,
            api_key=api_key,
            base_url=base_url,
            temperature=temperature,
        )

        # 工具列表与单轮对话的最大工具调用次数
        self.tools: list[BaseTool] = list(tools) if tools else []
        self.max_tool_rounds = max_tool_rounds

        # 对话历史：包含 system 消息在内的完整 messages 列表
        self.system_prompt = system_prompt
        self.history: list[BaseMessage] = []
        if system_prompt:
            self.history.append(SystemMessage(content=system_prompt))

        if auto_check:
            self._probe()

    # ---- 自动检测机制 ----

    @staticmethod
    def _detect_provider(base_url: Optional[str]) -> str:
        """根据 base_url 识别服务商。"""
        if not base_url:
            return "OpenAI (默认端点)"
        host = urlparse(base_url if "//" in base_url else f"https://{base_url}").hostname or ""
        for keyword, name in _KNOWN_PROVIDERS:
            if keyword in host:
                return name
        return f"未知服务商 ({host})"

    @staticmethod
    def _autodetect_model(api_key: str, base_url: Optional[str], provider: str) -> str:
        """未指定模型时，调用 /models 接口自动获取第一个可用模型。"""
        try:
            from openai import OpenAI

            models = OpenAI(api_key=api_key, base_url=base_url).models.list()
            first = next(iter(models.data), None)
            if first:
                print(f"[自动检测] 未指定模型，使用 {provider} 返回的第一个模型: {first.id}")
                return first.id
        except Exception as exc:
            raise ValueError(
                f"缺少 model 且无法从 {provider} 自动获取（{exc}）。"
                "请传入 model 参数或设置环境变量 AGENT_MODEL"
            ) from exc
        raise ValueError(f"缺少 model：{provider} 未返回任何可用模型，请手动指定")

    def _probe(self) -> None:
        """启动时的连通性探测：发送一次极短请求，报告服务商、模型与延迟。"""
        start = time.time()
        try:
            self.llm.invoke("ping")
        except Exception as exc:
            raise ConnectionError(
                f"连通性检测失败（服务商: {self.provider}, 模型: {self.model}）: {exc}"
            ) from exc
        latency = (time.time() - start) * 1000
        print(f"[自动检测] 连接正常: {self.provider} / {self.model}（延迟 {latency:.0f} ms）")

    # ---- 工具调用 ----

    def register_tool(self, tool: BaseTool) -> None:
        """运行时注册一个 LangChain 工具（@tool 装饰器定义）。"""
        self.tools.append(tool)

    def _execute_tool(self, call: dict[str, Any]) -> str:
        """执行单个工具调用。

        工具不存在或执行出错时不抛异常，而是把错误信息作为结果回传给模型，
        让模型有机会解释或换一种方式作答。
        """
        name = call.get("name", "")
        for tool in self.tools:
            if tool.name == name:
                try:
                    return str(tool.invoke(call.get("args") or {}))
                except Exception as exc:
                    return f"工具执行出错: {exc}"
        return f"错误: 未找到名为 {name!r} 的工具"

    # ---- 对话接口 ----

    def think(self, message: str, **kwargs) -> str:
        """发送一条用户消息，返回最终回复，并维护对话历史。

        注册了工具时自动执行工具调用循环：
            模型请求调用 -> 本地执行 -> 结果以 ToolMessage 回传 -> 继续生成
        直到模型不再请求工具，或达到 max_tool_rounds 上限（随后强制直接作答）。

        kwargs 透传给模型，如 temperature、max_tokens、tool_choice 等。
        """
        start_len = len(self.history)
        self.history.append(HumanMessage(content=message))
        try:
            # 绑定工具与调用参数
            llm = self.llm.bind_tools(self.tools, **kwargs) if self.tools else (
                self.llm.bind(**kwargs) if kwargs else self.llm
            )

            reply: AIMessage = llm.invoke(self.history)
            self.history.append(reply)

            rounds = 0
            while reply.tool_calls and rounds < self.max_tool_rounds:
                for call in reply.tool_calls:
                    result = self._execute_tool(call)
                    self.history.append(
                        ToolMessage(content=result, tool_call_id=call["id"])
                    )
                reply = llm.invoke(self.history)
                self.history.append(reply)
                rounds += 1

            if reply.tool_calls:
                # 超出工具调用轮数上限：不再执行工具，强制模型基于现有信息作答
                reply = self.llm.invoke(self.history)
                self.history.append(reply)
        except Exception:
            # 失败时回滚本轮对话（从用户消息开始的全部消息）
            del self.history[start_len:]
            raise

        content = reply.content
        if not isinstance(content, str):
            content = str(content)
        return content

    def stream_chat(self, message: str, **kwargs) -> Iterator[str]:
        """流式对话：逐段 yield 回复文本，结束后把完整回复写入历史。

        注意：本方法不处理工具调用循环，注册了工具时请使用 chat()。
        """
        start_len = len(self.history)
        self.history.append(HumanMessage(content=message))
        try:
            llm = self.llm.bind(**kwargs) if kwargs else self.llm
            chunks: list[str] = []
            for event in llm.stream(self.history):
                delta = event.content
                if delta:
                    if not isinstance(delta, str):
                        delta = str(delta)
                    chunks.append(delta)
                    yield delta
        except Exception:
            del self.history[start_len:]
            raise

        self.history.append(AIMessage(content="".join(chunks)))

    def clear_history(self) -> None:
        """清空对话历史，保留 system prompt。"""
        self.history = []
        if self.system_prompt:
            self.history.append(SystemMessage(content=self.system_prompt))


if __name__ == "__main__":
    from langchain_core.tools import tool

    @tool
    def get_current_time() -> str:
        """获取当前的日期和时间。"""
        from datetime import datetime

        return datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    @tool
    def calculator(expression: str) -> str:
        """计算一个 Python 算术表达式，如 3 * (4 + 5)。"""
        return str(eval(expression))  # noqa: S307  仅作演示

    # 自测：自动检测 + 工具调用
    assistant = Assistant(
        system_prompt="你是一个简洁的中文助手，需要时调用工具获取信息。",
        tools=[get_current_time, calculator],
    )
    print(assistant.chat("现在几点了？"))

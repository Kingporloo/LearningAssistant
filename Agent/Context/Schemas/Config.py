"""上下文构建的稳定配置。"""

from __future__ import annotations

from dataclasses import dataclass
from math import isfinite


@dataclass(frozen=True, slots=True)
class ContextConfig:
    model_window: int
    max_context_tokens: int
    output_reserve: int
    safety_margin: int
    tool_result_reserve: int = 0
    compact_trigger_ratio: float = 0.92
    summary_max_tokens: int = 2_000
    keep_recent_turns: int = 5

    def __post_init__(self) -> None:
        for name in (
            "model_window",
            "max_context_tokens",
            "output_reserve",
            "safety_margin",
            "tool_result_reserve",
            "summary_max_tokens",
            "keep_recent_turns",
        ):
            value = getattr(self, name)
            if isinstance(value, bool) or not isinstance(value, int):
                raise TypeError(f"{name} 必须是整数")
            if value < 0:
                raise ValueError(f"{name} 不能小于 0")

        if self.model_window == 0:
            raise ValueError("model_window 必须大于 0")
        if self.max_context_tokens == 0:
            raise ValueError("max_context_tokens 必须大于 0")
        if self.summary_max_tokens == 0:
            raise ValueError("summary_max_tokens 必须大于 0")
        if self.keep_recent_turns == 0:
            raise ValueError("keep_recent_turns 必须大于 0")
        if (
            self.max_context_tokens
            + self.output_reserve
            + self.tool_result_reserve
            + self.safety_margin
            > self.model_window
        ):
            raise ValueError(
                "max_context_tokens 必须为输出、工具结果和安全余量留出空间"
            )
        if (
            isinstance(self.compact_trigger_ratio, bool)
            or not isinstance(self.compact_trigger_ratio, (int, float))
            or not isfinite(self.compact_trigger_ratio)
            or not 0 < self.compact_trigger_ratio < 1
        ):
            raise ValueError("compact_trigger_ratio 必须是 0 到 1 之间的有限数值")

        object.__setattr__(
            self,
            "compact_trigger_ratio",
            float(self.compact_trigger_ratio),
        )


__all__ = ["ContextConfig"]

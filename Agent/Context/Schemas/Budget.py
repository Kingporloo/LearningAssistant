"""上下文窗口预算模型。"""

from __future__ import annotations

from dataclasses import dataclass, field
from math import floor, isfinite


@dataclass(frozen=True, slots=True)
class ContextBudget:
    model_window: int
    max_context_tokens: int
    output_reserve: int
    system_tokens: int
    tool_schema_tokens: int
    tool_result_reserve: int
    safety_margin: int
    mandatory_tokens: int
    compact_trigger_ratio: float = 0.92
    input_limit: int = field(init=False)
    compact_trigger_tokens: int = field(init=False)
    available_budget: int = field(init=False)
    selectable_budget: int = field(init=False)

    def __post_init__(self) -> None:
        for name in (
            "model_window",
            "max_context_tokens",
            "output_reserve",
            "system_tokens",
            "tool_schema_tokens",
            "tool_result_reserve",
            "safety_margin",
            "mandatory_tokens",
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
        if (
            isinstance(self.compact_trigger_ratio, bool)
            or not isinstance(self.compact_trigger_ratio, (int, float))
            or not isfinite(self.compact_trigger_ratio)
            or not 0 < self.compact_trigger_ratio < 1
        ):
            raise ValueError("compact_trigger_ratio 必须是 0 到 1 之间的有限数值")

        input_limit = min(
            self.max_context_tokens,
            self.model_window
            - self.output_reserve
            - self.tool_result_reserve
            - self.safety_margin,
        )
        trigger = floor(self.max_context_tokens * float(self.compact_trigger_ratio))
        available = input_limit - self.system_tokens - self.tool_schema_tokens
        object.__setattr__(self, "compact_trigger_ratio", float(self.compact_trigger_ratio))
        object.__setattr__(self, "input_limit", input_limit)
        object.__setattr__(self, "compact_trigger_tokens", max(0, trigger))
        object.__setattr__(self, "available_budget", available)
        object.__setattr__(self, "selectable_budget", available - self.mandatory_tokens)

    @property
    def mandatory_fits(self) -> bool:
        return self.selectable_budget >= 0

    def reaches_compact_threshold(self, input_tokens: int) -> bool:
        _validate_token_count(input_tokens)
        return input_tokens >= self.compact_trigger_tokens

    def fits_input(self, input_tokens: int) -> bool:
        _validate_token_count(input_tokens)
        return input_tokens <= self.input_limit

    def can_fit_selectable(self, token_count: int) -> bool:
        _validate_token_count(token_count)
        return self.mandatory_fits and token_count <= self.selectable_budget


def _validate_token_count(value: int) -> None:
    if isinstance(value, bool) or not isinstance(value, int):
        raise TypeError("token_count 必须是整数")
    if value < 0:
        raise ValueError("token_count 不能小于 0")


__all__ = ["ContextBudget"]

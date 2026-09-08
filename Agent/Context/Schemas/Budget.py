"""上下文窗口预算模型。"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class ContextBudget:
    model_window: int
    output_reserve: int
    system_tokens: int
    tool_schema_tokens: int
    tool_result_reserve: int
    safety_margin: int
    mandatory_tokens: int
    available_budget: int = field(init=False)
    selectable_budget: int = field(init=False)

    def __post_init__(self) -> None:
        for name in (
            "model_window",
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

        available = (
            self.model_window
            - self.output_reserve
            - self.system_tokens
            - self.tool_schema_tokens
            - self.tool_result_reserve
            - self.safety_margin
        )
        object.__setattr__(self, "available_budget", available)
        object.__setattr__(self, "selectable_budget", available - self.mandatory_tokens)

    @property
    def mandatory_fits(self) -> bool:
        return self.selectable_budget >= 0

    def can_fit_selectable(self, token_count: int) -> bool:
        if isinstance(token_count, bool) or not isinstance(token_count, int):
            raise TypeError("token_count 必须是整数")
        if token_count < 0:
            raise ValueError("token_count 不能小于 0")
        return self.mandatory_fits and token_count <= self.selectable_budget


__all__ = ["ContextBudget"]

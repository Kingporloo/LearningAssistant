"""校验教学策略评测集的结构、权重和基本覆盖。"""

from __future__ import annotations

import json
from collections import Counter
from pathlib import Path


DATASET = Path(__file__).with_name("teaching_strategy_cases.jsonl")
EXPECTED_CATEGORIES = {
    "explanation",
    "diagnostic_question",
    "socratic_guidance",
    "correction",
    "learner_adaptation",
    "restraint",
    "insufficient_evidence",
}
QUESTION_TYPES = {"none", "clarifying", "diagnostic"}


def load_cases(path: Path = DATASET) -> list[dict]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def validate_cases(cases: list[dict]) -> dict[str, int]:
    if len(cases) != 24:
        raise ValueError(f"教学策略用例应为 24 条，实际为 {len(cases)} 条")

    case_ids = [case["case_id"] for case in cases]
    if len(case_ids) != len(set(case_ids)):
        raise ValueError("case_id 必须唯一")

    category_counts = Counter(case["category"] for case in cases)
    if set(category_counts) != EXPECTED_CATEGORIES:
        raise ValueError("教学策略类别不完整或包含未知类别")

    ask_counts: Counter[bool] = Counter()
    for case in cases:
        if case["schema_version"] != 1 or not case["case_id"].startswith("teaching."):
            raise ValueError(f"{case['case_id']}: schema_version 或 case_id 不合法")

        conversation = case["conversation"]
        if not conversation or conversation[-1]["role"] != "user":
            raise ValueError(f"{case['case_id']}: conversation 必须以用户消息结束")
        if any(
            message["role"] not in {"user", "assistant"}
            or not message["content"].strip()
            for message in conversation
        ):
            raise ValueError(f"{case['case_id']}: conversation 消息不合法")

        expected = case["expected_behavior"]
        should_ask = expected["should_ask"]
        question_type = expected["question_type"]
        max_questions = expected["max_questions"]
        ask_counts[should_ask] += 1
        if question_type not in QUESTION_TYPES:
            raise ValueError(f"{case['case_id']}: question_type 不合法")
        if should_ask != (question_type != "none"):
            raise ValueError(f"{case['case_id']}: 提问决策与问题类型矛盾")
        if not isinstance(max_questions, int) or max_questions < 0:
            raise ValueError(f"{case['case_id']}: max_questions 必须为非负整数")
        if not should_ask and max_questions != 0:
            raise ValueError(f"{case['case_id']}: 不提问时 max_questions 必须为 0")

        if not expected["must_do"] or not case["forbidden_behavior"]:
            raise ValueError(f"{case['case_id']}: 缺少可观察的正向或禁止行为")
        if not case["reference_answer"]["facts"] or not case["rubric"]:
            raise ValueError(f"{case['case_id']}: 缺少参考事实或评分项")

        total_weight = sum(item["weight"] for item in case["rubric"])
        if abs(total_weight - 1.0) >= 1e-9:
            raise ValueError(f"{case['case_id']}: rubric 权重之和不是 1")
        if any(
            not item["dimension"].strip()
            or not item["pass_condition"].strip()
            or not 0 < item["weight"] <= 1
            for item in case["rubric"]
        ):
            raise ValueError(f"{case['case_id']}: rubric 评分项不合法")

    if ask_counts[True] < 6 or ask_counts[False] < 12:
        raise ValueError("评测集必须同时覆盖应提问和应克制的场景")
    if not any(
        case["expected_behavior"]["answer_policy"] == "hint_only"
        for case in cases
    ):
        raise ValueError("评测集缺少只给提示场景")

    return {
        "cases": len(cases),
        "should_ask": ask_counts[True],
        "should_not_ask": ask_counts[False],
        "categories": len(category_counts),
    }


if __name__ == "__main__":
    summary = validate_cases(load_cases())
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))

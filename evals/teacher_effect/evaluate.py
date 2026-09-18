"""教师效果评测集的统一校验、Select 校准和预测评分入口。"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from typing import Any

from validate_rag_citation_quality import (
    load_cases as load_rag_cases,
    validate_cases as validate_rag_cases,
)
from validate_select_memory import calibrate, load_cases as load_select_cases, run_case
from validate_teaching_strategy import (
    load_cases as load_teaching_cases,
    validate_cases as validate_teaching_cases,
)


def evaluate(
    rag_predictions: Path | None = None,
    teaching_predictions: Path | None = None,
) -> dict[str, Any]:
    select_cases = load_select_cases()
    for case in select_cases:
        run_case(case)

    rag_cases = load_rag_cases()
    validate_rag_cases(rag_cases)
    teaching_cases = load_teaching_cases()
    teaching_summary = validate_teaching_cases(teaching_cases)

    result: dict[str, Any] = {
        "dataset": {
            "select_memory_cases": len(select_cases),
            "select_memory_candidates": sum(len(case["candidates"]) for case in select_cases),
            "rag_citation_cases": len(rag_cases),
            "teaching_strategy_cases": len(teaching_cases),
            "teaching_categories": teaching_summary["categories"],
        },
        "select_calibration": calibrate(select_cases),
    }
    if rag_predictions is not None:
        result["rag_citation"] = score_rag(rag_cases, _load_predictions(rag_predictions))
    if teaching_predictions is not None:
        result["teaching_strategy"] = score_teaching(
            teaching_cases,
            _load_predictions(teaching_predictions),
        )
    return result


def score_rag(cases: list[dict[str, Any]], predictions: list[dict[str, Any]]) -> dict[str, Any]:
    indexed = _index_predictions(cases, predictions)
    allowed_hits = 0
    cited_total = 0
    required_hits = 0
    required_total = 0
    forbidden_hits = 0
    supported_claim_hits = 0
    supported_claim_total = 0
    location_hits = 0
    location_total = 0
    disclosure_hits = 0
    disclosure_total = 0
    forbidden_assertions = 0

    for case in cases:
        prediction = indexed[case["case_id"]]
        gold = case["gold"]
        citations = prediction.get("citations")
        if not isinstance(citations, list):
            raise ValueError(f"{case['case_id']}: citations 必须是数组")
        cited_ids = []
        for citation in citations:
            if not isinstance(citation, dict) or not isinstance(citation.get("chunk_id"), str):
                raise ValueError(f"{case['case_id']}: citation 必须包含 chunk_id")
            cited_ids.append(citation["chunk_id"])
        if len(cited_ids) != len(set(cited_ids)):
            raise ValueError(f"{case['case_id']}: citation 不能重复")

        required = set(gold["required_citation_ids"])
        acceptable = set(gold["acceptable_citation_ids"])
        forbidden = set(gold["forbidden_citation_ids"])
        cited = set(cited_ids)
        allowed_hits += len(cited & (required | acceptable))
        cited_total += len(cited)
        required_hits += len(cited & required)
        required_total += len(required)
        forbidden_hits += len(cited & forbidden)

        claim_citations = prediction.get("claim_citations", {})
        if not isinstance(claim_citations, dict):
            raise ValueError(f"{case['case_id']}: claim_citations 必须是对象")
        for claim in gold["claims"]:
            if claim["support_status"] != "supported":
                continue
            supported_claim_total += 1
            used = claim_citations.get(claim["claim_id"], [])
            if not isinstance(used, list) or any(not isinstance(item, str) for item in used):
                raise ValueError(f"{case['case_id']}/{claim['claim_id']}: claim 引用必须是字符串数组")
            if set(claim["required_citation_ids"]) <= set(used):
                supported_claim_hits += 1

        locations = {item["chunk_id"]: item for item in gold["citation_locations"]}
        for citation in citations:
            expected = locations.get(citation["chunk_id"])
            if expected is None:
                continue
            location_total += 1
            if all(citation.get(field) == expected.get(field) for field in ("source", "page", "heading")):
                location_hits += 1

        satisfied = prediction.get("satisfied_disclosures", [])
        if not isinstance(satisfied, list) or any(not isinstance(item, str) for item in satisfied):
            raise ValueError(f"{case['case_id']}: satisfied_disclosures 必须是字符串数组")
        required_disclosures = set(gold["required_disclosures"])
        disclosure_hits += len(required_disclosures & set(satisfied))
        disclosure_total += len(required_disclosures)

        assertions = prediction.get("forbidden_assertions", [])
        if not isinstance(assertions, list) or any(not isinstance(item, str) for item in assertions):
            raise ValueError(f"{case['case_id']}: forbidden_assertions 必须是字符串数组")
        forbidden_assertions += len(set(assertions) & set(gold["forbidden_assertions"]))

    return {
        "cases": len(cases),
        "citation_precision": _ratio(allowed_hits, cited_total),
        "citation_recall": _ratio(required_hits, required_total),
        "forbidden_citation_rate": _ratio(forbidden_hits, cited_total, empty=0.0),
        "claim_evidence_coverage": _ratio(supported_claim_hits, supported_claim_total),
        "location_accuracy": _ratio(location_hits, location_total),
        "boundary_disclosure_recall": _ratio(disclosure_hits, disclosure_total),
        "forbidden_assertion_count": forbidden_assertions,
    }


def score_teaching(
    cases: list[dict[str, Any]],
    predictions: list[dict[str, Any]],
) -> dict[str, Any]:
    indexed = _index_predictions(cases, predictions)
    scores: list[float] = []
    category_scores: dict[str, list[float]] = defaultdict(list)
    ask_hits = 0
    type_hits = 0
    type_total = 0
    count_hits = 0
    capped_cases = 0

    for case in cases:
        prediction = indexed[case["case_id"]]
        rubric_scores = prediction.get("rubric_scores")
        if not isinstance(rubric_scores, dict):
            raise ValueError(f"{case['case_id']}: rubric_scores 必须是对象")
        expected_dimensions = {item["dimension"] for item in case["rubric"]}
        if set(rubric_scores) != expected_dimensions:
            raise ValueError(f"{case['case_id']}: rubric_scores 维度与用例不一致")
        if any(value not in {0, 1, 2} for value in rubric_scores.values()):
            raise ValueError(f"{case['case_id']}: rubric 分数只能是 0、1、2")

        score = 100.0 * sum(
            item["weight"] * rubric_scores[item["dimension"]] / 2
            for item in case["rubric"]
        )
        hard_failures = prediction.get("hard_failures", [])
        if not isinstance(hard_failures, list) or any(not isinstance(item, str) for item in hard_failures):
            raise ValueError(f"{case['case_id']}: hard_failures 必须是字符串数组")
        if hard_failures:
            score = min(score, 40.0)
            capped_cases += 1
        scores.append(score)
        category_scores[case["category"]].append(score)

        observed_ask = prediction.get("should_ask")
        observed_type = prediction.get("question_type")
        question_count = prediction.get("question_count")
        if not isinstance(observed_ask, bool):
            raise ValueError(f"{case['case_id']}: should_ask 必须是 bool")
        if not isinstance(observed_type, str):
            raise ValueError(f"{case['case_id']}: question_type 必须是字符串")
        if isinstance(question_count, bool) or not isinstance(question_count, int) or question_count < 0:
            raise ValueError(f"{case['case_id']}: question_count 必须是非负整数")
        expected = case["expected_behavior"]
        ask_hits += observed_ask == expected["should_ask"]
        if expected["should_ask"]:
            type_total += 1
            type_hits += observed_type == expected["question_type"]
        count_hits += question_count <= expected["max_questions"]

    return {
        "cases": len(cases),
        "average_score": round(sum(scores) / len(scores), 4),
        "category_scores": {
            category: round(sum(values) / len(values), 4)
            for category, values in sorted(category_scores.items())
        },
        "ask_decision_accuracy": _ratio(ask_hits, len(cases)),
        "question_type_accuracy": _ratio(type_hits, type_total),
        "question_count_compliance": _ratio(count_hits, len(cases)),
        "hard_failure_cases": capped_cases,
    }


def _load_predictions(path: Path) -> list[dict[str, Any]]:
    predictions: list[dict[str, Any]] = []
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError(f"{path}:{line_number}: prediction 必须是对象")
        predictions.append(value)
    return predictions


def _index_predictions(
    cases: list[dict[str, Any]],
    predictions: list[dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    expected = {case["case_id"] for case in cases}
    indexed: dict[str, dict[str, Any]] = {}
    for prediction in predictions:
        case_id = prediction.get("case_id")
        if not isinstance(case_id, str) or not case_id:
            raise ValueError("prediction.case_id 必须是非空字符串")
        if case_id in indexed:
            raise ValueError(f"prediction.case_id 重复: {case_id}")
        indexed[case_id] = prediction
    if set(indexed) != expected:
        raise ValueError(
            f"prediction 用例不完整，missing={sorted(expected - set(indexed))}, "
            f"unknown={sorted(set(indexed) - expected)}"
        )
    return indexed


def _ratio(numerator: int, denominator: int, *, empty: float = 1.0) -> float:
    return round(numerator / denominator, 4) if denominator else empty


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rag-predictions", type=Path)
    parser.add_argument("--teaching-predictions", type=Path)
    args = parser.parse_args()
    print(
        json.dumps(
            evaluate(args.rag_predictions, args.teaching_predictions),
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()

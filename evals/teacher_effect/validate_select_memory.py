"""Validate and execute the Select/Memory teacher-effect evaluation subset."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[2]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from Agent.Context.Process.Select import select  # noqa: E402
from Agent.Context.Schemas.ContextUnit import (  # noqa: E402
    Authority,
    ContextUnit,
    ContextUnitType,
    Fidelity,
    SourceRef,
)


DATASET_PATH = Path(__file__).with_name("select_memory.jsonl")
_AUTHORITIES = {
    ContextUnitType.SEMANTIC_MEMORY: Authority.USER,
    ContextUnitType.EPISODIC_MEMORY: Authority.USER,
    ContextUnitType.WORKING_STATE: Authority.USER,
    ContextUnitType.DIALOGUE: Authority.ASSISTANT,
    ContextUnitType.SESSION_LEDGER: Authority.DERIVED,
    ContextUnitType.TOOL_OBSERVATION: Authority.TOOL,
}


def load_cases(path: Path = DATASET_PATH) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw_line.strip():
            continue
        try:
            case = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"{path}:{line_number}: invalid JSON: {exc}") from exc
        _validate_case(case, line_number)
        cases.append(case)
    if not cases:
        raise ValueError("Select/Memory evaluation dataset is empty")
    case_ids = [case["case_id"] for case in cases]
    if len(case_ids) != len(set(case_ids)):
        raise ValueError("case_id must be unique")
    return cases


def run_case(case: dict[str, Any]) -> None:
    query = case["query"]
    candidates = [_unit(raw) for raw in case["candidates"]]
    vectors = {query["task"]: query["task_embedding"]}
    if query["response"] is not None:
        vectors[query["response"]] = query.get(
            "response_embedding",
            query["task_embedding"],
        )
    vectors.update({raw["text"]: raw["embedding"] for raw in case["candidates"]})
    scores = {raw["text"]: raw for raw in case["candidates"]}

    def embed_texts(texts: list[str]) -> list[list[float]]:
        return [vectors[text] for text in texts]

    def score_relevance(relevance_query: str, texts: list[str]) -> list[float]:
        field = "r_response" if relevance_query == query["response"] else "r_task"
        return [scores[text][field] for text in texts]

    config = case["select_config"]
    result = select(
        candidates=candidates,
        mandatory=[],
        user_id=case["scope"]["user_id"],
        session_id=case["scope"]["session_id"],
        task_query=query["task"],
        response_query=query["response"],
        token_budget=config["token_budget"],
        task_threshold=config["task_threshold"],
        response_threshold=config["response_threshold"],
        min_gain_per_1k_tokens=config["min_gain_per_1k_tokens"],
        episodic_tau_days=config.get("episodic_tau_days", 30.0),
        embed_texts=embed_texts,
        score_relevance=score_relevance,
        now=_datetime(case["scope"]["now"]),
    )

    selected_ids = [unit.id for unit in result.selected]
    if selected_ids != case["expected_selected_ids"]:
        raise AssertionError(
            f"{case['case_id']}: selected {selected_ids}, "
            f"expected {case['expected_selected_ids']}"
        )
    actual_rejected = {
        candidate_id: result.traces[candidate_id].rejection_reason
        for candidate_id in case["expected_rejected"]
    }
    if actual_rejected != case["expected_rejected"]:
        raise AssertionError(
            f"{case['case_id']}: rejected {actual_rejected}, "
            f"expected {case['expected_rejected']}"
        )
    for candidate_id, expected in case.get("expected_trace", {}).items():
        trace = result.traces[candidate_id]
        for field, value in expected.items():
            if getattr(trace, field) != value:
                raise AssertionError(
                    f"{case['case_id']}/{candidate_id}: trace.{field} "
                    f"is {getattr(trace, field)!r}, expected {value!r}"
                )


def calibrate(cases: list[dict[str, Any]]) -> dict[str, dict[str, float | int]]:
    return {
        "task": _best_threshold(cases, "r_task", "task_relevant"),
        "response": _best_threshold(cases, "r_response", "response_relevant"),
    }


def _best_threshold(
    cases: list[dict[str, Any]],
    score_field: str,
    label_field: str,
) -> dict[str, float | int]:
    examples = [
        (candidate[score_field], candidate["ground_truth"][label_field])
        for case in cases
        if case["split"] == "calibration"
        for candidate in case["candidates"]
        if candidate[score_field] is not None
        and candidate["ground_truth"][label_field] is not None
    ]
    best: tuple[float, float, float, float, int] | None = None
    for step in range(101):
        threshold = step / 100
        true_positive = sum(score >= threshold and label for score, label in examples)
        false_positive = sum(score >= threshold and not label for score, label in examples)
        false_negative = sum(score < threshold and label for score, label in examples)
        precision = true_positive / (true_positive + false_positive) if true_positive + false_positive else 0.0
        recall = true_positive / (true_positive + false_negative) if true_positive + false_negative else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        proposal = (f1, precision, recall, -threshold, len(examples))
        if best is None or proposal > best:
            best = proposal
    assert best is not None
    return {
        "threshold": -best[3],
        "f1": best[0],
        "precision": best[1],
        "recall": best[2],
        "examples": best[4],
    }


def _unit(raw: dict[str, Any]) -> ContextUnit:
    source = raw["source_ref"]
    unit_type = ContextUnitType(raw["type"])
    return ContextUnit(
        id=raw["id"],
        type=unit_type,
        text=raw["text"],
        source_ref=SourceRef(
            kind=source["kind"],
            ref_id=source["ref_id"],
            session_id=source.get("session_id"),
            version=source.get("version"),
            exists=source["exists"],
            metadata=source["metadata"],
        ),
        token_count=raw["token_count"],
        user_id=raw["user_id"],
        session_id=raw["session_id"],
        authority=_AUTHORITIES.get(unit_type, Authority.DOCUMENT),
        fidelity=Fidelity.EXACT,
        status=raw["status"],
        event_time=_datetime(raw["event_time"]) if raw["event_time"] else None,
        importance=raw["importance"],
        duplicates=raw.get("duplicates", []),
    )


def _validate_case(case: dict[str, Any], line_number: int) -> None:
    required = {
        "case_id",
        "split",
        "category",
        "scope",
        "query",
        "select_config",
        "candidates",
        "expected_selected_ids",
        "expected_rejected",
    }
    missing = required - case.keys()
    if missing:
        raise ValueError(f"line {line_number}: missing fields {sorted(missing)}")
    if not isinstance(case["case_id"], str) or not case["case_id"].strip():
        raise ValueError(f"line {line_number}: case_id must be non-empty")
    if case["split"] not in {"calibration", "regression"}:
        raise ValueError(f"{case['case_id']}: invalid split")
    candidate_ids = [candidate["id"] for candidate in case["candidates"]]
    if len(candidate_ids) != len(set(candidate_ids)):
        raise ValueError(f"{case['case_id']}: candidate ids must be unique")
    selected = case["expected_selected_ids"]
    rejected = case["expected_rejected"]
    if set(selected) & rejected.keys():
        raise ValueError(f"{case['case_id']}: a candidate cannot be selected and rejected")
    if set(selected) | rejected.keys() != set(candidate_ids):
        raise ValueError(f"{case['case_id']}: every candidate needs one expected outcome")
    for candidate in case["candidates"]:
        for field in ("r_task", "r_response"):
            value = candidate.get(field)
            if value is not None and (
                isinstance(value, bool)
                or not isinstance(value, (int, float))
                or not 0 <= value <= 1
            ):
                raise ValueError(f"{case['case_id']}/{candidate['id']}: invalid {field}")
        if candidate.get("r_task") is None:
            raise ValueError(f"{case['case_id']}/{candidate['id']}: r_task is required")
        if set(candidate["ground_truth"]) != {"task_relevant", "response_relevant"}:
            raise ValueError(f"{case['case_id']}/{candidate['id']}: invalid ground_truth")


def _datetime(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--calibrate", action="store_true")
    args = parser.parse_args()
    cases = load_cases()
    for case in cases:
        run_case(case)
    print(f"validated {len(cases)} Select/Memory cases")
    if args.calibrate:
        print(json.dumps(calibrate(cases), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

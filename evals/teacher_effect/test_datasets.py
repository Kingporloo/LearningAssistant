from __future__ import annotations

import sys
from pathlib import Path


sys.path.insert(0, str(Path(__file__).parent))

from evaluate import evaluate  # noqa: E402


def test_datasets_and_select_calibration() -> None:
    result = evaluate()

    assert result["dataset"] == {
        "select_memory_cases": 12,
        "select_memory_candidates": 32,
        "rag_citation_cases": 14,
        "teaching_strategy_cases": 24,
        "teaching_categories": 7,
    }
    assert result["select_calibration"]["task"] == {
        "threshold": 0.45,
        "f1": 1.0,
        "precision": 1.0,
        "recall": 1.0,
        "examples": 6,
    }
    assert result["select_calibration"]["response"] == {
        "threshold": 0.45,
        "f1": 1.0,
        "precision": 1.0,
        "recall": 1.0,
        "examples": 3,
    }

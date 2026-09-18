"""校验 RAG 引用质量评测集的数据契约和覆盖范围。"""

from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Any


DATASET = Path(__file__).with_name("rag_citation_quality.jsonl")
RAG_STATUSES = {"ok", "empty", "no_match", "not_ready", "error"}
ANSWER_SCOPES = {"material_only", "material_preferred_general_allowed"}
SUPPORT_STATUSES = {"supported", "insufficient", "general_knowledge"}
REQUIRED_COVERAGE = {
    "correct_citation",
    "cross_page_heading",
    "no_result",
    "similar_distractor",
    "wrong_page_or_source",
    "insufficient_evidence",
    "evidence_supported_answer",
}


def load_cases(path: Path = DATASET) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not raw_line.strip():
            continue
        try:
            value = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"第 {line_number} 行不是有效 JSON: {exc}") from exc
        if not isinstance(value, dict):
            raise ValueError(f"第 {line_number} 行必须是 JSON 对象")
        cases.append(value)
    return cases


def validate_cases(cases: list[dict[str, Any]]) -> None:
    if not cases:
        raise ValueError("评测集不能为空")
    case_ids: set[str] = set()
    covered_tags: set[str] = set()
    for case in cases:
        case_id = _text(case, "case_id")
        if case_id in case_ids:
            raise ValueError(f"case_id 重复: {case_id}")
        case_ids.add(case_id)
        if case.get("schema_version") != "1.0":
            raise ValueError(f"{case_id}: schema_version 必须是 1.0")
        _text(case, "query")
        if case.get("answer_scope") not in ANSWER_SCOPES:
            raise ValueError(f"{case_id}: answer_scope 无效")
        tags = _string_list(case, "tags")
        covered_tags.update(tags)

        rag_response = _mapping(case, "rag_response")
        status = _text(rag_response, "status")
        if status not in RAG_STATUSES:
            raise ValueError(f"{case_id}: RAG status 无效")
        _text(rag_response, "message")

        chunks = _list(case, "retrieved_chunks")
        chunk_ids: set[str] = set()
        for chunk in chunks:
            if not isinstance(chunk, dict):
                raise ValueError(f"{case_id}: retrieved_chunks 只能包含对象")
            chunk_id = _text(chunk, "chunk_id")
            if chunk_id in chunk_ids:
                raise ValueError(f"{case_id}: chunk_id 重复: {chunk_id}")
            chunk_ids.add(chunk_id)
            for field in ("document_id", "text", "source", "file_type"):
                _text(chunk, field)
            if "page" in chunk and (not isinstance(chunk["page"], int) or chunk["page"] < 1):
                raise ValueError(f"{case_id}: page 必须是正整数")
            similarity = chunk.get("similarity")
            if not isinstance(similarity, (int, float)) or not 0 <= similarity <= 1:
                raise ValueError(f"{case_id}: similarity 必须在 [0,1]")
        if status == "ok" and not chunks:
            raise ValueError(f"{case_id}: ok 响应必须有 retrieved_chunks")
        if status != "ok" and chunks:
            raise ValueError(f"{case_id}: 非 ok 响应不能带 retrieved_chunks")

        gold = _mapping(case, "gold")
        _text(gold, "response_mode")
        _text(gold, "reference_answer")
        required = set(_string_list(gold, "required_citation_ids"))
        acceptable = set(_string_list(gold, "acceptable_citation_ids"))
        forbidden = set(_string_list(gold, "forbidden_citation_ids"))
        if required & acceptable or required & forbidden or acceptable & forbidden:
            raise ValueError(f"{case_id}: required/acceptable/forbidden 引用必须互斥")
        labelled = required | acceptable | forbidden
        if labelled != chunk_ids:
            missing = sorted(chunk_ids - labelled)
            unknown = sorted(labelled - chunk_ids)
            raise ValueError(f"{case_id}: 引用标签未覆盖候选，missing={missing}, unknown={unknown}")

        claims = _list(gold, "claims")
        claim_ids: set[str] = set()
        for claim in claims:
            if not isinstance(claim, dict):
                raise ValueError(f"{case_id}: claims 只能包含对象")
            claim_id = _text(claim, "claim_id")
            if claim_id in claim_ids:
                raise ValueError(f"{case_id}: claim_id 重复: {claim_id}")
            claim_ids.add(claim_id)
            _text(claim, "text")
            support_status = _text(claim, "support_status")
            if support_status not in SUPPORT_STATUSES:
                raise ValueError(f"{case_id}/{claim_id}: support_status 无效")
            claim_required = set(_string_list(claim, "required_citation_ids"))
            if not claim_required <= required:
                raise ValueError(f"{case_id}/{claim_id}: claim 引用不属于全局 required")
            if support_status == "supported" and not claim_required:
                raise ValueError(f"{case_id}/{claim_id}: supported claim 必须有引用")
            if support_status != "supported" and claim_required:
                raise ValueError(f"{case_id}/{claim_id}: 非 supported claim 不能要求引用")
        if not claims:
            raise ValueError(f"{case_id}: 至少需要一个可判定 claim")

        _string_list(gold, "required_disclosures")
        _string_list(gold, "forbidden_assertions")
        locations = _list(gold, "citation_locations")
        location_ids = set()
        for location in locations:
            if not isinstance(location, dict):
                raise ValueError(f"{case_id}: citation_locations 只能包含对象")
            location_ids.add(_text(location, "chunk_id"))
            _text(location, "source")
            _text(location, "heading")
            page = location.get("page")
            if page is not None and (not isinstance(page, int) or page < 1):
                raise ValueError(f"{case_id}: citation location page 无效")
        if not location_ids <= chunk_ids:
            raise ValueError(f"{case_id}: citation_locations 引用了不存在的 chunk")

    missing_coverage = REQUIRED_COVERAGE - covered_tags
    if missing_coverage:
        raise ValueError(f"评测覆盖缺失: {sorted(missing_coverage)}")


def _mapping(value: dict[str, Any], name: str) -> dict[str, Any]:
    result = value.get(name)
    if not isinstance(result, dict):
        raise ValueError(f"{name} 必须是对象")
    return result


def _list(value: dict[str, Any], name: str) -> list[Any]:
    result = value.get(name)
    if not isinstance(result, list):
        raise ValueError(f"{name} 必须是数组")
    return result


def _string_list(value: dict[str, Any], name: str) -> list[str]:
    result = _list(value, name)
    if any(not isinstance(item, str) or not item.strip() for item in result):
        raise ValueError(f"{name} 只能包含非空字符串")
    if len(result) != len(set(result)):
        raise ValueError(f"{name} 不能包含重复值")
    return result


def _text(value: dict[str, Any], name: str) -> str:
    result = value.get(name)
    if not isinstance(result, str) or not result.strip():
        raise ValueError(f"{name} 必须是非空字符串")
    return result


if __name__ == "__main__":
    loaded = load_cases()
    validate_cases(loaded)
    tag_counts = Counter(tag for case in loaded for tag in case["tags"])
    print(f"RAG citation dataset valid: {len(loaded)} cases")
    print("coverage:", ", ".join(f"{tag}={count}" for tag, count in sorted(tag_counts.items())))

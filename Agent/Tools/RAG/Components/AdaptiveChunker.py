"""Section 内 Block 的长度合并、超长切分和局部语义切点。"""

from __future__ import annotations

from math import sqrt
from typing import Any

from langchain_text_splitters import RecursiveCharacterTextSplitter

from Agent.Tools.RAG.Components.DocumentIR import DocumentBlock, DocumentSection


def adaptive_chunks(
    section: DocumentSection,
    *,
    token_limit: int,
    chunk_overlap: int,
    embeddings: Any,
) -> list[tuple[list[DocumentBlock], str]]:
    blocks = _split_oversized_blocks(
        section.blocks,
        token_limit=token_limit,
        chunk_overlap=chunk_overlap,
        embeddings=embeddings,
    )
    breaks = _semantic_breaks(section, blocks, token_limit, embeddings)
    return _merge_blocks(blocks, breaks, token_limit, embeddings)


def _split_oversized_blocks(
    blocks: tuple[DocumentBlock, ...],
    *,
    token_limit: int,
    chunk_overlap: int,
    embeddings: Any,
) -> list[DocumentBlock]:
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=token_limit,
        chunk_overlap=chunk_overlap,
        length_function=lambda text: embeddings.count_tokens(text, add_special_tokens=False),
        separators=["\n\n", "\n", "。", "！", "？", "；", " ", ""],
    )
    result: list[DocumentBlock] = []
    for block in blocks:
        if embeddings.count_tokens(block.text, add_special_tokens=False) <= token_limit:
            result.append(block)
            continue
        result.extend(
            DocumentBlock(
                block_id=f"{block.block_id}:part:{index:04d}",
                block_type=block.block_type,
                text=part.strip(),
                page_start=block.page_start,
                page_end=block.page_end,
            )
            for index, part in enumerate(splitter.split_text(block.text))
            if part.strip()
        )
    return result


def _semantic_breaks(
    section: DocumentSection,
    blocks: list[DocumentBlock],
    token_limit: int,
    embeddings: Any,
) -> set[int]:
    if len(blocks) < 3:
        return set()
    total = sum(embeddings.count_tokens(block.text, add_special_tokens=False) for block in blocks)
    weak_structure = not section.heading_path
    if total <= token_limit or (not weak_structure and total <= token_limit * 2):
        return set()
    vectors = embeddings.embed_documents([block.text for block in blocks])
    similarities = [_cosine(left, right) for left, right in zip(vectors, vectors[1:])]
    ordered = sorted(similarities)
    threshold = min(0.55, ordered[len(ordered) // 2] - 0.1)
    return {index + 1 for index, score in enumerate(similarities) if score < threshold}


def _cosine(left: list[float], right: list[float]) -> float:
    numerator = sum(a * b for a, b in zip(left, right))
    left_norm = sqrt(sum(value * value for value in left))
    right_norm = sqrt(sum(value * value for value in right))
    return numerator / (left_norm * right_norm) if left_norm and right_norm else 0.0


def _merge_blocks(
    blocks: list[DocumentBlock],
    semantic_breaks: set[int],
    token_limit: int,
    embeddings: Any,
) -> list[tuple[list[DocumentBlock], str]]:
    result: list[tuple[list[DocumentBlock], str]] = []
    current: list[DocumentBlock] = []
    minimum_for_semantic_break = max(1, token_limit // 4)

    def text_of(items: list[DocumentBlock]) -> str:
        return "\n\n".join(item.text for item in items).strip()

    def flush() -> None:
        if current:
            result.append((list(current), text_of(current)))
            current.clear()

    for index, block in enumerate(blocks):
        current_tokens = (
            embeddings.count_tokens(text_of(current), add_special_tokens=False)
            if current else 0
        )
        if index in semantic_breaks and current_tokens >= minimum_for_semantic_break:
            flush()
        candidate = [*current, block]
        if current and embeddings.count_tokens(text_of(candidate), add_special_tokens=False) > token_limit:
            flush()
        current.append(block)
    flush()
    return result

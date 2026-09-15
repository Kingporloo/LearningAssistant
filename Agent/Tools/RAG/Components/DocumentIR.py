"""Markdown 到 Document / Section / Block 中间表示的解析。"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

HEADING_RE = re.compile(r"^\s{0,3}(#{1,6})[ \t]+(.+?)\s*#*\s*$")
LIST_RE = re.compile(r"^\s*(?:[-+*]|\d+[.)])\s+")
FENCE_RE = re.compile(r"^\s*(`{3,}|~{3,})")


@dataclass(frozen=True)
class DocumentBlock:
    block_id: str
    block_type: str
    text: str
    page_start: int | None
    page_end: int | None


@dataclass(frozen=True)
class DocumentSection:
    section_id: str
    title: str | None
    heading_level: int | None
    headings: tuple[str | None, ...]
    blocks: tuple[DocumentBlock, ...]

    @property
    def heading_path(self) -> tuple[str, ...]:
        return tuple(title for title in self.headings if title)


@dataclass(frozen=True)
class DocumentIR:
    document_id: str
    title: str
    source: str
    file_type: str
    metadata: dict[str, str]
    sections: tuple[DocumentSection, ...]


@dataclass
class _SectionBuilder:
    section_id: str
    title: str | None
    heading_level: int | None
    headings: tuple[str | None, ...]
    blocks: list[DocumentBlock]


def build_document_ir(
    segments: list[tuple[dict[str, Any], str]],
    *,
    document_id: str,
) -> DocumentIR:
    """把按页标注的 Markdown 规范化为与物理页边界无关的文档结构。"""
    if not document_id.strip():
        raise ValueError("document_id 不能为空")
    if not segments:
        return DocumentIR(document_id, "", "", "md", {}, ())

    source = str(segments[0][0].get("source", ""))
    file_type = str(segments[0][0].get("file_type", "md"))
    metadata: dict[str, str] = {}
    headings: list[str | None] = [None] * 6
    sections = [_SectionBuilder(
        section_id=f"{document_id}:section:000000",
        title=None,
        heading_level=None,
        headings=tuple(headings),
        blocks=[],
    )]
    current = sections[0]
    block_index = 0

    for segment_index, (base, raw_text) in enumerate(segments):
        text = raw_text
        if segment_index == 0:
            metadata, text = _front_matter(text)
        page = int(base["page"]) if base.get("page") is not None else None
        parsed, block_index = _markdown_blocks(
            text,
            page=page,
            document_id=document_id,
            start_index=block_index,
        )
        for heading_level, heading_title, block in parsed:
            if heading_level is not None:
                headings[heading_level - 1] = heading_title
                for index in range(heading_level, len(headings)):
                    headings[index] = None
                current = _SectionBuilder(
                    section_id=f"{document_id}:section:{len(sections):06d}",
                    title=heading_title,
                    heading_level=heading_level,
                    headings=tuple(headings),
                    blocks=[],
                )
                sections.append(current)
            elif block is not None:
                current.blocks.append(block)

    immutable_sections = tuple(
        DocumentSection(
            section_id=section.section_id,
            title=section.title,
            heading_level=section.heading_level,
            headings=section.headings,
            blocks=tuple(section.blocks),
        )
        for section in sections
    )
    return DocumentIR(
        document_id=document_id,
        title=metadata.get("title", "").strip() or source,
        source=source,
        file_type=file_type,
        metadata=metadata,
        sections=immutable_sections,
    )


def _front_matter(text: str) -> tuple[dict[str, str], str]:
    lines = text.lstrip("\ufeff").splitlines(keepends=True)
    if not lines or lines[0].strip() != "---":
        return {}, text
    closing = next(
        (index for index, line in enumerate(lines[1:], start=1) if line.strip() == "---"),
        None,
    )
    if closing is None:
        return {}, text
    metadata: dict[str, str] = {}
    for line in lines[1:closing]:
        key, separator, value = line.partition(":")
        if separator and key.strip():
            metadata[key.strip()] = _yaml_scalar(value.strip())
    return metadata, "".join(lines[closing + 1:])


def _yaml_scalar(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
        value = value[1:-1]
        value = value.replace('\\"', '"').replace("\\\\", "\\")
    return value


def _markdown_blocks(
    text: str,
    *,
    page: int | None,
    document_id: str,
    start_index: int,
) -> tuple[list[tuple[int | None, str | None, DocumentBlock | None]], int]:
    lines = text.splitlines()
    events: list[tuple[int | None, str | None, DocumentBlock | None]] = []
    block_index = start_index
    index = 0
    while index < len(lines):
        line = lines[index]
        if not line.strip():
            index += 1
            continue
        heading = HEADING_RE.match(line)
        if heading:
            events.append((len(heading.group(1)), heading.group(2).strip(), None))
            index += 1
            continue

        fence = FENCE_RE.match(line)
        if fence:
            marker = fence.group(1)
            collected = [line]
            index += 1
            while index < len(lines):
                collected.append(lines[index])
                if re.match(rf"^\s*{re.escape(marker[0])}{{{len(marker)},}}\s*$", lines[index]):
                    index += 1
                    break
                index += 1
            block_type = "code"
        elif line.strip() == "$$":
            collected = [line]
            index += 1
            while index < len(lines):
                collected.append(lines[index])
                index += 1
                if collected[-1].strip() == "$$":
                    break
            block_type = "formula"
        else:
            block_type = _line_type(line)
            collected = [line]
            index += 1
            while index < len(lines):
                candidate = lines[index]
                if not candidate.strip() or HEADING_RE.match(candidate) or FENCE_RE.match(candidate):
                    break
                if candidate.strip() == "$$" or _line_type(candidate) != block_type:
                    break
                collected.append(candidate)
                index += 1

        content = "\n".join(collected).strip()
        if content:
            events.append((None, None, DocumentBlock(
                block_id=f"{document_id}:block:{block_index:08d}",
                block_type=block_type,
                text=content,
                page_start=page,
                page_end=page,
            )))
            block_index += 1
    return events, block_index


def _line_type(line: str) -> str:
    stripped = line.lstrip()
    if stripped.startswith("|"):
        return "table"
    if LIST_RE.match(line):
        return "list"
    if stripped.startswith(">"):
        return "quote"
    return "paragraph"


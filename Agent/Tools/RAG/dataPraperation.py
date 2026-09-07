"""RAG 文档加载、稳定分块与向量生成。

本模块只产生交给 Java 入库的数据，不连接数据库。
"""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from pathlib import Path
from threading import Lock
from typing import Any

from langchain_core.documents import Document
from langchain_text_splitters import MarkdownHeaderTextSplitter, RecursiveCharacterTextSplitter

PAGE_MARK_RE = re.compile(r"<!--\s*第\s*(\d+)\s*页\s*-->")
SUPPORTED_SUFFIXES = {".md", ".txt", ".pdf"}
HEADER_SPLIT_ON = [("#", "h1"), ("##", "h2"), ("###", "h3")]
DEFAULT_EMBED_MODEL = "BAAI/bge-small-zh-v1.5"


@dataclass(frozen=True)
class PreparedChunk:
    chunk_id: str
    document_id: str
    chunk_index: int
    text: str
    source: str
    file_type: str
    page: int | None = None
    h1: str | None = None
    h2: str | None = None
    h3: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {key: value for key, value in asdict(self).items() if value is not None}


class EmbeddingModel:
    """在工具服务进程内懒加载并复用本地嵌入模型。"""

    def __init__(self, model_name: str = DEFAULT_EMBED_MODEL) -> None:
        self.model_name = model_name
        self._model = None
        self._lock = Lock()

    def _get_model(self):
        if self._model is None:
            with self._lock:
                if self._model is None:
                    from langchain_huggingface import HuggingFaceEmbeddings

                    self._model = HuggingFaceEmbeddings(
                        model_name=self.model_name,
                        encode_kwargs={"normalize_embeddings": True},
                    )
        return self._model

    def embed_query(self, text: str) -> list[float]:
        return list(self._get_model().embed_query(text))

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return [list(vector) for vector in self._get_model().embed_documents(texts)]


def resolve_file_reference(file_ref: str, allowed_root: str | Path) -> Path:
    """把 Java 提供的文件引用限制在约定上传目录内。"""
    root = Path(allowed_root).resolve(strict=True)
    path = Path(file_ref).resolve(strict=True)
    if not path.is_relative_to(root):
        raise ValueError("file_ref 不在 RAG 允许读取的目录中")
    if not path.is_file():
        raise ValueError("file_ref 必须指向文件")
    if path.suffix.lower() not in SUPPORTED_SUFFIXES:
        raise ValueError(f"不支持的文件类型: {path.suffix}")
    return path


def load_file(path: Path, source_name: str | None = None) -> list[tuple[dict[str, Any], str]]:
    """读取一个受控文件，路径不会写入面向用户的来源元数据。"""
    suffix = path.suffix.lower()
    source = source_name or path.name
    base = {"source": source, "file_type": suffix.lstrip(".")}

    if suffix == ".pdf":
        from langchain_community.document_loaders import PyPDFLoader

        pages = PyPDFLoader(str(path)).load()
        return [
            ({**base, "page": int(page.metadata.get("page", 0)) + 1}, page.page_content)
            for page in pages
        ]

    text = path.read_text(encoding="utf-8")
    if suffix == ".md":
        return [
            ({**base, **({"page": page} if page is not None else {})}, content)
            for page, content in _split_markdown_by_page(text)
        ]
    return [(base, text)]


def _split_markdown_by_page(text: str) -> list[tuple[int | None, str]]:
    matches = list(PAGE_MARK_RE.finditer(text))
    if not matches:
        return [(None, text)]
    segments: list[tuple[int | None, str]] = []
    if matches[0].start() > 0:
        segments.append((None, text[: matches[0].start()]))
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        segments.append((int(match.group(1)), text[match.end():end]))
    return segments


def chunk_segments(
    segments: list[tuple[dict[str, Any], str]],
    *,
    document_id: str,
    chunk_size: int = 500,
    chunk_overlap: int = 50,
) -> list[PreparedChunk]:
    """按文档生成稳定 ID；chunk_index 只表达文档内顺序。"""
    if not document_id.strip():
        raise ValueError("document_id 不能为空")
    recursive = RecursiveCharacterTextSplitter(
        chunk_size=chunk_size,
        chunk_overlap=chunk_overlap,
    )
    markdown = MarkdownHeaderTextSplitter(
        headers_to_split_on=HEADER_SPLIT_ON,
        strip_headers=False,
    )
    parts: list[tuple[dict[str, Any], Document]] = []
    for metadata, text in segments:
        if not text.strip():
            continue
        documents = markdown.split_text(text) if metadata["file_type"] == "md" else [Document(page_content=text)]
        parts.extend((metadata, item) for item in recursive.split_documents(documents))

    chunks: list[PreparedChunk] = []
    for base, part in parts:
        text = part.page_content.strip()
        if not text:
            continue
        metadata = {**base, **part.metadata}
        chunk_index = len(chunks)
        chunks.append(
            PreparedChunk(
                chunk_id=f"{document_id}:{chunk_index:06d}",
                document_id=document_id,
                chunk_index=chunk_index,
                text=text,
                source=str(metadata["source"]),
                file_type=str(metadata["file_type"]),
                page=_optional_int(metadata.get("page")),
                h1=_optional_text(metadata.get("h1")),
                h2=_optional_text(metadata.get("h2")),
                h3=_optional_text(metadata.get("h3")),
            )
        )
    return chunks


def prepare_document(
    file_ref: str,
    *,
    allowed_root: str | Path,
    document_id: str,
    source_name: str | None = None,
    chunk_size: int = 500,
    chunk_overlap: int = 50,
    embeddings: EmbeddingModel | None = None,
) -> list[dict[str, Any]]:
    path = resolve_file_reference(file_ref, allowed_root)
    chunks = chunk_segments(
        load_file(path, source_name),
        document_id=document_id,
        chunk_size=chunk_size,
        chunk_overlap=chunk_overlap,
    )
    if not chunks:
        return []
    vectors = (embeddings or EmbeddingModel()).embed_documents([chunk.text for chunk in chunks])
    if len(vectors) != len(chunks):
        raise RuntimeError("嵌入模型返回的向量数量与分块数量不一致")
    return [{**chunk.to_dict(), "vector": vector} for chunk, vector in zip(chunks, vectors)]


def _optional_text(value: Any) -> str | None:
    text = str(value).strip() if value is not None else ""
    return text or None


def _optional_int(value: Any) -> int | None:
    return int(value) if value is not None else None


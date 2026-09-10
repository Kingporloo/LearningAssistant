"""RAG Markdown 加载、稳定分块与向量生成。

格式转换由 PDF2Markdown.py 完成；本模块只产生交给 Java 入库的数据。
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
HEADER_SPLIT_ON = [("#", "h1"), ("##", "h2"), ("###", "h3")]
DEFAULT_EMBED_MODEL = "jinaai/jina-embeddings-v2-base-zh"
# 4GB 显存（RTX 3050 Laptop）下 2048 Token 长序列的安全批量，避免注意力矩阵 OOM。
EMBED_BATCH_SIZE = 4
# jina-v2 的 ALiBi 缓冲区按 max_position_embeddings 预注册为 [1, heads, N, N]，
# 默认 8192 需 3.2GB 显存；收敛到分块上限 2048（约 200MB）才能在 4GB 卡上常驻。
EMBED_MAX_SEQ_TOKENS = 2048


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
                        model_kwargs={
                            # jina-embeddings-v2 是自定义 JinaBERT 架构，模型仓库携带自定义代码。
                            "trust_remote_code": True,
                            "config_kwargs": {"max_position_embeddings": EMBED_MAX_SEQ_TOKENS},
                        },
                        encode_kwargs={
                            "normalize_embeddings": True,
                            "batch_size": EMBED_BATCH_SIZE,
                        },
                    )
                    # 编码截断长度与 ALiBi 缓冲区保持一致，防止超长输入触发缓冲区重建。
                    client = self._model._client
                    client.max_seq_length = min(client.max_seq_length, EMBED_MAX_SEQ_TOKENS)
        return self._model

    def embed_query(self, text: str) -> list[float]:
        return self.embed_documents([text])[0]

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        limit = self.max_tokens
        for text in texts:
            count = self.count_tokens(text)
            if count > limit:
                raise ValueError(f"Embedding 输入有 {count} Token，超过模型上限 {limit}，请先分块")
        return [list(vector) for vector in self._get_model().embed_documents(texts)]

    @property
    def max_tokens(self) -> int:
        client = self._get_model()._client
        limit = client.max_seq_length
        if not isinstance(limit, int) or limit <= 0:
            raise RuntimeError("Embedding 模型未提供有效的 max_seq_length")
        return min(limit, client.tokenizer.model_max_length)

    def count_tokens(self, text: str, *, add_special_tokens: bool = True) -> int:
        # 与 HuggingFaceEmbeddings 的换行预处理保持一致，计数时禁止截断。
        tokenizer = self._get_model()._client.tokenizer
        return len(tokenizer.encode(
            text.replace("\n", " "),
            add_special_tokens=add_special_tokens,
            truncation=False,
            verbose=False,
        ))


def resolve_file_reference(file_ref: str, allowed_root: str | Path) -> Path:
    """把 Java 提供的文件引用限制在约定上传目录内。"""
    root = Path(allowed_root).resolve(strict=True)
    path = Path(file_ref).resolve(strict=True)
    if not path.is_relative_to(root):
        raise ValueError("file_ref 不在 RAG 允许读取的目录中")
    if not path.is_file():
        raise ValueError("file_ref 必须指向文件")
    if path.suffix.lower() != ".md":
        raise ValueError("RAG 只接收 .md 文件，请先通过 PDF2Markdown.py 完成格式转换")
    return path


def load_file(path: Path, source_name: str | None = None) -> list[tuple[dict[str, Any], str]]:
    """读取已转换的 Markdown，不再解析原始 PDF 或其他文件格式。"""
    source = source_name or path.name
    base = {"source": source, "file_type": "md"}
    text = path.read_text(encoding="utf-8")
    return [
        ({**base, **({"page": page} if page is not None else {})}, content)
        for page, content in _split_markdown_by_page(text)
    ]


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
    chunk_size: int = 2048,
    chunk_overlap: int = 256,
    embeddings: EmbeddingModel | None = None,
) -> list[PreparedChunk]:
    """按 Token 分块；chunk_size 含特殊 Token，chunk_overlap 只计正文 Token。"""
    if not document_id.strip():
        raise ValueError("document_id 不能为空")
    if not any(text.strip() for _, text in segments):
        return []
    embeddings = embeddings or EmbeddingModel()
    max_tokens = min(chunk_size, embeddings.max_tokens)
    token_limit = max_tokens - embeddings.count_tokens("")
    if token_limit <= 0:
        raise ValueError("分块 Token 上限必须大于模型所需的特殊 Token 数量")
    if not 0 <= chunk_overlap < token_limit:
        raise ValueError(f"chunk_overlap 必须在 0 到 {token_limit - 1} Token 之间")
    token_splitter = RecursiveCharacterTextSplitter(
        chunk_size=token_limit,
        chunk_overlap=chunk_overlap,
        length_function=lambda text: embeddings.count_tokens(text, add_special_tokens=False),
        separators=["\n\n", "\n", "。", "！", "？", "；", " ", ""],
    )
    markdown = MarkdownHeaderTextSplitter(
        headers_to_split_on=HEADER_SPLIT_ON,
        strip_headers=False,
    )
    parts: list[tuple[dict[str, Any], Document]] = []
    for metadata, text in segments:
        if not text.strip():
            continue
        documents = markdown.split_text(text)
        parts.extend((metadata, item) for item in token_splitter.split_documents(documents))

    chunks: list[PreparedChunk] = []
    for base, part in parts:
        text = part.page_content.strip()
        if not text:
            continue
        if embeddings.count_tokens(text) > max_tokens:
            raise ValueError(f"分块超过 {max_tokens} Token 上限，无法完整向量化")
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
    chunk_size: int = 2048,
    chunk_overlap: int = 256,
    embeddings: EmbeddingModel | None = None,
) -> list[dict[str, Any]]:
    path = resolve_file_reference(file_ref, allowed_root)
    embeddings = embeddings or EmbeddingModel()
    chunks = chunk_segments(
        load_file(path, source_name),
        document_id=document_id,
        chunk_size=chunk_size,
        chunk_overlap=chunk_overlap,
        embeddings=embeddings,
    )
    if not chunks:
        return []
    vectors = embeddings.embed_documents([chunk.text for chunk in chunks])
    if len(vectors) != len(chunks):
        raise RuntimeError("嵌入模型返回的向量数量与分块数量不一致")
    return [{**chunk.to_dict(), "vector": vector} for chunk, vector in zip(chunks, vectors)]


def _optional_text(value: Any) -> str | None:
    text = str(value).strip() if value is not None else ""
    return text or None


def _optional_int(value: Any) -> int | None:
    return int(value) if value is not None else None

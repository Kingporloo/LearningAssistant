"""用 markitdown 把文件转换为 Markdown，并针对 PDF 做增强。

PDF 增强：
  - 逐页转换，插入页码标记 <!-- 第 N 页 -->，便于定位原文
  - 提取 PDF 元数据写入 YAML front matter（标题、作者、页数等）
  - 检测无文本层的扫描页并统计警告（环境无 OCR 依赖时仅提示）
  - 支持 --pages 选择页码范围，适合超大 PDF

用法：
  python PDF2Markdown.py input.pdf               # 单个文件
  python PDF2Markdown.py input.pdf --pages 1-50  # 只转前 50 页
  python PDF2Markdown.py Pdf/ -r                 # 递归转换目录（保持目录结构）
  python PDF2Markdown.py a.pdf b.txt -o out      # 多文件，输出到 out/
"""

from __future__ import annotations

import argparse
import io
import logging
import sys
from pathlib import Path

from markitdown import MarkItDown, StreamInfo

# pdfminer 对缺少 FontBBox 等字段的字体描述符会发无害警告（自动回退默认值），
# 转换大 PDF 时会反复刷屏，这里直接抑制。
logging.getLogger("pdfminer").setLevel(logging.ERROR)

_markitdown = MarkItDown(enable_plugins=False)

_PDF_STREAM_INFO = StreamInfo(mimetype="application/pdf", extension=".pdf")
_PDF_MIME_PREFIXES = ("application/pdf", "application/x-pdf")
_PDF_EXTENSIONS = {".pdf"}


def _is_pdf(path: Path, chunk: bytes = b"") -> bool:
    """按扩展名和魔数判断是否为 PDF。"""
    if path.suffix.lower() in _PDF_EXTENSIONS:
        return True
    return chunk.startswith(b"%PDF-") or path.suffix.lower() in _PDF_EXTENSIONS


def _parse_pages(spec: str, total: int) -> list[int]:
    """解析页码选择表达式，如 '1-10,15,20-22'（1 基，含端点）。"""
    pages: list[int] = []
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            start_s, end_s = part.split("-", 1)
            start, end = int(start_s), int(end_s)
            if start < 1 or end < start:
                raise ValueError(f"无效的页码范围: {part}")
        else:
            start = end = int(part)
        pages.extend(range(start, min(end, total) + 1))
    return sorted(set(pages))


def _page_pdf_bytes(page) -> io.BytesIO:
    """把单个 PDF 页包装成独立的 BytesIO 流，供 markitdown 转换。"""
    from pypdf import PdfWriter

    writer = PdfWriter()
    writer.add_page(page)
    buf = io.BytesIO()
    writer.write(buf)
    buf.seek(0)
    return buf


def _yaml_str(value) -> str:
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'


def _build_front_matter(reader, source: Path, total: int) -> str:
    """从 PDF 元数据生成 YAML front matter。"""
    try:
        meta = reader.metadata
    except Exception:
        meta = None
    fields: dict[str, str] = {"source": _yaml_str(source.name), "pages": str(total)}
    if meta:
        mapping = {
            "title": "/Title",
            "author": "/Author",
            "subject": "/Subject",
            "creator": "/Creator",
            "creation_date": "/CreationDate",
        }
        for key, name in mapping.items():
            value = meta.get(name)
            if value:
                fields[key] = _yaml_str(value)
    lines = ["---", *[f"{k}: {v}" for k, v in fields.items()], "---"]
    return "\n".join(lines)


def convert_pdf(
    path: Path,
    *,
    page_markers: bool = True,
    metadata: bool = True,
    pages_spec: str | None = None,
    log=print,
) -> str:
    """增强版 PDF → Markdown 转换。

    逐页用 markitdown 转换后拼接，插入页码标记并统计扫描页。
    """
    from pypdf import PdfReader

    reader = PdfReader(str(path))
    total = len(reader.pages)

    if pages_spec:
        page_nums = _parse_pages(pages_spec, total)
    else:
        page_nums = list(range(1, total + 1))

    parts: list[str] = []
    if metadata:
        parts.append(_build_front_matter(reader, path, total))

    scanned_pages: list[int] = []
    for idx, page_num in enumerate(page_nums, start=1):
        log(f"\r  页 {idx}/{len(page_nums)}".ljust(20), end="", flush=True)
        buf = _page_pdf_bytes(reader.pages[page_num - 1])
        result = _markitdown.convert(buf, stream_info=_PDF_STREAM_INFO)
        text = (result.text_content or "").strip()

        if not text:
            scanned_pages.append(page_num)
            parts.append(f"<!-- 第 {page_num} 页（无可提取文本，可能是扫描页） -->")
        elif page_markers:
            parts.append(f"<!-- 第 {page_num} 页 -->\n\n{text}")
        else:
            parts.append(text)

    log("\r" + " " * 20 + "\r", end="")

    markdown = "\n\n".join(part for part in parts if part).strip() + "\n"

    if scanned_pages:
        shown = ", ".join(map(str, scanned_pages[:10]))
        more = " ..." if len(scanned_pages) > 10 else ""
        print(
            f"  警告: {len(scanned_pages)} 页无文本层（可能为扫描页）: {shown}{more}\n"
            "  未做 OCR。如需识别扫描页，请安装 tesseract-ocr 与 pytesseract。",
            file=sys.stderr,
        )
    return markdown


def convert_generic(path: Path) -> str:
    """非 PDF 文件直接交给 markitdown。"""
    result = _markitdown.convert(str(path))
    return result.text_content


def _resolve_output(inputs: list[Path], out_dir: Path) -> list[tuple[Path, Path]]:
    """返回 [(源文件, 目标 .md 路径)]，目录输入保持相对结构。"""
    pairs: list[tuple[Path, Path]] = []
    for src in inputs:
        if src.is_dir():
            for f in sorted(src.rglob("*")):
                if f.is_file() and not f.name.startswith("."):
                    rel = f.relative_to(src)
                    pairs.append((f, out_dir / src.stem / rel))
        else:
            pairs.append((src, out_dir / src.name))
    return [(f, p.with_suffix(".md")) for f, p in pairs]


def main() -> int:
    parser = argparse.ArgumentParser(description="用 markitdown 把文件转换为 Markdown（PDF 增强）")
    parser.add_argument("inputs", nargs="+", type=Path, help="输入文件或目录")
    parser.add_argument("-o", "--output", type=Path, default=Path("markdown_output"), help="输出目录（默认 markdown_output/）")
    parser.add_argument("-r", "--recursive", action="store_true", help="递归处理目录")
    parser.add_argument("--pages", help="PDF 页码范围，如 '1-50' 或 '1-10,15'（1 基）")
    parser.add_argument("--no-page-markers", action="store_true", help="不插入页码标记")
    parser.add_argument("--no-metadata", action="store_true", help="不写入 PDF 元数据 front matter")
    args = parser.parse_args()

    inputs = []
    for path in args.inputs:
        path = path.resolve()
        if not path.exists():
            print(f"跳过，路径不存在: {path}", file=sys.stderr)
            continue
        if path.is_dir() and not args.recursive:
            print(f"跳过目录（使用 -r 递归处理）: {path}", file=sys.stderr)
            continue
        inputs.append(path)
    if not inputs:
        return 1

    pairs = _resolve_output(inputs, args.output.resolve())
    ok = 0
    for src, dst in pairs:
        print(f"{src} -> {dst}")
        try:
            head = b""
            with open(src, "rb") as fh:
                head = fh.read(5)
            if _is_pdf(src, head):
                markdown = convert_pdf(
                    src,
                    page_markers=not args.no_page_markers,
                    metadata=not args.no_metadata,
                    pages_spec=args.pages if args.pages else None,
                )
            else:
                markdown = convert_generic(src)
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_text(markdown, encoding="utf-8")
            print(f"  完成，{len(markdown)} 字符")
            ok += 1
        except Exception as exc:
            print(f"  失败: {exc}", file=sys.stderr)
    print(f"共 {ok}/{len(pairs)} 个文件转换成功，输出目录: {args.output.resolve()}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

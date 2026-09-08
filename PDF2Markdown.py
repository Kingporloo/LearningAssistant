"""把文件转换为 Markdown：PDF 走 docling，其他文档走 markitdown。

按《RAG 异构文档解析与分块方案》：PDF 由 docling 做版面分析和结构恢复
（标题层级、阅读顺序、表格、列表），其余格式仍由 markitdown 统一转换。

PDF（docling 路径）：
  - 结构信号检查（书签 / 编号 / 文本层质量）自动选择转换模式：
      · 轻量模式：有书签或编号明显，书签+编号恢复标题层级，不做样式推断
      · 增强模式：结构信号不足时，额外用字号/粗细推断标题层级
  - OCR 按需：电子 PDF（文本层正常）关 OCR，扫描件自动开启
  - GPU 加速（auto 自动探测 CUDA，无 GPU 退回 CPU），layout batch=2 / OCR batch=1
  - fast image processor（torchvision 批量预处理，端到端提速 ~36%，失败自动回退）
  - 图片描述 / 公式 / VLM 等 enrichment 保持关闭，只保留 layout+heading+table+按需 OCR
  - 多文件串行处理（单 worker），转换完立即释放结果对象
  - 插入页码标记 <!-- 第 N 页 -->，页码为原始 PDF 页码，便于回链定位
  - 提取 PDF 元数据写入 YAML front matter（parser/模式/OCR 等）
  - 支持 --pages 选择页码范围，适合超大 PDF
  - docling 不可用或解析失败时自动回退 markitdown 逐页路径

用法：
  python PDF2Markdown.py input.pdf               # 单个文件（模式/OCR/GPU 自动）
  python PDF2Markdown.py input.pdf --pages 1-50  # 只转前 50 页
  python PDF2Markdown.py Pdf/ -r                 # 递归转换目录（保持目录结构）
  python PDF2Markdown.py a.pdf b.txt -o out      # 多文件，输出到 out/
  python PDF2Markdown.py input.pdf --pdf-mode enhanced  # 强制增强模式
  python PDF2Markdown.py input.pdf --device cpu          # 强制 CPU
  python PDF2Markdown.py input.pdf --pdf-parser markitdown  # 强制旧路径
"""

from __future__ import annotations

import argparse
import io
import importlib.util
import logging
import re
import sys
import time
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


# ---------------------------------------------------------------------------
# docling 路径：PDF 版面分析与结构恢复
# ---------------------------------------------------------------------------

# docling 导入开销大（torch / 模型加载），全部懒加载并复用单例。
_docling_converter: object | None = None
_docling_converter_key: tuple | None = None
_docling_serializer_cls = None

# 4G 显存（RTX 3050）实测可用的保守批次配置。
_LAYOUT_BATCH_SIZE = 2
_OCR_BATCH_SIZE = 1

# 结构信号检测阈值：平均每页字符数低于此值视为扫描件（无文本层）。
_TEXT_LAYER_MIN_CHARS = 50
# 抽样页中编号标题命中数达到此值视为"编号明显"。
_NUMBERING_MIN_HITS = 3
# PDF 书签条目数达到此值视为"有 bookmarks"。
_BOOKMARK_MIN_ENTRIES = 3

# 编号标题模式：1.1.1 / 1. / 1) / 第N章 / 第N节 等。
_DOC_NUMBERING = re.compile(
    r"^\s*(\d+(?:\.\d+)+\s*\S|\d+[.)]\s*\S|第[一二三四五六七八九十百\d]+[章节篇])"
)

# docling 序列化时在文档内部插入的原始分页标记，形如
# #_#_DOCLING_DOC_PAGE_BREAK_10_11_#_#（前页_后页），替换为带页码的注释。
_DOCLING_PAGE_BREAK = re.compile(r"#_#_DOCLING_DOC_PAGE_BREAK_(\d+)_(\d+)_#_#")

# 缓存的设备检测结果（auto 时惰性探测一次）。
_gpu_device: str | None = None


def docling_available() -> bool:
    """检查 docling 是否已安装（不触发导入）。"""
    try:
        return importlib.util.find_spec("docling") is not None
    except (ImportError, ValueError):
        return False


def _docling_version() -> str:
    try:
        from importlib.metadata import version

        return version("docling")
    except Exception:
        return "unknown"


# fast image processor patch 只应用一次。
_image_processor_patched = False


def _patch_image_processor_use_fast() -> None:
    """让 AutoImageProcessor 默认走 fast 实现（torchvision tensor 批量操作）。

    docling 加载 layout/表格模型时不传 use_fast，走 slow 处理器（纯 Pillow
    逐张 resize/归一化），并触发 transformers 的迁移警告。fast 版实测预处理
    快 ~2.5x、端到端省 ~36%。此处 patch 注入 use_fast=True；fast 不可用
    （旧 transformers 或模型无 fast 实现）时自动回退 slow，不影响功能。
    仅在本进程内生效，幂等。
    """
    global _image_processor_patched
    if _image_processor_patched:
        return
    _image_processor_patched = True
    try:
        from transformers import AutoImageProcessor

        original = AutoImageProcessor.from_pretrained.__func__

        def from_pretrained(cls, *args, **kwargs):
            injected = "use_fast" not in kwargs
            if injected:
                kwargs["use_fast"] = True
            try:
                return original(cls, *args, **kwargs)
            except Exception:
                if not injected:
                    raise  # 调用方显式指定 use_fast，失败照常抛出
                # 我们注入的 fast 失败：回退 slow（行为同未 patch）
                print(
                    "  提示: fast image processor 不可用，回退 slow 实现",
                    file=sys.stderr,
                )
                kwargs["use_fast"] = False
                return original(cls, *args, **kwargs)

        AutoImageProcessor.from_pretrained = classmethod(from_pretrained)
    except Exception:
        pass  # transformers 导入失败时无需 patch（docling 使用时自会报错）


def _detect_device(device: str) -> str:
    """解析设备参数：auto 时探测 CUDA（结果缓存），否则原样返回。"""
    global _gpu_device
    if device != "auto":
        return device
    if _gpu_device is None:
        try:
            import torch

            _gpu_device = "cuda" if torch.cuda.is_available() else "cpu"
        except Exception:
            _gpu_device = "cpu"
    return _gpu_device


def _probe_pdf_signals(reader, total: int) -> dict:
    """检查 PDF 结构信号，用于选择轻量/增强模式与 OCR 开关。

    - bookmarks：PDF outline（目录书签）条目数
    - numbering：抽样页文本中编号标题（1.1.1 / 1. / 第N章）命中数
    - 文本层质量：抽样页平均字符数，过低视为扫描件
    """
    try:
        outline = [o for o in reader.outline if hasattr(o, "title")]
    except Exception:
        outline = []

    sample_idx: list[int] = []
    if total > 0:
        step = max(1, total // 8)
        sample_idx = list(range(0, total, step))[:8]

    texts: list[str] = []
    numbering_hits = 0
    for i in sample_idx:
        try:
            text = reader.pages[i].extract_text() or ""
        except Exception:
            text = ""
        texts.append(text)
        numbering_hits += sum(
            1 for line in text.splitlines() if _DOC_NUMBERING.match(line)
        )

    avg_chars = sum(map(len, texts)) // max(len(texts), 1)
    return {
        "bookmark_entries": len(outline),
        "numbering_hits": numbering_hits,
        "avg_chars": avg_chars,
        "has_bookmarks": len(outline) >= _BOOKMARK_MIN_ENTRIES,
        "has_numbering": numbering_hits >= _NUMBERING_MIN_HITS,
        "scanned": avg_chars < _TEXT_LAYER_MIN_CHARS,
    }


def _docling_pipeline(mode: str, do_ocr: bool, device: str):
    """按模式构建 docling PdfPipelineOptions。

    轻量模式：书签 + 编号推断标题层级，不做视觉样式推断（generate_parsed_pages=False）。
    增强模式：额外启用字号/粗细等视觉样式推断（generate_parsed_pages=True）。
    其余 enrichment（图片描述/公式/VLM/图表）保持关闭，只保留 layout + heading + table + 按需 OCR。
    """
    from docling.datamodel.accelerator_options import AcceleratorDevice
    from docling.datamodel.pipeline_options import PdfPipelineOptions

    opts = PdfPipelineOptions()
    opts.accelerator_options.device = AcceleratorDevice(device)
    opts.layout_batch_size = _LAYOUT_BATCH_SIZE
    opts.ocr_batch_size = _OCR_BATCH_SIZE
    opts.do_ocr = do_ocr

    # 显式关闭不需要的 enrichment（默认即关，此处防止未来版本默认值变化）。
    opts.do_picture_description = False
    opts.do_picture_classification = False
    opts.do_formula_enrichment = False
    opts.do_code_enrichment = False
    opts.do_chart_extraction = False

    hh = opts.heading_hierarchy_options
    hh.enabled = True
    hh.use_bookmarks = True
    hh.use_numbering = True
    if mode == "enhanced":
        hh.use_style = True
        hh.use_font_style = True
        # style 推断依赖解析后的文本单元格（字号/粗细）。
        opts.generate_parsed_pages = True
    else:
        hh.use_style = False
        opts.generate_parsed_pages = False
    return opts


def _get_docling_converter(mode: str, do_ocr: bool, device: str):
    """惰性创建并复用 docling DocumentConverter（批量转换共享模型）。

    按配置（模式, OCR, 设备）缓存单实例；配置变化时重建（旧实例随之释放）。
    同一时刻只有一个 converter，避免多份模型同时占用显存。
    """
    global _docling_converter, _docling_converter_key
    key = (mode, do_ocr, device)
    if _docling_converter is None or _docling_converter_key != key:
        from docling.datamodel.base_models import InputFormat
        from docling.document_converter import DocumentConverter, PdfFormatOption

        # 模型加载走 transformers，先确保 fast image processor 生效。
        _patch_image_processor_use_fast()
        _docling_converter = DocumentConverter(
            format_options={
                InputFormat.PDF: PdfFormatOption(
                    pipeline_options=_docling_pipeline(mode, do_ocr, device)
                )
            }
        )
        _docling_converter_key = key
    return _docling_converter


def _get_docling_serializer_cls():
    """惰性构造带页码标记的 Markdown 序列化器类。"""
    global _docling_serializer_cls
    if _docling_serializer_cls is None:
        from docling_core.transforms.serializer.common import create_ser_result
        from docling_core.transforms.serializer.markdown import MarkdownDocSerializer

        class PageMarkerMarkdownSerializer(MarkdownDocSerializer):
            """把 docling 内部分页标记替换为 <!-- 第 N 页 -->。"""

            def serialize_doc(self, *, parts, **kwargs):
                text = "\n\n".join(p.text for p in parts if p.text)
                if self.requires_page_break():
                    text = _DOCLING_PAGE_BREAK.sub(
                        lambda m: f"<!-- 第 {m.group(2)} 页 -->", text
                    )
                return create_ser_result(text=text, span_source=parts)

        _docling_serializer_cls = PageMarkerMarkdownSerializer
    return _docling_serializer_cls


def _pages_to_runs(pages: list[int]) -> list[tuple[int, int]]:
    """把离散页码序列切分为连续区间 [(起, 止)]，docling 按区间转换。"""
    runs: list[tuple[int, int]] = []
    start = prev = pages[0]
    for p in pages[1:]:
        if p == prev + 1:
            prev = p
        else:
            runs.append((start, prev))
            start = prev = p
    runs.append((start, prev))
    return runs


def convert_pdf_docling(
    path: Path,
    *,
    page_markers: bool = True,
    metadata: bool = True,
    pages_spec: str | None = None,
    mode: str = "auto",
    device: str = "auto",
    ocr: str = "auto",
    log=print,
) -> str:
    """docling 版 PDF → Markdown：恢复结构 + 原始页码标记。

    mode:  auto（按结构信号自动选）/ light（书签+编号）/ enhanced（视觉样式推断）
    device: auto（探测 CUDA）/ cpu / cuda
    ocr:   auto（按文本层质量）/ on / off
    """
    from docling.datamodel.base_models import ConversionStatus
    from docling_core.transforms.serializer.markdown import MarkdownParams
    from pypdf import PdfReader

    reader = PdfReader(str(path))
    total = len(reader.pages)

    if pages_spec:
        page_nums = _parse_pages(pages_spec, total)
    else:
        page_nums = list(range(1, total + 1))
    runs = _pages_to_runs(page_nums)

    # --- 结构信号检查：决定模式与 OCR ---
    signals = _probe_pdf_signals(reader, total)
    if mode == "auto":
        resolved_mode = (
            "light" if signals["has_bookmarks"] or signals["has_numbering"] else "enhanced"
        )
    else:
        resolved_mode = mode
    if ocr == "auto":
        do_ocr = signals["scanned"]
    else:
        do_ocr = ocr == "on"
    resolved_device = _detect_device(device)

    reason = (
        f"书签 {signals['bookmark_entries']} 项 · 编号命中 {signals['numbering_hits']}"
        f" · 平均 {signals['avg_chars']} 字符/页"
    )
    log(
        f"  结构信号: {reason}\n"
        f"  转换配置: {resolved_mode} 模式 · OCR {'开' if do_ocr else '关'}"
        f" · {resolved_device.upper()} · layout batch={_LAYOUT_BATCH_SIZE}"
        f" · ocr batch={_OCR_BATCH_SIZE}"
    )

    parts: list[str] = []
    if metadata:
        parts.append(
            _build_front_matter(
                reader,
                path,
                total,
                parser="docling",
                pdf_mode=resolved_mode,
                do_ocr=do_ocr,
            )
        )

    converter = _get_docling_converter(resolved_mode, do_ocr, resolved_device)
    serializer_cls = _get_docling_serializer_cls()

    for idx, (start, end) in enumerate(runs, start=1):
        log(f"\r  页区间 {idx}/{len(runs)}（{start}-{end}）".ljust(28), end="", flush=True)
        result = converter.convert(str(path), page_range=(start, end))

        status = result.status
        if status == ConversionStatus.FAILURE:
            raise RuntimeError(f"docling 解析失败: {path.name} 页 {start}-{end}")

        doc = result.document
        # page_break_placeholder 非 None 才会生成分页标记；具体文本由子类替换。
        ser = serializer_cls(
            doc=doc,
            params=MarkdownParams(page_break_placeholder="page" if page_markers else None),
        )
        text = ser.serialize().text
        if page_markers:
            # docling 只在页间插标记，区间首页需补上，保证每页可定位。
            text = f"<!-- 第 {start} 页 -->\n\n{text}"
        parts.append(text)

        if status == ConversionStatus.PARTIAL_SUCCESS:
            print(
                f"  警告: {path.name} 页 {start}-{end} 部分转换成功（{status}）",
                file=sys.stderr,
            )

        # 尽快释放 ConversionResult：增强模式携带整份解析数据（单元格样式），占内存。
        del result, doc, ser

    log("\r" + " " * 28 + "\r", end="")

    markdown = "\n\n".join(part for part in parts if part).strip() + "\n"
    return markdown


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


def _build_front_matter(
    reader,
    source: Path,
    total: int,
    parser: str | None = None,
    pdf_mode: str | None = None,
    do_ocr: bool | None = None,
) -> str:
    """从 PDF 元数据生成 YAML front matter。"""
    try:
        meta = reader.metadata
    except Exception:
        meta = None
    fields: dict[str, str] = {"source": _yaml_str(source.name), "pages": str(total)}
    if parser == "docling":
        fields["parser"] = _yaml_str(f"docling-{_docling_version()}")
        if pdf_mode:
            fields["pdf_mode"] = _yaml_str(pdf_mode)
        fields["ocr"] = "true" if do_ocr else "false"
    elif parser:
        fields["parser"] = _yaml_str(parser)
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


def convert_pdf_markitdown(
    path: Path,
    *,
    page_markers: bool = True,
    metadata: bool = True,
    pages_spec: str | None = None,
    log=print,
) -> str:
    """旧路径：逐页用 markitdown 转换后拼接，插入页码标记并统计扫描页。

    无结构恢复、无 OCR，仅作为 docling 不可用或失败时的回退。
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
        parts.append(_build_front_matter(reader, path, total, parser="markitdown"))

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


def _convert_pdf(
    path: Path,
    *,
    pdf_parser: str,
    page_markers: bool,
    metadata: bool,
    pages_spec: str | None,
    mode: str = "auto",
    device: str = "auto",
    ocr: str = "auto",
) -> str:
    """按配置路由 PDF 解析路径：docling（默认）或 markitdown。

    docling 失败时自动回退 markitdown 逐页路径，保证转换总能产出结果。
    """
    if pdf_parser == "docling":
        if not docling_available():
            print(
                "  警告: 未安装 docling（pip install docling），回退 markitdown 路径",
                file=sys.stderr,
            )
            return convert_pdf_markitdown(
                path, page_markers=page_markers, metadata=metadata, pages_spec=pages_spec
            )
        try:
            return convert_pdf_docling(
                path,
                page_markers=page_markers,
                metadata=metadata,
                pages_spec=pages_spec,
                mode=mode,
                device=device,
                ocr=ocr,
            )
        except Exception as exc:
            print(f"  警告: docling 转换失败（{exc}），回退 markitdown 路径", file=sys.stderr)
    return convert_pdf_markitdown(
        path, page_markers=page_markers, metadata=metadata, pages_spec=pages_spec
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="把文件转换为 Markdown（PDF 用 docling 恢复结构，其他用 markitdown）"
    )
    parser.add_argument("inputs", nargs="+", type=Path, help="输入文件或目录")
    parser.add_argument("-o", "--output", type=Path, default=Path("markdown_output"), help="输出目录（默认 markdown_output/）")
    parser.add_argument("-r", "--recursive", action="store_true", help="递归处理目录")
    parser.add_argument("--pages", help="PDF 页码范围，如 '1-50' 或 '1-10,15'（1 基）")
    parser.add_argument(
        "--pdf-parser",
        choices=["docling", "markitdown"],
        default="docling",
        help="PDF 解析器（默认 docling，失败自动回退 markitdown）",
    )
    parser.add_argument(
        "--pdf-mode",
        choices=["auto", "light", "enhanced"],
        default="auto",
        help=(
            "PDF 结构恢复模式：auto 按书签/编号信号自动选择；"
            "light 仅书签+编号；enhanced 额外用字号/粗细推断标题层级"
        ),
    )
    parser.add_argument(
        "--device",
        choices=["auto", "cpu", "cuda"],
        default="auto",
        help="计算设备（auto 自动探测 CUDA，无 GPU 时退回 CPU）",
    )
    parser.add_argument(
        "--ocr",
        choices=["auto", "on", "off"],
        default="auto",
        help="OCR 开关（auto 按文本层质量：扫描件开、电子 PDF 关）",
    )
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
    total_start = time.monotonic()
    # 多文件串行处理（单 worker）：4G 显存同一时刻只跑一份 PDF，避免 OOM。
    for src, dst in pairs:
        print(f"{src} -> {dst}")
        start = time.monotonic()
        try:
            head = b""
            with open(src, "rb") as fh:
                head = fh.read(5)
            if _is_pdf(src, head):
                markdown = _convert_pdf(
                    src,
                    pdf_parser=args.pdf_parser,
                    page_markers=not args.no_page_markers,
                    metadata=not args.no_metadata,
                    pages_spec=args.pages if args.pages else None,
                    mode=args.pdf_mode,
                    device=args.device,
                    ocr=args.ocr,
                )
            else:
                markdown = convert_generic(src)
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_text(markdown, encoding="utf-8")
            elapsed = time.monotonic() - start
            print(f"  完成，{len(markdown)} 字符，耗时 {elapsed:.1f}s")
            ok += 1
        except Exception as exc:
            elapsed = time.monotonic() - start
            print(f"  失败（耗时 {elapsed:.1f}s）: {exc}", file=sys.stderr)
    total_elapsed = time.monotonic() - total_start
    rate = f"，平均 {total_elapsed / ok:.1f}s/文件" if ok else ""
    print(
        f"共 {ok}/{len(pairs)} 个文件转换成功，总耗时 {total_elapsed:.1f}s{rate}，"
        f"输出目录: {args.output.resolve()}"
    )
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

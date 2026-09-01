"""Extract visible text from .docx (OOXML zip) without external libraries."""

from __future__ import annotations

import io
import re
import zipfile


def extract_docx_plain_text(file_bytes: bytes) -> str:
    if not file_bytes or len(file_bytes) < 4:
        raise ValueError("空文件")
    if file_bytes[0:2] != b"PK":
        raise ValueError("不是 ZIP 格式（.docx 应为 PK 开头）")
    try:
        with zipfile.ZipFile(io.BytesIO(file_bytes)) as zf:
            if "word/document.xml" not in zf.namelist():
                raise ValueError("缺少 word/document.xml，可能不是有效 docx")
            xml = zf.read("word/document.xml").decode("utf-8", errors="replace")
    except zipfile.BadZipFile as exc:
        raise ValueError("ZIP 损坏或不是 docx") from exc
    parts = re.findall(r"<w:t[^>]*>([^<]*)</w:t>", xml)
    text = "".join(parts)
    text = text.replace("\u000b", " ").strip()
    return text

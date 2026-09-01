"""Minimal .docx writer (OOXML zip) — single body, plain paragraphs only."""

from __future__ import annotations

import io
import zipfile
from xml.sax.saxutils import escape


def write_plain_docx(text: str) -> bytes:
    """Write UTF-8 plain text as Word paragraphs (preserves line breaks)."""
    lines = (text or "").replace("\r\n", "\n").split("\n")
    body_parts: list[str] = []
    for line in lines:
        esc = escape(line)
        body_parts.append(
            f'<w:p><w:r><w:t xml:space="preserve">{esc}</w:t></w:r></w:p>'
        )
    body_xml = "".join(body_parts)
    document = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        f"<w:body>{body_xml}<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
        '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>'
    )
    content_types = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""
    rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""
    word_rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>
"""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("[Content_Types].xml", content_types)
        zf.writestr("_rels/.rels", rels)
        zf.writestr("word/_rels/document.xml.rels", word_rels)
        zf.writestr("word/document.xml", document.encode("utf-8"))
    return buf.getvalue()

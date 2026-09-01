"""
Data Bank 服务层 — 资料文档导入、切块、作用域检索。

作用域: global > character > session > message
"""
from __future__ import annotations

import logging
import re
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import RagChunkModel, RagDocumentModel

logger = logging.getLogger(__name__)

SCOPE_ORDER = {"global": 0, "character": 1, "session": 2, "message": 3}
MAX_CHUNK_TOKENS = 512
CHUNK_OVERLAP = 64


def _estimate_tokens(text: str) -> int:
    """估算 token 数（1 CJK ≈ 2 tokens, 1 English ≈ 0.5 token）。"""
    if not text:
        return 0
    cjk = len(re.findall(r"[\u4e00-\u9fff]", text))
    other = len(text) - cjk
    return cjk * 2 + int(other * 0.5)


def _split_into_chunks(text: str, max_tokens: int = MAX_CHUNK_TOKENS) -> list[str]:
    """按段落和长度切块。"""
    if not text.strip():
        return []
    paragraphs = re.split(r"\n\s*\n", text)
    chunks: list[str] = []
    current = ""
    current_tokens = 0
    for para in paragraphs:
        para = para.strip()
        if not para:
            continue
        para_tokens = _estimate_tokens(para)
        if para_tokens > max_tokens:
            # 长段落按句切
            sentences = re.split(r"(?<=[。！？.!?])", para)
            for sent in sentences:
                sent = sent.strip()
                if not sent:
                    continue
                sent_tokens = _estimate_tokens(sent)
                if current_tokens + sent_tokens > max_tokens and current:
                    chunks.append(current.strip())
                    current = ""
                    current_tokens = 0
                current += sent + "\n"
                current_tokens += sent_tokens
        elif current_tokens + para_tokens > max_tokens and current:
            chunks.append(current.strip())
            current = para
            current_tokens = para_tokens
        else:
            current += para + "\n"
            current_tokens += para_tokens
    if current.strip():
        chunks.append(current.strip())
    return chunks or [text.strip()]


def _tokenize(text: str) -> set[str]:
    """Tokenize text using 2-char sliding window for better recall."""
    if not text:
        return set()
    text_lower = text.lower()
    tokens = set()
    # Alphanumeric bigrams
    for match in re.finditer(r'[a-z0-9_]+', text_lower):
        word = match.group()
        if len(word) >= 2:
            for i in range(len(word) - 1):
                tokens.add(word[i:i+2])
        elif word:
            tokens.add(word)
    # CJK bigrams
    cjk_chars = re.findall(r'[\u4e00-\u9fff]', text_lower)
    for ch in cjk_chars:
        tokens.add(ch)
    for i in range(len(cjk_chars) - 1):
        tokens.add(cjk_chars[i] + cjk_chars[i+1])
    return tokens


def import_document(
    db: Session,
    title: str,
    raw_text: str,
    scope_type: str = "global",
    scope_id: int | None = None,
    source_kind: str = "manual",
    source_url: str = "",
    trust_level: str = "manual",
) -> RagDocumentModel:
    """导入一个资料文档，自动切块。"""
    doc = RagDocumentModel(
        scope_type=scope_type,
        scope_id=scope_id,
        title=title[:300],
        source_kind=source_kind,
        source_url=source_url[:1000],
        trust_level=trust_level,
        raw_text=raw_text,
        chunk_count=0,
    )
    db.add(doc)
    db.flush()

    chunks = _split_into_chunks(raw_text)
    for idx, chunk_text in enumerate(chunks):
        chunk = RagChunkModel(
            document_id=doc.id,
            chunk_index=idx,
            content=chunk_text,
            token_count=_estimate_tokens(chunk_text),
            metadata_json={"scope_type": scope_type, "scope_id": scope_id, "title": title},
        )
        db.add(chunk)
    doc.chunk_count = len(chunks)
    db.commit()
    logger.info("Imported RAG doc %r (%d chunks)", title, len(chunks))
    return doc


def search_chunks(
    db: Session,
    query: str,
    scope_type: str | None = None,
    scope_id: int | None = None,
    limit: int = 20,
) -> list[dict[str, Any]]:
    """按关键词搜索切块，支持作用域过滤。"""
    if not query.strip():
        return []

    query_tokens = _tokenize(query)
    if not query_tokens:
        return []

    stmt = select(RagChunkModel).order_by(RagChunkModel.id.desc()).limit(200)
    chunks = list(db.scalars(stmt))

    # Python 端过滤作用域（兼容 SQLite JSON）
    if scope_type:
        chunks = [c for c in chunks if c.metadata_json.get("scope_type") == scope_type]
        if scope_type != "global" and scope_id is not None:
            chunks = [c for c in chunks if c.metadata_json.get("scope_id") == scope_id]

    if not chunks:
        return []

    scored: list[tuple[float, RagChunkModel]] = []
    for chunk in chunks:
        chunk_tokens = _tokenize(chunk.content)
        overlap = len(query_tokens & chunk_tokens) if query_tokens else 0
        if overlap > 0:
            scored.append((float(overlap), chunk))
    scored.sort(key=lambda x: x[0], reverse=True)

    results = []
    for score, chunk in scored[:limit]:
        doc = db.get(RagDocumentModel, chunk.document_id)
        results.append({
            "chunk_id": chunk.id,
            "document_id": chunk.document_id,
            "title": doc.title if doc else "",
            "content": chunk.content[:500],
            "score": score,
            "scope_type": chunk.metadata_json.get("scope_type", "global"),
            "trust_level": doc.trust_level if doc else "unverified",
            "token_count": chunk.token_count,
        })
    return results


def get_context_chunks(
    db: Session,
    scopes: list[tuple[str, int | None]],
    query: str = "",
    token_budget: int = 1200,
) -> list[dict[str, Any]]:
    """按作用域优先级收集资料块，用于注入 Prompt。"""
    all_chunks: list[dict[str, Any]] = []
    used_tokens = 0

    for scope_type, scope_id in sorted(scopes, key=lambda s: SCOPE_ORDER.get(s[0], 99)):
        chunks = search_chunks(db, query, scope_type=scope_type, scope_id=scope_id, limit=10)
        for c in chunks:
            if c["token_count"] + used_tokens > token_budget:
                break
            all_chunks.append(c)
            used_tokens += c["token_count"]
        if used_tokens >= token_budget:
            break

    return all_chunks

"""
RAG / 知识库层路由：百科全书条目检索、语义搜索、资料库文档管理。
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import EncyclopediaEntryModel, RagDocumentModel, RagChunkModel, WorldEncyclopediaModel
from ..services.rag_service import import_document, search_chunks

router = APIRouter(prefix="/rag", tags=["知识库检索"])


@router.get("/search")
def rag_search(q: str, encyclopedia_id: int | None = None, db: Session = Depends(get_db)):
    """全文搜索百科条目。"""
    if not q.strip():
        return []
    like = f"%{q.strip()}%"
    stmt = select(EncyclopediaEntryModel).where(
        EncyclopediaEntryModel.title.contains(q.strip()) | EncyclopediaEntryModel.content.contains(q.strip())
    )
    if encyclopedia_id:
        stmt = stmt.where(EncyclopediaEntryModel.encyclopedia_id == encyclopedia_id)
    entries = db.scalars(stmt.order_by(EncyclopediaEntryModel.is_featured.desc()).limit(20)).all()
    result = []
    for e in entries:
        enc = db.get(WorldEncyclopediaModel, e.encyclopedia_id)
        meta = e.meta_json or {}
        result.append({
            "id": e.id,
            "title": e.title,
            "entry_type": e.entry_type,
            "summary": e.summary,
            "highlight": e.content[:200] if e.content else "",
            "encyclopedia_name": enc.name if enc else "",
            "tags": (e.tags or "").split(",") if e.tags else [],
            "source_url": meta.get("source_url", ""),
            "verification_status": meta.get("verification_status", ""),
            "source_trust_level": meta.get("source_trust_level", ""),
        })
    return result


@router.get("/context")
def rag_context(encyclopedia_id: int, db: Session = Depends(get_db)):
    """获取指定百科库的完整上下文文本（用于注入 System Prompt）。"""
    entries = db.scalars(
        select(EncyclopediaEntryModel)
        .where(EncyclopediaEntryModel.encyclopedia_id == encyclopedia_id, EncyclopediaEntryModel.is_featured == True)
        .order_by(EncyclopediaEntryModel.sort_order.asc())
    ).all()
    if not entries:
        entries = db.scalars(
            select(EncyclopediaEntryModel)
            .where(EncyclopediaEntryModel.encyclopedia_id == encyclopedia_id)
            .order_by(EncyclopediaEntryModel.sort_order.asc())
            .limit(30)
        ).all()
    context = []
    for e in entries:
        meta = e.meta_json or {}
        source_line = ""
        if meta.get("source_url") or meta.get("verification_status"):
            source_line = f"\n来源: {meta.get('source_url', '未记录')} | 校验: {meta.get('verification_status', 'unverified')}"
        context.append(f"## {e.title}\n{e.content}{source_line}")
    return {"context": "\n\n".join(context), "entry_count": len(entries)}


@router.post("/documents")
def create_rag_document(payload: dict, db: Session = Depends(get_db)):
    """导入一个资料文档（手动/URL）。"""
    title = payload.get("title", "").strip() or "未命名资料"
    raw_text = payload.get("raw_text", "").strip()
    if not raw_text:
        raise HTTPException(status_code=400, detail="raw_text 不能为空")
    scope_type = payload.get("scope_type", "global")
    scope_id = payload.get("scope_id")
    source_kind = payload.get("source_kind", "manual")
    source_url = payload.get("source_url", "")
    trust_level = payload.get("trust_level", "manual")
    doc = import_document(db, title, raw_text, scope_type, scope_id, source_kind, source_url, trust_level)
    return {"id": doc.id, "title": doc.title, "chunk_count": doc.chunk_count}


@router.get("/documents")
def list_rag_documents(
    scope_type: str | None = None,
    scope_id: int | None = None,
    db: Session = Depends(get_db)):
    """列出所有资料文档，可过滤作用域。"""
    stmt = select(RagDocumentModel).order_by(RagDocumentModel.id.desc())
    if scope_type:
        stmt = stmt.where(RagDocumentModel.scope_type == scope_type)
        if scope_type != "global" and scope_id is not None:
            stmt = stmt.where(RagDocumentModel.scope_id == scope_id)
    docs = db.scalars(stmt.limit(50)).all()
    return [
        {
            "id": d.id,
            "title": d.title,
            "scope_type": d.scope_type,
            "scope_id": d.scope_id,
            "source_kind": d.source_kind,
            "source_url": d.source_url,
            "chunk_count": d.chunk_count,
            "trust_level": d.trust_level,
            "verification_status": d.verification_status,
            "created_at": d.created_at.isoformat(),
        }
        for d in docs
    ]


@router.delete("/documents/{doc_id}")
def delete_rag_document(doc_id: int, db: Session = Depends(get_db)):
    """删除资料文档及其所有切块。"""
    doc = db.get(RagDocumentModel, doc_id)
    if not doc:
        raise HTTPException(status_code=404, detail="文档不存在")
    chunks = db.scalars(select(RagChunkModel).where(RagChunkModel.document_id == doc_id))
    for chunk in chunks:
        db.delete(chunk)
    db.delete(doc)
    db.commit()
    return {"ok": True}


@router.get("/databank/search")
def databank_search(
    q: str,
    scope_type: str | None = None,
    scope_id: int | None = None,
    db: Session = Depends(get_db)):
    """搜索 Data Bank 资料库中的切块内容。"""
    return search_chunks(db, q, scope_type, scope_id, limit=20)

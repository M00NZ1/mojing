from __future__ import annotations

from fastapi import HTTPException
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..models import StoryGenerationDraftModel, StoryRequestReceiptModel
from ..schemas import StoryGenerationRequestStatus, StoryWritingRequest
from .story_request_receipt import claim_request, find_receipt, payload_hash as request_payload_hash, release_request

DRAFT_VERSION = 1


def save_generation_draft(
    db: Session,
    *,
    request_id: str,
    payload: StoryWritingRequest,
    payload_hash: str,
    context_text: str,
    draft_json: dict,
) -> StoryGenerationDraftModel:
    row = StoryGenerationDraftModel(
        request_id=request_id,
        draft_version=DRAFT_VERSION,
        payload_hash=payload_hash,
        payload_json=payload.model_dump(mode="json", exclude={"request_id"}),
        context_text=context_text,
        draft_json=draft_json,
    )
    try:
        db.add(row)
        db.commit()
        return row
    except IntegrityError:
        db.rollback()
        existing = db.get(StoryGenerationDraftModel, request_id)
        if existing is None:
            raise
        return load_generation_draft(db, request_id, payload_hash)
    except Exception as exc:
        db.rollback()
        raise HTTPException(status_code=503, detail="完整正文暂存失败，请稍后重试") from exc


def load_generation_draft(db: Session, request_id: str, payload_hash: str) -> StoryGenerationDraftModel | None:
    row = db.get(StoryGenerationDraftModel, request_id)
    if row is None:
        return None
    if row.draft_version != DRAFT_VERSION:
        raise HTTPException(status_code=409, detail="不支持的故事正文暂存版本")
    if row.payload_hash != payload_hash:
        raise HTTPException(status_code=409, detail="本次创作的设定已改变，请修改内容后重新开始")
    if not isinstance(row.payload_json, dict) or not isinstance(row.draft_json, dict):
        raise HTTPException(status_code=409, detail="本次创作正文暂存已损坏，请修改内容后重新开始")
    try:
        stored_payload = StoryWritingRequest.model_validate({**row.payload_json, "request_id": request_id})
    except Exception as exc:
        raise HTTPException(status_code=409, detail="本次创作正文暂存已损坏，请修改内容后重新开始") from exc
    if request_payload_hash(stored_payload) != row.payload_hash:
        raise HTTPException(status_code=409, detail="本次创作正文暂存已损坏，请修改内容后重新开始")
    if not isinstance(row.draft_json.get("title"), str) or not isinstance(row.draft_json.get("chapters"), list) or not row.draft_json["chapters"]:
        raise HTTPException(status_code=409, detail="本次创作正文暂存已损坏，请修改内容后重新开始")
    _draft_text(row.draft_json)
    return row


def _draft_text(draft_json: dict) -> tuple[str, int]:
    if not isinstance(draft_json, dict):
        raise HTTPException(status_code=409, detail="本次创作正文暂存已损坏，请修改内容后重新开始")
    title = draft_json.get("title")
    chapters = draft_json.get("chapters")
    if not isinstance(title, str) or not isinstance(chapters, list) or not chapters:
        raise HTTPException(status_code=409, detail="本次创作正文暂存已损坏，请修改内容后重新开始")
    parts = [title]
    for index, chapter in enumerate(chapters, start=1):
        if not isinstance(chapter, dict) or not isinstance(chapter.get("content"), str) or not chapter["content"].strip():
            raise HTTPException(status_code=409, detail="本次创作正文暂存已损坏，请修改内容后重新开始")
        parts.append(f"{chapter.get('title') or f'第 {index} 章'}\n\n{chapter['content']}")
    return "\n\n".join(parts), len(chapters)


def read_request_status(db: Session, request_id: str) -> StoryGenerationRequestStatus:
    receipt = db.get(StoryRequestReceiptModel, request_id)
    if receipt is not None:
        result = find_receipt(db, request_id, receipt.payload_hash)
        return StoryGenerationRequestStatus(status="saved", request_id=request_id, title=result.title, chapter_count=result.chapter_count, session_id=result.session_id)
    row = db.get(StoryGenerationDraftModel, request_id)
    if row is None:
        return StoryGenerationRequestStatus(status="missing", request_id=request_id)
    if row.draft_version != DRAFT_VERSION:
        raise HTTPException(status_code=409, detail="不支持的故事正文暂存版本")
    text, chapter_count = _draft_text(row.draft_json)
    return StoryGenerationRequestStatus(status="draft", request_id=request_id, title=row.draft_json["title"], text=text, chapter_count=chapter_count)


async def delete_generation_draft(db: Session, request_id: str) -> dict[str, bool]:
    await claim_request(request_id)
    try:
        if db.get(StoryRequestReceiptModel, request_id) is not None:
            raise HTTPException(status_code=409, detail="本次创作已完成，不能删除会话回执")
        row = db.get(StoryGenerationDraftModel, request_id)
        if row is not None:
            db.delete(row)
        try:
            db.commit()
        except Exception as exc:
            db.rollback()
            raise HTTPException(status_code=503, detail="正文暂存未能删除，请稍后重试") from exc
        return {"deleted": True}
    except HTTPException:
        db.rollback()
        raise
    except Exception as exc:
        db.rollback()
        raise HTTPException(status_code=503, detail="正文暂存未能删除，请稍后重试") from exc
    finally:
        await release_request(request_id)

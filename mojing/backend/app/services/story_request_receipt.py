from __future__ import annotations

import asyncio
import hashlib
import json
from datetime import timezone

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import ChatSessionModel, StoryRequestReceiptModel
from ..schemas import StoryWritingRequest, StoryWritingResult

_active_request_ids: set[str] = set()
_active_guard = asyncio.Lock()
RECEIPT_VERSION = 1


def payload_hash(payload: StoryWritingRequest) -> str:
    body = payload.model_dump(mode="json", exclude={"request_id"})
    canonical = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def find_receipt(db: Session, request_id: str, expected_hash: str) -> StoryWritingResult | None:
    receipt = db.scalar(select(StoryRequestReceiptModel).where(StoryRequestReceiptModel.request_id == request_id))
    if receipt is None:
        return None
    if receipt.receipt_version != RECEIPT_VERSION:
        raise HTTPException(status_code=409, detail="不支持的故事请求回执版本")
    if receipt.payload_hash != expected_hash:
        raise HTTPException(status_code=409, detail="本次创作的设定已改变，请修改内容后重新开始")
    session = db.get(ChatSessionModel, receipt.session_id)
    if session is None or _utc_naive(session.created_at) != _utc_naive(receipt.session_created_at):
        raise HTTPException(status_code=410, detail="本次创作会话已删除，请修改内容后开始新作")
    if not isinstance(receipt.result_json, dict) or receipt.result_json.get("session_id") != receipt.session_id:
        raise HTTPException(status_code=409, detail="本次创作回执已损坏，请修改内容后重新开始")
    try:
        return StoryWritingResult.model_validate(receipt.result_json)
    except Exception as exc:
        raise HTTPException(status_code=409, detail="本次创作回执已损坏，请修改内容后重新开始") from exc


def _utc_naive(value):
    if value.tzinfo is not None:
        value = value.astimezone(timezone.utc).replace(tzinfo=None)
    return value


async def claim_request(request_id: str) -> None:
    async with _active_guard:
        if request_id in _active_request_ids:
            raise HTTPException(status_code=409, detail="本次创作仍在生成，请稍后继续")
        _active_request_ids.add(request_id)


async def release_request(request_id: str) -> None:
    async with _active_guard:
        _active_request_ids.discard(request_id)

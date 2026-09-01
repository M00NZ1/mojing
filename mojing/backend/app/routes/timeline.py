"""
世界时间线管理 API。
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import ChatSessionModel, SessionWorldModel
from ..schemas import TimelineEntry, TimelineRead

router = APIRouter(prefix="/sessions/{session_id}/timeline", tags=["世界时间线"])


@router.get("", response_model=TimelineRead)
def get_timeline(session_id: int, db: Session = Depends(get_db)):
    world = db.query(SessionWorldModel).where(SessionWorldModel.session_id == session_id).first()
    if world is None:
        return {"entries": [], "gameplay_mode": "自由剧情"}
    return {
        "entries": list(world.world_timeline_json or []),
        "gameplay_mode": world.gameplay_mode or "自由剧情",
    }


@router.post("", response_model=TimelineRead)
def add_timeline_entry(session_id: int, payload: TimelineEntry, db: Session = Depends(get_db)):
    world = db.query(SessionWorldModel).where(SessionWorldModel.session_id == session_id).first()
    if world is None:
        session = db.get(ChatSessionModel, session_id)
        if session is None:
            raise HTTPException(status_code=404, detail="会话不存在")
        raise HTTPException(status_code=400, detail="请先设置世界配置")
    entries = list(world.world_timeline_json or [])
    entry = {
        "title": payload.title,
        "description": payload.description,
        "entry_type": payload.entry_type or "事件",
        "timestamp": payload.timestamp or "",
        "era": payload.era or "",
    }
    entries.append(entry)
    world.world_timeline_json = entries
    db.commit()
    return {"entries": entries, "gameplay_mode": world.gameplay_mode}


@router.delete("/{entry_index}")
def delete_timeline_entry(session_id: int, entry_index: int, db: Session = Depends(get_db)):
    world = db.query(SessionWorldModel).where(SessionWorldModel.session_id == session_id).first()
    if world is None:
        raise HTTPException(status_code=404, detail="世界配置不存在")
    entries = list(world.world_timeline_json or [])
    if entry_index < 0 or entry_index >= len(entries):
        raise HTTPException(status_code=404, detail="时间线条目不存在")
    entries.pop(entry_index)
    world.world_timeline_json = entries
    db.commit()
    return {"ok": True}

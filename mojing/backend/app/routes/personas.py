from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import PersonaModel

router = APIRouter(prefix="/personas", tags=["用户人设"])


@router.get("", summary="获取人设列表")
def list_personas(db: Session = Depends(get_db)):
    rows = db.scalars(select(PersonaModel).order_by(PersonaModel.updated_at.desc())).all()
    return rows


@router.get("/active", summary="获取当前人设")
def get_active_persona(db: Session = Depends(get_db)):
    row = db.scalar(select(PersonaModel).where(PersonaModel.is_active == True))
    if not row:
        return {"id": 0, "name": "玩家", "description": "", "avatar_color": "#53c7a8", "avatar_image_path": ""}
    return row


@router.post("", summary="保存人设")
def save_persona(payload: dict, db: Session = Depends(get_db)):
    persona_id = payload.get("id")
    if persona_id and persona_id > 0:
        row = db.get(PersonaModel, persona_id)
        if not row:
            raise HTTPException(status_code=404, detail="人设未找到")
        row.name = payload.get("name", row.name)
        row.description = payload.get("description", row.description)
        row.avatar_color = payload.get("avatar_color", row.avatar_color)
        row.avatar_image_path = payload.get("avatar_image_path", row.avatar_image_path)
        row.is_active = payload.get("is_active", row.is_active)
    else:
        row = PersonaModel(
            name=payload.get("name", "玩家"),
            description=payload.get("description", ""),
            avatar_color=payload.get("avatar_color", "#53c7a8"),
            avatar_image_path=payload.get("avatar_image_path", ""),
            is_active=payload.get("is_active", True))
        db.add(row)
    db.commit()
    db.refresh(row)
    return row


@router.delete("/{persona_id}", summary="删除人设")
def delete_persona(persona_id: int, db: Session = Depends(get_db)):
    row = db.get(PersonaModel, persona_id)
    if not row:
        raise HTTPException(status_code=404, detail="人设未找到")
    db.delete(row)
    db.commit()
    return {"ok": True}

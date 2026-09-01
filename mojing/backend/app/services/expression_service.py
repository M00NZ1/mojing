"""
角色表情/立绘管理服务。
"""
from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import CharacterExpressionModel


def list_expressions(db: Session, character_id: int) -> list[dict]:
    entries = db.scalars(
        select(CharacterExpressionModel)
        .where(CharacterExpressionModel.character_id == character_id)
        .order_by(CharacterExpressionModel.sort_order.asc(), CharacterExpressionModel.id.asc())
    ).all()
    return [
        {
            "id": e.id,
            "expression": e.expression,
            "label": e.label,
            "image_path": e.image_path,
            "sort_order": e.sort_order,
        }
        for e in entries
    ]


def save_expression(db: Session, character_id: int, data: dict) -> dict:
    expr_id = data.get("id")
    if expr_id:
        entry = db.get(CharacterExpressionModel, expr_id)
        if not entry or entry.character_id != character_id:
            raise ValueError("表情不存在")
    else:
        entry = CharacterExpressionModel(character_id=character_id)
        db.add(entry)
    entry.expression = data.get("expression", "default")
    entry.label = data.get("label", "")
    entry.image_path = data.get("image_path", "")
    entry.sort_order = data.get("sort_order", 0)
    db.flush()
    db.commit()
    return {
        "id": entry.id,
        "expression": entry.expression,
        "label": entry.label,
        "image_path": entry.image_path,
        "sort_order": entry.sort_order,
    }


def delete_expression(db: Session, expression_id: int) -> bool:
    entry = db.get(CharacterExpressionModel, expression_id)
    if not entry:
        return False
    db.delete(entry)
    db.commit()
    return True

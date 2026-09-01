"""
角色表情/立绘管理 API。
"""
from pathlib import Path
import shutil

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from sqlalchemy.orm import Session

from ..config import STORAGE_DIR
from ..database import get_db
from ..models import CharacterModel
from ..services.expression_service import delete_expression, list_expressions, save_expression

router = APIRouter(prefix="/characters/{character_id}/expressions", tags=["角色表情"])


@router.get("", summary="获取表情列表")
def get_expressions(character_id: int, db: Session = Depends(get_db)):
    return list_expressions(db, character_id)


@router.post("", summary="保存表情")
def create_expression(character_id: int, payload: dict, db: Session = Depends(get_db)):
    try:
        return save_expression(db, character_id, payload)
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))


@router.post("/upload", summary="上传表情图片")
def upload_expression_image(character_id: int, expression: str = "default", file: UploadFile = File(...), db: Session = Depends(get_db)):
    character = db.get(CharacterModel, character_id)
    if character is None:
        raise HTTPException(status_code=404, detail="人物不存在")
    if not (file.content_type or "").startswith("image/"):
        raise HTTPException(status_code=400, detail="表情只支持图片文件")

    expr_dir = STORAGE_DIR / "expressions" / f"char_{character_id:04d}"
    expr_dir.mkdir(parents=True, exist_ok=True)
    safe_name = Path(file.filename or "expression.png").name
    target = expr_dir / safe_name
    with target.open("wb") as output:
        shutil.copyfileobj(file.file, output)

    relative_path = str(target.relative_to(STORAGE_DIR)).replace("\\", "/")
    payload = {"expression": expression, "label": expression, "image_path": relative_path}
    try:
        return save_expression(db, character_id, payload)
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))


@router.delete("/{expression_id}", summary="删除表情")
def remove_expression(character_id: int, expression_id: int, db: Session = Depends(get_db)):
    if not delete_expression(db, expression_id):
        raise HTTPException(status_code=404, detail="表情不存在")
    return {"ok": True}

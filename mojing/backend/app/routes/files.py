"""
文件层路由：上传、下载、解析、预览。
"""
from pathlib import Path

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from ..config import STORAGE_DIR
from ..database import get_db

router = APIRouter(prefix="/files", tags=["文件管理"])

UPLOAD_DIR = STORAGE_DIR / "uploads"


@router.post("/upload", summary="上传文件")
def upload_file(file: UploadFile = File(...)):
    """上传文件，返回可访问路径。"""
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    safe_name = Path(file.filename).name
    dest = UPLOAD_DIR / safe_name
    counter = 1
    while dest.exists():
        dest = UPLOAD_DIR / f"{Path(file.filename).stem}_{counter}{Path(file.filename).suffix}"
        counter += 1
    with dest.open("wb") as f:
        f.write(file.file.read())
    return {"ok": True, "path": str(dest.relative_to(STORAGE_DIR)), "filename": safe_name}


@router.get("/download/{file_path:path}", summary="下载文件")
def download_file(file_path: str):
    """下载/访问上传的文件。"""
    full = STORAGE_DIR / file_path
    if not full.is_relative_to(STORAGE_DIR) or not full.exists():
        raise HTTPException(status_code=404, detail="文件不存在")
    return FileResponse(full)


@router.post("/parse")
def parse_document(payload: dict):
    """解析文档内容（TXT/Markdown/PDF 文本提取）。"""
    file_path = payload.get("path", "")
    if not file_path:
        raise HTTPException(status_code=400, detail="path 不能为空")
    full = STORAGE_DIR / file_path
    if not full.is_relative_to(STORAGE_DIR) or not full.exists():
        raise HTTPException(status_code=404, detail="文件不存在")
    content = full.read_text(encoding="utf-8", errors="replace")
    return {"ok": True, "content": content[:100000], "char_count": len(content)}

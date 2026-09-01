"""
图像生成 API 路由。
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..database import get_db
from ..services.image_client_service import resolve_public_image_credentials
from ..services.image_service import generate_image

router = APIRouter(prefix="/images", tags=["图像生成"])


@router.post("/generate")
async def generate_image_endpoint(payload: dict, db: Session = Depends(get_db)):
    """生成图片。"""
    prompt = (payload.get("prompt") or "").strip()
    if not prompt:
        raise HTTPException(status_code=400, detail="prompt 不能为空")

    default_api_key, default_base_url, default_model = resolve_public_image_credentials(db)

    result = await generate_image(
        prompt=prompt,
        api_key=payload.get("api_key") or default_api_key,
        base_url=payload.get("base_url") or default_base_url,
        model=payload.get("model") or default_model,
        size=payload.get("size", "1024x1024"),
        negative_prompt=payload.get("negative_prompt", ""),
        steps=payload.get("steps", 20),
        timeout=payload.get("timeout", 60))
    return result

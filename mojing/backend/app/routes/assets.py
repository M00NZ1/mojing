from __future__ import annotations

from fastapi import APIRouter, Depends

from ..schemas import AssetItemRead
from ..services.asset_service import list_asset_categories, list_asset_items



router = APIRouter(prefix="/assets", tags=["资源中心"])


@router.get("", summary="获取资源列表", response_model=list[AssetItemRead])
def list_assets(category: str = "", q: str = ""):
    return list_asset_items(category, q)


@router.get("/categories", response_model=list[str])
def list_categories():
    return list_asset_categories()

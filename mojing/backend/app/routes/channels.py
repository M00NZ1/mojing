from fastapi import APIRouter, HTTPException

from ..services.channel_service import (
    delete_channel,
    get_provider_presets,
    get_purpose_options,
    list_channels,
    save_channel)

router = APIRouter(prefix="/channels", tags=["API 渠道"])


@router.get("")
def api_list_channels():
    return list_channels()


@router.post("")
def api_save_channel(payload: dict):
    if not payload.get("id"):
        raise HTTPException(status_code=400, detail="渠道 ID 不能为空")
    if not payload.get("label"):
        raise HTTPException(status_code=400, detail="渠道名称不能为空")
    return save_channel(payload)


@router.delete("/{channel_id}")
def api_delete_channel(channel_id: str):
    delete_channel(channel_id)
    return {"ok": True}


@router.get("/providers")
def api_provider_presets():
    return get_provider_presets()


@router.get("/purposes")
def api_purpose_options():
    return get_purpose_options()

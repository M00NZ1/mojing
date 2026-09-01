import importlib
import platform

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import CharacterModel, VoiceProfileModel

from ..schemas import (
    LocalConfigRead,
    LocalConfigUpdate,
    ProbePublicApiRequest,
    PublicApiProbeAttempt,
    PublicApiProbeResult,
    SystemStatusRead,
    VoiceServiceConfigRead,
    VoiceServiceConfigUpdate,
)
from ..services.public_api_probe import encode_probe_stream_line, iter_public_api_probe_events, run_probe
from ..services.crypto_service import decrypt_api_key, is_secret_mask
from ..services.system_config_service import (
    get_local_config,
    get_voice_service_config,
    mask_local_config_for_api,
    mask_voice_service_config_for_api,
    set_local_config,
    set_voice_service_config,
)


router = APIRouter(prefix="/system", tags=["系统"])


def _resolve_probe_key(payload: ProbePublicApiRequest, db: Session) -> str | None:
    if not is_secret_mask(payload.api_key):
        return payload.api_key
    if payload.character_id is None:
        return None
    character = db.get(CharacterModel, payload.character_id)
    if character is None:
        return None
    field = {
        "text": "api_key",
        "image": "image_gen_api_key",
        # 当前 voice probe 是语音转写（STT），真实转写链使用角色对话 Key；
        # voice_api_key 预留给外部 TTS，不能发送到 STT 供应商。
        "voice": "api_key",
    }[payload.channel]
    return decrypt_api_key(str(getattr(character, field) or "")) or None


def _has_module(module_name: str) -> bool:
    try:
        importlib.import_module(module_name)
        return True
    except Exception:
        return False


@router.get("/status", summary="获取系统状态", response_model=SystemStatusRead)
def get_system_status(db: Session = Depends(get_db)):
    builtin_voice_count = db.scalar(
        select(func.count()).where(VoiceProfileModel.provider == "edge_tts_builtin").select_from(VoiceProfileModel)
    ) or 0
    cloning_ready = _has_module("TTS")
    voice_config = get_voice_service_config(db)
    return {
        "database_ready": True,
        "builtin_tts_ready": _has_module("edge_tts"),
        "cloning_tts_ready": cloning_ready,
        "builtin_voice_count": int(builtin_voice_count),
        "python_version": platform.python_version(),
        "cloning_status_message": (
            "本机当前主 Python 为 3.12，Coqui TTS 官方包不支持 3.12，当前只能先使用免费内置声线；后续可改为独立 3.11 子服务或外部语音服务。"
            if not cloning_ready
            else "本机已具备本地声线克隆依赖。"
        ),
        "external_voice_enabled": bool(voice_config.get("enabled") and voice_config.get("external_base_url")),
        "recommended_flow": [
            "先在人物页选择默认模板或手动填写人物设定。",
            "再配置该人物的 API Key、Base URL 和模型。",
            "如果有人物长设定，导入 TXT 抽取人物卡。",
            "如果没有训练音频，先绑定内置免费声线。",
            "进入会话页绑定人物、填写背景、按需开启自动调度和旁白器。",
            "如果需要看图，让当前参与人物使用支持视觉的模型。",
        ],
    }


@router.get("/voice-service-config", response_model=VoiceServiceConfigRead)
def get_voice_config(db: Session = Depends(get_db)):
    return mask_voice_service_config_for_api(get_voice_service_config(db))


@router.put("/voice-service-config", response_model=VoiceServiceConfigRead)
def update_voice_config(payload: VoiceServiceConfigUpdate, db: Session = Depends(get_db)):
    current = get_voice_service_config(db)
    raw = payload.model_dump()
    clear_external = bool(raw.pop("clear_external_api_key", False))
    patch = {k: v for k, v in raw.items() if v is not None}
    current.update(patch)
    clear_fields = {"external_api_key"} if clear_external else set()
    return mask_voice_service_config_for_api(
        set_voice_service_config(db, current, clear_secret_fields=clear_fields)
    )


@router.get("/local-config", response_model=LocalConfigRead)
def get_config(db: Session = Depends(get_db)):
    return mask_local_config_for_api(get_local_config(db))


@router.put("/local-config", response_model=LocalConfigRead)
def update_config(payload: LocalConfigUpdate, db: Session = Depends(get_db)):
    current = get_local_config(db)
    raw = payload.model_dump()
    clear_fields = {
        field
        for flag, field in (
            ("clear_public_text_api_key", "public_text_api_key"),
            ("clear_public_image_api_key", "public_image_api_key"),
            ("clear_public_voice_api_key", "public_voice_api_key"),
        )
        if bool(raw.pop(flag, False))
    }
    patch = {k: v for k, v in raw.items() if v is not None}
    current.update(patch)
    return mask_local_config_for_api(
        set_local_config(db, current, clear_secret_fields=clear_fields)
    )


@router.post("/probe-public-api", response_model=PublicApiProbeResult, summary="测试公共 API（文本 / 生图 / 语音转写）")
async def probe_public_api(payload: ProbePublicApiRequest, db: Session = Depends(get_db)):
    cfg = get_local_config(db)
    raw = await run_probe(
        payload.channel,
        cfg,
        payload.base_url,
        _resolve_probe_key(payload, db),
        payload.model,
    )
    attempts = [
        PublicApiProbeAttempt(
            base_url=str(a.get("base_url") or ""),
            ok=bool(a.get("ok")),
            error=a.get("error"),
            detail=a.get("detail"),
        )
        for a in (raw.get("attempts") or [])
    ]
    return PublicApiProbeResult(
        channel=payload.channel,
        ok=bool(raw.get("ok")),
        error=raw.get("error"),
        detail=raw.get("detail"),
        warnings=list(raw.get("warnings") or []),
        meta=raw.get("meta"),
        used_base_url=raw.get("used_base_url"),
        attempts=attempts,
    )


@router.post(
    "/probe-public-api/stream",
    summary="测试公共 API（流式进度，多线路依次尝试）",
)
async def probe_public_api_stream(payload: ProbePublicApiRequest, db: Session = Depends(get_db)):
    cfg = get_local_config(db)

    async def gen():
        async for ev in iter_public_api_probe_events(
            cfg,
            payload.channel,
            payload.base_url,
            _resolve_probe_key(payload, db),
            payload.model,
        ):
            yield encode_probe_stream_line(ev)

    return StreamingResponse(gen(), media_type="application/x-ndjson; charset=utf-8")


@router.get("/macros")
def list_available_macros():
    """返回系统支持的宏变量列表，供前端提示词编辑器使用。"""
    from ..services.macro_service import get_available_macros
    return get_available_macros()

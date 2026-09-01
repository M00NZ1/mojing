import asyncio
import shutil
import uuid
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import VoiceProfileModel
from ..services.system_config_service import get_voice_service_config
from ..schemas import VoiceProfileRead, VoiceSynthesisResponse
from ..services.voice_service import (
    VOICE_STORAGE,
    AUDIO_STORAGE,
    _synthesize_narration,
    _synthesize_with_xtts,
    synthesize_message_audio)



router = APIRouter(prefix="/voices", tags=["声色"])


@router.get("", response_model=list[VoiceProfileRead])
def list_voice_profiles(db: Session = Depends(get_db)):
    return list(db.scalars(select(VoiceProfileModel).order_by(VoiceProfileModel.id.desc())))


@router.post("/upload", response_model=VoiceProfileRead)
async def upload_voice_reference(
    name: str = Form(...),
    description: str = Form(""),
    language: str = Form("zh-cn"),
    provider: str = Form("xtts_v2"),
    file: UploadFile = File(...),
    db: Session = Depends(get_db)):
    suffix = Path(file.filename or "reference.wav").suffix or ".wav"
    target_dir = VOICE_STORAGE / name
    target_dir.mkdir(parents=True, exist_ok=True)
    target_file = target_dir / f"reference{suffix}"
    with target_file.open("wb") as output:
        shutil.copyfileobj(file.file, output)

    profile = VoiceProfileModel(
        name=name,
        description=description,
        language=language,
        provider=provider,
        reference_audio_path=str(target_file))
    db.add(profile)
    db.commit()
    db.refresh(profile)
    return profile


@router.post("/synthesize/message/{message_id}", response_model=VoiceSynthesisResponse)
async def synthesize_message(message_id: int, db: Session = Depends(get_db)):
    total = float(get_voice_service_config(db).get("message_synthesis_total_timeout_seconds", 300))
    try:
        clips = await asyncio.wait_for(synthesize_message_audio(db, message_id), timeout=total)
    except asyncio.TimeoutError as exc:
        raise HTTPException(
            status_code=503,
            detail="语音合成总超时，请缩短正文或检查声线 / XTTS / 网络；可在 voice_service_config.message_synthesis_total_timeout_seconds 调大上限。",
        ) from exc
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {"message_id": message_id, "clips": clips}


@router.post("/{voice_id}/preview")
async def preview_voice(voice_id: int, db: Session = Depends(get_db)):
    """试听声线 — 生成一句示例语音并返回音频文件。"""
    voice = db.get(VoiceProfileModel, voice_id)
    if voice is None:
        raise HTTPException(status_code=404, detail="声线不存在")
    sample_text = "你好，我是人工智能助手，很高兴认识你。"
    xtts_timeout = float(get_voice_service_config(db).get("xtts_timeout_seconds", 90))
    try:
        if voice.provider == "edge_tts_builtin" and voice.reference_audio_path.startswith("builtin:"):
            voice_name = voice.reference_audio_path.replace("builtin:", "", 1).strip()
            output_path = AUDIO_STORAGE / f"preview_{voice_id}_{uuid.uuid4().hex[:8]}.mp3"
            await _synthesize_narration(sample_text, output_path, voice_name)
            return FileResponse(path=output_path, media_type="audio/mpeg", filename=f"preview_{voice_id}.mp3")
        output_path = AUDIO_STORAGE / f"preview_{voice_id}_{uuid.uuid4().hex[:8]}.wav"
        try:
            await asyncio.wait_for(
                _synthesize_with_xtts(sample_text, output_path, voice.reference_audio_path, voice.language),
                timeout=xtts_timeout,
            )
        except asyncio.TimeoutError as exc:
            raise HTTPException(
                status_code=504,
                detail=f"预览合成超时（>{xtts_timeout:.0f}s），无 GPU/未装 Coqui 时请改用 edge_tts_builtin 声线。",
            ) from exc
        return FileResponse(path=output_path, media_type="audio/wav", filename=f"preview_{voice_id}.wav")
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"语音合成失败：{exc}") from exc


@router.post("/transcribe")
async def transcribe_voice(
    file: UploadFile = File(...),
    character_id: int | None = None,
    db: Session = Depends(get_db),
):
    """语音转文字端点。"""
    if not file.filename:
        raise HTTPException(status_code=400, detail="请上传音频文件")

    upload_dir = VOICE_STORAGE / "uploads"
    upload_dir.mkdir(parents=True, exist_ok=True)
    file_ext = Path(file.filename).suffix or ".webm"
    saved_path = upload_dir / f"{uuid.uuid4().hex}{file_ext}"

    with open(saved_path, "wb") as buffer:
        shutil.copyfileobj(file.file, buffer)

    api_key = ""
    base_url = ""
    model = "whisper-1"
    if character_id:
        from ..models import CharacterModel
        character = db.get(CharacterModel, character_id)
        if character:
            from ..services.crypto_service import decrypt_api_key
            api_key = decrypt_api_key(character.api_key) if character.api_key else ""
            base_url = character.api_base_url or ""

    if not api_key:
        from ..services.system_config_service import get_local_config
        config = get_local_config(db)
        api_key = config.get("public_voice_api_key", "") or config.get("public_text_api_key", "")
        base_url = base_url or config.get("public_voice_base_url", "") or config.get("public_text_base_url", "")
        vm = (config.get("public_voice_model") or "").strip()
        if vm:
            model = vm

    if not api_key:
        raise HTTPException(status_code=400, detail="未配置语音转录 API Key")

    from ..services.stt_service import transcribe_audio
    result = await transcribe_audio(
        audio_file_path=str(saved_path),
        api_key=api_key,
        base_url=base_url,
        model=model,
        language="zh",
    )

    return {"ok": True, "text": result.get("text", ""), "duration": result.get("duration", 0)}

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from pathlib import Path

import httpx
from sqlalchemy.orm import Session

from ..config import STORAGE_DIR, settings
from ..models import CharacterModel, MessageModel, VoiceProfileModel
from .system_config_service import get_voice_service_config


VOICE_STORAGE = STORAGE_DIR / "voices"
AUDIO_STORAGE = STORAGE_DIR / "audio"
VOICE_STORAGE.mkdir(parents=True, exist_ok=True)
AUDIO_STORAGE.mkdir(parents=True, exist_ok=True)

logger = logging.getLogger(__name__)


def split_message_segments(structured_content: dict) -> list[tuple[str, str]]:
    """把消息拆成旁白、内心和对白三段。"""

    narration = (structured_content or {}).get("narration", "").strip()
    thought = (structured_content or {}).get("thought", "").strip()
    speech = (structured_content or {}).get("speech", "").strip()
    segments: list[tuple[str, str]] = []
    if narration:
        segments.append(("narration", narration))
    if thought:
        segments.append(("thought", thought))
    if speech:
        segments.append(("speech", speech))
    return segments


async def synthesize_message_audio(db: Session, message_id: int) -> list[dict]:
    """为一条人物消息生成可顺序播放的音频片段。"""

    message = db.get(MessageModel, message_id)
    if message is None:
        raise ValueError("消息不存在。")

    character = db.get(CharacterModel, message.character_id) if message.character_id else None
    voice_profile = db.get(VoiceProfileModel, character.voice_profile_id) if character and character.voice_profile_id else None

    segments = split_message_segments(message.structured_content or {})
    if not segments:
        segments = [("speech", message.content.strip())]

    config = get_voice_service_config(db)
    edge_timeout = float(config.get("edge_tts_timeout_seconds", 120))

    active = [(k, t) for k, t in segments if (t or "").strip()]
    t_all0 = time.perf_counter()
    logger.info(
        "synthesize_message_audio start message_id=%s active_segments=%d voice_profile_id=%s",
        message_id,
        len(active),
        voice_profile.id if voice_profile else None,
    )

    clips: list[dict] = []
    for index, (kind, text) in enumerate(segments, start=1):
        if not text:
            continue
        seg_t0 = time.perf_counter()
        file_suffix = ".mp3" if kind in {"narration", "thought"} or voice_profile is None else ".wav"
        output_name = f"msg_{message_id}_{index}_{uuid.uuid4().hex[:8]}{file_suffix}"
        output_path = AUDIO_STORAGE / output_name

        if kind == "speech" and voice_profile is not None:
            await synthesize_character_speech(db, text, output_path, voice_profile)
        else:
            try:
                await asyncio.wait_for(
                    _synthesize_narration(text, output_path, settings.narration_voice),
                    timeout=edge_timeout,
                )
            except asyncio.TimeoutError:
                logger.error(
                    "Edge TTS timeout message_id=%s segment_index=%s kind=%s edge_timeout=%s",
                    message_id,
                    index,
                    kind,
                    edge_timeout,
                )
                raise RuntimeError(
                    "旁白/内心语音合成超时，请检查网络或调大 voice_service_config.edge_tts_timeout_seconds。"
                ) from None

        clips.append(
            {
                "kind": kind,
                "text": text,
                "url": f"/storage/audio/{output_name}",
            }
        )
        logger.info(
            "synthesize_message_audio segment done message_id=%s index=%s kind=%s chars=%d elapsed_ms=%d",
            message_id,
            index,
            kind,
            len(text),
            int((time.perf_counter() - seg_t0) * 1000),
        )

    logger.info(
        "synthesize_message_audio done message_id=%s clips=%d total_elapsed_ms=%d",
        message_id,
        len(clips),
        int((time.perf_counter() - t_all0) * 1000),
    )
    return clips


async def _synthesize_narration(text: str, output_path: Path, voice_name: str) -> None:
    """使用 Edge TTS 生成旁白和内心音轨。"""

    try:
        import edge_tts
    except ImportError as exc:
        raise RuntimeError("未安装 edge-tts，无法生成旁白语音。") from exc

    communicate = edge_tts.Communicate(text=text, voice=voice_name)
    await communicate.save(str(output_path))


async def _synthesize_with_xtts(text: str, output_path: Path, reference_audio_path: str, language: str) -> None:
    """使用 XTTS 参考音频克隆人物声色。"""

    try:
        from TTS.api import TTS
    except ImportError as exc:
        raise RuntimeError("未安装 Coqui TTS，无法根据参考音频克隆人物声色。") from exc

    def _worker():
        logger.info("XTTS: loading model and synthesizing (%d chars)", len(text))
        tts = TTS(model_name="tts_models/multilingual/multi-dataset/xtts_v2")
        tts.tts_to_file(
            text=text,
            file_path=str(output_path),
            speaker_wav=reference_audio_path,
            language=language,
        )

    await asyncio.to_thread(_worker)


async def _synthesize_with_external_service(
    db: Session,
    text: str,
    output_path: Path,
    reference_audio_path: str,
    language: str,
) -> bool:
    """调用外部可配置语音克隆服务。"""

    config = get_voice_service_config(db)
    if not config.get("enabled") or not config.get("external_base_url"):
        return False

    endpoint = str(config.get("clone_endpoint", "/clone")).strip() or "/clone"
    if not endpoint.startswith("/"):
        endpoint = f"/{endpoint}"
    url = str(config["external_base_url"]).rstrip("/") + endpoint
    timeout_seconds = int(config.get("timeout_seconds", 120))
    headers = {}
    if config.get("external_api_key"):
        headers["Authorization"] = f"Bearer {config['external_api_key']}"

    data = {
        "text": text,
        "language": language,
    }

    with open(reference_audio_path, "rb") as handle:
        files = {"reference_audio": (Path(reference_audio_path).name, handle, "audio/wav")}
        async with httpx.AsyncClient(timeout=timeout_seconds) as client:
            response = await client.post(url, data=data, files=files, headers=headers)
            response.raise_for_status()
            output_path.write_bytes(response.content)
    return True


async def synthesize_character_speech(
    db: Session,
    text: str,
    output_path: Path,
    voice_profile: VoiceProfileModel,
) -> None:
    """按优先级为人物对白合成语音。"""

    if voice_profile.provider == "edge_tts_builtin" and voice_profile.reference_audio_path.startswith("builtin:"):
        logger.info(
            "synthesize_character_speech voice_id=%s branch=edge_builtin chars=%d",
            voice_profile.id,
            len(text),
        )
        await _synthesize_narration(text, output_path, voice_profile.reference_audio_path.split("builtin:", 1)[1])
        return

    config = get_voice_service_config(db)
    mode = str(config.get("mode", "builtin_only"))
    logger.info(
        "synthesize_character_speech voice_id=%s provider=%s mode=%s chars=%d",
        voice_profile.id,
        voice_profile.provider,
        mode,
        len(text),
    )

    if mode in {"external_clone_preferred", "external_only"}:
        used_external = await _synthesize_with_external_service(db, text, output_path, voice_profile.reference_audio_path, voice_profile.language)
        if used_external:
            logger.info("synthesize_character_speech voice_id=%s branch=external_clone", voice_profile.id)
            return
        if mode == "external_only":
            raise RuntimeError("已配置为仅使用外部语音克隆服务，但当前外部服务不可用。")

    xtts_timeout = float(config.get("xtts_timeout_seconds", 90))
    try:
        await asyncio.wait_for(
            _synthesize_with_xtts(text, output_path, voice_profile.reference_audio_path, voice_profile.language),
            timeout=xtts_timeout,
        )
    except asyncio.TimeoutError:
        logger.warning("XTTS 超时（%.0fs），改用 Edge TTS 旁白音色降级输出", xtts_timeout)
        await _synthesize_narration(text, output_path, settings.narration_voice)
    except Exception as exc:
        logger.warning("XTTS 失败（%s），改用 Edge TTS 旁白音色降级输出", exc)
        await _synthesize_narration(text, output_path, settings.narration_voice)

"""
语音转文字服务 — 通过 OpenAI Whisper 兼容协议将音频转为文字。
按文档第4章要求实现 transcribe_audio 方法。
"""

import logging
from pathlib import Path
from openai import OpenAI

from ..services.crypto_service import decrypt_api_key

logger = logging.getLogger(__name__)


async def transcribe_audio(
    audio_file_path: str,
    api_key: str = "",
    base_url: str = "",
    model: str = "whisper-1",
    language: str = "zh",
    timeout: float = 120.0,
) -> dict:
    """
    语音转文字。

    入参:
        audio_file_path: 上传的音频文件路径
        api_key: OpenAI API Key (支持加密格式)
        base_url: API 基础地址
        model: 模型名 (whisper-1)
        language: 语言代码
    出参:
        {"text": "识别的文字", "duration": 3.5, "language": "zh"}
    """
    audio_path = Path(audio_file_path)
    if not audio_path.exists():
        raise FileNotFoundError(f"音频文件不存在: {audio_file_path}")

    if not api_key:
        raise ValueError("未提供 API Key，无法进行语音转录")

    decrypted_key = decrypt_api_key(api_key)

    client_kwargs: dict = {"api_key": decrypted_key, "timeout": timeout}
    if base_url:
        client_kwargs["base_url"] = base_url

    client = OpenAI(**client_kwargs)

    with open(audio_file_path, "rb") as audio_file:
        result = client.audio.transcriptions.create(
            model=model,
            file=audio_file,
            language=language,
            response_format="verbose_json",
        )

    text = getattr(result, "text", "") if hasattr(result, "text") else str(result)
    duration = getattr(result, "duration", 0) if hasattr(result, "duration") else 0
    detected_language = getattr(result, "language", language) if hasattr(result, "language") else language

    logger.info(f"语音转录完成: {len(text)} 字符, 时长 {duration}s, 语言 {detected_language}")

    return {
        "text": text,
        "duration": duration,
        "language": detected_language,
    }

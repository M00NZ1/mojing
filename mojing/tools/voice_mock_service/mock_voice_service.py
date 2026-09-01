from __future__ import annotations

import io
import math
import wave

from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import Response


app = FastAPI(title="墨境 语音 Mock 服务")


def _build_wav_bytes(text: str, seconds: float = 1.2, sample_rate: int = 22050) -> bytes:
    """生成一段简单的正弦波音频，供联调验证 HTTP 协议。"""

    amplitude = 16000
    frequency = 440 + min(len(text), 40) * 8
    total_frames = int(seconds * sample_rate)
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        for index in range(total_frames):
            sample = int(amplitude * math.sin(2 * math.pi * frequency * index / sample_rate))
            wav_file.writeframesraw(sample.to_bytes(2, byteorder="little", signed=True))
    return buffer.getvalue()


@app.get("/health")
def health():
    return {"ok": True, "service": "voice-mock"}


@app.post("/clone")
async def clone_voice(
    text: str = Form(...),
    language: str = Form("zh-cn"),
    reference_audio: UploadFile = File(...),
):
    await reference_audio.read()
    wav_bytes = _build_wav_bytes(f"{language}:{text}")
    return Response(content=wav_bytes, media_type="audio/wav")

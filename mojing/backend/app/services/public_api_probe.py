"""公共 API（文本 / 生图 / 语音转写）连通性探测 — 支持多 Base URL 依次尝试与 NDJSON 流式进度。"""

from __future__ import annotations

import asyncio
import io
import json
import logging
import re
import tempfile
import wave
from collections.abc import AsyncIterator
from typing import Any, Literal

from openai import OpenAI

from .crypto_service import decrypt_api_key
from .image_service import _normalize_openai_compatible_base, generate_image
from .openai_compatible_routing import (
    VOLC_ARK_BASE,
    collect_probe_bases,
    is_volc_ark_host,
    normalize_base,
    uses_versioned_root,
)
from .stt_service import transcribe_audio

logger = logging.getLogger(__name__)

Channel = Literal["text", "image", "voice"]

# 单条候选线路超时（秒），多线路时略短以免用户长时间无反馈
TEXT_ATTEMPT_TIMEOUT = 22.0
IMAGE_ATTEMPT_TIMEOUT = 36.0
VOICE_ATTEMPT_TIMEOUT = 24.0


def _decrypt_key(api_key: str) -> str:
    k = (api_key or "").strip()
    if not k:
        return ""
    return decrypt_api_key(k)


def _openai_client_for_probe(base_url: str, api_key: str) -> OpenAI:
    key = _decrypt_key(api_key)
    base = (base_url or "").strip().rstrip("/")
    low = base.lower()
    if not base:
        return OpenAI(api_key=key)
    if "ollama" in low or low.endswith("11434") or ":11434" in low:
        return OpenAI(api_key=key or "ollama", base_url=base + "/v1")
    if "generativelanguage" in low:
        return OpenAI(api_key=key, base_url=base)
    if is_volc_ark_host(base):
        return OpenAI(api_key=key, base_url=normalize_base(base))
    return OpenAI(api_key=key, base_url=normalize_base(base) or base)


def _dedupe_urls_preserve_order(urls: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for u in urls:
        s = (u or "").strip().rstrip("/")
        key = s.lower() if s else "__empty__"
        if key in seen:
            continue
        seen.add(key)
        out.append(s)
    return out


def _host_mirror_urls(url: str) -> list[str]:
    """同一平台常见备用域名 / 路径（不含归一化，由调用方去重）。"""
    if not (url or "").strip():
        return []
    u = url.strip()
    low = u.lower()
    alts: list[str] = []
    if "api.siliconflow.cn" in low:
        alts.append(re.sub(r"api\.siliconflow\.cn", "api.siliconflow.com", u, flags=re.I))
    if "api.siliconflow.com" in low:
        alts.append(re.sub(r"api\.siliconflow\.com", "api.siliconflow.cn", u, flags=re.I))
    if "moonshot.cn" in low:
        alts.append(u.replace("moonshot.cn", "moonshot.ai").replace("MOONSHOT.CN", "moonshot.ai"))
    if "moonshot.ai" in low:
        alts.append(u.replace("moonshot.ai", "moonshot.cn").replace("MOONSHOT.AI", "moonshot.cn"))
    if "api.deepseek.com" in low and not re.search(r"/v1/?$", low):
        alts.append(u.rstrip("/") + "/v1")
    if re.search(r"api\.deepseek\.com/v1/?$", low):
        alts.append(re.sub(r"/v1/?$", "", u.rstrip("/")))
    if "api.openai.com" in low and "/v1" not in low:
        alts.append(u.rstrip("/") + "/v1")
    if re.search(r"api\.openai\.com/v1/?$", low):
        alts.append(re.sub(r"/v1/?$", "", u.rstrip("/")))
    return alts


def build_probe_candidate_bases(base_url: str, channel: Channel) -> list[str]:
    """生成按顺序尝试的 Base URL 列表：用户填写优先，其次归一化，再镜像域名。"""
    b0 = (base_url or "").strip()
    ordered: list[str] = []

    def push(u: str) -> None:
        s = (u or "").strip().rstrip("/")
        ordered.append(s)

    if b0:
        if is_volc_ark_host(b0) or (
            uses_versioned_root(b0) and not normalize_base(b0).lower().endswith("/v1")
        ):
            for c in collect_probe_bases(b0, _normalize_openai_compatible_base):
                push(c)
        else:
            push(b0)
            n0 = _normalize_openai_compatible_base(b0)
            if n0 and n0.rstrip("/") != b0.rstrip("/"):
                push(n0)
            for alt in _host_mirror_urls(b0):
                push(alt)
                n1 = _normalize_openai_compatible_base(alt)
                if n1 and n1.rstrip("/") != alt.rstrip("/"):
                    push(n1)
        if is_volc_ark_host(b0):
            push(VOLC_ARK_BASE)
    if channel == "image" and not b0:
        push("https://api.siliconflow.cn/v1")

    uniq = _dedupe_urls_preserve_order(ordered)
    return uniq if uniq else [""]


def collect_heuristic_warnings(channel: Channel, base_url: str, model: str) -> list[str]:
    """内置启发式：地址与模型名是否明显不匹配（仅提示，不阻止请求）。"""
    b = (base_url or "").strip().lower()
    m = (model or "").strip().lower()
    out: list[str] = []
    if not b and not m:
        return out

    if channel == "text":
        if "deepseek" in b and ("gpt-" in m or "claude" in m or "gemini" in m):
            out.append("Base 指向 DeepSeek，但模型名更像 OpenAI / Claude / Gemini，请核对。")
        if "api.openai.com" in b and ("deepseek" in m or "glm-" in m or "qwen/" in m or "moonshot" in m):
            out.append("Base 为 OpenAI 官方，但模型名不像 OpenAI 系列（如 gpt-4o）。")
        if "bigmodel" in b or "open.bigmodel" in b:
            if "gpt-" in m or "gemini" in m:
                out.append("Base 像智谱，但模型名更像他厂；智谱多为 glm-* 等 id。")
        if "siliconflow" in b and ("gpt-4" in m or "dall-e" in m):
            out.append("Base 为硅基流动时，聊天模型 id 多为带命名空间的开源名，一般不是 gpt-4 / dall-e。")
        if "generativelanguage" in b and m and not m.startswith("gemini"):
            out.append("Gemini OpenAI 兼容端点下，模型名多为 gemini-*。")
        if "moonshot" in b or "moonshot.cn" in b or "moonshot.ai" in b:
            if "deepseek" in m or "gpt-" in m:
                out.append("Base 像 Moonshot/Kimi，模型名请与 Kimi 控制台一致。")

    if channel == "image":
        if "deepseek" in b:
            out.append("DeepSeek 官方 Base 通常不提供 OpenAI 式 images/generations；生图建议硅基流动 / OpenAI / 智谱 CogView 等。")
        if m.startswith("gpt-") and "dall" not in m and "image" not in m:
            out.append("生图模型一般为 dall-e-*、flux* 或平台文生图 id；纯 gpt-* 多为聊天模型。")
        if ("siliconflow" in b or "bigmodel" in b) and ("dall-e" in m or m == "gpt-image-1"):
            out.append("当前 Base 为国内/智谱聚合时，生图模型 id 常为平台自有名称，未必与 OpenAI 商品名相同。")

    if channel == "voice":
        if "deepseek" in b:
            out.append("DeepSeek 官方一般不提供 Whisper 兼容转写；语音转文字建议 OpenAI / 硅基流动等。")
        if m == "whisper-1" and ("siliconflow" in b or "bigmodel" in b):
            out.append("当前模型为 whisper-1；若使用硅基流动，模型名常为 FunAudioLLM/SenseVoiceSmall 等（见硅基文档）。")
        if "generativelanguage" in b and m == "whisper-1":
            out.append("Gemini 兼容端点下转写模型名未必是 whisper-1，请查阅供应商说明。")

    return out


def probe_text_sync(*, base_url: str, api_key: str, model: str, timeout: float = TEXT_ATTEMPT_TIMEOUT) -> dict[str, Any]:
    key = _decrypt_key(api_key)
    if not key:
        return {"ok": False, "error": "未填写 API Key。", "detail": None, "warnings": []}
    m = (model or "").strip()
    if not m:
        return {"ok": False, "error": "未填写模型名称。", "detail": None, "warnings": collect_heuristic_warnings("text", base_url, m)}

    warnings = collect_heuristic_warnings("text", base_url, m)
    client = _openai_client_for_probe(base_url, api_key)
    try:
        resp = client.chat.completions.create(
            model=m,
            messages=[{"role": "user", "content": "ping"}],
            max_tokens=1,
            timeout=timeout,
        )
        choice0 = resp.choices[0].message if resp.choices else None
        tail = (getattr(choice0, "content", None) or "")[:80]
        return {
            "ok": True,
            "error": None,
            "detail": f"已收到回复片段（max_tokens=1）：{tail!r}",
            "warnings": warnings,
        }
    except Exception as e:
        logger.info("probe_text failed type=%s", type(e).__name__)
        safe_error = _safe_probe_error(e)
        return {"ok": False, "error": safe_error, "detail": safe_error, "warnings": warnings}


async def probe_image_async(
    *,
    base_url: str,
    api_key: str,
    model: str,
    timeout: float = IMAGE_ATTEMPT_TIMEOUT,
) -> dict[str, Any]:
    key = _decrypt_key(api_key)
    if not key:
        return {"ok": False, "error": "未填写 API Key。", "detail": None, "warnings": []}
    m = (model or "").strip()
    if not m:
        return {"ok": False, "error": "未填写生图模型。", "detail": None, "warnings": []}

    warnings = collect_heuristic_warnings("image", base_url, m)
    warnings.append("生图测试会真实调用一次 images/generations（可能计费），请确认额度。")
    try:
        result = await generate_image(
            prompt="solid blue",
            api_key=key,
            base_url=base_url or "https://api.siliconflow.cn/v1",
            model=m,
            size="1024x1024",
            negative_prompt="",
            steps=1,
            timeout=int(timeout),
        )
        urls = result.get("urls") or []
        err = result.get("error")
        if urls:
            return {
                "ok": True,
                "error": None,
                "detail": f"生图成功，返回 {len(urls)} 个 URL（首条已截断校验）。",
                "warnings": warnings,
                "meta": {"url_prefix": urls[0][:48] + "…" if len(urls[0]) > 48 else urls[0]},
            }
        return {
            "ok": False,
            "error": "上游未返回图片 URL" if err else "未返回图片 URL",
            "detail": None,
            "warnings": warnings,
        }
    except Exception as e:
        logger.info("probe_image failed type=%s", type(e).__name__)
        safe_error = _safe_probe_error(e)
        return {"ok": False, "error": safe_error, "detail": safe_error, "warnings": warnings}


def _minimal_wav_pcm16_mono(duration_sec: float = 0.15, sample_rate: int = 16000) -> bytes:
    n = int(sample_rate * duration_sec)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(b"\x00\x00" * n)
    return buf.getvalue()


def _safe_probe_error(exc: BaseException) -> str:
    if isinstance(exc, TimeoutError) or "timeout" in type(exc).__name__.lower():
        return "上游连接测试超时。"
    status_code = getattr(exc, "status_code", None)
    if isinstance(status_code, int) and 100 <= status_code <= 599:
        return f"上游返回 HTTP {status_code}。"
    return f"上游连接测试失败（{type(exc).__name__}）。"


async def probe_voice_async(
    *,
    base_url: str,
    api_key: str,
    model: str,
    timeout: float = VOICE_ATTEMPT_TIMEOUT,
) -> dict[str, Any]:
    """使用极短静音 WAV 调用 Whisper 兼容转写接口。"""
    key = _decrypt_key(api_key)
    if not key:
        return {"ok": False, "error": "未填写 API Key。", "detail": None, "warnings": []}
    m = (model or "").strip() or "whisper-1"

    warnings = collect_heuristic_warnings("voice", base_url, m)
    wav = _minimal_wav_pcm16_mono()
    tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    try:
        tmp.write(wav)
        tmp.flush()
        tmp.close()
        text = await transcribe_audio(
            audio_file_path=tmp.name,
            api_key=api_key,
            base_url=(base_url or "").strip(),
            model=m,
            language="zh",
            timeout=timeout,
        )
        snippet = (text.get("text") or "")[:120]
        return {
            "ok": True,
            "error": None,
            "detail": f"转写完成（静音片预期字少或无）：{snippet!r}",
            "warnings": warnings,
            "meta": {"duration": text.get("duration"), "language": text.get("language")},
        }
    except Exception as e:
        logger.info("probe_voice failed type=%s", type(e).__name__)
        safe_error = _safe_probe_error(e)
        return {"ok": False, "error": safe_error, "detail": safe_error, "warnings": warnings}
    finally:
        try:
            import os

            os.unlink(tmp.name)
        except Exception:
            pass


def merge_probe_payload(
    channel: Channel,
    saved: dict[str, Any],
    override_base: str | None,
    override_key: str | None,
    override_model: str | None,
) -> tuple[str, str, str]:
    """override_* 为 None 时回退到数据库中的 local_config。"""
    if channel == "text":
        b = override_base if override_base is not None else (saved.get("public_text_base_url") or "")
        k = override_key if override_key is not None else (saved.get("public_text_api_key") or "")
        m = override_model if override_model is not None else (saved.get("public_text_model") or "")
        return str(b), str(k), str(m)
    if channel == "image":
        b = override_base if override_base is not None else (saved.get("public_image_base_url") or "")
        k = override_key if override_key is not None else (saved.get("public_image_api_key") or "")
        m = override_model if override_model is not None else (saved.get("public_image_model") or "")
        if not k:
            k = saved.get("public_text_api_key") or ""
        if not b:
            b = saved.get("public_text_base_url") or ""
        return str(b), str(k), str(m)
    b = override_base if override_base is not None else (saved.get("public_voice_base_url") or "")
    k = override_key if override_key is not None else (saved.get("public_voice_api_key") or "")
    m = override_model if override_model is not None else (saved.get("public_voice_model") or "")
    if not k:
        k = saved.get("public_text_api_key") or ""
    if not b:
        b = saved.get("public_voice_base_url") or saved.get("public_text_base_url") or ""
    return str(b), str(k), str(m)


async def _probe_one_base(
    channel: Channel,
    *,
    base_try: str,
    api_key: str,
    model: str,
) -> dict[str, Any]:
    if channel == "text":
        return await asyncio.to_thread(
            probe_text_sync,
            base_url=base_try,
            api_key=api_key,
            model=model,
            timeout=TEXT_ATTEMPT_TIMEOUT,
        )
    if channel == "image":
        return await probe_image_async(
            base_url=base_try,
            api_key=api_key,
            model=model,
            timeout=IMAGE_ATTEMPT_TIMEOUT,
        )
    return await probe_voice_async(
        base_url=base_try,
        api_key=api_key,
        model=model,
        timeout=VOICE_ATTEMPT_TIMEOUT,
    )


async def iter_public_api_probe_events(
    saved: dict[str, Any],
    channel: Channel,
    override_base: str | None = None,
    override_key: str | None = None,
    override_model: str | None = None,
) -> AsyncIterator[dict[str, Any]]:
    base, key, model = merge_probe_payload(channel, saved, override_base, override_key, override_model)
    warnings_pre = collect_heuristic_warnings(channel, base, model)
    candidates = build_probe_candidate_bases(base, channel)
    yield {
        "type": "start",
        "channel": channel,
        "total": len(candidates),
        "warnings": warnings_pre,
        "candidates": candidates,
    }
    attempts: list[dict[str, Any]] = []
    last_out: dict[str, Any] | None = None
    for i, cand in enumerate(candidates):
        display = cand if cand else "（默认官方 OpenAI 根地址）"
        yield {"type": "attempt", "index": i + 1, "total": len(candidates), "base_url": cand, "display_base": display}
        out = await _probe_one_base(channel, base_try=cand, api_key=key, model=model)
        last_out = out
        rec = {
            "base_url": cand,
            "ok": bool(out.get("ok")),
            "error": out.get("error"),
            "detail": out.get("detail"),
        }
        attempts.append(rec)
        yield {"type": "attempt_result", **rec}
        if out.get("ok"):
            yield {
                "type": "done",
                "ok": True,
                "channel": channel,
                "used_base_url": cand,
                "attempts": attempts,
                "error": None,
                "detail": out.get("detail"),
                "warnings": list(warnings_pre) + list(out.get("warnings") or []),
                "meta": out.get("meta"),
            }
            return

    merged_err = "；".join(
        f"{(a.get('base_url') or '默认')[:48]}: {a.get('error') or '失败'}" for a in attempts[:4]
    )
    if len(attempts) > 4:
        merged_err += f" …（共 {len(attempts)} 条）"
    yield {
        "type": "done",
        "ok": False,
        "channel": channel,
        "used_base_url": None,
        "attempts": attempts,
        "error": (last_out or {}).get("error") or "所有候选线路均未通过",
        "detail": merged_err[:900],
        "warnings": list(warnings_pre) + list((last_out or {}).get("warnings") or []),
        "meta": None,
    }


async def run_probe(
    channel: Channel,
    saved: dict[str, Any],
    override_base: str | None = None,
    override_key: str | None = None,
    override_model: str | None = None,
) -> dict[str, Any]:
    """非流式：跑完全部事件，返回最终 done 载荷（供旧客户端与单测）。"""
    final: dict[str, Any] | None = None
    async for ev in iter_public_api_probe_events(saved, channel, override_base, override_key, override_model):
        if ev.get("type") == "done":
            final = ev
    assert final is not None
    return {
        "ok": bool(final.get("ok")),
        "error": final.get("error"),
        "detail": final.get("detail"),
        "warnings": list(final.get("warnings") or []),
        "meta": final.get("meta"),
        "used_base_url": final.get("used_base_url"),
        "attempts": list(final.get("attempts") or []),
    }


def encode_probe_stream_line(obj: dict[str, Any]) -> bytes:
    return json.dumps(obj, ensure_ascii=False).encode("utf-8") + b"\n"

"""
图像生成服务 — 支持多 Provider 适配。

Provider 类型:
- openai: DALL-E 2/3
- sd_webui: Stable Diffusion WebUI (API)
- comfyui: ComfyUI (API)
"""
from __future__ import annotations

import json
import logging
import time
from typing import Any
from urllib.parse import urljoin

import httpx
import openai

logger = logging.getLogger(__name__)


def _normalize_openai_compatible_base(base_url: str) -> str:
    from .openai_compatible_routing import normalize_base

    return normalize_base(base_url)


def _detect_provider(base_url: str, model: str = "") -> str:
    from .dmx_api_routing import (
        classify_image_protocol,
        is_dmx_host,
        should_use_gemini_image,
        should_use_responses_image,
    )
    from .openai_compatible_routing import classify_image_body, is_volc_ark_host, is_seedream_model

    base = base_url.lower()
    if should_use_gemini_image(base_url, model):
        return "dmx_gemini"
    if should_use_responses_image(base_url, model):
        return "dmx_responses"
    if is_dmx_host(base_url) and classify_image_protocol(model) == "openai_images":
        return "dmx_openai_images"
    if is_volc_ark_host(base_url) or is_seedream_model(model):
        return "volc_seedream"
    if "siliconflow" in base:
        return "siliconflow"
    if classify_image_body(base_url, model, "siliconflow" in base) == "siliconflow":
        return "siliconflow"
    if "sd" in base or "stable-diffusion" in base or "webui" in base:
        return "sd_webui"
    if "comfy" in base:
        return "comfyui"
    return "openai"


def _openai_image_supports_quality(model: str) -> bool:
    """仅 DALL·E / gpt-image 系列支持 quality；硅基 Kolors 等会 400。"""
    m = (model or "").lower()
    return "dall-e" in m or m.startswith("gpt-image")


async def generate_image(
    *,
    prompt: str,
    api_key: str = "",
    base_url: str = "https://api.openai.com",
    model: str = "dall-e-3",
    size: str = "1024x1024",
    negative_prompt: str = "",
    steps: int = 20,
    timeout: int = 60,
) -> dict:
    """生成图片，返回结果结构统一为 {urls: [...], revised_prompt: ""}。"""
    provider = _detect_provider(base_url, model)

    if provider == "dmx_gemini":
        return await _generate_dmx_gemini_image(prompt, api_key, model, size, timeout)
    if provider == "dmx_responses":
        return await _generate_dmx_responses_image(prompt, api_key, base_url, model, size, timeout)
    if provider == "dmx_openai_images":
        return await _generate_dmx_openai_images(prompt, api_key, base_url, model, size, timeout)
    if provider == "volc_seedream":
        return await _generate_volc_seedream(prompt, api_key, base_url, model, size, timeout)
    if provider == "siliconflow":
        return await _generate_siliconflow(prompt, api_key, base_url, model, size, timeout)
    if provider == "openai":
        return await _generate_openai(prompt, api_key, base_url, model, size, timeout)
    if provider == "sd_webui":
        return await _generate_sd_webui(prompt, base_url, negative_prompt, steps, timeout)
    return await _generate_openai(prompt, api_key, base_url, model, size, timeout)


def _parse_dmx_response_image_urls(raw: str, data: dict | None = None) -> list[str]:
    import re

    urls = re.findall(r"!\[[^\]]*]\((https?://[^)]+)\)", raw)
    if urls:
        return urls
    if not data:
        return []
    for item in data.get("output") or []:
        for c in item.get("content") or []:
            text = c.get("text") or ""
            if isinstance(text, str) and text.startswith("http"):
                urls.append(text)
            urls.extend(re.findall(r"!\[[^\]]*]\((https?://[^)]+)\)", text))
    u = (data.get("data") or {}).get("url")
    if isinstance(u, str) and u.startswith("http"):
        urls.append(u)
    return urls


async def _generate_dmx_responses_image(
    prompt: str,
    api_key: str,
    base_url: str,
    model: str,
    size: str,
    timeout: int,
) -> dict:
    from .dmx_api_routing import build_responses_url, classify_image_protocol, format_token_auth, map_wan_image_size
    from .openai_compatible_routing import map_seedream_size

    if not api_key:
        return {"urls": [], "revised_prompt": "", "error": "API Key 未配置"}
    url = build_responses_url(base_url or "")
    proto = classify_image_protocol(model)
    if proto == "responses_wan":
        body = {
            "model": model,
            "input": {"messages": [{"role": "user", "content": [{"text": prompt}]}]},
            "parameters": {"watermark": False, "n": 1, "size": map_wan_image_size(model, size)},
        }
    else:
        body = {
            "model": model,
            "input": prompt,
            "size": map_seedream_size(size),
            "sequential_image_generation": "disabled",
            "response_format": "url",
            "watermark": False,
            "stream": False,
        }
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(
                url,
                json=body,
                headers={"Authorization": format_token_auth(api_key), "Content-Type": "application/json"},
            )
            if resp.status_code >= 400:
                return {"urls": [], "revised_prompt": "", "error": f"HTTP {resp.status_code}: {resp.text[:400]}"}
            data = resp.json()
            urls = _parse_dmx_response_image_urls(resp.text, data)
            return {"urls": urls, "revised_prompt": ""}
    except Exception as e:
        logger.error("[Image] DMX Responses 生图失败: %s", e)
        return {"urls": [], "revised_prompt": "", "error": str(e)}


async def _generate_dmx_gemini_image(
    prompt: str,
    api_key: str,
    model: str,
    size: str,
    timeout: int,
) -> dict:
    import base64

    from .dmx_api_routing import build_gemini_generate_content_url, format_token_auth

    if not api_key:
        return {"urls": [], "revised_prompt": "", "error": "API Key 未配置"}
    url = build_gemini_generate_content_url(model)
    body = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "responseModalities": ["IMAGE"],
            "imageConfig": {"aspectRatio": "1:1"},
        },
    }
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(
                url,
                json=body,
                headers={"x-goog-api-key": format_token_auth(api_key), "Content-Type": "application/json"},
            )
            if resp.status_code >= 400:
                return {"urls": [], "revised_prompt": "", "error": f"HTTP {resp.status_code}: {resp.text[:400]}"}
            data = resp.json()
            parts = (data.get("candidates") or [{}])[0].get("content", {}).get("parts") or []
            urls: list[str] = []
            for part in parts:
                inline = part.get("inlineData") or part.get("inline_data") or {}
                b64 = inline.get("data")
                if b64:
                    urls.append(f"data:image/png;base64,{b64}")
            return {"urls": urls, "revised_prompt": ""}
    except Exception as e:
        logger.error("[Image] DMX Gemini 生图失败: %s", e)
        return {"urls": [], "revised_prompt": "", "error": str(e)}


async def _generate_dmx_openai_images(
    prompt: str,
    api_key: str,
    base_url: str,
    model: str,
    size: str,
    timeout: int,
) -> dict:
    from .dmx_api_routing import format_bearer_auth, map_qwen_image_size
    from .openai_compatible_routing import build_images_generations_url

    if not api_key:
        return {"urls": [], "revised_prompt": "", "error": "API Key 未配置"}
    url = build_images_generations_url(base_url or "")
    mapped = map_qwen_image_size(size) if "qwen-image" in model.lower() else size
    body: dict[str, Any] = {
        "model": model,
        "prompt": prompt,
        "n": 1,
        "size": mapped,
        "response_format": "url",
    }
    if "gpt-image" in model.lower():
        body["quality"] = "high"
        body["moderation"] = "low"
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(
                url,
                json=body,
                headers={"Authorization": format_bearer_auth(api_key), "Content-Type": "application/json"},
            )
            if resp.status_code >= 400:
                return {"urls": [], "revised_prompt": "", "error": f"HTTP {resp.status_code}: {resp.text[:400]}"}
            data = resp.json()
            urls: list[str] = []
            for item in data.get("data") or []:
                u = item.get("url") or item.get("b64_json")
                if u:
                    urls.append(str(u))
            extra = data.get("extra") or {}
            for r in (extra.get("output") or {}).get("results") or []:
                u = r.get("url")
                if u:
                    urls.append(str(u))
            return {"urls": urls, "revised_prompt": ""}
    except Exception as e:
        logger.error("[Image] DMX OpenAI 生图失败: %s", e)
        return {"urls": [], "revised_prompt": "", "error": str(e)}


async def _generate_volc_seedream(
    prompt: str,
    api_key: str,
    base_url: str,
    model: str,
    size: str,
    timeout: int,
) -> dict:
    from .openai_compatible_routing import build_images_generations_url, map_seedream_size

    if not api_key:
        return {"urls": [], "revised_prompt": "", "error": "API Key 未配置"}
    url = build_images_generations_url(base_url or "")
    body = {
        "model": model,
        "prompt": prompt,
        "size": map_seedream_size(size),
        "response_format": "url",
        "watermark": False,
        "sequential_image_generation": "disabled",
    }
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(
                url,
                json=body,
                headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            )
            if resp.status_code >= 400:
                return {"urls": [], "revised_prompt": "", "error": f"HTTP {resp.status_code}: {resp.text[:400]}"}
            data = resp.json()
            urls: list[str] = []
            for item in data.get("data") or []:
                if isinstance(item, dict):
                    u = item.get("url") or item.get("b64_json")
                    if u:
                        urls.append(str(u))
            return {"urls": urls, "revised_prompt": ""}
    except Exception as e:
        logger.error("[Image] Volc Seedream 生图失败: %s", e)
        return {"urls": [], "revised_prompt": "", "error": str(e)}


async def _generate_siliconflow(
    prompt: str,
    api_key: str,
    base_url: str,
    model: str,
    size: str,
    timeout: int,
) -> dict:
    from .openai_compatible_routing import build_images_generations_url

    if not api_key:
        return {"urls": [], "revised_prompt": "", "error": "API Key 未配置"}
    url = build_images_generations_url(base_url or "https://api.siliconflow.cn/v1")
    body: dict[str, Any] = {"model": model, "prompt": prompt}
    if "flux" in model.lower():
        body["image_size"] = size
    elif "qwen-image-edit" in model.lower():
        body["num_inference_steps"] = 20
        body["guidance_scale"] = 4.0
    else:
        image_size = "1328x1328" if "qwen/qwen-image" in model.lower() and size == "1024x1024" else size
        body.update(
            {
                "image_size": image_size,
                "batch_size": 1,
                "num_inference_steps": 20,
                "guidance_scale": 4.0 if "qwen-image" in model.lower() else 7.5,
            }
        )
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(
                url,
                json=body,
                headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            )
            if resp.status_code >= 400:
                return {"urls": [], "revised_prompt": "", "error": f"HTTP {resp.status_code}: {resp.text[:400]}"}
            data = resp.json()
            urls: list[str] = []
            for key in ("images", "data", "output"):
                items = data.get(key) or []
                if isinstance(items, list):
                    for item in items:
                        if isinstance(item, dict):
                            u = item.get("url") or item.get("image_url") or item.get("b64_json")
                            if u:
                                urls.append(str(u))
            if isinstance(data.get("url"), str):
                urls.append(data["url"])
            return {"urls": urls, "revised_prompt": ""}
    except Exception as e:
        logger.error("[Image] SiliconFlow 生图失败: %s", e)
        return {"urls": [], "revised_prompt": "", "error": str(e)}


async def _generate_openai(
    prompt: str,
    api_key: str,
    base_url: str,
    model: str,
    size: str,
    timeout: int,
) -> dict:
    if not api_key:
        return {"urls": [], "revised_prompt": "", "error": "OpenAI API Key 未配置"}
    try:
        normalized_base = _normalize_openai_compatible_base(base_url)
        client = openai.AsyncOpenAI(api_key=api_key, base_url=normalized_base, timeout=timeout)
        response = await client.images.generate(
            model=model,
            prompt=prompt,
            size=size,
            n=1,
            **({"quality": "standard"} if _openai_image_supports_quality(model) else {}),
        )
        urls = []
        revised = ""
        data = getattr(response, "data", None) or []
        for item in data:
            if item.url:
                urls.append(item.url)
            elif getattr(item, "b64_json", None):
                urls.append(f"data:image/png;base64,{item.b64_json}")
            if getattr(item, "revised_prompt", None):
                revised = item.revised_prompt or revised
        return {"urls": urls, "revised_prompt": revised}
    except Exception as e:
        logger.error("[Image] OpenAI 生图失败: %s", e)
        return {"urls": [], "revised_prompt": "", "error": str(e)}


async def _generate_sd_webui(
    prompt: str,
    base_url: str,
    negative_prompt: str,
    steps: int,
    timeout: int,
) -> dict:
    try:
        payload = {
            "prompt": prompt,
            "negative_prompt": negative_prompt,
            "steps": steps,
            "save_images": False,
            "send_images": True,
        }
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(urljoin(base_url, "/sdapi/v1/txt2img"), json=payload)
            resp.raise_for_status()
            data = resp.json()
            images = data.get("images", [])
            urls = []
            for i, img_b64 in enumerate(images[:4]):
                import base64
                urls.append(f"data:image/png;base64,{img_b64}")
            return {"urls": urls, "revised_prompt": prompt}
    except Exception as e:
        logger.error("[Image] SD WebUI 生图失败: %s", e)
        return {"urls": [], "revised_prompt": "", "error": str(e)}

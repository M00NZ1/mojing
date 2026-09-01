"""DMXAPI 全量文生图 / TTS 路由（与 Android DmxApiRouting 对齐）。"""

from __future__ import annotations

from .openai_compatible_routing import is_seedream_model, normalize_base

BASE = "https://www.dmxapi.cn/v1"
GEMINI_HOST = "https://www.dmxapi.cn"

IMAGE_OPENAI_SUGGESTED_MODELS = [
    "gpt-image-2-ssvip",
    "gpt-image-1.5",
    "qwen-image",
    "qwen-image-2.0",
    "qwen-image-2.0-pro",
    "qwen-image-max",
    "flux-kontext-pro",
]

IMAGE_RESPONSES_SUGGESTED_MODELS = [
    "doubao-seedream-5.0-lite",
    "doubao-seedream-4.5",
    "doubao-seedream-4-5-251128",
    "doubao-seedream-4-0-250828",
    "wan2.6-t2i",
    "wan2.7-image",
    "wan2.7-image-pro",
]

IMAGE_GEMINI_SUGGESTED_MODELS = [
    "gemini-3.1-flash-image-preview",
    "gemini-3-pro-image-preview",
    "gemini-2.5-flash-image",
]

IMAGE_SUGGESTED_MODELS = (
    IMAGE_RESPONSES_SUGGESTED_MODELS + IMAGE_GEMINI_SUGGESTED_MODELS + IMAGE_OPENAI_SUGGESTED_MODELS
)

VOICE_SUGGESTED_MODELS = [
    "gpt-4o-mini-tts",
    "tts-1-hd",
    "tts-1",
    "speech-2.6-hd",
    "speech-2.6-turbo",
    "speech-2.8-hd",
    "mimo-v2-tts",
    "gemini-2.5-pro-preview-tts",
    "gemini-2.5-flash-preview-tts",
]

DEFAULT_MINIMAX_VOICE = "male-qn-qingse"


def is_dmx_host(url: str) -> bool:
    return "dmxapi.cn" in (url or "").lower()


def _m(model: str) -> str:
    return (model or "").strip().lower()


def is_gemini_image_model(model: str) -> bool:
    m = _m(model)
    return "gemini" in m and ("image" in m or "flash-image" in m)


def is_wan_image_model(model: str) -> bool:
    m = _m(model)
    return m.startswith("wan2.") and ("image" in m or "-t2i" in m)


def classify_image_protocol(model: str) -> str:
    m = _m(model)
    if is_gemini_image_model(m):
        return "gemini_generate"
    if is_wan_image_model(m):
        return "responses_wan"
    if is_seedream_model(m):
        return "responses_seedream"
    return "openai_images"


def should_use_dmx_special_image(base_url: str, model: str) -> bool:
    return is_dmx_host(base_url) and classify_image_protocol(model) != "openai_images"


def should_use_responses_image(base_url: str, model: str) -> bool:
    return is_dmx_host(base_url) and classify_image_protocol(model) in ("responses_seedream", "responses_wan")


def should_use_gemini_image(base_url: str, model: str) -> bool:
    return is_dmx_host(base_url) and classify_image_protocol(model) == "gemini_generate"


def build_responses_url(base_url: str) -> str:
    base = normalize_base(base_url).rstrip("/")
    low = base.lower()
    if low.endswith("/responses"):
        return base
    if low.endswith("/v1"):
        return f"{base}/responses"
    if is_dmx_host(base):
        return f"{BASE.rstrip('/')}/responses"
    return f"{base}/v1/responses"


def build_gemini_generate_content_url(model: str) -> str:
    return f"{GEMINI_HOST}/v1beta/models/{model.strip()}:generateContent"


def format_bearer_auth(api_key: str) -> str:
    k = (api_key or "").strip().removeprefix("Bearer ").strip()
    return f"Bearer {k}"


def format_token_auth(api_key: str) -> str:
    return (api_key or "").strip().removeprefix("Bearer ").strip()


def map_qwen_image_size(size: str) -> str:
    s = (size or "").strip().replace("x", "*").replace("X", "*")
    return s or "1328*1328"


def map_wan_image_size(model: str, size: str) -> str:
    m = _m(model)
    s = (size or "").strip()
    if m.startswith("wan2.7"):
        low = s.lower()
        if low in ("1k", "2k", "4k"):
            return s.upper()
        if "*" in s:
            return s
        if "4096" in low:
            return "4K"
        return "2K"
    return s.replace("x", "*").replace("X", "*") or "1280*1280"

"""OpenAI 兼容网关统一路由（与 Android OpenAiCompatibleRouting 对齐）。"""

from __future__ import annotations

VOLC_ARK_BASE = "https://ark.cn-beijing.volces.com/api/v3"

VOLC_CHAT_SUGGESTED_MODELS = [
    "doubao-seed-1-6-flash-250715",
    "doubao-1-5-lite-32k-250115",
]

VOLC_IMAGE_SUGGESTED_MODELS = [
    "doubao-seedream-5.0-lite",
    "doubao-seedream-4.5",
    "doubao-seedream-4-5-251128",
    "doubao-seedream-4-0-250828",
]

_STRIP_SUFFIXES = (
    "/v1/chat/completions",
    "/chat/completions",
    "/v1/images/generations",
    "/images/generations",
    "/v1/audio/speech",
    "/audio/speech",
    "/v1/models",
    "/models",
    "/v1/responses",
    "/responses",
)

_VERSIONED_ROOT_SUFFIXES = (
    "/compatible-mode/v1",
    "/v1beta/openai",
    "/api/paas/v4",
    "/api/v3",
    "/v1",
    "/v2",
    "/v3",
    "/v4",
)


def is_volc_ark_host(url: str) -> bool:
    low = (url or "").lower()
    return "volces.com" in low or "volcengineapi.com" in low


def is_seedream_model(model: str) -> bool:
    m = (model or "").strip().lower()
    return "seedream" in m or m.startswith("doubao-seedream")


def normalize_base(base_url: str) -> str:
    b = (base_url or "").strip().rstrip("/")
    if not b:
        return b
    while True:
        low = b.lower()
        hit = next((s for s in _STRIP_SUFFIXES if low.endswith(s)), None)
        if not hit:
            break
        b = b[: -len(hit)].rstrip("/")
    if is_volc_ark_host(b) and not b.lower().endswith("/api/v3"):
        if "ark." in b.lower():
            return VOLC_ARK_BASE
    return b


def uses_versioned_root(base_url: str) -> bool:
    low = normalize_base(base_url).lower()
    return any(low.endswith(s) for s in _VERSIONED_ROOT_SUFFIXES)


def _join_resource(base_url: str, resource_path: str) -> str:
    base = normalize_base(base_url).rstrip("/")
    if uses_versioned_root(base):
        return f"{base}/{resource_path}"
    return f"{base}/v1/{resource_path}"


def build_chat_completions_url(base_url: str) -> str:
    return _join_resource(base_url, "chat/completions")


def build_images_generations_url(base_url: str) -> str:
    return _join_resource(base_url, "images/generations")


def map_seedream_size(size: str) -> str:
    s = (size or "").strip()
    low = s.lower()
    if low in ("1k", "2k", "3k", "4k"):
        return s.upper()
    if "4096" in low:
        return "4K"
    if "3072" in low:
        return "3K"
    if "2048" in low or s in ("1024x1024", "1280x1280", "256x256"):
        return "2K"
    if "512" in low:
        return "1K"
    return "2K"


def classify_image_body(base_url: str, model: str, is_siliconflow: bool) -> str:
    from .dmx_api_routing import should_use_dmx_special_image

    if should_use_dmx_special_image(base_url, model):
        return "openai_standard"
    if is_volc_ark_host(base_url) or is_seedream_model(model):
        return "volc_seedream"
    if is_siliconflow:
        return "siliconflow"
    return "openai_standard"


def collect_probe_bases(user_input: str, normalizer) -> list[str]:
    trimmed = (user_input or "").strip().rstrip("/")
    if not trimmed:
        return []
    out: list[str] = []
    seen: set[str] = set()

    def add(raw: str) -> None:
        n = normalize_base(raw) or normalizer(raw).strip().rstrip("/")
        if n and n.lower() not in seen:
            seen.add(n.lower())
            out.append(n)

    add(trimmed)
    if is_volc_ark_host(trimmed):
        add(VOLC_ARK_BASE)
    once = normalize_base(trimmed) or normalizer(trimmed).strip().rstrip("/")
    if once:
        low = once.lower()
        if uses_versioned_root(once) and not low.endswith("/v1"):
            pass
        elif low.endswith("/v1"):
            bare = once[:-3].rstrip("/")
            if bare.lower() not in seen:
                seen.add(bare.lower())
                out.append(bare)
        else:
            v1 = f"{once}/v1"
            if v1.lower() not in seen:
                seen.add(v1.lower())
                out.append(v1)
    return out

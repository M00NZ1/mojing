"""生图凭证解析与角色卡提示词（与 Web/Android 共用后端规则）。"""
from __future__ import annotations

import base64
import re

import httpx
from fastapi import HTTPException
from sqlalchemy.orm import Session

from ..config import STORAGE_DIR
from ..models import CharacterModel
from .crypto_service import decrypt_api_key
from .system_config_service import get_local_config


def build_encyclopedia_entry_cover_prompt(
    *,
    title: str,
    entry_type: str,
    summary: str,
    prompt_hint: str = "",
    max_summary_chars: int = 1000,
) -> str:
    """百科条目配图：竖卡比例、原创设定插画，不点名作。"""
    t = (title or "").strip() or "未命名条目"
    typ = (entry_type or "concept").strip()
    s = (summary or "").strip().replace("\r\n", "\n")
    if len(s) > max_summary_chars:
        s = s[: max_summary_chars - 1] + "…"
    hint = (prompt_hint or "").strip()
    parts = [
        "Original fictional encyclopedia illustration, single focal subject, vertical card layout 2:3, "
        "stylized art, no readable text in the image, no real-person likeness, no copyrighted logos or characters.",
        f"Entry title: {t}",
        f"Category / type: {typ}",
    ]
    if s:
        parts.append("Setting notes (visual cues only):\n" + s)
    if hint:
        parts.append("Additional art direction: " + hint)
    return "\n\n".join(parts)


def build_character_card_image_prompt(
    *,
    name: str,
    persona_prompt: str,
    prompt_hint: str = "",
    max_persona_chars: int = 1200,
) -> str:
    """竖卡角色图：强调原创角色、竖构图，避免点名作。"""
    n = (name or "").strip() or "未命名角色"
    p = (persona_prompt or "").strip().replace("\r\n", "\n")
    if len(p) > max_persona_chars:
        p = p[: max_persona_chars - 1] + "…"
    hint = (prompt_hint or "").strip()
    parts = [
        "Original fictional character portrait, single subject, vertical card layout 2:3 aspect, "
        "clean background or subtle abstract backdrop, high quality illustration, "
        "no real-person likeness, no copyrighted character or logo.",
        f"Character name: {n}",
    ]
    if p:
        parts.append("Character notes / personality (for visual cues only, do not render text on the image):\n" + p)
    if hint:
        parts.append("Additional art direction: " + hint)
    return "\n\n".join(parts)


def resolve_public_image_credentials(db: Session) -> tuple[str, str, str]:
    """仅使用 local_config 中的 public_image_*（与 public_text_* 并列）。"""
    cfg = get_local_config(db)
    api_key = decrypt_api_key(cfg.get("public_image_api_key") or "")
    base_url = (cfg.get("public_image_base_url") or "").strip()
    model = (cfg.get("public_image_model") or "dall-e-3").strip() or "dall-e-3"
    if not api_key or not base_url:
        raise HTTPException(
            status_code=400,
            detail="请先在「设置」中填写配图服务地址与访问密钥；或由管理员在服务端完成配图线路配置后再试。",
        )
    return api_key, base_url, model


def resolve_character_image_credentials(db: Session, character: CharacterModel) -> tuple[str, str, str]:
    """生图凭证：与 Android `ApiKeyResolver.resolveImageGenPrimaryResolved` 对齐。

    - **不**使用角色「对话」`api_key` / `api_base_url` 作为配图兜底。
    - 角色开启配图且 `image_gen_*` 成对有效时优先使用；
    - 否则依次尝试 `public_image_*`、`public_text_*`（公共配图 Key → 公共对话 Key）。
    """
    cfg = get_local_config(db)
    pub_img_k = decrypt_api_key(cfg.get("public_image_api_key") or "")
    pub_img_b = (cfg.get("public_image_base_url") or "").strip()
    pub_img_m = (cfg.get("public_image_model") or "dall-e-3").strip() or "dall-e-3"
    pub_txt_k = decrypt_api_key(cfg.get("public_text_api_key") or "")
    pub_txt_b = (cfg.get("public_text_base_url") or "").strip()

    if character.image_gen_enabled:
        k = decrypt_api_key(character.image_gen_api_key or "")
        b = (character.image_gen_base_url or "").strip()
        m = (character.image_gen_model or "").strip() or "dall-e-3"
        if k and b:
            return k, b, m
        if pub_img_k and pub_img_b:
            return pub_img_k, pub_img_b, pub_img_m
        if pub_txt_k and pub_txt_b:
            return pub_txt_k, pub_txt_b, pub_img_m
        raise HTTPException(
            status_code=400,
            detail="已开启角色配图，但未填写完整的生图地址与密钥；且设置中未配置可用的公共配图或公共对话线路。",
        )

    if pub_img_k and pub_img_b:
        return pub_img_k, pub_img_b, pub_img_m

    raise HTTPException(
        status_code=400,
        detail="未配置生图线路：请在角色页开启「图片生成」并填写生图地址与密钥，或在「设置」中填写公共生图 API（public_image_*）。",
    )


async def persist_first_generated_image(
    *,
    urls: list[str],
    dest_subdir: str,
    file_prefix: str,
    download_bearer: str | None = None,
) -> str:
    """将上游返回的首张图保存到 STORAGE_DIR 下，返回相对 STORAGE_DIR 的正斜杠路径。

    部分环境（系统 HTTP(S)_PROXY）会导致拉取生图 CDN 失败；先直连（trust_env=False）再回退系统代理。
    硅基等网关返回的临时 URL 有时需带 Bearer 拉取，可通过 download_bearer 传入与「生图」相同的 API Key。
    """
    if not urls:
        raise HTTPException(status_code=502, detail="生图服务未返回任何图片地址")

    raw = urls[0].strip()
    dest_dir = STORAGE_DIR / dest_subdir
    dest_dir.mkdir(parents=True, exist_ok=True)
    safe_prefix = re.sub(r"[^\w\-]+", "_", file_prefix).strip("_")[:40] or "img"

    if raw.startswith("data:image"):
        # data:image/png;base64,....
        try:
            header, b64 = raw.split(",", 1)
            ext = ".png" if "png" in header.lower() else ".jpg"
            data = base64.b64decode(b64)
        except Exception as exc:
            raise HTTPException(status_code=502, detail=f"解析 data URL 失败: {exc}") from exc
        out = dest_dir / f"{safe_prefix}_{abs(hash(raw)) & 0xFFFF:04x}{ext}"
        out.write_bytes(data)
    else:
        headers: dict[str, str] = {}
        if download_bearer and download_bearer.strip():
            # 硅基 / 自建网关常返回同域或子域临时链接，需鉴权
            low = raw.lower()
            if "siliconflow" in low or "openai" in low or "volces" in low or "byteimg" in low:
                headers["Authorization"] = f"Bearer {download_bearer.strip()}"

        data: bytes
        ctype: str
        try:
            data, ctype = await _download_image_bytes(raw, headers=headers)
        except httpx.HTTPError as exc:
            raise HTTPException(
                status_code=502,
                detail=f"下载生成的图片失败（网络/代理/防火墙或链接已过期）：{exc}",
            ) from exc
        ext = ".png" if "png" in ctype else ".webp" if "webp" in ctype else ".jpg"
        out = dest_dir / f"{safe_prefix}_{abs(hash(raw)) & 0xFFFF:04x}{ext}"
        out.write_bytes(data)

    # 避免 Windows 下 resolve / relative_to 与 STORAGE_DIR 不一致导致 ValueError → 500
    sub = dest_subdir.strip("/").replace("\\", "/")
    rel = f"{sub}/{out.name}"
    return rel


async def _download_image_bytes(url: str, *, headers: dict[str, str]) -> tuple[bytes, str]:
    """先不走系统代理，失败后再走系统代理（与 OpenAI SDK 走代理、CDN 直连冲突时的常见修复）。"""
    last: Exception | None = None
    for trust_env in (False, True):
        try:
            async with httpx.AsyncClient(timeout=120.0, follow_redirects=True, trust_env=trust_env) as client:
                resp = await client.get(url, headers=headers or None)
                resp.raise_for_status()
                return resp.content, (resp.headers.get("content-type") or "").lower()
        except httpx.HTTPError as exc:
            last = exc
            continue
    raise last  # noqa: B904  # both attempts failed

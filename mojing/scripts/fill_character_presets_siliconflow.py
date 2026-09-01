#!/usr/bin/env python3
"""
调用硅基流动 OpenAI 兼容「文生图」接口，将图片写入:
  android/app/src/main/assets/character_presets/

用法（不要把 Key 写进仓库）:
  Windows PowerShell:
    $env:SILICONFLOW_API_KEY = "sk-..."
    python scripts/fill_character_presets_siliconflow.py

  或把 Key 单独一行写入 scripts/.siliconflow_api_key（该文件已在 .gitignore）:
    python scripts/fill_character_presets_siliconflow.py

可选环境变量:
  SILICONFLOW_IMAGE_MODEL  默认 Kwai-Kolors/Kolors（若 FLUX 在账号侧被禁用可改环境变量）
  SILICONFLOW_IMAGE_SIZE   默认 1024x1024（与 App 内 ImageApiService 对 FLUX 分支一致）
"""
from __future__ import annotations

import json
import os
import ssl
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "android" / "app" / "src" / "main" / "assets" / "character_presets"
ENDPOINT = "https://api.siliconflow.cn/v1/images/generations"

# 二次元、偏插画向；避免写实敏感描写
JOBS: list[tuple[str, str]] = [
    (
        "preset_anime_teen_girl_01.png",
        "高质量二次元插画，少女角色，半身或全身，清爽赛璐璐上色，线条干净，柔和光影，"
        "浅色简单背景，无文字水印，非真人照片风格",
    ),
    (
        "preset_anime_teen_girl_02.png",
        "动漫风格立绘，少女，校园休闲装，微笑，明亮配色，全身或七分身，干净背景，"
        "无文字，插画质感",
    ),
    (
        "preset_anime_teen_boy_01.png",
        "高质量二次元插画，少年角色，短发，休闲外套，半身或全身，赛璐璐上色，"
        "简洁背景，无文字",
    ),
    (
        "preset_anime_teen_boy_02.png",
        "动漫风格立绘，少年，运动风穿搭，自信站姿，明亮色调，干净背景，无文字水印",
    ),
    (
        "preset_anime_child_girl_01.png",
        "Q版可爱二次元插画，小女孩角色，卡通比例，明亮糖果色，简单场景，"
        "健康阳光、非写实，无文字",
    ),
    (
        "preset_anime_child_girl_02.png",
        "儿童向动漫插画风格，小女孩，双马尾，连衣裙，柔和粉彩背景，Q版大头身比，无文字",
    ),
    (
        "preset_anime_child_boy_01.png",
        "Q版可爱二次元插画，小男孩角色，卡通比例，短裤T恤，户外浅色背景，"
        "活泼健康、非写实，无文字",
    ),
    (
        "preset_anime_child_boy_02.png",
        "儿童向动漫插画风格，小男孩，棒球帽，背包，明快配色，简单背景，Q版，无文字",
    ),
]


def load_api_key() -> str:
    k = os.environ.get("SILICONFLOW_API_KEY", "").strip()
    if k:
        return k
    p = ROOT / "scripts" / ".siliconflow_api_key"
    if p.is_file():
        line = p.read_text(encoding="utf-8").strip().splitlines()
        if line:
            return line[0].strip()
    return ""


def build_request_body(model: str, prompt: str, image_size: str) -> dict:
    m = model
    if "FLUX" in m.upper():
        return {"model": m, "prompt": prompt, "image_size": image_size}
    is_qwen_edit = "Qwen-Image-Edit" in m or "Qwen/Qwen-Image-Edit" in m
    o: dict = {"model": m, "prompt": prompt}
    if not is_qwen_edit:
        sz = image_size
        if "Qwen/Qwen-Image" in m and "Edit" not in m and image_size == "1024x1024":
            sz = "1328x1328"
        o["image_size"] = sz
        o["batch_size"] = 1
        o["num_inference_steps"] = 20
        o["guidance_scale"] = 4.0 if "Qwen-Image" in m else 7.5
    else:
        o["num_inference_steps"] = 20
        o["guidance_scale"] = 4.0
    return o


def request_image(api_key: str, model: str, image_size: str, prompt: str) -> str:
    body = json.dumps(
        build_request_body(model, prompt, image_size),
        ensure_ascii=False,
    ).encode("utf-8")
    req = urllib.request.Request(
        ENDPOINT,
        data=body,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    ctx = ssl.create_default_context()
    with urllib.request.urlopen(req, timeout=300, context=ctx) as resp:
        raw = resp.read().decode("utf-8")
    data = json.loads(raw)
    url = None
    for item in data.get("images") or data.get("data") or []:
        if isinstance(item, dict):
            u = (item.get("url") or "").strip()
            if u:
                url = u
                break
    if not url:
        raise RuntimeError(f"响应中无图片 URL: {raw[:800]}")
    return url


def download(url: str, dest: Path) -> None:
    req = urllib.request.Request(url, method="GET")
    ctx = ssl.create_default_context()
    with urllib.request.urlopen(req, timeout=120, context=ctx) as resp:
        dest.write_bytes(resp.read())


def main() -> None:
    api_key = load_api_key()
    if not api_key:
        raise SystemExit(
            "未找到 SILICONFLOW_API_KEY：请设置环境变量，或写入 scripts/.siliconflow_api_key（单行）。"
        )
    model = os.environ.get("SILICONFLOW_IMAGE_MODEL", "Kwai-Kolors/Kolors").strip()
    image_size = os.environ.get("SILICONFLOW_IMAGE_SIZE", "1024x1024").strip()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    for i, (filename, prompt) in enumerate(JOBS):
        dest = OUT_DIR / filename
        print(f"[{i + 1}/{len(JOBS)}] 生成 {filename} …")
        try:
            url = request_image(api_key, model, image_size, prompt)
            download(url, dest)
            print(f"    已保存 {dest} ({dest.stat().st_size} bytes)")
        except urllib.error.HTTPError as e:
            err = e.read().decode("utf-8", errors="replace")
            raise SystemExit(f"HTTP {e.code}: {err[:1200]}") from e
        time.sleep(2.5)
    print("全部完成。重新编译 App 后，角色页「从内置图库」会列出这些文件。")


if __name__ == "__main__":
    main()

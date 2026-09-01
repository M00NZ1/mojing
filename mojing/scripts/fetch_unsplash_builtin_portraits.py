"""下载 Unsplash Illustrations（标准 Unsplash License）到 builtin_pack，便于离线内置。

用法：
  python scripts/fetch_unsplash_builtin_portraits.py

可在脚本内 UNSPLASH_TARGETS 追加 (文件名, 完整图片 URL)。
"""
from __future__ import annotations

import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "data" / "builtin_pack" / "assets" / "builtin" / "avatars" / "raster"

# 高分辨率（长边约 2.5k–3k，可按需改 w=）
UNSPLASH_TARGETS: list[tuple[str, str]] = [
    (
        "unsplash_anime_stars_and_girl.jpg",
        "https://images.unsplash.com/vector-1753190326256-1f9c8a7256ee"
        "?fm=jpg&q=90&w=2880&auto=format&fit=max&ixlib=rb-4.1.0",
    ),
    (
        "unsplash_anime_cyberpunk_mask.jpg",
        "https://images.unsplash.com/vector-1745847439151-58e18d3c676b"
        "?fm=jpg&q=90&w=2880&auto=format&fit=max&ixlib=rb-4.1.0",
    ),
]


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    ua = "Mozilla/5.0 (compatible; MoJing/1.0; +https://github.com/)"
    for fname, url in UNSPLASH_TARGETS:
        dest = OUT_DIR / fname
        req = urllib.request.Request(url, headers={"User-Agent": ua})
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = resp.read()
        dest.write_bytes(data)
        print(f"wrote {dest} ({len(data)} bytes)")


if __name__ == "__main__":
    main()

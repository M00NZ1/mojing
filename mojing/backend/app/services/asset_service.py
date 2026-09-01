from __future__ import annotations

import json
from pathlib import Path

from ..schemas import AssetItemRead


PROJECT_DIR = Path(__file__).resolve().parents[3]
ASSET_MANIFEST_PATH = PROJECT_DIR / "data" / "assets" / "manifest.json"


def list_asset_items(category: str = "", keyword: str = "") -> list[AssetItemRead]:
    """读取资源清单，并按分类与关键词过滤。"""

    if not ASSET_MANIFEST_PATH.exists():
        return []
    payload = json.loads(ASSET_MANIFEST_PATH.read_text(encoding="utf-8"))
    rows = payload.get("items") or []
    normalized_category = category.strip().lower()
    normalized_keyword = keyword.strip().lower()

    results: list[AssetItemRead] = []
    for item in rows:
        if normalized_category and str(item.get("category", "")).lower() != normalized_category:
            continue
        if normalized_keyword:
            search_text = " ".join(
                [
                    str(item.get("id", "")),
                    str(item.get("label", "")),
                    str(item.get("category", "")),
                    " ".join(item.get("tags") or []),
                ]
            ).lower()
            if normalized_keyword not in search_text:
                continue
        results.append(
            AssetItemRead(
                id=str(item.get("id", "")),
                label=str(item.get("label", "")),
                category=str(item.get("category", "")),
                kind=str(item.get("kind", "image")),
                preview_path=str(item.get("preview_path", "")),
                storage_path=str(item.get("storage_path", "")),
                external_url=str(item.get("external_url", "")),
                source_label=str(item.get("source_label", "")),
                author=str(item.get("author", "")),
                license_name=str(item.get("license_name", "")),
                attribution_required=bool(item.get("attribution_required", False)),
                is_builtin=bool(item.get("is_builtin", False)),
                tags=list(item.get("tags") or []),
            )
        )
    return results


def list_asset_categories() -> list[str]:
    """返回资源类别列表。"""

    categories = {item.category for item in list_asset_items()}
    return sorted(categories)

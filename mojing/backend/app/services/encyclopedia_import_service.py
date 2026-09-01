from __future__ import annotations

import json
import re
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import EncyclopediaEntryModel, EntryVersionModel, WorldEncyclopediaModel
from .encyclopedia_template import COMMON_SOURCE_META, UNIVERSAL_TEMPLATE_ENTRIES


USER_AGENT = "MoJing/1.0 world-encyclopedia-importer"


def bootstrap_universal_template(db: Session, encyclopedia_id: int) -> dict:
    enc = db.get(WorldEncyclopediaModel, encyclopedia_id)
    if enc is None:
        raise ValueError("百科库不存在")

    created = 0
    skipped = 0
    updated = 0
    existing_by_title = {
        item.title: item
        for item in db.scalars(
            select(EncyclopediaEntryModel).where(EncyclopediaEntryModel.encyclopedia_id == encyclopedia_id)
        )
    }
    for payload in UNIVERSAL_TEMPLATE_ENTRIES:
        row = existing_by_title.get(payload["title"])
        if row is None:
            db.add(
                EncyclopediaEntryModel(
                    encyclopedia_id=encyclopedia_id,
                    title=payload["title"],
                    entry_type=payload["entry_type"],
                    summary=payload.get("summary", ""),
                    content=payload.get("content", ""),
                    tags=payload.get("tags", ""),
                    is_featured=payload.get("is_featured", False),
                    sort_order=payload.get("sort_order", 0),
                    meta_json=payload.get("meta_json", {}),
                )
            )
            created += 1
            continue

        meta = dict(row.meta_json or {})
        if int(meta.get("schema_version") or 0) >= 2 and meta.get("verification_status") == "template":
            skipped += 1
            continue

        meta.setdefault("template_required_fields", payload.get("meta_json", {}).get("template_required_fields", []))
        meta.setdefault("anti_hallucination_rule", payload.get("meta_json", {}).get("anti_hallucination_rule", ""))
        meta.setdefault("ai_completion_policy", payload.get("meta_json", {}).get("ai_completion_policy", ""))
        meta.setdefault("preserve_user_input", True)
        meta.setdefault("custom_fields", [])
        row.meta_json = meta
        updated += 1

    db.commit()
    return {
        "encyclopedia_id": encyclopedia_id,
        "created_count": created,
        "updated_count": updated,
        "skipped_count": skipped,
    }


def import_encyclopedia_sources(
    db: Session,
    *,
    encyclopedia_id: int,
    sources: list[dict],
    dry_run: bool = True,
    overwrite_existing: bool = False,
    max_extract_chars: int = 6000,
) -> dict:
    enc = db.get(WorldEncyclopediaModel, encyclopedia_id)
    if enc is None:
        raise ValueError("百科库不存在")
    if not sources:
        raise ValueError("sources 不能为空")

    items: list[dict] = []
    imported = 0
    skipped = 0
    failed = 0

    for source in sources:
        try:
            entry_payload = build_entry_payload_from_source(source, max_extract_chars=max_extract_chars)
            existing = db.scalar(
                select(EncyclopediaEntryModel).where(
                    EncyclopediaEntryModel.encyclopedia_id == encyclopedia_id,
                    EncyclopediaEntryModel.title == entry_payload["title"],
                )
            )
            if dry_run:
                items.append({**entry_payload, "status": "preview", "entry_id": existing.id if existing else None})
                continue
            if existing is not None and not overwrite_existing:
                skipped += 1
                items.append(
                    {
                        "title": existing.title,
                        "entry_type": existing.entry_type,
                        "status": "skipped_existing",
                        "entry_id": existing.id,
                        "source_url": entry_payload["meta_json"].get("source_url", ""),
                        "warnings": ["同名条目已存在，未开启覆盖导入。"],
                    }
                )
                continue
            if existing is None:
                row = EncyclopediaEntryModel(encyclopedia_id=encyclopedia_id, **entry_payload)
                db.add(row)
                db.flush()
                imported += 1
            else:
                _snapshot_entry(db, existing, "来源导入覆盖前快照")
                existing.entry_type = entry_payload["entry_type"]
                existing.summary = entry_payload["summary"]
                existing.content = entry_payload["content"]
                existing.tags = entry_payload["tags"]
                existing.is_featured = entry_payload["is_featured"]
                existing.meta_json = entry_payload["meta_json"]
                row = existing
                imported += 1
            items.append(
                {
                    "title": row.title,
                    "entry_type": row.entry_type,
                    "status": "imported",
                    "entry_id": row.id,
                    "source_url": row.meta_json.get("source_url", ""),
                    "warnings": row.meta_json.get("import_warnings", []),
                }
            )
        except Exception as exc:
            failed += 1
            items.append(
                {
                    "title": str(source.get("title") or source.get("url") or "未命名来源"),
                    "entry_type": str(source.get("entry_type") or "concept"),
                    "status": "failed",
                    "entry_id": None,
                    "source_url": str(source.get("url") or ""),
                    "warnings": [str(exc)],
                }
            )

    if not dry_run:
        db.commit()

    return {
        "encyclopedia_id": encyclopedia_id,
        "dry_run": dry_run,
        "imported_count": imported,
        "skipped_count": skipped,
        "failed_count": failed,
        "items": items,
    }


def build_entry_payload_from_source(source: dict, *, max_extract_chars: int) -> dict:
    title_hint = str(source.get("title") or "").strip()
    url = str(source.get("url") or "").strip()
    source_text = str(source.get("source_text") or "").strip()
    entry_type = str(source.get("entry_type") or "concept").strip() or "concept"
    tags = _normalize_tags(source.get("tags") or [])
    warnings: list[str] = []
    fetched: dict = {}

    if source_text:
        fetched = {
            "title": title_hint or _title_from_url(url) or "手动来源条目",
            "extract": source_text,
            "canonical_url": url,
            "api_url": str(source.get("api_url") or ""),
            "license": str(source.get("source_license") or ""),
        }
        if not url:
            warnings.append("手动文本没有 source_url，只能作为待核查资料。")
    elif url:
        fetched = fetch_mediawiki_extract(url=url, api_url=str(source.get("api_url") or ""), title=title_hint)
    else:
        raise ValueError("来源必须提供 url 或 source_text")

    title = title_hint or fetched.get("title") or _title_from_url(url)
    if not title:
        raise ValueError("无法识别来源标题")

    extract = _clean_text(str(fetched.get("extract") or ""))
    if not extract:
        raise ValueError("来源正文为空，未导入")
    clipped = extract[: max(800, min(max_extract_chars, 20000))]
    if len(extract) > len(clipped):
        warnings.append(f"来源正文超过 {len(clipped)} 字，已截断导入。")

    source_url = fetched.get("canonical_url") or url
    trust_level = str(source.get("trust_level") or ("official" if source.get("is_official") else "wiki")).strip()
    verification_status = "pending"
    if source_url and not source_text:
        verification_status = "fetched"
    if source.get("verified"):
        verification_status = "verified"
    if not source_url:
        verification_status = "manual_unverified"

    source_host = urllib.parse.urlparse(source_url).netloc if source_url else ""
    normalized_tags = _merge_unique(tags, [entry_type, source_host, "来源导入"])
    summary = _first_sentence(clipped, 220)
    meta = dict(COMMON_SOURCE_META)
    meta.update(
        {
            "source_kind": "manual_text" if source_text else "mediawiki",
            "source_url": source_url,
            "source_api_url": fetched.get("api_url", ""),
            "source_page_title": fetched.get("title") or title,
            "source_retrieved_at": datetime.now(timezone.utc).isoformat(),
            "source_license": fetched.get("license") or str(source.get("source_license") or ""),
            "source_trust_level": trust_level,
            "verification_status": verification_status,
            "canon_scope": str(source.get("canon_scope") or ""),
            "canon_conflicts": list(source.get("canon_conflicts") or []),
            "unknown_fields": list(source.get("unknown_fields") or []),
            "import_warnings": warnings,
            "source_excerpt_char_count": len(clipped),
        }
    )
    return {
        "title": title,
        "entry_type": entry_type,
        "summary": summary,
        "content": clipped,
        "tags": ",".join(item for item in normalized_tags if item),
        "related_entries": "",
        "sort_order": int(source.get("sort_order") or 0),
        "is_featured": bool(source.get("is_featured", False)),
        "meta_json": meta,
    }


def fetch_mediawiki_extract(*, url: str, api_url: str = "", title: str = "") -> dict:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError("来源 URL 必须是 http/https")

    final_title = title.strip() or _title_from_url(url)
    if not final_title:
        raise ValueError("无法从 URL 识别 MediaWiki 标题，请显式传 title")
    final_api_url = api_url.strip() or _derive_mediawiki_api_url(parsed)
    params = urllib.parse.urlencode(
        {
            "action": "query",
            "format": "json",
            "formatversion": "2",
            "prop": "extracts|info|pageprops",
            "explaintext": "1",
            "redirects": "1",
            "inprop": "url",
            "titles": final_title,
        }
    )
    request_url = f"{final_api_url}?{params}"
    request = urllib.request.Request(request_url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise ValueError(f"来源抓取失败：HTTP {exc.code}") from exc
    except urllib.error.URLError as exc:
        raise ValueError(f"来源抓取失败：{exc.reason}") from exc

    pages = ((payload.get("query") or {}).get("pages") or [])
    page = pages[0] if pages else {}
    if page.get("missing"):
        raise ValueError(f"MediaWiki 页面不存在：{final_title}")
    extract = str(page.get("extract") or "").strip()
    if not extract:
        raise ValueError(f"MediaWiki 页面无可导入正文：{final_title}")
    return {
        "title": str(page.get("title") or final_title),
        "extract": extract,
        "canonical_url": str(page.get("fullurl") or url),
        "api_url": final_api_url,
        "license": _guess_license(parsed.netloc),
    }


def _derive_mediawiki_api_url(parsed: urllib.parse.ParseResult) -> str:
    if parsed.netloc.endswith("wikipedia.org"):
        return f"{parsed.scheme}://{parsed.netloc}/w/api.php"
    return f"{parsed.scheme}://{parsed.netloc}/api.php"


def _title_from_url(url: str) -> str:
    if not url:
        return ""
    parsed = urllib.parse.urlparse(url)
    path = urllib.parse.unquote(parsed.path or "")
    match = re.search(r"/wiki/(.+)$", path)
    if match:
        return match.group(1).replace("_", " ").strip()
    name = path.rstrip("/").rsplit("/", 1)[-1]
    return name.replace("_", " ").strip()


def _guess_license(host: str) -> str:
    if host.endswith("wikipedia.org"):
        return "CC BY-SA / GFDL, 以页面实际声明为准"
    return ""


def _normalize_tags(values) -> list[str]:
    if isinstance(values, str):
        values = re.split(r"[,，\n]", values)
    result: list[str] = []
    for value in values or []:
        text = str(value).strip()
        if text and text not in result:
            result.append(text)
    return result


def _merge_unique(left: list[str], right: list[str]) -> list[str]:
    result = list(left)
    for item in right:
        text = str(item).strip()
        if text and text not in result:
            result.append(text)
    return result


def _clean_text(text: str) -> str:
    lines = [line.strip() for line in (text or "").splitlines()]
    cleaned = "\n".join(line for line in lines if line)
    return re.sub(r"\n{3,}", "\n\n", cleaned).strip()


def _first_sentence(text: str, limit: int) -> str:
    collapsed = " ".join((text or "").split())
    for sep in ["。", "！", "？", ". "]:
        idx = collapsed.find(sep)
        if 20 <= idx <= limit:
            return collapsed[: idx + len(sep)].strip()
    return collapsed[:limit].strip()


def _snapshot_entry(db: Session, entry: EncyclopediaEntryModel, change_note: str) -> None:
    latest = db.scalar(
        select(EntryVersionModel)
        .where(EntryVersionModel.entry_id == entry.id)
        .order_by(EntryVersionModel.version.desc())
        .limit(1)
    )
    version = int(latest.version + 1) if latest else 1
    db.add(
        EntryVersionModel(
            entry_id=entry.id,
            version=version,
            title=entry.title,
            summary=entry.summary,
            content=entry.content,
            tags=entry.tags,
            meta_snapshot_json=entry.meta_json or {},
            change_note=change_note,
            created_by="source_import",
        )
    )

"""
世界百科路由 — Wiki 级条目、关系图谱、时间线、版本系统。
"""
from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import (
    EncyclopediaEntryModel,
    EntryRelationModel,
    EntryVersionModel,
    TimelineEventModel,
    WorldEncyclopediaModel)
from ..schemas import (
    CharacterCardImageGenBody,
    CharacterCardImagePreviewResult,
    EncyclopediaEntryCoverPreviewBody,
    EncyclopediaPersistCoverFromUrlBody,
)
from ..services.name_generator import GENERATE_TYPES, STYLE_NAMES, generate_names
from ..services.name_refine_llm import refine_name_batch_with_llm
from ..services.encyclopedia_template import (
    FIRST_RELEASE_MODULES,
    UNIVERSAL_ENTRY_SCHEMAS,
    UNIVERSAL_TEMPLATE_MODULES,
)


router = APIRouter(
    prefix="/encyclopedia",
    tags=["世界百科"],
)

ENTRY_TYPES = [
    {"id": "character", "label": "人物 / NPC", "icon": "👤"},
    {"id": "location", "label": "地点 / 地图", "icon": "📍"},
    {"id": "faction", "label": "势力 / 组织", "icon": "🏛️"},
    {"id": "event", "label": "事件 / 历史", "icon": "📜"},
    {"id": "item", "label": "物品 / 装备", "icon": "⚔️"},
    {"id": "skill", "label": "技能 / 法术", "icon": "✨"},
    {"id": "profession", "label": "职业 / 体系", "icon": "📯"},
    {"id": "concept", "label": "概念 / 法则", "icon": "🔮"},
    {"id": "species", "label": "种族 / 生物", "icon": "🐉"},
    {"id": "world", "label": "世界观 / 总览", "icon": "🌍"},
    {"id": "timeline", "label": "时间线", "icon": "⏳"},
    {"id": "other", "label": "其他", "icon": "📄"},
]

RELATION_TYPES = [
    "属于", "位于", "控制", "敌对", "联盟", "使用", "持有",
    "创造", "参与", "影响", "导致", "继承", "师徒", "血缘",
    "雇佣", "信仰", "统治", "交易", "诅咒", "封印", "关联",
]

# ──────────────────────────────────────────────
#  兼容端点
# ──────────────────────────────────────────────

@router.post("/{encyclopedia_id}/bootstrap-template")
def bootstrap_template_builtin(encyclopedia_id: int, db: Session = Depends(get_db)):
    encyclopedia = db.execute(select(WorldEncyclopediaModel).where(WorldEncyclopediaModel.id == encyclopedia_id)).scalar_one_or_none()
    if not encyclopedia:
        raise HTTPException(status_code=404, detail="百科库不存在")
    
    import os, json
    builtin_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "services", "builtin_templates.json")
    try:
        with open(builtin_path, "r", encoding="utf-8") as f:
            templates_data = json.load(f)
    except Exception:
        raise HTTPException(status_code=500, detail="找不到内置模板数据文件")

    created_count = 0
    
    errors = []
    for type_key, data in templates_data.items():
        try:
            new_entry = EncyclopediaEntryModel(
                encyclopedia_id=encyclopedia.id,
                title=data.get("title", f"示例 {type_key}"),
                summary=data.get("summary", ""),
                content=data.get("content", ""),
                entry_type=type_key,
                meta_json=data.get("meta_json", {}),
                tags="内置模板,极详尽示例"
            )
            db.add(new_entry)
            db.commit()
            created_count += 1
        except Exception as e:
            import logging
            logging.error(f"Failed to insert builtin template for {type_key}: {e}")
            errors.append(f"{type_key}: {str(e)}")
            db.rollback()
            continue

    msg = f"成功导入了 {created_count} 个极详尽的内置百科模板"
    if errors:
        msg += f" (报错: {', '.join(errors)})"

    return {"ok": True, "created_count": created_count, "updated_count": 0, "skipped_count": 0, "message": msg}


@router.post("/{encyclopedia_id}/import-sources")
def import_sources_deprecated(encyclopedia_id: int, payload: dict):
    if not payload.get("sources"):
        raise HTTPException(status_code=400, detail="没有提供来源数据")
    return {"ok": True, "imported": 0, "skipped": 0, "errors": [], "dry_run": payload.get("dry_run", False), "items": []}

# ──────────────────────────────────────────────
#  百科库管理
# ──────────────────────────────────────────────

@router.get("/types")
def entry_types():
    return ENTRY_TYPES


@router.get("/entry-types")
def entry_types_compat():
    return ENTRY_TYPES


@router.get("/relation-types")
def relation_types():
    return RELATION_TYPES


@router.get("/schemas")
def entry_schemas():
    return UNIVERSAL_ENTRY_SCHEMAS


@router.get("/template-modules")
def template_modules():
    return {"modules": UNIVERSAL_TEMPLATE_MODULES, "first_release_modules": FIRST_RELEASE_MODULES}


@router.get("/source-policy")
def source_policy():
    return {
        "trust_levels": ["official", "wiki", "community", "manual", "unverified", "template"],
        "verification_statuses": ["verified", "fetched", "pending", "manual_unverified", "template"],
        "runtime_rule": "会话绑定玩家百科库后，AI 必须优先采用百科命中条目；未记录或未核验内容不能冒充官方设定。",
        "manual_input_rule": "玩家手动输入是最高优先级事实；AI 只能补齐空字段，不能改写用户原文。",
        "ai_completion_rule": "AI 补全内容必须标记为 AI补全/待确认，审核前不能等同于官方设定。",
    }


@router.get("", summary="获取百科列表")
def list_encyclopedias(db: Session = Depends(get_db)):
    from ..services.starter_catalog_service import hidden_catalog_ids
    rows = db.scalars(select(WorldEncyclopediaModel).where(WorldEncyclopediaModel.id.not_in(hidden_catalog_ids(db, "encyclopedias"))).order_by(WorldEncyclopediaModel.is_official.desc(), WorldEncyclopediaModel.updated_at.desc())).all()
    result = []
    for row in rows:
        entry_count = db.scalar(select(func.count(EncyclopediaEntryModel.id)).where(EncyclopediaEntryModel.encyclopedia_id == row.id)) or 0
        result.append({**row.__dict__, "entry_count": entry_count})
    return result


@router.post("")
def save_encyclopedia(payload: dict, db: Session = Depends(get_db)):
    if not payload.get("name"):
        raise HTTPException(status_code=400, detail="名称不能为空")
    editable_fields = (
        "name",
        "description",
        "genre_tags",
        "world_prompt",
        "gameplay_mode",
        "anti_cheat_prompt",
        "cover_image_path",
        "is_official",
    )
    encyclopedia_id = payload.get("id")
    if encyclopedia_id:
        existing = db.get(WorldEncyclopediaModel, int(encyclopedia_id))
        if not existing:
            raise HTTPException(status_code=404, detail="百科库不存在")
        for field in editable_fields:
            if field in payload:
                setattr(existing, field, payload.get(field) or "" if field != "is_official" else bool(payload.get(field)))
        db.commit()
        db.refresh(existing)
        return existing

    existing = db.scalar(select(WorldEncyclopediaModel).where(WorldEncyclopediaModel.name == payload["name"]))
    if existing:
        for field in editable_fields:
            if field in payload:
                setattr(existing, field, payload.get(field) or "" if field != "is_official" else bool(payload.get(field)))
        db.commit()
        db.refresh(existing)
        return existing
    obj = WorldEncyclopediaModel(
        name=payload["name"],
        description=payload.get("description", ""),
        genre_tags=payload.get("genre_tags", ""),
        world_prompt=payload.get("world_prompt", ""),
        gameplay_mode=payload.get("gameplay_mode", "自由剧情") or "自由剧情",
        anti_cheat_prompt=payload.get("anti_cheat_prompt", ""),
        cover_image_path=payload.get("cover_image_path", ""),
        is_official=payload.get("is_official", False))
    db.add(obj)
    db.commit()
    db.refresh(obj)
    return obj


@router.delete("/{encyclopedia_id}", summary="删除百科")
def delete_encyclopedia(encyclopedia_id: int, db: Session = Depends(get_db)):
    obj = db.get(WorldEncyclopediaModel, encyclopedia_id)
    if not obj:
        raise HTTPException(status_code=404, detail="百科库不存在")
    db.delete(obj)
    db.commit()
    return {"ok": True}


@router.post("/{encyclopedia_id}/import-worldinfo", summary="SillyTavern WorldInfo JSON → 批量创建百科条目")
def import_worldinfo_into_encyclopedia(encyclopedia_id: int, payload: dict, db: Session = Depends(get_db)):
    from ..services.worldinfo_import import parse_worldinfo_payload

    enc = db.get(WorldEncyclopediaModel, encyclopedia_id)
    if not enc:
        raise HTTPException(status_code=404, detail="百科库不存在")
    text = str(payload.get("json_text") or "").strip()
    try:
        items = parse_worldinfo_payload(text)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    created = 0
    for it in items:
        db.add(
            EncyclopediaEntryModel(
                encyclopedia_id=encyclopedia_id,
                title=it["title"],
                entry_type=it.get("entry_type", "concept"),
                summary=it.get("summary", ""),
                content=it.get("content", ""),
                tags=it.get("tags", ""),
                meta_json=it.get("meta_json") or {},
                confidence=it.get("confidence", "confirmed"),
            )
        )
        created += 1
    db.commit()
    return {"created": created}


# ──────────────────────────────────────────────
#  条目管理 (CRUD + 版本快照)
# ──────────────────────────────────────────────

def _snapshot_entry(db: Session, entry: EncyclopediaEntryModel, change_note: str = "") -> None:
    """创建版本快照。"""
    latest = db.scalar(
        select(EntryVersionModel)
        .where(EntryVersionModel.entry_id == entry.id)
        .order_by(EntryVersionModel.version.desc())
        .limit(1)
    )
    new_ver = (int(getattr(latest, "version", None) or 0) + 1) if latest else 1
    db.add(EntryVersionModel(
        entry_id=entry.id,
        version=new_ver,
        title=entry.title,
        summary=entry.summary,
        content=entry.content,
        tags=entry.tags,
        meta_snapshot_json=entry.meta_json or {},
        change_note=change_note or f"v{new_ver} 更新"))


@router.get("/entries")
def list_entries(
    encyclopedia_id: int | None = None,
    entry_type: str | None = None,
    q: str = "",
    tag: str = "",
    sort: str = "updated_at",
    limit: int = 100,
    offset: int = 0,
    db: Session = Depends(get_db)):
    stmt = select(EncyclopediaEntryModel)
    if encyclopedia_id:
        stmt = stmt.where(EncyclopediaEntryModel.encyclopedia_id == encyclopedia_id)
    if entry_type:
        stmt = stmt.where(EncyclopediaEntryModel.entry_type == entry_type)
    if q.strip():
        keyword = q.strip()
        stmt = stmt.where(
            EncyclopediaEntryModel.title.contains(keyword)
            | EncyclopediaEntryModel.content.contains(keyword)
            | EncyclopediaEntryModel.tags.contains(keyword)
        )
    if tag.strip():
        stmt = stmt.where(EncyclopediaEntryModel.tags.contains(tag.strip()))
    if sort == "title":
        stmt = stmt.order_by(EncyclopediaEntryModel.title.asc())
    else:
        stmt = stmt.order_by(EncyclopediaEntryModel.updated_at.desc())
    stmt = stmt.offset(offset).limit(limit)
    return list(db.scalars(stmt))


@router.get("/entries/{entry_id}")
def get_entry(entry_id: int, db: Session = Depends(get_db)):
    entry = db.get(EncyclopediaEntryModel, entry_id)
    if not entry:
        raise HTTPException(status_code=404, detail="条目不存在")
    enc = db.get(WorldEncyclopediaModel, entry.encyclopedia_id)
    # 获取关联条目
    relations = list(
        db.scalars(
            select(EntryRelationModel)
            .where(
                (EntryRelationModel.from_entry_id == entry_id)
                | (EntryRelationModel.to_entry_id == entry_id)
            )
        )
    )
    # 收集关联条目信息
    related_ids = set()
    for r in relations:
        related_ids.add(r.from_entry_id)
        related_ids.add(r.to_entry_id)
    related_ids.discard(entry_id)
    related_map = {}
    if related_ids:
        for r_id in related_ids:
            r_entry = db.get(EncyclopediaEntryModel, r_id)
            if r_entry:
                related_map[r_id] = {"id": r_entry.id, "title": r_entry.title, "entry_type": r_entry.entry_type}
    return {
        "entry": entry,
        "encyclopedia": enc,
        "relations": [
            {
                "id": r.id,
                "from_id": r.from_entry_id,
                "to_id": r.to_entry_id,
                "relation_type": r.relation_type,
                "label": r.label,
                "from_title": related_map.get(r.from_entry_id, {}).get("title", f"#{r.from_entry_id}"),
                "to_title": related_map.get(r.to_entry_id, {}).get("title", f"#{r.to_entry_id}"),
                "from_type": related_map.get(r.from_entry_id, {}).get("entry_type", ""),
                "to_type": related_map.get(r.to_entry_id, {}).get("entry_type", ""),
            }
            for r in relations
        ],
    }


@router.post("/entries")
def save_entry(payload: dict, db: Session = Depends(get_db)):
    entry_id = payload.get("id")
    change_note = payload.get("change_note", "")
    if entry_id and entry_id > 0:
        entry = db.get(EncyclopediaEntryModel, entry_id)
        if not entry:
            raise HTTPException(status_code=404, detail="条目不存在")
        _snapshot_entry(db, entry, change_note or f"编辑: {entry.title}")
        entry.title = payload.get("title", entry.title)
        entry.entry_type = payload.get("entry_type", entry.entry_type)
        entry.summary = payload.get("summary", entry.summary)
        entry.content = payload.get("content", entry.content)
        entry.tags = payload.get("tags", entry.tags)
        entry.is_featured = payload.get("is_featured", entry.is_featured)
        entry.meta_json = payload.get("meta_json", entry.meta_json)
        if "cover_image_path" in payload:
            entry.cover_image_path = str(payload.get("cover_image_path") or "")
        if payload.get("encyclopedia_id"):
            entry.encyclopedia_id = payload["encyclopedia_id"]
    else:
        entry = EncyclopediaEntryModel(
            encyclopedia_id=payload["encyclopedia_id"],
            title=payload.get("title", ""),
            entry_type=payload.get("entry_type", "concept"),
            summary=payload.get("summary", ""),
            content=payload.get("content", ""),
            cover_image_path=str(payload.get("cover_image_path") or ""),
            tags=payload.get("tags", ""),
            is_featured=payload.get("is_featured", False),
            meta_json=payload.get("meta_json", {}))
        db.add(entry)
    db.commit()
    db.refresh(entry)
    return entry


@router.post(
    "/entries/preview-cover-image",
    response_model=CharacterCardImagePreviewResult,
    summary="预览百科条目封面（仅 public_image_*，不落库）",
)
async def preview_encyclopedia_entry_cover_image(
    payload: EncyclopediaEntryCoverPreviewBody,
    db: Session = Depends(get_db),
):
    """供 Android 等本地条目 id 与服务器不一致时，仍用服务端公共生图线路得到预览 URL。"""
    from ..services.image_client_service import build_encyclopedia_entry_cover_prompt, resolve_public_image_credentials
    from ..services.image_service import generate_image

    api_key, base_url, model = resolve_public_image_credentials(db)
    prompt = build_encyclopedia_entry_cover_prompt(
        title=payload.title,
        entry_type=payload.entry_type,
        summary=payload.summary or "",
        prompt_hint=payload.prompt_hint,
    )
    result = await generate_image(
        prompt=prompt,
        api_key=api_key,
        base_url=base_url,
        model=model,
        size=(payload.size or "1024x1792").strip() or "1024x1792",
    )
    return CharacterCardImagePreviewResult(
        urls=list(result.get("urls") or []),
        revised_prompt=str(result.get("revised_prompt") or ""),
        error=(result.get("error") or None),
    )


@router.post(
    "/entries/persist-cover-from-url",
    summary="将图片 URL 拉取并保存为百科条目封面文件（不写 encyclopedia_entries 表）",
)
async def persist_encyclopedia_entry_cover_from_url(payload: EncyclopediaPersistCoverFromUrlBody):
    """Web 新建条目：先 preview-cover-image 再传首张 URL，由服务端下载落盘，避免浏览器跨域。"""
    from ..services.image_client_service import persist_first_generated_image

    raw = payload.image_url.strip()
    if not raw.startswith("https://"):
        raise HTTPException(status_code=400, detail="仅允许 https:// 图片地址")
    rel = await persist_first_generated_image(
        urls=[raw],
        dest_subdir="generated/encyclopedia/entry_covers",
        file_prefix="enc_entry_web_preview",
    )
    return {"cover_image_path": rel}


@router.post("/entries/{entry_id}/generate-cover-image", summary="AI 生成百科条目封面并写入 cover_image_path")
async def generate_encyclopedia_entry_cover_image(
    entry_id: int,
    payload: CharacterCardImageGenBody,
    db: Session = Depends(get_db),
):
    """使用服务端 public_image_* 生图并落盘（与角色卡预览链路一致，条目无独立生图 Key）。"""
    from ..services.image_client_service import (
        build_encyclopedia_entry_cover_prompt,
        persist_first_generated_image,
        resolve_public_image_credentials,
    )
    from ..services.image_service import generate_image

    entry = db.get(EncyclopediaEntryModel, entry_id)
    if not entry:
        raise HTTPException(status_code=404, detail="条目不存在")

    api_key, base_url, model = resolve_public_image_credentials(db)
    prompt = build_encyclopedia_entry_cover_prompt(
        title=entry.title,
        entry_type=entry.entry_type,
        summary=entry.summary or "",
        prompt_hint=payload.prompt_hint,
    )
    result = await generate_image(
        prompt=prompt,
        api_key=api_key,
        base_url=base_url,
        model=model,
        size=(payload.size or "1024x1792").strip() or "1024x1792",
    )
    err = result.get("error")
    urls = list(result.get("urls") or [])
    if not urls:
        detail = str(err).strip() if err else "生图服务未返回图片地址（可能仅支持 base64 或尺寸/模型不兼容）"
        raise HTTPException(status_code=502, detail=detail)

    _snapshot_entry(db, entry, change_note="AI 生成封面")
    rel = await persist_first_generated_image(
        urls=urls,
        dest_subdir="generated/encyclopedia/entry_covers",
        file_prefix=f"enc_entry_{entry_id}",
        download_bearer=api_key,
    )
    entry.cover_image_path = rel
    db.commit()
    db.refresh(entry)
    return entry


@router.delete("/entries/{entry_id}")
def delete_entry(entry_id: int, db: Session = Depends(get_db)):
    entry = db.get(EncyclopediaEntryModel, entry_id)
    if not entry:
        raise HTTPException(status_code=404, detail="条目不存在")
    _snapshot_entry(db, entry, f"删除: {entry.title}")
    db.delete(entry)
    db.commit()
    return {"ok": True}

# ──────────────────────────────────────────────
#  版本历史
# ──────────────────────────────────────────────

@router.get("/entries/{entry_id}/versions")
def list_entry_versions(entry_id: int, db: Session = Depends(get_db)):
    return list(
        db.scalars(
            select(EntryVersionModel)
            .where(EntryVersionModel.entry_id == entry_id)
            .order_by(EntryVersionModel.version.desc())
            .limit(50)
        )
    )


@router.get("/entries/{entry_id}/versions/{version}")
def get_entry_version(entry_id: int, version: int, db: Session = Depends(get_db)):
    ver = db.scalar(
        select(EntryVersionModel).where(
            EntryVersionModel.entry_id == entry_id,
            EntryVersionModel.version == version)
    )
    if not ver:
        raise HTTPException(status_code=404, detail="版本不存在")
    return ver

# ──────────────────────────────────────────────
#  关系图谱
# ──────────────────────────────────────────────

@router.get("/entries/{entry_id}/relations")
def list_entry_relations(entry_id: int, db: Session = Depends(get_db)):
    """获取某个条目关联的所有关系（图查询）。"""
    rels = list(
        db.scalars(
            select(EntryRelationModel)
            .where(
                (EntryRelationModel.from_entry_id == entry_id)
                | (EntryRelationModel.to_entry_id == entry_id)
            )
        )
    )
    node_ids = set()
    for r in rels:
        node_ids.add(r.from_entry_id)
        node_ids.add(r.to_entry_id)
    nodes = {}
    for nid in node_ids:
        e = db.get(EncyclopediaEntryModel, nid)
        if e:
            nodes[nid] = {"id": e.id, "title": e.title, "entry_type": e.entry_type}
    return {
        "nodes": list(nodes.values()),
        "edges": [
            {
                "id": r.id,
                "source": r.from_entry_id,
                "target": r.to_entry_id,
                "relation_type": r.relation_type,
                "label": r.label,
            }
            for r in rels
        ],
    }


@router.get("/entries/{entry_id}/graph")
def get_entry_graph(entry_id: int, depth: int = 2, db: Session = Depends(get_db)):
    """递归获取关联图（最大 depth 层）。"""
    visited = set()
    nodes = {}
    edges = []

    def walk(eid: int, d: int):
        if eid in visited or d > depth:
            return
        visited.add(eid)
        e = db.get(EncyclopediaEntryModel, eid)
        if e:
            nodes[eid] = {"id": e.id, "title": e.title, "entry_type": e.entry_type}
        rels = list(
            db.scalars(
                select(EntryRelationModel)
                .where((EntryRelationModel.from_entry_id == eid) | (EntryRelationModel.to_entry_id == eid))
            )
        )
        for r in rels:
            edges.append({"source": r.from_entry_id, "target": r.to_entry_id, "relation_type": r.relation_type, "label": r.label})
            other = r.to_entry_id if r.from_entry_id == eid else r.from_entry_id
            walk(other, d + 1)

    walk(entry_id, 0)
    return {"nodes": list(nodes.values()), "edges": edges}


@router.post("/relations")
def create_relation(payload: dict, db: Session = Depends(get_db)):
    """创建关系。"""
    from_id = payload.get("from_entry_id")
    to_id = payload.get("to_entry_id")
    if not from_id or not to_id:
        raise HTTPException(status_code=400, detail="需要 from_entry_id 和 to_entry_id")
    existing = db.scalar(
        select(EntryRelationModel).where(
            EntryRelationModel.from_entry_id == from_id,
            EntryRelationModel.to_entry_id == to_id,
            EntryRelationModel.relation_type == payload.get("relation_type", "关联"))
    )
    if existing:
        return existing
    rel = EntryRelationModel(
        encyclopedia_id=payload.get("encyclopedia_id", 0),
        from_entry_id=from_id,
        to_entry_id=to_id,
        relation_type=payload.get("relation_type", "关联"),
        label=payload.get("label", ""),
        metadata_json=payload.get("metadata_json", {}))
    db.add(rel)
    db.commit()
    db.refresh(rel)
    return rel


@router.delete("/relations/{relation_id}")
def delete_relation(relation_id: int, db: Session = Depends(get_db)):
    rel = db.get(EntryRelationModel, relation_id)
    if not rel:
        raise HTTPException(status_code=404, detail="关系不存在")
    db.delete(rel)
    db.commit()
    return {"ok": True}

# ──────────────────────────────────────────────
#  时间线
# ──────────────────────────────────────────────

@router.get("/timeline")
def list_timeline(encyclopedia_id: int | None = None, branch: str = "main", db: Session = Depends(get_db)):
    stmt = select(TimelineEventModel).order_by(TimelineEventModel.time_order.asc())
    if encyclopedia_id:
        stmt = stmt.where(TimelineEventModel.encyclopedia_id == encyclopedia_id)
    if branch:
        stmt = stmt.where(TimelineEventModel.timeline_branch == branch)
    return list(db.scalars(stmt))


@router.post("/timeline", summary="创建事件")
def create_timeline_event(payload: dict, db: Session = Depends(get_db)):
    event = TimelineEventModel(
        encyclopedia_id=payload.get("encyclopedia_id", 0),
        title=payload.get("title", ""),
        time_label=payload.get("time_label", ""),
        time_order=payload.get("time_order", 0),
        entry_type=payload.get("entry_type", "event"),
        summary=payload.get("summary", ""),
        content=payload.get("content", ""),
        tags=payload.get("tags", ""),
        timeline_branch=payload.get("timeline_branch", "main"),
        metadata_json=payload.get("metadata_json", {}))
    db.add(event)
    db.commit()
    db.refresh(event)
    return event


@router.delete("/timeline/{event_id}", summary="删除事件")
def delete_timeline_event(event_id: int, db: Session = Depends(get_db)):
    event = db.get(TimelineEventModel, event_id)
    if not event:
        raise HTTPException(status_code=404, detail="事件不存在")
    db.delete(event)
    db.commit()
    return {"ok": True}


@router.get("/{encyclopedia_id}/relation-graph", summary="百科库内全部关系边（可视化用）")
def encyclopedia_relation_graph(encyclopedia_id: int, db: Session = Depends(get_db)):
    enc = db.get(WorldEncyclopediaModel, encyclopedia_id)
    if not enc:
        raise HTTPException(status_code=404, detail="百科库不存在")
    rels = list(
        db.scalars(
            select(EntryRelationModel).where(EntryRelationModel.encyclopedia_id == encyclopedia_id)
        )
    )
    node_ids: set[int] = set()
    for r in rels:
        node_ids.add(r.from_entry_id)
        node_ids.add(r.to_entry_id)
    nodes: list[dict] = []
    for nid in node_ids:
        e = db.get(EncyclopediaEntryModel, nid)
        if e:
            nodes.append({"id": e.id, "title": e.title, "entry_type": e.entry_type})
    edges = [
        {
            "id": r.id,
            "source": r.from_entry_id,
            "target": r.to_entry_id,
            "relation_type": r.relation_type,
            "label": r.label or "",
        }
        for r in rels
    ]
    return {"encyclopedia_id": encyclopedia_id, "nodes": nodes, "edges": edges}


@router.get("/{encyclopedia_id}/sediment-entries", summary="整库沉淀条目（推断或带会话溯源）")
def list_sediment_entries(
    encyclopedia_id: int,
    limit: int = 200,
    offset: int = 0,
    db: Session = Depends(get_db),
):
    """与 Android `getSedimentEntries` 对齐：confidence=inferred 或 source_session_id 非空。"""
    enc = db.get(WorldEncyclopediaModel, encyclopedia_id)
    if not enc:
        raise HTTPException(status_code=404, detail="百科库不存在")
    lim = max(1, min(limit, 500))
    off = max(0, offset)
    stmt = (
        select(EncyclopediaEntryModel)
        .where(EncyclopediaEntryModel.encyclopedia_id == encyclopedia_id)
        .where(
            or_(
                EncyclopediaEntryModel.confidence == "inferred",
                EncyclopediaEntryModel.source_session_id.isnot(None),
            )
        )
        .order_by(EncyclopediaEntryModel.updated_at.desc())
        .offset(off)
        .limit(lim)
    )
    return list(db.scalars(stmt))


@router.post("/{encyclopedia_id}/sediment-entries/confirm")
def confirm_selected_sediment_entries(encyclopedia_id: int, payload: dict, db: Session = Depends(get_db)):
    ids = payload.get("entry_ids")
    if not isinstance(ids, list) or not 1 <= len(ids) <= 100 or any(type(value) is not int or value <= 0 for value in ids):
        raise HTTPException(status_code=400, detail="请选择 1 至 100 条资料")
    if not db.get(WorldEncyclopediaModel, encyclopedia_id):
        raise HTTPException(status_code=404, detail="百科库不存在")
    from ..services.sediment_service import confirm_sediment_entries
    return {"confirmed": confirm_sediment_entries(db, encyclopedia_id, list(dict.fromkeys(ids)))}


# ──────────────────────────────────────────────
#  AI 生成
# ──────────────────────────────────────────────

@router.post("/ai/generate")
def ai_generate_entries(payload: dict, db: Session = Depends(get_db)):
    """AI 自动生成条目（基于已有条目的补全和扩展）。"""
    enc_id = payload.get("encyclopedia_id")
    if not enc_id:
        raise HTTPException(status_code=400, detail="需要 encyclopedia_id")
    enc = db.get(WorldEncyclopediaModel, enc_id)
    if not enc:
        raise HTTPException(status_code=404, detail="百科库不存在")
    count = payload.get("count", 5)
    entry_type = payload.get("entry_type", "concept")
    existing_titles = {
        row.title for row in db.scalars(
            select(EncyclopediaEntryModel.title).where(EncyclopediaEntryModel.encyclopedia_id == enc_id)
        )
    }
    # 基于已有条目的标签生成推荐标题
    existing_tags = set()
    for row in db.scalars(select(EncyclopediaEntryModel.tags).where(EncyclopediaEntryModel.encyclopedia_id == enc_id)):
        for tag in (row or "").split(","):
            t = tag.strip()
            if t:
                existing_tags.add(t)

    suggestions = _generate_suggestions(enc.name, entry_type, existing_titles, existing_tags, count)
    return {"suggestions": suggestions, "existing_tags": list(existing_tags)}


def _generate_suggestions(enc_name: str, entry_type: str, existing: set, tags: set, count: int) -> list[dict]:
    """基于百科库名称和已有标签生成推荐条目。"""
    themes = []
    name_lower = enc_name.lower()
    if "战锤" in name_lower or "40k" in name_lower or "warhammer" in name_lower:
        themes = ["原体", "恶魔王子", "智库", "战斗修女", "星神", "太空亡灵法皇", "黑色军团", "灰骑士"]
    elif "修仙" in name_lower or "九天" in name_lower:
        themes = ["散修", "魔道功法", "上古秘境", "天材地宝", "渡劫秘法", "灵兽", "丹方"]
    elif "dnd" in name_lower or "费伦" in name_lower or "龙" in name_lower.lower():
        themes = ["地牢领主", "远古巨龙", "精灵王国", "矮人堡垒", "巫妖法塔", "魔法源泉", "神罚"]
    elif "克苏鲁" in name_lower:
        themes = ["深潜者", "星之眷族", "疯狂学者", "禁忌仪式", "古老神像", "无名之雾", "夏盖虫族"]
    else:
        themes = ["远古遗迹", "失落文明", "神秘组织", "传说生物", "禁忌知识", "英雄史诗"]

    result = []
    for i, theme in enumerate(themes):
        if len(result) >= count:
            break
        title = f"{enc_name} · {theme}"
        if title in existing:
            continue
        result.append({
            "title": title,
            "entry_type": entry_type or "concept",
            "summary": f"关于{theme}的条目",
            "content": f"这是{enc_name}世界中关于{theme}的详细描述……",
            "tags": theme,
        })
    return result[:count]


# ──────────────────────────────────────────────
#  名称生成
# ──────────────────────────────────────────────

@router.get("/generate-styles")
def list_generate_styles():
    """获取可用的名称生成风格列表。"""
    return [{"id": k, "label": v} for k, v in STYLE_NAMES.items()]


@router.get("/generate-types")
def list_generate_types():
    """获取可用的名称生成类型列表。"""
    return GENERATE_TYPES


@router.post("/generate-names")
def api_generate_names(payload: dict, db: Session = Depends(get_db)):
    """生成名称。可选 refine_llm=true 时用已配置的人物 LLM 再润色一轮（条数不变）。"""
    style = payload.get("style", "western")
    name_type = payload.get("name_type", "character")
    count = min(payload.get("count", 5), 30)
    existing = payload.get("existing", [])
    results = generate_names(style=style, name_type=name_type, count=count, existing=existing)
    refine_note: str | None = None
    if payload.get("refine_llm") and results:
        results, refine_note = refine_name_batch_with_llm(db, results, style, name_type)
    return {"results": results, "style": style, "name_type": name_type, "refine_note": refine_note}


# ──────────────────────────────────────────────
#  AI 智能补全条目字段
# ──────────────────────────────────────────────

# 每种条目类型需要 LLM 补全的核心字段清单
_AI_COMPLETE_FIELDS: dict[str, list[str]] = {
    "world": ["alias", "genre", "timeline_model", "geography_model", "power_system", "core_conflict", "hard_rules"],
    "faction": ["alias", "type", "founder", "leader", "headquarters", "status", "doctrine", "history", "allies", "enemies"],
    "character": ["alias", "race", "gender", "age", "faction", "title", "status", "personality", "appearance", "background", "abilities", "equipment"],
    "location": ["alias", "location_type", "region", "controller", "status", "population", "landmarks", "resources", "hazards"],
    "event": ["era", "time_label", "location", "participants", "causes", "process", "result", "impact"],
    "item": ["alias", "item_type", "rarity", "creator", "owner", "appearance", "effects", "limitations", "history"],
    "skill": ["skill_type", "level", "founder", "origin", "prerequisites", "effects", "side_effects", "users", "cost"],
    "concept": ["alias", "concept_type", "definition", "scope", "mechanism", "limits", "examples"],
    "species": ["alias", "origin", "habitat", "lifespan", "appearance", "abilities", "culture", "weaknesses"],
    "profession": ["role_type", "requirements", "duties", "skills", "equipment", "promotion_path"],
    "ecology": ["species_type", "habitat", "ecological_zone", "food_chain", "abilities", "weaknesses", "drops", "risk_level"],
    "quest": ["quest_type", "giver", "conditions", "rewards", "consequences", "branches", "endings"],
    "timeline": ["calendar", "time_label", "time_order", "branch"],
}


@router.post("/entries/ai-complete")
async def ai_complete_entry(payload: dict, db: Session = Depends(get_db)):
    """
    AI 智能补全条目字段。
    用户提供标题 +（一句话简介 和/或 详细内容），LLM 补全 meta_json；简介可单独驱动补全。
    """
    import json as json_mod
    import re

    from ..services.llm_client import build_public_text_client
    from ..services.llm_retry import safe_non_streaming_call

    title = (payload.get("title") or "").strip()
    summary_in = (payload.get("summary") or "").strip()
    content = (payload.get("content") or "").strip()
    entry_type = (payload.get("entry_type") or "concept").strip() or "concept"

    if not title:
        raise HTTPException(status_code=400, detail="条目标题不能为空")
    if not summary_in and not content:
        raise HTTPException(
            status_code=400,
            detail="请至少填写「一句话简介」或「详细内容」之一，便于模型理解条目。",
        )

    try:
        client, model_default = build_public_text_client(db)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"无法初始化大模型客户端：{exc}") from exc

    model_name = (payload.get("model_name") or "").strip() or model_default

    fields = _AI_COMPLETE_FIELDS.get(entry_type, ["alias", "definition", "scope", "examples"])
    fields_text = ", ".join(fields)

    desc_lines: list[str] = []
    if summary_in:
        desc_lines.append(f"一句话简介：{summary_in}")
    if content:
        desc_lines.append(f"详细内容（作者草稿）：{content}")
    user_desc_block = "\n".join(desc_lines)

    system_prompt = (
        "你是一个百科全书编辑助手。用户正在创建一个百科条目，请根据你的知识帮助补全以下字段。\n"
        "规则：\n"
        "1. 只返回 JSON 对象，不要附加任何解释\n"
        "2. 字段值为字符串或字符串数组（tags 类型用数组）\n"
        "3. 如果某个字段你不确定，填空字符串或空数组\n"
        "4. 内容用中文\n"
        "5. 不要编造虚假信息；涉及现实作品可简要标注出处\n"
        "6. 若用户未写「详细内容」草稿但有「一句话简介」，请额外返回键 content_draft："
        "作为百科「详细内容」初稿（可多段，300–2500 字），与 meta 短字段互补、避免重复堆砌。\n"
        "7. 若用户已有详细内容草稿，不要返回 content_draft，或返回空字符串。\n"
    )
    user_prompt = (
        f"条目类型：{entry_type}\n"
        f"标题：{title}\n"
        f"{user_desc_block}\n\n"
        f"请补全以下 meta 字段（JSON 键名如下）：{fields_text}\n\n"
        "同时请生成 summary（一句话简介）字段；若用户已写简介可润色或保持为空字符串表示不改。\n"
        "直接返回 JSON 对象，例如 {\"alias\": [\"别名1\"], \"summary\": \"...\", ...}"
    )

    try:
        raw = safe_non_streaming_call(
            client,
            model_name,
            [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            temperature=0.7,
            max_tokens=3500,
        )
        text = raw if isinstance(raw, str) else str(raw)
        if "```" in text:
            match = re.search(r"```(?:json)?\s*\n?(.*?)\n?```", text, re.DOTALL)
            if match:
                text = match.group(1).strip()
        json_start = text.find("{")
        json_end = text.rfind("}") + 1
        if json_start == -1 or json_end <= 0:
            raise ValueError("LLM 返回中未找到 JSON 对象")
        meta = json_mod.loads(text[json_start:json_end])
    except (json_mod.JSONDecodeError, ValueError):
        raise HTTPException(status_code=500, detail="AI 返回了无效的 JSON 格式，请重试") from None
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"AI 补全失败：{exc}") from exc

    summary_out = meta.pop("summary", "") or ""
    content_draft = (meta.pop("content_draft", None) or meta.pop("detailed_body", None) or "")
    if not isinstance(content_draft, str):
        content_draft = str(content_draft)

    return {
        "meta_json": meta,
        "summary": summary_out,
        "content": content_draft.strip(),
        "model_used": model_name,
    }


@router.post("/{encyclopedia_id}/sediment-from-session")
def sediment_from_session(encyclopedia_id: int, payload: dict, db: Session = Depends(get_db)):
    """手动触发从会话沉淀事实到百科。payload: {"session_id": int}"""
    session_id = payload.get("session_id")
    if not session_id:
        raise HTTPException(status_code=400, detail="缺少 session_id 参数")
    from ..services.sediment_service import auto_sediment_facts
    result = auto_sediment_facts(db, int(session_id), encyclopedia_id)
    return result


@router.put("/entries/{entry_id}/confidence")
def update_entry_confidence(entry_id: int, payload: dict, db: Session = Depends(get_db)):
    """更新条目置信度。payload: {"confidence": "confirmed"|"inferred"|"speculative"}"""
    new_confidence = payload.get("confidence", "")
    if new_confidence not in ("confirmed", "inferred", "speculative"):
        raise HTTPException(status_code=400, detail="置信度必须是 confirmed/inferred/speculative 之一")
    from ..services.sediment_service import promote_entry_confidence
    promote_entry_confidence(db, entry_id, new_confidence)
    return {"ok": True, "entry_id": entry_id, "confidence": new_confidence}


@router.post("/{encyclopedia_id}/batch-generate")
def batch_generate_entries(encyclopedia_id: int, payload: dict, db: Session = Depends(get_db)):
    """批量 AI 生成百科条目。payload 支持：
    - entry_type, count, context_hint, reference_style, sequential（同前）
    - query_override: 与 context_hint 合并用于条目节选召回（如当前分类名）
    - digest_token_budget: 节选 digest 的 token 预算（默认 900；≤0 则仅用字符安全上限）
    """
    entry_type = payload.get("entry_type", "character")
    count = int(payload.get("count", 10))
    context_hint = payload.get("context_hint", "")
    reference_style = payload.get("reference_style", "builtin")
    sequential = bool(payload.get("sequential", True))
    query_override = str(payload.get("query_override") or "").strip() or None
    raw_tb = payload.get("digest_token_budget", 900)
    digest_token_budget: int | None
    try:
        digest_token_budget = int(raw_tb) if raw_tb is not None else 900
        if digest_token_budget <= 0:
            digest_token_budget = None
    except (TypeError, ValueError):
        digest_token_budget = 900
    from ..services.batch_generate_service import batch_generate_entries as _batch_gen
    result = _batch_gen(
        db,
        encyclopedia_id,
        entry_type,
        count,
        context_hint,
        reference_style,
        sequential=sequential,
        query_override=query_override,
        digest_token_budget=digest_token_budget,
    )
    return result


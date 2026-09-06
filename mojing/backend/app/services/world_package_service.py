from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from sqlalchemy import delete, select
from sqlalchemy.orm import Session, load_only

from ..config import STORAGE_DIR
from ..models import WorldLoreEntryModel, WorldTemplateModel
from ..schemas import (
    WorldTemplateBundlePreviewItemRead,
    WorldTemplateBundlePreviewRead,
    WorldTemplateBundleRead,
    WorldTemplatePackageRead,
    WorldTemplateCreate,
    WorldLoreEntryCreate,
)


def build_world_template_package(db: Session, template_id: str) -> WorldTemplatePackageRead:
    """导出单个世界模板及其 Lorebook 条目。"""

    world = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == template_id))
    if world is None:
        raise ValueError("世界模板不存在")
    lore_entries = list(
        db.scalars(
            select(WorldLoreEntryModel)
            .where(WorldLoreEntryModel.world_template_id == world.id)
            .order_by(WorldLoreEntryModel.sort_order.asc(), WorldLoreEntryModel.id.asc())
        )
    )
    return WorldTemplatePackageRead(
        format_version=1,
        exported_at=datetime.utcnow(),
        template={
            "template_id": world.template_id,
            "label": world.label,
            "category": world.category,
            "summary": world.summary,
            "gameplay_mode": world.gameplay_mode,
            "world_prompt": world.world_prompt,
            "cover_image_path": world.cover_image_path,
            "suggested_choices": list(world.suggested_choices_json or []),
            "anti_cheat_prompt": world.anti_cheat_prompt,
        },
        lore_entries=[
            {
                "title": item.title,
                "entry_type": item.entry_type,
                "keywords_json": list(item.keywords_json or []),
                "content": item.content,
                "sort_order": item.sort_order,
                "is_core": item.is_core,
            }
            for item in lore_entries
        ],
    )


def write_world_template_package_file(package: WorldTemplatePackageRead, template_id: str) -> Path:
    """把世界模板包写成 JSON 文件，供前端下载。"""

    export_dir = STORAGE_DIR / "exports" / "world_templates"
    export_dir.mkdir(parents=True, exist_ok=True)
    export_path = export_dir / f"{template_id}.json"
    export_path.write_text(
        json.dumps(package.model_dump(mode="json"), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return export_path


def build_world_template_bundle(db: Session, include_builtin: bool = True) -> WorldTemplateBundleRead:
    """导出一组世界模板包。"""

    stmt = select(WorldTemplateModel).order_by(WorldTemplateModel.is_builtin.desc(), WorldTemplateModel.updated_at.desc())
    if not include_builtin:
        stmt = stmt.where(WorldTemplateModel.is_builtin.is_(False))
    rows = list(db.scalars(stmt))
    packages = [build_world_template_package(db, row.template_id) for row in rows]
    return WorldTemplateBundleRead(
        format_version=1,
        exported_at=datetime.utcnow(),
        package_count=len(packages),
        packages=packages,
    )


def write_world_template_bundle_file(bundle: WorldTemplateBundleRead, file_name: str = "world_templates_bundle") -> Path:
    """把一组世界模板包写成 JSON 文件。"""

    export_dir = STORAGE_DIR / "exports" / "world_templates"
    export_dir.mkdir(parents=True, exist_ok=True)
    export_path = export_dir / f"{file_name}.json"
    export_path.write_text(
        json.dumps(bundle.model_dump(mode="json"), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return export_path


def _validated_package(package_json: dict, *, new_template_id: str = "", new_label: str = "") -> dict:
    """Validate the complete versioned payload before any database mutation."""
    if not isinstance(package_json, dict) or package_json.get("format_version", 1) != 1:
        raise ValueError("不支持的世界模板包版本，原有资料未改变。")
    template_payload = package_json.get("template")
    lore_payload = package_json.get("lore_entries", [])
    if not isinstance(template_payload, dict) or not isinstance(lore_payload, list):
        raise ValueError("模板包需要 template 对象和 lore_entries 列表。")
    template = {
        "category": "通用", "summary": "", "gameplay_mode": "自由剧情", "world_prompt": "",
        **template_payload,
        "template_id": new_template_id or template_payload.get("template_id", ""),
        "label": new_label or template_payload.get("label", ""),
    }
    try:
        parsed = WorldTemplateCreate.model_validate(template)
        parsed.template_id = parsed.template_id.strip()
        parsed.label = parsed.label.strip()
        if not parsed.template_id or not parsed.label:
            raise ValueError()
        lore = []
        for index, item in enumerate(lore_payload):
            if not isinstance(item, dict):
                raise ValueError()
            lore.append(WorldLoreEntryCreate.model_validate({
                "title": f"条目 {index + 1}", "sort_order": index, **item,
            }).model_dump())
    except (ValueError, TypeError):
        raise ValueError("模板名称、标识或 Lore 字段无效，请修正模板包后重试。") from None
    return {"format_version": 1, "template": parsed.model_dump(), "lore_entries": lore}


def _validated_bundle(bundle_json: dict) -> list[dict]:
    if not isinstance(bundle_json, dict) or bundle_json.get("format_version", 1) != 1:
        raise ValueError("不支持的世界模板合集版本，原有资料未改变。")
    packages = bundle_json.get("packages")
    if not isinstance(packages, list) or not packages:
        raise ValueError("导入包缺少 packages 列表")
    if "package_count" in bundle_json and bundle_json["package_count"] != len(packages):
        raise ValueError("合集声明的模板数量与实际数量不一致。")
    return [_validated_package(item) for item in packages]


def _apply_world_template_package(db: Session, package: dict, *, override_existing: bool) -> WorldTemplateModel:
    template = package["template"]
    row = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == template["template_id"]))
    if row is not None and row.is_builtin:
        raise ValueError("不能覆盖内置世界模板，请改用新的模板 ID 导入")
    if row is not None and not override_existing:
        raise ValueError("模板 ID 已存在，如需覆盖请开启覆盖导入")
    if row is None:
        row = WorldTemplateModel(template_id=template["template_id"], is_builtin=False)
        db.add(row)
    else:
        db.execute(delete(WorldLoreEntryModel).where(WorldLoreEntryModel.world_template_id == row.id))
    for field in ("label", "category", "summary", "gameplay_mode", "world_prompt", "cover_image_path", "anti_cheat_prompt"):
        setattr(row, field, template[field])
    row.suggested_choices_json = template["suggested_choices"]
    db.flush()
    for item in package["lore_entries"]:
        db.add(WorldLoreEntryModel(world_template_id=row.id, **item))
    db.flush()
    return row


def import_world_template_package(
    db: Session,
    *,
    package_json: dict,
    override_existing: bool = False,
    new_template_id: str = "",
    new_label: str = "",
    commit: bool = True,
) -> WorldTemplateModel:
    """Import atomically; a route may include its job status in the same commit."""
    try:
        package = _validated_package(package_json, new_template_id=new_template_id, new_label=new_label)
        row = _apply_world_template_package(db, package, override_existing=override_existing)
        if commit:
            db.commit()
        return row
    except Exception:
        db.rollback()
        raise


def import_world_template_bundle(
    db: Session,
    *,
    bundle_json: dict,
    override_existing: bool = False,
    replace_all_custom_templates: bool = False,
    commit: bool = True,
) -> list[WorldTemplateModel]:
    """Validate the complete bundle; replace and insert in one transaction."""
    try:
        packages = _validated_bundle(bundle_json)
        ids = [item["template"]["template_id"] for item in packages]
        if len(ids) != len(set(ids)):
            raise ValueError("同一个合集包含重复模板 ID，请清理冲突后重试。")
        preview = preview_world_template_bundle_import(db, bundle_json=bundle_json,
            override_existing=override_existing, replace_all_custom_templates=replace_all_custom_templates)
        if preview.blocked_count:
            raise ValueError("合集存在无法导入的模板，请先处理预览中的冲突。")
        if replace_all_custom_templates:
            # Keep IDs for matching templates instead of deleting and recreating
            # them. Only templates absent from the incoming bundle are removed.
            removed = select(WorldTemplateModel.id).where(
                WorldTemplateModel.is_builtin.is_(False), WorldTemplateModel.template_id.not_in(ids))
            db.execute(delete(WorldLoreEntryModel).where(WorldLoreEntryModel.world_template_id.in_(removed)))
            db.execute(delete(WorldTemplateModel).where(
                WorldTemplateModel.is_builtin.is_(False), WorldTemplateModel.template_id.not_in(ids)))
        rows = [_apply_world_template_package(db, package,
            override_existing=override_existing or replace_all_custom_templates) for package in packages]
        if commit:
            db.commit()
        return rows
    except Exception:
        db.rollback()
        raise


def preview_world_template_bundle_import(
    db: Session,
    *,
    bundle_json: dict,
    override_existing: bool = False,
    replace_all_custom_templates: bool = False,
) -> WorldTemplateBundlePreviewRead:
    """预览批量模板恢复会产生的结果，避免用户直接盲覆盖。"""

    packages = _validated_bundle(bundle_json)

    existing_rows = list(db.scalars(select(WorldTemplateModel).options(load_only(
        WorldTemplateModel.template_id, WorldTemplateModel.label, WorldTemplateModel.is_builtin))))
    existing_map = {item.template_id: item for item in existing_rows}
    incoming_ids = {package["template"]["template_id"] for package in packages}
    will_delete_template_ids = [
        item.template_id
        for item in existing_rows
        if not item.is_builtin and item.template_id not in incoming_ids
    ] if replace_all_custom_templates else []

    seen_ids: dict[str, int] = {}
    duplicate_ids: set[str] = set()
    preview_items: list[WorldTemplateBundlePreviewItemRead] = []
    create_count = 0
    overwrite_count = 0
    recreate_count = 0
    blocked_count = 0
    warnings: list[str] = []

    for package_json in packages:
        template_payload = dict(package_json.get("template") or {})
        template_id = str(template_payload.get("template_id") or "").strip()
        label = str(template_payload.get("label") or "").strip()
        category = str(template_payload.get("category") or "通用").strip() or "通用"
        lore_entry_count = len(list(package_json.get("lore_entries") or []))

        if not template_id or not label:
            blocked_count += 1
            preview_items.append(
                WorldTemplateBundlePreviewItemRead(
                    template_id=template_id or "缺失",
                    label=label or "未命名模板",
                    category=category,
                    lore_entry_count=lore_entry_count,
                    action="阻止",
                    conflict_reason="模板缺少有效的模板 ID 或名称，无法恢复。",
                )
            )
            continue

        seen_ids[template_id] = seen_ids.get(template_id, 0) + 1
        if seen_ids[template_id] > 1:
            duplicate_ids.add(template_id)

        existing = existing_map.get(template_id)
        if seen_ids[template_id] > 1:
            blocked_count += 1
            preview_items.append(
                WorldTemplateBundlePreviewItemRead(
                    template_id=template_id,
                    label=label,
                    category=category,
                    lore_entry_count=lore_entry_count,
                    action="阻止",
                    conflict_reason="同一个批量包里出现重复模板 ID，必须先清理冲突。",
                    existing_label=existing.label if existing is not None else "",
                    existing_is_builtin=bool(existing.is_builtin) if existing is not None else False,
                )
            )
            continue

        if existing is None:
            create_count += 1
            preview_items.append(
                WorldTemplateBundlePreviewItemRead(
                    template_id=template_id,
                    label=label,
                    category=category,
                    lore_entry_count=lore_entry_count,
                    action="新建",
                )
            )
            continue

        if existing.is_builtin:
            blocked_count += 1
            preview_items.append(
                WorldTemplateBundlePreviewItemRead(
                    template_id=template_id,
                    label=label,
                    category=category,
                    lore_entry_count=lore_entry_count,
                    action="阻止",
                    conflict_reason="目标模板是内置模板，不能被批量恢复覆盖。",
                    existing_label=existing.label,
                    existing_is_builtin=True,
                )
            )
            continue

        if replace_all_custom_templates:
            overwrite_count += 1
            preview_items.append(
                WorldTemplateBundlePreviewItemRead(
                    template_id=template_id,
                    label=label,
                    category=category,
                    lore_entry_count=lore_entry_count,
                    action="替换内容",
                    conflict_reason="保留同标识模板并替换内容，包外自定义模板将移除。",
                    existing_label=existing.label,
                    existing_is_builtin=False,
                )
            )
            continue

        if override_existing:
            overwrite_count += 1
            preview_items.append(
                WorldTemplateBundlePreviewItemRead(
                    template_id=template_id,
                    label=label,
                    category=category,
                    lore_entry_count=lore_entry_count,
                    action="覆盖",
                    conflict_reason="当前模板 ID 已存在，恢复后会覆盖现有自定义模板。",
                    existing_label=existing.label,
                    existing_is_builtin=False,
                )
            )
            continue

        blocked_count += 1
        preview_items.append(
            WorldTemplateBundlePreviewItemRead(
                template_id=template_id,
                label=label,
                category=category,
                lore_entry_count=lore_entry_count,
                action="阻止",
                conflict_reason="当前模板 ID 已存在，但未开启覆盖恢复。",
                existing_label=existing.label,
                existing_is_builtin=False,
            )
        )

    if duplicate_ids:
        warnings.append("批量包内部存在重复模板 ID，重复项会被阻止恢复。")
    if replace_all_custom_templates and will_delete_template_ids:
        warnings.append("将移除合集之外的自定义模板；全部内容校验并写入成功后才会统一生效。")
    if blocked_count > 0 and not override_existing and not replace_all_custom_templates:
        warnings.append("存在需要覆盖或改名的模板，建议先预览后再决定是否恢复。")

    return WorldTemplateBundlePreviewRead(
        package_count=len(packages),
        create_count=create_count,
        overwrite_count=overwrite_count,
        recreate_count=recreate_count,
        blocked_count=blocked_count,
        duplicate_ids=sorted(duplicate_ids),
        replace_all_custom_templates=replace_all_custom_templates,
        will_delete_template_ids=sorted(will_delete_template_ids),
        warnings=warnings,
        items=preview_items,
    )

from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from ..config import STORAGE_DIR
from ..models import WorldLoreEntryModel, WorldTemplateModel
from ..schemas import (
    WorldTemplateBundlePreviewItemRead,
    WorldTemplateBundlePreviewRead,
    WorldTemplateBundleRead,
    WorldTemplatePackageRead,
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


def import_world_template_package(
    db: Session,
    *,
    package_json: dict,
    override_existing: bool = False,
    new_template_id: str = "",
    new_label: str = "",
) -> WorldTemplateModel:
    """导入单个世界模板包，并根据需要覆盖现有自定义模板。"""

    template_payload = dict(package_json.get("template") or {})
    lore_payload = list(package_json.get("lore_entries") or [])
    if not template_payload:
        raise ValueError("导入包缺少 template 字段")

    final_template_id = (new_template_id or template_payload.get("template_id") or "").strip()
    if not final_template_id:
        raise ValueError("导入包缺少有效的模板 ID")
    final_label = (new_label or template_payload.get("label") or "").strip()
    if not final_label:
        raise ValueError("导入包缺少有效的模板名称")

    existing = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == final_template_id))
    if existing is not None and existing.is_builtin:
        raise ValueError("不能覆盖内置世界模板，请改用新的模板 ID 导入")
    if existing is not None and not override_existing:
        raise ValueError("模板 ID 已存在，如需覆盖请开启覆盖导入")

    if existing is None:
        row = WorldTemplateModel(
            template_id=final_template_id,
            label=final_label,
            category=template_payload.get("category", "通用"),
            summary=template_payload.get("summary", ""),
            gameplay_mode=template_payload.get("gameplay_mode", "自由剧情"),
            world_prompt=template_payload.get("world_prompt", ""),
            cover_image_path=template_payload.get("cover_image_path", ""),
            suggested_choices_json=list(template_payload.get("suggested_choices") or []),
            anti_cheat_prompt=template_payload.get("anti_cheat_prompt", ""),
            is_builtin=False,
        )
        db.add(row)
        db.flush()
    else:
        row = existing
        row.label = final_label
        row.category = template_payload.get("category", row.category)
        row.summary = template_payload.get("summary", row.summary)
        row.gameplay_mode = template_payload.get("gameplay_mode", row.gameplay_mode)
        row.world_prompt = template_payload.get("world_prompt", row.world_prompt)
        row.cover_image_path = template_payload.get("cover_image_path", row.cover_image_path)
        row.suggested_choices_json = list(template_payload.get("suggested_choices") or [])
        row.anti_cheat_prompt = template_payload.get("anti_cheat_prompt", row.anti_cheat_prompt)
        db.execute(delete(WorldLoreEntryModel).where(WorldLoreEntryModel.world_template_id == row.id))

    for index, item in enumerate(lore_payload):
        db.add(
            WorldLoreEntryModel(
                world_template_id=row.id,
                title=str(item.get("title") or f"条目 {index + 1}"),
                entry_type=str(item.get("entry_type") or "设定"),
                keywords_json=list(item.get("keywords_json") or []),
                content=str(item.get("content") or ""),
                sort_order=int(item.get("sort_order") or index),
                is_core=bool(item.get("is_core", False)),
            )
        )

    db.commit()
    db.refresh(row)
    return row


def import_world_template_bundle(
    db: Session,
    *,
    bundle_json: dict,
    override_existing: bool = False,
    replace_all_custom_templates: bool = False,
) -> list[WorldTemplateModel]:
    """批量导入模板包，可选替换全部自定义模板。"""

    packages = list(bundle_json.get("packages") or [])
    if not packages:
        raise ValueError("导入包缺少 packages 列表")

    if replace_all_custom_templates:
        custom_templates = list(db.scalars(select(WorldTemplateModel).where(WorldTemplateModel.is_builtin.is_(False))))
        custom_ids = [item.id for item in custom_templates]
        if custom_ids:
            db.execute(delete(WorldLoreEntryModel).where(WorldLoreEntryModel.world_template_id.in_(custom_ids)))
            db.execute(delete(WorldTemplateModel).where(WorldTemplateModel.id.in_(custom_ids)))
            db.commit()

    imported_rows: list[WorldTemplateModel] = []
    for package_json in packages:
        row = import_world_template_package(
            db,
            package_json=package_json,
            override_existing=override_existing or replace_all_custom_templates,
        )
        imported_rows.append(row)
    return imported_rows


def preview_world_template_bundle_import(
    db: Session,
    *,
    bundle_json: dict,
    override_existing: bool = False,
    replace_all_custom_templates: bool = False,
) -> WorldTemplateBundlePreviewRead:
    """预览批量模板恢复会产生的结果，避免用户直接盲覆盖。"""

    packages = list(bundle_json.get("packages") or [])
    if not packages:
        raise ValueError("导入包缺少 packages 列表")

    existing_rows = list(db.scalars(select(WorldTemplateModel)))
    existing_map = {item.template_id: item for item in existing_rows}
    will_delete_template_ids = [
        item.template_id
        for item in existing_rows
        if not item.is_builtin
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
            recreate_count += 1
            preview_items.append(
                WorldTemplateBundlePreviewItemRead(
                    template_id=template_id,
                    label=label,
                    category=category,
                    lore_entry_count=lore_entry_count,
                    action="清空后重建",
                    conflict_reason="当前启用了“先清空全部自定义模板再恢复”，会先删除旧模板再重建。",
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
        warnings.append("已启用“先清空全部自定义模板再恢复”，恢复前会删除当前全部自定义模板。")
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

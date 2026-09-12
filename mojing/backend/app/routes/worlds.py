from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse
from sqlalchemy import or_, select, text
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import WorldLoreEntryModel, WorldTemplateModel, WorldEncyclopediaModel, LegacyWorldMappingModel, UnifiedWorldProfileModel
from ..schemas import (
    WorldGenerationRequest,
    WorldGenerationResponse,
    WorldImportRequest,
    WorldImportResponse,
    WorldQualityPreviewRequest,
    WorldQualityReportRead,
    WorldTemplateBundleImportRequest,
    WorldTemplateBundlePreviewRead,
    WorldTemplateBundleRead,
    WorldLoreEntryCreate,
    WorldLoreEntryRead,
    WorldLoreEntryUpdate,
    WorldTemplateCreate,
    WorldTemplatePackageImportRequest,
    WorldTemplatePackageRead,
    WorldTemplateRead,
    WorldTemplateUpdate)
from ..services.job_service import create_job_run, mark_job_failed, mark_job_running, mark_job_succeeded, mark_job_cancelled
from ..services.world_package_service import (
    build_world_template_bundle,
    build_world_template_package,
    import_world_template_bundle,
    import_world_template_package,
    preview_world_template_bundle_import,
    write_world_template_bundle_file,
    write_world_template_package_file)
from ..services.world_request_control import run_world_request
from ..services.world_job_service import complete_world_job, save_world_job_result
from ..services.world_building_service import generate_world_package, import_world_package, review_world_package



router = APIRouter(prefix="/worlds", tags=["世界库"])


def _require_legacy_editable(db: Session, world_template_id: int):
    if db.get(LegacyWorldMappingModel, world_template_id) is not None:
        raise HTTPException(status_code=409, detail="这份资料已归入世界，请在世界资料中编辑")


@router.get("/library", summary="获取世界与待整理的旧工坊资料")
def world_library(db: Session = Depends(get_db)):
    from ..services.starter_catalog_service import hidden_catalog_ids
    from ..services.unified_world_service import legacy_world_revision
    worlds = db.scalars(select(WorldEncyclopediaModel).where(
        WorldEncyclopediaModel.id.not_in(hidden_catalog_ids(db, "encyclopedias"))
    ).order_by(WorldEncyclopediaModel.updated_at.desc(), WorldEncyclopediaModel.id.desc())).all()
    legacy = db.scalars(select(WorldTemplateModel).where(
        WorldTemplateModel.id.not_in(select(LegacyWorldMappingModel.world_template_id)),
        WorldTemplateModel.id.not_in(hidden_catalog_ids(db, "worlds")),
    ).order_by(WorldTemplateModel.updated_at.desc(), WorldTemplateModel.id.desc())).all()
    profiles = {row.encyclopedia_id: row for row in db.scalars(select(UnifiedWorldProfileModel))}
    if any(row.id not in profiles for row in worlds):
        raise HTTPException(status_code=503, detail="世界资料升级尚未完成，请重新打开应用后重试")
    return {
        "worlds": [{"id": row.id, "name": row.name, "description": row.description,
                    "gameplay_mode": row.gameplay_mode, "cover_image_path": row.cover_image_path,
                    "world_key": profiles[row.id].world_key, "version": profiles[row.id].version}
                   for row in worlds],
        "legacy_templates": [{"template_id": row.template_id, "name": row.label,
                              "description": row.summary, "updated_at": row.updated_at.isoformat(),
                              "source_hash": legacy_world_revision(db, row)}
                             for row in legacy],
    }


@router.post("/templates/{template_id}/promote", summary="将旧工坊资料移入世界")
def promote_world_template(template_id: str, payload: dict, db: Session = Depends(get_db)):
    from ..services.unified_world_service import promote_legacy_world, legacy_world_revision
    db.execute(text("BEGIN IMMEDIATE"))
    row = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == template_id))
    if row is None:
        raise HTTPException(status_code=404, detail="世界资料不存在")
    if payload.get("updated_at") != row.updated_at.isoformat() or payload.get("source_hash") != legacy_world_revision(db, row):
        raise HTTPException(status_code=409, detail="世界资料已更新，请刷新后重新整理")
    try:
        canonical = promote_legacy_world(db, row)
        db.commit()
        return {"encyclopedia_id": canonical.id, "name": canonical.name}
    except Exception:
        db.rollback()
        raise


def _serialize_world_template(row: WorldTemplateModel, db: Session) -> WorldTemplateRead:
    mapping = db.get(LegacyWorldMappingModel, row.id)
    return WorldTemplateRead(
        id=row.id,
        encyclopedia_id=mapping.encyclopedia_id if mapping else None,
        template_id=row.template_id,
        label=row.label,
        category=row.category,
        summary=row.summary,
        gameplay_mode=row.gameplay_mode,
        world_prompt=row.world_prompt,
        cover_image_path=row.cover_image_path,
        suggested_choices=list(row.suggested_choices_json or []),
        anti_cheat_prompt=row.anti_cheat_prompt,
        is_builtin=row.is_builtin)


@router.get("/templates", summary="获取模板列表", response_model=list[WorldTemplateRead])
def list_world_templates(q: str = "", db: Session = Depends(get_db)):
    from ..services.starter_catalog_service import hidden_catalog_ids
    stmt = select(WorldTemplateModel).where(WorldTemplateModel.id.not_in(hidden_catalog_ids(db, "worlds")))
    keyword = q.strip()
    if keyword:
        like_value = f"%{keyword}%"
        stmt = stmt.where(
            or_(
                WorldTemplateModel.template_id.ilike(like_value),
                WorldTemplateModel.label.ilike(like_value),
                WorldTemplateModel.category.ilike(like_value),
                WorldTemplateModel.summary.ilike(like_value))
        )
    stmt = stmt.order_by(WorldTemplateModel.is_builtin.desc(), WorldTemplateModel.updated_at.desc())
    rows = list(db.scalars(stmt))
    return [_serialize_world_template(item, db) for item in rows]


@router.post("/templates", summary="创建模板", response_model=WorldTemplateRead)
def create_world_template(payload: WorldTemplateCreate, db: Session = Depends(get_db)):
    exists = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == payload.template_id))
    if exists is not None:
        raise HTTPException(status_code=400, detail="模板标识已存在，请换一个模板 ID")
    row = WorldTemplateModel(
        template_id=payload.template_id,
        label=payload.label,
        category=payload.category,
        summary=payload.summary,
        gameplay_mode=payload.gameplay_mode,
        world_prompt=payload.world_prompt,
        cover_image_path=payload.cover_image_path,
        suggested_choices_json=payload.suggested_choices,
        anti_cheat_prompt=payload.anti_cheat_prompt,
        is_builtin=False)
    db.add(row)
    db.commit()
    db.refresh(row)
    return _serialize_world_template(row, db)


@router.put("/templates/{template_id}", summary="更新模板", response_model=WorldTemplateRead)
def update_world_template(template_id: str, payload: WorldTemplateUpdate, db: Session = Depends(get_db)):
    row = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == template_id))
    if row is None:
        raise HTTPException(status_code=404, detail="世界模板不存在")
    _require_legacy_editable(db, row.id)
    if row.is_builtin:
        raise HTTPException(status_code=400, detail="内置模板不能直接修改，请复制后再编辑")
    row.label = payload.label
    row.category = payload.category
    row.summary = payload.summary
    row.gameplay_mode = payload.gameplay_mode
    row.world_prompt = payload.world_prompt
    row.cover_image_path = payload.cover_image_path
    row.suggested_choices_json = payload.suggested_choices
    row.anti_cheat_prompt = payload.anti_cheat_prompt
    db.commit()
    db.refresh(row)
    return _serialize_world_template(row, db)


@router.delete("/templates/{template_id}", summary="删除模板")
def delete_world_template(template_id: str, db: Session = Depends(get_db)):
    from ..services.world_reference_service import template_is_referenced
    row = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == template_id))
    if row is None:
        raise HTTPException(status_code=404, detail="世界模板不存在")
    _require_legacy_editable(db, row.id)
    if template_is_referenced(db, template_id):
        raise HTTPException(status_code=409, detail="这个世界仍被会话或默认开局使用，请先更换关联世界")
    if row.is_builtin:
        raise HTTPException(status_code=400, detail="内置模板不能删除")
    db.delete(row)
    db.commit()
    return {"ok": True}


@router.get("/templates/{template_id}/export", summary="导出模板包", response_model=WorldTemplatePackageRead)
def export_world_template_metadata(template_id: str, db: Session = Depends(get_db)):
    try:
        return build_world_template_package(db, template_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@router.get("/templates/export-bundle", response_model=WorldTemplateBundleRead)
def export_world_template_bundle_metadata(include_builtin: bool = True, db: Session = Depends(get_db)):
    return build_world_template_bundle(db, include_builtin=include_builtin)


@router.get("/templates/export-bundle-file", summary="导出合集文件")
def export_world_template_bundle_file(include_builtin: bool = True, db: Session = Depends(get_db)):
    bundle = build_world_template_bundle(db, include_builtin=include_builtin)
    export_path = write_world_template_bundle_file(
        bundle,
        "world_templates_bundle_all" if include_builtin else "world_templates_bundle_custom")
    return FileResponse(path=export_path, filename=export_path.name, media_type="application/json")


@router.get("/templates/{template_id}/export-file", summary="导出模板文件")
def export_world_template_file(template_id: str, db: Session = Depends(get_db)):
    try:
        package = build_world_template_package(db, template_id)
        export_path = write_world_template_package_file(package, template_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    return FileResponse(path=export_path, filename=export_path.name, media_type="application/json")


@router.post("/templates/import-package", response_model=WorldTemplateRead)
def import_world_template_archive(payload: WorldTemplatePackageImportRequest, db: Session = Depends(get_db)):
    job = create_job_run(
        db,
        job_type="world_template_import",
        scope="world",
        input_json={
            "override_existing": payload.override_existing,
            "new_template_id": payload.new_template_id,
        })
    mark_job_running(db, job.id)
    try:
        row = import_world_template_package(
            db,
            package_json=payload.package_json,
            override_existing=payload.override_existing,
            new_template_id=payload.new_template_id,
            new_label=payload.new_label, commit=False)
        mark_job_succeeded(
            db,
            job.id,
            {
                "template_id": row.template_id,
                "label": row.label,
            })
        return _serialize_world_template(row, db)
    except ValueError as exc:
        db.rollback()
        mark_job_failed(db, job.id, str(exc))
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        db.rollback()
        mark_job_failed(db, job.id, str(exc))
        raise


@router.post("/templates/import-bundle", response_model=list[WorldTemplateRead])
def import_world_template_bundle_archive(payload: WorldTemplateBundleImportRequest, db: Session = Depends(get_db)):
    job = create_job_run(
        db,
        job_type="world_template_import_bundle",
        scope="world",
        input_json={
            "override_existing": payload.override_existing,
            "replace_all_custom_templates": payload.replace_all_custom_templates,
        })
    mark_job_running(db, job.id)
    try:
        rows = import_world_template_bundle(
            db,
            bundle_json=payload.bundle_json,
            override_existing=payload.override_existing,
            replace_all_custom_templates=payload.replace_all_custom_templates, commit=False)
        mark_job_succeeded(
            db,
            job.id,
            {
                "template_count": len(rows),
                "template_ids": [row.template_id for row in rows],
            })
        return [_serialize_world_template(row, db) for row in rows]
    except ValueError as exc:
        db.rollback()
        mark_job_failed(db, job.id, str(exc))
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        db.rollback()
        mark_job_failed(db, job.id, str(exc))
        raise


@router.post("/templates/preview-bundle-import", response_model=WorldTemplateBundlePreviewRead)
def preview_world_template_bundle_archive(payload: WorldTemplateBundleImportRequest, db: Session = Depends(get_db)):
    try:
        return preview_world_template_bundle_import(
            db,
            bundle_json=payload.bundle_json,
            override_existing=payload.override_existing,
            replace_all_custom_templates=payload.replace_all_custom_templates)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/templates/{template_id}/lore", summary="获取 Lore 列表", response_model=list[WorldLoreEntryRead])
def list_world_lore_entries(template_id: str, db: Session = Depends(get_db)):
    world = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == template_id))
    if world is None:
        raise HTTPException(status_code=404, detail="世界模板不存在")
    return list(
        db.scalars(
            select(WorldLoreEntryModel)
            .where(WorldLoreEntryModel.world_template_id == world.id)
            .order_by(WorldLoreEntryModel.sort_order.asc(), WorldLoreEntryModel.id.asc())
        )
    )


@router.post("/templates/{template_id}/lore", summary="创建 Lore 条目", response_model=WorldLoreEntryRead)
def create_world_lore_entry(template_id: str, payload: WorldLoreEntryCreate, db: Session = Depends(get_db)):
    world = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == template_id))
    if world is None:
        raise HTTPException(status_code=404, detail="世界模板不存在")
    _require_legacy_editable(db, world.id)
    row = WorldLoreEntryModel(
        world_template_id=world.id,
        title=payload.title,
        entry_type=payload.entry_type,
        keywords_json=payload.keywords_json,
        content=payload.content,
        sort_order=payload.sort_order,
        is_core=payload.is_core)
    db.add(row)
    db.commit()
    db.refresh(row)
    return row


@router.put("/lore/{entry_id}", summary="更新 Lore 条目", response_model=WorldLoreEntryRead)
def update_world_lore_entry(entry_id: int, payload: WorldLoreEntryUpdate, db: Session = Depends(get_db)):
    row = db.get(WorldLoreEntryModel, entry_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Lore 条目不存在")
    _require_legacy_editable(db, row.world_template_id)
    row.title = payload.title
    row.entry_type = payload.entry_type
    row.keywords_json = payload.keywords_json
    row.content = payload.content
    row.sort_order = payload.sort_order
    row.is_core = payload.is_core
    db.commit()
    db.refresh(row)
    return row


@router.delete("/lore/{entry_id}", summary="删除 Lore 条目")
def delete_world_lore_entry(entry_id: int, db: Session = Depends(get_db)):
    row = db.get(WorldLoreEntryModel, entry_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Lore 条目不存在")
    _require_legacy_editable(db, row.world_template_id)
    db.delete(row)
    db.commit()
    return {"ok": True}


@router.post("/review-quality", summary="完整质量评审（模板 + Lore，对齐 generate/import 报告）", response_model=WorldQualityReportRead)
def review_world_quality(payload: WorldQualityPreviewRequest, db: Session = Depends(get_db)):
    _ = db  # 预留：后续可接 DB 侧 Lore
    tmpl = WorldTemplateCreate(
        template_id=(payload.template_id or "preview").strip() or "preview",
        label=payload.label,
        category=payload.category,
        summary=payload.summary,
        gameplay_mode=payload.gameplay_mode,
        world_prompt=payload.world_prompt,
        cover_image_path=payload.cover_image_path,
        suggested_choices=list(payload.suggested_choices or []),
        anti_cheat_prompt=payload.anti_cheat_prompt,
    )
    return review_world_package(tmpl, list(payload.lore_entries or []))


@router.post("/generate", summary="AI 生成世界", response_model=WorldGenerationResponse)
async def generate_world(payload: WorldGenerationRequest, db: Session = Depends(get_db), request: Request = None):
    job = create_job_run(
        db,
        job_type="world_generate",
        scope="world",
        input_json={
            "world_type": payload.world_type,
            "character_id": payload.character_id,
            "template_id": payload.template_id,
        })
    mark_job_running(db, job.id)
    try:
        result = await run_world_request(
            lambda: generate_world_package(db, payload.model_copy(update={"auto_save": False})),
            request.is_disconnected if request else None)
        complete_world_job(db, job.id, result)
    except asyncio.CancelledError:
        db.rollback()
        mark_job_cancelled(db, job.id)
        raise
    except ValueError as exc:
        db.rollback()
        mark_job_failed(db, job.id, str(exc))
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        db.rollback()
        mark_job_failed(db, job.id, str(exc))
        raise
    if payload.auto_save:
        db.rollback()
        return save_world_job_result(db, result.job_id)
    return result


@router.post("/import", summary="导入世界设定", response_model=WorldImportResponse)
async def import_world(payload: WorldImportRequest, db: Session = Depends(get_db), request: Request = None):
    job = create_job_run(
        db,
        job_type="world_import",
        scope="world",
        input_json={
            "source_filename": payload.source_filename,
            "category_hint": payload.category_hint,
            "character_id": payload.character_id,
        })
    mark_job_running(db, job.id)
    try:
        result = await run_world_request(
            lambda: import_world_package(db, payload.model_copy(update={"auto_save": False})),
            request.is_disconnected if request else None)
        complete_world_job(db, job.id, result)
    except asyncio.CancelledError:
        db.rollback()
        mark_job_cancelled(db, job.id)
        raise
    except ValueError as exc:
        db.rollback()
        mark_job_failed(db, job.id, str(exc))
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        db.rollback()
        mark_job_failed(db, job.id, str(exc))
        raise
    if payload.auto_save:
        db.rollback()
        return save_world_job_result(db, result.job_id)
    return result

"""World generation results remain owned by their existing local job records."""
from sqlalchemy import func, select, text
from sqlalchemy.orm import Session
from pydantic import ValidationError

from ..models import JobRunModel, WorldTemplateModel
from ..schemas import WorldGenerationResponse, WorldTemplateRead
from .job_service import mark_job_succeeded
from .world_package_service import import_world_template_package


def _template_read(row, db):
    from .unified_world_service import mapped_world_id
    return WorldTemplateRead(
        encyclopedia_id=mapped_world_id(db, row.template_id),
        **{field: getattr(row, field) for field in (
            "id", "template_id", "label", "category", "summary", "gameplay_mode",
            "world_prompt", "cover_image_path", "anti_cheat_prompt", "is_builtin")},
        suggested_choices=list(row.suggested_choices_json or []))


def complete_world_job(db: Session, job_id: int, result):
    result.job_id = job_id
    mark_job_succeeded(db, job_id, {
        "world_result_version": 1, "world_result": result.model_dump(mode="json"),
        "label": result.template.label, "template_id": result.template.template_id,
        "category": result.template.category, "quality_score": result.quality_report.score,
        "chunk_count": result.debug.chunk_count if result.debug else 1,
    })
    return result


def world_job_history(db: Session, *, before_id: int | None = None, limit: int = 20):
    from .world_checkpoint_service import recover_world_jobs
    recover_world_jobs(db)
    limit = min(max(limit, 1), 50)
    job = JobRunModel
    stmt = select(job.id, job.job_type, job.status, job.created_at, job.finished_at,
        func.substr(job.error_message, 1, 600).label("error_message"),
        func.coalesce(func.json_extract(job.output_json, "$.label"),
            func.json_extract(job.input_json, "$.label"),
            func.json_extract(job.input_json, "$.source_filename"),
            func.json_extract(job.input_json, "$.world_type"), "世界创作").label("label"),
        func.json_extract(job.output_json, "$.world_result_version").label("result_version"),
        func.json_extract(job.input_json, "$.world_request_version").label("request_version"),
        func.json_extract(job.output_json, "$.completed_steps").label("completed_steps"),
        func.json_extract(job.output_json, "$.stage_label").label("stage_label"),
    ).where(job.scope == "world", job.job_type.in_(["world_generate", "world_import"]))
    if before_id is not None:
        stmt = stmt.where(job.id < before_id)
    rows = list(db.execute(stmt.order_by(job.id.desc()).limit(limit + 1)).mappings())
    return {"items": [dict(row) for row in rows[:limit]],
            "next_cursor": rows[limit - 1]["id"] if len(rows) > limit else None}


def read_world_job_result(db: Session, job_id: int):
    job = db.get(JobRunModel, job_id)
    if job is None or job.scope != "world" or job.job_type not in ("world_generate", "world_import"):
        raise LookupError("生成记录不存在")
    output = job.output_json or {}
    if output.get("world_result_version") != 1:
        raise ValueError("这条旧记录没有可恢复的完整结果。已保存的世界仍可在“我的世界”中查看。")
    try:
        result = WorldGenerationResponse.model_validate(output.get("world_result"))
    except ValidationError:
        raise ValueError("记录中的结果格式无效，原始记录已保留。") from None
    result.job_id = job.id
    # A deleted template must not leave a false saved badge or dead action.
    if result.saved_template:
        saved = db.get(WorldTemplateModel, result.saved_template.id)
        if saved is None or saved.template_id != result.saved_template.template_id:
            result.saved_template = None
        else:
            result.saved_template = _template_read(saved, db)
    return job, result


def save_world_job_result(db: Session, job_id: int):
    try:
        # Serialize repeat/double-click saves before checking their saved marker.
        db.execute(text("BEGIN IMMEDIATE"))
        db.expire_all()
        job, result = read_world_job_result(db, job_id)
        if result.saved_template:
            db.rollback()
            return result
        template_id = result.template.template_id
        if db.scalar(select(WorldTemplateModel.id).where(WorldTemplateModel.template_id == template_id)) is not None:
            template_id = f"world_job_{job.id}_{template_id}"
        row = import_world_template_package(db, package_json={
            "format_version": 1, "template": result.template.model_dump(),
            "lore_entries": [entry.model_dump() for entry in result.lore_entries],
        }, new_template_id=template_id, commit=False)
        result.saved_template = _template_read(row, db)
        job.output_json = {**job.output_json, "world_result": result.model_dump(mode="json")}
        db.commit()
        return result
    except Exception:
        db.rollback()
        raise

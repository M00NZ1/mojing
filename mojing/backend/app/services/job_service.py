from __future__ import annotations

from datetime import datetime

from sqlalchemy import JSON, func, select, type_coerce
from sqlalchemy.orm import Session

from ..models import JobRunModel


def create_job_run(
    db: Session,
    *,
    job_type: str,
    scope: str,
    target_id: int | None = None,
    input_json: dict | None = None,
) -> JobRunModel:
    """创建一条任务记录。"""

    row = JobRunModel(
        job_type=job_type,
        status="pending",
        scope=scope,
        target_id=target_id,
        input_json=input_json or {},
        output_json={},
        error_message="",
    )
    db.add(row)
    db.commit()
    db.refresh(row)
    return row


def mark_job_running(db: Session, job_id: int) -> JobRunModel:
    """把任务切到运行中。"""

    row = db.get(JobRunModel, job_id)
    if row is None:
        raise ValueError("任务不存在")
    row.status = "running"
    row.started_at = datetime.utcnow()
    db.commit()
    db.refresh(row)
    return row


def mark_job_succeeded(db: Session, job_id: int, output_json: dict | None = None) -> JobRunModel:
    """把任务标记为成功。"""

    row = db.get(JobRunModel, job_id)
    if row is None:
        raise ValueError("任务不存在")
    row.status = "succeeded"
    row.output_json = output_json or {}
    row.error_message = ""
    if row.started_at is None:
        row.started_at = datetime.utcnow()
    row.finished_at = datetime.utcnow()
    db.commit()
    db.refresh(row)
    return row


def mark_job_failed(db: Session, job_id: int, error_message: str) -> JobRunModel:
    """把任务标记为失败。"""

    row = db.get(JobRunModel, job_id)
    if row is None:
        raise ValueError("任务不存在")
    row.status = "failed"
    row.error_message = error_message
    if row.started_at is None:
        row.started_at = datetime.utcnow()
    row.finished_at = datetime.utcnow()
    db.commit()
    db.refresh(row)
    return row


def mark_job_cancelled(db: Session, job_id: int) -> None:
    """Only an unfinished request may become cancelled."""
    from sqlalchemy import update
    db.execute(update(JobRunModel).where(JobRunModel.id == job_id,
        JobRunModel.status.in_(("pending", "running", "pause_requested"))).values(
            status="cancelled", finished_at=datetime.utcnow(), error_message="用户已停止生成"))
    db.commit()


def list_job_runs(
    db: Session,
    *,
    scope: str | None = None,
    target_id: int | None = None,
    limit: int = 50,
) -> list[dict]:
    """按范围查询最近任务。"""

    # Preserve the legacy list shape without transferring stored world bodies.
    columns = [column for column in JobRunModel.__table__.columns if column.name not in ("output_json", "input_json")]
    stmt = select(*columns, type_coerce(func.json_remove(JobRunModel.output_json, "$.world_result", "$.world_steps"), JSON).label("output_json"), type_coerce(func.json_remove(JobRunModel.input_json, "$.world_request"), JSON).label("input_json")).order_by(JobRunModel.id.desc()).limit(min(max(limit, 1), 200))
    if scope:
        stmt = stmt.where(JobRunModel.scope == scope)
    if target_id is not None:
        stmt = stmt.where(JobRunModel.target_id == target_id)
    return [dict(row) for row in db.execute(stmt).mappings()]

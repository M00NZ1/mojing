from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from ..database import get_db
from ..schemas import JobRunRead
from ..services.job_service import list_job_runs



router = APIRouter(prefix="/jobs", tags=["任务"])


@router.get("", summary="获取任务列表", response_model=list[JobRunRead])
def list_jobs(
    scope: str | None = None,
    target_id: int | None = None,
    limit: int = 50,
    db: Session = Depends(get_db)):
    return list_job_runs(db, scope=scope, target_id=target_id, limit=limit)

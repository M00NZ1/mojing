from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..database import get_db
from ..schemas import JobRunRead, WorldGenerationResponse
from ..services.job_service import list_job_runs
from ..services.world_job_service import world_job_history, read_world_job_result, save_world_job_result



router = APIRouter(prefix="/jobs", tags=["任务"])


@router.get("", summary="获取任务列表", response_model=list[JobRunRead])
def list_jobs(
    scope: str | None = None,
    target_id: int | None = None,
    limit: int = 50,
    db: Session = Depends(get_db)):
    return list_job_runs(db, scope=scope, target_id=target_id, limit=limit)


@router.get("/world-history", summary="分页获取世界生成记录摘要")
def list_world_history(before_id: int | None = None, limit: int = 20, db: Session = Depends(get_db)):
    return world_job_history(db, before_id=before_id, limit=limit)


@router.get("/{job_id}/world-result", response_model=WorldGenerationResponse)
def get_world_result(job_id: int, db: Session = Depends(get_db)):
    try:
        return read_world_job_result(db, job_id)[1]
    except LookupError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc


@router.post("/{job_id}/save-world", response_model=WorldGenerationResponse)
def save_world_result(job_id: int, db: Session = Depends(get_db)):
    try:
        return save_world_job_result(db, job_id)
    except LookupError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc

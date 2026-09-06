from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field, ValidationError
from typing import Literal
from sqlalchemy.orm import Session

from ..database import get_db
from ..schemas import JobRunRead, WorldGenerationResponse
from ..services.job_service import list_job_runs
from ..services.world_job_service import world_job_history, read_world_job_result, save_world_job_result



router = APIRouter(prefix="/jobs", tags=["任务"])


class WorldJobCreate(BaseModel):
    operation: Literal['generate', 'import']
    request: dict = Field(default_factory=dict)


@router.post('/world-request', status_code=201)
def create_world_request(payload: WorldJobCreate, db: Session = Depends(get_db)):
    from ..services.world_checkpoint_service import prepare_world_job
    try:
        return prepare_world_job(db, payload.operation, payload.request)
    except (ValueError, ValidationError) as exc:
        raise HTTPException(400, '生成输入格式无效，请检查后重试') from exc


@router.get('/{job_id}/world-progress')
def get_world_progress(job_id: int, db: Session = Depends(get_db)):
    from ..services.world_checkpoint_service import world_job_progress
    try:
        return world_job_progress(db, job_id)
    except LookupError as exc:
        raise HTTPException(404, str(exc)) from exc


@router.post('/{job_id}/pause-world')
def pause_world_request(job_id: int, db: Session = Depends(get_db)):
    from ..services.world_checkpoint_service import pause_world_job
    try:
        return pause_world_job(db, job_id)
    except LookupError as exc:
        raise HTTPException(404, str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(409, str(exc)) from exc


@router.post('/{job_id}/run-world')
async def run_world_job(job_id: int, request: Request, db: Session = Depends(get_db)):
    from ..services.world_checkpoint_service import run_checkpoint_world_job, WorldAlreadyRunning
    try:
        return await run_checkpoint_world_job(db, job_id, request.is_disconnected)
    except LookupError as exc:
        raise HTTPException(404, str(exc)) from exc
    except WorldAlreadyRunning as exc:
        raise HTTPException(409, str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(409, '无法继续此记录，请检查输入、角色和检查点版本；原记录保留。') from exc
    except Exception as exc:
        raise HTTPException(502, '执行未完成，已保存步骤保留；请检查模型配置后继续。') from exc


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

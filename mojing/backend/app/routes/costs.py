"""
LLM 成本统计 API。
"""
from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from ..database import get_db
from ..services.cost_service import get_cost_models, get_cost_providers, get_cost_records, get_cost_summary

router = APIRouter(prefix="/costs", tags=["成本统计"])


@router.get("", summary="获取成本统计")
def cost_summary(days: int = Query(30, ge=1, le=365), session_id: int | None = Query(None), db: Session = Depends(get_db)):
    return get_cost_summary(db, session_id=session_id, days=days)


@router.get("/providers", summary="按平台汇总成本")
def cost_providers(
    days: int = Query(30, ge=1, le=365),
    session_id: int | None = Query(None),
    status: str = Query("all", pattern="^(all|success|failed)$"),
    db: Session = Depends(get_db),
):
    return get_cost_providers(db, days=days, session_id=session_id, status=status)


@router.get("/providers/{provider}/models", summary="按模型汇总平台成本")
def cost_models(
    provider: str,
    days: int = Query(30, ge=1, le=365),
    session_id: int | None = Query(None),
    status: str = Query("all", pattern="^(all|success|failed)$"),
    db: Session = Depends(get_db),
):
    return get_cost_models(db, provider=provider, days=days, session_id=session_id, status=status)


@router.get("/providers/{provider}/records", summary="查看模型调用记录")
def cost_records(
    provider: str,
    model: str = Query(..., max_length=120),
    days: int = Query(30, ge=1, le=365),
    session_id: int | None = Query(None),
    status: str = Query("all", pattern="^(all|success|failed)$"),
    limit: int = Query(50, ge=1, le=200),
    before_id: int | None = Query(None, ge=1),
    db: Session = Depends(get_db),
):
    return get_cost_records(db, provider=provider, model_name=model, days=days, session_id=session_id,
                            status=status, limit=limit, before_id=before_id)

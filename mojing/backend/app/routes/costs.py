"""
LLM 成本统计 API。
"""
from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from ..database import get_db
from ..services.cost_service import get_cost_summary

router = APIRouter(prefix="/costs", tags=["成本统计"])


@router.get("", summary="获取成本统计")
def cost_summary(days: int = Query(30, ge=1, le=365), session_id: int | None = Query(None), db: Session = Depends(get_db)):
    return get_cost_summary(db, session_id=session_id, days=days)

"""
LLM 成本统计 API。
"""
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field, field_validator
from sqlalchemy.orm import Session

from ..database import get_db
from ..services.cost_service import (get_cost_for_message, get_cost_models, get_cost_providers,
                                     get_cost_records, get_cost_summary, save_model_price)
from ..models import LlmCostRecordModel
from sqlalchemy import select

router = APIRouter(prefix="/costs", tags=["成本统计"])


class ModelPricePayload(BaseModel):
    platform_id: str = Field(..., min_length=1, max_length=120)
    model_name: str = Field(..., min_length=1, max_length=120)
    currency: str = Field(..., min_length=3, max_length=3)
    input_per_million: float = Field(..., ge=0)
    output_per_million: float = Field(..., ge=0)
    cached_input_per_million: float = Field(..., ge=0)
    sync_history: bool = False

    @field_validator("currency")
    @classmethod
    def validate_currency(cls, value: str) -> str:
        value = value.upper()
        if value not in {"USD", "CNY"}:
            raise ValueError("currency must be USD or CNY")
        return value


@router.get("", summary="获取成本统计")
def cost_summary(days: int = Query(30, ge=1, le=365), session_id: int | None = Query(None), db: Session = Depends(get_db)):
    return get_cost_summary(db, session_id=session_id, days=days)


@router.get("/prices", summary="获取模型价格")
def cost_prices(platform_id: str = Query(..., min_length=1, max_length=120), db: Session = Depends(get_db)):
    from ..models import ModelPriceModel
    from sqlalchemy import select
    rows = db.scalars(select(ModelPriceModel).where(ModelPriceModel.platform_id == platform_id)
                     .order_by(ModelPriceModel.model_name)).all()
    return {"platform_id": platform_id, "items": [
        {"platform_id": item.platform_id, "model_name": item.model_name, "currency": item.currency,
         "input_per_million": item.input_per_million, "output_per_million": item.output_per_million,
         "cached_input_per_million": item.cached_input_per_million}
        for item in rows
    ]}


@router.put("/prices", summary="保存模型价格")
def update_cost_price(payload: ModelPricePayload, db: Session = Depends(get_db)):
    try:
        item, recalculated_count = save_model_price(db, **payload.model_dump())
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return {**item, "recalculated_count": recalculated_count}


@router.get("/messages/{message_id}", summary="获取消息成本")
def cost_for_message(message_id: str, db: Session = Depends(get_db)):
    item = get_cost_for_message(db, message_id)
    if item is None:
        raise HTTPException(status_code=404, detail="cost record not found")
    return item


@router.get("/messages", summary="批量获取消息成本")
def costs_for_messages(ids: str = Query(..., min_length=1), db: Session = Depends(get_db)):
    message_ids = list(dict.fromkeys(part.strip() for part in ids.split(",") if part.strip()))
    if not message_ids or len(message_ids) > 100:
        raise HTTPException(status_code=422, detail="ids must contain 1 to 100 unique message IDs")
    if any(len(item) > 128 for item in message_ids):
        raise HTTPException(status_code=422, detail="message ID is too long")
    records = db.scalars(select(LlmCostRecordModel).where(LlmCostRecordModel.message_id.in_(message_ids))).all()
    return {"items": {record.message_id: {
        "message_id": record.message_id,
        "platform_id": record.platform_id,
        "platform_name": record.platform_name,
        "model_name": record.model_name,
        "prompt_tokens": record.prompt_tokens,
        "completion_tokens": record.completion_tokens,
        "total_tokens": record.total_tokens,
        "estimated_cost": record.estimated_cost if record.cost_known else None,
        "currency": record.currency,
        "cost_known": bool(record.cost_known),
        "usage_source": record.usage_source,
        "duration_ms": record.duration_ms,
    } for record in records}}


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

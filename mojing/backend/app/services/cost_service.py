"""
LLM 调用成本统计服务。
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal

from sqlalchemy import case, func, select
from sqlalchemy.orm import Session

from ..models import LlmCostRecordModel

# 各模型每千 Token 的价格（美元），来源：各厂商公开定价
MODEL_PRICES: dict[str, dict[str, float]] = {
    "deepseek-chat": {"prompt": 0.00014, "completion": 0.00028},
    "deepseek-reasoner": {"prompt": 0.00055, "completion": 0.00110},
    "gpt-4o": {"prompt": 0.00250, "completion": 0.01000},
    "gpt-4o-mini": {"prompt": 0.00015, "completion": 0.00060},
    "claude-3-opus": {"prompt": 0.01500, "completion": 0.07500},
    "claude-3-sonnet": {"prompt": 0.00300, "completion": 0.01500},
    "claude-3-haiku": {"prompt": 0.00025, "completion": 0.00125},
    "gemini-pro": {"prompt": 0.000125, "completion": 0.000375},
}


def record_llm_call(
    db: Session,
    *,
    session_id: int | None = None,
    character_id: int | None = None,
    model_name: str = "",
    provider: str = "openai",
    prompt_tokens: int = 0,
    completion_tokens: int = 0,
    duration_ms: int = 0,
    success: bool = True,
) -> LlmCostRecordModel:
    """记录一次 LLM 调用及估算成本。"""
    prices = MODEL_PRICES.get(model_name, {"prompt": 0.00015, "completion": 0.00060})
    cost = (prompt_tokens / 1000 * prices["prompt"]) + (completion_tokens / 1000 * prices["completion"])

    record = LlmCostRecordModel(
        session_id=session_id,
        character_id=character_id,
        model_name=model_name,
        provider=provider,
        prompt_tokens=prompt_tokens,
        completion_tokens=completion_tokens,
        total_tokens=prompt_tokens + completion_tokens,
        estimated_cost=round(cost, 8),
        duration_ms=duration_ms,
        success=success,
    )
    db.add(record)
    db.commit()
    return record


def get_cost_summary(db: Session, session_id: int | None = None, days: int = 30) -> dict:
    """获取成本汇总。"""
    filters = _record_filters(session_id=session_id, days=days)
    records = db.scalars(
        select(LlmCostRecordModel)
        .where(*filters)
        .order_by(LlmCostRecordModel.created_at.desc(), LlmCostRecordModel.id.desc())
        .limit(200)
    ).all()

    # The old response is kept intact, but its totals now use SQL aggregation so a
    # long local history does not have to be loaded into Python just to count it.
    totals = _aggregate(db, filters, successful_only=True)

    total_cost = totals["cost_usd"]
    total_tokens = totals["total_tokens"]
    total_calls = totals["success_calls"]
    failed_calls = totals["failed_calls"]

    # 按模型分组
    by_model: dict[str, dict] = {}
    model_rows = db.execute(
        select(
            func.coalesce(LlmCostRecordModel.model_name, "").label("model_name"),
            func.sum(case((LlmCostRecordModel.success == 1, 1), else_=0)).label("calls"),
            func.coalesce(func.sum(case((LlmCostRecordModel.success == 1, LlmCostRecordModel.total_tokens), else_=0)), 0).label("tokens"),
            func.coalesce(func.sum(case((LlmCostRecordModel.success == 1, LlmCostRecordModel.estimated_cost), else_=0.0)), 0.0).label("cost"),
        ).where(*filters).group_by(LlmCostRecordModel.model_name)
    )
    for row in model_rows:
        by_model[row.model_name or "unknown"] = {
            "calls": int(row.calls or 0),
            "tokens": int(row.tokens or 0),
            "cost": float(row.cost or 0.0),
        }

    return {
        "total_cost_usd": round(total_cost, 6),
        "total_tokens": total_tokens,
        "total_calls": total_calls,
        "failed_calls": failed_calls,
        "by_model": by_model,
        "records": [
            {
                "id": r.id,
                "model_name": r.model_name,
                "provider": r.provider,
                "prompt_tokens": r.prompt_tokens,
                "completion_tokens": r.completion_tokens,
                "total_tokens": r.total_tokens,
                "estimated_cost": r.estimated_cost,
                "duration_ms": r.duration_ms,
                "success": bool(r.success),
                "created_at": r.created_at.isoformat() if r.created_at else "",
            }
            for r in records[:200]
        ],
    }


def _record_filters(*, session_id: int | None = None, days: int = 30, provider: str | None = None,
                    model_name: str | None = None, status: str = "all") -> list:
    """Build shared filters for all cost drill-down queries."""
    filters = []
    if session_id is not None:
        filters.append(LlmCostRecordModel.session_id == session_id)
    if days:
        since = datetime.now(timezone.utc) - timedelta(days=days)
        filters.append(LlmCostRecordModel.created_at >= since)
    if provider is not None:
        filters.append(LlmCostRecordModel.provider == provider)
    if model_name is not None:
        filters.append(LlmCostRecordModel.model_name == model_name)
    if status == "success":
        filters.append(LlmCostRecordModel.success == 1)
    elif status == "failed":
        filters.append(LlmCostRecordModel.success == 0)
    return filters


def _aggregate(db: Session, filters: list, *, successful_only: bool = False) -> dict:
    cost_expr = case((LlmCostRecordModel.success == 1, LlmCostRecordModel.estimated_cost), else_=0.0) if successful_only else LlmCostRecordModel.estimated_cost
    token_expr = case((LlmCostRecordModel.success == 1, LlmCostRecordModel.total_tokens), else_=0) if successful_only else LlmCostRecordModel.total_tokens
    row = db.execute(select(
        func.coalesce(func.sum(cost_expr), 0.0).label("cost_usd"),
        func.coalesce(func.sum(token_expr), 0).label("total_tokens"),
        func.coalesce(func.sum(case((LlmCostRecordModel.success == 1, 1), else_=0)), 0).label("success_calls"),
        func.coalesce(func.sum(case((LlmCostRecordModel.success == 0, 1), else_=0)), 0).label("failed_calls"),
        func.coalesce(func.sum(LlmCostRecordModel.duration_ms), 0).label("duration_ms"),
    ).where(*filters)).one()
    success_calls = int(row.success_calls or 0)
    failed_calls = int(row.failed_calls or 0)
    return {"cost_usd": float(row.cost_usd or 0.0), "total_tokens": int(row.total_tokens or 0),
            "total_calls": success_calls + failed_calls, "success_calls": success_calls, "failed_calls": failed_calls,
            "duration_ms": int(row.duration_ms or 0)}


def _period(days: int) -> dict:
    now = datetime.now(timezone.utc)
    return {"days": days, "since": (now - timedelta(days=days)).isoformat(), "until": now.isoformat()}


def _aggregate_item(row, *, provider: str | None = None, model_name: str | None = None) -> dict:
    item = {"calls": int(row.calls or 0), "success_calls": int(row.success_calls or 0),
            "failed_calls": int(row.failed_calls or 0), "prompt_tokens": int(row.prompt_tokens or 0),
            "completion_tokens": int(row.completion_tokens or 0), "total_tokens": int(row.total_tokens or 0),
            "cost_usd": float(row.cost_usd or 0.0), "duration_ms": int(row.duration_ms or 0)}
    if provider is None:
        item["provider"] = row.provider
        item["models_count"] = int(row.models_count or 0)
    elif model_name is None:
        item["provider"] = provider
        item["model_name"] = row.model_name
    return item


def get_cost_providers(db: Session, *, days: int = 30, session_id: int | None = None, status: str = "all") -> dict:
    filters = _record_filters(session_id=session_id, days=days, status=status)
    totals = _aggregate(db, filters)
    rows = db.execute(select(
        LlmCostRecordModel.provider.label("provider"),
        func.count(LlmCostRecordModel.id).label("calls"),
        func.sum(case((LlmCostRecordModel.success == 1, 1), else_=0)).label("success_calls"),
        func.sum(case((LlmCostRecordModel.success == 0, 1), else_=0)).label("failed_calls"),
        func.sum(LlmCostRecordModel.prompt_tokens).label("prompt_tokens"),
        func.sum(LlmCostRecordModel.completion_tokens).label("completion_tokens"),
        func.sum(LlmCostRecordModel.total_tokens).label("total_tokens"),
        func.sum(LlmCostRecordModel.estimated_cost).label("cost_usd"),
        func.sum(LlmCostRecordModel.duration_ms).label("duration_ms"),
        func.count(func.distinct(LlmCostRecordModel.model_name)).label("models_count"),
    ).where(*filters).group_by(LlmCostRecordModel.provider).order_by(LlmCostRecordModel.provider)).all()
    return {"period": _period(days), "totals": totals, "items": [_aggregate_item(row) for row in rows]}


def get_cost_models(db: Session, *, provider: str, days: int = 30, session_id: int | None = None, status: str = "all") -> dict:
    filters = _record_filters(session_id=session_id, days=days, provider=provider, status=status)
    totals = _aggregate(db, filters)
    rows = db.execute(select(
        LlmCostRecordModel.model_name.label("model_name"),
        func.count(LlmCostRecordModel.id).label("calls"),
        func.sum(case((LlmCostRecordModel.success == 1, 1), else_=0)).label("success_calls"),
        func.sum(case((LlmCostRecordModel.success == 0, 1), else_=0)).label("failed_calls"),
        func.sum(LlmCostRecordModel.prompt_tokens).label("prompt_tokens"),
        func.sum(LlmCostRecordModel.completion_tokens).label("completion_tokens"),
        func.sum(LlmCostRecordModel.total_tokens).label("total_tokens"),
        func.sum(LlmCostRecordModel.estimated_cost).label("cost_usd"),
        func.sum(LlmCostRecordModel.duration_ms).label("duration_ms"),
    ).where(*filters).group_by(LlmCostRecordModel.model_name).order_by(LlmCostRecordModel.model_name)).all()
    return {"provider": provider, "period": _period(days), "totals": totals,
            "items": [_aggregate_item(row, provider=provider) for row in rows]}


def get_cost_records(db: Session, *, provider: str, model_name: str, days: int = 30,
                     session_id: int | None = None, status: str = "all", limit: int = 50,
                     before_id: int | None = None) -> dict:
    filters = _record_filters(session_id=session_id, days=days, provider=provider, model_name=model_name, status=status)
    if before_id is not None:
        filters.append(LlmCostRecordModel.id < before_id)
    records = db.scalars(select(LlmCostRecordModel).where(*filters)
        .order_by(LlmCostRecordModel.id.desc()).limit(limit + 1)).all()
    has_more = len(records) > limit
    records = records[:limit]
    return {"provider": provider, "model_name": model_name, "period": _period(days),
            "items": [_record_to_dict(r) for r in records],
            "next_cursor": records[-1].id if has_more and records else None}


def _record_to_dict(r: LlmCostRecordModel) -> dict:
    return {"id": r.id, "session_id": r.session_id, "character_id": r.character_id,
            "model_name": r.model_name, "provider": r.provider, "prompt_tokens": r.prompt_tokens,
            "completion_tokens": r.completion_tokens, "total_tokens": r.total_tokens,
            "estimated_cost": r.estimated_cost, "duration_ms": r.duration_ms, "success": bool(r.success),
            "created_at": r.created_at.isoformat() if r.created_at else ""}

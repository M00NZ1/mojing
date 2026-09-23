"""
LLM 调用成本统计服务。
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal
import math

from sqlalchemy import and_, case, func, select, update
from sqlalchemy.orm import Session

from ..models import LlmCostRecordModel, ModelPriceModel

SUPPORTED_CURRENCIES = {"USD", "CNY"}


def _price_dict(price: ModelPriceModel) -> dict:
    return {
        "platform_id": price.platform_id,
        "model_name": price.model_name,
        "currency": price.currency,
        "input_per_million": float(price.input_per_million),
        "output_per_million": float(price.output_per_million),
        "cached_input_per_million": float(price.cached_input_per_million),
    }


def get_price_snapshot(db: Session, platform_id: str, model_name: str) -> dict | None:
    price = db.scalar(select(ModelPriceModel).where(
        ModelPriceModel.platform_id == platform_id,
        ModelPriceModel.model_name == model_name,
    ))
    return _price_dict(price) if price else None


def _cost_for_usage(prompt_tokens: int, completion_tokens: int, snapshot: dict) -> float:
    return round(
        (prompt_tokens * float(snapshot.get("input_per_million", 0.0))
         + completion_tokens * float(snapshot.get("output_per_million", 0.0))) / 1_000_000,
        8,
    )


def record_llm_call(
    db: Session,
    *,
    session_id: int | None = None,
    character_id: int | None = None,
    model_name: str = "",
    provider: str = "openai",
    platform_id: str | None = None,
    platform_name: str = "",
    message_id: str | None = None,
    price_snapshot: dict | None = None,
    usage_source: str = "estimated",
    prompt_tokens: int = 0,
    completion_tokens: int = 0,
    duration_ms: int = 0,
    success: bool = True,
) -> LlmCostRecordModel:
    """记录一次 LLM 调用；没有明确价格时保留金额并标记为未知。"""
    snapshot = price_snapshot
    if snapshot is None and platform_id:
        snapshot = get_price_snapshot(db, platform_id, model_name)
    currency = str(snapshot.get("currency", "USD")).upper() if snapshot else "USD"
    if currency not in SUPPORTED_CURRENCIES:
        currency = "USD"
        snapshot = None
    cost = _cost_for_usage(prompt_tokens, completion_tokens, snapshot) if snapshot else 0.0

    record = LlmCostRecordModel(
        session_id=session_id,
        character_id=character_id,
        model_name=model_name,
        provider=provider,
        platform_id=platform_id,
        platform_name=platform_name,
        currency=currency,
        cost_known=bool(snapshot),
        pricing_snapshot_json=dict(snapshot) if snapshot else None,
        message_id=message_id,
        usage_source=usage_source,
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
            func.coalesce(func.sum(case((LlmCostRecordModel.cost_known == 1, case((LlmCostRecordModel.currency == "USD", LlmCostRecordModel.estimated_cost), else_=0.0)), else_=0.0)), 0.0).label("cost"),
            func.coalesce(func.sum(case((LlmCostRecordModel.cost_known == 1, case((LlmCostRecordModel.currency == "CNY", LlmCostRecordModel.estimated_cost), else_=0.0)), else_=0.0)), 0.0).label("cost_cny"),
            func.coalesce(func.sum(case((LlmCostRecordModel.cost_known == 0, 1), else_=0)), 0).label("unknown_calls"),
        ).where(*filters).group_by(LlmCostRecordModel.model_name)
    )
    for row in model_rows:
        by_model[row.model_name or "unknown"] = {
            "calls": int(row.calls or 0),
            "tokens": int(row.tokens or 0),
            "cost": float(row.cost or 0.0),
            "currency_totals": {"USD": float(row.cost or 0.0), "CNY": float(row.cost_cny or 0.0)},
            "unknown_calls": int(row.unknown_calls or 0),
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
                "estimated_cost": r.estimated_cost if r.cost_known else None,
                "currency": r.currency,
                "cost_known": bool(r.cost_known),
                "platform_id": r.platform_id,
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
        if provider.startswith("platform:"):
            filters.append(LlmCostRecordModel.platform_id == provider.removeprefix("platform:"))
        else:
            filters.extend((LlmCostRecordModel.platform_id.is_(None), LlmCostRecordModel.provider == provider))
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
    cost_filters = [*filters, LlmCostRecordModel.success == 1] if successful_only else filters
    currency_rows = db.execute(select(
        LlmCostRecordModel.currency.label("currency"),
        func.sum(LlmCostRecordModel.estimated_cost).label("cost"),
    ).where(*cost_filters, LlmCostRecordModel.cost_known == 1).group_by(LlmCostRecordModel.currency)).all()
    by_currency = {str(item.currency or "USD"): float(item.cost or 0.0) for item in currency_rows}
    unknown_calls = db.scalar(select(func.count(LlmCostRecordModel.id)).where(*cost_filters, LlmCostRecordModel.cost_known == 0)) or 0
    return {"cost_usd": by_currency.get("USD", 0.0), "currency_totals": by_currency, "cost_by_currency": by_currency,
            "unknown_calls": int(unknown_calls), "total_tokens": int(row.total_tokens or 0),
            "total_calls": success_calls + failed_calls, "success_calls": success_calls, "failed_calls": failed_calls,
            "duration_ms": int(row.duration_ms or 0)}


def _period(days: int) -> dict:
    now = datetime.now(timezone.utc)
    return {"days": days, "since": (now - timedelta(days=days)).isoformat(), "until": now.isoformat()}


def _aggregate_item(row, *, provider: str | None = None, model_name: str | None = None) -> dict:
    item = {"calls": int(row.calls or 0), "success_calls": int(row.success_calls or 0),
            "failed_calls": int(row.failed_calls or 0), "prompt_tokens": int(row.prompt_tokens or 0),
            "completion_tokens": int(row.completion_tokens or 0), "total_tokens": int(row.total_tokens or 0),
            "cost_usd": float(row.cost_usd or 0.0) if getattr(row, "known_usd_calls", 0) else None,
            "currency_totals": {
                currency: amount for currency, amount, count in (
                    ("USD", float(row.cost_usd or 0.0), getattr(row, "known_usd_calls", 0)),
                    ("CNY", float(getattr(row, "cost_cny", 0.0) or 0.0), getattr(row, "known_cny_calls", 0)),
                ) if count
            },
            "unknown_calls": int(getattr(row, "unknown_calls", 0) or 0),
            "duration_ms": int(row.duration_ms or 0)}
    if provider is None:
        item["provider"] = row.provider_key
        item["platform_name"] = getattr(row, "platform_name", None)
        item["models_count"] = int(row.models_count or 0)
    elif model_name is None:
        item["provider"] = provider
        item["platform_name"] = getattr(row, "platform_name", None)
        item["model_name"] = row.model_name
    return item


def get_cost_providers(db: Session, *, days: int = 30, session_id: int | None = None, status: str = "all") -> dict:
    filters = _record_filters(session_id=session_id, days=days, status=status)
    totals = _aggregate(db, filters)
    rows = db.execute(select(
        case((LlmCostRecordModel.platform_id.is_not(None), "platform:" + LlmCostRecordModel.platform_id),
             else_=LlmCostRecordModel.provider).label("provider_key"),
        func.max(LlmCostRecordModel.platform_name).label("platform_name"),
        func.count(LlmCostRecordModel.id).label("calls"),
        func.sum(case((LlmCostRecordModel.success == 1, 1), else_=0)).label("success_calls"),
        func.sum(case((LlmCostRecordModel.success == 0, 1), else_=0)).label("failed_calls"),
        func.sum(LlmCostRecordModel.prompt_tokens).label("prompt_tokens"),
        func.sum(LlmCostRecordModel.completion_tokens).label("completion_tokens"),
        func.sum(LlmCostRecordModel.total_tokens).label("total_tokens"),
        func.sum(case((LlmCostRecordModel.cost_known == 1, case((LlmCostRecordModel.currency == "USD", LlmCostRecordModel.estimated_cost), else_=0.0)), else_=0.0)).label("cost_usd"),
        func.sum(case((LlmCostRecordModel.cost_known == 1, case((LlmCostRecordModel.currency == "CNY", LlmCostRecordModel.estimated_cost), else_=0.0)), else_=0.0)).label("cost_cny"),
        func.sum(case((and_(LlmCostRecordModel.cost_known == 1, LlmCostRecordModel.currency == "USD"), 1), else_=0)).label("known_usd_calls"),
        func.sum(case((and_(LlmCostRecordModel.cost_known == 1, LlmCostRecordModel.currency == "CNY"), 1), else_=0)).label("known_cny_calls"),
        func.sum(case((LlmCostRecordModel.cost_known == 0, 1), else_=0)).label("unknown_calls"),
        func.sum(LlmCostRecordModel.duration_ms).label("duration_ms"),
        func.count(func.distinct(LlmCostRecordModel.model_name)).label("models_count"),
    ).where(*filters).group_by(
        case((LlmCostRecordModel.platform_id.is_not(None), "platform:" + LlmCostRecordModel.platform_id),
             else_=LlmCostRecordModel.provider)
    ).order_by("provider_key")).all()
    return {"period": _period(days), "totals": totals, "items": [_aggregate_item(row) for row in rows]}


def get_cost_models(db: Session, *, provider: str, days: int = 30, session_id: int | None = None, status: str = "all") -> dict:
    filters = _record_filters(session_id=session_id, days=days, provider=provider, status=status)
    totals = _aggregate(db, filters)
    rows = db.execute(select(
        LlmCostRecordModel.model_name.label("model_name"),
        func.max(LlmCostRecordModel.platform_name).label("platform_name"),
        func.count(LlmCostRecordModel.id).label("calls"),
        func.sum(case((LlmCostRecordModel.success == 1, 1), else_=0)).label("success_calls"),
        func.sum(case((LlmCostRecordModel.success == 0, 1), else_=0)).label("failed_calls"),
        func.sum(LlmCostRecordModel.prompt_tokens).label("prompt_tokens"),
        func.sum(LlmCostRecordModel.completion_tokens).label("completion_tokens"),
        func.sum(LlmCostRecordModel.total_tokens).label("total_tokens"),
        func.sum(case((LlmCostRecordModel.cost_known == 1, case((LlmCostRecordModel.currency == "USD", LlmCostRecordModel.estimated_cost), else_=0.0)), else_=0.0)).label("cost_usd"),
        func.sum(case((LlmCostRecordModel.cost_known == 1, case((LlmCostRecordModel.currency == "CNY", LlmCostRecordModel.estimated_cost), else_=0.0)), else_=0.0)).label("cost_cny"),
        func.sum(case((and_(LlmCostRecordModel.cost_known == 1, LlmCostRecordModel.currency == "USD"), 1), else_=0)).label("known_usd_calls"),
        func.sum(case((and_(LlmCostRecordModel.cost_known == 1, LlmCostRecordModel.currency == "CNY"), 1), else_=0)).label("known_cny_calls"),
        func.sum(case((LlmCostRecordModel.cost_known == 0, 1), else_=0)).label("unknown_calls"),
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
            "estimated_cost": r.estimated_cost if r.cost_known else None, "currency": r.currency, "cost_known": bool(r.cost_known),
            "platform_id": r.platform_id, "platform_name": r.platform_name,
            "message_id": r.message_id, "usage_source": r.usage_source,
            "duration_ms": r.duration_ms, "success": bool(r.success),
            "created_at": r.created_at.isoformat() if r.created_at else ""}


def save_model_price(
    db: Session,
    *,
    platform_id: str,
    model_name: str,
    currency: str,
    input_per_million: float,
    output_per_million: float,
    cached_input_per_million: float,
    sync_history: bool,
) -> tuple[dict, int]:
    """保存精确平台/模型价格，并按契约选择性回算历史。"""
    currency = currency.upper()
    if currency not in SUPPORTED_CURRENCIES:
        raise ValueError("currency must be USD or CNY")
    from .model_platform_service import get_catalog
    platform = next((item for item in get_catalog(db)["platforms"] if item.get("id") == platform_id), None)
    if platform is None or model_name not in platform.get("models", []):
        raise ValueError("platform/model is not present in the saved catalog")
    if not all(math.isfinite(float(value)) and float(value) >= 0 for value in (
        input_per_million, output_per_million, cached_input_per_million,
    )):
        raise ValueError("prices must be finite and nonnegative")
    values = {
        "currency": currency,
        "input_per_million": input_per_million,
        "output_per_million": output_per_million,
        "cached_input_per_million": cached_input_per_million,
    }
    price = db.scalar(select(ModelPriceModel).where(
        ModelPriceModel.platform_id == platform_id,
        ModelPriceModel.model_name == model_name,
    ))
    first_save = price is None
    if price is None:
        price = ModelPriceModel(platform_id=platform_id, model_name=model_name, **values)
        db.add(price)
    else:
        for key, value in values.items():
            setattr(price, key, value)
    db.flush()

    snapshot = _price_dict(price)
    record_query = update(LlmCostRecordModel).where(
        LlmCostRecordModel.platform_id == platform_id,
        LlmCostRecordModel.model_name == model_name,
    )
    if first_save:
        record_query = record_query.where(LlmCostRecordModel.cost_known == 0)
    elif not sync_history:
        record_query = record_query.where(LlmCostRecordModel.id == -1)
    result = db.execute(record_query.values(
        currency=currency,
        cost_known=True,
        pricing_snapshot_json=dict(snapshot),
        estimated_cost=func.round((
            LlmCostRecordModel.prompt_tokens * input_per_million
            + LlmCostRecordModel.completion_tokens * output_per_million
        ) / 1_000_000, 8),
    ))
    db.commit()
    return snapshot, result.rowcount or 0


def get_cost_for_message(db: Session, message_id: str) -> dict | None:
    record = db.scalar(select(LlmCostRecordModel).where(LlmCostRecordModel.message_id == message_id)
                       .order_by(LlmCostRecordModel.id.desc()))
    if record is None:
        return None
    return {
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
    }

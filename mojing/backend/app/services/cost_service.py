"""
LLM 调用成本统计服务。
"""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal

from sqlalchemy import func, select
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
    query = select(LlmCostRecordModel)
    if session_id:
        query = query.where(LlmCostRecordModel.session_id == session_id)
    if days:
        since = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
        query = query.where(LlmCostRecordModel.created_at >= since)

    records = db.scalars(query.order_by(LlmCostRecordModel.created_at.desc())).all()

    total_cost = sum(r.estimated_cost for r in records if r.success)
    total_tokens = sum(r.total_tokens for r in records if r.success)
    total_calls = len([r for r in records if r.success])
    failed_calls = len([r for r in records if not r.success])

    # 按模型分组
    by_model: dict[str, dict] = {}
    for r in records:
        if r.success:
            m = r.model_name or "unknown"
            if m not in by_model:
                by_model[m] = {"calls": 0, "tokens": 0, "cost": 0.0}
            by_model[m]["calls"] += 1
            by_model[m]["tokens"] += r.total_tokens
            by_model[m]["cost"] += r.estimated_cost

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

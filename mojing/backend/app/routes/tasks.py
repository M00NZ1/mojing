"""
LLM 调用事件日志接口。
"""
from fastapi import APIRouter, Query

from ..services.llm_retry import get_llm_events, clear_llm_events

router = APIRouter(prefix="/tasks", tags=["异步任务"])


@router.get("/llm-events")
def list_llm_events(limit: int = Query(100, ge=1, le=500)):
    """查询最近的 LLM 调用事件（结构化日志）。"""
    return get_llm_events(limit)


@router.delete("/llm-events")
def delete_llm_events():
    """清空 LLM 调用事件日志。"""
    clear_llm_events()
    return {"ok": True}

"""
安全宏动作路由 — 白名单声明式动作的 API 端点。
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..database import get_db
from ..services.macro_actions import execute_action, list_actions

router = APIRouter(prefix="/actions", tags=["安全宏"])


@router.get("")
def get_actions():
    """列出所有可用的安全宏动作。"""
    return list_actions()


@router.post("/execute")
def run_action(payload: dict, db: Session = Depends(get_db)):
    """执行一个安全宏动作。"""
    name = payload.get("name", "").strip()
    if not name:
        raise HTTPException(status_code=400, detail="name 不能为空")
    params = payload.get("params", {})
    try:
        result = execute_action(name, params, db)
        return result
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"动作执行失败: {str(e)}")

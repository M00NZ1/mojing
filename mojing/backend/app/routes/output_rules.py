"""
输出后处理路由：管理正则替换规则。
"""
from fastapi import APIRouter, HTTPException

from ..services.output_postprocess import apply_rules, delete_rule, get_rules, save_rule

router = APIRouter(prefix="/output-rules", tags=["输出后处理"])


@router.get("")
def list_rules():
    return get_rules()


@router.post("")
def create_or_update_rule(payload: dict):
    if not payload.get("pattern"):
        raise HTTPException(status_code=400, detail="pattern 不能为空")
    return save_rule(payload)


@router.delete("/{rule_id}")
def remove_rule(rule_id: str):
    if not delete_rule(rule_id):
        raise HTTPException(status_code=404, detail="规则不存在")
    return {"ok": True}


@router.post("/preview")
def preview_rule(payload: dict):
    """测试一条规则的效果。"""
    text = payload.get("text", "")
    pattern = payload.get("pattern", "")
    replacement = payload.get("replacement", "")
    if not pattern:
        raise HTTPException(status_code=400, detail="pattern 不能为空")
    import re
    flags = 0
    if payload.get("ignore_case"):
        flags |= re.IGNORECASE
    try:
        result = re.sub(pattern, replacement, text, flags=flags)
    except re.error as e:
        raise HTTPException(status_code=400, detail=f"正则错误: {e}")
    return {"original": text, "result": result}

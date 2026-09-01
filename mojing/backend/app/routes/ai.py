"""
AI 能力层路由：模型调用、流式输出、工具调用、Embedding、语音/图片生成。
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from fastapi.responses import StreamingResponse

from ..database import get_db

router = APIRouter(prefix="/ai", tags=["AI 能力层"])


@router.get("/providers")
def list_ai_providers():
    """列出所有可用的 AI 提供商配置。"""
    from ..services.channel_service import list_channels
    return list_channels()


@router.post("/test-connection")
def test_ai_connection(payload: dict):
    """测试指定渠道的连通性。"""
    from ..services.llm_client import build_client_from_channel
    channel_id = payload.get("channel_id", "")
    if not channel_id:
        raise HTTPException(status_code=400, detail="channel_id 是必填项")
    try:
        client = build_client_from_channel(channel_id)
        return {"ok": True, "message": "连接成功"}
    except Exception as e:
        return {"ok": False, "message": str(e)}


@router.get("/embedding")
def text_embedding(text: str, model: str = "deepseek-chat"):
    """获取文本的 Embedding 向量。"""
    if not text.strip():
        raise HTTPException(status_code=400, detail="text 不能为空")
    from ..services.llm_client import get_embedding
    try:
        embedding = get_embedding(text, model)
        return {"ok": True, "embedding": embedding, "dimensions": len(embedding)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/models")
def list_available_models():
    """列出项目中所有可用的模型/渠道配置。"""
    from ..services.channel_service import list_channels, get_provider_presets, get_purpose_options
    return {
        "channels": list_channels(),
        "providers": get_provider_presets(),
        "purposes": get_purpose_options(),
    }


@router.post("/complete")
def ai_complete_endpoint(payload: dict, db: Session = Depends(get_db)):
    """AI 一键补全：百科条目/人物/世界模板。"""
    target_type = payload.get("target_type", "")
    if target_type not in ("encyclopedia_entry", "character", "world_template"):
        raise HTTPException(status_code=400, detail="不支持的补全类型")
    from ..services.ai_complete_service import ai_complete
    result = ai_complete(
        db=db,
        target_type=target_type,
        target_data=payload.get("target_data", {}),
        fields_to_complete=payload.get("fields_to_complete"),
        character_id=payload.get("character_id"),
        extra_context=payload.get("extra_context", ""),
    )
    return result

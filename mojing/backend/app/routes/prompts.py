from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import PromptTemplateModel, PromptTemplateRevisionModel
from ..schemas import PromptTemplateRead, PromptTemplateRevisionRead



router = APIRouter(prefix="/prompts", tags=["提示词库"])


@router.get("/templates", summary="获取模板列表", response_model=list[PromptTemplateRead])
def list_prompt_templates(db: Session = Depends(get_db)):
    return list(
        db.scalars(
            select(PromptTemplateModel).order_by(PromptTemplateModel.category.asc(), PromptTemplateModel.label.asc())
        )
    )


@router.get("/templates/{template_id}/revisions", response_model=list[PromptTemplateRevisionRead])
def list_prompt_template_revisions(template_id: str, db: Session = Depends(get_db)):
    template = db.scalar(select(PromptTemplateModel).where(PromptTemplateModel.template_id == template_id))
    if template is None:
        return []
    return list(
        db.scalars(
            select(PromptTemplateRevisionModel)
            .where(PromptTemplateRevisionModel.prompt_template_id == template.id)
            .order_by(PromptTemplateRevisionModel.version.desc(), PromptTemplateRevisionModel.created_at.desc())
        )
    )

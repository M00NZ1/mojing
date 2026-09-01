from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import PromptTemplateModel, PromptTemplateRevisionModel
from ..schemas import PromptTemplateCreate, PromptTemplateUpdate


def create_prompt_template(db: Session, payload: PromptTemplateCreate) -> PromptTemplateModel:
    """创建自定义提示词模板并写入首个修订版本。"""

    exists = db.scalar(select(PromptTemplateModel).where(PromptTemplateModel.template_id == payload.template_id.strip()))
    if exists is not None:
        raise ValueError("提示词模板 ID 已存在")

    row = PromptTemplateModel(
        template_id=payload.template_id.strip(),
        label=payload.label.strip(),
        category=payload.category.strip() or "通用",
        scope=payload.scope.strip() or "story",
        description=payload.description,
        system_prompt=payload.system_prompt,
        user_prompt=payload.user_prompt,
        variables_json=list(payload.variables_json or []),
        output_format=payload.output_format,
        version=1,
        is_builtin=False,
    )
    db.add(row)
    db.flush()
    db.add(
        PromptTemplateRevisionModel(
            prompt_template_id=row.id,
            version=1,
            system_prompt=row.system_prompt,
            user_prompt=row.user_prompt,
            variables_json=row.variables_json,
            output_format=row.output_format,
            change_note=payload.change_note or "新建模板",
        )
    )
    db.commit()
    db.refresh(row)
    return row


def update_prompt_template(db: Session, template_id: str, payload: PromptTemplateUpdate) -> PromptTemplateModel:
    """更新自定义提示词模板并写入新修订版本。"""

    row = db.scalar(select(PromptTemplateModel).where(PromptTemplateModel.template_id == template_id))
    if row is None:
        raise ValueError("提示词模板不存在")
    if row.is_builtin:
        raise ValueError("内置提示词模板不能直接修改，请复制后再编辑")

    next_version = int(row.version or 1) + 1
    row.label = payload.label.strip()
    row.category = payload.category.strip() or "通用"
    row.scope = payload.scope.strip() or "story"
    row.description = payload.description
    row.system_prompt = payload.system_prompt
    row.user_prompt = payload.user_prompt
    row.variables_json = list(payload.variables_json or [])
    row.output_format = payload.output_format
    row.version = next_version
    db.add(
        PromptTemplateRevisionModel(
            prompt_template_id=row.id,
            version=next_version,
            system_prompt=row.system_prompt,
            user_prompt=row.user_prompt,
            variables_json=row.variables_json,
            output_format=row.output_format,
            change_note=payload.change_note or f"更新到 v{next_version}",
        )
    )
    db.commit()
    db.refresh(row)
    return row


def delete_prompt_template(db: Session, template_id: str) -> None:
    """删除自定义提示词模板。"""

    row = db.scalar(select(PromptTemplateModel).where(PromptTemplateModel.template_id == template_id))
    if row is None:
        raise ValueError("提示词模板不存在")
    if row.is_builtin:
        raise ValueError("内置提示词模板不能删除")
    db.delete(row)
    db.commit()

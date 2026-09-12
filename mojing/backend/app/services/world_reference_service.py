from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..models import AppSettingModel, SessionWorldModel


def template_is_referenced(db: Session, template_id: str) -> bool:
    return (
        db.scalar(select(SessionWorldModel.id).where(SessionWorldModel.template_id == template_id).limit(1)) is not None
        or db.scalar(select(AppSettingModel.id).where(
            func.json_extract(AppSettingModel.value_json, "$.default_world_template_id") == template_id).limit(1)) is not None
    )


def encyclopedia_is_referenced(db: Session, encyclopedia_id: int) -> bool:
    return db.scalar(select(SessionWorldModel.id).where(
        SessionWorldModel.encyclopedia_id == encyclopedia_id).limit(1)) is not None

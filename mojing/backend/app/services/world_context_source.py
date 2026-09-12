from __future__ import annotations

from sqlalchemy.orm import Session

from .unified_world_service import mapped_world_id


def uses_canonical_world(db: Session, template_id: str | None, encyclopedia_id: int | None) -> bool:
    """Only suppress a legacy source when this session uses its mapped world."""
    return bool(
        template_id
        and template_id != "custom"
        and encyclopedia_id is not None
        and mapped_world_id(db, template_id) == encyclopedia_id
    )

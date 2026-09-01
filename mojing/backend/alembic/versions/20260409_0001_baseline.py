"""基线迁移

Revision ID: 20260409_0001
Revises:
Create Date: 2026-04-09 00:00:00
"""
from __future__ import annotations

from alembic import op

from backend.app import models  # noqa: F401
from backend.app.database import Base


revision = "20260409_0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    """首次迁移直接按当前元数据建表。"""

    bind = op.get_bind()
    Base.metadata.create_all(bind=bind)


def downgrade() -> None:
    """回滚时按反向依赖删除全部业务表。"""

    bind = op.get_bind()
    Base.metadata.drop_all(bind=bind)

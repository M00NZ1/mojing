"""人物：角色卡竖图路径（与 Android cardImagePath 对齐）

Revision ID: 20260513_0010
Revises: 20260513_0009
Create Date: 2026-05-13 12:00:00
"""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "20260513_0010"
down_revision = "20260513_0009"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    columns = {column["name"] for column in sa.inspect(bind).get_columns("characters")}
    if "card_image_path" not in columns:
        op.add_column(
            "characters",
            sa.Column("card_image_path", sa.String(length=500), nullable=False, server_default=""),
        )


def downgrade() -> None:
    bind = op.get_bind()
    columns = {column["name"] for column in sa.inspect(bind).get_columns("characters")}
    if "card_image_path" in columns:
        op.drop_column("characters", "card_image_path")

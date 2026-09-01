"""世界模板封面图

Revision ID: 20260409_0004
Revises: 20260409_0003
Create Date: 2026-04-09 03:20:00
"""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy import inspect


revision = "20260409_0004"
down_revision = "20260409_0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    inspector = inspect(bind)
    columns = {item["name"] for item in inspector.get_columns("world_templates")}
    if "cover_image_path" not in columns:
        op.add_column(
            "world_templates",
            sa.Column("cover_image_path", sa.String(length=500), nullable=False, server_default=""),
        )


def downgrade() -> None:
    bind = op.get_bind()
    inspector = inspect(bind)
    columns = {item["name"] for item in inspector.get_columns("world_templates")}
    if "cover_image_path" in columns:
        op.drop_column("world_templates", "cover_image_path")

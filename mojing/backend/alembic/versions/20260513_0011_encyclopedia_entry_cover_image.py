"""百科条目：列表/网格封面图路径

Revision ID: 20260513_0011
Revises: 20260513_0010
Create Date: 2026-05-13 14:00:00
"""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "20260513_0011"
down_revision = "20260513_0010"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    columns = {column["name"] for column in sa.inspect(bind).get_columns("encyclopedia_entries")}
    if "cover_image_path" not in columns:
        op.add_column(
            "encyclopedia_entries",
            sa.Column("cover_image_path", sa.String(length=500), nullable=False, server_default=""),
        )


def downgrade() -> None:
    bind = op.get_bind()
    columns = {column["name"] for column in sa.inspect(bind).get_columns("encyclopedia_entries")}
    if "cover_image_path" in columns:
        op.drop_column("encyclopedia_entries", "cover_image_path")

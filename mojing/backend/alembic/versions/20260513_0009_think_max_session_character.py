"""会话与角色：思考/Max 模式开关

Revision ID: 20260513_0009
Revises: 20260429_0008
Create Date: 2026-05-13 00:00:00
"""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "20260513_0009"
down_revision = "20260429_0008"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    insp = sa.inspect(bind)
    sess_cols = {c["name"] for c in insp.get_columns("chat_sessions")}
    char_cols = {c["name"] for c in insp.get_columns("characters")}
    if "think_max_enabled" not in sess_cols:
        op.add_column(
            "chat_sessions",
            sa.Column("think_max_enabled", sa.Boolean(), nullable=False, server_default=sa.text("0")),
        )
    if "think_max_enabled" not in char_cols:
        op.add_column(
            "characters",
            sa.Column("think_max_enabled", sa.Boolean(), nullable=False, server_default=sa.text("0")),
        )
    if "think_max_model_name" not in char_cols:
        op.add_column(
            "characters",
            sa.Column("think_max_model_name", sa.String(length=120), nullable=False, server_default=""),
        )


def downgrade() -> None:
    bind = op.get_bind()
    insp = sa.inspect(bind)
    char_cols = {c["name"] for c in insp.get_columns("characters")}
    sess_cols = {c["name"] for c in insp.get_columns("chat_sessions")}
    if "think_max_model_name" in char_cols:
        op.drop_column("characters", "think_max_model_name")
    if "think_max_enabled" in char_cols:
        op.drop_column("characters", "think_max_enabled")
    if "think_max_enabled" in sess_cols:
        op.drop_column("chat_sessions", "think_max_enabled")

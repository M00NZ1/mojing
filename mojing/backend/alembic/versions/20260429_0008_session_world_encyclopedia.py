"""会话世界关联玩家百科库

Revision ID: 20260429_0008
Revises: 20260428_0007
Create Date: 2026-04-29 00:00:00
"""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "20260429_0008"
down_revision = "20260428_0007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    insp = sa.inspect(bind)
    cols = {c["name"] for c in insp.get_columns("session_worlds")}
    indexes = {i["name"] for i in insp.get_indexes("session_worlds")}
    if "encyclopedia_id" not in cols:
        op.add_column("session_worlds", sa.Column("encyclopedia_id", sa.Integer(), nullable=True))
    if "ix_session_worlds_encyclopedia_id" not in indexes:
        op.create_index("ix_session_worlds_encyclopedia_id", "session_worlds", ["encyclopedia_id"])


def downgrade() -> None:
    bind = op.get_bind()
    insp = sa.inspect(bind)
    indexes = {i["name"] for i in insp.get_indexes("session_worlds")}
    cols = {c["name"] for c in insp.get_columns("session_worlds")}
    if "ix_session_worlds_encyclopedia_id" in indexes:
        op.drop_index("ix_session_worlds_encyclopedia_id", table_name="session_worlds")
    if "encyclopedia_id" in cols:
        op.drop_column("session_worlds", "encyclopedia_id")

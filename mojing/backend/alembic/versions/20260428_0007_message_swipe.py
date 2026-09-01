"""add swipe_group_id to messages for swipe multi-version

Revision ID: 20260428_0007
Revises: 20260409_0006
Create Date: 2026-04-28 14:00:00
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "20260428_0007"
down_revision = "20260409_0006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    cols = {c["name"] for c in sa.inspect(op.get_bind()).get_columns("messages")}
    if "swipe_group_id" not in cols:
        op.add_column("messages", sa.Column("swipe_group_id", sa.String(length=80), nullable=True))


def downgrade() -> None:
    cols = {c["name"] for c in sa.inspect(op.get_bind()).get_columns("messages")}
    if "swipe_group_id" in cols:
        op.drop_column("messages", "swipe_group_id")

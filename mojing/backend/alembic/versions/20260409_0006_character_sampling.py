"""add character sampling params (top_p, top_k, etc.)

Revision ID: 20260409_0006
Revises: 20260409_0004
Create Date: 2026-04-28 12:00:00

Note: 20260409_0005 was never added to the repo; chain 0004 -> 0006 keeps a single head line.
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "20260409_0006"
down_revision = "20260409_0004"
branch_labels = None
depends_on = None


def _character_column_names() -> set[str]:
    bind = op.get_bind()
    insp = sa.inspect(bind)
    return {c["name"] for c in insp.get_columns("characters")}


def upgrade() -> None:
    cols = _character_column_names()
    if "top_p" not in cols:
        op.add_column("characters", sa.Column("top_p", sa.Float(), nullable=False, server_default="1.0"))
    if "top_k" not in cols:
        op.add_column("characters", sa.Column("top_k", sa.Integer(), nullable=False, server_default="0"))
    if "frequency_penalty" not in cols:
        op.add_column("characters", sa.Column("frequency_penalty", sa.Float(), nullable=False, server_default="0.0"))
    if "presence_penalty" not in cols:
        op.add_column("characters", sa.Column("presence_penalty", sa.Float(), nullable=False, server_default="0.0"))
    if "repetition_penalty" not in cols:
        op.add_column("characters", sa.Column("repetition_penalty", sa.Float(), nullable=False, server_default="1.0"))


def downgrade() -> None:
    cols = _character_column_names()
    for name in ("repetition_penalty", "presence_penalty", "frequency_penalty", "top_k", "top_p"):
        if name in cols:
            op.drop_column("characters", name)

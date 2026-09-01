"""会话分支表

Revision ID: 20260409_0003
Revises: 20260409_0002
Create Date: 2026-04-09 01:00:00
"""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy import inspect


revision = "20260409_0003"
down_revision = "20260409_0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    inspector = inspect(bind)
    tables = set(inspector.get_table_names())
    if "session_branches" not in tables:
        op.create_table(
            "session_branches",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("session_id", sa.Integer(), nullable=False),
            sa.Column("branch_id", sa.String(length=80), nullable=False),
            sa.Column("label", sa.String(length=120), nullable=False, server_default=""),
            sa.Column("source_message_id", sa.Integer(), nullable=False),
            sa.Column("parent_branch_id", sa.String(length=80), nullable=False, server_default="main"),
            sa.Column("created_at", sa.DateTime(), nullable=True),
            sa.UniqueConstraint("session_id", "branch_id", name="uq_session_branch"),
        )
    indexes = {item["name"] for item in inspector.get_indexes("session_branches")}
    if "ix_session_branches_session_id" not in indexes:
        op.create_index("ix_session_branches_session_id", "session_branches", ["session_id"], unique=False)
    if "ix_session_branches_branch_id" not in indexes:
        op.create_index("ix_session_branches_branch_id", "session_branches", ["branch_id"], unique=False)
    if "ix_session_branches_source_message_id" not in indexes:
        op.create_index("ix_session_branches_source_message_id", "session_branches", ["source_message_id"], unique=False)
    if "ux_session_branches_session_branch" not in indexes:
        op.create_index(
            "ux_session_branches_session_branch",
            "session_branches",
            ["session_id", "branch_id"],
            unique=True,
        )


def downgrade() -> None:
    bind = op.get_bind()
    inspector = inspect(bind)
    tables = set(inspector.get_table_names())
    if "session_branches" in tables:
        indexes = {item["name"] for item in inspector.get_indexes("session_branches")}
        for name in [
            "ux_session_branches_session_branch",
            "ix_session_branches_source_message_id",
            "ix_session_branches_branch_id",
            "ix_session_branches_session_id",
        ]:
            if name in indexes:
                op.drop_index(name, table_name="session_branches")
        op.drop_table("session_branches")

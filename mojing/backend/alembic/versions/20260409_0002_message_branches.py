"""消息分支字段

Revision ID: 20260409_0002
Revises: 20260409_0001
Create Date: 2026-04-09 00:30:00
"""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy import inspect


revision = "20260409_0002"
down_revision = "20260409_0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    inspector = inspect(bind)
    columns = {item["name"] for item in inspector.get_columns("messages")}
    indexes = {item["name"] for item in inspector.get_indexes("messages")}

    if "branch_id" not in columns:
        op.add_column("messages", sa.Column("branch_id", sa.String(length=80), nullable=False, server_default="main"))
    if "parent_message_id" not in columns:
        op.add_column("messages", sa.Column("parent_message_id", sa.Integer(), nullable=True))
    if "regenerated_from_message_id" not in columns:
        op.add_column("messages", sa.Column("regenerated_from_message_id", sa.Integer(), nullable=True))
    if "ix_messages_branch_id" not in indexes:
        op.create_index("ix_messages_branch_id", "messages", ["branch_id"], unique=False)
    if "ix_messages_parent_message_id" not in indexes:
        op.create_index("ix_messages_parent_message_id", "messages", ["parent_message_id"], unique=False)
    if "ix_messages_regenerated_from_message_id" not in indexes:
        op.create_index("ix_messages_regenerated_from_message_id", "messages", ["regenerated_from_message_id"], unique=False)


def downgrade() -> None:
    bind = op.get_bind()
    inspector = inspect(bind)
    indexes = {item["name"] for item in inspector.get_indexes("messages")}
    columns = {item["name"] for item in inspector.get_columns("messages")}

    if "ix_messages_regenerated_from_message_id" in indexes:
        op.drop_index("ix_messages_regenerated_from_message_id", table_name="messages")
    if "ix_messages_parent_message_id" in indexes:
        op.drop_index("ix_messages_parent_message_id", table_name="messages")
    if "ix_messages_branch_id" in indexes:
        op.drop_index("ix_messages_branch_id", table_name="messages")
    if "regenerated_from_message_id" in columns:
        op.drop_column("messages", "regenerated_from_message_id")
    if "parent_message_id" in columns:
        op.drop_column("messages", "parent_message_id")
    if "branch_id" in columns:
        op.drop_column("messages", "branch_id")

"""会话分支检查点字段与旧库安全迁移

Revision ID: 20260829_0012
Revises: 20260513_0011
Create Date: 2026-08-29 12:00:00
"""
from __future__ import annotations

from alembic import op

from backend.app.services.schema_migration_service import ensure_session_branch_schema


revision = "20260829_0012"
down_revision = "20260513_0011"
branch_labels = None
depends_on = None


def upgrade() -> None:
    ensure_session_branch_schema(op.get_bind())


def downgrade() -> None:
    # SQLite 删除字段需要重建整表，风险高于保留向后兼容的附加字段；回退代码版本时保留数据。
    pass

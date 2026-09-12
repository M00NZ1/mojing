"""Add completed story request receipts without changing existing story data.

Revision ID: 20260913_0013
Revises: 20260829_0012
"""
from alembic import op
import sqlalchemy as sa

revision = "20260913_0013"
down_revision = "20260829_0012"
branch_labels = None
depends_on = None


def upgrade() -> None:
    if not sa.inspect(op.get_bind()).has_table("story_request_receipts"):
        op.create_table(
            "story_request_receipts",
            sa.Column("request_id", sa.String(128), primary_key=True),
            sa.Column("receipt_version", sa.Integer(), nullable=False),
            sa.Column("payload_hash", sa.String(64), nullable=False),
            sa.Column("session_id", sa.Integer(), nullable=False),
            sa.Column("session_created_at", sa.DateTime(), nullable=False),
            sa.Column("result_json", sa.JSON(), nullable=False),
            sa.Column("created_at", sa.DateTime(), nullable=False),
        )
    indexes = {item["name"] for item in sa.inspect(op.get_bind()).get_indexes("story_request_receipts")}
    if "ix_story_request_receipts_session_id" not in indexes:
        op.create_index("ix_story_request_receipts_session_id", "story_request_receipts", ["session_id"])


def downgrade() -> None:
    # Older code ignores the additive table. Preserve receipts when rolling back code.
    pass

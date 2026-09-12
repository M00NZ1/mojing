"""Preserve generated chapters until their story session is saved.

Revision ID: 20260913_0014
Revises: 20260913_0013
"""
from alembic import op
import sqlalchemy as sa

revision = "20260913_0014"
down_revision = "20260913_0013"
branch_labels = None
depends_on = None


def upgrade() -> None:
    if not sa.inspect(op.get_bind()).has_table("story_generation_drafts"):
        op.create_table(
            "story_generation_drafts",
            sa.Column("request_id", sa.String(128), primary_key=True),
            sa.Column("draft_version", sa.Integer(), nullable=False),
            sa.Column("payload_hash", sa.String(64), nullable=False),
            sa.Column("payload_json", sa.JSON(), nullable=False),
            sa.Column("context_text", sa.Text(), nullable=False),
            sa.Column("draft_json", sa.JSON(), nullable=False),
            sa.Column("created_at", sa.DateTime(), nullable=False),
        )


def downgrade() -> None:
    # Keep generated content available when the application code is rolled back.
    pass

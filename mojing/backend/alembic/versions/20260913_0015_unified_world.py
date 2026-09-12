"""Add the additive unified-world side tables."""

from alembic import op
import sqlalchemy as sa
from uuid import uuid4

revision = "20260913_0015"
down_revision = "20260913_0014"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    if not inspector.has_table("unified_world_profiles"):
        op.create_table(
            "unified_world_profiles",
            sa.Column("encyclopedia_id", sa.Integer(), sa.ForeignKey("world_encyclopedias.id", ondelete="CASCADE"), primary_key=True),
            sa.Column("world_key", sa.String(36), nullable=False),
            sa.Column("category", sa.String(80), nullable=False),
            sa.Column("suggested_choices_json", sa.JSON(), nullable=False),
            sa.Column("version", sa.Integer(), nullable=False),
        )
    if not inspector.has_table("legacy_world_mappings"):
        op.create_table(
            "legacy_world_mappings",
            sa.Column("world_template_id", sa.Integer(), sa.ForeignKey("world_templates.id"), primary_key=True),
            sa.Column("encyclopedia_id", sa.Integer(), sa.ForeignKey("world_encyclopedias.id"), nullable=False),
            sa.Column("source_hash", sa.String(64), nullable=False),
            sa.Column("migration_version", sa.Integer(), nullable=False),
        )
    if not inspector.has_table("legacy_lore_mappings"):
        op.create_table(
            "legacy_lore_mappings",
            sa.Column("lore_entry_id", sa.Integer(), sa.ForeignKey("world_lore_entries.id"), primary_key=True),
            sa.Column("encyclopedia_entry_id", sa.Integer(), sa.ForeignKey("encyclopedia_entries.id", ondelete="SET NULL"), nullable=True),
            sa.Column("source_hash", sa.String(64), nullable=False),
            sa.Column("migration_version", sa.Integer(), nullable=False),
        )
    for table_name, column_name, index_name in (
        ("unified_world_profiles", "world_key", "ix_unified_world_profiles_world_key"),
        ("legacy_world_mappings", "encyclopedia_id", "ix_legacy_world_mappings_encyclopedia_id"),
        ("legacy_lore_mappings", "encyclopedia_entry_id", "ix_legacy_lore_mappings_encyclopedia_entry_id"),
    ):
        if index_name not in {item["name"] for item in sa.inspect(bind).get_indexes(table_name)}:
            op.create_index(index_name, table_name, [column_name], unique=column_name == "world_key")
    # Add identities only; existing world metadata and session snapshots stay intact.
    ids = bind.execute(sa.text("SELECT id FROM world_encyclopedias WHERE id NOT IN (SELECT encyclopedia_id FROM unified_world_profiles)")).scalars().all()
    for encyclopedia_id in ids:
        bind.execute(sa.text("INSERT INTO unified_world_profiles (encyclopedia_id, world_key, category, suggested_choices_json, version) VALUES (:id, :key, '通用', '[]', 1)"),
                     {"id": encyclopedia_id, "key": str(uuid4())})


def downgrade() -> None:
    # Preserve mappings and canonical data when application code is rolled back.
    pass

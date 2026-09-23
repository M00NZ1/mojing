"""Add platform-scoped model pricing and cost provenance fields."""
from alembic import op
import sqlalchemy as sa

revision = "20260923_0016"
down_revision = "20260913_0015"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    if inspector.has_table("llm_cost_records"):
        columns = {item["name"] for item in inspector.get_columns("llm_cost_records")}
        additions = (
            ("platform_id", sa.Column("platform_id", sa.String(120), nullable=True)),
            ("platform_name", sa.Column("platform_name", sa.String(120), nullable=False, server_default="")),
            ("currency", sa.Column("currency", sa.String(3), nullable=False, server_default="USD")),
            ("cost_known", sa.Column("cost_known", sa.Integer(), nullable=False, server_default=sa.text("1"))),
            ("pricing_snapshot_json", sa.Column("pricing_snapshot_json", sa.JSON(), nullable=True)),
            ("message_id", sa.Column("message_id", sa.String(128), nullable=True)),
            ("usage_source", sa.Column("usage_source", sa.String(40), nullable=False, server_default="estimated")),
        )
        for name, column in additions:
            if name not in columns:
                op.add_column("llm_cost_records", column)
        bind.execute(sa.text("UPDATE llm_cost_records SET cost_known = 0 WHERE platform_id IS NULL"))
        indexes = {item["name"] for item in sa.inspect(bind).get_indexes("llm_cost_records")}
        if "ix_llm_cost_records_platform_id" not in indexes:
            op.create_index("ix_llm_cost_records_platform_id", "llm_cost_records", ["platform_id"])
        if "ix_llm_cost_records_message_id" not in indexes:
            op.create_index("ix_llm_cost_records_message_id", "llm_cost_records", ["message_id"])

    if not inspector.has_table("model_prices"):
        op.create_table(
            "model_prices",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("platform_id", sa.String(120), nullable=False),
            sa.Column("model_name", sa.String(120), nullable=False),
            sa.Column("currency", sa.String(3), nullable=False),
            sa.Column("input_per_million", sa.Float(), nullable=False),
            sa.Column("output_per_million", sa.Float(), nullable=False),
            sa.Column("cached_input_per_million", sa.Float(), nullable=False),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.Column("updated_at", sa.DateTime(), nullable=False),
            sa.UniqueConstraint("platform_id", "model_name", name="uq_model_prices_platform_model"),
        )
    indexes = {item["name"] for item in sa.inspect(bind).get_indexes("model_prices")}
    if "ix_model_prices_platform_id" not in indexes:
        op.create_index("ix_model_prices_platform_id", "model_prices", ["platform_id"])


def downgrade() -> None:
    # Preserve local pricing and provenance when rolling back application code.
    pass

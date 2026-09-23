"""Track Web text sends by client ID so an uncertain response can be retried."""

from alembic import op

from backend.app.services.schema_migration_service import ensure_message_client_id_schema


revision = "20260924_0017"
down_revision = "20260923_0016"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # The shared upgrader validates old data and writes a rollback snapshot before ALTER.
    ensure_message_client_id_schema(op.get_bind())


def downgrade() -> None:
    # Keep message IDs and the uniqueness guarantee when older code is restored.
    pass

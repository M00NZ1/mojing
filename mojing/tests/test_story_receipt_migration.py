from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import create_engine, inspect, text


def test_story_receipt_upgrade_repeat_and_code_rollback_preserve_data(tmp_path):
    path = Path(__file__).resolve().parents[1] / "backend/alembic/versions/20260913_0013_story_request_receipts.py"
    spec = spec_from_file_location("story_receipt_migration", path)
    migration = module_from_spec(spec)
    spec.loader.exec_module(migration)
    engine = create_engine(f"sqlite:///{tmp_path / 'legacy.db'}")
    with engine.begin() as connection:
        connection.execute(text("CREATE TABLE original_messages (id INTEGER PRIMARY KEY, content TEXT)"))
        connection.execute(text("INSERT INTO original_messages VALUES (1, '保留旧故事')"))
        migration.op = Operations(MigrationContext.configure(connection))
        migration.upgrade()
        connection.execute(text("""INSERT INTO story_request_receipts
            (request_id, receipt_version, payload_hash, session_id, session_created_at, result_json, created_at)
            VALUES ('request-1', 1, 'hash', 1, '2026-09-13', '{}', '2026-09-13')"""))
        migration.upgrade()
        migration.downgrade()
        migration.upgrade()
        assert connection.scalar(text("SELECT content FROM original_messages WHERE id = 1")) == '保留旧故事'
        assert connection.scalar(text("SELECT count(*) FROM story_request_receipts")) == 1
        assert connection.scalar(text("PRAGMA integrity_check")) == 'ok'
        assert inspect(connection).get_pk_constraint('story_request_receipts')['constrained_columns'] == ['request_id']
        assert any(i['column_names'] == ['session_id'] for i in inspect(connection).get_indexes('story_request_receipts'))
    engine.dispose()

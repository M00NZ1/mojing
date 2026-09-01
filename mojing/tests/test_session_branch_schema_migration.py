from __future__ import annotations

import json
import sqlite3
import zipfile
from contextlib import closing
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

from backend.app import models  # noqa: F401
from backend.app.config import settings
from backend.app.database import Base
from backend.app.services import backup_service, schema_migration_service


def _create_legacy_database(path: Path, *, duplicates: bool = False) -> None:
    with closing(sqlite3.connect(path)) as connection:
        connection.execute("PRAGMA journal_mode = WAL")
        connection.executescript(
            """
            CREATE TABLE session_branches (
                id INTEGER NOT NULL PRIMARY KEY,
                session_id INTEGER NOT NULL,
                branch_id VARCHAR(80) NOT NULL,
                label VARCHAR(120) DEFAULT '',
                source_message_id INTEGER NOT NULL,
                parent_branch_id VARCHAR(80) DEFAULT 'main',
                created_at DATETIME
            );
            CREATE TABLE private_settings (
                id INTEGER NOT NULL PRIMARY KEY,
                payload TEXT NOT NULL
            );
            """
        )
        connection.execute(
            "INSERT INTO session_branches "
            "(session_id, branch_id, label, source_message_id, parent_branch_id) "
            "VALUES (1, 'main', '主线', 10, 'main')"
        )
        if duplicates:
            connection.execute(
                "INSERT INTO session_branches "
                "(session_id, branch_id, label, source_message_id, parent_branch_id) "
                "VALUES (1, 'main', '重复主线', 11, 'main')"
            )
        connection.execute(
            "INSERT INTO private_settings(payload) VALUES (?)",
            (json.dumps({"api_key": "rollback-must-preserve-this-secret"}),),
        )
        connection.commit()


def _engine_for(path: Path):
    return create_engine(
        f"sqlite:///{path.as_posix()}",
        connect_args={"check_same_thread": False},
    )


def _columns(path: Path) -> set[str]:
    with closing(sqlite3.connect(path)) as connection:
        return {str(row[1]) for row in connection.execute("PRAGMA table_info(session_branches)")}


def _index_shapes(path: Path) -> set[tuple[tuple[str, ...], bool]]:
    with closing(sqlite3.connect(path)) as connection:
        shapes: set[tuple[tuple[str, ...], bool]] = set()
        for row in connection.execute("PRAGMA index_list(session_branches)"):
            fields = tuple(
                str(index_row[2])
                for index_row in connection.execute(f'PRAGMA index_info("{row[1]}")')
            )
            shapes.add((fields, bool(row[2])))
        return shapes


def test_old_branch_schema_is_snapshotted_upgraded_and_idempotent(tmp_path: Path) -> None:
    database_path = tmp_path / "storage" / "app.db"
    database_path.parent.mkdir()
    _create_legacy_database(database_path)
    engine = _engine_for(database_path)

    with Session(engine) as session:
        result = schema_migration_service.ensure_session_branch_schema(session)

    assert result.changed is True
    assert result.snapshot_path is not None and result.snapshot_path.is_file()
    assert result.added_columns == ("is_checkpoint", "checkpoint_label")
    assert set(result.added_indexes) == {
        "ix_session_branches_session_id",
        "ix_session_branches_branch_id",
        "ix_session_branches_source_message_id",
        "ux_session_branches_session_branch",
    }
    validation = backup_service.validate_database_rollback_snapshot(
        result.snapshot_path,
        expected_migration_id=schema_migration_service.SESSION_BRANCH_MIGRATION_ID,
    )
    assert validation["database_check"] == "ok"

    rollback_database = tmp_path / "rollback.db"
    with zipfile.ZipFile(result.snapshot_path) as archive:
        rollback_database.write_bytes(
            archive.read(backup_service.ROLLBACK_DATABASE_ARCHIVE_PATH)
        )
    assert "is_checkpoint" not in _columns(rollback_database)
    with closing(sqlite3.connect(rollback_database)) as connection:
        private_payload = connection.execute("SELECT payload FROM private_settings").fetchone()[0]
    assert "rollback-must-preserve-this-secret" in private_payload

    assert {"is_checkpoint", "checkpoint_label"}.issubset(_columns(database_path))
    with closing(sqlite3.connect(database_path)) as connection:
        migrated_row = connection.execute(
            "SELECT label, is_checkpoint, checkpoint_label FROM session_branches"
        ).fetchone()
    assert migrated_row == ("主线", 0, "")
    shapes = _index_shapes(database_path)
    assert (("session_id",), False) in shapes
    assert (("branch_id",), False) in shapes
    assert (("source_message_id",), False) in shapes
    assert (("session_id", "branch_id"), True) in shapes

    existing_snapshots = set(result.snapshot_path.parent.iterdir())
    with Session(engine) as session:
        repeated = schema_migration_service.ensure_session_branch_schema(session)
    assert repeated.changed is False
    assert repeated.snapshot_path is None
    assert set(result.snapshot_path.parent.iterdir()) == existing_snapshots
    engine.dispose()


def test_current_metadata_is_a_strict_no_op_without_snapshot(tmp_path: Path) -> None:
    database_path = tmp_path / "app.db"
    engine = _engine_for(database_path)
    Base.metadata.create_all(engine)

    with Session(engine) as session:
        result = schema_migration_service.ensure_session_branch_schema(session)

    assert result.changed is False
    assert result.snapshot_path is None
    assert not (tmp_path / "backups" / "migrations").exists()
    engine.dispose()


def test_duplicate_branch_ids_stop_before_snapshot_or_schema_change(tmp_path: Path) -> None:
    database_path = tmp_path / "app.db"
    _create_legacy_database(database_path, duplicates=True)
    engine = _engine_for(database_path)

    with Session(engine) as session:
        with pytest.raises(schema_migration_service.SchemaMigrationError, match="重复标识"):
            schema_migration_service.ensure_session_branch_schema(session)

    assert "is_checkpoint" not in _columns(database_path)
    assert not (tmp_path / "backups" / "migrations").exists()
    engine.dispose()


def test_partial_schema_backfills_null_without_overwriting_valid_checkpoint(tmp_path: Path) -> None:
    database_path = tmp_path / "app.db"
    _create_legacy_database(database_path)
    with closing(sqlite3.connect(database_path)) as connection:
        connection.execute("ALTER TABLE session_branches ADD COLUMN is_checkpoint INTEGER")
        connection.execute("UPDATE session_branches SET is_checkpoint = 1")
        connection.commit()
    engine = _engine_for(database_path)

    with Session(engine) as session:
        result = schema_migration_service.ensure_session_branch_schema(session)

    assert result.added_columns == ("checkpoint_label",)
    with closing(sqlite3.connect(database_path)) as connection:
        assert connection.execute(
            "SELECT is_checkpoint, checkpoint_label FROM session_branches"
        ).fetchone() == (1, "")
    engine.dispose()


def test_abnormal_existing_checkpoint_value_stops_before_snapshot(tmp_path: Path) -> None:
    database_path = tmp_path / "app.db"
    _create_legacy_database(database_path)
    with closing(sqlite3.connect(database_path)) as connection:
        connection.execute("ALTER TABLE session_branches ADD COLUMN is_checkpoint INTEGER")
        connection.execute("UPDATE session_branches SET is_checkpoint = 2")
        connection.commit()
    engine = _engine_for(database_path)

    with Session(engine) as session:
        with pytest.raises(schema_migration_service.SchemaMigrationError, match="异常值"):
            schema_migration_service.ensure_session_branch_schema(session)

    assert not (tmp_path / "backups" / "migrations").exists()
    assert "checkpoint_label" not in _columns(database_path)
    engine.dispose()


def test_failed_schema_change_rolls_back_and_keeps_recovery_snapshot(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    database_path = tmp_path / "app.db"
    _create_legacy_database(database_path)
    engine = _engine_for(database_path)

    def fail_after_first_column(connection, **kwargs) -> None:
        connection.exec_driver_sql(
            "ALTER TABLE session_branches "
            "ADD COLUMN is_checkpoint INTEGER NOT NULL DEFAULT 0"
        )
        raise RuntimeError("simulated migration failure")

    monkeypatch.setattr(
        schema_migration_service,
        "_apply_session_branch_schema_changes",
        fail_after_first_column,
    )
    with Session(engine) as session:
        with pytest.raises(schema_migration_service.SchemaMigrationError) as raised:
            schema_migration_service.ensure_session_branch_schema(session)

    snapshot_path = raised.value.snapshot_path
    assert snapshot_path is not None and snapshot_path.is_file()
    assert backup_service.validate_database_rollback_snapshot(snapshot_path)["database_check"] == "ok"
    assert "is_checkpoint" not in _columns(database_path)
    with closing(sqlite3.connect(database_path)) as connection:
        assert connection.execute("SELECT COUNT(*) FROM session_branches").fetchone()[0] == 1
    engine.dispose()


def test_alembic_head_uses_the_same_idempotent_migration_on_temp_database(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    database_path = tmp_path / "app.db"
    _create_legacy_database(database_path)
    with closing(sqlite3.connect(database_path)) as connection:
        connection.execute("CREATE TABLE alembic_version (version_num VARCHAR(32) NOT NULL)")
        connection.execute("INSERT INTO alembic_version(version_num) VALUES ('20260513_0011')")
        connection.commit()

    monkeypatch.setattr(settings, "database_url", f"sqlite:///{database_path.as_posix()}")
    config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
    command.upgrade(config, "head")

    assert {"is_checkpoint", "checkpoint_label"}.issubset(_columns(database_path))
    with closing(sqlite3.connect(database_path)) as connection:
        assert connection.execute("SELECT version_num FROM alembic_version").fetchone()[0] == "20260829_0012"
    snapshots = list((tmp_path / "backups" / "migrations").glob("*.zip"))
    assert len(snapshots) == 1
    assert backup_service.validate_database_rollback_snapshot(snapshots[0])["database_check"] == "ok"

    command.upgrade(config, "head")
    assert list((tmp_path / "backups" / "migrations").glob("*.zip")) == snapshots

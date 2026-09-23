from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from sqlalchemy import Connection, text
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session

from .backup_service import BackupError, build_database_rollback_snapshot


SESSION_BRANCH_MIGRATION_ID = "20260829_session_branch_checkpoints"
SESSION_BRANCH_TABLE = "session_branches"
COST_SCHEMA_MIGRATION_ID = "20260923_cost_pricing"


class SchemaMigrationError(RuntimeError):
    """运行时结构迁移无法安全完成时抛出。"""

    def __init__(self, message: str, *, snapshot_path: Path | None = None) -> None:
        super().__init__(message)
        self.snapshot_path = snapshot_path


@dataclass(frozen=True)
class SchemaMigrationResult:
    changed: bool
    snapshot_path: Path | None = None
    added_columns: tuple[str, ...] = ()
    added_indexes: tuple[str, ...] = ()
    backfilled_nulls: bool = False


@dataclass(frozen=True)
class _SchemaRequirements:
    missing_columns: tuple[str, ...]
    missing_indexes: tuple[tuple[str, tuple[str, ...], bool], ...]
    null_backfill_needed: bool

    @property
    def needs_change(self) -> bool:
        return bool(self.missing_columns or self.missing_indexes or self.null_backfill_needed)


SnapshotBuilder = Callable[[Path, str], Path]


def ensure_cost_schema(
    executor: Session | Connection,
    *,
    snapshot_builder: SnapshotBuilder = build_database_rollback_snapshot,
) -> None:
    """幂等补齐成本字段；旧记录只标为未知，不推断其平台或价格。"""
    connection = _connection_for(executor)
    if connection.engine.url.get_backend_name() != "sqlite":
        raise SchemaMigrationError("成本结构兼容迁移仅支持本机 SQLite 数据库。")
    if not _table_exists(connection, "llm_cost_records"):
        return
    columns = _column_names(connection, "llm_cost_records")
    required_columns = {
        "id", "model_name", "provider", "prompt_tokens", "completion_tokens",
        "total_tokens", "estimated_cost", "success",
    }
    missing_required = required_columns - columns
    if missing_required:
        raise SchemaMigrationError("成本记录表缺少基础字段，已停止自动迁移以避免误改数据。")
    additions = {
        "platform_id": "VARCHAR(120)",
        "platform_name": "VARCHAR(120) NOT NULL DEFAULT ''",
        "currency": "VARCHAR(3) NOT NULL DEFAULT 'USD'",
        "cost_known": "INTEGER NOT NULL DEFAULT 1",
        "pricing_snapshot_json": "JSON",
        "message_id": "VARCHAR(128)",
        "usage_source": "VARCHAR(40) NOT NULL DEFAULT 'estimated'",
    }
    existing_indexes = _index_shapes(connection, "llm_cost_records")
    missing_indexes = []
    for name, fields, unique in (
        ("ix_llm_cost_records_platform_id", ("platform_id",), False),
        ("ix_llm_cost_records_message_id", ("message_id",), False),
    ):
        if not _has_index_shape(existing_indexes, fields, unique):
            missing_indexes.append((name, fields, unique))
    null_backfill_needed = connection.exec_driver_sql(
        'SELECT 1 FROM "llm_cost_records" WHERE "platform_id" IS NULL AND "cost_known" != 0 LIMIT 1'
    ).first() is not None if "cost_known" in columns else True
    needs_change = bool(set(additions) - columns or missing_indexes or null_backfill_needed)
    if _table_exists(connection, "model_prices"):
        price_required = {
            "platform_id", "model_name", "currency", "input_per_million",
            "output_per_million", "cached_input_per_million",
        }
        missing_price = price_required - _column_names(connection, "model_prices")
        if missing_price:
            raise SchemaMigrationError("模型价格表缺少基础字段，已停止自动迁移。")
        price_indexes = _index_shapes(connection, "model_prices")
        if not _has_index_shape(price_indexes, ("platform_id", "model_name"), True):
            needs_change = True
    if not needs_change:
        return
    snapshot_path: Path | None = None
    changed = False
    try:
        _begin_sqlite_write_transaction(connection)
        # Re-read after acquiring the lock, then snapshot before any ALTER/backfill.
        columns = _column_names(connection, "llm_cost_records")
        if not set(additions) - columns and not missing_indexes and not null_backfill_needed:
            if isinstance(executor, Session):
                executor.commit()
            return
        database = connection.engine.url.database
        if database and database != ":memory:":
            snapshot_path = snapshot_builder(_sqlite_database_path(connection), COST_SCHEMA_MIGRATION_ID)
        for name, definition in additions.items():
            if name not in columns:
                connection.exec_driver_sql(
                    f'ALTER TABLE "llm_cost_records" ADD COLUMN "{name}" {definition}'
                )
                changed = True
        for name, fields, unique in missing_indexes:
            field_sql = ", ".join(_quote_identifier(field) for field in fields)
            connection.exec_driver_sql(
                f'CREATE INDEX IF NOT EXISTS "{name}" ON "llm_cost_records" ({field_sql})'
            )
            changed = True
        if _table_exists(connection, "model_prices"):
            duplicate = connection.exec_driver_sql(
                "SELECT 1 FROM model_prices GROUP BY platform_id, model_name HAVING COUNT(*) > 1 LIMIT 1"
            ).first()
            if duplicate is not None:
                raise SchemaMigrationError("模型价格存在重复平台/模型标识，已停止自动迁移。")
            existing_indexes = _index_shapes(connection, "model_prices")
            if not _has_index_shape(existing_indexes, ("platform_id", "model_name"), True):
                connection.exec_driver_sql(
                    'CREATE UNIQUE INDEX IF NOT EXISTS "uq_model_prices_platform_model" '
                    'ON "model_prices" ("platform_id", "model_name")'
                )
                changed = True
        # NULL platform_id is the only safe identity test for pre-feature records.
        if null_backfill_needed or "platform_id" not in columns:
            connection.exec_driver_sql(
                'UPDATE "llm_cost_records" SET "cost_known" = 0 WHERE "platform_id" IS NULL'
            )
        if isinstance(executor, Session):
            executor.commit()
    except BackupError as exc:
        if isinstance(executor, Session):
            executor.rollback()
        raise SchemaMigrationError("无法创建迁移前回滚快照，数据库结构未修改。") from exc
    except Exception as exc:
        if isinstance(executor, Session):
            executor.rollback()
        if snapshot_path is None and connection.engine.url.database not in (None, ":memory:"):
            raise SchemaMigrationError("成本结构迁移失败，数据库结构未修改。") from exc
        raise


def ensure_session_branch_schema(
    executor: Session | Connection,
    *,
    snapshot_builder: SnapshotBuilder = build_database_rollback_snapshot,
) -> SchemaMigrationResult:
    """幂等补齐会话分支结构；任何结构写入前先生成完整本机回滚快照。"""

    connection = _connection_for(executor)
    requirements = _inspect_session_branch_requirements(connection)
    if not requirements.needs_change:
        return SchemaMigrationResult(changed=False)
    _validate_session_branch_requirements(connection, requirements)
    database_path = _sqlite_database_path(connection)

    snapshot_path: Path | None = None
    added_columns: list[str] = []
    added_indexes: list[str] = []
    try:
        _begin_sqlite_write_transaction(connection)

        # 等待写锁期间另一个进程可能已完成迁移；锁内复读后严格 no-op。
        requirements = _inspect_session_branch_requirements(connection)
        if not requirements.needs_change:
            if isinstance(executor, Session):
                executor.commit()
            return SchemaMigrationResult(changed=False)
        _validate_session_branch_requirements(connection, requirements)

        snapshot_path = snapshot_builder(database_path, SESSION_BRANCH_MIGRATION_ID)
        _apply_session_branch_schema_changes(
            connection,
            missing_columns=requirements.missing_columns,
            missing_indexes=requirements.missing_indexes,
            null_backfill_needed=requirements.null_backfill_needed,
            added_columns=added_columns,
            added_indexes=added_indexes,
        )

        if isinstance(executor, Session):
            executor.commit()
    except SchemaMigrationError:
        if isinstance(executor, Session):
            executor.rollback()
        raise
    except BackupError as exc:
        if isinstance(executor, Session):
            executor.rollback()
        raise SchemaMigrationError("无法创建迁移前回滚快照，数据库结构未修改。") from exc
    except Exception as exc:
        if isinstance(executor, Session):
            executor.rollback()
        if snapshot_path is None:
            raise SchemaMigrationError("会话分支结构迁移未能开始，数据库结构未修改。") from exc
        raise SchemaMigrationError(
            "会话分支结构迁移失败；迁移前回滚快照已保留。",
            snapshot_path=snapshot_path,
        ) from exc

    return SchemaMigrationResult(
        changed=True,
        snapshot_path=snapshot_path,
        added_columns=tuple(added_columns),
        added_indexes=tuple(added_indexes),
        backfilled_nulls=requirements.null_backfill_needed,
    )


def _inspect_session_branch_requirements(connection: Connection) -> _SchemaRequirements:
    if not _table_exists(connection, SESSION_BRANCH_TABLE):
        raise SchemaMigrationError("会话分支表不存在，无法执行兼容迁移。")

    columns = _column_names(connection, SESSION_BRANCH_TABLE)
    required_base_columns = {
        "id",
        "session_id",
        "branch_id",
        "label",
        "source_message_id",
        "parent_branch_id",
        "created_at",
    }
    missing_base_columns = required_base_columns - columns
    if missing_base_columns:
        raise SchemaMigrationError("会话分支表缺少基础字段，已停止自动迁移以避免误改数据。")

    if _invalid_checkpoint_values_exist(connection, columns):
        raise SchemaMigrationError("会话分支检查点字段含异常值，已停止自动迁移以避免猜测用户数据。")

    missing_columns = tuple(
        name for name in ("is_checkpoint", "checkpoint_label") if name not in columns
    )
    null_backfill_needed = _checkpoint_nulls_exist(connection, columns)
    existing_indexes = _index_shapes(connection, SESSION_BRANCH_TABLE)
    desired_indexes = (
        ("ix_session_branches_session_id", ("session_id",), False),
        ("ix_session_branches_branch_id", ("branch_id",), False),
        ("ix_session_branches_source_message_id", ("source_message_id",), False),
        ("ux_session_branches_session_branch", ("session_id", "branch_id"), True),
    )
    missing_indexes = tuple(
        (name, fields, unique)
        for name, fields, unique in desired_indexes
        if not _has_index_shape(existing_indexes, fields, unique)
    )

    return _SchemaRequirements(
        missing_columns=missing_columns,
        missing_indexes=missing_indexes,
        null_backfill_needed=null_backfill_needed,
    )


def _validate_session_branch_requirements(
    connection: Connection,
    requirements: _SchemaRequirements,
) -> None:
    if any(unique for _name, _fields, unique in requirements.missing_indexes):
        duplicate = connection.execute(
            text(
                "SELECT 1 FROM session_branches "
                "GROUP BY session_id, branch_id HAVING COUNT(*) > 1 LIMIT 1"
            )
        ).first()
        if duplicate is not None:
            raise SchemaMigrationError(
                "会话分支存在重复标识，已停止自动迁移；请先人工确认要保留的分支。"
            )

    existing_index_names = {
        name
        for name, _fields, _unique in _index_shapes(connection, SESSION_BRANCH_TABLE)
    }
    colliding_names = {
        name
        for name, _fields, _unique in requirements.missing_indexes
        if name in existing_index_names
    }
    if colliding_names:
        raise SchemaMigrationError("会话分支索引名称与现有结构冲突，已停止自动迁移。")


def _connection_for(executor: Session | Connection) -> Connection:
    if isinstance(executor, Session):
        return executor.connection()
    if isinstance(executor, Connection):
        return executor
    raise TypeError("executor must be a SQLAlchemy Session or Connection")


def _table_exists(connection: Connection, table_name: str) -> bool:
    return connection.execute(
        text("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = :name"),
        {"name": table_name},
    ).first() is not None


def _column_names(connection: Connection, table_name: str) -> set[str]:
    return {str(row[1]) for row in connection.exec_driver_sql(f"PRAGMA table_info({_quote_identifier(table_name)})")}


def _checkpoint_nulls_exist(connection: Connection, columns: set[str]) -> bool:
    conditions: list[str] = []
    if "is_checkpoint" in columns:
        conditions.append("is_checkpoint IS NULL")
    if "checkpoint_label" in columns:
        conditions.append("checkpoint_label IS NULL")
    if not conditions:
        return False
    where_sql = " OR ".join(conditions)
    return connection.exec_driver_sql(
        f"SELECT 1 FROM session_branches WHERE {where_sql} LIMIT 1"
    ).first() is not None


def _invalid_checkpoint_values_exist(connection: Connection, columns: set[str]) -> bool:
    conditions: list[str] = []
    if "is_checkpoint" in columns:
        conditions.append(
            "(is_checkpoint IS NOT NULL AND "
            "(typeof(is_checkpoint) != 'integer' OR is_checkpoint NOT IN (0, 1)))"
        )
    if "checkpoint_label" in columns:
        conditions.append(
            "(checkpoint_label IS NOT NULL AND "
            "(typeof(checkpoint_label) != 'text' OR length(checkpoint_label) > 200))"
        )
    if not conditions:
        return False
    where_sql = " OR ".join(conditions)
    return connection.exec_driver_sql(
        f"SELECT 1 FROM session_branches WHERE {where_sql} LIMIT 1"
    ).first() is not None


def _index_shapes(connection: Connection, table_name: str) -> tuple[tuple[str, tuple[str, ...], bool], ...]:
    indexes: list[tuple[str, tuple[str, ...], bool]] = []
    for row in connection.exec_driver_sql(f"PRAGMA index_list({_quote_identifier(table_name)})"):
        name = str(row[1])
        unique = bool(row[2])
        fields = tuple(
            str(index_row[2])
            for index_row in connection.exec_driver_sql(f"PRAGMA index_info({_quote_identifier(name)})")
            if index_row[2] is not None
        )
        indexes.append((name, fields, unique))
    return tuple(indexes)


def _has_index_shape(
    indexes: tuple[tuple[str, tuple[str, ...], bool], ...],
    fields: tuple[str, ...],
    unique: bool,
) -> bool:
    return any(index_fields == fields and index_unique == unique for _name, index_fields, index_unique in indexes)


def _sqlite_database_path(connection: Connection) -> Path:
    engine: Engine = connection.engine
    if engine.url.get_backend_name() != "sqlite":
        raise SchemaMigrationError("自动兼容迁移仅支持本机 SQLite 数据库。")
    database = engine.url.database
    if not database or database == ":memory:":
        raise SchemaMigrationError("内存数据库不支持创建迁移回滚快照。")
    return Path(database).resolve()


def _begin_sqlite_write_transaction(connection: Connection) -> None:
    driver_connection = connection.connection.driver_connection
    if not bool(getattr(driver_connection, "in_transaction", False)):
        connection.exec_driver_sql("BEGIN IMMEDIATE")


def _apply_session_branch_schema_changes(
    connection: Connection,
    *,
    missing_columns: tuple[str, ...],
    missing_indexes: tuple[tuple[str, tuple[str, ...], bool], ...],
    null_backfill_needed: bool,
    added_columns: list[str],
    added_indexes: list[str],
) -> None:
    if "is_checkpoint" in missing_columns:
        connection.exec_driver_sql(
            "ALTER TABLE session_branches "
            "ADD COLUMN is_checkpoint INTEGER NOT NULL DEFAULT 0"
        )
        added_columns.append("is_checkpoint")
    if "checkpoint_label" in missing_columns:
        connection.exec_driver_sql(
            "ALTER TABLE session_branches "
            "ADD COLUMN checkpoint_label VARCHAR(200) NOT NULL DEFAULT ''"
        )
        added_columns.append("checkpoint_label")

    if null_backfill_needed:
        connection.exec_driver_sql(
            "UPDATE session_branches SET is_checkpoint = 0 WHERE is_checkpoint IS NULL"
        )
        connection.exec_driver_sql(
            "UPDATE session_branches SET checkpoint_label = '' WHERE checkpoint_label IS NULL"
        )

    for name, fields, unique in missing_indexes:
        unique_sql = "UNIQUE " if unique else ""
        field_sql = ", ".join(_quote_identifier(field) for field in fields)
        connection.exec_driver_sql(
            f"CREATE {unique_sql}INDEX {_quote_identifier(name)} "
            f"ON {_quote_identifier(SESSION_BRANCH_TABLE)} ({field_sql})"
        )
        added_indexes.append(name)


def _quote_identifier(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'

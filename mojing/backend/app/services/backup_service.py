from __future__ import annotations

import hashlib
import json
import os
import shutil
import sqlite3
import stat
import tempfile
import unicodedata
import uuid
import zipfile
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any

from ..config import STORAGE_DIR


BACKUP_FORMAT = "mojing-portable-backup"
BACKUP_FORMAT_VERSION = 1
ROLLBACK_FORMAT = "mojing-database-rollback"
ROLLBACK_FORMAT_VERSION = 1
MANIFEST_NAME = "manifest.json"
DATABASE_ARCHIVE_PATH = "storage/app.db"
ROLLBACK_DATABASE_ARCHIVE_PATH = "database/app.db"
MAX_MANIFEST_BYTES = 2 * 1024 * 1024
MAX_ARCHIVE_FILE_COUNT = 100_000
MAX_ARCHIVE_UNCOMPRESSED_BYTES = 1024 * 1024 * 1024 * 1024
MAX_SINGLE_FILE_BYTES = 512 * 1024 * 1024 * 1024
MAX_SUSPICIOUS_COMPRESSION_RATIO = 10_000

_EXCLUDED_TOP_LEVEL = {"backups", "conversations", "exports", "temp"}
_SENSITIVE_FILENAMES = {".fernet_key", ".fernet_key.id"}
_WINDOWS_DEVICE_NAMES = {
    "aux",
    "con",
    "nul",
    "prn",
    *(f"com{index}" for index in range(1, 10)),
    *(f"lpt{index}" for index in range(1, 10)),
}
_SENSITIVE_JSON_KEYS = {
    "api_key",
    "access_token",
    "authorization",
    "bearer_token",
    "client_secret",
    "external_api_key",
    "image_gen_api_key",
    "password",
    "public_image_api_key",
    "public_text_api_key",
    "public_voice_api_key",
    "refresh_token",
    "secret",
    "voice_api_key",
}


class BackupError(RuntimeError):
    """备份无法安全生成或校验时抛出。"""


def build_private_file_rollback_copy(
    source_path: Path,
    migration_id: str,
    *,
    backup_dir: Path | None = None,
) -> Path:
    """为数据库外的私密配置生成不覆盖既有文件的原子回滚副本。"""

    source = source_path.resolve()
    normalized_migration_id = migration_id.strip()
    if (
        not normalized_migration_id
        or len(normalized_migration_id) > 80
        or any(
            character not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-"
            for character in normalized_migration_id
        )
    ):
        raise BackupError("迁移标识无效，无法创建配置回滚副本。")
    if not source.is_file():
        raise BackupError("私密配置文件不存在，无法创建迁移回滚副本。")

    destination_dir = (backup_dir or (source.parent / "backups" / "migrations")).resolve()
    destination_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(destination_dir, 0o700)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output_name = (
        f"pre_{normalized_migration_id}_{timestamp}_{uuid.uuid4().hex[:8]}_{source.name}"
    )
    output_path = destination_dir / output_name
    temp_path = destination_dir / f".{output_name}.{uuid.uuid4().hex}.tmp"
    try:
        _copy_stable_file(source, temp_path)
        if temp_path.stat().st_size != source.stat().st_size:
            raise BackupError("私密配置回滚副本大小校验失败。")
        if _hash_file(temp_path) != _hash_file(source):
            raise BackupError("私密配置回滚副本哈希校验失败。")
        with temp_path.open("rb+") as handle:
            os.fsync(handle.fileno())
        os.link(temp_path, output_path)
        os.chmod(output_path, 0o600)
        temp_path.unlink()
        return output_path
    except BackupError:
        raise
    except OSError as exc:
        raise BackupError("创建私密配置回滚副本失败，请检查本机磁盘空间和权限。") from exc
    finally:
        if temp_path.exists():
            temp_path.unlink()


def build_database_rollback_snapshot(
    database_path: Path,
    migration_id: str,
    *,
    backup_dir: Path | None = None,
) -> Path:
    """在 schema 变更前生成保留全部数据库内容的本机私有回滚快照。"""

    source_database = database_path.resolve()
    normalized_migration_id = migration_id.strip()
    if (
        not normalized_migration_id
        or len(normalized_migration_id) > 80
        or any(character not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-" for character in normalized_migration_id)
    ):
        raise BackupError("迁移标识无效，无法创建回滚快照。")
    if not source_database.is_file():
        raise BackupError("当前 SQLite 数据库不存在，无法创建迁移回滚快照。")

    destination_dir = (backup_dir or (source_database.parent / "backups" / "migrations")).resolve()
    destination_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(destination_dir, 0o700)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output_name = f"pre_{normalized_migration_id}_{timestamp}_{uuid.uuid4().hex[:8]}.zip"
    output_path = destination_dir / output_name
    temp_archive = destination_dir / f".{output_name}.{uuid.uuid4().hex}.tmp"

    try:
        with tempfile.TemporaryDirectory(prefix="mojing-migration-backup-") as temp_root_value:
            temp_root = Path(temp_root_value)
            snapshot_path = temp_root / "app.db"
            _snapshot_sqlite_database(
                source_database,
                snapshot_path,
                require_foreign_keys=False,
            )
            database_metadata = _database_metadata(snapshot_path)
            database_size = snapshot_path.stat().st_size
            database_hash = _hash_file(snapshot_path)
            manifest = {
                "format": ROLLBACK_FORMAT,
                "format_version": ROLLBACK_FORMAT_VERSION,
                "created_at": datetime.now(timezone.utc).isoformat(),
                "migration_id": normalized_migration_id,
                "contains_private_content": True,
                "contains_access_credentials": True,
                "database": {
                    "path": ROLLBACK_DATABASE_ARCHIVE_PATH,
                    "size": database_size,
                    "sha256": database_hash,
                    **database_metadata,
                },
            }
            manifest_path = temp_root / MANIFEST_NAME
            manifest_path.write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True),
                encoding="utf-8",
            )
            with zipfile.ZipFile(temp_archive, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
                archive.write(manifest_path, MANIFEST_NAME)
                archive.write(snapshot_path, ROLLBACK_DATABASE_ARCHIVE_PATH)

        validate_database_rollback_snapshot(temp_archive, expected_migration_id=normalized_migration_id)
        with temp_archive.open("rb+") as handle:
            os.fsync(handle.fileno())
        # 同目录硬链接发布既保持原子可见，又拒绝覆盖任何同名既有回滚点。
        os.link(temp_archive, output_path)
        os.chmod(output_path, 0o600)
        temp_archive.unlink()
        return output_path
    except BackupError:
        raise
    except (OSError, sqlite3.Error, zipfile.BadZipFile, json.JSONDecodeError) as exc:
        raise BackupError("创建迁移回滚快照失败，请确认本机磁盘空间和文件权限后重试。") from exc
    finally:
        if temp_archive.exists():
            temp_archive.unlink()


def validate_database_rollback_snapshot(
    archive_path: Path,
    *,
    expected_migration_id: str | None = None,
) -> dict[str, Any]:
    """复读迁移回滚快照，确认清单、哈希与数据库完整性。"""

    try:
        with zipfile.ZipFile(archive_path, "r") as archive:
            infos = archive.infolist()
            if len(infos) != 2:
                raise BackupError("迁移回滚快照文件数量无效。")
            names = [info.filename for info in infos]
            if set(names) != {MANIFEST_NAME, ROLLBACK_DATABASE_ARCHIVE_PATH} or len(names) != len(set(names)):
                raise BackupError("迁移回滚快照结构无效。")
            for info in infos:
                _validate_archive_info(info)
            manifest_info = archive.getinfo(MANIFEST_NAME)
            if manifest_info.file_size > MAX_MANIFEST_BYTES:
                raise BackupError("迁移回滚快照清单异常过大。")
            try:
                manifest = json.loads(archive.read(MANIFEST_NAME).decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise BackupError("迁移回滚快照清单无效。") from exc
            if manifest.get("format") != ROLLBACK_FORMAT or manifest.get("format_version") != ROLLBACK_FORMAT_VERSION:
                raise BackupError("迁移回滚快照格式不受支持。")
            migration_id = manifest.get("migration_id")
            if not isinstance(migration_id, str) or (expected_migration_id and migration_id != expected_migration_id):
                raise BackupError("迁移回滚快照的迁移标识不匹配。")
            database_row = manifest.get("database")
            if not isinstance(database_row, dict) or database_row.get("path") != ROLLBACK_DATABASE_ARCHIVE_PATH:
                raise BackupError("迁移回滚快照缺少数据库清单。")
            database_info = archive.getinfo(ROLLBACK_DATABASE_ARCHIVE_PATH)
            if database_info.file_size != database_row.get("size"):
                raise BackupError("迁移回滚数据库大小校验失败。")
            if _hash_archive_member(archive, database_info) != database_row.get("sha256"):
                raise BackupError("迁移回滚数据库哈希校验失败。")

            with tempfile.TemporaryDirectory(prefix="mojing-migration-check-") as temp_root_value:
                database_copy = Path(temp_root_value) / "app.db"
                with archive.open(ROLLBACK_DATABASE_ARCHIVE_PATH, "r") as source, database_copy.open("wb") as target:
                    shutil.copyfileobj(source, target, length=1024 * 1024)
                _assert_sqlite_database_is_valid(
                    database_copy,
                    require_foreign_keys=False,
                )
                database_metadata = _database_metadata(database_copy)
            if database_metadata.get("schema_fingerprint") != database_row.get("schema_fingerprint"):
                raise BackupError("迁移回滚数据库结构指纹不匹配。")
            if database_metadata.get("table_count") != database_row.get("table_count"):
                raise BackupError("迁移回滚数据库表数量不匹配。")
            if database_metadata.get("foreign_key_violation_count") != database_row.get(
                "foreign_key_violation_count"
            ):
                raise BackupError("迁移回滚数据库外键状态不匹配。")
            return {
                "format": manifest["format"],
                "format_version": manifest["format_version"],
                "migration_id": migration_id,
                "database_check": "ok",
                "schema_fingerprint": database_metadata["schema_fingerprint"],
            }
    except BackupError:
        raise
    except (OSError, zipfile.BadZipFile) as exc:
        raise BackupError("无法读取迁移回滚快照。") from exc


def build_portable_project_backup(
    database_path: Path,
    *,
    storage_dir: Path = STORAGE_DIR,
    backup_dir: Path | None = None,
    output_name: str | None = None,
) -> Path:
    """生成不含访问密钥的可迁移备份，并在原子替换前完成自校验。"""

    source_database = database_path.resolve()
    source_storage = storage_dir.resolve()
    destination_dir = (backup_dir or (source_storage / "backups")).resolve()
    if not source_database.is_file():
        raise BackupError("当前 SQLite 数据库不存在，无法生成备份。")

    destination_dir.mkdir(parents=True, exist_ok=True)
    if output_name is None:
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        output_name = f"mojing_portable_{timestamp}_{uuid.uuid4().hex[:8]}.zip"
    if Path(output_name).name != output_name or not output_name.lower().endswith(".zip"):
        raise BackupError("备份文件名无效。")

    output_path = destination_dir / output_name
    temp_archive = destination_dir / f".{output_name}.{uuid.uuid4().hex}.tmp"

    try:
        with tempfile.TemporaryDirectory(prefix="mojing-backup-") as temp_root_value:
            staging_root = Path(temp_root_value) / "archive"
            staged_storage = staging_root / "storage"
            staged_storage.mkdir(parents=True, exist_ok=True)

            staged_database = staged_storage / "app.db"
            _snapshot_sqlite_database(source_database, staged_database)
            redacted_value_count = _redact_database_secrets(staged_database)
            database_metadata = _database_metadata(staged_database)

            excluded_paths: list[str] = []
            for source_path in _iter_storage_files(source_storage):
                relative = source_path.relative_to(source_storage)
                archive_relative = PurePosixPath("storage", *relative.parts).as_posix()
                if _should_exclude_storage_file(source_path, relative, source_database):
                    excluded_paths.append(archive_relative)
                    continue

                destination = staged_storage / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                if relative.as_posix().lower() == "api_channels.json":
                    redacted_value_count += _copy_sanitized_json(source_path, destination)
                else:
                    _copy_stable_file(source_path, destination)

            payload_files = _describe_payload_files(staging_root)
            manifest = {
                "format": BACKUP_FORMAT,
                "format_version": BACKUP_FORMAT_VERSION,
                "created_at": datetime.now(timezone.utc).isoformat(),
                "backup_kind": "portable_redacted",
                "contains_private_content": True,
                "contains_access_credentials": False,
                "database": DATABASE_ARCHIVE_PATH,
                "database_metadata": database_metadata,
                "redacted_value_count": redacted_value_count,
                "excluded_rules": [
                    "storage/{backups,conversations,exports,temp}/**",
                    "storage/.fernet_key",
                    "storage/*.db",
                    "storage/*.db-wal",
                    "storage/*.db-shm",
                    "storage/*.sqlite",
                    "storage/*.sqlite3",
                ],
                "excluded_paths": sorted(excluded_paths),
                "files": payload_files,
            }
            (staging_root / MANIFEST_NAME).write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True),
                encoding="utf-8",
            )

            with zipfile.ZipFile(temp_archive, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
                for file_path in sorted(path for path in staging_root.rglob("*") if path.is_file()):
                    archive.write(file_path, file_path.relative_to(staging_root).as_posix())

        validate_portable_project_backup(temp_archive)
        with temp_archive.open("rb+") as handle:
            os.fsync(handle.fileno())
        os.replace(temp_archive, output_path)
        return output_path
    except BackupError:
        raise
    except (OSError, sqlite3.Error, zipfile.BadZipFile, json.JSONDecodeError) as exc:
        raise BackupError("生成备份失败，请确认本机磁盘空间和文件权限后重试。") from exc
    finally:
        if temp_archive.exists():
            temp_archive.unlink()


def validate_portable_project_backup(archive_path: Path) -> dict[str, Any]:
    """只读预检备份结构、逐文件哈希与 SQLite 完整性，不接触正式数据。"""

    try:
        with zipfile.ZipFile(archive_path, "r") as archive:
            infos = archive.infolist()
            if len(infos) > MAX_ARCHIVE_FILE_COUNT:
                raise BackupError("备份文件数量超过安全预检上限。")
            names = [item.filename for item in infos]
            if len(names) != len(set(names)):
                raise BackupError("备份包含重复路径。")
            normalized_names = {unicodedata.normalize("NFC", name).casefold() for name in names}
            if len(names) != len(normalized_names):
                raise BackupError("备份包含仅大小写不同的冲突路径。")
            total_declared_bytes = 0
            for info in infos:
                _validate_archive_info(info)
                total_declared_bytes += info.file_size
                if total_declared_bytes > MAX_ARCHIVE_UNCOMPRESSED_BYTES:
                    raise BackupError("备份解压后大小超过安全预检上限。")
            if MANIFEST_NAME not in names:
                raise BackupError("备份缺少 manifest.json。")

            manifest_info = archive.getinfo(MANIFEST_NAME)
            if manifest_info.file_size > MAX_MANIFEST_BYTES:
                raise BackupError("备份清单异常过大。")
            try:
                manifest = json.loads(archive.read(MANIFEST_NAME).decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise BackupError("备份清单不是有效 UTF-8 JSON。") from exc

            if manifest.get("format") != BACKUP_FORMAT:
                raise BackupError("不是受支持的墨境可迁移备份。")
            if manifest.get("format_version") != BACKUP_FORMAT_VERSION:
                raise BackupError("备份格式版本暂不受支持。")
            if manifest.get("backup_kind") != "portable_redacted":
                raise BackupError("备份类型与当前预检器不匹配。")
            if manifest.get("contains_access_credentials") is not False:
                raise BackupError("备份未声明移除访问凭据。")
            database_metadata = manifest.get("database_metadata")
            if not isinstance(database_metadata, dict):
                raise BackupError("备份缺少数据库结构指纹。")

            file_rows = manifest.get("files")
            if not isinstance(file_rows, list):
                raise BackupError("备份清单的 files 字段无效。")

            expected_names = {MANIFEST_NAME}
            listed_names: set[str] = set()
            total_bytes = 0
            for row in file_rows:
                if not isinstance(row, dict):
                    raise BackupError("备份清单包含无效文件项。")
                name = row.get("path")
                expected_size = row.get("size")
                expected_hash = row.get("sha256")
                if not isinstance(name, str) or not isinstance(expected_size, int) or not isinstance(expected_hash, str):
                    raise BackupError("备份清单文件项字段不完整。")
                _validate_archive_path(name)
                if name in listed_names:
                    raise BackupError("备份清单包含重复文件项。")
                listed_names.add(name)
                expected_names.add(name)

                try:
                    info = archive.getinfo(name)
                except KeyError as exc:
                    raise BackupError(f"备份缺少清单中的文件：{name}") from exc
                if info.file_size != expected_size:
                    raise BackupError(f"文件大小校验失败：{name}")
                actual_hash = _hash_archive_member(archive, info)
                if actual_hash != expected_hash:
                    raise BackupError(f"文件哈希校验失败：{name}")
                total_bytes += info.file_size

            if set(names) != expected_names:
                raise BackupError("备份包含未写入清单的额外文件。")
            if DATABASE_ARCHIVE_PATH not in listed_names:
                raise BackupError("备份缺少一致性数据库快照。")
            if any(PurePosixPath(name).name in _SENSITIVE_FILENAMES for name in listed_names):
                raise BackupError("可迁移备份包含本机加密密钥。")

            with tempfile.TemporaryDirectory(prefix="mojing-backup-check-") as temp_root_value:
                database_copy = Path(temp_root_value) / "app.db"
                with archive.open(DATABASE_ARCHIVE_PATH, "r") as source, database_copy.open("wb") as target:
                    shutil.copyfileobj(source, target, length=1024 * 1024)
                _assert_sqlite_database_is_valid(database_copy)
                _assert_database_credentials_are_redacted(database_copy)
                actual_database_metadata = _database_metadata(database_copy)
                if actual_database_metadata != database_metadata:
                    raise BackupError("数据库结构指纹与备份清单不一致。")

            return {
                "format": manifest["format"],
                "format_version": manifest["format_version"],
                "created_at": manifest.get("created_at"),
                "backup_kind": manifest["backup_kind"],
                "contains_private_content": bool(manifest.get("contains_private_content", True)),
                "contains_access_credentials": False,
                "file_count": len(file_rows),
                "total_bytes": total_bytes,
                "database_check": "ok",
                "schema_fingerprint": database_metadata.get("schema_fingerprint"),
            }
    except BackupError:
        raise
    except (OSError, zipfile.BadZipFile) as exc:
        raise BackupError("无法读取备份，请确认文件完整且未被其他程序占用。") from exc


def _snapshot_sqlite_database(
    source_path: Path,
    destination_path: Path,
    *,
    require_foreign_keys: bool = True,
) -> None:
    destination_path.parent.mkdir(parents=True, exist_ok=True)
    source_uri = f"{source_path.resolve().as_uri()}?mode=ro"
    with closing(sqlite3.connect(source_uri, uri=True)) as source, closing(
        sqlite3.connect(destination_path)
    ) as destination:
        source.backup(destination)
    _assert_sqlite_database_is_valid(
        destination_path,
        require_foreign_keys=require_foreign_keys,
    )


def _assert_sqlite_database_is_valid(
    database_path: Path,
    *,
    require_foreign_keys: bool = True,
) -> None:
    database_uri = f"{database_path.resolve().as_uri()}?mode=ro"
    with closing(sqlite3.connect(database_uri, uri=True)) as connection:
        result = connection.execute("PRAGMA quick_check").fetchone()
    if result is None or result[0] != "ok":
        detail = result[0] if result else "无结果"
        raise BackupError(f"SQLite 完整性检查失败：{detail}")
    with closing(sqlite3.connect(database_uri, uri=True)) as connection:
        foreign_key_failure = connection.execute("PRAGMA foreign_key_check").fetchone()
    if require_foreign_keys and foreign_key_failure is not None:
        raise BackupError(f"SQLite 外键检查失败：{foreign_key_failure[0]}")


def _database_metadata(database_path: Path) -> dict[str, Any]:
    database_uri = f"{database_path.resolve().as_uri()}?mode=ro"
    with closing(sqlite3.connect(database_uri, uri=True)) as connection:
        rows = connection.execute(
            """
            SELECT type, name, tbl_name, COALESCE(sql, '')
            FROM sqlite_master
            WHERE name NOT LIKE 'sqlite_%'
            ORDER BY type, name, tbl_name
            """
        ).fetchall()
        foreign_key_violation_count = sum(
            1 for _row in connection.execute("PRAGMA foreign_key_check")
        )
    encoded_schema = json.dumps(rows, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return {
        "schema_fingerprint": hashlib.sha256(encoded_schema).hexdigest(),
        "table_count": sum(1 for row in rows if row[0] == "table"),
        "foreign_key_violation_count": foreign_key_violation_count,
    }


def _redact_database_secrets(database_path: Path) -> int:
    redacted_count = 0
    with closing(sqlite3.connect(database_path)) as connection:
        connection.execute("PRAGMA secure_delete = ON")
        character_columns = _table_columns(connection, "characters")
        for column in ("api_key", "voice_api_key", "image_gen_api_key"):
            if column not in character_columns:
                continue
            cursor = connection.execute(
                f'UPDATE "characters" SET "{column}" = \'\' WHERE COALESCE("{column}", \'\') <> \'\''
            )
            redacted_count += max(cursor.rowcount, 0)

        if {"id", "value_json"}.issubset(_table_columns(connection, "app_settings")):
            rows = connection.execute('SELECT "id", "value_json" FROM "app_settings"').fetchall()
            for row_id, raw_value in rows:
                if raw_value is None:
                    continue
                try:
                    value = json.loads(raw_value) if isinstance(raw_value, str) else raw_value
                except json.JSONDecodeError as exc:
                    raise BackupError("本机设置包含无效 JSON，无法确认密钥已移除。") from exc
                sanitized, count = _redact_sensitive_values(value)
                if count:
                    connection.execute(
                        'UPDATE "app_settings" SET "value_json" = ? WHERE "id" = ?',
                        (json.dumps(sanitized, ensure_ascii=False), row_id),
                    )
                    redacted_count += count

        connection.commit()
        connection.execute("VACUUM")
    _assert_database_credentials_are_redacted(database_path)
    return redacted_count


def _assert_database_credentials_are_redacted(database_path: Path) -> None:
    database_uri = f"{database_path.resolve().as_uri()}?mode=ro"
    with closing(sqlite3.connect(database_uri, uri=True)) as connection:
        character_columns = _table_columns(connection, "characters")
        for column in ("api_key", "voice_api_key", "image_gen_api_key"):
            if column not in character_columns:
                continue
            count = connection.execute(
                f'SELECT COUNT(*) FROM "characters" WHERE COALESCE("{column}", \'\') <> \'\''
            ).fetchone()[0]
            if count:
                raise BackupError(f"数据库字段 {column} 仍包含访问凭据。")

        if {"id", "value_json"}.issubset(_table_columns(connection, "app_settings")):
            for (raw_value,) in connection.execute('SELECT "value_json" FROM "app_settings"'):
                if raw_value is None:
                    continue
                try:
                    value = json.loads(raw_value) if isinstance(raw_value, str) else raw_value
                except json.JSONDecodeError as exc:
                    raise BackupError("备份设置 JSON 无法解析。") from exc
                _, count = _redact_sensitive_values(value)
                if count:
                    raise BackupError("备份设置仍包含访问凭据。")


def _table_columns(connection: sqlite3.Connection, table_name: str) -> set[str]:
    return {row[1] for row in connection.execute(f'PRAGMA table_info("{table_name}")')}


def _redact_sensitive_values(value: Any) -> tuple[Any, int]:
    if isinstance(value, dict):
        result: dict[str, Any] = {}
        count = 0
        for key, child in value.items():
            normalized_key = str(key).strip().lower().replace("-", "_")
            if normalized_key in _SENSITIVE_JSON_KEYS:
                if child not in (None, ""):
                    count += 1
                result[key] = ""
            else:
                result[key], nested_count = _redact_sensitive_values(child)
                count += nested_count
        return result, count
    if isinstance(value, list):
        result_list = []
        count = 0
        for child in value:
            sanitized, nested_count = _redact_sensitive_values(child)
            result_list.append(sanitized)
            count += nested_count
        return result_list, count
    return value, 0


def _iter_storage_files(storage_dir: Path):
    if not storage_dir.exists():
        return
    for root, dir_names, file_names in os.walk(storage_dir, followlinks=False):
        root_path = Path(root)
        for directory_name in dir_names:
            directory_path = root_path / directory_name
            if directory_path.is_symlink():
                relative = directory_path.relative_to(storage_dir).as_posix()
                raise BackupError(f"存储目录包含符号链接，已停止以避免越界读取：{relative}")
        if root_path == storage_dir:
            dir_names[:] = sorted(name for name in dir_names if name not in _EXCLUDED_TOP_LEVEL)
        else:
            dir_names.sort()
        for file_name in sorted(file_names):
            yield root_path / file_name


def _should_exclude_storage_file(source_path: Path, relative: Path, source_database: Path) -> bool:
    if relative.parts and relative.parts[0] in _EXCLUDED_TOP_LEVEL:
        return True
    if source_path.is_symlink():
        raise BackupError(f"存储目录包含符号链接，已停止以避免越界读取：{relative.as_posix()}")
    if relative.name in _SENSITIVE_FILENAMES:
        return True
    if source_path.resolve() == source_database:
        return True
    lower_name = relative.name.lower()
    if lower_name.endswith((".db", ".db-wal", ".db-shm", ".sqlite", ".sqlite3")):
        return True
    return False


def _copy_stable_file(source_path: Path, destination_path: Path) -> None:
    before = source_path.stat()
    shutil.copy2(source_path, destination_path)
    after = source_path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise BackupError(f"备份期间文件发生变化，请稍后重试：{source_path.name}")


def _copy_sanitized_json(source_path: Path, destination_path: Path) -> int:
    before = source_path.stat()
    try:
        value = json.loads(source_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BackupError(f"无法安全处理渠道配置 {source_path.name}。") from exc
    after = source_path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise BackupError(f"备份期间文件发生变化，请稍后重试：{source_path.name}")
    sanitized, count = _redact_sensitive_values(value)
    destination_path.write_text(json.dumps(sanitized, ensure_ascii=False, indent=2), encoding="utf-8")
    return count


def _describe_payload_files(staging_root: Path) -> list[dict[str, Any]]:
    rows = []
    for file_path in sorted(path for path in staging_root.rglob("*") if path.is_file()):
        relative = file_path.relative_to(staging_root).as_posix()
        rows.append(
            {
                "path": relative,
                "size": file_path.stat().st_size,
                "sha256": _hash_file(file_path),
            }
        )
    return rows


def _hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _hash_archive_member(archive: zipfile.ZipFile, info: zipfile.ZipInfo) -> str:
    digest = hashlib.sha256()
    with archive.open(info, "r") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _validate_archive_path(name: str) -> None:
    path = PurePosixPath(name)
    if not name or "\\" in name or path.is_absolute() or any(part in ("", ".", "..") for part in path.parts):
        raise BackupError(f"备份包含不安全路径：{name!r}")
    if path.as_posix() != name:
        raise BackupError(f"备份包含非规范路径：{name!r}")
    if path.parts and ":" in path.parts[0]:
        raise BackupError(f"备份包含 Windows 盘符路径：{name!r}")
    for part in path.parts:
        if part != part.rstrip(" ."):
            raise BackupError(f"备份包含 Windows 尾随空格或句点路径：{name!r}")
        stem = part.rstrip(" .").split(".", 1)[0].casefold()
        if stem in _WINDOWS_DEVICE_NAMES:
            raise BackupError(f"备份包含 Windows 保留设备名：{name!r}")


def _validate_archive_info(info: zipfile.ZipInfo) -> None:
    _validate_archive_path(info.filename)
    mode = info.external_attr >> 16
    if mode and stat.S_ISLNK(mode):
        raise BackupError(f"备份包含符号链接：{info.filename}")
    if info.file_size > MAX_SINGLE_FILE_BYTES:
        raise BackupError(f"备份文件超过单文件预检上限：{info.filename}")
    if (
        info.file_size > 100 * 1024 * 1024
        and info.compress_size > 0
        and info.file_size / info.compress_size > MAX_SUSPICIOUS_COMPRESSION_RATIO
    ):
        raise BackupError(f"备份文件压缩比异常：{info.filename}")

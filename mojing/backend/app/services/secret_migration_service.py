from __future__ import annotations

import json
import os
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import AppSettingModel, CharacterModel
from . import channel_service
from .backup_service import (
    BackupError,
    build_database_rollback_snapshot,
    build_private_file_rollback_copy,
)
from .crypto_service import (
    decrypt_api_key,
    is_encrypted_secret,
    is_secret_mask,
    prepare_secret_for_storage,
)
from .system_config_service import (
    LOCAL_CONFIG_KEY,
    LOCAL_CONFIG_SECRET_FIELDS,
    VOICE_SERVICE_CONFIG_KEY,
    VOICE_SERVICE_SECRET_FIELDS,
)


SECRET_MIGRATION_ID = "20260829_local_secret_encryption"
CHARACTER_SECRET_FIELDS = ("api_key", "voice_api_key", "image_gen_api_key")


class SecretMigrationError(RuntimeError):
    """旧明文访问密钥无法安全迁移时抛出。"""


@dataclass(frozen=True)
class SecretMigrationResult:
    changed: bool
    migrated_value_count: int = 0
    database_snapshot_path: Path | None = None
    channel_snapshot_path: Path | None = None
    journal_path: Path | None = None


def migrate_persisted_secrets(db: Session) -> SecretMigrationResult:
    """把角色、系统配置和渠道 JSON 的旧明文密钥幂等升级为 enc_v1。"""

    with channel_service.channel_storage_lock():
        return _migrate_persisted_secrets_locked(db)


def _migrate_persisted_secrets_locked(db: Session) -> SecretMigrationResult:
    journal_path = (
        channel_service.CHANNEL_FILE.parent
        / "backups"
        / "migrations"
        / "secret_migration_state.json"
    )
    journal = _load_migration_journal(journal_path)

    characters = list(db.scalars(select(CharacterModel)))
    setting_rows = {
        row.key: row
        for row in db.scalars(
            select(AppSettingModel).where(
                AppSettingModel.key.in_([LOCAL_CONFIG_KEY, VOICE_SERVICE_CONFIG_KEY])
            )
        )
    }
    channels = channel_service._load_channels_raw()

    database_values: list[str] = []
    for character in characters:
        database_values.extend(str(getattr(character, field) or "") for field in CHARACTER_SECRET_FIELDS)
    for key, fields in (
        (LOCAL_CONFIG_KEY, LOCAL_CONFIG_SECRET_FIELDS),
        (VOICE_SERVICE_CONFIG_KEY, VOICE_SERVICE_SECRET_FIELDS),
    ):
        row = setting_rows.get(key)
        value = row.value_json if row and isinstance(row.value_json, dict) else {}
        database_values.extend(str(value.get(field) or "") for field in fields)
    channel_values = [str(channel.get("api_key") or "") for channel in channels]
    all_values = [value for value in (*database_values, *channel_values) if value]

    if any(is_secret_mask(value) for value in all_values):
        raise SecretMigrationError("持久化数据中发现密钥掩码，已停止迁移以避免把掩码当作真实密钥。")

    # 只要已有密文存在，先证明当前密钥可解；缺失或错误时不得生成新密钥掩盖问题。
    for value in all_values:
        if is_encrypted_secret(value):
            decrypt_api_key(value)

    database_plaintext = any(value and not is_encrypted_secret(value) for value in database_values)
    channel_plaintext = any(value and not is_encrypted_secret(value) for value in channel_values)
    if not database_plaintext and not channel_plaintext:
        if journal is not None and journal.get("status") != "complete":
            _write_migration_journal(
                journal_path,
                status="complete",
                database_stage="complete",
                channel_stage="complete",
            )
        return SecretMigrationResult(changed=False, journal_path=journal_path if journal else None)

    database_snapshot: Path | None = None
    channel_snapshot: Path | None = None
    migrated_count = 0
    stage = "preflight"
    try:
        _write_migration_journal(
            journal_path,
            status="started",
            database_stage="pending" if database_plaintext else "not_needed",
            channel_stage="pending" if channel_plaintext else "not_needed",
        )
        if database_plaintext:
            connection = db.connection()
            driver_connection = connection.connection.driver_connection
            if not bool(getattr(driver_connection, "in_transaction", False)):
                connection.exec_driver_sql("BEGIN IMMEDIATE")
            database = connection.engine.url.database
            if not database or database == ":memory:":
                raise SecretMigrationError("内存数据库不支持旧明文密钥迁移。")
            database_snapshot = build_database_rollback_snapshot(
                Path(database).resolve(),
                SECRET_MIGRATION_ID,
            )

        if channel_plaintext and channel_service.CHANNEL_FILE.is_file():
            channel_snapshot = build_private_file_rollback_copy(
                channel_service.CHANNEL_FILE,
                SECRET_MIGRATION_ID,
                backup_dir=channel_service.CHANNEL_FILE.parent / "backups" / "migrations",
            )

        for character in characters:
            for field in CHARACTER_SECRET_FIELDS:
                value = str(getattr(character, field) or "")
                if value and not is_encrypted_secret(value):
                    setattr(character, field, prepare_secret_for_storage(value))
                    migrated_count += 1

        for key, fields in (
            (LOCAL_CONFIG_KEY, LOCAL_CONFIG_SECRET_FIELDS),
            (VOICE_SERVICE_CONFIG_KEY, VOICE_SERVICE_SECRET_FIELDS),
        ):
            row = setting_rows.get(key)
            if row is None or not isinstance(row.value_json, dict):
                continue
            updated = row.value_json.copy()
            changed = False
            for field in fields:
                value = str(updated.get(field) or "")
                if value and not is_encrypted_secret(value):
                    updated[field] = prepare_secret_for_storage(value)
                    migrated_count += 1
                    changed = True
            if changed:
                row.value_json = updated

        if database_plaintext:
            db.commit()
            stage = "database_committed"
            _write_migration_journal(
                journal_path,
                status="database_committed",
                database_stage="complete",
                channel_stage="pending" if channel_plaintext else "not_needed",
                database_snapshot=database_snapshot.name if database_snapshot else None,
                channel_snapshot=channel_snapshot.name if channel_snapshot else None,
            )

        if channel_plaintext:
            updated_channels = [channel.copy() for channel in channels]
            for channel in updated_channels:
                value = str(channel.get("api_key") or "")
                if value and not is_encrypted_secret(value):
                    channel["api_key"] = prepare_secret_for_storage(value)
                    migrated_count += 1
            channel_service._save_channels(updated_channels)
        stage = "complete"
        _write_migration_journal(
            journal_path,
            status="complete",
            database_stage="complete" if database_plaintext else "not_needed",
            channel_stage="complete" if channel_plaintext else "not_needed",
            database_snapshot=database_snapshot.name if database_snapshot else None,
            channel_snapshot=channel_snapshot.name if channel_snapshot else None,
        )
    except Exception as exc:
        db.rollback()
        if stage == "database_committed":
            raise SecretMigrationError(
                "数据库密钥阶段已完成，但渠道配置阶段失败；回滚点已保留，下次启动会幂等续跑。"
            ) from exc
        if isinstance(exc, SecretMigrationError):
            raise
        if isinstance(exc, BackupError):
            raise SecretMigrationError("无法创建旧明文密钥迁移回滚点，原数据未修改。") from exc
        raise SecretMigrationError("旧明文访问密钥迁移失败，应用已停止启动。") from exc

    return SecretMigrationResult(
        changed=True,
        migrated_value_count=migrated_count,
        database_snapshot_path=database_snapshot,
        channel_snapshot_path=channel_snapshot,
        journal_path=journal_path,
    )


def _load_migration_journal(path: Path) -> dict | None:
    if not path.exists():
        return None
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SecretMigrationError("密钥迁移状态文件损坏，已停止以避免覆盖恢复证据。") from exc
    if (
        not isinstance(value, dict)
        or value.get("format") != "mojing-secret-migration-state"
        or value.get("migration_id") != SECRET_MIGRATION_ID
    ):
        raise SecretMigrationError("密钥迁移状态文件格式无效，已停止以避免覆盖恢复证据。")
    return value


def _write_migration_journal(
    path: Path,
    *,
    status: str,
    database_stage: str,
    channel_stage: str,
    database_snapshot: str | None = None,
    channel_snapshot: str | None = None,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    os.chmod(path.parent, 0o700)
    value = {
        "format": "mojing-secret-migration-state",
        "format_version": 1,
        "migration_id": SECRET_MIGRATION_ID,
        "status": status,
        "database_stage": database_stage,
        "channel_stage": channel_stage,
        "database_snapshot": database_snapshot,
        "channel_snapshot": channel_snapshot,
        "updated_at": datetime.now(timezone.utc).isoformat(),
    }
    temp_path = path.parent / f".{path.name}.{uuid.uuid4().hex}.tmp"
    try:
        with temp_path.open("w", encoding="utf-8", newline="\n") as handle:
            json.dump(value, handle, ensure_ascii=False, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp_path, path)
        os.chmod(path, 0o600)
    except OSError as exc:
        raise SecretMigrationError("无法写入密钥迁移状态，已停止迁移。") from exc
    finally:
        if temp_path.exists():
            temp_path.unlink()

from __future__ import annotations

import json
import sqlite3
import zipfile
from contextlib import closing
from pathlib import Path

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session

from backend.app import models  # noqa: F401
from backend.app.config import settings
from backend.app.database import Base
from backend.app.models import AppSettingModel, CharacterModel
from backend.app.routes.characters import _character_read, create_character, update_character
from backend.app.routes.system import _resolve_probe_key
from backend.app.schemas import CharacterCreate, CharacterUpdate, ProbePublicApiRequest
from backend.app.services import channel_service, crypto_service
from backend.app.services.backup_service import ROLLBACK_DATABASE_ARCHIVE_PATH
from backend.app.services.public_api_probe import _safe_probe_error
from backend.app.services.secret_migration_service import SecretMigrationError, migrate_persisted_secrets
from backend.app.services.system_config_service import (
    LOCAL_CONFIG_KEY,
    VOICE_SERVICE_CONFIG_KEY,
    get_local_config,
    get_voice_service_config,
    mask_local_config_for_api,
    mask_voice_service_config_for_api,
    set_local_config,
    set_voice_service_config,
)


@pytest.fixture
def isolated_secret_storage(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    storage_dir = tmp_path / "storage"
    storage_dir.mkdir()
    monkeypatch.setattr(settings, "encryption_key", "")
    monkeypatch.setattr(crypto_service, "STORAGE_DIR", storage_dir)
    monkeypatch.setattr(channel_service, "CHANNEL_FILE", storage_dir / "api_channels.json")
    crypto_service._reset_fernet_cache_for_tests()
    yield storage_dir
    crypto_service._reset_fernet_cache_for_tests()


def _engine_for(path: Path):
    engine = create_engine(
        f"sqlite:///{path.as_posix()}",
        connect_args={"check_same_thread": False},
    )
    Base.metadata.create_all(engine)
    return engine


def test_config_and_channel_storage_encrypts_masks_and_preserves_saved_values(
    tmp_path: Path,
    isolated_secret_storage: Path,
) -> None:
    engine = _engine_for(tmp_path / "app.db")
    with Session(engine) as session:
        local = set_local_config(
            session,
            {
                "public_text_api_key": "text-secret",
                "public_image_api_key": "image-secret",
                "public_voice_api_key": "voice-secret",
                "public_text_base_url": "https://example.test/v1",
            },
        )
        voice = set_voice_service_config(
            session,
            {"external_api_key": "external-secret", "enabled": True},
        )
        raw_local = session.scalar(
            select(AppSettingModel).where(AppSettingModel.key == LOCAL_CONFIG_KEY)
        ).value_json
        raw_voice = session.scalar(
            select(AppSettingModel).where(AppSettingModel.key == VOICE_SERVICE_CONFIG_KEY)
        ).value_json

        assert local["public_text_api_key"] == "text-secret"
        assert voice["external_api_key"] == "external-secret"
        assert all(
            str(raw_local[field]).startswith("enc_v1:")
            for field in (
                "public_text_api_key",
                "public_image_api_key",
                "public_voice_api_key",
            )
        )
        assert str(raw_voice["external_api_key"]).startswith("enc_v1:")
        assert mask_local_config_for_api(get_local_config(session))["public_text_api_key"] == crypto_service.SECRET_MASK
        assert mask_voice_service_config_for_api(get_voice_service_config(session))["external_api_key"] == crypto_service.SECRET_MASK

        preserved_ciphertext = raw_local["public_text_api_key"]
        current = get_local_config(session)
        current["public_text_api_key"] = crypto_service.SECRET_MASK
        set_local_config(session, current)
        reloaded = session.scalar(
            select(AppSettingModel).where(AppSettingModel.key == LOCAL_CONFIG_KEY)
        ).value_json
        assert reloaded["public_text_api_key"] == preserved_ciphertext
        set_local_config(session, {"public_text_api_key": ""})
        assert session.scalar(
            select(AppSettingModel).where(AppSettingModel.key == LOCAL_CONFIG_KEY)
        ).value_json["public_text_api_key"] == preserved_ciphertext

    saved = channel_service.save_channel(
        {
            "id": "text-1",
            "label": "文字渠道",
            "provider": "custom",
            "base_url": "https://example.test/v1",
            "api_key": "channel-secret",
            "model_name": "model",
            "purpose": "text",
            "enabled": True,
        }
    )
    assert saved["api_key"] == crypto_service.SECRET_MASK
    raw_channels = json.loads(channel_service.CHANNEL_FILE.read_text(encoding="utf-8"))
    original_ciphertext = raw_channels[0]["api_key"]
    assert original_ciphertext.startswith("enc_v1:")
    assert channel_service.list_channels()[0]["api_key"] == crypto_service.SECRET_MASK
    assert channel_service._get_default_channel("text")["api_key"] == "channel-secret"

    saved["label"] = "新名称"
    saved["api_key"] = ""
    channel_service.save_channel(saved)
    raw_channels = json.loads(channel_service.CHANNEL_FILE.read_text(encoding="utf-8"))
    assert raw_channels[0]["api_key"] == original_ciphertext
    channel_service.save_channel({"id": "text-1", "clear_api_key": True})
    raw_channels = json.loads(channel_service.CHANNEL_FILE.read_text(encoding="utf-8"))
    assert raw_channels[0]["api_key"] == ""
    engine.dispose()


def test_character_secret_fields_encrypt_and_never_serialize_ciphertext(
    tmp_path: Path,
    isolated_secret_storage: Path,
) -> None:
    engine = _engine_for(tmp_path / "app.db")
    with Session(engine) as session:
        response = create_character(
            CharacterCreate(
                name="测试角色",
                api_key="text-secret",
                voice_api_key="voice-secret",
                image_gen_api_key="image-secret",
            ),
            session,
        )
        assert response.api_key == crypto_service.SECRET_MASK
        assert response.voice_api_key == crypto_service.SECRET_MASK
        assert response.image_gen_api_key == crypto_service.SECRET_MASK

        row = session.get(CharacterModel, response.id)
        assert row is not None
        original_values = {
            field: getattr(row, field)
            for field in ("api_key", "voice_api_key", "image_gen_api_key")
        }
        assert all(str(value).startswith("enc_v1:") for value in original_values.values())
        serialized = _character_read(row).model_dump()
        assert not any("enc_v1:" in str(value) for value in serialized.values())
        assert _resolve_probe_key(
            ProbePublicApiRequest(
                channel="voice",
                api_key=crypto_service.SECRET_MASK,
                character_id=response.id,
            ),
            session,
        ) == "text-secret"

        update_payload = CharacterUpdate(**response.model_dump(exclude={"id", "created_at", "updated_at", "voice_profile", "favorite"}))
        updated = update_character(response.id, update_payload, session)
        assert updated.api_key == crypto_service.SECRET_MASK
        refreshed = session.get(CharacterModel, response.id)
        assert refreshed is not None
        assert {
            field: getattr(refreshed, field)
            for field in ("api_key", "voice_api_key", "image_gen_api_key")
        } == original_values

        update_character(response.id, CharacterUpdate(name="改名"), session)
        refreshed = session.get(CharacterModel, response.id)
        assert refreshed is not None and refreshed.api_key == original_values["api_key"]
        update_character(
            response.id,
            CharacterUpdate(name="改名", api_key="", clear_api_key=True),
            session,
        )
        assert session.get(CharacterModel, response.id).api_key == ""
    engine.dispose()


def test_plaintext_migration_creates_private_rollback_points_and_is_idempotent(
    tmp_path: Path,
    isolated_secret_storage: Path,
) -> None:
    database_path = tmp_path / "app.db"
    engine = _engine_for(database_path)
    with Session(engine) as session:
        session.connection().exec_driver_sql(
            "CREATE TABLE rollback_parent (id INTEGER PRIMARY KEY)"
        )
        session.connection().exec_driver_sql(
            "CREATE TABLE rollback_child ("
            "id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES rollback_parent(id))"
        )
        session.connection().exec_driver_sql(
            "INSERT INTO rollback_child(id, parent_id) VALUES (1, 999)"
        )
        session.add(
            CharacterModel(
                name="旧角色",
                api_key="old-text",
                voice_api_key="old-voice",
                image_gen_api_key="old-image",
            )
        )
        session.add(
            AppSettingModel(
                key=LOCAL_CONFIG_KEY,
                value_json={"public_text_api_key": "old-public"},
            )
        )
        session.add(
            AppSettingModel(
                key=VOICE_SERVICE_CONFIG_KEY,
                value_json={"external_api_key": "old-external"},
            )
        )
        session.commit()

        channel_service.CHANNEL_FILE.write_text(
            json.dumps(
                [
                    {
                        "id": "legacy",
                        "label": "旧渠道",
                        "purpose": "text",
                        "enabled": True,
                        "api_key": "old-channel",
                    }
                ],
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        result = migrate_persisted_secrets(session)

        assert result.changed is True
        assert result.migrated_value_count == 6
        assert result.database_snapshot_path is not None and result.database_snapshot_path.is_file()
        assert result.channel_snapshot_path is not None and result.channel_snapshot_path.is_file()
        assert result.journal_path is not None
        assert json.loads(result.journal_path.read_text(encoding="utf-8"))["status"] == "complete"
        assert "old-channel" in result.channel_snapshot_path.read_text(encoding="utf-8")

        with zipfile.ZipFile(result.database_snapshot_path) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            rollback_database = tmp_path / "rollback.db"
            rollback_database.write_bytes(archive.read(ROLLBACK_DATABASE_ARCHIVE_PATH))
        assert manifest["database"]["foreign_key_violation_count"] == 1
        with closing(sqlite3.connect(rollback_database)) as connection:
            rollback_key = connection.execute("SELECT api_key FROM characters").fetchone()[0]
        assert rollback_key == "old-text"

        migrated_character = session.scalar(select(CharacterModel))
        assert migrated_character is not None
        assert migrated_character.api_key.startswith("enc_v1:")
        assert crypto_service.decrypt_api_key(migrated_character.api_key) == "old-text"
        assert channel_service._get_default_channel("text")["api_key"] == "old-channel"

        before = set(result.database_snapshot_path.parent.iterdir())
        channel_before = set(result.channel_snapshot_path.parent.iterdir())
        repeated = migrate_persisted_secrets(session)
        assert repeated.changed is False
        assert set(result.database_snapshot_path.parent.iterdir()) == before
        assert set(result.channel_snapshot_path.parent.iterdir()) == channel_before
    engine.dispose()


def test_missing_or_wrong_local_key_fails_without_generating_replacement(
    isolated_secret_storage: Path,
) -> None:
    with pytest.raises(crypto_service.SecretStorageError, match="掩码"):
        crypto_service.prepare_secret_for_storage(crypto_service.SECRET_MASK)
    safe_error = _safe_probe_error(RuntimeError("Authorization: Bearer should-never-leak"))
    assert "Bearer" not in safe_error and "should-never-leak" not in safe_error

    ciphertext = crypto_service.encrypt_api_key("secret")
    key_file = isolated_secret_storage / ".fernet_key"
    assert key_file.is_file()
    key_file.unlink()
    crypto_service._reset_fernet_cache_for_tests()

    with pytest.raises(crypto_service.SecretKeyUnavailableError):
        crypto_service.decrypt_api_key(ciphertext)
    with pytest.raises(crypto_service.SecretKeyUnavailableError):
        crypto_service.encrypt_api_key("replacement-must-not-be-written")
    assert not key_file.exists()

    key_file.write_text(crypto_service.Fernet.generate_key().decode("ascii"), encoding="utf-8")
    crypto_service._reset_fernet_cache_for_tests()
    with pytest.raises(crypto_service.SecretKeyUnavailableError):
        crypto_service.decrypt_api_key(ciphertext)


def test_channel_failure_after_database_stage_is_explicit_and_resumable(
    tmp_path: Path,
    isolated_secret_storage: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine = _engine_for(tmp_path / "app.db")
    channel_service.CHANNEL_FILE.write_text(
        json.dumps([{"id": "legacy", "purpose": "text", "api_key": "channel-plain"}]),
        encoding="utf-8",
    )
    with Session(engine) as session:
        session.add(CharacterModel(name="旧角色", api_key="database-plain"))
        session.commit()
        real_save = channel_service._save_channels

        def fail_channel_save(_channels) -> None:
            raise OSError("simulated channel failure")

        monkeypatch.setattr(channel_service, "_save_channels", fail_channel_save)
        with pytest.raises(SecretMigrationError, match="数据库密钥阶段已完成"):
            migrate_persisted_secrets(session)

        stored = session.scalar(select(CharacterModel))
        assert stored is not None and stored.api_key.startswith("enc_v1:")
        assert json.loads(channel_service.CHANNEL_FILE.read_text(encoding="utf-8"))[0]["api_key"] == "channel-plain"
        journal_path = (
            channel_service.CHANNEL_FILE.parent
            / "backups"
            / "migrations"
            / "secret_migration_state.json"
        )
        assert json.loads(journal_path.read_text(encoding="utf-8"))["status"] == "database_committed"

        monkeypatch.setattr(channel_service, "_save_channels", real_save)
        resumed = migrate_persisted_secrets(session)
        assert resumed.changed is True
        assert resumed.database_snapshot_path is None
        assert json.loads(journal_path.read_text(encoding="utf-8"))["status"] == "complete"
        assert channel_service._get_default_channel("text")["api_key"] == "channel-plain"
    engine.dispose()

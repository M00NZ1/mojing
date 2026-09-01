from __future__ import annotations

import json
import sqlite3
import zipfile
from contextlib import closing
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from backend.app.database import get_db
from backend.app.routes.sessions import router as sessions_router
from backend.app.services import backup_service


def _create_source_database(path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA journal_mode = WAL")
    connection.execute("PRAGMA wal_autocheckpoint = 0")
    connection.executescript(
        """
        CREATE TABLE characters (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            api_key TEXT NOT NULL DEFAULT '',
            voice_api_key TEXT NOT NULL DEFAULT '',
            image_gen_api_key TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE app_settings (
            id INTEGER PRIMARY KEY,
            key TEXT NOT NULL,
            value_json TEXT NOT NULL
        );
        CREATE TABLE messages (
            id INTEGER PRIMARY KEY,
            content TEXT NOT NULL
        );
        """
    )
    connection.execute(
        "INSERT INTO characters(name, api_key, voice_api_key, image_gen_api_key) VALUES (?, ?, ?, ?)",
        ("测试角色", "enc_v1:text-secret", "voice-secret", "image-secret"),
    )
    connection.execute(
        "INSERT INTO app_settings(key, value_json) VALUES (?, ?)",
        (
            "local_config",
            json.dumps(
                {
                    "public_text_api_key": "public-secret",
                    "max_tokens": 4096,
                    "nested": {"client_secret": "nested-secret", "label": "保留"},
                },
                ensure_ascii=False,
            ),
        ),
    )
    connection.execute("INSERT INTO messages(content) VALUES (?)", ("只存在于已提交 WAL 的剧情",))
    connection.commit()
    return connection


def _extract_database(archive_path: Path, destination: Path) -> None:
    with zipfile.ZipFile(archive_path) as archive:
        with archive.open(backup_service.DATABASE_ARCHIVE_PATH) as source, destination.open("wb") as target:
            target.write(source.read())


def test_portable_backup_snapshots_wal_redacts_credentials_and_validates(tmp_path: Path) -> None:
    storage_dir = tmp_path / "storage"
    storage_dir.mkdir()
    source_database = storage_dir / "app.db"
    source_connection = _create_source_database(source_database)
    try:
        (storage_dir / ".fernet_key").write_text("local-fernet-secret", encoding="utf-8")
        (storage_dir / "api_channels.json").write_text(
            json.dumps(
                [
                    {
                        "id": "channel-1",
                        "api_key": "channel-secret",
                        "base_url": "http://127.0.0.1:11434/v1",
                    }
                ]
            ),
            encoding="utf-8",
        )
        asset_path = storage_dir / "assets" / "custom" / "portrait.txt"
        asset_path.parent.mkdir(parents=True)
        asset_path.write_text("user asset", encoding="utf-8")
        for excluded_dir in ("backups", "conversations", "exports", "temp"):
            path = storage_dir / excluded_dir / "old-private.txt"
            path.parent.mkdir(parents=True)
            path.write_text("do not duplicate", encoding="utf-8")

        archive_path = backup_service.build_portable_project_backup(
            source_database,
            storage_dir=storage_dir,
            backup_dir=tmp_path / "output",
            output_name="portable.zip",
        )

        result = backup_service.validate_portable_project_backup(archive_path)
        assert result["database_check"] == "ok"
        assert result["contains_access_credentials"] is False

        with zipfile.ZipFile(archive_path) as archive:
            names = set(archive.namelist())
            manifest = json.loads(archive.read(backup_service.MANIFEST_NAME))
            channels = json.loads(archive.read("storage/api_channels.json"))
        assert "storage/.fernet_key" not in names
        assert not any(name.startswith("storage/backups/") for name in names)
        assert not any(name.startswith("storage/conversations/") for name in names)
        assert not any(name.startswith("storage/exports/") for name in names)
        assert not any(name.startswith("storage/temp/") for name in names)
        assert "storage/assets/custom/portrait.txt" in names
        assert manifest["format"] == backup_service.BACKUP_FORMAT
        assert manifest["format_version"] == backup_service.BACKUP_FORMAT_VERSION
        assert manifest["redacted_value_count"] >= 6
        assert channels[0]["api_key"] == ""
        assert channels[0]["base_url"] == "http://127.0.0.1:11434/v1"

        extracted_database = tmp_path / "extracted.db"
        _extract_database(archive_path, extracted_database)
        database_bytes = extracted_database.read_bytes()
        for secret in (
            b"text-secret",
            b"voice-secret",
            b"image-secret",
            b"public-secret",
            b"nested-secret",
        ):
            assert secret not in database_bytes
        with closing(sqlite3.connect(extracted_database)) as connection:
            character = connection.execute(
                "SELECT api_key, voice_api_key, image_gen_api_key FROM characters"
            ).fetchone()
            settings = json.loads(connection.execute("SELECT value_json FROM app_settings").fetchone()[0])
            story = connection.execute("SELECT content FROM messages").fetchone()[0]
        assert character == ("", "", "")
        assert settings["public_text_api_key"] == ""
        assert settings["nested"]["client_secret"] == ""
        assert settings["nested"]["label"] == "保留"
        assert settings["max_tokens"] == 4096
        assert story == "只存在于已提交 WAL 的剧情"
    finally:
        source_connection.close()


def test_generation_failure_preserves_existing_backup(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    storage_dir = tmp_path / "storage"
    storage_dir.mkdir()
    source_database = storage_dir / "app.db"
    source_connection = _create_source_database(source_database)
    asset_path = storage_dir / "asset.txt"
    asset_path.write_text("asset", encoding="utf-8")
    output_dir = tmp_path / "output"
    output_dir.mkdir()
    existing_backup = output_dir / "portable.zip"
    existing_backup.write_bytes(b"existing backup")

    def fail_copy(_source: Path, _destination: Path) -> None:
        raise backup_service.BackupError("simulated copy failure")

    monkeypatch.setattr(backup_service, "_copy_stable_file", fail_copy)
    try:
        with pytest.raises(backup_service.BackupError, match="simulated copy failure"):
            backup_service.build_portable_project_backup(
                source_database,
                storage_dir=storage_dir,
                backup_dir=output_dir,
                output_name="portable.zip",
            )
        assert existing_backup.read_bytes() == b"existing backup"
        assert not list(output_dir.glob("*.tmp"))
    finally:
        source_connection.close()


def test_validation_rejects_tampered_payload(tmp_path: Path) -> None:
    storage_dir = tmp_path / "storage"
    storage_dir.mkdir()
    source_database = storage_dir / "app.db"
    source_connection = _create_source_database(source_database)
    (storage_dir / "asset.txt").write_text("original", encoding="utf-8")
    try:
        archive_path = backup_service.build_portable_project_backup(
            source_database,
            storage_dir=storage_dir,
            backup_dir=tmp_path / "output",
            output_name="portable.zip",
        )
        tampered_path = tmp_path / "tampered.zip"
        with zipfile.ZipFile(archive_path) as source, zipfile.ZipFile(tampered_path, "w") as target:
            for info in source.infolist():
                payload = source.read(info.filename)
                if info.filename == "storage/asset.txt":
                    payload = b"tampered"
                target.writestr(info.filename, payload)

        with pytest.raises(backup_service.BackupError, match="哈希校验失败"):
            backup_service.validate_portable_project_backup(tampered_path)
    finally:
        source_connection.close()


def test_validation_rejects_unsafe_archive_path(tmp_path: Path) -> None:
    archive_path = tmp_path / "unsafe.zip"
    with zipfile.ZipFile(archive_path, "w") as archive:
        archive.writestr("../escape.txt", "unsafe")
        archive.writestr(backup_service.MANIFEST_NAME, "{}")

    with pytest.raises(backup_service.BackupError, match="不安全路径"):
        backup_service.validate_portable_project_backup(archive_path)


def test_backup_generation_is_post_only_and_requires_local_action_header() -> None:
    app = FastAPI()
    app.include_router(sessions_router)

    def fake_db():
        yield object()

    app.dependency_overrides[get_db] = fake_db
    client = TestClient(app)

    assert client.get("/sessions/backup/project").status_code == 405
    response = client.post("/sessions/backup/project")
    assert response.status_code == 403
    assert response.json()["detail"] == "备份操作缺少本机请求标记。"

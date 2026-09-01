from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.testclient import TestClient

from backend.app.config import LOCAL_CORS_ORIGIN_REGEX
from backend.app.services.public_storage import mount_public_storage


def _client(storage_dir: Path) -> TestClient:
    app = FastAPI()
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[],
        allow_origin_regex=LOCAL_CORS_ORIGIN_REGEX,
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    mount_public_storage(app, storage_dir)
    return TestClient(app)


def test_only_allowlisted_media_directories_are_public(tmp_path: Path) -> None:
    storage_dir = tmp_path / "storage"
    public_asset = storage_dir / "assets" / "custom" / "cover.txt"
    public_asset.parent.mkdir(parents=True)
    public_asset.write_text("cover", encoding="utf-8")
    for relative, payload in (
        ("app.db", "database"),
        (".fernet_key", "key"),
        ("api_channels.json", "channels"),
        ("backups/portable.zip", "backup"),
        ("exports/session.zip", "export"),
        ("temp/staging.txt", "temporary"),
    ):
        path = storage_dir / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(payload, encoding="utf-8")

    client = _client(storage_dir)
    assert client.get("/storage/assets/custom/cover.txt").text == "cover"
    assert client.get("/storage/app.db").status_code == 404
    assert client.get("/storage/.fernet_key").status_code == 404
    assert client.get("/storage/api_channels.json").status_code == 404
    assert client.get("/storage/backups/portable.zip").status_code == 404
    assert client.get("/storage/exports/session.zip").status_code == 404
    assert client.get("/storage/temp/staging.txt").status_code == 404


def test_default_cors_allows_loopback_frontends_but_not_web_origins(tmp_path: Path) -> None:
    storage_dir = tmp_path / "storage"
    asset = storage_dir / "assets" / "item.txt"
    asset.parent.mkdir(parents=True)
    asset.write_text("ok", encoding="utf-8")
    client = _client(storage_dir)

    local_response = client.get(
        "/storage/assets/item.txt",
        headers={"Origin": "http://127.0.0.1:5173"},
    )
    assert local_response.headers.get("access-control-allow-origin") == "http://127.0.0.1:5173"

    remote_response = client.get(
        "/storage/assets/item.txt",
        headers={"Origin": "https://example.com"},
    )
    assert "access-control-allow-origin" not in remote_response.headers

"""公共 API 探测路由 — 无上游网络（缺 Key）。"""

import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from fastapi.testclient import TestClient

from backend.app.main import create_app

app = create_app()
client = TestClient(app)


def test_probe_text_rejects_empty_key():
    r = client.post(
        "/api/system/probe-public-api",
        json={"channel": "text", "base_url": "https://api.openai.com/v1", "api_key": "", "model": "gpt-4o-mini"},
    )
    assert r.status_code == 200
    data = r.json()
    assert data["ok"] is False
    assert "Key" in (data.get("error") or "")


def test_probe_image_rejects_empty_model():
    r = client.post(
        "/api/system/probe-public-api",
        json={"channel": "image", "base_url": "https://api.openai.com/v1", "api_key": "sk-test", "model": ""},
    )
    assert r.status_code == 200
    data = r.json()
    assert data["ok"] is False


def test_probe_stream_returns_ndjson_lines():
    r = client.post(
        "/api/system/probe-public-api/stream",
        json={"channel": "text", "base_url": "", "api_key": "", "model": "gpt-4o-mini"},
    )
    assert r.status_code == 200
    lines = [ln for ln in r.text.strip().split("\n") if ln.strip()]
    assert len(lines) >= 2
    types = [json.loads(ln)["type"] for ln in lines]
    assert "start" in types
    assert "done" in types

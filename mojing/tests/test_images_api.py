"""生图路由契约与 base 归一 — 不调用外网上游（mock service）。"""

import os
import sys
from unittest.mock import AsyncMock, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from fastapi.testclient import TestClient

from backend.app.main import create_app
from backend.app.services.image_service import _normalize_openai_compatible_base

app = create_app()
client = TestClient(app)


class TestNormalizeImageBase:
    def test_strips_chat_completions_path(self):
        assert _normalize_openai_compatible_base(
            "https://api.siliconflow.cn/v1/chat/completions",
        ) == "https://api.siliconflow.cn"

    def test_strips_images_generations(self):
        assert _normalize_openai_compatible_base(
            "https://api.siliconflow.cn/v1/images/generations",
        ) == "https://api.siliconflow.cn"

    def test_preserves_plain_v1_base(self):
        assert _normalize_openai_compatible_base("https://api.siliconflow.cn/v1") == "https://api.siliconflow.cn/v1"


class TestImagesGenerateRoute:
    def test_empty_prompt_400(self):
        resp = client.post("/api/images/generate", json={"prompt": "   "})
        assert resp.status_code == 400

    def test_missing_prompt_400(self):
        resp = client.post("/api/images/generate", json={})
        assert resp.status_code == 400

    def test_returns_service_payload_when_upstream_mocked(self):
        payload = {"urls": ["https://cdn.example.test/out.png"], "revised_prompt": "mock"}
        with (
            patch(
                "backend.app.routes.images.resolve_public_image_credentials",
                return_value=("test-key", "https://api.example.test/v1", "test-image-model"),
            ),
            patch("backend.app.routes.images.generate_image", new_callable=AsyncMock, return_value=payload),
        ):
            resp = client.post("/api/images/generate", json={"prompt": "一只猫"})
        assert resp.status_code == 200
        data = resp.json()
        assert data["urls"] == ["https://cdn.example.test/out.png"]
        assert data["revised_prompt"] == "mock"

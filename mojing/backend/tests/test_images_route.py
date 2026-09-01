from fastapi.testclient import TestClient

from backend.app.database import get_db
from backend.app.main import create_app
from backend.app.middleware.rate_limit import RateLimitMiddleware


class FakeDb:
    pass


def test_generate_image_endpoint_uses_resolved_public_image_credentials(monkeypatch):
    captured = {}
    app = create_app()
    app.user_middleware = [middleware for middleware in app.user_middleware if middleware.cls is not RateLimitMiddleware]
    app.middleware_stack = app.build_middleware_stack()

    def override_db():
        yield FakeDb()

    async def fake_generate_image(**kwargs):
        captured.update(kwargs)
        return {"urls": ["mock://image"], "revised_prompt": ""}

    monkeypatch.setattr("backend.app.routes.images.generate_image", fake_generate_image)
    monkeypatch.setattr(
        "backend.app.routes.images.resolve_public_image_credentials",
        lambda db: (
            "resolved-key",
            "https://api.siliconflow.cn/v1/images/generations",
            "black-forest-labs/FLUX.2-pro",
        ),
    )
    app.dependency_overrides[get_db] = override_db
    client = TestClient(app)

    response = client.post("/api/images/generate", json={"prompt": "一座雨夜霓虹城市"})

    assert response.status_code == 200
    assert captured["api_key"] == "resolved-key"
    assert captured["base_url"] == "https://api.siliconflow.cn/v1/images/generations"
    assert captured["model"] == "black-forest-labs/FLUX.2-pro"
    app.dependency_overrides.clear()


def test_generate_image_endpoint_payload_overrides_public_image_config(monkeypatch):
    captured = {}
    app = create_app()
    app.user_middleware = [middleware for middleware in app.user_middleware if middleware.cls is not RateLimitMiddleware]
    app.middleware_stack = app.build_middleware_stack()

    def override_db():
        yield FakeDb()

    async def fake_generate_image(**kwargs):
        captured.update(kwargs)
        return {"urls": ["mock://image"], "revised_prompt": ""}

    monkeypatch.setattr("backend.app.routes.images.generate_image", fake_generate_image)
    monkeypatch.setattr(
        "backend.app.routes.images.resolve_public_image_credentials",
        lambda db: (
            "image-key",
            "https://api.siliconflow.cn/v1",
            "black-forest-labs/FLUX.2-pro",
        ),
    )
    app.dependency_overrides[get_db] = override_db
    client = TestClient(app)

    response = client.post(
        "/api/images/generate",
        json={
            "prompt": "一座雨夜霓虹城市",
            "api_key": "payload-key",
            "base_url": "https://www.dmxapi.cn/v1",
            "model": "qwen-image-max",
        },
    )

    assert response.status_code == 200
    assert captured["api_key"] == "payload-key"
    assert captured["base_url"] == "https://www.dmxapi.cn/v1"
    assert captured["model"] == "qwen-image-max"
    app.dependency_overrides.clear()

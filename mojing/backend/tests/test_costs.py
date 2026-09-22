from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool
from sqlalchemy.orm import Session

from backend.app.database import Base, get_db
from backend.app.main import create_app
from backend.app.models import LlmCostRecordModel


def _client(records: list[LlmCostRecordModel]):
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Base.metadata.create_all(engine)
    with Session(engine) as db:
        db.add_all(records)
        db.commit()
    app = create_app()

    def override_db():
        with Session(engine) as db:
            yield db

    app.dependency_overrides[get_db] = override_db
    return TestClient(app), engine


def _record(*, provider: str, model: str, record_id: int, success: bool = True, age_days: int = 1):
    return LlmCostRecordModel(
        id=record_id, provider=provider, model_name=model, session_id=record_id,
        prompt_tokens=10, completion_tokens=20, total_tokens=30,
        estimated_cost=0.5, duration_ms=100, success=success,
        created_at=datetime.now(timezone.utc) - timedelta(days=age_days),
    )


def test_cost_drilldown_is_provider_isolated_and_filters_period_and_status():
    client, engine = _client([
        _record(provider="openai", model="same", record_id=1),
        _record(provider="deepseek", model="same", record_id=2),
        _record(provider="openai", model="old", record_id=3, age_days=20),
        _record(provider="openai", model="same", record_id=4, success=False),
    ])

    response = client.get("/api/costs/providers/openai/models?days=7&status=success")
    assert response.status_code == 200
    body = response.json()
    assert body["totals"]["total_tokens"] == 30
    assert [item["model_name"] for item in body["items"]] == ["same"]
    assert body["items"][0]["cost_usd"] == 0.5

    records = client.get("/api/costs/providers/openai/records", params={"model": "same", "limit": 1})
    assert records.status_code == 200
    assert records.json()["items"][0]["provider"] == "openai"
    engine.dispose()


def test_cost_records_support_model_names_with_slashes_and_keyset_cursor():
    client, engine = _client([
        _record(provider="siliconflow", model="Qwen/Qwen3-8B", record_id=1),
        _record(provider="siliconflow", model="Qwen/Qwen3-8B", record_id=2),
    ])
    response = client.get("/api/costs/providers/siliconflow/records", params={
        "model": "Qwen/Qwen3-8B", "limit": 1,
    })
    assert response.status_code == 200
    assert response.json()["next_cursor"] == 2
    next_page = client.get("/api/costs/providers/siliconflow/records", params={
        "model": "Qwen/Qwen3-8B", "limit": 1, "before_id": 2,
    })
    assert [row["id"] for row in next_page.json()["items"]] == [1]
    engine.dispose()


def test_cost_drilldown_preserves_empty_names_and_failed_status():
    client, engine = _client([
        _record(provider="", model="", record_id=1, success=False),
        _record(provider="other", model="", record_id=2, success=False),
    ])
    providers = client.get("/api/costs/providers", params={"status": "failed"})
    assert providers.status_code == 200
    assert {item["provider"] for item in providers.json()["items"]} == {"", "other"}
    assert providers.json()["totals"]["failed_calls"] == 2
    assert providers.json()["totals"]["total_tokens"] == 60
    assert providers.json()["totals"]["cost_usd"] == 1.0
    models = client.get("/api/costs/providers/other/models", params={"status": "failed"})
    assert models.json()["items"][0]["model_name"] == ""
    details = client.get("/api/costs/providers/other/records", params={"model": "", "status": "failed"})
    assert details.status_code == 200
    assert details.json()["items"][0]["model_name"] == ""
    engine.dispose()

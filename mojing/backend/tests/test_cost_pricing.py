from sqlalchemy import create_engine, inspect, text
from sqlalchemy.orm import Session

from backend.app.database import Base
from backend.app.models import LlmCostRecordModel
from backend.app.services.cost_service import get_cost_for_message, get_cost_providers, get_cost_records, record_llm_call, save_model_price
from backend.app.services.schema_migration_service import ensure_cost_schema
from backend.app.services.model_platform_service import CATALOG_KEY
from backend.app.services.system_config_service import set_setting


def _db():
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    return engine, Session(engine)


def _catalog(db):
    set_setting(db, CATALOG_KEY, {"version": 1, "active_id": "p1", "platforms": [
        {"id": "p1", "name": "One", "base_url": "https://one.test", "api_key": "x", "models": ["m"], "selected_model": "m"},
        {"id": "p2", "name": "Two", "base_url": "https://two.test", "api_key": "x", "models": ["m"], "selected_model": "m"},
    ]})


def test_price_scope_and_history_sync_are_exact():
    engine, db = _db()
    _catalog(db)
    first = record_llm_call(db, platform_id="p1", model_name="m", prompt_tokens=1_000_000, completion_tokens=0)
    second = record_llm_call(db, platform_id="p2", model_name="m", prompt_tokens=1_000_000, completion_tokens=0)
    legacy = record_llm_call(db, provider="openai", model_name="m", prompt_tokens=10, completion_tokens=0)
    _, count = save_model_price(db, platform_id="p1", model_name="m", currency="USD", input_per_million=2,
                                output_per_million=0, cached_input_per_million=0, sync_history=False)
    assert count == 1 and db.get(LlmCostRecordModel, first.id).estimated_cost == 2
    assert db.get(LlmCostRecordModel, second.id).cost_known == 0
    save_model_price(db, platform_id="p1", model_name="m", currency="CNY", input_per_million=3,
                     output_per_million=0, cached_input_per_million=0, sync_history=False)
    assert db.get(LlmCostRecordModel, first.id).currency == "USD"
    save_model_price(db, platform_id="p1", model_name="m", currency="CNY", input_per_million=3,
                     output_per_million=0, cached_input_per_million=0, sync_history=True)
    assert db.get(LlmCostRecordModel, first.id).estimated_cost == 3
    providers = get_cost_providers(db, days=30)
    assert {item["provider"] for item in providers["items"]} == {"platform:p1", "platform:p2", "openai"}
    assert get_cost_records(db, provider="openai", model_name="m")["items"][0]["id"] == legacy.id
    assert get_cost_records(db, provider="platform:p2", model_name="m")["items"][0]["estimated_cost"] is None
    assert get_cost_for_message(db, "absent") is None
    db.close(); engine.dispose()


def test_legacy_cost_schema_is_additive_and_idempotent():
    engine = create_engine("sqlite://")
    with engine.begin() as connection:
        connection.exec_driver_sql("CREATE TABLE llm_cost_records (id INTEGER PRIMARY KEY, model_name VARCHAR(120), provider VARCHAR(60), prompt_tokens INTEGER, completion_tokens INTEGER, total_tokens INTEGER, estimated_cost FLOAT, success INTEGER)")
        connection.exec_driver_sql("INSERT INTO llm_cost_records VALUES (1, 'legacy', 'old', 1, 2, 3, 9.5, 1)")
    ensure_cost_schema(engine.connect())
    ensure_cost_schema(engine.connect())
    with engine.connect() as connection:
        columns = {item[1] for item in connection.exec_driver_sql("PRAGMA table_info(llm_cost_records)")}
        row = connection.execute(text("SELECT platform_id, cost_known, estimated_cost FROM llm_cost_records WHERE id=1")).one()
    assert {"platform_id", "currency", "cost_known", "pricing_snapshot_json"}.issubset(columns)
    assert row.platform_id is None and row.cost_known == 0 and row.estimated_cost == 9.5
    engine.dispose()

import json

import pytest

from .test_android_billing_migration import _room20_database, _apply_real_migration, _dao_query


def database():
    conn = _room20_database()
    _apply_real_migration(conn)
    conn.execute("DELETE FROM llm_cost_records")
    rows = [("a", "m", 0), ("a", "m", 1), ("b", "m", 0), ("a", "other", 0), ("", "m", 0)]
    conn.executemany(
        "INSERT INTO llm_cost_records (platformId,modelName,costKnown,provider,promptTokens,completionTokens,"
        "cachedPromptTokens,totalTokens,estimatedCost,currency,durationMs,success,createdAt) "
        "VALUES (?,?,?,'llm_stream',1000000,500000,200000,1500000,9,'USD',100,1,10)", rows)
    conn.commit()
    return conn


def update(conn, include_known):
    return conn.execute(_dao_query("repriceHistory"), dict(platformId="a", modelName="m",
        inputRate=2.0, outputRate=4.0, cachedRate=0.5, currency="CNY", snapshot='{"source":"manual"}',
        includeKnown=include_known)).rowcount


def test_backfill_and_sync_only_touch_exact_platform_and_model():
    conn = database()
    before = list(conn.execute("SELECT * FROM llm_cost_records WHERE platformId!='a' OR modelName!='m'"))
    assert update(conn, False) == 1
    assert conn.execute("SELECT estimatedCost,currency,costKnown FROM llm_cost_records WHERE id=2").fetchone() == (3.7, "CNY", 1)
    assert conn.execute("SELECT estimatedCost,currency FROM llm_cost_records WHERE id=3").fetchone() == (9, "USD")
    assert update(conn, True) == 2
    assert conn.execute("SELECT COUNT(*) FROM llm_cost_records WHERE platformId='a' AND modelName='m' AND estimatedCost=3.7 AND currency='CNY'").fetchone()[0] == 2
    assert before == list(conn.execute("SELECT * FROM llm_cost_records WHERE platformId!='a' OR modelName!='m'"))


def test_zero_price_is_already_priced_and_currency_change_does_not_change_tokens():
    conn = database()
    conn.execute("UPDATE llm_cost_records SET estimatedCost=0 WHERE id=3")
    assert conn.execute(_dao_query("hasPricedHistory"), dict(platformId="a", modelName="m")).fetchone()[0] == 1
    before = list(conn.execute("SELECT id,promptTokens,completionTokens,totalTokens,success,createdAt FROM llm_cost_records"))
    update(conn, True)
    assert before == list(conn.execute("SELECT id,promptTokens,completionTokens,totalTokens,success,createdAt FROM llm_cost_records"))


def test_price_write_and_repricing_roll_back_together():
    conn = database()
    before = list(conn.execute("SELECT * FROM llm_cost_records"))
    with pytest.raises(RuntimeError), conn:
        conn.execute("INSERT INTO app_config (`key`, valueJson, updatedAt) VALUES (?, ?, 1)",
                     ("billing_price:v1:" + json.dumps(["a", "m"]), '{}'))
        update(conn, True)
        raise RuntimeError("interrupted save")
    assert list(conn.execute("SELECT * FROM llm_cost_records")) == before
    assert conn.execute("SELECT COUNT(*) FROM app_config WHERE `key` LIKE 'billing_price:%'").fetchone()[0] == 0

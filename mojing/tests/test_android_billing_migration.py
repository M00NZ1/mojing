import json
import re
import sqlite3
from pathlib import Path

ROOT = Path(__file__).parents[1]
SCHEMA_DIR = ROOT / "android" / "app" / "schemas" / "com.mojing.app.data.local.AppDatabase"
MIGRATIONS = ROOT / "android/app/src/main/java/com/mojing/app/data/local/Migrations.kt"
COST_DAO = ROOT / "android/app/src/main/java/com/mojing/app/data/local/dao/CostRecordDao.kt"


def _room20_database():
    spec = json.loads((SCHEMA_DIR / "20.json").read_text(encoding="utf-8"))["database"]
    conn = sqlite3.connect(":memory:")
    conn.execute("PRAGMA foreign_keys=OFF")
    for entity in spec["entities"]:
        conn.execute(entity["createSql"].replace("`${TABLE_NAME}`", f"`{entity['tableName']}`"))
    for query in spec.get("setupQueries", []):
        conn.execute(query)
    for entity in spec["entities"]:
        for index in entity.get("indices", []):
            conn.execute(index["createSql"].replace("`${TABLE_NAME}`", f"`{entity['tableName']}`"))
    conn.execute("INSERT INTO llm_cost_records (modelName,provider,promptTokens,completionTokens,totalTokens,estimatedCost,durationMs,success,createdAt) VALUES ('legacy-model','old',10,20,30,1.25,40,1,10)")
    conn.commit()
    return conn


def _migration_sql():
    text = MIGRATIONS.read_text(encoding="utf-8")
    block = text.split("val MIGRATION_20_21", 1)[1].split("\n    }", 1)[0]
    return [bytes(value, "utf-8").decode("unicode_escape") for value in re.findall(r'(?:db\.execSQL|addColumn)\("((?:[^"\\]|\\.)*)"\)', block)]


def _apply_real_migration(conn, fail_after=None):
    # Same PRAGMA guard as the Kotlin migration; statements come from production.
    existing = {row[1] for row in conn.execute("PRAGMA table_info(`llm_cost_records`)")}
    with conn:
        for index, statement in enumerate(_migration_sql()):
            name = re.search(r"ADD COLUMN `([^`]+)`", statement)
            if name and name[1] in existing:
                continue
            conn.execute(statement)
            if fail_after == index:
                raise RuntimeError("injected migration failure")


def _dao_query(method):
    text = COST_DAO.read_text(encoding="utf-8")
    method_start = text.find("suspend fun " + method)
    assert method_start >= 0, f"DAO method not found: {method}"
    query_start = text.rfind("@Query(", 0, method_start)
    query_body = text[query_start + len("@Query("):method_start]
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', query_body)
    return "".join(literals)


def test_room20_to21_uses_real_schema_and_preserves_legacy_value():
    conn = _room20_database()
    _apply_real_migration(conn)
    row = conn.execute("SELECT modelName, estimatedCost, totalTokens, currency, platformId, costKnown FROM llm_cost_records WHERE id=1").fetchone()
    assert row == ("legacy-model", 1.25, 30, "USD", "", 1)
    assert {item[1] for item in conn.execute("PRAGMA table_info(llm_cost_records)")} >= {"platformId", "platformName", "currency", "costKnown", "tokenSource", "status", "cachedPromptTokens", "pricingSnapshotJson"}


def test_real_migration_repeat_and_transaction_rollback():
    conn = _room20_database()
    columns_before = list(conn.execute("PRAGMA table_info(llm_cost_records)"))
    conn.execute("BEGIN")
    try:
        _apply_real_migration(conn, fail_after=3)
    except RuntimeError:
        pass
    assert list(conn.execute("PRAGMA table_info(llm_cost_records)")) == columns_before
    assert conn.execute("SELECT estimatedCost FROM llm_cost_records WHERE id=1").fetchone()[0] == 1.25
    _apply_real_migration(conn)
    _apply_real_migration(conn)
    schema = json.loads((SCHEMA_DIR / "21.json").read_text(encoding="utf-8"))["database"]
    entity = next(item for item in schema["entities"] if item["tableName"] == "llm_cost_records")
    expected = sqlite3.connect(":memory:")
    expected.execute(entity["createSql"].replace("`${TABLE_NAME}`", "`llm_cost_records`"))
    assert list(conn.execute("PRAGMA table_info(llm_cost_records)")) == list(expected.execute("PRAGMA table_info(llm_cost_records)"))
    assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    indices = {row[1] for row in conn.execute("PRAGMA index_list(llm_cost_records)")}
    assert {index["name"] for index in entity["indices"]} <= indices


def test_real_dao_queries_filter_platform_model_and_group_currency():
    conn = _room20_database()
    _apply_real_migration(conn)
    conn.executemany("INSERT INTO llm_cost_records (modelName,provider,promptTokens,completionTokens,totalTokens,estimatedCost,durationMs,success,createdAt,platformId,platformName,currency,costKnown,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", [("m", "p", 4, 6, 10, 1.0, 1, 1, 20, "platform-a", "A", "USD", 1, "success"), ("m", "p", 8, 12, 20, 2.0, 1, 0, 19, "platform-a", "A", "CNY", 1, "failed"), ("other", "p", 10, 20, 30, 3.0, 1, 1, 18, "platform-b", "B", "USD", 0, "success")])
    rows = conn.execute(_dao_query("usageSummary"), {"platformId": "platform-a", "modelName": None}).fetchall()
    assert {(row[0], row[1], row[2], row[6]) for row in rows} == {("CNY", 1, 1, 20), ("USD", 1, 0, 10)}
    page = conn.execute(_dao_query("requestPage"), {"platformId": "platform-a", "modelName": "m", "beforeId": 99, "limit": 10}).fetchall()
    assert len(page) == 2

    unknown = conn.execute(_dao_query("usageSummary"), {"platformId": "platform-b", "modelName": "other"}).fetchone()
    assert unknown[7] == 0.0 and unknown[8] == 1
    query_plan = conn.execute("EXPLAIN QUERY PLAN " + _dao_query("requestPage"), {"platformId": "platform-a", "modelName": "m", "beforeId": 99, "limit": 1}).fetchall()
    assert any("index_llm_cost_records_platformId_modelName_id" in row[3] for row in query_plan)

"""Run Android's additive world migration SQL against isolated SQLite."""
import json
import re
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "android"


def test_world_mapping_upgrade_preserves_old_schema_and_is_repeatable():
    schemas = ROOT / "app/schemas/com.mojing.app.data.local.AppDatabase"
    old = {e["tableName"]: e for e in json.loads((schemas / "19.json").read_text())["database"]["entities"]}
    new = {e["tableName"]: e for e in json.loads((schemas / "20.json").read_text())["database"]["entities"]}
    added = {"legacy_world_mappings", "legacy_lore_mappings"}
    assert set(new) - set(old) == added
    assert all(old[name] == new[name] for name in old)
    source = (ROOT / "app/src/main/java/com/mojing/app/data/local/Migrations.kt").read_text(encoding="utf-8").split("val MIGRATION_19_20")[1]
    statements = [v.strip() for v in re.findall(r'"""(.*?)"""', source, re.S)]
    statements += re.findall(r'db.execSQL\("([^"\n]+)"\)', source)
    assert len(statements) == 4
    with sqlite3.connect(":memory:") as db:
        db.execute("PRAGMA foreign_keys=ON")
        for entity in old.values():
            db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            for index in entity.get("indices", []):
                db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for _ in range(2):
            for sql in statements:
                db.execute(sql)
        for table in added:
            columns = list(db.execute(f"PRAGMA table_info({table})"))
            assert [v[1] for v in columns] == [v["columnName"] for v in new[table]["fields"]]
            assert [bool(v[3]) for v in columns] == [v.get("notNull", False) for v in new[table]["fields"]]
            actual_fks = {(row[2], row[3], row[4], row[5], row[6]) for row in db.execute(f"PRAGMA foreign_key_list({table})")}
            expected_fks = {(fk["table"], fk["columns"][0], fk["referencedColumns"][0], fk["onUpdate"], fk["onDelete"]) for fk in new[table]["foreignKeys"]}
            assert actual_fks == expected_fks
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"

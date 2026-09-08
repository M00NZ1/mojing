"""Execute the Android DAO SQL on desktop SQLite; this is not Room/device evidence."""
import json
import re
import sqlite3
import unittest
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[1] / "android/app"
SOURCE = ANDROID / "src/main/java/com/mojing/app"


def queries(filename):
    text = (SOURCE / "data/local/dao" / filename).read_text(encoding="utf-8")
    result = {}
    for body, name in re.findall(r"@Query\((.*?)\)\s*(?:suspend\s+)?fun\s+(\w+)", text, re.S):
        literal = re.search(r'"""(.*?)"""|"(.*?)"', body, re.S)
        result[name] = next(value for value in literal.groups() if value is not None)
    return result


class AndroidLibrarySqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        schema = json.loads((ANDROID / "schemas/com.mojing.app.data.local.AppDatabase/19.json").read_text())
        for entity in schema["database"]["entities"]:
            self.db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            for index in entity.get("indices", []):
                self.db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        self.characters = queries("CharacterDao.kt")
        self.tasks = queries("GenerationTaskDao.kt")

    def tearDown(self):
        self.db.close()

    def insert(self, table, **values):
        row = {c["name"]: ("" if c["type"] == "TEXT" else 0) if c["notnull"] else None
               for c in self.db.execute(f"PRAGMA table_info({table})") if c["name"] != "id"}
        row.update(values)
        return self.db.execute(f"INSERT INTO {table} ({','.join(row)}) VALUES ({','.join('?' for _ in row)})", list(row.values())).lastrowid

    def test_character_order_is_stable_across_filters_and_edits(self):
        old = self.insert("characters", name="旧角色", createdAt=1, updatedAt=500, boundEncyclopediaId=7)
        new = self.insert("characters", name="新导入", createdAt=100, updatedAt=100, boundEncyclopediaId=7)
        favorite = self.insert("characters", favorite=1, createdAt=2, boundEncyclopediaId=7)
        pinned = self.insert("characters", pinnedAt=10, createdAt=3, boundEncyclopediaId=7)
        expected = [pinned, favorite, new, old]
        for method in ("getAll", "observeAll", "getAllBound", "observeByEncyclopedia"):
            rows = self.db.execute(self.characters[method], {"encyclopediaId": 7})
            self.assertEqual(expected, [r["id"] for r in rows])

    def test_revision_pages_cover_large_interleaved_history_without_duplicates(self):
        query = queries("EntryVersionDao.kt")["getPage"]
        seed = self.insert("entry_versions", entryId=7, content="旧正文" * 400)
        self.db.executemany(
            "INSERT INTO entry_versions (entryId, version, title, summary, content, tags, metaSnapshotJson, changeNote, createdBy, createdAt) "
            "SELECT ?, ?, title, summary, content, tags, metaSnapshotJson, changeNote, createdBy, createdAt FROM entry_versions WHERE id = ?",
            ((7 if n % 2 else 8, n, seed) for n in range(1, 20001)),
        )
        expected = [r[0] for r in self.db.execute("SELECT id FROM entry_versions WHERE entryId = 7 ORDER BY id DESC")]
        before = 2**63 - 1
        seen = []
        while True:
            rows = list(self.db.execute(query, {"entryId": 7, "beforeId": before, "limit": 11}))
            self.assertLessEqual(len(rows), 11)
            page = rows[:10]
            self.assertTrue(all(r["entryId"] == 7 and r["content"] == "旧正文" * 400 for r in page))
            seen.extend(r["id"] for r in page)
            if len(rows) <= 10:
                break
            before = page[-1]["id"]
        self.assertEqual(expected, seen)
        plan = " ".join(r[3] for r in self.db.execute("EXPLAIN QUERY PLAN " + query,
            {"entryId": 7, "beforeId": before, "limit": 11}))
        self.assertIn("index_entry_versions_entryId", plan)
        self.assertNotIn("TEMP B-TREE", plan)

    def test_revision_cursor_survives_deleted_boundary_and_newer_insert(self):
        query = queries("EntryVersionDao.kt")["getPage"]
        ids = [self.insert("entry_versions", entryId=7, version=n) for n in range(25)]
        first = list(self.db.execute(query, {"entryId": 7, "beforeId": 2**63 - 1, "limit": 11}))[:10]
        boundary = first[-1]["id"]
        self.db.execute("DELETE FROM entry_versions WHERE id = ?", (boundary,))
        newest = self.insert("entry_versions", entryId=7, version=26)
        older = list(self.db.execute(query, {"entryId": 7, "beforeId": boundary, "limit": 11}))[:10]
        self.assertEqual(list(reversed(ids[:15]))[:10], [r["id"] for r in older])
        self.assertNotIn(newest, [r["id"] for r in older])

    def test_failed_retry_keeps_saved_progress_and_only_changes_failed_rows(self):
        task = self.insert("generation_tasks", status="FAILED", progressDone=3, progressTotal=5)
        params = {"id": task, "total": 5, "now": 100}
        self.assertEqual(1, self.db.execute(self.tasks["requeueFailed"], params).rowcount)
        row = self.db.execute(self.tasks["getById"], {"id": task}).fetchone()
        self.assertEqual(("QUEUED", 3, 5), (row["status"], row["progressDone"], row["progressTotal"]))
        self.assertEqual(0, self.db.execute(self.tasks["requeueFailed"], params).rowcount)

    def test_cancelled_task_cannot_be_overwritten_by_late_completion(self):
        task = self.insert("generation_tasks", status="RUNNING")
        self.db.execute(self.tasks["cancelTask"], {"id": task, "now": 2})
        self.db.execute(self.tasks["setTerminal"], {"id": task, "status": "COMPLETED", "err": "", "now": 3})
        self.assertEqual("CANCELLED", self.db.execute(self.tasks["getById"], {"id": task}).fetchone()["status"])

    def test_active_tasks_remain_visible_ahead_of_recent_completed_history(self):
        task = self.insert("generation_tasks", status="PAUSED")
        for _ in range(160):
            self.insert("generation_tasks", status="COMPLETED")
        rows = list(self.db.execute(self.tasks["observeQueueVisible"]))
        self.assertEqual(150, len(rows))
        self.assertEqual(task, rows[0]["id"])

    def test_catalog_reference_queries_match_current_schema(self):
        source = (SOURCE / "data/BuiltinCatalogUpgrade.kt").read_text(encoding="utf-8")
        for sql in re.findall(r'exists\("([^"]+)"', source):
            tables = ["session_participants", "messages", "character_profiles", "character_expressions", "session_character_states", "session_event_nodes", "llm_cost_records"] if "$it" in sql else ["characters"]
            for table in tables:
                query = sql.replace("$it", table).replace("$table", table)
                self.db.execute("EXPLAIN " + query, (1,))


if __name__ == "__main__":
    unittest.main()

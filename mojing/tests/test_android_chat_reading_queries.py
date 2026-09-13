"""Exercise the current Android story-directory Room SQL in isolated SQLite."""

import json
import re
import sqlite3
from pathlib import Path


DAO_PATH = Path(__file__).parents[1] / "android/app/src/main/java/com/mojing/app/data/local/dao/MessageDao.kt"
DAO = DAO_PATH.read_text(encoding="utf-8")


def room_query(method: str) -> str:
    start = DAO.index("suspend fun " + method + "(")
    annotation_start = DAO.rfind("@Query(", 0, start)
    annotation = DAO[annotation_start:start]
    sql = "".join(re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"', annotation))
    constants = {}
    for name in ("MAIN_CONTEXT_MESSAGES_QUERY", "VISIBLE_CONTEXT_MESSAGES_QUERY", "CURRENT_MESSAGES_QUERY"):
        match = re.search(rf'private const val {name} = (?:([A-Z_]+)\s*\+\s*)?"""(.*?)"""', DAO, re.S)
        if match:
            constants[name] = (match.group(1), match.group(2))
    for name in ("MAIN_CONTEXT_MESSAGES_QUERY", "VISIBLE_CONTEXT_MESSAGES_QUERY", "CURRENT_MESSAGES_QUERY"):
        base, body = constants.get(name, (None, ""))
        constants[name] = (None, constants[base][1] + body if base else body)
    for name, (_, value) in constants.items():
        sql = sql.replace(f"${name}", value)
    return sql


def database():
    db = sqlite3.connect(":memory:")
    # Use the exported Room schema; no second schema maintained by the test.
    schema = Path(__file__).parents[1] / "android/app/schemas/com.mojing.app.data.local.AppDatabase/20.json"
    for entity in json.loads(schema.read_text())["database"]["entities"]:
        if entity["tableName"] in {"messages", "branch_visibility_segments", "branch_swipe_selections", "message_search_fts"}:
            db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    return db


def add_message(db, message_id, branch="main", group=None, content=None):
    text = content or ("正文 " + "x" * 220)
    db.execute(
        "INSERT INTO messages VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (message_id, 42, "narrator", None, branch, None, None, group, text,
         '{"chapter_number": %d, "chapter_title": "章节 %d"}' % (message_id, message_id),
         1, message_id, "", ""),
    )


def page(db, method, **params):
    return db.execute(room_query(method), params).fetchall()


def test_main_directory_is_descending_projected_and_paginates_without_gaps():
    db = database()
    for message_id in range(1, 86):
        add_message(db, message_id)
    cursor = 2**63 - 1
    ids = []
    pages = []
    while True:
        rows = page(db, "getMainStoryContentsBefore", sessionId=42, beforeMessageId=cursor, limit=40)
        pages.append(rows)
        ids.extend(row[0] for row in rows)
        if len(rows) < 40:
            break
        cursor = rows[-1][0]
    assert [len(rows) for rows in pages] == [40, 40, 5]
    assert ids == list(range(85, 0, -1))
    assert len(ids) == len(set(ids))
    assert all(len(row[5]) == 180 for row in pages[0])


def test_branch_directory_uses_visible_context_and_selected_swipe_once():
    db = database()
    for message_id in range(1, 61):
        add_message(db, message_id, group="swipe" if message_id in (10, 11) else None)
    for message_id in range(100, 106):
        add_message(db, message_id, branch="branch-a")
    db.execute("INSERT INTO branch_visibility_segments VALUES(42,'branch-a','main',60)")
    db.execute("INSERT INTO branch_visibility_segments VALUES(42,'branch-a','branch-a',105)")
    db.execute("INSERT INTO branch_swipe_selections VALUES(42,'branch-a','swipe',11)")
    rows = page(db, "getBranchStoryContentsBefore", sessionId=42, branchId="branch-a", beforeMessageId=2**63 - 1, limit=100)
    ids = [row[0] for row in rows]
    assert ids == sorted(ids, reverse=True)
    assert ids.count(10) == 0
    assert ids.count(11) == 1
    assert set(ids) == set(range(1, 10)) | {11} | set(range(12, 61)) | set(range(100, 106))


def test_search_counts_match_complete_keyset_results_for_both_branches():
    db = database()
    for message_id in range(1, 56):
        add_message(db, message_id, content="雾港命中" if message_id % 3 else "其它内容")
    db.execute("INSERT INTO branch_visibility_segments VALUES(42,'branch-a','main',55)")
    params = dict(sessionId=42, branchId="branch-a", query="命中", normalizedQuery="命中",
                  matchExpression="unused", exactMatch=0, indexedThroughMessageId=0, indexComplete=0,
                  beforeMessageId=2**63-1, limit=11)
    for prefix in ("Main", "Visible"):
        total = page(db, f"count{prefix}MessagesIndexed", **params)[0][0]
        result_ids = []
        cursor = 2**63 - 1
        while True:
            rows = page(db, f"search{prefix}MessagesIndexed", **(params | {"beforeMessageId": cursor}))
            result_ids.extend(row[0] for row in rows)
            if len(rows) < 11:
                break
            cursor = rows[-1][0]
        assert total == 37 == len(set(result_ids)) == len(result_ids)
        assert page(db, f"count{prefix}MessagesIndexed", **(params | {"exactMatch": 1}))[0][0] == 0
        assert page(db, f"count{prefix}MessagesIndexed", **(params | {"exactMatch": 1, "query": "雾港命中", "normalizedQuery": "雾港命中"}))[0][0] == total

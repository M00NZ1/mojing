"""Run Android search DAO SQL on isolated desktop SQLite."""
import re
import sqlite3
from pathlib import Path

DAO = (Path(__file__).parents[1] / "android/app/src/main/java/com/mojing/app/data/local/dao/MessageDao.kt").read_text(encoding="utf-8")


def search_sql(method):
    declaration = DAO.index("suspend fun " + method + "(")
    annotation = DAO[DAO.rfind("@Query(", 0, declaration):declaration]
    sql = "".join(re.findall(r'"([^"\n]*)"', annotation))
    constant = re.search(r'private const val CURRENT_MESSAGES_QUERY = """(.*?)"""', DAO, re.S)[1]
    return sql.replace("$CURRENT_MESSAGES_QUERY", constant)


def test_main_and_branch_search_page_all_matches_with_complete_or_partial_index():
    with sqlite3.connect(":memory:") as db:
        db.executescript("""
            CREATE TABLE messages(id INTEGER PRIMARY KEY, sessionId INTEGER, branchId TEXT,
                regeneratedFromMessageId INTEGER, searchNormalized TEXT, content TEXT);
            CREATE TABLE branch_visibility_segments(sessionId INTEGER, targetBranchId TEXT,
                sourceBranchId TEXT, maxMessageId INTEGER);
            CREATE VIRTUAL TABLE message_search_fts USING fts4(tokens);
        """)
        rows = [(i, 42, "main", None, "灯塔线索", "灯塔线索") for i in range(1, 251)]
        rows += [(i, 42, "A", 50 if i == 260 else None, "灯塔线索", "灯塔线索") for i in range(251, 276)]
        rows += [(300, 42, "sibling", None, "灯塔线索", "灯塔线索"),
                 (400, 99, "main", None, "灯塔线索", "灯塔线索")]
        db.executemany("INSERT INTO messages VALUES(?,?,?,?,?,?)", rows)
        db.executescript("""
            INSERT INTO branch_visibility_segments VALUES(42,'A','main',150),(42,'A','A',275);
        """)
        for complete in (False, True):
            db.execute("DELETE FROM message_search_fts")
            indexed = rows if complete else rows[:100]
            db.executemany("INSERT INTO message_search_fts(rowid,tokens) VALUES(?, 'hit')", [(row[0],) for row in indexed])
            for method, expected in (
                ("searchMainMessagesIndexed", list(range(250, 0, -1))),
                ("searchVisibleMessagesIndexed", list(range(275, 250, -1)) + [i for i in range(150, 0, -1) if i != 50]),
            ):
                before = 2**63 - 1
                actual = []
                while True:
                    params = dict(sessionId=42, branchId="A", query="灯塔", normalizedQuery="灯塔",
                                  matchExpression="hit", exactMatch=0, indexComplete=int(complete),
                                  indexedThroughMessageId=100, limit=101, beforeMessageId=before)
                    page = db.execute(search_sql(method), params).fetchall()
                    actual.extend(row[0] for row in page[:100])
                    if len(page) <= 100:
                        break
                    before = page[99][0]
                assert actual == expected
                # Exact matching retains the same cursor and visibility constraints.
                params.update(query="灯塔线索", normalizedQuery="灯塔线索", exactMatch=1, beforeMessageId=60)
                assert [row[0] for row in db.execute(search_sql(method), params)] == [i for i in expected if i < 60]

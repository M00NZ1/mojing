"""Run Android search DAO SQL on isolated desktop SQLite."""
import re
import sqlite3
from pathlib import Path

DAO = (Path(__file__).parents[1] / "android/app/src/main/java/com/mojing/app/data/local/dao/MessageDao.kt").read_text(encoding="utf-8")


def search_sql(method):
    declaration = DAO.index("suspend fun " + method + "(")
    annotation = DAO[DAO.rfind("@Query(", 0, declaration):declaration]
    sql = "".join(re.findall(r'"([^"\n]*)"', annotation))
    for name in ("CURRENT_MESSAGES_QUERY", "CURRENT_MESSAGES_SEARCH_QUERY", "CURRENT_MESSAGES_FROM_QUERY",
                 "SEARCH_RESULT_CONTENT", "SPEAKER_NAME_SEARCH_MATCH", "MESSAGE_BODY_SEARCH_MATCH"):
        constant = re.search(rf'private const val {name} = """(.*?)"""', DAO, re.S)[1]
        sql = sql.replace(f"${name}", constant)
    return sql


def test_main_and_branch_search_page_all_matches_with_complete_or_partial_index():
    with sqlite3.connect(":memory:") as db:
        db.executescript("""
            CREATE TABLE messages(id INTEGER PRIMARY KEY, sessionId INTEGER, branchId TEXT,
                regeneratedFromMessageId INTEGER, searchNormalized TEXT, content TEXT,
                speakerType TEXT, characterId INTEGER, createdAt INTEGER);
            CREATE TABLE characters(id INTEGER PRIMARY KEY, name TEXT);
            CREATE TABLE branch_visibility_segments(sessionId INTEGER, targetBranchId TEXT,
                sourceBranchId TEXT, maxMessageId INTEGER);
            CREATE VIRTUAL TABLE message_search_fts USING fts4(tokens);
        """)
        rows = [(i, 42, "main", None, "灯塔线索", "灯塔线索", "user", None, i) for i in range(1, 251)]
        rows += [(i, 42, "A", 50 if i == 260 else None, "灯塔线索", "灯塔线索", "user", None, i) for i in range(251, 276)]
        rows += [(300, 42, "sibling", None, "灯塔线索", "灯塔线索", "user", None, 300),
                 (400, 99, "main", None, "灯塔线索", "灯塔线索", "user", None, 400)]
        db.executemany("INSERT INTO messages VALUES(?,?,?,?,?,?,?,?,?)", rows)
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


def test_name_only_hits_share_cursor_count_and_branch_visibility_with_body_hits():
    with sqlite3.connect(":memory:") as db:
        db.executescript("""
            CREATE TABLE messages(id INTEGER PRIMARY KEY, sessionId INTEGER, branchId TEXT,
                regeneratedFromMessageId INTEGER, searchNormalized TEXT, content TEXT,
                speakerType TEXT, characterId INTEGER, createdAt INTEGER);
            CREATE TABLE characters(id INTEGER PRIMARY KEY, name TEXT);
            CREATE TABLE branch_visibility_segments(sessionId INTEGER, targetBranchId TEXT,
                sourceBranchId TEXT, maxMessageId INTEGER);
            CREATE VIRTUAL TABLE message_search_fts USING fts4(tokens);
            INSERT INTO characters VALUES(10, '阿沅');
            INSERT INTO messages VALUES
                (1,42,'main',NULL,'走进图书馆','走进图书馆','character',10,1),
                (2,42,'main',NULL,'阿沅留下了信','阿沅留下了信','user',NULL,2),
                (3,42,'main',NULL,'阿沅收到回信','阿沅收到回信','character',10,3),
                (4,99,'main',NULL,'其他会话','其他会话','character',10,4),
                (5,42,'A',NULL,'沿另一条路','沿另一条路','character',10,5);
            INSERT INTO branch_visibility_segments VALUES(42,'A','main',1),(42,'A','A',5);
            INSERT INTO message_search_fts(rowid,tokens) VALUES(2,'hit'),(3,'hit');
        """)
        name_only_body = "走进图书馆" + "风" * 10000
        body_match = "阿沅" + "风" * 10000
        db.execute("UPDATE messages SET content=?, searchNormalized=? WHERE id=1", (name_only_body, name_only_body))
        db.execute("UPDATE messages SET content=?, searchNormalized=? WHERE id=3", (body_match, body_match))
        params = dict(sessionId=42, branchId="A", query="阿沅", normalizedQuery="阿沅",
                      matchExpression="hit", exactMatch=0, indexComplete=1,
                      indexedThroughMessageId=5, limit=2, beforeMessageId=2**63 - 1)
        main_page = db.execute(search_sql("searchMainMessagesIndexed"), params).fetchall()
        assert [row[0] for row in main_page] == [3, 2]
        assert main_page[0][5] == body_match[:2048]
        assert db.execute(search_sql("countMainMessagesIndexed"), params).fetchone()[0] == 3
        params["beforeMessageId"] = 2
        older_page = db.execute(search_sql("searchMainMessagesIndexed"), params).fetchall()
        assert [row[0] for row in older_page] == [1]
        assert older_page[0][5] == name_only_body[:2048]
        params["beforeMessageId"] = 2**63 - 1
        assert [row[0] for row in db.execute(search_sql("searchVisibleMessagesIndexed"), params)] == [5, 1]
        assert db.execute(search_sql("countVisibleMessagesIndexed"), params).fetchone()[0] == 2
        params["exactMatch"] = 1
        assert [row[0] for row in db.execute(search_sql("searchMainMessagesIndexed"), params)] == [3, 1]
        assert db.execute(search_sql("countMainMessagesIndexed"), params).fetchone()[0] == 2


def test_long_plain_hits_return_bounded_late_window_but_structured_and_normalized_hits_keep_body():
    with sqlite3.connect(":memory:") as db:
        db.executescript("""
            CREATE TABLE messages(id INTEGER PRIMARY KEY, sessionId INTEGER, branchId TEXT,
                regeneratedFromMessageId INTEGER, searchNormalized TEXT, content TEXT,
                speakerType TEXT, characterId INTEGER, createdAt INTEGER);
            CREATE TABLE characters(id INTEGER PRIMARY KEY, name TEXT);
            CREATE TABLE branch_visibility_segments(sessionId INTEGER, targetBranchId TEXT,
                sourceBranchId TEXT, maxMessageId INTEGER);
            CREATE VIRTUAL TABLE message_search_fts USING fts4(tokens);
            INSERT INTO branch_visibility_segments VALUES(42,'A','main',4),(42,'A','A',4);
        """)
        plain = "🌊" * 10000 + "灯塔线索" + "风" * 1000
        structured = "<NARRATION>" + "风" * 10000 + "灯塔线索</NARRATION>"
        branch_plain = "雨" * 10000 + "灯塔线索" + "雾" * 1000
        normalized_only = "ＨＥＬＬＯ" + "风" * 10000
        db.executemany("INSERT INTO messages VALUES(?,?,?,?,?,?,?,?,?)", [
            (1, 42, "main", None, plain, plain, "user", None, 1),
            (2, 42, "main", None, "风" * 10000 + "灯塔线索", structured, "character", None, 2),
            (3, 42, "A", None, branch_plain, branch_plain, "narrator", None, 3),
            (4, 42, "main", None, "hello" + "风" * 10000, normalized_only, "user", None, 4),
        ])
        db.executemany("INSERT INTO message_search_fts(rowid,tokens) VALUES(?,'hit')", [(i,) for i in range(1, 5)])
        params = dict(sessionId=42, branchId="A", query="灯塔线索", normalizedQuery="灯塔线索",
                      matchExpression="hit", exactMatch=0, indexComplete=1,
                      indexedThroughMessageId=4, limit=10, beforeMessageId=2**63 - 1)
        main = {row[0]: row[5] for row in db.execute(search_sql("searchMainMessagesIndexed"), params)}
        branch = {row[0]: row[5] for row in db.execute(search_sql("searchVisibleMessagesIndexed"), params)}
        assert len(main[1]) <= 2048 and "灯塔线索" in main[1] and main[1] != plain
        assert main[2] == structured
        assert len(branch[3]) <= 2048 and "灯塔线索" in branch[3] and branch[3] != branch_plain
        params.update(query="hello", normalizedQuery="hello")
        fallback = {row[0]: row[5] for row in db.execute(search_sql("searchMainMessagesIndexed"), params)}
        assert fallback[4] == normalized_only

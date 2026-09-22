"""Resolve Android reply selection outside a loaded UI/export page."""
import re
import sqlite3
from pathlib import Path

DAO = (Path(__file__).parents[1] / "android/app/src/main/java/com/mojing/app/data/local/dao/MessageDao.kt").read_text(encoding="utf-8")


def selection_sql(method):
    declaration = DAO.index("suspend fun " + method + "(")
    annotation = DAO[DAO.rfind("@Query(", 0, declaration):declaration]
    sql = "".join(re.findall(r'"([^"\n]*)"', annotation))
    current = re.search(r'CURRENT_MESSAGES_QUERY = """(.*?)"""', DAO, re.S)[1]
    main = re.search(r'MAIN_CONTEXT_MESSAGES_QUERY = """(.*?)"""', DAO, re.S)[1]
    visible = current + re.search(r'VISIBLE_CONTEXT_MESSAGES_QUERY = CURRENT_MESSAGES_QUERY \+ """(.*?)"""', DAO, re.S)[1]
    return sql.replace("$MAIN_CONTEXT_MESSAGES_QUERY", main).replace("$VISIBLE_CONTEXT_MESSAGES_QUERY", visible)


def test_effective_selection_is_independent_of_loaded_window():
    with sqlite3.connect(":memory:") as db:
        db.executescript("""
            CREATE TABLE messages(id INTEGER PRIMARY KEY, sessionId INTEGER, branchId TEXT,
                swipeGroupId TEXT, includeInContext INTEGER, createdAt INTEGER, regeneratedFromMessageId INTEGER);
            CREATE TABLE branch_visibility_segments(sessionId INTEGER,targetBranchId TEXT,sourceBranchId TEXT,maxMessageId INTEGER);
            CREATE TABLE branch_swipe_selections(sessionId INTEGER,branchId TEXT,swipeGroupId TEXT,selectedMessageId INTEGER);
            INSERT INTO messages VALUES(1,42,'main','reply',1,1,NULL),(501,42,'main','reply',0,501,NULL),
                (601,42,'A','reply',0,601,NULL),(701,42,'B','reply',1,701,NULL),
                (801,99,'main','reply',1,801,NULL),(901,42,'main','other',1,901,NULL);
            INSERT INTO branch_visibility_segments VALUES(42,'A','main',501),(42,'A','A',601),
                (42,'B','main',501),(42,'B','B',701);
        """)
        def selected(branch):
            method = 'getMainEffectiveSwipeSelections' if branch == 'main' else 'getVisibleEffectiveSwipeSelections'
            return db.execute(selection_sql(method), dict(sessionId=42, branchId=branch, groupIds='reply')).fetchall()
        # The loaded page may contain only 501, but the selected original is still 1.
        assert selected('main') == [(42, 'main', 'reply', 1)]
        assert selected('A') == [(42, 'A', 'reply', 1)]
        db.execute("INSERT INTO branch_swipe_selections VALUES(42,'A','reply',601)")
        assert selected('A') == [(42, 'A', 'reply', 601)]
        assert selected('main') == [(42, 'main', 'reply', 1)]
        assert selected('B') == [(42, 'B', 'reply', 701)]
        db.execute("UPDATE messages SET includeInContext=0 WHERE sessionId=42")
        assert selected('main') == [(42, 'main', 'reply', 501)]
        assert selected('A') == [(42, 'A', 'reply', 601)]
        # Branch cutoff hides the newer main variant when no explicit selection exists.
        db.execute("UPDATE branch_visibility_segments SET maxMessageId=1 WHERE targetBranchId='B' AND sourceBranchId='main'")
        db.execute("DELETE FROM messages WHERE id=701")
        assert selected('B') == [(42, 'B', 'reply', 1)]

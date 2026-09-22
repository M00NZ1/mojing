"""Exercise the Android compact preview projection against SQLite."""
import re
import sqlite3
from pathlib import Path


def test_preview_projection_keeps_raw_text_and_session_scope_without_index_columns():
    source = (Path(__file__).parents[1] / "android/app/src/main/java/com/mojing/app/data/local/dao/MessageDao.kt").read_text(encoding="utf-8")
    method = source.index("suspend fun getPreviewSourcesInSession(")
    annotation = source[source.rfind("@Query(", 0, method):method]
    sql = re.search(r'@Query\("([^"\n]+)"\)', annotation)[1]
    raw = "<NARRATION>" + "长篇正文。" * 20000 + "</NARRATION><SPEECH>结尾台词</SPEECH>"
    with sqlite3.connect(":memory:") as db:
        db.execute("CREATE TABLE messages(id INTEGER PRIMARY KEY, sessionId INTEGER, speakerType TEXT, content TEXT, searchNormalized TEXT, searchTerms TEXT, structuredContentJson TEXT)")
        db.executemany("INSERT INTO messages VALUES(?,?,?,?,?,?,?)", [
            (1, 42, "character", raw, "索引" * 20000, "分词" * 20000, "{}"),
            (2, 99, "user", "其他会话", "", "", "{}"),
        ])
        cursor = db.execute(sql, dict(sessionId=42, messageIds=1))
        assert [column[0] for column in cursor.description] == ["id", "speakerType", "content"]
        assert cursor.fetchall() == [(1, "character", raw)]
        assert db.execute(sql, dict(sessionId=42, messageIds=2)).fetchall() == []
        assert db.execute(sql, dict(sessionId=42, messageIds=999)).fetchall() == []

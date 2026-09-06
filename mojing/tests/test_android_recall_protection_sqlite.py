"""Execute the actual Android DAO SQL on isolated desktop SQLite, not Room."""
from pathlib import Path
import re
import sqlite3

DAO_ROOT = Path(__file__).parents[1] / 'android/app/src/main/java/com/mojing/app/data/local/dao'


def query(file, method):
    source = (DAO_ROOT / file).read_text(encoding='utf-8')
    found = re.search(r'@Query\("([^"\n]+)"\)\s+suspend fun ' + method + r'\(', source)
    assert found, f'Locate and review changed DAO query: {method}'
    return found.group(1)


def test_reference_count_and_preview_include_child_sources_and_stay_bounded():
    with sqlite3.connect(':memory:') as db:
        db.execute('CREATE TABLE session_branches(id INTEGER PRIMARY KEY, sessionId INTEGER, sourceMessageId INTEGER, branchId TEXT, label TEXT, isCheckpoint INTEGER)')
        db.executemany('INSERT INTO session_branches VALUES(?,?,?,?,?,?)', [(i, 42, 9, f'b{i}', '另一条路', i % 2) for i in range(1, 13)])
        db.execute("INSERT INTO session_branches VALUES(99,99,9,'other','其他会话',0)")
        params = {'sessionId': 42, 'messageIds': 9}
        assert db.execute(query('MessageDao.kt', 'countRecallReferences'), params).fetchone()[0] == 12
        assert len(db.execute(query('MessageDao.kt', 'getRecallReferences'), params).fetchall()) == 10
        assert db.execute(query('MessageDao.kt', 'countRecallReferences'), {'sessionId': 42, 'messageIds': 8}).fetchone()[0] == 0


def test_cross_branch_edits_are_distinct_from_same_branch_legacy_versions():
    with sqlite3.connect(':memory:') as db:
        db.execute('CREATE TABLE messages(id INTEGER PRIMARY KEY, sessionId INTEGER, branchId TEXT, regeneratedFromMessageId INTEGER)')
        db.executemany('INSERT INTO messages VALUES(?,?,?,?)', [(8, 42, 'main', None), (9, 42, 'main', 8), (10, 99, 'edit', 8)])
        statement = query('MessageDao.kt', 'countCrossBranchReplacements')
        params = {'sessionId': 42, 'messageIds': 8}
        assert db.execute(statement, params).fetchone()[0] == 0
        db.execute("INSERT INTO messages VALUES(11,42,'edit',8)")
        assert db.execute(statement, params).fetchone()[0] == 1
        source_query = query('SessionBranchDao.kt', 'sourceSessionId')
        assert db.execute(source_query, {'sourceId': 8}).fetchone()[0] == 42
        db.execute('DELETE FROM messages WHERE id=8')
        assert db.execute(source_query, {'sourceId': 8}).fetchone() is None

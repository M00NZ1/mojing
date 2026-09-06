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


def test_summary_tail_queries_preserve_earlier_other_branch_and_other_session_rows():
    with sqlite3.connect(':memory:') as db:
        db.executescript('''
            CREATE TABLE session_memory_segments(id INTEGER PRIMARY KEY, sessionId INTEGER, branchId TEXT, startMessageId INTEGER, endMessageId INTEGER);
            INSERT INTO session_memory_segments VALUES(1,42,'main',1,10),(2,42,'main',11,20),
              (3,42,'main',21,30),(4,42,'A',31,40),(5,42,'B',31,40),(6,99,'main',11,20);
        ''')
        count = query('MessageDao.kt', 'countMemorySegmentTail')
        delete = query('MessageDao.kt', 'deleteMemorySegmentTail')
        params = {'sessionId': 42, 'branchId': 'main', 'messageId': 15}
        assert db.execute(count, params).fetchone()[0] == 2
        assert db.execute(count, dict(params, branchId='A')).fetchone()[0] == 1
        db.execute(delete, params)
        db.execute(delete, dict(params, branchId='A'))
        assert db.execute('SELECT id FROM session_memory_segments ORDER BY id').fetchall() == [(1,), (5,), (6,)]
        assert db.execute("SELECT MAX(endMessageId) FROM session_memory_segments WHERE sessionId=42 AND branchId='main'").fetchone()[0] == 10


def test_summary_tail_delete_rolls_back_with_original_message_failure():
    with sqlite3.connect(':memory:') as db:
        db.executescript('''
            CREATE TABLE session_memory_segments(id INTEGER PRIMARY KEY, sessionId INTEGER, branchId TEXT, endMessageId INTEGER);
            CREATE TABLE messages(id INTEGER PRIMARY KEY);
            INSERT INTO session_memory_segments VALUES(1,42,'main',20),(2,42,'main',30);
            INSERT INTO messages VALUES(15);
            CREATE TRIGGER reject_delete BEFORE DELETE ON messages BEGIN SELECT RAISE(ABORT,'test failure'); END;
        ''')
        try:
            with db:
                db.execute(query('MessageDao.kt', 'deleteMemorySegmentTail'), {'sessionId': 42, 'branchId': 'main', 'messageId': 15})
                db.execute('DELETE FROM messages WHERE id=15')
        except sqlite3.IntegrityError:
            pass
        else:
            raise AssertionError('Original message deletion must fail')
        assert db.execute('SELECT COUNT(*) FROM session_memory_segments').fetchone()[0] == 2
        assert db.execute('SELECT id FROM messages').fetchall() == [(15,)]


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

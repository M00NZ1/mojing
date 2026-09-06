"""Run actual Android context-source SQL on isolated desktop SQLite, not Room."""
from pathlib import Path
import re
import sqlite3

DAO = (Path(__file__).parents[1] / 'android/app/src/main/java/com/mojing/app/data/local/dao/MessageDao.kt').read_text(encoding='utf-8')


def query(method):
    raw = re.search(r'@Query\("([^"\n]+)"\)\s+suspend fun ' + method + r'\(', DAO).group(1)
    constants = {
        name: (f'${prefix}' if prefix else '') + body
        for name, prefix, body in re.findall(
            r'private const val (\w+) = (?:(\w+) \+ )?"""(.*?)"""', DAO, re.S
        )
    }
    for _ in range(3):
        raw = re.sub(r'\$(\w+)', lambda match: constants[match[1]], raw)
    assert '$' not in raw
    return raw.replace('IN (:messageIds)', 'IN (1,2,3,4)')


def test_sources_use_effective_reply_selection_and_branch_visibility():
    with sqlite3.connect(':memory:') as db:
        db.executescript('''
            CREATE TABLE messages(id INTEGER PRIMARY KEY, sessionId INTEGER, branchId TEXT,
              swipeGroupId TEXT, includeInContext INTEGER, createdAt INTEGER, regeneratedFromMessageId INTEGER);
            CREATE TABLE branch_visibility_segments(sessionId INTEGER, targetBranchId TEXT, sourceBranchId TEXT, maxMessageId INTEGER);
            CREATE TABLE branch_swipe_selections(sessionId INTEGER, branchId TEXT, swipeGroupId TEXT, selectedMessageId INTEGER);
            INSERT INTO messages VALUES(1,42,'main','g',1,1,NULL),(2,42,'main','g',0,2,NULL),
              (3,42,'main',NULL,0,3,NULL),(4,42,'sibling',NULL,1,4,NULL);
            INSERT INTO branch_visibility_segments VALUES(42,'A','main',3);
            INSERT INTO branch_swipe_selections VALUES(42,'A','g',2);
        ''')
        params = {'sessionId': 42, 'branchId': 'A'}
        assert [row[0] for row in db.execute(query('getMainEventSources'), params)] == [1]
        assert [row[0] for row in db.execute(query('getVisibleEventSources'), params)] == [2]
        db.execute("UPDATE branch_swipe_selections SET selectedMessageId=1 WHERE branchId='A'")
        assert [row[0] for row in db.execute(query('getVisibleEventSources'), params)] == [1]
        db.execute('DELETE FROM messages WHERE id=1')
        assert all(row[0] != 1 for row in db.execute(query('getVisibleEventSources'), params))


def test_equivalent_event_lookup_keeps_branch_character_and_source_scope():
    with sqlite3.connect(':memory:') as db:
        db.executescript('''
            CREATE TABLE session_event_nodes(id INTEGER PRIMARY KEY, sessionId INTEGER, branchId TEXT,
              characterId INTEGER, messageId INTEGER, title TEXT);
            INSERT INTO session_event_nodes VALUES(1,42,'main',NULL,8,'钥匙'),(2,42,'A',9,8,'钥匙');
        ''')
        params = {'sessionId': 42, 'branchId': 'main', 'characterId': None, 'messageId': 8, 'title': '钥匙'}
        assert db.execute(query('findEquivalentEvent'), params).fetchone() == (1,)
        assert db.execute(query('findEquivalentEvent'), dict(params, branchId='A')).fetchone() is None
        assert db.execute(query('findEquivalentEvent'), dict(params, branchId='A', characterId=9)).fetchone() == (2,)
        assert db.execute(query('findEquivalentEvent'), dict(params, sessionId=99)).fetchone() is None

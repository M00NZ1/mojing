"""Run the Android inherited-summary query against isolated SQLite fixtures."""
import re
import sqlite3
from pathlib import Path


def test_inherited_summaries_follow_reply_selection_and_edit_cutoff():
    source = (Path(__file__).parents[1] / 'android/app/src/main/java/com/mojing/app/data/local/dao/SessionMemorySegmentDao.kt').read_text(encoding='utf-8')
    sql = re.search(r'VISIBLE_MEMORY_SEGMENTS_QUERY = """(.*?)"""', source, re.S).group(1)
    with sqlite3.connect(':memory:') as db:
        db.executescript('''
            CREATE TABLE branch_visibility_segments(sessionId INTEGER,targetBranchId TEXT,sourceBranchId TEXT,maxMessageId INTEGER);
            CREATE TABLE messages(id INTEGER PRIMARY KEY,sessionId INTEGER,branchId TEXT,swipeGroupId TEXT,regeneratedFromMessageId INTEGER);
            CREATE TABLE session_memory_segments(id INTEGER PRIMARY KEY,sessionId INTEGER,branchId TEXT,startMessageId INTEGER,endMessageId INTEGER);
            CREATE TABLE branch_swipe_selections(sessionId INTEGER,branchId TEXT,swipeGroupId TEXT,selectedMessageId INTEGER);
            INSERT INTO branch_visibility_segments VALUES(42,'A','main',30),(42,'A','A',100),(42,'B','main',30),(42,'B','B',100);
            INSERT INTO messages VALUES(8,42,'main','g',NULL),(12,42,'main','g',NULL);
            INSERT INTO session_memory_segments VALUES(1,42,'main',1,5),(2,42,'main',6,15),(3,42,'main',16,25),(4,42,'A',6,30);
        ''')
        def ids(branch):
            return {row[0] for row in db.execute(sql, {'sessionId':42, 'branchId':branch})}
        assert ids('A') == {1, 2, 3, 4}
        db.execute("INSERT INTO branch_swipe_selections VALUES(42,'A','g',12)")
        assert ids('A') == {1, 4}  # own recomputed summary remains usable
        assert ids('B') == {1, 2, 3}
        db.execute("INSERT INTO branch_swipe_selections VALUES(42,'main','g',12)")
        assert ids('A') == {1, 2, 3, 4}
        assert ids('B') == {1}
        db.execute("INSERT INTO messages VALUES(31,42,'A',NULL,8)")
        assert ids('A') == {1, 4}  # editing invalidates inherited later summaries too
        assert db.execute('SELECT COUNT(*) FROM session_memory_segments').fetchone()[0] == 4

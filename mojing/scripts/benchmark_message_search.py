"""Repeatable isolated SQLite benchmark. Never opens the configured product DB."""
import json
import platform
import sqlite3
import statistics
import sys
import tempfile
import time
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import tiktoken
from sqlalchemy import create_engine, event, insert
from sqlalchemy.orm import Session

from backend.app.database import Base
from backend.app.models import ChatSessionModel, MessageModel
from backend.app.services.chat_service import (
    get_session_message_window, get_session_messages_page, search_session_messages,
)
from backend.app.services.message_search_service import advance_message_search_index, search_message_page


def timed(operation):
    start = time.perf_counter()
    result = operation()
    return (time.perf_counter() - start) * 1000, result


def median_ms(operation):
    return round(statistics.median(timed(operation)[0] for _ in range(7)), 2)


def main():
    # Cached tokenizer only: a missing cache fails explicitly, never downloads.
    with patch('requests.get', side_effect=RuntimeError('cl100k_base must already be cached')):
        encoding = tiktoken.get_encoding('cl100k_base')
    paragraph = '沈照收起信纸，望向雾港的旧灯塔。林汐把航海日志放到桌上，指出潮汐时间与船只离港记录的矛盾。窗外雨声渐密，两人决定先询问码头的守夜人，再去查阅保存于档案馆的旧地图。玩家保留了那封没有署名的来信，没有将其中的秘密告诉旁人。'
    messages = [f'第 {i} 夜，记录编号 {i:06d}。{paragraph * 2}' + ('隐藏证词七号' if i in (3, 2003, 4003) else '') for i in range(1, 6001)]
    report = {'os': platform.system(), 'python': platform.python_version(), 'sqlite': sqlite3.sqlite_version,
              'messages': len(messages), 'characters': sum(map(len, messages)),
              'tokens_cl100k_base': sum(len(encoding.encode(body)) for body in messages)}
    assert report['tokens_cl100k_base'] >= 1_000_000
    with tempfile.TemporaryDirectory(prefix='mojing-search-benchmark-') as temporary:
        path = Path(temporary) / 'fixture.db'
        engine = create_engine(f'sqlite:///{path}')
        try:
            Base.metadata.create_all(engine)
            with Session(engine) as db:
                db.add(ChatSessionModel(id=1, title='百万 Token 隔离样本'))
                db.flush()
                db.execute(insert(MessageModel), [{'session_id': 1, 'content': body} for body in messages])
                db.commit()
                report['db_bytes_before_index'] = path.stat().st_size
                query = '隐藏证词七号'
                report['legacy_search_median_ms'] = median_ms(lambda: search_session_messages(db, 1, query))
                batches = []
                while True:
                    duration, progress = timed(lambda: advance_message_search_index(db, 1))
                    batches.append(duration)
                    if progress['ready']:
                        break
                report.update(index_batches=len(batches), index_total_ms=round(sum(batches), 2),
                              index_batch_median_ms=round(statistics.median(batches), 2),
                              index_batch_max_ms=round(max(batches), 2), db_bytes_after_index=path.stat().st_size)
                report['indexed_search_median_ms'] = median_ms(lambda: search_message_page(db, 1, query))
                report['common_search_median_ms'] = median_ms(lambda: search_message_page(db, 1, '雾港'))
                report['open_recent_page_median_ms'] = median_ms(lambda: get_session_messages_page(db, 1, None, 40))
                report['old_message_window_median_ms'] = median_ms(lambda: get_session_message_window(db, 1, 3, radius=20))
                result = search_message_page(db, 1, query)
                assert [item['id'] for item in result['items']] == [4003, 2003, 3]
                statements = []
                def capture(conn, cursor, statement, params, context, executemany):
                    if 'MATCH' in statement and statement.lstrip().startswith('SELECT'):
                        statements.append((statement, params))
                event.listen(engine, 'before_cursor_execute', capture)
                search_message_page(db, 1, query)
                event.remove(engine, 'before_cursor_execute', capture)
                report['query_plan'] = [list(row) for row in db.connection().exec_driver_sql('EXPLAIN QUERY PLAN ' + statements[-1][0], statements[-1][1])]
        finally:
            engine.dispose()
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()

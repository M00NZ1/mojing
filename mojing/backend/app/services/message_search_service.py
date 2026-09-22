"""Versioned, rebuildable local substring index; original messages remain authoritative."""
import sqlite3
from itertools import chain

from sqlalchemy import column, event, func, select, table, text
from sqlalchemy.engine import Engine

from ..models import CharacterModel, MessageModel

VERSION = 1
BATCH_SIZE = 200
TRIGGERS = ('mojing_message_search_insert', 'mojing_message_search_update', 'mojing_message_search_delete')


def normalize_search(value):
    return (value or '').casefold()


def search_terms(value):
    value = normalize_search(value)
    singles = (f'c{ord(char):x}' for char in value)
    pairs = (f'b{ord(a):x}x{ord(b):x}' for a, b in zip(value, value[1:]))
    return ' '.join(dict.fromkeys(chain(singles, pairs)))


def register_search_functions(connection, *_):
    if isinstance(connection, sqlite3.Connection):
        connection.create_function('mojing_search_normalize', 1, normalize_search, deterministic=True)


event.listen(Engine, 'connect', register_search_functions)
event.listen(Engine, 'checkout', register_search_functions)


def _begin_write(db):
    connection = db.connection().connection.driver_connection
    register_search_functions(connection)
    if not connection.in_transaction:
        db.execute(text('BEGIN IMMEDIATE'))


def ensure_message_search_index(db):
    try:
        _begin_write(db)
        db.execute(text('CREATE TABLE IF NOT EXISTS mojing_message_search_meta (version INTEGER NOT NULL)'))
        version = db.scalar(text('SELECT version FROM mojing_message_search_meta LIMIT 1'))
        if version is not None and version != VERSION:
            raise ValueError('搜索索引版本不受支持，请使用对应版本的应用；原始对话未改变')
        if version is None:
            db.execute(text('INSERT INTO mojing_message_search_meta(version) VALUES (:version)'), {'version': VERSION})
        exists = db.scalar(text("SELECT 1 FROM sqlite_master WHERE type='table' AND name='mojing_message_search_fts'"))
        db.execute(text('CREATE TABLE IF NOT EXISTS mojing_message_search_state (session_id INTEGER PRIMARY KEY, cursor INTEGER, done INTEGER NOT NULL DEFAULT 0, indexed_count INTEGER NOT NULL DEFAULT 0)'))
        if not exists:
            # SQLite 3.43+ avoids storing a second copy of generated terms.
            mode = ", content='', contentless_delete=1" if sqlite3.sqlite_version_info >= (3, 43, 0) else ''
            db.execute(text(f"CREATE VIRTUAL TABLE mojing_message_search_fts USING fts5(terms, detail=none{mode})"))
            db.execute(text('UPDATE mojing_message_search_state SET cursor=NULL, done=0, indexed_count=0'))
        installed = set(db.scalars(text("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'mojing_message_search_%'")))
        if not set(TRIGGERS).issubset(installed):
            db.execute(text('UPDATE mojing_message_search_state SET cursor=NULL,done=0,indexed_count=0'))
        # Native SQL change capture keeps source writes independent of Python/FTS.
        # It also works while an older app or a local import tool writes the DB.
        db.execute(text('CREATE TABLE IF NOT EXISTS mojing_message_search_pending (message_id INTEGER PRIMARY KEY, session_id INTEGER NOT NULL)'))
        db.execute(text('CREATE INDEX IF NOT EXISTS ix_mojing_search_pending_session ON mojing_message_search_pending(session_id,message_id)'))
        db.execute(text(f'''CREATE TRIGGER IF NOT EXISTS {TRIGGERS[0]} AFTER INSERT ON messages BEGIN
            INSERT OR REPLACE INTO mojing_message_search_pending VALUES(new.id,new.session_id); END'''))
        db.execute(text(f'''CREATE TRIGGER IF NOT EXISTS {TRIGGERS[1]} AFTER UPDATE OF content, session_id, id ON messages BEGIN
            INSERT OR REPLACE INTO mojing_message_search_pending VALUES(old.id,old.session_id);
            INSERT OR REPLACE INTO mojing_message_search_pending VALUES(new.id,new.session_id); END'''))
        db.execute(text(f'''CREATE TRIGGER IF NOT EXISTS {TRIGGERS[2]} AFTER DELETE ON messages BEGIN
            INSERT OR REPLACE INTO mojing_message_search_pending VALUES(old.id,old.session_id); END'''))
        db.commit()
    except Exception:
        db.rollback()
        raise


def advance_message_search_index(db, session_id):
    ensure_message_search_index(db)
    try:
        _begin_write(db)
        db.execute(text('INSERT OR IGNORE INTO mojing_message_search_state(session_id) VALUES (:id)'), {'id': session_id})
        state = db.execute(text('SELECT cursor,done,indexed_count FROM mojing_message_search_state WHERE session_id=:id'), {'id': session_id}).mappings().one()
        # Backfill and change replay are bounded and committed with their cursors.
        rows = []
        if not state['done']:
            stmt = select(MessageModel.id, MessageModel.content).where(MessageModel.session_id == session_id)
            if state['cursor'] is not None:
                stmt = stmt.where(MessageModel.id < state['cursor'])
            rows = list(db.execute(stmt.order_by(MessageModel.id.desc()).limit(BATCH_SIZE)))
            if rows:
                db.execute(text('INSERT OR REPLACE INTO mojing_message_search_fts(rowid,terms) VALUES (:id,:terms)'),
                    [{'id': row.id, 'terms': search_terms(row.content) + f' s{session_id}'} for row in rows])
        pending = list(db.execute(text('''SELECT p.message_id,m.content,m.session_id FROM mojing_message_search_pending p
            LEFT JOIN messages m ON m.id=p.message_id WHERE p.session_id=:id ORDER BY p.message_id LIMIT :limit'''),
            {'id': session_id, 'limit': BATCH_SIZE}))
        for message_id, content, current_session_id in pending:
            if content is None:
                db.execute(text('DELETE FROM mojing_message_search_fts WHERE rowid=:id'), {'id': message_id})
            else:
                db.execute(text('INSERT OR REPLACE INTO mojing_message_search_fts(rowid,terms) VALUES (:id,:terms)'),
                    {'id': message_id, 'terms': search_terms(content) + f' s{current_session_id}'})
            db.execute(text('DELETE FROM mojing_message_search_pending WHERE message_id=:id'), {'id': message_id})
        count = state['indexed_count'] + len(rows)
        backfill_done = bool(state['done']) or len(rows) < BATCH_SIZE
        db.execute(text('UPDATE mojing_message_search_state SET cursor=:cursor,done=:done,indexed_count=:count WHERE session_id=:id'),
            {'cursor': rows[-1].id if rows else state['cursor'], 'done': int(backfill_done), 'count': count, 'id': session_id})
        still_pending = db.scalar(text('SELECT 1 FROM mojing_message_search_pending WHERE session_id=:id LIMIT 1'), {'id': session_id})
        db.commit()
        return {'ready': backfill_done and not still_pending, 'indexed_count': count}
    except Exception:
        db.rollback()
        raise


def get_message_search_progress(db, session_id):
    ensure_message_search_index(db)
    state = db.execute(text('SELECT done,indexed_count FROM mojing_message_search_state WHERE session_id=:id'), {'id': session_id}).mappings().first()
    pending = db.scalar(text('SELECT 1 FROM mojing_message_search_pending WHERE session_id=:id LIMIT 1'), {'id': session_id})
    return {'ready': bool(state and state['done'] and not pending), 'indexed_count': state['indexed_count'] if state else 0}


def rebuild_message_search_index(db):
    ensure_message_search_index(db)
    try:
        _begin_write(db)
        for trigger in TRIGGERS:
            db.execute(text(f'DROP TRIGGER IF EXISTS {trigger}'))
        db.execute(text('DROP TABLE mojing_message_search_fts'))
        db.execute(text('UPDATE mojing_message_search_state SET cursor=NULL,done=0,indexed_count=0'))
        # DDL and metadata reset share one transaction. No original rows change.
        ensure_message_search_index(db)
    except Exception:
        db.rollback()
        raise


def disable_message_search_triggers_for_downgrade(db):
    """Optional rollback: stop change capture without touching original messages."""
    try:
        _begin_write(db)
        for trigger in TRIGGERS:
            db.execute(text(f'DROP TRIGGER IF EXISTS {trigger}'))
        if db.scalar(text("SELECT 1 FROM sqlite_master WHERE name='mojing_message_search_state'")):
            db.execute(text('UPDATE mojing_message_search_state SET cursor=NULL,done=0,indexed_count=0'))
        db.commit()
    except Exception:
        db.rollback()
        raise


def _snippet(content, query):
    folded = normalize_search(content)
    offset = folded.find(query)
    if len(folded) != len(content):
        position = 0
        for index, char in enumerate(content):
            if position >= offset:
                offset = index
                break
            position += len(char.casefold())
    start, end = max(offset - 24, 0), min(offset + len(query) + 36, len(content))
    return ('…' if start else '') + content[start:end].strip() + ('…' if end < len(content) else '')


def search_message_page(db, session_id, keyword, branch_id='main', before_id=None, limit=25, advance_index=True):
    from .chat_service import resolve_branch_context, _visibility_clause
    context = resolve_branch_context(db, session_id, branch_id)
    query = normalize_search(keyword.strip())
    if not query:
        return {'items': [], 'next_cursor': None, 'total_count': 0, 'index': {'ready': True, 'indexed_count': 0}}
    if len(query) > 256:
        raise ValueError('搜索词最多 256 个字符')
    progress = advance_message_search_index(db, session_id) if advance_index else get_message_search_progress(db, session_id)
    terms = [f'b{ord(a):x}x{ord(b):x}' for a, b in zip(query, query[1:])] if len(query) > 1 else [f'c{ord(query):x}']
    expression = f's{session_id} AND ' + ' AND '.join(dict.fromkeys(terms))
    fts = table('mojing_message_search_fts', column('rowid'))
    message = MessageModel
    stmt = select(message.id, message.session_id, message.speaker_type, message.character_id,
        CharacterModel.name.label('character_name'), message.branch_id, message.content, message.created_at)
    stmt = stmt.select_from(fts.join(message, message.id == fts.c.rowid).outerjoin(CharacterModel, CharacterModel.id == message.character_id))
    stmt = stmt.where(text('mojing_message_search_fts MATCH :expression'), message.session_id == session_id,
        _visibility_clause(context, message), func.instr(func.mojing_search_normalize(message.content), query) > 0)
    total_count = None
    if progress['ready']:
        count_stmt = select(func.count()).select_from(
            fts.join(message, message.id == fts.c.rowid)
        ).where(
            text('mojing_message_search_fts MATCH :expression'), message.session_id == session_id,
            _visibility_clause(context, message), func.instr(func.mojing_search_normalize(message.content), query) > 0,
        )
        total_count = db.scalar(count_stmt, {'expression': expression})
    if before_id is not None:
        stmt = stmt.where(fts.c.rowid < before_id)
    limit = min(max(limit, 1), 100)
    rows = list(db.execute(stmt.order_by(fts.c.rowid.desc()).limit(limit + 1), {'expression': expression}).mappings())
    return {'items': [{**{key: value for key, value in row.items() if key != 'content'}, 'snippet': _snippet(row['content'], query)} for row in rows[:limit]],
        'next_cursor': rows[limit - 1]['id'] if len(rows) > limit else None, 'total_count': total_count, 'index': progress}

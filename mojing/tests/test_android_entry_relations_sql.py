"""Current relation projection executed on SQLite; separate from Room/UI evidence."""
import json
import sqlite3
from pathlib import Path
import pytest
from tests.test_android_library_sql import queries


@pytest.fixture
def database():
    db = sqlite3.connect(':memory:')
    db.row_factory = sqlite3.Row
    schema_dir = Path(__file__).resolve().parents[1] / 'android/app/schemas/com.mojing.app.data.local.AppDatabase'
    schema = json.loads(max(schema_dir.glob('*.json'), key=lambda p: int(p.stem)).read_text())
    for entity in schema['database']['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    yield db
    db.close()


def insert(db, table, **values):
    row = {c['name']: ('' if c['type'] == 'TEXT' else 0) if c['notnull'] else None
           for c in db.execute(f'PRAGMA table_info({table})') if c['name'] != 'id'}
    row.update(values)
    return db.execute(f"INSERT INTO {table} ({','.join(row)}) VALUES ({','.join('?' for _ in row)})", list(row.values())).lastrowid


def page(db, enc, entry, cursor=2**63-1):
    return list(db.execute(queries('EntryRelationDao.kt')['getEntryPage'], dict(encId=enc, entryId=entry, beforeId=cursor, limit=25)))


@pytest.mark.parametrize('count', [0, 1, 23, 24, 25, 49])
def test_boundaries_and_both_directions(database, count):
    db = database
    enc = insert(db, 'world_encyclopedias', name='世界')
    entry = insert(db, 'encyclopedia_entries', encyclopediaId=enc, title='当前')
    others = [insert(db, 'encyclopedia_entries', encyclopediaId=enc, title=f'人物{i}', entryType='faction', content='长正文'*1000) for i in range(count)]
    ids = []
    for i, other in enumerate(others):
        ids.append(insert(db, 'entry_relations', encyclopediaId=enc, fromEntryId=entry if i%2 else other,
                          toEntryId=other if i%2 else entry, label='长备注'*1000, metadataJson='大字段'*1000))
    rows = page(db, enc, entry)
    assert len(rows) == min(count, 25)
    all_ids = []
    while rows:
        visible = rows[:24]
        all_ids += [r['id'] for r in visible]
        assert all(len(r['label']) == 240 and r['entryType'] == 'faction' for r in visible)
        assert set(visible[0].keys()) == {'id','fromEntryId','toEntryId','relationType','label','otherEntryId','title','entryType'}
        rows = page(db, enc, entry, visible[-1]['id'])
    assert all_ids == ids[::-1]


def test_owner_checks_and_deleted_cursor(database):
    db = database
    a = insert(db, 'world_encyclopedias', name='A'); b = insert(db, 'world_encyclopedias', name='B')
    x = insert(db, 'encyclopedia_entries', encyclopediaId=a); y = insert(db, 'encyclopedia_entries', encyclopediaId=a)
    z = insert(db, 'encyclopedia_entries', encyclopediaId=b)
    valid = insert(db, 'entry_relations', encyclopediaId=a, fromEntryId=x, toEntryId=y)
    corrupt = insert(db, 'entry_relations', encyclopediaId=a, fromEntryId=x, toEntryId=z)
    wrong = insert(db, 'entry_relations', encyclopediaId=b, fromEntryId=x, toEntryId=y)
    assert [r['id'] for r in page(db,a,x)] == [valid]
    assert page(db,b,x) == [] and page(db,a,0) == []
    db.execute('DELETE FROM entry_relations WHERE id=?', (corrupt,))
    assert [r['id'] for r in page(db,a,x,corrupt)] == [valid]
    db.execute('UPDATE encyclopedia_entries SET encyclopediaId=? WHERE id=?', (b,y))
    assert page(db,a,x) == []

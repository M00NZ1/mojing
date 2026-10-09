"""Execute the current Room endpoint projection; no application data."""
from tests.test_android_entry_relations_sql import database, insert
from tests.test_android_library_sql import queries


def test_endpoint_covers_outside_entry_page_are_scoped_and_lightweight(database):
    db = database
    enc = insert(db, 'world_encyclopedias', name='A')
    other = insert(db, 'world_encyclopedias', name='B')
    for i in range(100):
        insert(db, 'encyclopedia_entries', encyclopediaId=enc, title=f'人物{i}', entryType='character')
    ids = [insert(db, 'encyclopedia_entries', encyclopediaId=enc, title=f'地点{i}', entryType='location',
                  coverImagePath=f'/owned/{i}.png', content='长正文'*10000, metaJson='大元数据'*10000) for i in range(48)]
    foreign = insert(db, 'encyclopedia_entries', encyclopediaId=other, coverImagePath='/foreign.png')
    query = queries('EncyclopediaEntryDao.kt')['getRelationEndpointsByIds']
    selected = ids + [foreign]
    expanded = query.replace(':ids', ','.join('?' for _ in selected)).replace(':encId', '?')
    rows = list(db.execute(expanded, [enc, *selected]))
    assert len(rows) == 48
    assert {r['id'] for r in rows} == set(ids)
    assert all(set(r.keys()) == {'id', 'title', 'entryType', 'coverImagePath'} for r in rows)
    assert [r['coverImagePath'] for r in rows] == [f'/owned/{i}.png' for i in range(48)]
    db.execute('UPDATE encyclopedia_entries SET coverImagePath=? WHERE id=?', ('/changed.png', ids[0]))
    db.execute('DELETE FROM encyclopedia_entries WHERE id=?', (ids[-1],))
    rows = list(db.execute(expanded, [enc, *selected]))
    assert len(rows) == 47 and rows[0]['coverImagePath'] == '/changed.png'

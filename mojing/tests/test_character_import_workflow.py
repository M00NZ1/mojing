import base64
import io
import json
from datetime import datetime

import pytest
from fastapi import HTTPException, UploadFile
from PIL import Image, PngImagePlugin
from sqlalchemy import create_engine, event, select
from sqlalchemy.orm import Session

from backend.app.database import Base
from backend.app.models import CharacterModel, CharacterProfileModel
from backend.app.routes import characters
from backend.app.services.character_portable_service import PORTABLE_KIND, build_portable_payload, portable_to_txt
from backend.app.utils.docx_simple import write_plain_docx


@pytest.fixture
def db(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'imports.db'}", connect_args={'check_same_thread': False})
    @event.listens_for(engine, 'connect')
    def foreign_keys(connection, _): connection.execute('PRAGMA foreign_keys=ON')
    Base.metadata.create_all(engine)
    with Session(engine) as session:
        session.add(CharacterModel(name='已有角色', persona_prompt='保留原文'))
        session.commit()
        yield session
    engine.dispose()


def upload(name, data):
    return UploadFile(filename=name, file=io.BytesIO(data))


def portable(**fields):
    return {'kind': PORTABLE_KIND, 'version': 1, 'name': '已有角色', 'persona_prompt': '新角色设定',
        'temperature': 0, 'profile': {'raw_persona_text': '完整来源', 'character_card_json': {'facts': ['身份']}}, **fields}


def import_portable(db, payload):
    return characters.import_character_portable(upload('story.json', json.dumps(payload).encode()), db)


@pytest.mark.parametrize('failure', ['profile', 'commit'])
def test_failed_import_keeps_originals_and_retry_creates_one_complete_role(db, failure):
    def fail_insert(connection, cursor, statement, parameters, context, executemany):
        if statement.startswith('INSERT INTO character_profiles'): raise RuntimeError('profile write failed')
    def fail_commit(session): raise RuntimeError('commit failed')
    target, name, handler = (db.bind, 'before_cursor_execute', fail_insert) if failure == 'profile' else (db, 'before_commit', fail_commit)
    event.listen(target, name, handler)
    try:
        with pytest.raises(RuntimeError): import_portable(db, portable())
    finally:
        event.remove(target, name, handler)
    assert [(row.name, row.persona_prompt) for row in db.scalars(select(CharacterModel))] == [('已有角色', '保留原文')]
    assert db.scalar(select(CharacterProfileModel)) is None
    result = import_portable(db, portable())
    assert result.name != '已有角色'
    assert len(list(db.scalars(select(CharacterModel)))) == 2
    assert db.scalar(select(CharacterProfileModel)).raw_persona_text == '完整来源'


@pytest.mark.parametrize('format', ['json', 'txt', 'docx'])
def test_portable_roundtrip_order_and_zero_temperature(db, format):
    source = portable()
    raw = json.dumps(source).encode() if format == 'json' else portable_to_txt(source).encode()
    if format == 'docx': raw = write_plain_docx(portable_to_txt(source))
    imported = characters.import_character_portable(upload(f'角色.{format}', raw), db)
    assert imported.temperature == 0
    assert imported.api_key == ''
    assert db.scalar(select(CharacterProfileModel).where(CharacterProfileModel.character_id == imported.id)).raw_persona_text == '完整来源'
    row = db.get(CharacterModel, imported.id)
    assert build_portable_payload(row, None)['temperature'] == 0
    assert characters.list_characters(db)[0].id == imported.id
    original = db.get(CharacterModel, 1)
    original.persona_prompt = '编辑不改变普通列表次序'
    db.commit()
    assert characters.list_characters(db)[0].id == imported.id
    original.favorite = True
    db.commit()
    assert characters.list_characters(db)[0].id == original.id


def test_creation_time_order_and_id_tie_breaker(db):
    db.get(CharacterModel, 1).created_at = datetime(2025, 1, 1)
    db.add_all([CharacterModel(name='较新', created_at=datetime(2025, 3, 1)), CharacterModel(name='较旧', created_at=datetime(2025, 2, 1)), CharacterModel(name='同秒新建', created_at=datetime(2025, 3, 1))])
    db.commit()
    assert [row.name for row in characters.list_characters(db)] == ['同秒新建', '较新', '较旧', '已有角色']


@pytest.mark.parametrize('raw', [b'[]', b'null', b'{"data":[]}', b'\xff', b'{'])
def test_invalid_json_returns_actionable_error_without_writes(db, raw):
    with pytest.raises(HTTPException) as error:
        characters.import_character_card_json(upload('CARD.JSON', raw), db)
    assert error.value.status_code == 400
    assert len(list(db.scalars(select(CharacterModel)))) == 1


def png_card(name):
    metadata = PngImagePlugin.PngInfo()
    metadata.add_text('chara', base64.b64encode(json.dumps({'spec': 'chara_card_v2', 'data': {'name': name, 'description': '完整设定'}}).encode()).decode())
    buffer = io.BytesIO()
    Image.new('RGB', (1, 1)).save(buffer, format='PNG', pnginfo=metadata)
    return buffer.getvalue()


def test_same_filename_pngs_do_not_touch_existing_temp_files(db, tmp_path, monkeypatch):
    storage = tmp_path / 'storage'
    (storage / 'temp').mkdir(parents=True)
    protected = storage / 'temp/import_card_same.png'
    protected.write_bytes(b'pre-existing protected content')
    monkeypatch.setattr(characters, 'STORAGE_DIR', storage)
    for name in ['第一位', '第二位']:
        result = characters.import_character_card(upload('same.png', png_card(name)), db)
        assert result.name == name
    assert protected.read_bytes() == b'pre-existing protected content'
    assert len(list((storage / 'temp').iterdir())) == 1


def test_json_card_and_link_keep_full_profile(db, monkeypatch):
    result = characters.import_character_card_json(upload('CARD.JSON', '\ufeff{"spec":"chara_card_v2","data":{"name":"新角色","description":"正文","first_mes":"开场"}}'.encode()), db)
    assert db.scalar(select(CharacterProfileModel).where(CharacterProfileModel.character_id == result.id)).character_card_json['tavern_chara_card_v2']['data']['first_mes'] == '开场'
    import urllib.request
    def download(url, timeout):
        assert timeout == 30
        return io.BytesIO(png_card('链接角色'))
    monkeypatch.setattr(urllib.request, 'urlopen', download)
    assert characters.import_character_from_url({'url': 'https://example.invalid/card.png'}, db).name == '链接角色'


def test_future_portable_text_version_and_bad_parameters_do_not_write(db):
    text = portable_to_txt(portable(version=99))
    with pytest.raises(HTTPException):
        characters.import_character_portable(upload('future.txt', text.encode()), db)
    with pytest.raises(HTTPException): import_portable(db, portable(temperature='invalid'))
    assert len(list(db.scalars(select(CharacterModel)))) == 1


def test_legacy_optional_parameters_remain_compatible(db):
    result = import_portable(db, portable(version='1', temperature=None, top_p='', max_tokens=None))
    assert result.temperature == .9
    assert result.top_p == 1
    assert result.max_tokens == 1200


def test_docx_preserves_paragraphs_entities_runs_and_breaks():
    import zipfile
    from backend.app.utils.docx_text import extract_docx_plain_text
    raw = io.BytesIO()
    with zipfile.ZipFile(raw, 'w') as archive:
        archive.writestr('word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>沈照 &amp; </w:t></w:r><w:r><w:t>林汐 &lt;旧信&gt;</w:t><w:tab/><w:t>档案</w:t><w:br/><w:t>第二行</w:t></w:r></w:p><w:p/><w:p><w:r><w:t>下一段</w:t></w:r></w:p></w:body></w:document>')
    assert extract_docx_plain_text(raw.getvalue()) == '沈照 & 林汐 <旧信>\t档案\n第二行\n\n下一段'


def test_upload_limit_and_link_timeout_do_not_write(db, monkeypatch):
    import urllib.request
    with pytest.raises(HTTPException) as error:
        characters.import_character_card_json(upload('large.json', b' ' * (32 * 1024 * 1024 + 1)), db)
    assert error.value.status_code == 413
    def timeout(*args, **kwargs): raise TimeoutError('test timeout')
    monkeypatch.setattr(urllib.request, 'urlopen', timeout)
    with pytest.raises(HTTPException) as error:
        characters.import_character_from_url({'url': 'https://example.invalid/card.png'}, db)
    assert error.value.status_code == 400
    assert len(list(db.scalars(select(CharacterModel)))) == 1


def test_http_uploads_and_sorted_library_contract(db):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from backend.app.database import get_db
    app = FastAPI()
    app.include_router(characters.router)
    app.dependency_overrides[get_db] = lambda: db
    with TestClient(app) as client:
        result = client.post('/characters/import-portable', files={'file': ('角色.json', json.dumps(portable()).encode(), 'application/json')})
        assert result.status_code == 200
        assert result.json()['api_key'] == ''
        assert client.get('/characters').json()[0]['id'] == result.json()['id']
        bad = client.post('/characters/import-card-json', files={'file': ('bad.json', b'[]', 'application/json')})
        assert bad.status_code == 400
        assert len(client.get('/characters').json()) == 2


def test_tavern_profile_failure_rolls_back_character(db):
    def fail_insert(connection, cursor, statement, parameters, context, executemany):
        if statement.startswith('INSERT INTO character_profiles'): raise RuntimeError('test write failure')
    event.listen(db.bind, 'before_cursor_execute', fail_insert)
    try:
        with pytest.raises(RuntimeError):
            characters.import_character_card(upload('card.png', png_card('不能留下半角色')), db)
    finally:
        event.remove(db.bind, 'before_cursor_execute', fail_insert)
    assert len(list(db.scalars(select(CharacterModel)))) == 1

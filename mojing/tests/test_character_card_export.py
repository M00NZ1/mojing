import base64
import io
import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from PIL import Image, PngImagePlugin
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

from backend.app.database import Base, get_db
from backend.app.models import CharacterModel
from backend.app.routes import characters
from backend.app.services.character_card_service import (
    read_character_card_from_png_bytes,
    write_character_card_to_png,
)


@pytest.fixture
def exported_role(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'roles.db'}", connect_args={'check_same_thread': False})
    Base.metadata.create_all(engine)
    storage = tmp_path / 'storage'
    storage.mkdir()
    monkeypatch.setattr(characters, 'STORAGE_DIR', storage)
    with Session(engine) as db:
        role = CharacterModel(name='雾港/来信', persona_prompt=('长篇设定🙂\n' * 3000) + '最后一条秘密', api_key='private-test-key')
        db.add(role)
        db.commit()
        app = FastAPI()
        app.include_router(characters.router)
        app.dependency_overrides[get_db] = lambda: db
        with TestClient(app) as client:
            yield db, role, storage, client
    engine.dispose()


def read_with_standard_png_decoder(raw):
    # Pillow stops metadata parsing at IEND, independently of the app's codec.
    with Image.open(io.BytesIO(raw)) as image:
        image.load()
        return json.loads(base64.b64decode(image.info['chara']).decode())


def test_full_text_download_roundtrip_and_repeated_export(exported_role):
    db, role, storage, client = exported_role
    original_text = role.persona_prompt
    first = client.get(f'/characters/{role.id}/export-card')
    assert first.status_code == 200
    assert first.headers['content-type'] == 'image/png'
    assert "filename*=UTF-8''" in first.headers['content-disposition']
    assert read_with_standard_png_decoder(first.content)['data']['description'] == original_text
    assert read_character_card_from_png_bytes(first.content)['data']['description'] == original_text
    assert 'private-test-key' not in json.dumps(read_with_standard_png_decoder(first.content))
    assert list(storage.iterdir()) == []
    imported = client.post('/characters/import-card', files={'file': ('role.png', first.content, 'image/png')})
    assert imported.status_code == 200
    assert imported.json()['persona_prompt'] == original_text
    repeated = client.get(f"/characters/{imported.json()['id']}/export-card")
    assert read_with_standard_png_decoder(repeated.content)['data']['description'] == original_text
    role.persona_prompt = '更新后的角色设定'
    db.commit()
    second = client.get(f'/characters/{role.id}/export-card')
    assert read_with_standard_png_decoder(second.content)['data']['description'] == role.persona_prompt
    assert read_with_standard_png_decoder(first.content)['data']['description'] == original_text


@pytest.mark.parametrize('format', ['PNG', 'JPEG', 'WEBP'])
def test_avatar_formats_remain_visible(exported_role, format):
    db, role, storage, client = exported_role
    avatar = storage / f'avatar.{format.lower()}'
    with Image.new('RGB', (13, 17), (200, 50, 90)) as image:
        image.save(avatar, format=format)
    source = avatar.read_bytes()
    role.avatar_image_path = avatar.name
    db.commit()
    result = client.get(f'/characters/{role.id}/export-card')
    assert result.status_code == 200
    with Image.open(io.BytesIO(result.content)) as image:
        assert image.size == (13, 17)
        assert image.getpixel((0, 0))[0] > 190
    assert avatar.read_bytes() == source
    assert read_with_standard_png_decoder(result.content)['data']['description'] == role.persona_prompt


def test_invalid_image_can_retry_without_export_files(exported_role):
    db, role, storage, client = exported_role
    avatar = storage / 'bad.png'
    avatar.write_bytes(b'broken image')
    role.avatar_image_path = avatar.name
    db.commit()
    result = client.get(f'/characters/{role.id}/export-card')
    assert result.status_code == 422
    assert '重试' in result.json()['detail']
    assert list(storage.iterdir()) == [avatar]
    role.avatar_image_path = ''
    db.commit()
    assert client.get(f'/characters/{role.id}/export-card').status_code == 200


def test_jpeg_orientation_is_applied(exported_role):
    db, role, storage, client = exported_role
    avatar = storage / 'portrait.jpg'
    with Image.new('RGB', (13, 17)) as image:
        exif = image.getexif()
        exif[274] = 6
        image.save(avatar, exif=exif)
    role.avatar_image_path = avatar.name
    db.commit()
    response = client.get(f'/characters/{role.id}/export-card')
    assert response.status_code == 200
    with Image.open(io.BytesIO(response.content)) as image:
        assert image.size == (17, 13)


def test_replacing_compressed_and_duplicate_metadata_before_iend(tmp_path):
    metadata = PngImagePlugin.PngInfo()
    old = base64.b64encode(b'{"data":{"name":"old"}}').decode()
    metadata.add_text('chara', old, zip=True)
    metadata.add_text('chara', old)
    metadata.add_itxt('chara', old)
    metadata.add_text('other', 'keep')
    source = tmp_path / 'source.png'
    with Image.new('RGB', (2, 3)) as image:
        image.save(source, pnginfo=metadata)
    original = source.read_bytes()
    output = tmp_path / 'result.png'
    card = {'spec': 'chara_card_v2', 'data': {'name': '新角色', 'description': '完整正文'}}
    write_character_card_to_png(source, card, output)
    raw = output.read_bytes()
    assert read_with_standard_png_decoder(raw) == card
    assert read_character_card_from_png_bytes(raw) == card
    assert raw.count(b'chara\x00') == 1
    assert raw[-8:-4] == b'IEND'
    with Image.open(output) as image:
        assert image.info['other'] == 'keep'
    assert source.read_bytes() == original

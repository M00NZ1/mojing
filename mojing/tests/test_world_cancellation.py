import asyncio
import json
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session

from backend.app.database import Base, get_db
from backend.app.models import CharacterModel, JobRunModel, WorldTemplateModel
from backend.app.routes import worlds
from backend.app.schemas import WorldGenerationRequest, WorldImportRequest
from backend.app.services import world_building_service as building
from backend.app.services.job_service import mark_job_cancelled
from backend.app.services.world_request_control import run_world_request


@pytest.fixture
def db(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'cancel.db'}")
    Base.metadata.create_all(engine)
    with Session(engine) as db:
        db.add(CharacterModel(name="测试角色", persona_prompt="测试", model_name="test", max_tokens=2048))
        db.commit()
        yield db
    engine.dispose()


@pytest.mark.parametrize('kind', ['generate', 'import'])
def test_stop_cancels_upstream_closes_client_and_never_falls_back_or_saves(db, monkeypatch, kind):
    async def exercise():
        entered = asyncio.Event()
        upstream_cancelled = asyncio.Event()
        closed = []
        calls = []
        disconnected = False

        async def completion(**kwargs):
            calls.append(kwargs['model'])
            entered.set()
            try:
                await asyncio.Future()
            finally:
                upstream_cancelled.set()

        class Client:
            chat = SimpleNamespace(completions=SimpleNamespace(create=completion))
            async def __aenter__(self): return self
            async def __aexit__(self, *args): closed.append(True)

        monkeypatch.setattr(building, 'build_async_client', lambda *args: Client())
        monkeypatch.setattr(building, '_has_online_text_config', lambda *args: True)
        monkeypatch.setattr(building, 'resolve_text_model', lambda *args: 'test')
        async def is_disconnected(): return disconnected
        request = SimpleNamespace(is_disconnected=is_disconnected)
        payload = WorldGenerationRequest(character_id=1, world_type='奇幻', auto_save=True) if kind == 'generate' else WorldImportRequest(character_id=1, source_text='设定原文' * 3000, auto_save=True)
        task = asyncio.create_task(getattr(worlds, f'{kind}_world')(payload, db, request))
        await asyncio.wait_for(entered.wait(), 2)
        disconnected = True
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(task, 2)
        assert upstream_cancelled.is_set()
        assert len(closed) == len(calls) == 1
        assert db.scalar(select(JobRunModel)).status == 'cancelled'
        assert db.scalar(select(WorldTemplateModel)) is None
        assert db.scalar(select(JobRunModel)).output_json == {}
    asyncio.run(exercise())


def test_disconnect_wins_same_tick_as_late_error_and_does_not_start_if_already_gone():
    async def exercise():
        checks = 0
        builds = 0
        async def disconnected():
            nonlocal checks
            checks += 1
            return checks > 1
        async def build():
            nonlocal builds
            builds += 1
            raise RuntimeError('late upstream error')
        with pytest.raises(asyncio.CancelledError):
            await run_world_request(build, disconnected)
        assert builds == 1
        with pytest.raises(asyncio.CancelledError):
            await run_world_request(build, disconnected)
        assert builds == 1
    asyncio.run(exercise())


@pytest.mark.parametrize('kind', ['generate', 'import'])
def test_real_local_pipeline_completes_and_late_cancel_preserves_success(db, kind):
    payload = WorldGenerationRequest(world_type='奇幻') if kind == 'generate' else WorldImportRequest(source_text='雾港在东海，灯塔守望者维护航道。')
    result = asyncio.run(getattr(worlds, f'{kind}_world')(payload, db))
    assert result.template.world_prompt
    assert result.lore_entries
    mark_job_cancelled(db, result.job_id)
    assert db.get(JobRunModel, result.job_id).status == 'succeeded'


@pytest.mark.parametrize('kind', ['generate', 'import'])
def test_real_online_pipeline_awaits_all_steps_and_closes_clients(db, monkeypatch, kind):
    calls = []
    closed = []
    body = {'template': {'label': '雾港', 'world_prompt': '在线正文'}, 'lore_entries': [], 'names': {}}
    async def completion(**kwargs):
        calls.append(kwargs['model'])
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps(body)))])
    class Client:
        chat = SimpleNamespace(completions=SimpleNamespace(create=completion))
        async def __aenter__(self): return self
        async def __aexit__(self, *args): closed.append(True)
    monkeypatch.setattr(building, 'build_async_client', lambda *args: Client())
    monkeypatch.setattr(building, '_has_online_text_config', lambda *args: True)
    monkeypatch.setattr(building, 'resolve_text_model', lambda *args: 'test')
    payload = WorldGenerationRequest(character_id=1, world_type='奇幻') if kind == 'generate' else WorldImportRequest(character_id=1, source_text='雾港在东海。')
    result = asyncio.run(getattr(worlds, f'{kind}_world')(payload, db))
    assert result.lore_entries
    assert len(calls) == len(closed) == 2
    assert db.get(JobRunModel, result.job_id).status == 'succeeded'


def test_async_world_http_request_contract_with_fixture_database(db):
    app = FastAPI()
    app.include_router(worlds.router)
    def fixture_db():
        with Session(db.bind) as request_db:
            yield request_db
    app.dependency_overrides[get_db] = fixture_db
    with TestClient(app) as client:
        response = client.post('/worlds/generate', json={'world_type': '奇幻', 'core_theme': '灯塔失踪案'})
        assert response.status_code == 200
        result = response.json()
        assert result['job_id']
        assert result['template']['world_prompt']
        imported = client.post('/worlds/import', json={'source_text': '雾港位于东海。'})
        assert imported.status_code == 200
        assert imported.json()['job_id'] != result['job_id']

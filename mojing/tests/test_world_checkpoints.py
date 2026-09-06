import asyncio
import os
import subprocess
import sys
import time
from pathlib import Path
from types import SimpleNamespace

import pytest
from sqlalchemy import create_engine, event, select, update
from sqlalchemy.orm import Session

from backend.app.database import Base
from backend.app.models import CharacterModel, JobRunModel, WorldTemplateModel
from backend.app.services import world_building_service as building
from backend.app.services import world_checkpoint_service as checkpoint
from backend.app.services.job_service import list_job_runs
from backend.app.services.llm_client import ResolvedTextConfig
from backend.app.services.world_job_service import world_job_history


@pytest.fixture
def db(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'checkpoint.db'}", connect_args={'check_same_thread': False})
    Base.metadata.create_all(engine)
    with Session(engine) as session:
        yield session
    engine.dispose()


def prepare(db, operation='generate', **fields):
    payload = {'world_type': '奇幻'} if operation == 'generate' else {'source_text': '雾港的灯塔照亮航道。' * 800}
    return checkpoint.prepare_world_job(db, operation, {**payload, **fields})['id']


def test_pause_after_step_survives_new_session_and_resume_skips_completed_step(db, monkeypatch):
    job_id = prepare(db)
    original = building._generate_template_with_fallback
    calls = []
    async def template(*args):
        result = await original(*args)
        calls.append('template')
        with Session(db.bind) as control:
            checkpoint.pause_world_job(control, job_id)
        return result
    monkeypatch.setattr(building, '_generate_template_with_fallback', template)
    outcome = asyncio.run(checkpoint.run_checkpoint_world_job(db, job_id))
    assert outcome == {'status': 'paused', 'result': None}
    progress = checkpoint.world_job_progress(db, job_id)
    assert progress['status'] == 'paused' and progress['completed_steps'] == 2
    assert db.scalar(select(WorldTemplateModel)) is None
    with Session(db.bind) as restarted:
        outcome = asyncio.run(checkpoint.run_checkpoint_world_job(restarted, job_id))
        assert outcome['status'] == 'succeeded'
    assert calls == ['template']


def test_live_owner_blocks_duplicate_run_and_recovery(db):
    job_id = prepare(db)
    db.execute(update(JobRunModel).where(JobRunModel.id == job_id).values(status='running'))
    db.commit()
    with checkpoint.world_job_lock(db, job_id):
        with Session(db.bind) as other:
            assert checkpoint.world_job_progress(other, job_id)['status'] == 'running'
            with pytest.raises(checkpoint.WorldAlreadyRunning):
                asyncio.run(checkpoint.run_checkpoint_world_job(other, job_id))
    assert checkpoint.world_job_progress(db, job_id)['status'] == 'interrupted'


def test_os_releases_lock_after_process_crash(db, tmp_path):
    job_id = prepare(db)
    db.execute(update(JobRunModel).where(JobRunModel.id == job_id).values(status='running'))
    db.commit()
    ready = tmp_path / 'child-ready'
    code = '''import sys
from pathlib import Path
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from backend.app.services.world_checkpoint_service import world_job_lock
with Session(create_engine(sys.argv[1])) as db:
    with world_job_lock(db, int(sys.argv[2])):
        Path(sys.argv[3]).write_text('locked')
        sys.stdin.read(1)
'''
    child = subprocess.Popen([sys.executable, '-c', code, str(db.bind.url), str(job_id), str(ready)],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        env={**os.environ, 'MOJING_DISABLE_ENV_FILE': '1', 'MOJING_STORAGE_DIR': str(tmp_path / 'child-storage')},
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    try:
        deadline = time.monotonic() + 8
        while not ready.exists() and child.poll() is None and time.monotonic() < deadline:
            time.sleep(.05)
        assert ready.exists(), 'isolated lock owner did not start'
        assert checkpoint.world_job_progress(db, job_id)['status'] == 'running'
        child.kill()
        child.communicate(timeout=5)
        assert checkpoint.world_job_progress(db, job_id)['status'] == 'interrupted'
        assert asyncio.run(checkpoint.run_checkpoint_world_job(db, job_id))['status'] == 'succeeded'
    finally:
        if child.poll() is None: child.kill()
        child.communicate(timeout=5)


def test_failure_retains_steps_and_does_not_silently_finish_locally(db, monkeypatch):
    job_id = prepare(db)
    async def failed(*args): raise RuntimeError('upstream failed')
    original = building._generate_lore_entries_with_fallback
    monkeypatch.setattr(building, '_generate_lore_entries_with_fallback', failed)
    with pytest.raises(RuntimeError): asyncio.run(checkpoint.run_checkpoint_world_job(db, job_id))
    assert checkpoint.world_job_progress(db, job_id)['status'] == 'failed'
    assert checkpoint.world_job_progress(db, job_id)['completed_steps'] == 2
    monkeypatch.setattr(building, '_generate_lore_entries_with_fallback', original)
    assert asyncio.run(checkpoint.run_checkpoint_world_job(db, job_id))['status'] == 'succeeded'


def test_import_pause_reuses_saved_online_chunks(db, monkeypatch):
    db.add(CharacterModel(name='模型角色'))
    db.commit()
    job_id = prepare(db, 'import', character_id=1)
    from backend.app.services import llm_client
    monkeypatch.setattr(llm_client, 'resolve_text_config', lambda *args: ResolvedTextConfig('test-only', 'https://example.invalid', 'test', 'character'))
    monkeypatch.setattr(building, '_has_online_text_config', lambda *args: True)
    calls = []
    async def chunk(db_arg, character, payload, text, index, total, category):
        calls.append(index)
        if len(calls) == 1:
            with Session(db.bind) as control: checkpoint.pause_world_job(control, job_id)
        return {'summary': f'片段 {index}', 'names': {}, 'lore_entries': []}
    async def merge(*args): return {'template': {'label': '雾港', 'world_prompt': '所有片段合并'}, 'names': {}, 'lore_entries': []}
    monkeypatch.setattr(building, '_extract_world_chunk_with_llm', chunk)
    monkeypatch.setattr(building, '_merge_world_chunks_with_llm', merge)
    assert asyncio.run(checkpoint.run_checkpoint_world_job(db, job_id))['status'] == 'paused'
    assert checkpoint.world_job_progress(db, job_id)['completed_steps'] == 1
    with Session(db.bind) as another:
        result = asyncio.run(checkpoint.run_checkpoint_world_job(another, job_id))
        assert result['status'] == 'succeeded'
    assert calls == list(range(1, len(calls) + 1)) and len(calls) > 1


def test_cancel_releases_owner_and_preserves_steps(db, monkeypatch):
    async def exercise():
        job_id = prepare(db)
        entered = asyncio.Event()
        async def hanging(*args): entered.set(); await asyncio.Future()
        monkeypatch.setattr(building, '_generate_template_with_fallback', hanging)
        task = asyncio.create_task(checkpoint.run_checkpoint_world_job(db, job_id))
        await asyncio.wait_for(entered.wait(), 2)
        task.cancel()
        with pytest.raises(asyncio.CancelledError): await task
        progress = checkpoint.world_job_progress(db, job_id)
        assert progress['status'] == 'cancelled' and progress['completed_steps'] == 1
        assert not progress['can_resume']
        with checkpoint.world_job_lock(db, job_id): pass
    asyncio.run(exercise())


def test_history_and_legacy_lists_do_not_load_source_or_steps(db):
    job_id = prepare(db, 'import', source_text='原文' * 60000)
    row = db.get(JobRunModel, job_id)
    row.output_json = {'world_checkpoint_version': 1, 'world_steps': {'big': '正文' * 60000}, 'completed_steps': 1}
    row.status = 'paused'
    db.commit()
    assert 'world_steps' not in list_job_runs(db)[0]['output_json']
    assert 'world_request' not in list_job_runs(db)[0]['input_json']
    assert world_job_history(db)['items'][0]['completed_steps'] == 1
    assert '原文' not in str(world_job_history(db))


def test_unknown_checkpoint_and_legacy_records_remain_untouched(db):
    job_id = prepare(db)
    row = db.get(JobRunModel, job_id)
    row.output_json = {'world_checkpoint_version': 99, 'protected': 'keep'}
    db.commit()
    with pytest.raises(ValueError): asyncio.run(checkpoint.run_checkpoint_world_job(db, job_id))
    assert db.get(JobRunModel, job_id).output_json['protected'] == 'keep'
    row.input_json = {}
    row.status = 'running'
    db.commit()
    checkpoint.recover_world_jobs(db)
    assert checkpoint.world_job_progress(db, job_id)['status'] == 'running'


def test_http_prepare_run_and_auto_save(db):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from backend.app.database import get_db
    from backend.app.routes.jobs import router
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_db] = lambda: db
    with TestClient(app) as client:
        prepared = client.post('/jobs/world-request', json={'operation': 'generate', 'request': {'world_type': '奇幻', 'auto_save': True}})
        assert prepared.status_code == 201
        job_id = prepared.json()['id']
        result = client.post(f'/jobs/{job_id}/run-world')
        assert result.status_code == 200 and result.json()['result']['saved_template']
        assert client.post(f'/jobs/{job_id}/pause-world').json()['status'] == 'succeeded'
        assert client.post(f'/jobs/{job_id}/run-world').status_code == 409
        assert client.get(f'/jobs/{job_id}/world-progress').json()['status'] == 'succeeded'


def test_checkpoint_commit_failure_preserves_previous_step(db):
    job_id = prepare(db)
    writes = 0
    def fail_checkpoint(connection, cursor, statement, parameters, context, executemany):
        nonlocal writes
        if statement.startswith('UPDATE job_runs SET output_json'):
            writes += 1
            if writes == 2: raise RuntimeError('injected step save failure')
    event.listen(db.bind, 'before_cursor_execute', fail_checkpoint)
    try:
        with pytest.raises(RuntimeError): asyncio.run(checkpoint.run_checkpoint_world_job(db, job_id))
    finally:
        event.remove(db.bind, 'before_cursor_execute', fail_checkpoint)
    assert checkpoint.world_job_progress(db, job_id)['completed_steps'] == 1
    assert asyncio.run(checkpoint.run_checkpoint_world_job(db, job_id))['status'] == 'succeeded'


def test_online_error_does_not_replace_checkpoint_with_local_skeleton(db, monkeypatch):
    db.add(CharacterModel(name='在线角色'))
    db.commit()
    job_id = prepare(db, character_id=1)
    from backend.app.services import llm_client
    monkeypatch.setattr(llm_client, 'resolve_text_config', lambda *args: ResolvedTextConfig('private-test-key', 'https://example.invalid', 'test', 'character'))
    monkeypatch.setattr(building, '_has_online_text_config', lambda *args: True)
    async def fail(*args): raise RuntimeError('private-test-key must never be recorded')
    monkeypatch.setattr(building, '_generate_template_with_llm', fail)
    with pytest.raises(RuntimeError): asyncio.run(checkpoint.run_checkpoint_world_job(db, job_id))
    row = db.get(JobRunModel, job_id)
    assert row.status == 'failed'
    assert 'private-test-key' not in str((row.input_json, row.output_json, row.error_message))
    assert db.scalar(select(WorldTemplateModel)) is None

from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, delete, event, select
from sqlalchemy.orm import Session

from backend.app.database import Base, get_db
from backend.app.models import JobRunModel, WorldTemplateModel
from backend.app.routes import worlds, jobs
from backend.app.schemas import WorldGenerationRequest, WorldGenerationResponse, WorldImportRequest
from backend.app.services.job_service import list_job_runs
from backend.app.services.world_job_service import read_world_job_result, save_world_job_result, world_job_history


@pytest.fixture
def engine(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'jobs.db'}")
    Base.metadata.create_all(engine)
    yield engine
    engine.dispose()


def sample():
    return WorldGenerationResponse.model_validate({
        "template": {"template_id": "test", "label": "测试世界", "category": "奇幻", "summary": "摘要",
                     "gameplay_mode": "自由剧情", "world_prompt": "完整设定" * 10000},
        "lore_entries": [{"title": "地方志", "content": "原始条目"}],
        "quality_report": {"score": 90, "verdict": "完整"},
    })


@pytest.mark.parametrize("kind", ["generate", "import"])
@pytest.mark.parametrize("auto_save", [False, True])
def test_generation_persists_full_result_before_response_and_reload(engine, monkeypatch, kind, auto_save):
    result = sample()
    monkeypatch.setattr(worlds, f"{kind}_world_package", lambda db, payload: result)
    with Session(engine) as db:
        payload = WorldGenerationRequest(world_type="奇幻", auto_save=auto_save) if kind == "generate" else WorldImportRequest(source_text="原文", auto_save=auto_save)
        response = getattr(worlds, f"{kind}_world")(payload, db)
        job_id = response.job_id
    with Session(engine) as db:
        job, restored = read_world_job_result(db, job_id)
        assert job.status == "succeeded"
        assert restored.template.world_prompt == result.template.world_prompt
        assert restored.lore_entries == result.lore_entries
        assert bool(restored.saved_template) is auto_save
        assert "world_result" not in list_job_runs(db)[0]["output_json"]
        history = world_job_history(db)
        assert history["items"][0]["label"] == "测试世界"
        assert "完整设定" not in str(history)


def create_result(engine, monkeypatch):
    monkeypatch.setattr(worlds, 'generate_world_package', lambda db, payload: sample())
    with Session(engine) as db:
        return worlds.generate_world(WorldGenerationRequest(world_type="奇幻"), db).job_id


def test_simultaneous_saves_are_idempotent_and_do_not_overwrite_existing_world(engine, monkeypatch):
    job_id = create_result(engine, monkeypatch)
    with Session(engine) as db:
        db.add(WorldTemplateModel(template_id="test", label="玩家原世界", world_prompt="不能覆盖", is_builtin=False))
        db.commit()
    def save():
        with Session(engine) as db:
            return save_world_job_result(db, job_id).saved_template.id
    with ThreadPoolExecutor(max_workers=2) as pool:
        ids = list(pool.map(lambda _: save(), range(2)))
    assert ids[0] == ids[1]
    with Session(engine) as db:
        assert len(list(db.scalars(select(WorldTemplateModel)))) == 2
        assert db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == "test")).world_prompt == "不能覆盖"


def test_save_commit_failure_preserves_result_and_can_retry(engine, monkeypatch):
    job_id = create_result(engine, monkeypatch)
    with Session(engine) as db:
        def fail(session):
            raise RuntimeError("commit unavailable")
        event.listen(db, 'before_commit', fail)
        with pytest.raises(RuntimeError):
            save_world_job_result(db, job_id)
        event.remove(db, 'before_commit', fail)
    with Session(engine) as db:
        assert db.scalar(select(WorldTemplateModel)) is None
        assert read_world_job_result(db, job_id)[1].saved_template is None
        db.rollback()
        assert save_world_job_result(db, job_id).saved_template


def test_deleted_saved_template_can_be_restored(engine, monkeypatch):
    job_id = create_result(engine, monkeypatch)
    with Session(engine) as db:
        saved = save_world_job_result(db, job_id).saved_template
        db.execute(delete(WorldTemplateModel).where(WorldTemplateModel.id == saved.id))
        db.commit()
    with Session(engine) as db:
        assert read_world_job_result(db, job_id)[1].saved_template is None
        db.rollback()
        assert save_world_job_result(db, job_id).saved_template


def test_manage_uses_current_saved_world_without_replacing_original_result(engine, monkeypatch):
    job_id = create_result(engine, monkeypatch)
    with Session(engine) as db:
        saved = save_world_job_result(db, job_id).saved_template
        db.get(WorldTemplateModel, saved.id).world_prompt = "玩家后续修改"
        db.commit()
    with Session(engine) as db:
        result = read_world_job_result(db, job_id)[1]
        assert result.saved_template.world_prompt == "玩家后续修改"
        assert result.template.world_prompt == sample().template.world_prompt


def test_keyset_history_handles_new_records_without_duplicate_rows_and_old_jobs(engine):
    with Session(engine) as db:
        for i in range(45):
            db.add(JobRunModel(job_type="world_generate", scope="world", status="succeeded", input_json={"label": f"旧世界{i}"}, output_json={}))
        db.commit()
        first = world_job_history(db)
        db.add(JobRunModel(job_type="world_generate", scope="world", status="running"))
        db.commit()
        second = world_job_history(db, before_id=first['next_cursor'])
        third = world_job_history(db, before_id=second['next_cursor'])
        ids = [item['id'] for page in (first, second, third) for item in page['items']]
        assert len(ids) == len(set(ids)) == 45
        assert third['next_cursor'] is None
        assert first['items'][0]['result_version'] is None
        with pytest.raises(ValueError, match="旧记录"):
            read_world_job_result(db, ids[0])
        with pytest.raises(LookupError):
            read_world_job_result(db, 999)


def test_http_list_detail_save_contracts_use_only_fixture_database(engine, monkeypatch):
    job_id = create_result(engine, monkeypatch)
    app = FastAPI()
    app.include_router(jobs.router)
    def fixture_db():
        with Session(engine) as db:
            yield db
    app.dependency_overrides[get_db] = fixture_db
    with TestClient(app) as client:
        listing = client.get('/jobs?scope=world')
        assert listing.status_code == 200
        assert 'world_result' not in listing.json()[0]['output_json']
        history = client.get('/jobs/world-history')
        assert history.status_code == 200
        assert history.json()['items'][0]['result_version'] == 1
        detail = client.get(f'/jobs/{job_id}/world-result')
        assert detail.status_code == 200
        assert detail.json()['template']['world_prompt'] == sample().template.world_prompt
        saved = client.post(f'/jobs/{job_id}/save-world')
        assert saved.status_code == 200
        repeated = client.post(f'/jobs/{job_id}/save-world')
        assert repeated.json()['saved_template']['id'] == saved.json()['saved_template']['id']
        assert client.get('/jobs/999/world-result').status_code == 404

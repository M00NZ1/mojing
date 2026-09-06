"""Import failure must never commit deletion or partial overwrites with a job log."""
from copy import deepcopy

import pytest
from fastapi import HTTPException
from sqlalchemy import create_engine, event, select, text
from sqlalchemy.orm import Session

from backend.app.database import Base
from backend.app.models import JobRunModel, WorldLoreEntryModel, WorldTemplateModel
from backend.app.routes import worlds
from backend.app.schemas import WorldTemplateBundleImportRequest, WorldTemplatePackageImportRequest
from backend.app.services.world_package_service import (
    build_world_template_bundle, import_world_template_bundle, import_world_template_package,
    preview_world_template_bundle_import,
)


def package(template_id, *, content="新内容", count=2):
    return {"format_version": 1, "template": {"template_id": template_id, "label": template_id},
            "lore_entries": [{"title": f"条目 {n}", "content": content, "sort_order": 0} for n in range(count)]}


def snapshot(db):
    return {table.name: list(db.execute(select(table).order_by(table.c.id)).all())
            for table in (WorldTemplateModel.__table__, WorldLoreEntryModel.__table__)}


@pytest.fixture
def db(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'worlds.db'}")
    @event.listens_for(engine, "connect")
    def enable_foreign_keys(connection, _):
        connection.execute("PRAGMA foreign_keys=ON")
    Base.metadata.create_all(engine)
    with Session(engine) as session:
        for template_id in ("keep", "outside", "builtin"):
            row = import_world_template_package(session, package_json=package(template_id, content="原内容"))
            if template_id == "builtin":
                row.is_builtin = True
                session.commit()
        yield session
    engine.dispose()


@pytest.mark.parametrize("replace", [False, True])
def test_invalid_last_package_preserves_all_data_and_logs_only_failure(db, replace):
    before = snapshot(db)
    broken = package("second")
    broken["lore_entries"][-1]["sort_order"] = "not-an-integer"
    with pytest.raises(HTTPException) as exc:
        worlds.import_world_template_bundle_archive(WorldTemplateBundleImportRequest(
            bundle_json={"packages": [package("keep"), broken]}, override_existing=True,
            replace_all_custom_templates=replace), db)
    assert exc.value.status_code == 400
    assert snapshot(db) == before
    assert db.scalar(select(JobRunModel)).status == "failed"


def test_invalid_single_overwrite_does_not_commit_partial_deletion_with_job(db):
    before = snapshot(db)
    broken = package("keep")
    broken["lore_entries"].append({"title": "broken", "keywords_json": "not-a-list"})
    with pytest.raises(HTTPException):
        worlds.import_world_template_archive(WorldTemplatePackageImportRequest(package_json=broken, override_existing=True), db)
    assert snapshot(db) == before
    assert db.scalar(select(JobRunModel)).status == "failed"


@pytest.mark.parametrize("bundle", [False, True])
def test_actual_late_write_failure_rolls_back_everything(db, bundle):
    before = snapshot(db)
    def fail_late(connection, cursor, statement, parameters, context, executemany):
        if statement.startswith("INSERT INTO world_lore_entries") and "FAIL_AFTER_WRITES" in str(parameters):
            raise RuntimeError("injected failure after earlier writes")
    event.listen(db.bind, "before_cursor_execute", fail_late)
    try:
        with pytest.raises(RuntimeError, match="injected failure"):
            if bundle:
                worlds.import_world_template_bundle_archive(WorldTemplateBundleImportRequest(bundle_json={
                    "packages": [package("keep"), package("second", content="FAIL_AFTER_WRITES")]
                }, replace_all_custom_templates=True), db)
            else:
                worlds.import_world_template_archive(WorldTemplatePackageImportRequest(
                    package_json=package("keep", content="FAIL_AFTER_WRITES"), override_existing=True), db)
    finally:
        event.remove(db.bind, "before_cursor_execute", fail_late)
    assert snapshot(db) == before
    assert db.scalar(select(JobRunModel)).status == "failed"


def test_job_success_and_import_share_one_commit(db, monkeypatch):
    before = snapshot(db)
    def fail_before_commit(*args, **kwargs):
        raise RuntimeError("job persistence unavailable")
    monkeypatch.setattr(worlds, "mark_job_succeeded", fail_before_commit)
    with pytest.raises(RuntimeError, match="job persistence"):
        worlds.import_world_template_bundle_archive(WorldTemplateBundleImportRequest(
            bundle_json={"packages": [package("keep")]}, replace_all_custom_templates=True), db)
    assert snapshot(db) == before


def test_final_transaction_commit_failure_preserves_old_worlds(db):
    before = snapshot(db)
    commits = 0

    def fail_final_commit(session):
        nonlocal commits
        commits += 1
        # The first two commits record pending and running. The third contains
        # both the imported data and success state; the fourth logs the failure.
        if commits == 3:
            raise RuntimeError("final transaction commit unavailable")

    event.listen(db, "before_commit", fail_final_commit)
    try:
        with pytest.raises(RuntimeError, match="final transaction commit"):
            worlds.import_world_template_bundle_archive(WorldTemplateBundleImportRequest(
                bundle_json={"packages": [package("keep")]}, replace_all_custom_templates=True), db)
    finally:
        event.remove(db, "before_commit", fail_final_commit)
    assert snapshot(db) == before
    assert db.scalar(select(JobRunModel)).status == "failed"


def test_replace_preview_matches_commit_and_preserves_matching_template_id(db):
    original_id = db.scalar(select(WorldTemplateModel.id).where(WorldTemplateModel.template_id == "keep"))
    builtin_before = db.scalar(select(WorldTemplateModel).where(WorldTemplateModel.template_id == "builtin")).id
    bundle = {"format_version": 1, "package_count": 2, "packages": [package("keep"), package("new")]}
    preview = preview_world_template_bundle_import(db, bundle_json=bundle, replace_all_custom_templates=True)
    assert preview.will_delete_template_ids == ["outside"]
    assert preview.overwrite_count == preview.create_count == 1
    assert preview.recreate_count == preview.blocked_count == 0
    rows = worlds.import_world_template_bundle_archive(WorldTemplateBundleImportRequest(bundle_json=bundle, replace_all_custom_templates=True), db)
    assert [row.template_id for row in rows] == ["keep", "new"]
    assert rows[0].id == original_id
    assert set(db.scalars(select(WorldTemplateModel.template_id))) == {"keep", "new", "builtin"}
    assert db.get(WorldTemplateModel, builtin_before).is_builtin
    assert db.scalar(select(JobRunModel)).status == "succeeded"
    assert list(db.scalars(select(WorldLoreEntryModel.sort_order).where(WorldLoreEntryModel.world_template_id == original_id))) == [0, 0]
    assert not list(db.execute(text('PRAGMA foreign_key_check')))


@pytest.mark.parametrize("invalid", [
    {"format_version": 2, "packages": [package("new")]},
    {"package_count": 2, "packages": [package("new")]},
    {"packages": [package("new"), package("new")]},
    {"packages": [package("new"), package("builtin")]},
    {"packages": [package("new"), {"template": None}]},
])
def test_version_count_duplicates_and_builtin_conflicts_never_modify_data(db, invalid):
    before = snapshot(db)
    with pytest.raises(ValueError):
        import_world_template_bundle(db, bundle_json=deepcopy(invalid), replace_all_custom_templates=True)
    assert snapshot(db) == before


def test_legacy_package_and_large_bundle_round_trip_and_repeated_import(db):
    legacy = {"template": {"template_id": "legacy", "label": "旧包"}, "lore_entries": [{"content": "旧正文"}]}
    legacy_row = import_world_template_package(db, package_json=legacy)
    assert db.scalar(select(WorldLoreEntryModel.title).where(WorldLoreEntryModel.world_template_id == legacy_row.id)) == "条目 1"
    bundle = {"packages": [package(f"world_{i}", content="长篇设定" * 1000, count=20) for i in range(100)]}
    rows = import_world_template_bundle(db, bundle_json=bundle, replace_all_custom_templates=True)
    ids = [row.id for row in rows]
    exported = build_world_template_bundle(db, include_builtin=False).model_dump(mode="json")
    repeated = import_world_template_bundle(db, bundle_json=exported, override_existing=True)
    assert {row.id for row in repeated} == set(ids)
    assert len(list(db.scalars(select(WorldLoreEntryModel.id)))) == 2002
    assert build_world_template_bundle(db, include_builtin=False).package_count == 100

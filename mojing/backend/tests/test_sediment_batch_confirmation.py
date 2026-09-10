import pytest
from fastapi import HTTPException
from sqlalchemy import create_engine, select, func
from sqlalchemy.orm import Session

from backend.app.database import Base
from backend.app.models import WorldEncyclopediaModel, EncyclopediaEntryModel, EntryVersionModel
from backend.app.routes.encyclopedia import confirm_selected_sediment_entries
from backend.app.routes.encyclopedia import list_sediment_page
from backend.app.services import sediment_service


@pytest.fixture
def db():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with Session(engine) as session:
        session.add_all([WorldEncyclopediaModel(id=1, name="世界一"), WorldEncyclopediaModel(id=2, name="世界二")])
        session.add_all([
            EncyclopediaEntryModel(id=1, encyclopedia_id=1, title="约定", content="原文", confidence="inferred", source_session_id=8, source_message_id=9, meta_json={"source_message_ids": [9]}),
            EncyclopediaEntryModel(id=2, encyclopedia_id=2, title="另一百科", confidence="inferred"),
            EncyclopediaEntryModel(id=3, encyclopedia_id=1, title="手动资料", confidence="speculative"),
        ])
        session.commit()
        yield session
    engine.dispose()


def test_confirmation_preserves_sources_and_is_scoped_and_idempotent(db):
    result = confirm_selected_sediment_entries(1, {"entry_ids": [1, 1, 2, 3, 999]}, db)
    assert result == {"confirmed": 1}
    entry = db.get(EncyclopediaEntryModel, 1)
    assert (entry.content, entry.source_session_id, entry.source_message_id, entry.meta_json) == ("原文", 8, 9, {"source_message_ids": [9]})
    assert db.get(EncyclopediaEntryModel, 2).confidence == "inferred"
    assert db.get(EncyclopediaEntryModel, 3).confidence == "speculative"
    assert confirm_selected_sediment_entries(1, {"entry_ids": [1]}, db) == {"confirmed": 0}
    versions = list(db.scalars(select(EntryVersionModel)))
    assert len(versions) == 1
    assert versions[0].content == "原文"


def test_cursor_pages_preserve_order_and_filter_across_large_library(db):
    db.add_all([EncyclopediaEntryModel(id=i, encyclopedia_id=1, title=f"资料{i}",
        confidence="confirmed" if i % 3 == 0 else "inferred", source_session_id=8,
        summary="摘要" * 500, content="正文" * 1000) for i in range(10, 1210)])
    db.commit()
    for status in ("all", "pending", "confirmed"):
        cursor = None
        ids = []
        while True:
            result = list_sediment_page(1, status, cursor, db)
            assert len(result["items"]) <= 100
            for row in result["items"]:
                assert "content" not in row
                assert len(row["summary"]) <= 480
                assert status == "all" or (row["confidence"] == "confirmed") == (status == "confirmed")
            ids.extend(row["id"] for row in result["items"])
            cursor = result["next_cursor"]
            if cursor is None:
                break
        expected = [i for i in range(1209, 9, -1) if status == "all" or (i % 3 == 0) == (status == "confirmed")]
        if status != "confirmed":
            expected.append(1)
        assert ids == expected


def test_cursor_survives_deleted_boundary_and_new_entries(db):
    db.add_all([EncyclopediaEntryModel(id=i, encyclopedia_id=1, title=str(i), confidence="inferred") for i in range(10, 112)])
    db.commit()
    first = list_sediment_page(1, "pending", None, db)
    boundary = first["next_cursor"]
    db.delete(db.get(EncyclopediaEntryModel, boundary))
    db.add(EncyclopediaEntryModel(id=200, encyclopedia_id=1, title="新资料", confidence="inferred"))
    db.commit()
    second = list_sediment_page(1, "pending", boundary, db)
    assert [row["id"] for row in second["items"]] == [11, 10, 1]


def test_version_failure_rolls_back_the_entire_batch(db, monkeypatch):
    db.add(EncyclopediaEntryModel(id=4, encyclopedia_id=1, title="第二条", confidence="inferred"))
    db.commit()
    original = sediment_service._create_entry_version
    calls = 0

    def fail_second(*args):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise RuntimeError("version write failed")
        return original(*args)

    monkeypatch.setattr(sediment_service, "_create_entry_version", fail_second)
    with pytest.raises(RuntimeError):
        confirm_selected_sediment_entries(1, {"entry_ids": [1, 4]}, db)
    assert db.get(EncyclopediaEntryModel, 1).confidence == "inferred"
    assert db.get(EncyclopediaEntryModel, 4).confidence == "inferred"
    assert db.scalar(select(func.count()).select_from(EntryVersionModel)) == 0


@pytest.mark.parametrize("ids", [[], [0], [True], ["1"], list(range(1, 102))])
def test_invalid_selection_is_rejected_before_accessing_storage(ids):
    with pytest.raises(HTTPException) as error:
        confirm_selected_sediment_entries(1, {"entry_ids": ids}, None)
    assert error.value.status_code == 400

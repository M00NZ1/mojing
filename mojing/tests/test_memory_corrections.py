import json
from datetime import timedelta

import pytest
from fastapi import HTTPException
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base
from backend.app.models import (
    CharacterModel,
    ChatSessionModel,
    MessageModel,
    SessionBranchModel,
    SessionMemoryCorrectionModel,
    SessionMemorySegmentModel,
    SessionParticipantModel,
)
from backend.app.routes.sessions import (
    create_memory_correction,
    delete_memory_correction,
    delete_message,
    get_memory_segments,
    get_latest_prompt_trace,
    list_memory_corrections,
    update_memory_correction,
)
from backend.app.schemas import SessionMemoryCorrectionWrite
from backend.app.services.memory_v2_service import (
    build_context_memory,
    get_active_memory_corrections,
)
from backend.app.services.chat_service import build_group_prompt, build_narrator_prompt
from backend.app.services import export_service


def _db(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'corrections.db'}")
    Base.metadata.create_all(engine)
    return engine, sessionmaker(bind=engine, expire_on_commit=False, autoflush=False)


def test_corrections_are_scoped_and_crud_validates_references(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = ChatSessionModel(title="corrections")
            other = ChatSessionModel(title="other")
            db.add_all([session, other])
            db.flush()
            source = MessageModel(session_id=session.id, content="source")
            disposable_source = MessageModel(session_id=session.id, content="disposable")
            db.add_all([source, disposable_source])
            db.flush()
            db.add(SessionBranchModel(session_id=session.id, branch_id="A", source_message_id=source.id, parent_branch_id="main"))
            db.commit()

            global_row = create_memory_correction(session.id, SessionMemoryCorrectionWrite(content=" global "), db)
            branch_row = create_memory_correction(session.id, SessionMemoryCorrectionWrite(content="branch", branch_id="A", source_message_id=source.id), db)
            assert [row.content for row in get_active_memory_corrections(db, session.id, "main")] == ["global"]
            assert [row.content for row in get_active_memory_corrections(db, session.id, "A")] == ["global", "branch"]
            assert [row.content for row in list_memory_corrections(session.id, "main", db)] == ["global"]

            preserved_row = create_memory_correction(
                session.id,
                SessionMemoryCorrectionWrite(
                    content="source can disappear",
                    source_message_id=disposable_source.id,
                ),
                db,
            )

            with pytest.raises(HTTPException) as bad_branch:
                create_memory_correction(session.id, SessionMemoryCorrectionWrite(content="bad", branch_id="missing"), db)
            assert bad_branch.value.status_code == 400
            with pytest.raises(HTTPException) as bad_source:
                create_memory_correction(session.id, SessionMemoryCorrectionWrite(content="bad", source_message_id=999), db)
            assert bad_source.value.status_code == 400
            assert SessionMemoryCorrectionWrite(content="  fixed  ").content == "fixed"
            update_memory_correction(session.id, global_row.id, SessionMemoryCorrectionWrite(content="updated"), db)
            delete_memory_correction(session.id, branch_row.id, db)
            assert db.get(SessionMemoryCorrectionModel, branch_row.id) is None
            delete_message(session.id, disposable_source.id, db)
            assert db.get(SessionMemoryCorrectionModel, preserved_row.id).source_message_id == disposable_source.id
            update_memory_correction(
                session.id,
                preserved_row.id,
                SessionMemoryCorrectionWrite(
                    content="still editable",
                    source_message_id=disposable_source.id,
                ),
                db,
            )
            assert db.get(SessionMemoryCorrectionModel, preserved_row.id).content == "still editable"
    finally:
        engine.dispose()


def test_context_injects_corrections_before_auto_segments_without_mutating_them(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = ChatSessionModel(title="memory")
            db.add(session)
            db.flush()
            correction = SessionMemoryCorrectionModel(session_id=session.id, content="用户纠正事实")
            db.add_all([correction, SessionMemorySegmentModel(session_id=session.id, branch_id="main", segment_index=1, start_message_id=1, end_message_id=2, summary="自动摘要")])
            db.commit()
            before = correction.content
            context = build_context_memory(db, session.id, "main")
            assert context.index("用户锁定记忆（冲突时优先）") < context.index("自动摘要")
            assert correction.content == before
    finally:
        engine.dispose()


def test_memory_segments_follow_ancestor_cutoffs_and_replacement_exclusion(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = ChatSessionModel(title="segments")
            db.add(session)
            db.flush()
            messages = [MessageModel(session_id=session.id, content=f"m{i}") for i in range(1, 5)]
            db.add_all(messages)
            db.flush()
            db.add(SessionBranchModel(session_id=session.id, branch_id="A", source_message_id=messages[1].id, parent_branch_id="main"))
            replacement = MessageModel(session_id=session.id, branch_id="A", content="replacement", regenerated_from_message_id=messages[0].id)
            db.add(replacement)
            db.add_all([
                SessionMemorySegmentModel(session_id=session.id, branch_id="main", segment_index=1, start_message_id=1, end_message_id=1, summary="old"),
                SessionMemorySegmentModel(session_id=session.id, branch_id="main", segment_index=2, start_message_id=2, end_message_id=4, summary="future"),
                SessionMemorySegmentModel(session_id=session.id, branch_id="A", segment_index=1, start_message_id=5, end_message_id=5, summary="branch"),
            ])
            db.commit()
            visible = get_memory_segments(session.id, "A", db, 100)
            assert [segment.summary for segment in visible] == ["branch"]
            context = build_context_memory(db, session.id, "A")
            assert "branch" in context
            assert "old" not in context
            assert "future" not in context
    finally:
        engine.dispose()


def test_character_and_narrator_prompts_inject_corrections_once_without_main_summary_leak(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = ChatSessionModel(title="prompt", summary="MAIN_ONLY_SUMMARY")
            character = CharacterModel(name="纠正测试角色", persona_prompt="保持角色身份")
            db.add_all([session, character])
            db.flush()
            source = MessageModel(session_id=session.id, content="分叉前原文")
            db.add(source)
            db.flush()
            db.add_all(
                [
                    SessionParticipantModel(
                        session_id=session.id,
                        character_id=character.id,
                        sort_order=0,
                    ),
                    SessionBranchModel(
                        session_id=session.id,
                        branch_id="A",
                        source_message_id=source.id,
                        parent_branch_id="main",
                    ),
                    SessionMemoryCorrectionModel(
                        session_id=session.id,
                        branch_id="A",
                        content="用户纠正事实",
                        source_message_id=source.id,
                    ),
                ]
            )
            db.commit()

            character_messages, character_debug = build_group_prompt(
                db,
                session.id,
                character,
                "A",
            )
            narrator_messages, narrator_debug = build_narrator_prompt(
                db,
                session.id,
                "旁白",
                "A",
            )

            assert character_messages[0]["content"].count("用户纠正事实") == 1
            assert narrator_messages[0]["content"].count("用户纠正事实") == 1
            assert "MAIN_ONLY_SUMMARY" not in narrator_messages[-1]["content"]
            character_refs = character_debug["prompt_debug"]["memory_corrections"]
            narrator_refs = narrator_debug["prompt_debug"]["memory_corrections"]
            assert [item["id"] for item in character_refs] == [narrator_refs[0]["id"]]
            assert character_refs[0]["updated_at"] == narrator_refs[0]["updated_at"]
            assert "content" not in character_refs[0]
    finally:
        engine.dispose()


def test_prompt_trace_is_branch_scoped_and_expands_lightweight_correction_refs(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = ChatSessionModel(title="trace")
            db.add(session)
            db.flush()
            source = MessageModel(session_id=session.id, content="branch source")
            db.add(source)
            db.flush()
            db.add_all(
                [
                    SessionBranchModel(
                        session_id=session.id,
                        branch_id="A",
                        source_message_id=source.id,
                        parent_branch_id="main",
                    ),
                    SessionBranchModel(
                        session_id=session.id,
                        branch_id="B",
                        source_message_id=source.id,
                        parent_branch_id="main",
                    ),
                ]
            )
            global_row = SessionMemoryCorrectionModel(
                session_id=session.id,
                content="global correction",
                source_message_id=source.id,
            )
            branch_row = SessionMemoryCorrectionModel(
                session_id=session.id,
                branch_id="A",
                content="branch correction",
            )
            sibling_row = SessionMemoryCorrectionModel(
                session_id=session.id,
                branch_id="B",
                content="sibling correction",
            )
            db.add_all([global_row, branch_row, sibling_row])
            db.flush()
            branch_version = branch_row.updated_at.isoformat()
            branch_reply = MessageModel(
                session_id=session.id,
                speaker_type="character",
                branch_id="A",
                content="branch reply",
                structured_content={
                    "prompt_debug": {
                        "kind": "character_reply",
                        "branch_id": "A",
                        "memory_corrections": [
                            {"id": global_row.id, "updated_at": global_row.updated_at.isoformat()},
                            {"id": branch_row.id, "updated_at": branch_version},
                        ],
                    }
                },
            )
            db.add(branch_reply)
            db.flush()
            main_reply = MessageModel(
                session_id=session.id,
                speaker_type="narrator",
                branch_id="main",
                content="newer main reply",
                structured_content={
                    "prompt_debug": {
                        "kind": "narrator_reply",
                        "branch_id": "main",
                        "memory_corrections": [global_row.id],
                    }
                },
            )
            db.add(main_reply)
            db.commit()

            branch_row.content = "branch correction changed later"
            branch_row.updated_at = branch_row.updated_at + timedelta(seconds=1)
            db.commit()

            trace = get_latest_prompt_trace(session.id, "A", db)
            assert trace["message_id"] == branch_reply.id
            assert trace["branch_id"] == "A"
            assert [item["content"] for item in trace["memory_corrections"]] == [
                "global correction",
                "branch correction changed later",
            ]
            assert [item["status"] for item in trace["memory_corrections"]] == [
                "current",
                "changed",
            ]
            assert [item["order"] for item in trace["memory_corrections"]] == [1, 2]
            assert trace["memory_corrections"][0]["branch_id"] is None
            assert trace["memory_corrections"][1]["branch_id"] == "A"
            assert trace["memory_corrections_recorded"] is True
            assert all("优先于自动记忆" in item["reason"] for item in trace["memory_corrections"])
            assert sibling_row.id not in {item["id"] for item in trace["memory_corrections"]}

            main_trace = get_latest_prompt_trace(session.id, "main", db)
            assert main_trace["message_id"] == main_reply.id
            assert main_trace["memory_corrections"][0]["status"] == "unknown_revision"

            db.delete(global_row)
            db.commit()
            deleted_trace = get_latest_prompt_trace(session.id, "A", db)
            assert deleted_trace["memory_corrections"][0]["status"] == "deleted"
            assert deleted_trace["memory_corrections"][0]["content"] is None
    finally:
        engine.dispose()


def test_explicit_session_export_includes_user_corrections(tmp_path, monkeypatch):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = ChatSessionModel(title="export")
            db.add(session)
            db.flush()
            db.add(
                SessionMemoryCorrectionModel(
                    session_id=session.id,
                    content="导出的用户纠正",
                )
            )
            db.commit()

            export_root = tmp_path / "exports"
            monkeypatch.setattr(export_service, "EXPORT_ROOT", export_root)
            export_service.export_session_snapshot(db, session.id)

            payload = json.loads(
                (export_root / f"convo_{session.id:04d}" / "meta" / "memory_corrections.json").read_text(
                    encoding="utf-8"
                )
            )
            assert payload[0]["content"] == "导出的用户纠正"
            assert payload[0]["branch_id"] is None
    finally:
        engine.dispose()

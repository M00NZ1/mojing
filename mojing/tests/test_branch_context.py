import pytest

from fastapi import HTTPException
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base
from backend.app.models import (
    ChatSessionModel,
    EncyclopediaEntryModel,
    MessageAttachmentModel,
    MessageModel,
    SessionBranchModel,
    SessionEventNodeModel,
    SessionMemorySegmentModel,
)
from backend.app.routes.sessions import add_user_message, create_session_branch, generate_stream, list_session_branches, update_message
from backend.app.schemas import GenerateRequest, SessionBranchCreate, SessionMessageCreate, SessionMessageEdit
from backend.app.services.macro_actions import _action_summarize_session
from backend.app.services.chat_service import (
    BranchContextError,
    _build_recent_prompt_messages,
    _list_visible_messages,
    create_message,
    get_session_message_window,
    get_session_messages_page,
    resolve_branch_context,
    search_session_messages,
)
from backend.app.services.memory_v2_service import build_context_memory


def _db(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'branches.db'}", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    return engine, sessionmaker(bind=engine, expire_on_commit=False, autoflush=False)


def _session(db, title="branch test"):
    row = ChatSessionModel(title=title)
    db.add(row)
    db.flush()
    return row


def _message(db, session_id, content, branch_id="main"):
    row = MessageModel(session_id=session_id, branch_id=branch_id, content=content)
    db.add(row)
    db.flush()
    return row


def test_user_message_writes_to_selected_branch_and_binds_visible_parent(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = _session(db)
            main_source = _message(db, session.id, "main-source")
            main_after_fork = _message(db, session.id, "main-after-fork")
            db.add(SessionBranchModel(
                session_id=session.id,
                branch_id="story-a",
                source_message_id=main_source.id,
                parent_branch_id="main",
            ))
            db.commit()

            created = add_user_message(
                session.id,
                SessionMessageCreate(content="branch message", branch_id="story-a"),
                db,
            )

            assert created.branch_id == "story-a"
            assert created.parent_message_id == main_source.id
            main_rows, _ = get_session_messages_page(db, session.id, None, 40, "main")
            branch_rows, _ = get_session_messages_page(db, session.id, None, 40, "story-a")
            assert [row.content for row in main_rows] == ["main-source", "main-after-fork"]
            assert [row.content for row in branch_rows] == ["main-source", "branch message"]
    finally:
        engine.dispose()


def test_summarize_session_events_uses_only_selected_branch_visible_messages(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = _session(db)
            main_source = _message(db, session.id, "main-source")
            main_after_fork = _message(db, session.id, "main-after-fork")
            db.add(SessionBranchModel(
                session_id=session.id, branch_id="story-a",
                source_message_id=main_source.id, parent_branch_id="main",
            ))
            db.flush()
            branch_message = _message(db, session.id, "story-a-message", "story-a")
            db.add(SessionBranchModel(
                session_id=session.id, branch_id="story-b",
                source_message_id=main_source.id, parent_branch_id="main",
            ))
            db.flush()
            _message(db, session.id, "story-b-message", "story-b")
            db.commit()

            result = _action_summarize_session({"session_id": session.id, "branch_id": "story-a"}, db)
            entry = db.get(EncyclopediaEntryModel, result["entry_id"])
            assert "main-source" in entry.content
            assert "story-a-message" in entry.content
            assert "main-after-fork" not in entry.content
            assert "story-b-message" not in entry.content
            assert entry.meta_json["source_branch_id"] == "story-a"
    finally:
        engine.dispose()


def test_user_message_and_summary_reject_unknown_branch(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = _session(db)
            _message(db, session.id, "main")
            db.commit()
            with pytest.raises(HTTPException) as message_error:
                add_user_message(session.id, SessionMessageCreate(content="must reject", branch_id="missing"), db)
            assert message_error.value.status_code == 400
            with pytest.raises(ValueError):
                _action_summarize_session({"session_id": session.id, "branch_id": "missing"}, db)
    finally:
        engine.dispose()


def test_nested_branch_visibility_is_shared_by_list_search_and_prompt(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = _session(db)
            main_1 = _message(db, session.id, "main-1")
            main_2 = _message(db, session.id, "main-2")
            main_after_fork = _message(db, session.id, "main-after-fork")
            db.add(SessionBranchModel(session_id=session.id, branch_id="A", source_message_id=main_2.id, parent_branch_id="main"))
            db.flush()
            a_1 = _message(db, session.id, "A-1", "A")
            db.add(SessionBranchModel(session_id=session.id, branch_id="B", source_message_id=a_1.id, parent_branch_id="A"))
            db.flush()
            b_1 = _message(db, session.id, "B-1", "B")
            db.add(SessionBranchModel(session_id=session.id, branch_id="C", source_message_id=b_1.id, parent_branch_id="B"))
            db.flush()
            c_1 = _message(db, session.id, "C-1", "C")
            db.add(SessionBranchModel(session_id=session.id, branch_id="D", source_message_id=main_1.id, parent_branch_id="C"))
            db.flush()
            d_1 = _message(db, session.id, "D-1", "D")
            db.commit()

            expected = {
                "main": [main_1.id, main_2.id, main_after_fork.id],
                "A": [main_1.id, main_2.id, a_1.id],
                "B": [main_1.id, main_2.id, a_1.id, b_1.id],
                "C": [main_1.id, main_2.id, a_1.id, b_1.id, c_1.id],
                "D": [main_1.id, d_1.id],
            }
            for branch_id, ids in expected.items():
                listed, _ = get_session_messages_page(db, session.id, None, 40, branch_id)
                assert [row.id for row in listed] == ids
                assert [row.id for row in _list_visible_messages(db, session.id, branch_id, limit=40)] == ids
                hits = search_session_messages(db, session.id, "-", branch_id=branch_id)
                assert [hit["id"] for hit in hits] == list(reversed(ids))
                window, _, _ = get_session_message_window(
                    db,
                    session.id,
                    ids[len(ids) // 2],
                    radius=5,
                    branch_id=branch_id,
                )
                assert [row.id for row in window] == ids
                prompt = _build_recent_prompt_messages(db, session.id, branch_id, False, 40, False)
                assert [item["content"] for item in prompt] == [db.get(MessageModel, mid).content for mid in ids]
    finally:
        engine.dispose()


def test_unknown_cross_session_and_invalid_source_never_fall_back_to_main(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            first = _session(db, "first")
            second = _session(db, "second")
            source = _message(db, first.id, "first-main")
            db.commit()
            for branch_id in ("unknown",):
                for operation in (
                    lambda: resolve_branch_context(db, first.id, branch_id),
                    lambda: get_session_messages_page(db, first.id, None, 40, branch_id),
                    lambda: get_session_message_window(db, first.id, source.id, 5, branch_id),
                    lambda: search_session_messages(db, first.id, "first", branch_id=branch_id),
                ):
                    try:
                        operation()
                    except BranchContextError:
                        pass
                    else:
                        raise AssertionError("unknown branch was accepted")

            db.add(SessionBranchModel(session_id=first.id, branch_id="bad", source_message_id=source.id, parent_branch_id="missing"))
            db.commit()
            with Session() as other_db:
                _message(other_db, second.id, "foreign-1")
                foreign = _message(other_db, second.id, "foreign-2")
                other_db.commit()
            db.add(SessionBranchModel(session_id=first.id, branch_id="foreign-source", source_message_id=foreign.id, parent_branch_id="main"))
            db.commit()
            for branch_id in ("bad", "foreign-source"):
                with pytest.raises(BranchContextError):
                    resolve_branch_context(db, first.id, branch_id)

            before = db.scalar(select(func.count()).where(MessageModel.session_id == first.id))
            with pytest.raises(BranchContextError):
                create_message(db, session_id=first.id, speaker_type="user", content="must not write", branch_id="unknown")
            with pytest.raises(HTTPException) as exc_info:
                generate_stream(
                    first.id,
                    GenerateRequest(user_message="must not write through route", branch_id="unknown"),
                    db,
                )
            assert exc_info.value.status_code == 400
            after = db.scalar(select(func.count()).where(MessageModel.session_id == first.id))
            assert after == before
    finally:
        engine.dispose()


def test_source_must_be_visible_to_parent_and_corrupt_parent_chains_fail(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = _session(db)
            main_source = _message(db, session.id, "main-source")
            main_after = _message(db, session.id, "main-after")
            db.add(SessionBranchModel(session_id=session.id, branch_id="A", source_message_id=main_source.id, parent_branch_id="main"))
            db.flush()
            a_source = _message(db, session.id, "a-source", "A")
            db.flush()
            with pytest.raises(HTTPException) as invisible_source_error:
                create_session_branch(
                    session.id,
                    SessionBranchCreate(
                        source_message_id=main_after.id,
                        branch_id="rejected",
                        parent_branch_id="A",
                    ),
                    db,
                )
            assert invisible_source_error.value.status_code == 400
            assert db.scalar(
                select(SessionBranchModel.id).where(
                    SessionBranchModel.session_id == session.id,
                    SessionBranchModel.branch_id == "rejected",
                )
            ) is None
            with pytest.raises(HTTPException) as reserved_main_error:
                create_session_branch(
                    session.id,
                    SessionBranchCreate(
                        source_message_id=main_source.id,
                        branch_id="main",
                        parent_branch_id="main",
                    ),
                    db,
                )
            assert reserved_main_error.value.status_code == 400
            db.add_all([
                SessionBranchModel(session_id=session.id, branch_id="B", source_message_id=main_after.id, parent_branch_id="A"),
                SessionBranchModel(session_id=session.id, branch_id="loop-1", source_message_id=main_source.id, parent_branch_id="loop-2"),
                SessionBranchModel(session_id=session.id, branch_id="loop-2", source_message_id=main_source.id, parent_branch_id="loop-1"),
                SessionBranchModel(session_id=session.id, branch_id="orphan", source_message_id=main_source.id, parent_branch_id="missing"),
            ])
            db.commit()
            with pytest.raises(BranchContextError):
                resolve_branch_context(db, session.id, "B")
            for branch_id in ("loop-1", "orphan"):
                with pytest.raises(BranchContextError):
                    resolve_branch_context(db, session.id, branch_id)
            assert a_source.id == db.scalar(select(MessageModel.id).where(MessageModel.content == "a-source"))
    finally:
        engine.dispose()


def test_new_branch_is_listed_before_it_has_own_messages(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = _session(db)
            source = _message(db, session.id, "branch source")
            db.commit()

            created = create_session_branch(
                session.id,
                SessionBranchCreate(
                    source_message_id=source.id,
                    branch_id="empty-branch",
                    label="尚未生成回复",
                    parent_branch_id="main",
                ),
                db,
            )
            branches = list_session_branches(session.id, db)
            by_id = {item["branch_id"]: item for item in branches}

            assert created["branch_id"] == "empty-branch"
            assert by_id["empty-branch"]["message_count"] == 0
            assert by_id["empty-branch"]["source_message_id"] == source.id
            assert by_id["empty-branch"]["latest_message_id"] == source.id
            assert by_id["empty-branch"]["source_message_preview"] == "branch source"
    finally:
        engine.dispose()


def test_edit_creates_replacement_branch_without_mutating_story_or_memory(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = _session(db)
            original = MessageModel(
                session_id=session.id,
                speaker_type="user",
                branch_id="main",
                content="走进旧书店",
            )
            db.add(original)
            db.flush()
            db.add(
                MessageAttachmentModel(
                    message_id=original.id,
                    asset_type="image",
                    file_name="clue.png",
                    mime_type="image/png",
                    storage_path="uploads/clue.png",
                )
            )
            reply = MessageModel(
                session_id=session.id,
                speaker_type="character",
                branch_id="main",
                parent_message_id=original.id,
                content="店主递来一本旧书",
            )
            db.add(reply)
            db.flush()
            db.add_all(
                [
                    SessionMemorySegmentModel(
                        session_id=session.id,
                        branch_id="main",
                        segment_index=1,
                        start_message_id=original.id,
                        end_message_id=reply.id,
                        summary="玩家进入旧书店并拿到旧书",
                    ),
                    SessionEventNodeModel(
                        session_id=session.id,
                        branch_id="main",
                        message_id=reply.id,
                        title="获得旧书",
                        description="店主交出旧书",
                    ),
                ]
            )
            db.commit()

            replacement = update_message(
                session.id,
                original.id,
                SessionMessageEdit(content="绕过旧书店，追踪窗外脚印", branch_id="main"),
                db,
            )

            main_rows, _ = get_session_messages_page(db, session.id, None, 40, "main")
            edit_rows, _ = get_session_messages_page(db, session.id, None, 40, replacement.branch_id)
            assert [row.content for row in main_rows] == ["走进旧书店", "店主递来一本旧书"]
            assert [row.content for row in edit_rows] == ["绕过旧书店，追踪窗外脚印"]
            assert replacement.regenerated_from_message_id == original.id
            assert replacement.parent_message_id is None
            assert len(replacement.attachments) == 1
            assert replacement.attachments[0].storage_path == "uploads/clue.png"
            assert db.get(MessageModel, original.id).content == "走进旧书店"
            assert db.scalar(select(func.count()).select_from(SessionMemorySegmentModel)) == 1
            assert db.scalar(select(func.count()).select_from(SessionEventNodeModel)) == 1
            assert "玩家进入旧书店" in build_context_memory(db, session.id, "main")
            assert build_context_memory(db, session.id, replacement.branch_id) == ""
            assert [
                hit["id"] for hit in search_session_messages(
                    db, session.id, "旧书店", branch_id=replacement.branch_id
                )
            ] == [replacement.id]
            prompt_contents = [item["content"] for item in _build_recent_prompt_messages(
                db, session.id, replacement.branch_id, False, 40, False
            )]
            assert len(prompt_contents) == 1
            assert prompt_contents[0].startswith("绕过旧书店，追踪窗外脚印")
            assert "走进旧书店" not in prompt_contents[0]
    finally:
        engine.dispose()


def test_editing_an_edit_replaces_only_the_visible_version(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = _session(db)
            original = MessageModel(
                session_id=session.id,
                speaker_type="user",
                branch_id="main",
                content="原始选择",
            )
            db.add(original)
            db.commit()

            first_edit = update_message(
                session.id,
                original.id,
                SessionMessageEdit(content="第一次修改", branch_id="main"),
                db,
            )
            second_edit = update_message(
                session.id,
                first_edit.id,
                SessionMessageEdit(content="第二次修改", branch_id=first_edit.branch_id),
                db,
            )

            first_rows, _ = get_session_messages_page(db, session.id, None, 40, first_edit.branch_id)
            second_rows, _ = get_session_messages_page(db, session.id, None, 40, second_edit.branch_id)
            assert [row.content for row in first_rows] == ["第一次修改"]
            assert [row.content for row in second_rows] == ["第二次修改"]
            assert second_edit.regenerated_from_message_id == first_edit.id
            second_branch = db.scalar(
                select(SessionBranchModel).where(SessionBranchModel.branch_id == second_edit.branch_id)
            )
            assert second_branch is not None
            assert second_branch.parent_branch_id == first_edit.branch_id
            assert second_branch.source_message_id == first_edit.id
    finally:
        engine.dispose()


def test_editing_character_message_keeps_choices_out_of_story_text(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = _session(db)
            original = MessageModel(
                session_id=session.id,
                speaker_type="character",
                content="旧回复",
            )
            db.add(original)
            db.commit()

            replacement = update_message(
                session.id,
                original.id,
                SessionMessageEdit(
                    content="新的回答。\n\n### 可选行动\n1. 继续追问\n2. 暂时离开",
                    branch_id="main",
                ),
                db,
            )

            assert replacement.content == "新的回答。"
            assert replacement.structured_content["choices"] == ["继续追问", "暂时离开"]
            prompt = _build_recent_prompt_messages(
                db, session.id, replacement.branch_id, False, 40, False
            )
            assert [item["content"] for item in prompt] == ["角色：新的回答。"]
    finally:
        engine.dispose()


def test_edit_rejects_message_that_is_not_visible_in_requested_branch(tmp_path):
    engine, Session = _db(tmp_path)
    try:
        with Session() as db:
            session = _session(db)
            source = _message(db, session.id, "main source")
            db.add(SessionBranchModel(session_id=session.id, branch_id="A", source_message_id=source.id, parent_branch_id="main"))
            db.flush()
            sibling_message = _message(db, session.id, "only in A", "A")
            db.commit()

            with pytest.raises(HTTPException) as exc_info:
                update_message(
                    session.id,
                    sibling_message.id,
                    SessionMessageEdit(content="must not cross branches", branch_id="main"),
                    db,
                )
            assert exc_info.value.status_code == 404
            assert db.scalar(select(func.count()).select_from(SessionBranchModel)) == 1
            assert db.get(MessageModel, sibling_message.id).content == "only in A"
    finally:
        engine.dispose()

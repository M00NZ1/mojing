import asyncio
import json
import threading
import zipfile
from pathlib import Path

import pytest
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base
from backend.app.routes import sessions as sessions_routes
from backend.app.models import (
    CharacterModel,
    CharacterProfileModel,
    ChatSessionModel,
    MessageModel,
    SessionEventNodeModel,
    SessionMemorySegmentModel,
    SessionParticipantModel,
    SessionCharacterStateModel,
    SessionMemoryCorrectionModel,
    VoiceProfileModel,
)
from backend.app.services import chat_service, export_service, memory_service, memory_v2_service
from backend.app.services.memory_source_service import memory_deletion_plan


def _database(tmp_path, name="memory-v2.db"):
    engine = create_engine(
        f"sqlite:///{tmp_path / name}",
        connect_args={"check_same_thread": False},
    )
    Base.metadata.create_all(engine)
    return engine, sessionmaker(bind=engine, expire_on_commit=False)


@pytest.fixture
def isolated_export_storage(tmp_path, monkeypatch):
    storage_root = tmp_path / "isolated-storage"
    export_root = storage_root / "conversations"
    monkeypatch.setattr(export_service, "STORAGE_DIR", storage_root)
    monkeypatch.setattr(export_service, "EXPORT_ROOT", export_root)
    return storage_root, export_root


def _seed_session(Session, *, summary="", branches=("main",), messages_per_branch=12):
    with Session() as db:
        session = ChatSessionModel(title="记忆压缩测试", summary=summary)
        character = CharacterModel(name=f"记忆角色-{id(db)}", persona_prompt="记住剧情")
        db.add_all([session, character])
        db.flush()
        db.add(SessionParticipantModel(session_id=session.id, character_id=character.id))
        for branch_id in branches:
            for index in range(messages_per_branch):
                is_user = index % 2 == 0
                db.add(
                    MessageModel(
                        session_id=session.id,
                        branch_id=branch_id,
                        speaker_type="user" if is_user else "character",
                        character_id=None if is_user else character.id,
                        content=f"{branch_id}-消息-{index + 1}",
                    )
                )
        db.commit()
        return session.id, character.id


def _install_memory_fakes(monkeypatch, Session, *, fail_events=False, block_first_call=None):
    calls = []

    def fake_llm_call(client, model, messages, **kwargs):
        prompt = messages[0]["content"]
        calls.append(prompt)
        if block_first_call is not None and len(calls) == 1:
            entered, release = block_first_call
            entered.set()
            assert release.wait(3)
        if "提取关键事件节点" in prompt:
            if fail_events:
                raise RuntimeError("event extraction failed")
            return json.dumps(
                [
                    {
                        "title": f"事件-{len(calls)}",
                        "event_type": "discovery",
                        "description": "发现新的剧情线索",
                        "importance": 4,
                        "parent_event_title": None,
                    }
                ],
                ensure_ascii=False,
            )
        return json.dumps(
            {
                "summary": f"阶段摘要-{len(calls)}",
                "key_facts": ["事实A"],
                "key_characters": ["记忆角色"],
                "emotional_tone": "悬疑",
            },
            ensure_ascii=False,
        )

    monkeypatch.setattr(memory_service, "SessionLocal", Session)
    monkeypatch.setattr(memory_service, "build_client", lambda character, db: object())
    monkeypatch.setattr(memory_service, "resolve_text_model", lambda character, db: "local-memory-model")
    monkeypatch.setattr(memory_v2_service, "safe_non_streaming_call", fake_llm_call)
    return calls


def test_compaction_appends_bounded_segments_and_does_not_repeat_work(tmp_path, monkeypatch):
    engine, Session = _database(tmp_path)
    try:
        session_id, character_id = _seed_session(Session)
        calls = _install_memory_fakes(monkeypatch, Session)

        assert memory_service.compact_session_memory_v2(session_id, "main") is True
        assert len(calls) == 2
        with Session() as db:
            segments = list(db.scalars(select(SessionMemorySegmentModel)))
            events = list(db.scalars(select(SessionEventNodeModel)))
            session = db.get(ChatSessionModel, session_id)
            assert [(row.segment_index, row.start_message_id, row.end_message_id) for row in segments] == [(1, 1, 12)]
            assert len(events) == 1
            assert events[0].character_id is None
            assert "阶段摘要" in session.summary

        assert memory_service.compact_session_memory_v2(session_id, "main") is True
        assert len(calls) == 2

        with Session() as db:
            for index in range(12):
                is_user = index % 2 == 0
                db.add(
                    MessageModel(
                        session_id=session_id,
                        branch_id="main",
                        speaker_type="user" if is_user else "character",
                        character_id=None if is_user else character_id,
                        content=f"main-后续-{index + 1}",
                    )
                )
            db.commit()

        assert memory_service.compact_session_memory_v2(session_id, "main") is True
        assert len(calls) == 4
        with Session() as db:
            segments = list(
                db.scalars(select(SessionMemorySegmentModel).order_by(SessionMemorySegmentModel.segment_index))
            )
            assert [row.segment_index for row in segments] == [1, 2]
            assert segments[0].end_message_id < segments[1].start_message_id
    finally:
        engine.dispose()


def test_failed_event_extraction_rolls_back_segment_and_is_retriable(tmp_path, monkeypatch):
    engine, Session = _database(tmp_path, "memory-failure.db")
    try:
        session_id, _ = _seed_session(Session, summary="原摘要")
        _install_memory_fakes(monkeypatch, Session, fail_events=True)

        assert memory_service.compact_session_memory_v2(session_id, "main") is False
        with Session() as db:
            assert db.scalar(select(func.count()).select_from(SessionMemorySegmentModel)) == 0
            assert db.scalar(select(func.count()).select_from(SessionEventNodeModel)) == 0
            assert db.get(ChatSessionModel, session_id).summary == "原摘要"

        calls = _install_memory_fakes(monkeypatch, Session)
        assert memory_service.compact_session_memory_v2(session_id, "main") is True
        assert len(calls) == 2
        with Session() as db:
            assert db.scalar(select(func.count()).select_from(SessionMemorySegmentModel)) == 1
            assert db.scalar(select(func.count()).select_from(SessionEventNodeModel)) == 1
    finally:
        engine.dispose()


def test_branch_compaction_is_isolated_from_main_summary(tmp_path, monkeypatch):
    engine, Session = _database(tmp_path, "memory-branch.db")
    try:
        session_id, _ = _seed_session(
            Session,
            summary="主线原摘要",
            branches=("main", "branch-a"),
        )
        _install_memory_fakes(monkeypatch, Session)

        assert memory_service.compact_session_memory_v2(session_id, "branch-a") is True
        with Session() as db:
            segments = list(db.scalars(select(SessionMemorySegmentModel)))
            events = list(db.scalars(select(SessionEventNodeModel)))
            assert {row.branch_id for row in segments} == {"branch-a"}
            assert {row.branch_id for row in events} == {"branch-a"}
            assert db.get(ChatSessionModel, session_id).summary == "主线原摘要"

        assert memory_service.compact_session_memory_v2(session_id, "main") is True
        with Session() as db:
            assert {
                row.branch_id for row in db.scalars(select(SessionMemorySegmentModel))
            } == {"main", "branch-a"}
            assert db.get(ChatSessionModel, session_id).summary != "主线原摘要"
    finally:
        engine.dispose()


def test_same_branch_compaction_is_single_flight(tmp_path, monkeypatch):
    engine, Session = _database(tmp_path, "memory-concurrency.db")
    try:
        session_id, _ = _seed_session(Session)
        entered = threading.Event()
        release = threading.Event()
        calls = _install_memory_fakes(monkeypatch, Session, block_first_call=(entered, release))
        results = []

        first = threading.Thread(
            target=lambda: results.append(memory_service.compact_session_memory_v2(session_id, "main"))
        )
        second = threading.Thread(
            target=lambda: results.append(memory_service.compact_session_memory_v2(session_id, "main"))
        )
        first.start()
        assert entered.wait(2)
        second.start()
        release.set()
        first.join(5)
        second.join(5)

        assert not first.is_alive()
        assert not second.is_alive()
        assert results == [True, True]
        assert len(calls) == 2
        with Session() as db:
            assert db.scalar(select(func.count()).select_from(SessionMemorySegmentModel)) == 1
            assert db.scalar(select(func.count()).select_from(SessionEventNodeModel)) == 1
    finally:
        engine.dispose()


def test_large_backlog_is_processed_in_bounded_batches(tmp_path, monkeypatch):
    engine, Session = _database(tmp_path, "memory-backlog.db")
    try:
        session_id, _ = _seed_session(Session, messages_per_branch=100)
        calls = _install_memory_fakes(monkeypatch, Session)

        assert memory_service.compact_session_memory_v2(session_id, "main") is True
        assert memory_service.compact_session_memory_v2(session_id, "main") is True
        assert memory_service.compact_session_memory_v2(session_id, "main") is True

        with Session() as db:
            segments = list(
                db.scalars(select(SessionMemorySegmentModel).order_by(SessionMemorySegmentModel.segment_index))
            )
            assert [(row.start_message_id, row.end_message_id) for row in segments] == [
                (1, 40),
                (41, 80),
                (81, 100),
            ]
        assert len(calls) == 6
    finally:
        engine.dispose()


def test_async_trigger_forwards_the_generation_branch(monkeypatch):
    captured = {}

    class FakeThread:
        def __init__(self, *, target, args, daemon):
            captured.update(target=target, args=args, daemon=daemon)

        def start(self):
            captured["started"] = True

    monkeypatch.setattr(chat_service.threading, "Thread", FakeThread)

    chat_service.trigger_memory_compaction_async(9, "branch-b")

    assert captured["target"] is memory_service.compact_session_memory_v2
    assert captured["args"] == (9, "branch-b")
    assert captured["daemon"] is True
    assert captured["started"] is True


def test_session_snapshot_is_generated_only_when_export_is_requested(tmp_path, isolated_export_storage):
    engine, Session = _database(tmp_path, "snapshot-on-demand.db")
    try:
        _, snapshot_root = isolated_export_storage
        with Session() as db:
            session = ChatSessionModel(title="按需导出")
            db.add(session)
            db.commit()
            session_id = session.id
            chat_service.create_message(
                db,
                session_id=session_id,
                speaker_type="user",
                content="这条消息只写入主数据库",
            )
            assert not snapshot_root.exists()

            export_service.export_session_snapshot(db, session_id)
            messages_file = snapshot_root / f"convo_{session_id:04d}" / "messages" / "messages.jsonl"
            assert messages_file.exists()
            assert "这条消息只写入主数据库" in messages_file.read_text(encoding="utf-8")
            zip_path = export_service.build_session_export_zip(session_id)
            assert zip_path.is_file()
            assert zip_path.parent == isolated_export_storage[0] / "exports"
    finally:
        engine.dispose()


def test_session_snapshot_replaces_stale_participants_and_zip_contents(tmp_path, isolated_export_storage):
    engine, Session = _database(tmp_path, "snapshot-replacement.db")
    try:
        storage_root, export_root = isolated_export_storage

        with Session() as db:
            session = ChatSessionModel(title="快照替换")
            old_voice = VoiceProfileModel(
                name="旧声音", reference_audio_path="builtin:old", description="old voice"
            )
            new_voice = VoiceProfileModel(
                name="新声音", reference_audio_path="builtin:new", description="new voice"
            )
            old_character = CharacterModel(name="旧角色", voice_profile=old_voice)
            new_character = CharacterModel(name="新角色", voice_profile=new_voice)
            db.add_all([session, old_character, new_character])
            db.flush()
            db.add_all(
                [
                    SessionParticipantModel(session_id=session.id, character_id=old_character.id, sort_order=0),
                    SessionParticipantModel(session_id=session.id, character_id=new_character.id, sort_order=1),
                    CharacterProfileModel(
                        character_id=old_character.id,
                        source_filename="old_profile.txt",
                        raw_persona_text="old profile",
                    ),
                    SessionCharacterStateModel(
                        session_id=session.id,
                        character_id=old_character.id,
                        dynamic_state_json={"state": "old"},
                    ),
                ]
            )
            db.commit()
            session_id = session.id

            export_service.export_session_snapshot(db, session_id)
            snapshot = export_root / f"convo_{session_id:04d}"
            assert (snapshot / "characters" / "旧角色").exists()
            first_snapshot = {
                path.relative_to(snapshot).as_posix(): path.read_bytes()
                for path in snapshot.rglob("*")
                if path.is_file()
            }

            participant = db.scalar(
                select(SessionParticipantModel).where(
                    SessionParticipantModel.session_id == session_id,
                    SessionParticipantModel.character_id == old_character.id,
                )
            )
            db.delete(participant)
            db.commit()
            zip_path = export_service.build_session_export_archive(db, session_id)

            current_files = [path.relative_to(snapshot).as_posix() for path in snapshot.rglob("*") if path.is_file()]
            assert any("新角色" in path for path in current_files)
            assert not any("旧角色" in path or "old_profile" in path for path in current_files)
            assert first_snapshot != {
                path.relative_to(snapshot).as_posix(): path.read_bytes()
                for path in snapshot.rglob("*")
                if path.is_file()
            }

            assert zip_path.parent == storage_root / "exports"
            assert zip_path.name.startswith(f"session_{session_id:04d}-")
            with zipfile.ZipFile(zip_path) as archive:
                zip_names = archive.namelist()
            assert any("新角色" in name for name in zip_names)
            assert not any("旧角色" in name or "old_profile" in name for name in zip_names)
            assert not list(export_root.glob(".convo_*.tmp-*"))
            assert not list(export_root.glob(".convo_*.old-*"))
    finally:
        engine.dispose()


def test_failed_session_snapshot_keeps_previous_snapshot(tmp_path, monkeypatch, isolated_export_storage):
    engine, Session = _database(tmp_path, "snapshot-failure.db")
    try:
        _, snapshot_root = isolated_export_storage
        with Session() as db:
            session = ChatSessionModel(title="失败保留")
            character = CharacterModel(name="保留角色")
            db.add_all([session, character])
            db.flush()
            db.add(SessionParticipantModel(session_id=session.id, character_id=character.id))
            db.commit()
            session_id = session.id
            export_service.export_session_snapshot(db, session_id)
            snapshot = snapshot_root / f"convo_{session_id:04d}"
            before = {
                path.relative_to(snapshot).as_posix(): path.read_bytes()
                for path in snapshot.rglob("*")
                if path.is_file()
            }

            def fail_dump(*args, **kwargs):
                raise RuntimeError("simulated snapshot build failure")

            monkeypatch.setattr(export_service.json, "dumps", fail_dump)
            with pytest.raises(RuntimeError, match="simulated snapshot build failure"):
                export_service.export_session_snapshot(db, session_id)

            after = {
                path.relative_to(snapshot).as_posix(): path.read_bytes()
                for path in snapshot.rglob("*")
                if path.is_file()
            }
            assert after == before
            assert not list(snapshot_root.glob(".convo_*.tmp-*"))
            assert not list(snapshot_root.glob(".convo_*.old-*"))
    finally:
        engine.dispose()


def test_base_to_old_failure_never_deletes_original_snapshot(tmp_path, monkeypatch, isolated_export_storage):
    engine, Session = _database(tmp_path, "snapshot-base-move-failure.db")
    try:
        _, snapshot_root = isolated_export_storage
        with Session() as db:
            session = ChatSessionModel(title="原快照不可删除")
            db.add(session)
            db.commit()
            session_id = session.id
            export_service.export_session_snapshot(db, session_id)
            base_dir = snapshot_root / f"convo_{session_id:04d}"
            before = _snapshot_files(base_dir)
            original_replace = Path.replace

            def fail_base_move(path, target):
                if path == base_dir and ".old-" in Path(target).name:
                    raise OSError("simulated base to old failure")
                return original_replace(path, target)

            monkeypatch.setattr(Path, "replace", fail_base_move)
            with pytest.raises(export_service.SnapshotPublishError, match="原快照保持"):
                export_service.export_session_snapshot(db, session_id)

            assert _snapshot_files(base_dir) == before
            assert not list(snapshot_root.glob(f".convo_{session_id:04d}.tmp-*"))
            assert not list(snapshot_root.glob(f".convo_{session_id:04d}.old-*"))
    finally:
        engine.dispose()


def test_temp_to_base_failure_restores_old_snapshot(tmp_path, monkeypatch, isolated_export_storage):
    engine, Session = _database(tmp_path, "snapshot-temp-move-failure.db")
    try:
        _, snapshot_root = isolated_export_storage
        with Session() as db:
            session = ChatSessionModel(title="恢复旧快照")
            db.add(session)
            db.commit()
            session_id = session.id
            export_service.export_session_snapshot(db, session_id)
            base_dir = snapshot_root / f"convo_{session_id:04d}"
            before = _snapshot_files(base_dir)
            original_replace = Path.replace

            def fail_temp_publish(path, target):
                if path.name.startswith(f".convo_{session_id:04d}.tmp-") and Path(target) == base_dir:
                    raise OSError("simulated temp to base failure")
                return original_replace(path, target)

            monkeypatch.setattr(Path, "replace", fail_temp_publish)
            with pytest.raises(export_service.SnapshotPublishError, match="新快照发布失败"):
                export_service.export_session_snapshot(db, session_id)

            assert _snapshot_files(base_dir) == before
            assert not list(snapshot_root.glob(f".convo_{session_id:04d}.tmp-*"))
            assert not list(snapshot_root.glob(f".convo_{session_id:04d}.old-*"))
    finally:
        engine.dispose()


def test_concurrent_archives_are_serialized_and_use_unique_zip_paths(
    tmp_path, monkeypatch, isolated_export_storage
):
    engine, Session = _database(tmp_path, "snapshot-concurrency.db")
    try:
        storage_root, _ = isolated_export_storage
        with Session() as db:
            session = ChatSessionModel(title="并发导出")
            db.add(session)
            db.commit()
            session_id = session.id

        original_write = export_service._write_session_snapshot
        first_entered = threading.Event()
        second_entered = threading.Event()
        release_first = threading.Event()
        state_lock = threading.Lock()
        active = 0
        max_active = 0
        entries = 0

        def controlled_write(db, requested_session_id, base_dir):
            nonlocal active, max_active, entries
            with state_lock:
                active += 1
                max_active = max(max_active, active)
                entries += 1
                current_entry = entries
            if current_entry == 1:
                first_entered.set()
                assert release_first.wait(3)
            else:
                second_entered.set()
            try:
                return original_write(db, requested_session_id, base_dir)
            finally:
                with state_lock:
                    active -= 1

        monkeypatch.setattr(export_service, "_write_session_snapshot", controlled_write)
        paths = []
        failures = []
        result_lock = threading.Lock()

        def worker():
            try:
                with Session() as db:
                    path = export_service.build_session_export_archive(db, session_id)
                with result_lock:
                    paths.append(path)
            except BaseException as exc:
                with result_lock:
                    failures.append(exc)

        first = threading.Thread(target=worker)
        second = threading.Thread(target=worker)
        first.start()
        assert first_entered.wait(2)
        second.start()
        assert not second_entered.wait(0.2)
        release_first.set()
        first.join(5)
        second.join(5)

        assert not first.is_alive() and not second.is_alive()
        assert failures == []
        assert max_active == 1
        assert len(paths) == 2
        assert paths[0] != paths[1]
        assert all(path.parent == storage_root / "exports" and path.is_file() for path in paths)
    finally:
        engine.dispose()


def test_export_route_deletes_unique_zip_after_file_response(
    tmp_path, isolated_export_storage
):
    engine, Session = _database(tmp_path, "snapshot-response-cleanup.db")
    try:
        with Session() as db:
            session = ChatSessionModel(title="响应后清理")
            db.add(session)
            db.commit()
            session_id = session.id

            response = sessions_routes.export_session_archive(session_id, db)
            zip_path = Path(response.path)
            assert zip_path.is_file()
            assert f'filename="session_{session_id:04d}.zip"' in response.headers["content-disposition"]
            assert response.background is not None
            asyncio.run(response.background())
            assert not zip_path.exists()
    finally:
        engine.dispose()


def _snapshot_files(snapshot: Path) -> dict[str, bytes]:
    return {
        path.relative_to(snapshot).as_posix(): path.read_bytes()
        for path in snapshot.rglob("*")
        if path.is_file()
    }


def test_deletion_rewinds_only_affected_automatic_memory_and_preserves_locked_facts(tmp_path):
    engine, Session = _database(tmp_path)
    try:
        sid, cid = _seed_session(Session, summary='自动主线概览', branches=('main', 'other'), messages_per_branch=36)
        with Session() as db:
            for index, start in enumerate((1, 13, 25, 37), 1):
                db.add(SessionMemorySegmentModel(session_id=sid, branch_id='main' if start < 37 else 'other',
                    segment_index=index, start_message_id=start, end_message_id=start + 11,
                    summary='已删除的秘密' if start == 13 else f'保留-{start}'))
            for mid in (8, 24, 36, 48):
                db.add(SessionEventNodeModel(session_id=sid, branch_id='main' if mid < 37 else 'other', message_id=mid, title=f'事件-{mid}'))
            db.add(SessionMemoryCorrectionModel(session_id=sid, content='用户锁定事实', source_message_id=20))
            db.add(SessionCharacterStateModel(session_id=sid, character_id=cid, dynamic_state_json={'手动状态': '保留'}))
            db.commit()
            plan = memory_deletion_plan(db, db.get(MessageModel, 20))
            assert plan == {'start': 13, 'memory_segments_removed': 2, 'memory_events_removed': 2, 'summary_reset': True}
            sessions_routes.delete_message(sid, 20, db=db)
        with Session() as db:
            assert db.get(MessageModel, 20) is None
            assert db.scalar(select(func.count()).select_from(MessageModel)) == 71
            assert list(db.scalars(select(SessionMemorySegmentModel.start_message_id).order_by(SessionMemorySegmentModel.start_message_id))) == [1, 37]
            assert list(db.scalars(select(SessionEventNodeModel.message_id).order_by(SessionEventNodeModel.message_id))) == [8, 48]
            assert db.get(ChatSessionModel, sid).summary == ''
            assert db.scalar(select(SessionMemoryCorrectionModel)).content == '用户锁定事实'
            assert db.scalar(select(SessionCharacterStateModel)).dynamic_state_json == {'手动状态': '保留'}
            runtime = memory_v2_service.build_context_memory(db, sid, 'main')
            assert '已删除的秘密' not in runtime
            assert '用户锁定事实' in runtime
    finally:
        engine.dispose()


@pytest.mark.parametrize('blocked_call', [1, 2])
def test_deletion_during_remote_memory_work_rejects_late_result_and_can_retry(tmp_path, monkeypatch, blocked_call):
    engine, Session = _database(tmp_path)
    entered, release = threading.Event(), threading.Event()
    worker = None
    try:
        sid, _ = _seed_session(Session, summary='旧概览', messages_per_branch=24)
        calls = _install_memory_fakes(monkeypatch, Session)
        original_call = memory_v2_service.safe_non_streaming_call

        def delayed(*args, **kwargs):
            if len(calls) + 1 == blocked_call:
                entered.set()
                assert release.wait(5)
            return original_call(*args, **kwargs)

        monkeypatch.setattr(memory_v2_service, 'safe_non_streaming_call', delayed)
        results = []
        worker = threading.Thread(target=lambda: results.append(memory_service.compact_session_memory_v2(sid)))
        worker.start()
        assert entered.wait(3)
        # A separate writer must be able to commit while the model is waiting.
        with Session() as db:
            sessions_routes.delete_message(sid, 20, db=db)
        release.set()
        worker.join(5)
        assert not worker.is_alive()
        assert results == [False]
        with Session() as db:
            assert db.scalar(select(func.count()).select_from(SessionMemorySegmentModel)) == 0
            assert db.scalar(select(func.count()).select_from(SessionEventNodeModel)) == 0
            assert db.get(ChatSessionModel, sid).summary == ''
        monkeypatch.setattr(memory_v2_service, 'safe_non_streaming_call', original_call)
        assert memory_service.compact_session_memory_v2(sid)
        with Session() as db:
            assert db.scalar(select(func.count()).select_from(SessionMemorySegmentModel)) == 1
            assert db.get(MessageModel, 20) is None
    finally:
        release.set()
        if worker:
            worker.join(6)
        engine.dispose()


def test_rewinding_an_earlier_segment_rejects_later_inflight_batch(tmp_path, monkeypatch):
    engine, Session = _database(tmp_path)
    try:
        sid, _ = _seed_session(Session, messages_per_branch=24)
        with Session() as db:
            db.add(SessionMemorySegmentModel(session_id=sid, branch_id='main', segment_index=1,
                start_message_id=1, end_message_id=12, summary='旧阶段'))
            db.commit()
        _install_memory_fakes(monkeypatch, Session)
        original = memory_v2_service.safe_non_streaming_call
        deleted = False

        def delete_earlier(*args, **kwargs):
            nonlocal deleted
            if not deleted:
                with Session() as db:
                    sessions_routes.delete_message(sid, 5, db=db)
                deleted = True
            return original(*args, **kwargs)

        monkeypatch.setattr(memory_v2_service, 'safe_non_streaming_call', delete_earlier)
        assert not memory_service.compact_session_memory_v2(sid)
        with Session() as db:
            assert db.scalar(select(func.count()).select_from(SessionMemorySegmentModel)) == 0
    finally:
        engine.dispose()


def test_empty_event_result_still_publishes_validated_summary(tmp_path, monkeypatch):
    engine, Session = _database(tmp_path)
    try:
        Session.configure(autoflush=False)
        sid, _ = _seed_session(Session)
        _install_memory_fakes(monkeypatch, Session)
        original = memory_v2_service.safe_non_streaming_call
        monkeypatch.setattr(memory_v2_service, 'safe_non_streaming_call', lambda *args, **kwargs:
            '[]' if '提取关键事件节点' in kwargs['messages'][0]['content'] else original(*args, **kwargs))
        assert memory_service.compact_session_memory_v2(sid)
        with Session() as db:
            assert db.scalar(select(func.count()).select_from(SessionMemorySegmentModel)) == 1
            assert db.scalar(select(func.count()).select_from(SessionEventNodeModel)) == 0
            assert '阶段摘要' in db.get(ChatSessionModel, sid).summary
    finally:
        engine.dispose()

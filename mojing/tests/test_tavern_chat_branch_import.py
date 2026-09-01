import json

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base, get_db
from backend.app.main import app
from backend.app.models import (
    CharacterModel,
    ChatSessionModel,
    MessageModel,
    SessionBranchModel,
    SessionParticipantModel,
)
from backend.app.services.chat_service import get_session_messages_page


@pytest.fixture
def branch_import_client(tmp_path):
    engine = create_engine(
        f"sqlite:///{tmp_path / 'tavern-branch.db'}",
        connect_args={"check_same_thread": False},
    )
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False, autoflush=False)

    def override_get_db():
        with Session() as db:
            yield db

    app.dependency_overrides[get_db] = override_get_db
    try:
        with TestClient(app) as client:
            yield client, Session
    finally:
        app.dependency_overrides.clear()
        engine.dispose()


def _seed_branched_session(Session):
    with Session() as db:
        session = ChatSessionModel(title="分支导入")
        character = CharacterModel(name="向导")
        db.add_all([session, character])
        db.flush()
        db.add(SessionParticipantModel(session_id=session.id, character_id=character.id))
        source = MessageModel(session_id=session.id, speaker_type="user", content="分叉点")
        db.add(source)
        db.flush()
        db.add(MessageModel(session_id=session.id, speaker_type="character", content="主线后续"))
        db.add(
            SessionBranchModel(
                session_id=session.id,
                branch_id="story-a",
                label="雨夜路线",
                source_message_id=source.id,
                parent_branch_id="main",
            )
        )
        db.commit()
        return session.id, character.id, source.id


def _tavern_file():
    payload = {
        "mes": [
            {"is_user": True, "mes": "进入小巷"},
            {"is_user": False, "mes": "向导点亮提灯"},
        ]
    }
    return {"file": ("branch-chat.json", json.dumps(payload, ensure_ascii=False).encode("utf-8"), "application/json")}


def test_tavern_import_writes_and_chains_messages_in_selected_branch(branch_import_client):
    client, Session = branch_import_client
    session_id, character_id, source_id = _seed_branched_session(Session)

    response = client.post(
        f"/api/sessions/{session_id}/import-tavern-chat",
        data={"character_id": str(character_id), "branch_id": "story-a"},
        files=_tavern_file(),
    )

    assert response.status_code == 200
    assert response.json() == {"imported": 2, "branch_id": "story-a"}
    with Session() as db:
        imported = list(
            db.scalars(
                select(MessageModel)
                .where(MessageModel.session_id == session_id, MessageModel.branch_id == "story-a")
                .order_by(MessageModel.id.asc())
            )
        )
        assert [row.content for row in imported] == ["进入小巷", "向导点亮提灯"]
        assert [row.parent_message_id for row in imported] == [source_id, imported[0].id]
        main_rows, _ = get_session_messages_page(db, session_id, None, 40, "main")
        branch_rows, _ = get_session_messages_page(db, session_id, None, 40, "story-a")
        assert [row.content for row in main_rows] == ["分叉点", "主线后续"]
        assert [row.content for row in branch_rows] == ["分叉点", "进入小巷", "向导点亮提灯"]


def test_tavern_import_rejects_unknown_branch_without_writes(branch_import_client):
    client, Session = branch_import_client
    session_id, character_id, _ = _seed_branched_session(Session)

    response = client.post(
        f"/api/sessions/{session_id}/import-tavern-chat",
        data={"character_id": str(character_id), "branch_id": "missing"},
        files=_tavern_file(),
    )

    assert response.status_code == 400
    assert "分支不存在" in response.json()["detail"]
    with Session() as db:
        assert db.scalar(
            select(func.count())
            .select_from(MessageModel)
            .where(MessageModel.session_id == session_id, MessageModel.branch_id != "main")
        ) == 0

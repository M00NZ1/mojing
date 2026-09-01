from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base, get_db
from backend.app.main import create_app
from backend.app.models import ChatSessionModel, MessageModel


def test_search_hit_window_and_newer_cursor_are_reachable_through_http(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'message-window-api.db'}", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)

    with Session() as db:
        session = ChatSessionModel(title="深历史搜索 API")
        db.add(session)
        db.flush()
        messages = [
            MessageModel(
                session_id=session.id,
                branch_id="main",
                speaker_type="user",
                content=f"历史消息 {index}" + (" 唯一远页命中" if index == 37 else ""),
            )
            for index in range(1, 126)
        ]
        db.add_all(messages)
        db.commit()
        session_id = session.id
        anchor_id = messages[36].id

    app = create_app()

    def override_db():
        with Session() as db:
            yield db

    app.dependency_overrides[get_db] = override_db
    try:
        with TestClient(app) as client:
            search = client.get(
                f"/api/sessions/{session_id}/messages/search",
                params={"q": "唯一远页命中", "branch_id": "main"},
            )
            assert search.status_code == 200
            assert [item["id"] for item in search.json()] == [anchor_id]

            window = client.get(
                f"/api/sessions/{session_id}/messages/window",
                params={"anchor_id": anchor_id, "radius": 5, "branch_id": "main"},
            )
            assert window.status_code == 200
            payload = window.json()
            assert [item["id"] for item in payload["items"]] == list(range(anchor_id - 5, anchor_id + 6))
            assert payload["older_cursor"] == anchor_id - 5
            assert payload["newer_cursor"] == anchor_id + 5

            newer = client.get(
                f"/api/sessions/{session_id}/messages",
                params={"after": payload["newer_cursor"], "limit": 40, "branch_id": "main"},
            )
            assert newer.status_code == 200
            assert newer.json()["items"][0]["id"] == anchor_id + 6
            assert newer.json()["next_cursor"] == anchor_id + 45

            invalid_direction = client.get(
                f"/api/sessions/{session_id}/messages",
                params={"cursor": anchor_id, "after": anchor_id, "branch_id": "main"},
            )
            assert invalid_direction.status_code == 400

            missing = client.get(
                f"/api/sessions/{session_id}/messages/window",
                params={"anchor_id": 999_999, "branch_id": "main"},
            )
            assert missing.status_code == 404
            assert missing.json()["detail"] == "消息已删除或不在当前故事线"
    finally:
        app.dependency_overrides.clear()
        engine.dispose()

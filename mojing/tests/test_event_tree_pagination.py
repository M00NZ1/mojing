from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base, get_db
from backend.app.models import ChatSessionModel, SessionEventNodeModel
from backend.app.routes.sessions import get_event_tree, router


def test_event_tree_pages_recent_first_without_mixing_branches_or_new_rows(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'event-pages.db'}", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)
    try:
        with Session() as db:
            session = ChatSessionModel(title="长篇事件")
            other = ChatSessionModel(title="另一会话")
            db.add_all([session, other])
            db.flush()
            main = []
            for index in range(105):
                event = SessionEventNodeModel(session_id=session.id, branch_id="main", title=f"事件{index}")
                db.add(event)
                db.flush()
                main.append(event.id)
                if index % 20 == 0:
                    db.add(SessionEventNodeModel(session_id=session.id, branch_id="side", title="支线事件"))
                    db.add(SessionEventNodeModel(session_id=other.id, branch_id="main", title="其他会话事件"))
            db.commit()

            first = get_event_tree(session.id, "main", None, 40, db)
            newest = SessionEventNodeModel(session_id=session.id, branch_id="main", title="刚发生")
            db.add(newest)
            db.commit()
            second = get_event_tree(session.id, "main", first["next_cursor"], 40, db)
            third = get_event_tree(session.id, "main", second["next_cursor"], 40, db)
            assert [[event.id for event in page["items"]] for page in (first, second, third)] == [
                main[::-1][:40], main[::-1][40:80], main[::-1][80:],
            ]
            assert first["next_cursor"] == main[::-1][39]
            assert second["next_cursor"] == main[::-1][79]
            assert third["next_cursor"] is None
            assert len(get_event_tree(session.id, "side", None, 40, db)["items"]) == 6

            app = FastAPI()
            app.include_router(router, prefix="/api")

            def isolated_db():
                with Session() as route_db:
                    yield route_db

            app.dependency_overrides[get_db] = isolated_db
            with TestClient(app) as client:
                response = client.get(f"/api/sessions/{session.id}/event-tree", params={"branch_id": "main", "limit": 40})
                assert response.status_code == 200
                assert [item["id"] for item in response.json()["items"]] == [newest.id, *main[::-1][:39]]
                assert response.json()["next_cursor"] == main[::-1][38]
                assert client.get(f"/api/sessions/{session.id}/event-tree", params={"limit": 101}).status_code == 422
    finally:
        engine.dispose()

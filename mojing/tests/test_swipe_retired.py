from fastapi.testclient import TestClient
from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base, get_db
from backend.app.main import create_app
from backend.app.models import MessageModel


def test_retired_swipe_endpoint_does_not_create_messages(tmp_path):
    engine = create_engine(
        f"sqlite:///{tmp_path / 'swipe-retired.db'}",
        connect_args={"check_same_thread": False},
    )
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)
    app = create_app()

    def override_db():
        with Session() as db:
            yield db

    app.dependency_overrides[get_db] = override_db
    try:
        with TestClient(app) as client:
            response = client.post("/api/sessions/1/messages/1/swipe")

        assert response.status_code == 410
        assert response.json()["detail"] == "Swipe 已停用，请使用“重新生成”创建新的剧情分支"
        with Session() as db:
            assert db.scalar(select(func.count(MessageModel.id))) == 0
    finally:
        app.dependency_overrides.clear()
        engine.dispose()

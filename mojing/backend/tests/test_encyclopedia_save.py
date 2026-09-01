from fastapi.testclient import TestClient

from backend.app.database import get_db
from backend.app.main import create_app
from backend.app.middleware.rate_limit import RateLimitMiddleware
from backend.app.models import WorldEncyclopediaModel


class FakeQueryResult:
    def __init__(self, value):
        self.value = value

    def scalar_one_or_none(self):
        return self.value


class FakeDb:
    def __init__(self):
        self.created = []
        self.committed = False
        self.refreshed = None
        self.by_id = {}
        self.by_name = {}

    def get(self, model, obj_id):
        if model is WorldEncyclopediaModel:
            return self.by_id.get(obj_id)
        return None

    def scalar(self, statement):
        return None

    def execute(self, statement):
        return FakeQueryResult(None)

    def add(self, obj):
        obj.id = len(self.created) + 1
        self.created.append(obj)

    def commit(self):
        self.committed = True

    def refresh(self, obj):
        self.refreshed = obj


def build_client(fake_db):
    app = create_app()
    app.user_middleware = [middleware for middleware in app.user_middleware if middleware.cls is not RateLimitMiddleware]
    app.middleware_stack = app.build_middleware_stack()

    def override_db():
        yield fake_db

    app.dependency_overrides[get_db] = override_db
    return app, TestClient(app)


def test_save_encyclopedia_creates_core_world_fields():
    fake_db = FakeDb()
    app, client = build_client(fake_db)

    response = client.post(
        "/api/encyclopedia",
        json={
            "name": "灰烬王朝",
            "description": "末日后的王朝设定",
            "genre_tags": "黑暗奇幻,王权斗争",
            "world_prompt": "王朝建立在灰烬海之上。",
            "gameplay_mode": "剧情推进",
            "anti_cheat_prompt": "禁止玩家绕过继承法。",
        },
    )

    assert response.status_code == 200
    created = fake_db.created[0]
    assert created.genre_tags == "黑暗奇幻,王权斗争"
    assert created.world_prompt == "王朝建立在灰烬海之上。"
    assert created.gameplay_mode == "剧情推进"
    assert created.anti_cheat_prompt == "禁止玩家绕过继承法。"
    app.dependency_overrides.clear()


def test_save_encyclopedia_updates_by_id_and_keeps_existing_when_field_absent():
    existing = WorldEncyclopediaModel(
        id=7,
        name="旧百科",
        description="旧描述",
        genre_tags="旧体裁",
        world_prompt="旧世界补充",
        gameplay_mode="自由剧情",
        anti_cheat_prompt="旧反作弊",
    )
    fake_db = FakeDb()
    fake_db.by_id[7] = existing
    app, client = build_client(fake_db)

    response = client.post(
        "/api/encyclopedia",
        json={
            "id": 7,
            "name": "新百科名",
            "description": "新描述",
            "world_prompt": "新的世界补充",
        },
    )

    assert response.status_code == 200
    assert existing.name == "新百科名"
    assert existing.description == "新描述"
    assert existing.world_prompt == "新的世界补充"
    assert existing.genre_tags == "旧体裁"
    assert existing.gameplay_mode == "自由剧情"
    assert existing.anti_cheat_prompt == "旧反作弊"
    app.dependency_overrides.clear()

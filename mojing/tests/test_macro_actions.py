from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from backend.app.database import Base
from backend.app.models import EncyclopediaEntryModel, WorldEncyclopediaModel
from backend.app.services.macro_actions import _action_check_conflicts


def test_setting_conflicts_include_readable_entry_destinations(tmp_path):
    engine = create_engine(
        f"sqlite:///{tmp_path / 'macro-actions.db'}",
        connect_args={"check_same_thread": False},
    )
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False, autoflush=False)
    try:
        with Session() as db:
            encyclopedia = WorldEncyclopediaModel(name="雾海纪事")
            db.add(encyclopedia)
            db.flush()
            first = EncyclopediaEntryModel(
                encyclopedia_id=encyclopedia.id,
                title="雾港",
                entry_type="location",
                summary="现行设定",
                tags="港口",
            )
            second = EncyclopediaEntryModel(
                encyclopedia_id=encyclopedia.id,
                title="雾港",
                entry_type="location",
                summary="旧稿",
                tags="港口",
            )
            db.add_all([first, second])
            db.commit()

            result = _action_check_conflicts({"encyclopedia_id": encyclopedia.id}, db)

            assert result["conflict_count"] == 1
            conflict = result["conflicts"][0]
            assert conflict["entry_ids"] == [first.id, second.id]
            assert conflict["entries"] == [
                {"id": first.id, "title": "雾港", "entry_type": "location", "summary": "现行设定"},
                {"id": second.id, "title": "雾港", "entry_type": "location", "summary": "旧稿"},
            ]
    finally:
        engine.dispose()

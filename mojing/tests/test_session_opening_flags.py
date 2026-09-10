import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session
from backend.app.database import Base
from backend.app.models import SessionWorldModel
from backend.app.routes import sessions
from backend.app.schemas import SessionCreate


@pytest.mark.parametrize("defaults", [False, True])
@pytest.mark.parametrize("explicit", [None, False, True])
def test_opening_flags_distinguish_omitted_values_from_explicit_choices(tmp_path, monkeypatch, defaults, explicit):
    engine = create_engine(f"sqlite:///{tmp_path / 'opening.db'}")
    Base.metadata.create_all(engine)
    fields = ("narrator_enabled", "choice_generation_enabled", "anti_cheat_enabled")
    monkeypatch.setattr(sessions, "get_local_config", lambda db: {f"default_{key}": defaults for key in fields})
    values = {} if explicit is None else {key: explicit for key in fields}
    try:
        with Session(engine) as db:
            result = sessions.create_session(SessionCreate(title="新故事", **values), db)
            db.expire_all()
            world = db.scalar(select(SessionWorldModel).where(SessionWorldModel.session_id == result["id"]))
            assert world is not None
            for key in fields:
                assert getattr(world, key) is (defaults if explicit is None else explicit)
    finally:
        engine.dispose()

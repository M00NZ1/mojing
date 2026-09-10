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


def test_missing_opening_character_does_not_create_a_partial_session(tmp_path, monkeypatch):
    from fastapi import HTTPException
    from backend.app.models import ChatSessionModel, CharacterModel, SessionParticipantModel
    engine = create_engine(f"sqlite:///{tmp_path / 'participants.db'}")
    Base.metadata.create_all(engine)
    monkeypatch.setattr(sessions, "get_local_config", lambda db: {})
    try:
        with Session(engine) as db:
            character = CharacterModel(name="沈照", persona_prompt="守灯人")
            db.add(character)
            db.commit()
            with pytest.raises(HTTPException) as error:
                sessions.create_session(SessionCreate(initial_character_ids=[character.id, 999]), db)
            assert error.value.status_code == 409
            db.commit()
            assert list(db.scalars(select(ChatSessionModel))) == []
            assert list(db.scalars(select(SessionWorldModel))) == []
            assert list(db.scalars(select(SessionParticipantModel))) == []
            assert db.get(CharacterModel, character.id) is not None
    finally:
        engine.dispose()


def test_opening_participant_order_count_and_deduplication(tmp_path, monkeypatch):
    from backend.app.models import CharacterModel, SessionParticipantModel
    engine = create_engine(f"sqlite:///{tmp_path / 'participants.db'}")
    Base.metadata.create_all(engine)
    monkeypatch.setattr(sessions, "get_local_config", lambda db: {})
    try:
        with Session(engine) as db:
            a, b = CharacterModel(name="沈照"), CharacterModel(name="林汐")
            db.add_all([a, b])
            db.commit()
            result = sessions.create_session(SessionCreate(initial_character_ids=[b.id, a.id, b.id]), db)
            participants = list(db.scalars(select(SessionParticipantModel).where(
                SessionParticipantModel.session_id == result["id"]).order_by(SessionParticipantModel.sort_order)))
            assert result["participant_count"] == 2
            assert [p.character_id for p in participants] == [b.id, a.id]
    finally:
        engine.dispose()

import io
import json
from types import SimpleNamespace

import pytest
from fastapi import HTTPException, UploadFile
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session

from backend.app.database import Base
from backend.app.models import CharacterModel, CharacterProfileModel
from backend.app.routes import characters
from backend.app.services.character_portable_service import build_portable_payload, portable_to_txt
from backend.app.utils.docx_simple import write_plain_docx

VALUES = dict(temperature=0.0, max_tokens=4097, top_p=0.37, frequency_penalty=-1.5, presence_penalty=1.25)
DEFAULTS = dict(temperature=0.9, max_tokens=1200, top_p=1.0, frequency_penalty=0.0, presence_penalty=0.0)


@pytest.fixture
def db(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'sampling.db'}")
    Base.metadata.create_all(engine)
    with Session(engine) as session:
        session.add(CharacterModel(name="合成源", persona_prompt="原始正文", api_key="synthetic-key", **VALUES))
        session.commit()
        yield session
    engine.dispose()


def encode(payload, fmt):
    if fmt == "json":
        return json.dumps(payload).encode()
    txt = portable_to_txt(payload)
    return txt.encode() if fmt == "txt" else write_plain_docx(txt)


def import_payload(db, payload, fmt="json"):
    return characters.import_character_portable(UploadFile(filename=f"sampling.{fmt}", file=io.BytesIO(encode(payload, fmt))), db)


@pytest.mark.parametrize("fmt", ["json", "txt", "docx"])
def test_export_import_all_sampling_and_source_unchanged(db, fmt):
    source = db.get(CharacterModel, 1)
    response = characters.export_character_portable(1, fmt, db)
    result = characters.import_character_portable(UploadFile(filename=f"sampling.{fmt}", file=io.BytesIO(response.body)), db)
    for field, value in VALUES.items():
        assert getattr(result, field) == value
        assert getattr(source, field) == value
    assert source.persona_prompt == result.persona_prompt == "原始正文"
    assert source.api_key == "synthetic-key" and result.api_key == ""
    assert b"synthetic-key" not in response.body


@pytest.mark.parametrize("empty", [None, "", "missing"])
def test_legacy_missing_or_null_defaults(db, empty):
    payload = {"kind": "mojing_character_portable", "version": 1, "name": "legacy"}
    if empty != "missing":
        payload.update(dict.fromkeys(DEFAULTS, empty))
    result = import_payload(db, payload)
    assert {k: getattr(result, k) for k in DEFAULTS} == DEFAULTS


@pytest.mark.parametrize("field", ["temperature", "top_p", "frequency_penalty", "presence_penalty"])
@pytest.mark.parametrize("invalid", [True, [], {}, "bad", "NaN", "Infinity", "1e100", float("nan")])
def test_invalid_sampling_rolls_back_then_retry(db, field, invalid):
    payload = build_portable_payload(db.get(CharacterModel, 1), None)
    payload[field] = invalid
    with pytest.raises(HTTPException) as exc:
        import_payload(db, payload)
    assert exc.value.status_code == 400
    assert len(list(db.scalars(select(CharacterModel)))) == 1
    assert db.scalar(select(CharacterProfileModel)) is None
    payload[field] = VALUES[field]
    result = import_payload(db, payload)
    assert getattr(result, field) == VALUES[field]
    assert len(list(db.scalars(select(CharacterModel)))) == 2


@pytest.mark.parametrize("invalid", [0, -1, 1.5, 2147483648, True, "NaN", "Infinity", {}, []])
def test_invalid_token_limit_never_writes(db, invalid):
    payload = build_portable_payload(db.get(CharacterModel, 1), None)
    payload["max_tokens"] = invalid
    with pytest.raises(HTTPException):
        import_payload(db, payload)
    assert len(list(db.scalars(select(CharacterModel)))) == 1


def test_numeric_strings_zero_negative_and_finite_values_are_not_clamped(db):
    payload = build_portable_payload(db.get(CharacterModel, 1), None)
    payload.update(dict(temperature="0", max_tokens="2147483647", top_p="0", frequency_penalty="-2", presence_penalty="2"))
    result = import_payload(db, payload)
    assert result.temperature == result.top_p == 0
    assert result.max_tokens == 2147483647 and result.frequency_penalty == -2 and result.presence_penalty == 2


def test_android_float_max_decimal_rounds_to_finite_and_imports(db):
    payload = build_portable_payload(db.get(CharacterModel, 1), None)
    payload["frequency_penalty"] = "3.4028235e38"
    result = import_payload(db, payload)
    assert result.frequency_penalty == float(payload["frequency_penalty"])


@pytest.mark.parametrize("fmt", ["json", "txt", "docx"])
def test_summary_preserves_source_sampling_over_model_output(db, monkeypatch, fmt):
    import openai
    from backend.app.services import crypto_service, system_config_service
    monkeypatch.setattr(openai, "OpenAI", lambda **kw: SimpleNamespace())
    monkeypatch.setattr(crypto_service, "decrypt_api_key", lambda value: "synthetic-key")
    monkeypatch.setattr(system_config_service, "get_local_config", lambda db: {"public_text_api_key": "synthetic-key"})
    monkeypatch.setattr(characters, "summarize_portable_with_public_llm", lambda **kw: {
        "kind": "mojing_character_portable", "version": 1, "name": "摘要", "persona_prompt": "摘要正文",
        **dict.fromkeys(VALUES, 999),
    })
    source = db.get(CharacterModel, 1)
    response = characters.export_character_portable_summary(1, fmt, db)
    result = characters.import_character_portable(UploadFile(filename=f"sampling.{fmt}", file=io.BytesIO(response.body)), db)
    assert result.persona_prompt == "摘要正文"
    assert {k: getattr(result, k) for k in VALUES} == VALUES
    assert source.persona_prompt == "原始正文"
    assert {k: getattr(source, k) for k in VALUES} == VALUES

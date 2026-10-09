from copy import deepcopy
from types import SimpleNamespace

import pytest

from backend.app.models import CharacterProfileModel
from backend.app.services.character_card_service import (
    convert_internal_to_v2,
    convert_v2_to_internal,
)
from tests.test_character_card_export import exported_role, read_with_standard_png_decoder


def full_card():
    return {
        "spec": "chara_card_v2", "spec_version": "2.0", "root_unknown": {"null": None},
        "data": {
            "name": "完整卡", "description": "描述🙂" * 4000, "personality": "性格",
            "scenario": "场景", "first_mes": "开场", "mes_example": "示例",
            "system_prompt": "系统", "post_history_instructions": "后置",
            "creator_notes": "只作元资料", "alternate_greetings": ["另一开场"],
            "tags": ["tag"], "creator": "作者", "character_version": "原版本",
            "character_book": {"name": "角色书", "entries": [{"content": "设定", "unknown": None}]},
            "extensions": {"unknown": {"keep": True}, "nullable": None}, "data_unknown": None,
        },
    }


@pytest.mark.parametrize("shape", ["nested", "direct", "legacy"])
def test_unchanged_persona_preserves_complete_structure_without_mutation(shape):
    root = full_card()
    raw = {"tavern_chara_card_v2": root, "private_local": "do-not-export"} if shape == "nested" else root if shape == "direct" else root["data"]
    before = deepcopy(raw)
    character = SimpleNamespace(name="新名字", persona_prompt=convert_v2_to_internal(root)["persona_prompt"])
    result = convert_internal_to_v2(character, SimpleNamespace(character_card_json=raw))
    expected = deepcopy(root["data"])
    expected["name"] = character.name
    assert result["data"] == expected
    assert result.get("root_unknown") == (None if shape == "legacy" else root["root_unknown"])
    assert "private_local" not in result
    assert raw == before
    assert convert_v2_to_internal(result)["persona_prompt"] == character.persona_prompt
    assert "只作元资料" not in character.persona_prompt
    result["data"]["extensions"]["unknown"]["keep"] = False
    assert raw == before


@pytest.mark.parametrize("persona", ["新的完整正文\n" * 4000, ""], ids=["long", "empty"])
def test_changed_persona_clears_stale_sections_and_keeps_metadata(persona):
    root = full_card()
    before = deepcopy(root)
    result = convert_internal_to_v2(SimpleNamespace(name="当前", persona_prompt=persona), SimpleNamespace(character_card_json={"tavern_chara_card_v2": root}))
    assert result["data"]["description"] == persona
    for key in ("personality", "scenario", "first_mes", "mes_example", "system_prompt", "post_history_instructions"):
        assert result["data"][key] == ""
    for key in ("creator_notes", "character_book", "extensions", "alternate_greetings", "data_unknown"):
        assert result["data"][key] == root["data"][key]
    assert convert_v2_to_internal(result)["persona_prompt"] == persona.strip()
    assert root == before


@pytest.mark.parametrize("raw", [None, {}, [], "bad", {"identity": {"name": "本机抽取卡"}}, {"data": None}, {"tavern_chara_card_v2": "bad"}])
def test_non_tavern_profile_falls_back_without_local_metadata(raw):
    result = convert_internal_to_v2(SimpleNamespace(name="当前", persona_prompt="正文"), SimpleNamespace(character_card_json=raw))
    assert result["data"]["description"] == "正文"
    assert convert_v2_to_internal(result)["persona_prompt"] == "正文"
    assert "identity" not in result["data"]


def test_missing_fields_default_and_explicit_metadata_nulls_preserved():
    raw = {"name": "旧卡", "description": "正文", "creator_notes": None, "character_book": None, "extensions": None, "tags": None}
    result = convert_internal_to_v2(SimpleNamespace(name="当前", persona_prompt="正文"), SimpleNamespace(character_card_json=raw))
    for key in ("creator_notes", "character_book", "extensions", "tags"):
        assert result["data"][key] is None
    assert result["data"]["first_mes"] == ""


def test_malformed_old_sections_cannot_break_current_persona_export():
    raw = {"spec": "chara_card_v2", "data": {"name": "旧", "description": ["bad"], "scenario": {"bad": True}, "creator_notes": None, "unknown": None}}
    result = convert_internal_to_v2(SimpleNamespace(name="当前", persona_prompt="当前正文"), SimpleNamespace(character_card_json=raw))
    assert convert_v2_to_internal(result)["persona_prompt"] == "当前正文"
    assert result["data"]["creator_notes"] is None and result["data"]["unknown"] is None


def test_route_reads_profile_png_reimport_and_source_rows_unchanged(exported_role):
    db, role, storage, client = exported_role
    root = full_card()
    role.name = root["data"]["name"]
    role.persona_prompt = convert_v2_to_internal(root)["persona_prompt"]
    profile = CharacterProfileModel(character_id=role.id, source_filename="source.png", raw_persona_text="raw", character_card_markdown="md", character_card_json={"tavern_chara_card_v2": root, "local_only": "private"})
    db.add(profile)
    db.commit()
    before = deepcopy(profile.character_card_json)
    role_before = {column.name: deepcopy(getattr(role, column.name)) for column in role.__table__.columns}
    profile_before = {column.name: deepcopy(getattr(profile, column.name)) for column in profile.__table__.columns}
    for _ in range(2):
        response = client.get(f"/characters/{role.id}/export-card")
        assert response.status_code == 200
        assert read_with_standard_png_decoder(response.content) == root
    imported = client.post("/characters/import-card", files={"file": ("cross.png", response.content, "image/png")})
    assert imported.status_code == 200
    assert imported.json()["persona_prompt"] == role.persona_prompt
    second = client.get(f"/characters/{imported.json()['id']}/export-card")
    card = read_with_standard_png_decoder(second.content)
    assert {k: v for k, v in card["data"].items() if k != "name"} == {k: v for k, v in root["data"].items() if k != "name"}
    db.refresh(profile)
    db.refresh(role)
    assert {column.name: getattr(role, column.name) for column in role.__table__.columns} == role_before
    assert {column.name: getattr(profile, column.name) for column in profile.__table__.columns} == profile_before
    assert profile.character_card_json == before
    assert profile.raw_persona_text == "raw" and profile.character_card_markdown == "md" and profile.source_filename == "source.png"
    assert role.api_key == "private-test-key"
    assert "private-test-key" not in response.text and "local_only" not in response.text

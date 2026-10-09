from types import SimpleNamespace

from backend.app.services.character_card_service import (
    convert_internal_to_v2,
    convert_v2_to_internal,
)


def test_web_import_does_not_append_creator_notes_to_persona():
    card = {
        "spec": "chara_card_v2",
        "spec_version": "2.0",
        "data": {
            "name": "备注角色",
            "description": "完整正文",
            "personality": "",
            "scenario": "",
            "first_mes": "",
            "mes_example": "",
            "system_prompt": "",
            "post_history_instructions": "",
            "creator_notes": "只用于导出元资料的备注",
        },
    }

    parsed = convert_v2_to_internal(card)

    assert parsed["persona_prompt"] == "完整正文"


def test_web_full_persona_export_roundtrips_without_truncation():
    persona = "长人设片段：" * 4000
    exported = convert_internal_to_v2(SimpleNamespace(name="长角色", persona_prompt=persona))

    assert exported["data"]["description"] == persona
    assert convert_v2_to_internal(exported)["persona_prompt"] == persona

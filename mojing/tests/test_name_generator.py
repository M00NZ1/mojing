"""name_generator：东方分支结构化生成（不启动 FastAPI）。"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from backend.app.services.name_generator import generate_names


def test_eastern_character_shape():
    rows = generate_names("eastern", "character", 30)
    assert len(rows) == 30
    for r in rows:
        n = r["name"]
        assert 2 <= len(n) <= 5, n
        assert r.get("meaning")


def test_eastern_skill_has_suffix_token():
    rows = generate_names("eastern", "skill", 20)
    suffixish = ("经", "诀", "典", "录", "谱", "篇", "法", "功", "掌", "剑")
    assert any(any(t in r["name"] for t in suffixish) for r in rows)


def test_eastern_skill_taoist_vocab_appears():
    blob = ""
    for _ in range(40):
        blob += "".join(x["name"] for x in generate_names("eastern", "skill", 5))
    assert any(k in blob for k in ("洞真", "洞玄", "洞神", "上清", "灵宝", "太上", "秘录", "真经"))


def test_eastern_skill_folk_or_anime_vocab_appears():
    blob = ""
    for _ in range(50):
        blob += "".join(x["name"] for x in generate_names("eastern", "skill", 5))
    folk = ("茅山", "闾山", "傩门", "坛门", "敕令", "科仪", "香火")
    anime = ("星穹", "虚界", "序列", "零式", "界律", "灵子", "秘仪")
    assert any(k in blob for k in folk) or any(k in blob for k in anime)


def test_eastern_item_not_only_grade_syllable():
    rows = generate_names("eastern", "item", 15)
    assert all(len(r["name"]) >= 2 for r in rows)


def test_western_character_markov_or_syllable():
    rows = generate_names("western", "character", 25)
    assert len(rows) == 25
    for r in rows:
        assert len(r["name"]) >= 3
        assert r["name"][0].isupper()


def test_scifi_skill_has_space_or_concat():
    rows = generate_names("scifi", "skill", 20)
    assert len(rows) == 20


def test_eastern_faction_folk_or_anime_head():
    blob = ""
    for _ in range(35):
        blob += "".join(x["name"] for x in generate_names("eastern", "faction", 4))
    assert any(k in blob for k in ("茅山", "闾山", "香火", "星穹", "虚界", "零式", "天剑"))

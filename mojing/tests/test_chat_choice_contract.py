from types import SimpleNamespace

from backend.app.services.chat_service import (
    _build_dynamic_choices_rule,
    _strip_reply_choices,
    parse_structured_reply,
)


def test_parse_structured_reply_accepts_wrapped_choices():
    result = parse_structured_reply(
        "<NARRATION>夜色降临。</NARRATION>"
        "<THOUGHT></THOUGHT><SPEECH>我们走吧。</SPEECH>"
        "<CHOICES><OPTION>前往车站</OPTION><OPTION>留在原地</OPTION></CHOICES>"
    )

    assert result["choices"] == ["前往车站", "留在原地"]
    assert "前往车站" not in result["raw"]


def test_parse_structured_reply_accepts_bare_choices_and_deduplicates():
    result = parse_structured_reply(
        "<OPTION>向东走</OPTION><option>向西走</option><OPTION>向东走</OPTION>"
    )

    assert result["choices"] == ["向东走", "向西走"]


def test_parse_structured_reply_recovers_numbered_choices_inside_wrapper():
    result = parse_structured_reply(
        "夜色笼罩了车站。\n<CHOICES>\n"
        "1. 前往站台\n2. 留在候车室\n3. 找工作人员询问\n"
        "</CHOICES>"
    )

    assert result["choices"] == ["前往站台", "留在候车室", "找工作人员询问"]
    assert result["raw"] == "夜色笼罩了车站。"
    assert result["speech"] == "夜色笼罩了车站。"


def test_parse_structured_reply_recovers_markdown_labelled_choices():
    result = parse_structured_reply(
        "她停下来等待你的回答。\n\n"
        "**后续选项：**\n"
        "**选项一：继续追问**\n"
        "**选项二：暂时离开**"
    )

    assert result["choices"] == ["继续追问", "暂时离开"]
    assert result["raw"] == "她停下来等待你的回答。"


def test_parse_structured_reply_recovers_explicit_choices_before_closing_prose():
    result = parse_structured_reply(
        "她把手放在门把上，回头看你。\n\n"
        "### 可选行动（任选其一）：\n"
        "> 1. 推门进入\n"
        "> （二）先观察四周\n\n"
        "请选择你接下来要做的事。"
    )

    assert result["choices"] == ["推门进入", "先观察四周"]
    assert result["raw"] == "她把手放在门把上，回头看你。\n\n请选择你接下来要做的事。"


def test_parse_structured_reply_accepts_one_choice_in_explicit_wrapper():
    result = parse_structured_reply("正文。\n<CHOICES>\n1. 继续\n</CHOICES>")

    assert result["choices"] == ["继续"]
    assert result["raw"] == "正文。"


def test_reply_choice_cleanup_does_not_consume_story_list_followed_by_prose():
    raw = "桌上放着：\n1. 一封信\n2. 一把钥匙\n\n她把两样东西都收进抽屉。"

    assert _strip_reply_choices(raw) == raw


def test_dynamic_choice_rule_does_not_use_template_choices():
    world = SimpleNamespace(
        choice_generation_enabled=True,
        max_choice_count=4,
        suggested_choices_json=["固定选项不应注入"],
    )

    rule = _build_dynamic_choices_rule(world)

    assert "<CHOICES>" in rule
    assert "每轮重新生成" in rule
    assert "固定选项不应注入" not in rule

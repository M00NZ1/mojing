from __future__ import annotations

import re


STORY_CANON_RULES = """【设定一致性（最高优先级）】
1. 用户写下的背景、否定描述和知情范围都是不可自行改写的事实；不得为了制造冲突擅自增加力量体系、超自然现象或隐藏组织。
2. 严格区分“作者知道”“主角知道”和“其他人物知道”。秘密只能由已经获得合理线索的人物知晓；不得让配角凭空说出、理解或利用秘密。
3. 如果设定说明只有主角拥有或知道某事，除非用户后来明确要求揭露，否则正文和后续选项都必须继续保密。
4. 后续选项只能描述从当前情节自然可达的行动或方向，不得把尚未发生的发现、关系或能力当成既成事实。"""

_SECRET_SIGNALS = ("只有", "仅主角", "别人都不知道", "其他人不知道", "无人知道", "无人知晓", "保密", "秘密")
_MUNDANE_SIGNALS = ("普通都市", "普通现代", "现实世界", "没有超自然", "没有额外的力量", "无超凡", "无异能")
_FORBIDDEN_POWERS = ("魔法", "修仙", "灵气", "异能", "超能力", "血脉觉醒", "神明", "鬼怪", "妖怪")
_PROTECTED_TOPIC = r"系统|能力|力量|身份|秘密|真相|穿越|重生"
_LEAK_PATTERNS = tuple(
    re.compile(pattern)
    for pattern in (
        rf"(告诉|告知|坦白|公开|暴露|透露).{{0,12}}({_PROTECTED_TOPIC})",
        rf"({_PROTECTED_TOPIC}).{{0,12}}(公开|暴露|被发现|被知晓|泄露)",
        rf"(众人|其他人|别人|同学|同事|朋友|家人|父母|老师|警察|女主|男主|她|他).{{0,12}}(知道|得知|发现|识破|察觉).{{0,12}}({_PROTECTED_TOPIC})",
    )
)
_CHOICES_BLOCK_PATTERN = re.compile(r"<CHOICES>.*?</CHOICES>", re.IGNORECASE | re.DOTALL)
_OPTION_PATTERN = re.compile(r"<OPTION>(.*?)</OPTION>", re.IGNORECASE | re.DOTALL)


def deepseek_story_instruction(model: str) -> str:
    if "deepseek" not in (model or "").lower():
        return ""
    return "DeepSeek 写作适配：先在内部核对设定、人物知情范围和章节连续性，再只输出最终结果；不要展示推理过程。"


def story_temperature(model: str) -> float:
    return 0.76 if "deepseek" in (model or "").lower() else 0.82


def build_story_world_prompt(premise: str, supplemental_context: str) -> str:
    parts = [f"【小说核心设定（持续生效）】\n{premise.strip()}"]
    if supplemental_context.strip():
        parts.append(f"【补充世界与人物资料】\n{supplemental_context.strip()}")
    parts.append(STORY_CANON_RULES)
    return "\n\n".join(parts)


def filter_story_choices(choices: list[str], canon_text: str) -> list[str]:
    result: list[str] = []
    for raw in choices:
        choice = str(raw).strip()
        if not choice or choice in result or _violates_explicit_canon(choice, canon_text):
            continue
        result.append(choice)
    return result


def sanitize_story_choice_tags(content: str, canon_text: str) -> str:
    block = _CHOICES_BLOCK_PATTERN.search(content or "")
    if block:
        original = _OPTION_PATTERN.findall(block.group(0))
        kept = filter_story_choices(original, canon_text)
        if len(kept) == len(original):
            return content
        replacement = ""
        if kept:
            replacement = "<CHOICES>" + "".join(f"<OPTION>{_escape_xml(item)}</OPTION>" for item in kept) + "</CHOICES>"
        return f"{content[:block.start()]}{replacement}{content[block.end():]}".strip()
    return _OPTION_PATTERN.sub(
        lambda match: match.group(0) if not _violates_explicit_canon(match.group(1), canon_text) else "",
        content or "",
    ).strip()


def _violates_explicit_canon(choice: str, canon_text: str) -> bool:
    compact_canon = canon_text.replace(" ", "")
    compact_choice = choice.replace(" ", "")
    if any(signal in compact_canon for signal in _SECRET_SIGNALS):
        if any(pattern.search(compact_choice) for pattern in _LEAK_PATTERNS):
            return True
    if any(signal in compact_canon for signal in _MUNDANE_SIGNALS):
        if any(power in choice and power not in canon_text for power in _FORBIDDEN_POWERS):
            return True
    return False


def _escape_xml(value: str) -> str:
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

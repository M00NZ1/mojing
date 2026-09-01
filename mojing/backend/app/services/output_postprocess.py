"""
输出后处理 — 正则替换脚本引擎。

用户可以通过 API 配置一系列「查找→替换」规则，
模型生成的消息在推送到前端之前会自动应用这些替换。
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from ..config import STORAGE_DIR

RULES_FILE = STORAGE_DIR / "output_rules.json"


def _load_rules() -> list[dict]:
    if RULES_FILE.exists():
        try:
            return json.loads(RULES_FILE.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            return []
    return []


def _save_rules(rules: list[dict]):
    RULES_FILE.parent.mkdir(parents=True, exist_ok=True)
    RULES_FILE.write_text(json.dumps(rules, ensure_ascii=False, indent=2), encoding="utf-8")


def _normalize_replacement(replacement: str) -> str:
    """兼容前端常见的 $1 写法，转换成 Python re.sub 可识别的分组语法。"""

    return re.sub(r"\$(\d+)", r"\\g<\1>", replacement)


def get_rules() -> list[dict]:
    """获取所有替换规则。"""
    return _load_rules()


def save_rule(rule: dict) -> dict:
    """保存或更新一条规则。"""
    rules = _load_rules()
    rule_id = rule.get("id", "")
    if not rule_id:
        rule_id = f"rule_{len(rules) + 1}_{hash(rule['pattern']) % 10000}"
        rule["id"] = rule_id
    existing = [i for i, r in enumerate(rules) if r.get("id") == rule_id]
    if existing:
        rules[existing[0]] = rule
    else:
        rules.append(rule)
    _save_rules(rules)
    return rule


def delete_rule(rule_id: str) -> bool:
    """删除一条规则。"""
    rules = _load_rules()
    new_rules = [r for r in rules if r.get("id") != rule_id]
    if len(new_rules) == len(rules):
        return False
    _save_rules(new_rules)
    return True


def apply_rules(text: str) -> str:
    """对文本应用所有启用的替换规则。"""
    rules = _load_rules()
    for rule in rules:
        if not rule.get("enabled", True):
            continue
        pattern = rule.get("pattern", "")
        replacement = _normalize_replacement(rule.get("replacement", ""))
        flags = 0
        if rule.get("ignore_case"):
            flags |= re.IGNORECASE
        if rule.get("multiline"):
            flags |= re.MULTILINE
        try:
            # [⚠ 避坑] 规则配置来自用户输入，前端常会写成 $1 / $2；
            # 如果这里不兼容转换，生产环境会把分组替换结果直接污染成字面量。
            text = re.sub(pattern, replacement, text, flags=flags)
        except re.error:
            continue
    return text

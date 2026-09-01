from __future__ import annotations

import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
CASES_PATH = PROJECT_ROOT / "data" / "prompt_eval" / "prompt_eval_cases.json"
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from backend.app.services.defaults import BUILTIN_PROMPT_TEMPLATES


def load_cases() -> list[dict]:
    return json.loads(CASES_PATH.read_text(encoding="utf-8"))


def evaluate_case(template_map: dict[str, dict], case: dict) -> dict:
    template_id = case["template_id"]
    template = template_map.get(template_id)
    if template is None:
        return {
            "case_id": case["case_id"],
            "template_id": template_id,
            "passed": False,
            "issues": [f"模板不存在：{template_id}"],
        }

    issues: list[str] = []
    system_prompt = str(template.get("system_prompt") or "")
    user_prompt = str(template.get("user_prompt") or "")
    variables = [str(item) for item in (template.get("variables_json") or [])]
    output_format = str(template.get("output_format") or "")

    for token in case.get("required_in_system", []):
        if token not in system_prompt:
            issues.append(f"system_prompt 缺少关键词：{token}")
    for token in case.get("required_in_user", []):
        if token not in user_prompt:
            issues.append(f"user_prompt 缺少关键词：{token}")
    for token in case.get("required_variables", []):
        if token not in variables:
            issues.append(f"变量清单缺少：{token}")
    for token in case.get("expected_output_format_contains", []):
        if token not in output_format:
            issues.append(f"输出格式缺少：{token}")

    return {
        "case_id": case["case_id"],
        "template_id": template_id,
        "passed": len(issues) == 0,
        "issues": issues,
    }


def main() -> int:
    template_map = {item["template_id"]: item for item in BUILTIN_PROMPT_TEMPLATES}
    cases = load_cases()
    results = [evaluate_case(template_map, case) for case in cases]
    passed = sum(1 for item in results if item["passed"])
    total = len(results)

    print("提示词静态回归结果")
    print(f"通过：{passed}/{total}")
    for result in results:
        print(f"- {result['case_id']} / {result['template_id']} / {'通过' if result['passed'] else '失败'}")
        for issue in result["issues"]:
            print(f"  - {issue}")

    return 0 if passed == total else 1


if __name__ == "__main__":
    raise SystemExit(main())

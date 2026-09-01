import json
import os
from pathlib import Path

from openai import OpenAI

# 仓库根目录下的 mojing_config.json（与 images 路由默认生图配置一致；维护脚本用）
from ..config import resolve_project_config_path

config_path = resolve_project_config_path()
with open(config_path, "r", encoding="utf-8") as f:
    config = json.load(f)

api_key = config.get("api_key_deepseek") or config.get("api_key")
base_url = config.get("base_url_deepseek") or config.get("base_url")
model = config.get("model_deepseek") or config.get("model")

client = OpenAI(api_key=api_key, base_url=base_url)

TARGET_SCHEMAS = {
    "world": {"label": "世界观总览", "fields": ["alias: tags", "core_rules: rich", "world_laws: textarea", "era_background: textarea", "civilization_stage: text", "tech_magic_level: textarea", "world_map_structure: textarea", "cosmology: textarea", "timeline_summary: rich"]},
    "character": {"label": "人物", "fields": ["alias: tags", "character_type: select", "race: text", "bloodline: text", "family: text", "gender: text", "age: text", "faction: text", "status_record: textarea", "appearance: textarea", "personality: textarea", "background: rich", "growth_path: rich", "abilities: tags", "equipment: tags", "relationships: objects(target,relation)"]},
    "location": {"label": "地点", "fields": ["alias: tags", "location_type: select", "region: text", "controller: text", "status: text", "population: text", "landmarks: tags", "resources: tags", "travel_routes: objects", "hazards: tags", "local_rules: textarea"]},
    "faction": {"label": "势力", "fields": ["alias: tags", "faction_type: select", "founder: text", "founded_year: text", "leader: text", "headquarters: text", "status: text", "doctrine: textarea", "history: rich", "war_records: textarea", "hierarchy: objects", "departments: objects", "members: objects", "allies: tags", "enemies: tags"]},
    "event": {"label": "事件", "fields": ["event_type: select", "time_label: text", "location: text", "participants: tags", "causes: rich", "process: rich", "result: rich", "impact: textarea", "world_status_changes: textarea"]},
    "item": {"label": "物品", "fields": ["alias: tags", "item_type: select", "rarity: text", "creator: text", "owner_records: textarea", "source_location: text", "appearance: textarea", "effects: rich", "limitations: textarea", "cost: text", "history: rich"]},
    "skill": {"label": "技能/法术", "fields": ["skill_type: select", "energy_system: text", "prerequisites: tags", "cost_rules: textarea", "counter_relations: textarea", "effects: rich", "side_effects: textarea", "stages: objects"]},
    "profession": {"label": "职业/等级", "fields": ["system_type: select", "promotion_conditions: textarea", "growth_path: rich", "class_transfer_path: rich", "limitations: textarea", "skill_pool: tags", "equipment_limitations: tags"]},
    "concept": {"label": "概念术语", "fields": ["alias: tags", "concept_type: select", "definition: rich", "scope: textarea", "mechanism: rich", "limits: textarea", "examples: tags", "counterexamples: tags"]},
    "timeline": {"label": "时间线", "fields": ["calendar: text", "time_label: text", "time_order: number", "branch: text", "events: objects"]}
}

results = {}
for type_key, schema_def in TARGET_SCHEMAS.items():
    print(f"Generating {type_key}...")
    prompt = f"""
你是一位世界观架构大师。请为一个名为“星渊序列”的架空世界（融合了赛博修仙与星际奇幻）生成一个设定极为详尽的【{schema_def['label']}】百科模板样例。
要求：
1. 细节非常到位，不要敷衍。所有的 rich 和 textarea 描述字段写至少 150 字。
2. 以严格的 JSON 格式输出，不要包含 markdown 标志。
3. JSON 格式如下：
{{
  "title": "条目名称",
  "summary": "一句话简介",
  "content": "详细的正文描述...",
  "meta_json": {{
    // 这里包含具体字段，必须符合下面列出的所需字段类型
  }}
}}
需要填充的 meta_json 字段列表及类型：
{json.dumps(schema_def['fields'], ensure_ascii=False)}
"""
    try:
        response = client.chat.completions.create(
            model=model,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.8,
            max_tokens=4000
        )
        content = response.choices[0].message.content.strip()
        if content.startswith("```json"): content = content[7:]
        if content.startswith("```"): content = content[3:]
        if content.endswith("```"): content = content[:-3]
        results[type_key] = json.loads(content.strip())
        print(f"Success for {type_key}")
    except Exception as e:
        print(f"Failed for {type_key}: {e}")

output_path = Path(__file__).with_name("generated_templates.json")
with output_path.open("w", encoding="utf-8") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
print("Done!")

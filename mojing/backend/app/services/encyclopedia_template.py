from __future__ import annotations

from copy import deepcopy


ENTRY_TYPES: list[dict] = [
    {"id": "world", "label": "世界基础", "icon": "W"},
    {"id": "character", "label": "人物体系", "icon": "C"},
    {"id": "location", "label": "地点地图", "icon": "L"},
    {"id": "faction", "label": "势力组织", "icon": "F"},
    {"id": "event", "label": "事件时间线", "icon": "E"},
    {"id": "timeline", "label": "时间线节点", "icon": "T"},
    {"id": "item", "label": "物品装备", "icon": "I"},
    {"id": "skill", "label": "能力体系", "icon": "S"},
    {"id": "profession", "label": "职业成长", "icon": "P"},
    {"id": "rule", "label": "规则系统", "icon": "R"},
    {"id": "concept", "label": "概念术语", "icon": "N"},
    {"id": "culture", "label": "文化社会", "icon": "U"},
    {"id": "economy", "label": "经济资源", "icon": "M"},
    {"id": "ecology", "label": "生态生物", "icon": "B"},
    {"id": "quest", "label": "剧情任务", "icon": "Q"},
    {"id": "source", "label": "资料来源", "icon": "O"},
    {"id": "ai_tool", "label": "AI辅助模块", "icon": "A"},
    {"id": "admin", "label": "数据治理（导入/权限/版本）", "icon": "D"},
    {"id": "frontend_view", "label": "前端展示", "icon": "V"},
    {"id": "field_template", "label": "字段模板", "icon": "X"},
    {"id": "relationship", "label": "关系系统", "icon": "G"},
    {"id": "first_release", "label": "推荐首版必须上线模块", "icon": "Y"},
    {"id": "species", "label": "种族生物", "icon": "B"},
    {"id": "other", "label": "其他", "icon": "Z"},
]


RELATION_TYPES: list[str] = [
    "属于",
    "位于",
    "控制",
    "敌对",
    "联盟",
    "使用",
    "持有",
    "创造",
    "参与",
    "影响",
    "导致",
    "继承",
    "师徒",
    "血缘",
    "雇佣",
    "信仰",
    "统治",
    "交易",
    "诅咒",
    "封印",
    "弱点",
    "前置条件",
    "替代称呼",
    "资料来源",
    "关联",
]


COMMON_SOURCE_META = {
    "source_kind": "",
    "source_url": "",
    "source_page_title": "",
    "source_retrieved_at": "",
    "source_license": "",
    "source_trust_level": "unverified",
    "verification_status": "pending",
    "canon_scope": "",
    "canon_conflicts": [],
    "unknown_fields": [],
    "import_warnings": [],
    "custom_fields": [],
    "ai_completion_status": "manual_optional",
    "ai_completion_policy": "用户手填字段全部视为最高优先级事实；AI 只能补空字段，不能改写用户原文。",
    "preserve_user_input": True,
}


UNIVERSAL_ENTRY_SCHEMAS: dict[str, dict] = {
    "world": {
        "required": ["alias", "genre", "canon_scope", "timeline_model", "geography_model", "power_system", "core_conflict", "hard_rules", "unknown_fields", "source_url", "custom_fields"],
        "anti_hallucination_rule": "世界基础只记录已确认事实、硬规则和未知项；AI 不得把未来源化补全当成官方设定。",
    },
    "character": {
        "required": ["alias", "race", "gender", "age", "faction", "title", "status", "origin", "personality", "appearance", "background", "abilities", "equipment", "relationships", "growth_line", "state_records", "source_url", "custom_fields"],
        "anti_hallucination_rule": "人物条目必须区分官方履历、玩家输入、AI 补全和传闻；状态变化只能由剧情事件或用户编辑触发。",
    },
    "location": {
        "required": ["alias", "location_type", "region", "controller", "status", "population", "landmarks", "resources", "travel_routes", "hazards", "map_position", "local_rules", "related_events", "source_url", "custom_fields"],
        "anti_hallucination_rule": "地点条目不得凭空补地图距离、人口和控制权；来源没有写明时只写未知或待确认。",
    },
    "faction": {
        "required": ["alias", "type", "founder", "founded_year", "leader", "headquarters", "status", "doctrine", "history", "hierarchy", "departments", "members", "allies", "enemies", "war_records", "source_url", "custom_fields"],
        "anti_hallucination_rule": "势力条目必须记录组织边界、权力层级和敌友关系来源，不因玩家偏好倒推设定。",
    },
    "event": {
        "required": ["era", "time_label", "location", "participants", "causes", "process", "result", "impact", "canon_status", "world_state_changes", "related_entries", "source_url", "custom_fields"],
        "anti_hallucination_rule": "事件条目必须保留因果链；时间顺序不确定时不能强行排序。",
    },
    "timeline": {
        "required": ["calendar", "time_label", "time_order", "branch", "summary", "events", "uncertain_dates", "source_url", "custom_fields"],
        "anti_hallucination_rule": "时间线只做索引，不把互相冲突的版本合并成单一事实。",
    },
    "item": {
        "required": ["alias", "item_type", "rarity", "creator", "owner", "location", "appearance", "effects", "limitations", "cost", "history", "source_path", "holder_records", "source_url", "custom_fields"],
        "anti_hallucination_rule": "物品能力必须写限制、代价和已知持有者；不能因为名称强就默认无上限。",
    },
    "skill": {
        "required": ["skill_type", "level", "founder", "origin", "prerequisites", "effects", "side_effects", "stages", "users", "cost", "range", "counter_rules", "source_url", "custom_fields"],
        "anti_hallucination_rule": "能力条目必须写学习条件、消耗、副作用和克制关系，避免无代价万能能力。",
    },
    "profession": {
        "required": ["role_type", "rank_system", "requirements", "duties", "ranks", "skills", "equipment", "legal_status", "organizations", "promotion_path", "transfer_path", "restrictions", "source_url", "custom_fields"],
        "anti_hallucination_rule": "职业和身份必须绑定制度来源；称号不等于实际权限。",
    },
    "rule": {
        "required": ["rule_type", "definition", "scope", "mechanism", "inputs", "outputs", "limits", "exceptions", "examples", "counterexamples", "source_url", "custom_fields"],
        "anti_hallucination_rule": "规则必须写适用范围、例外和反例；局部规则不能泛化成世界通用规则。",
    },
    "concept": {
        "required": ["alias", "concept_type", "definition", "scope", "mechanism", "limits", "examples", "counterexamples", "related_rules", "source_url", "custom_fields"],
        "anti_hallucination_rule": "概念条目必须写定义和边界，术语不得用相似作品常识补完。",
    },
    "culture": {
        "required": ["region", "groups", "language", "writing", "religion", "myths", "festivals", "customs", "taboos", "class_system", "law", "education", "daily_life", "source_url", "custom_fields"],
        "anti_hallucination_rule": "文化条目用于约束日常细节；没有来源时不能硬编货币、礼节、服饰和仪式。",
    },
    "economy": {
        "required": ["currency_system", "trade_routes", "goods", "taxes", "resources", "rare_materials", "industries", "black_market", "auction_house", "guilds", "ownership", "conflicts", "source_url", "custom_fields"],
        "anti_hallucination_rule": "经济条目必须记录资源来源、归属和交易限制；不能无限刷钱或凭空出现稀有材料。",
    },
    "ecology": {
        "required": ["species_type", "habitat", "ecological_zone", "food_chain", "abilities", "weaknesses", "taming_rules", "drops", "materials", "risk_level", "source_url", "custom_fields"],
        "anti_hallucination_rule": "生态条目必须区分普通生物、魔物、神话生物、植物和药材；掉落物不能脱离生态逻辑。",
    },
    "quest": {
        "required": ["quest_type", "giver", "conditions", "objectives", "steps", "rewards", "consequences", "branches", "endings", "related_entries", "source_url", "custom_fields"],
        "anti_hallucination_rule": "任务条目必须写条件、后果和分支；AI 不得绕过前置条件直接发放奖励。",
    },
    "source": {
        "required": ["source_kind", "source_url", "source_page_title", "source_retrieved_at", "source_license", "source_trust_level", "verification_status", "canon_conflicts", "custom_fields"],
        "anti_hallucination_rule": "来源条目只记录证据链和冲突口径，不扩写剧情。",
    },
    "ai_tool": {
        "required": ["tool_type", "input_scope", "output_scope", "guardrails", "preserve_user_input", "review_policy", "conflict_check_policy", "batch_policy", "source_url", "custom_fields"],
        "anti_hallucination_rule": "AI 辅助条目只定义自动化边界；AI 补全必须标记待确认，不能覆盖用户输入。",
    },
    "admin": {
        "required": ["module", "permissions", "audit_log", "versioning", "backup_policy", "draft_policy", "review_policy", "import_export_policy", "source_url", "custom_fields"],
        "anti_hallucination_rule": "数据治理类条目定义导入、权限与审核规则，不能让未审核内容直接污染运行时事实源。",
    },
    "frontend_view": {
        "required": ["view_name", "entry_points", "filters", "search_fields", "relation_view", "timeline_view", "map_view", "mobile_policy", "source_url", "custom_fields"],
        "anti_hallucination_rule": "前端展示条目只描述信息展示与筛选规则，不承载世界事实。",
    },
    "field_template": {
        "required": ["field_name", "field_type", "default_value", "editable", "ai_fillable", "validation_rule", "version", "visibility", "related_entries", "source_url", "custom_fields"],
        "anti_hallucination_rule": "字段模板必须保证手填可选、AI 只补空、不改用户原文。",
    },
    "relationship": {
        "required": ["relation_type", "from_entry_type", "to_entry_type", "direction", "evidence", "status", "start_event", "end_event", "source_url", "custom_fields"],
        "anti_hallucination_rule": "关系条目必须有方向、证据和状态；不能凭语义相近自动建立强关系。",
    },
    "first_release": {
        "required": ["required_modules", "launch_scope", "acceptance_criteria", "search_policy", "tag_policy", "ai_policy", "source_url", "custom_fields"],
        "anti_hallucination_rule": "首版模块是产品完整性门槛；缺失入口必须显式报错，不能用空泛总设定冒充完整世界观。",
    },
    "species": {
        "required": ["alias", "origin", "habitat", "lifespan", "appearance", "abilities", "culture", "factions", "weaknesses", "relations", "status", "source_url", "custom_fields"],
        "anti_hallucination_rule": "种族条目必须区分生理特征、文化习俗和政治阵营，不能混写。",
    },
    "other": {
        "required": ["definition", "scope", "related_entries", "source_url", "custom_fields"],
        "anti_hallucination_rule": "其他条目必须尽快归类；未归类内容不能作为高可信事实。",
    },
}


UNIVERSAL_TEMPLATE_MODULES: list[dict] = [
    {"module_id": "01_world_base", "title": "一、世界基础", "entry_type": "world", "summary": "世界观总览、基础规则、世界法则、时代背景、历史年表、时间线、文明阶段、科技/魔法发展水平、世界地图结构、维度/位面/宇宙结构。", "submodules": ["世界观总览", "基础规则", "世界法则", "时代背景", "历史年表", "时间线", "文明阶段", "科技/魔法发展水平", "世界地图结构", "维度/位面/宇宙结构"]},
    {"module_id": "02_character_system", "title": "二、人物体系", "entry_type": "character", "summary": "人物/NPC、主角、配角、反派、历史人物、高阶存在、种族、血脉、家族、关系网、成长线和状态记录。", "submodules": ["人物 / NPC", "主角", "配角", "反派", "重要历史人物", "神明 / 高阶存在", "种族", "血脉", "家族", "关系网", "人物成长线", "人物状态记录"]},
    {"module_id": "03_location_map", "title": "三、地点地图", "entry_type": "location", "summary": "世界地图、国家、城市、村镇、区域、建筑、遗迹、副本、禁地、秘境、星球、星系、位面和交通节点。", "submodules": ["世界地图", "国家", "城市", "村镇", "区域", "建筑", "遗迹", "副本/地下城", "禁地", "秘境", "星球", "星系", "位面", "传送点/交通节点"]},
    {"module_id": "04_faction_org", "title": "四、势力组织", "entry_type": "faction", "summary": "国家、帝国、宗门、教会、公会、公司、军队、学院、家族、黑帮、叛军、秘密组织及战争记录。", "submodules": ["国家", "王国/帝国", "宗门", "教会", "公会", "公司", "军队", "学院", "家族", "黑帮", "叛军", "秘密组织", "势力关系", "势力战争记录"]},
    {"module_id": "05_event_timeline", "title": "五、事件时间线", "entry_type": "event", "summary": "大事件、历史事件、战争、灾难、政变、探索、失踪、神话事件、主支线事件和世界状态变更。", "submodules": ["大事件", "历史事件", "战争", "灾难", "政变", "探索事件", "失踪事件", "神话事件", "主线剧情事件", "支线事件", "世界状态变更记录"]},
    {"module_id": "06_item_equipment", "title": "六、物品装备", "entry_type": "item", "summary": "普通物品、武器、防具、饰品、消耗品、材料、货币、遗物、神器、禁忌物、载具、来源和持有者记录。", "submodules": ["普通物品", "武器", "防具", "饰品", "消耗品", "材料", "货币", "遗物", "神器", "禁忌物", "载具", "道具来源", "持有者记录"]},
    {"module_id": "07_ability_system", "title": "七、能力体系", "entry_type": "skill", "summary": "技能、法术、天赋、被动、职业能力、血脉能力、神术、禁术、科技能力、能量体系、技能树、学习条件、消耗和克制。", "submodules": ["技能", "法术", "天赋", "被动能力", "职业能力", "血脉能力", "神术", "禁术", "科技能力", "能量体系", "技能树", "学习条件", "消耗规则", "克制关系"]},
    {"module_id": "08_profession_growth", "title": "八、职业成长", "entry_type": "profession", "summary": "职业、职阶、等级、境界、晋升、成长、转职、职业限制、技能池和装备限制。", "submodules": ["职业", "职阶", "等级体系", "境界体系", "晋升条件", "成长路线", "转职路线", "职业限制", "职业技能池", "职业装备限制"]},
    {"module_id": "09_rule_system", "title": "九、规则系统", "entry_type": "rule", "summary": "战斗、属性、伤害、判定、探索、经济、贸易、政治、信仰、魔法、科技、死亡复活、时间空间、因果命运规则。", "submodules": ["战斗规则", "属性规则", "伤害规则", "判定规则", "探索规则", "经济规则", "贸易规则", "政治规则", "信仰规则", "魔法规则", "科技规则", "死亡/复活规则", "时间/空间规则", "因果/命运规则"]},
    {"module_id": "10_terms", "title": "十、概念术语", "entry_type": "concept", "summary": "专有名词、世界概念、魔法、科技、宗教、政治、地理、历史、文化、禁忌和黑话术语。", "submodules": ["专有名词", "世界概念", "魔法概念", "科技概念", "宗教概念", "政治概念", "地理概念", "历史概念", "文化概念", "禁忌概念", "黑话/术语表"]},
    {"module_id": "11_culture_society", "title": "十一、文化社会", "entry_type": "culture", "summary": "语言、文字、宗教、神话、节日、风俗、阶级、法律、教育、婚姻、葬礼、饮食、服饰、艺术和娱乐。", "submodules": ["语言", "文字", "宗教", "神话", "节日", "风俗", "阶级", "法律", "教育", "婚姻", "葬礼", "饮食", "服饰", "艺术", "娱乐"]},
    {"module_id": "12_economy_resource", "title": "十二、经济资源", "entry_type": "economy", "summary": "货币、贸易、商品、税收、矿产、稀有材料、产业、黑市、拍卖行、商会、资源归属和经济冲突。", "submodules": ["货币体系", "贸易路线", "商品", "税收", "资源矿产", "稀有材料", "产业", "黑市", "拍卖行", "商会", "资源归属", "经济冲突"]},
    {"module_id": "13_ecology_biology", "title": "十三、生态生物", "entry_type": "ecology", "summary": "普通生物、魔物、怪物、神话生物、异形生物、植物、药材、生态区域、食物链、弱点、驯养和掉落物。", "submodules": ["普通生物", "魔物", "怪物", "神话生物", "异形生物", "植物", "药材", "生态区域", "食物链", "生物弱点", "驯养规则", "掉落物"]},
    {"module_id": "14_plot_quest", "title": "十四、剧情任务", "entry_type": "quest", "summary": "主线、支线、角色、阵营、探索、悬赏、隐藏任务、奖励、条件、后果、分支和多结局。", "submodules": ["主线剧情", "支线剧情", "角色任务", "阵营任务", "探索任务", "悬赏任务", "隐藏任务", "任务奖励", "任务条件", "任务后果", "分支剧情", "多结局"]},
    {"module_id": "15_ai_assist", "title": "十五、AI辅助模块", "entry_type": "ai_tool", "summary": "AI 生成条目、补全字段、冲突检查、关系图、时间线、背景、地点、势力、剧情钩子、任务链、漏洞检查、总结、改写和批量生成。", "submodules": ["AI生成条目", "AI补全字段", "AI检查设定冲突", "AI生成关系图", "AI生成时间线", "AI生成角色背景", "AI生成地点描述", "AI生成势力关系", "AI生成剧情钩子", "AI生成任务链", "AI检查世界观漏洞", "AI总结条目", "AI改写风格", "AI批量生成"]},
    {"module_id": "16_admin", "title": "十六、数据治理与导入导出", "entry_type": "admin", "summary": "数据集、条目、分类、字段模板、关系模板、标签、权限、导入导出、版本、草稿、审核、回收站、日志和备份。", "submodules": ["数据集管理", "条目管理", "分类管理", "字段模板管理", "关系模板管理", "标签管理", "权限管理", "导入导出", "版本管理", "草稿管理", "审核管理", "回收站", "操作日志", "数据备份"]},
    {"module_id": "17_frontend", "title": "十七、前端展示", "entry_type": "frontend_view", "summary": "首页总览、分类、列表、详情、搜索、高级筛选、标签筛选、关系图谱、时间线、地图、收藏、最近浏览、推荐和移动端适配。", "submodules": ["首页总览", "分类入口", "条目列表", "条目详情", "搜索页", "高级筛选", "标签筛选", "关系图谱", "时间线视图", "地图视图", "收藏夹", "最近浏览", "相关推荐", "移动端适配"]},
    {"module_id": "18_common_fields", "title": "十八、条目通用字段", "entry_type": "field_template", "summary": "名称、原名、别名、类型、简介、详细描述、标签、封面图、图集、所属世界、分类、状态、创建者、更新时间、版本、备注、可见性和关联条目。", "submodules": ["名称", "原名", "别名", "类型", "简介", "详细描述", "标签", "封面图", "图集", "所属世界", "所属分类", "状态", "创建者", "更新时间", "版本号", "备注", "可见性", "关联条目"]},
    {"module_id": "19_relationship", "title": "十九、关系系统", "entry_type": "relationship", "summary": "属于、位于、控制、敌对、联盟、使用、持有、创造、参与、影响、导致、继承、师徒、血缘、雇佣、信仰、统治、交易、诅咒和封印。", "submodules": ["属于", "位于", "控制", "敌对", "联盟", "使用", "持有", "创造", "参与", "影响", "导致", "继承", "师徒", "血缘", "雇佣", "信仰", "统治", "交易", "诅咒", "封印"]},
    {"module_id": "20_first_release", "title": "二十、推荐首版必须上线模块", "entry_type": "first_release", "summary": "首版必须上线世界观总览、人物、地点、势力、事件、物品、技能/法术、职业/等级、概念术语、时间线、关系图谱、AI生成、字段模板、标签筛选和全局搜索。", "submodules": ["世界观总览", "人物", "地点", "势力", "事件", "物品", "技能/法术", "职业/等级", "概念术语", "时间线", "关系图谱", "AI生成", "字段模板", "标签筛选", "全局搜索"]},
]


FIRST_RELEASE_MODULES: list[str] = [
    "世界观总览",
    "人物",
    "地点",
    "势力",
    "事件",
    "物品",
    "技能/法术",
    "职业/等级",
    "概念术语",
    "时间线",
    "关系图谱",
    "AI生成",
    "字段模板",
    "标签筛选",
    "全局搜索",
]


FIRST_RELEASE_LORE_BLUEPRINTS: list[dict] = [
    {"title": "世界观总览", "entry_type": "世界观总览", "keywords": ["世界观", "总览", "核心规则"]},
    {"title": "人物", "entry_type": "人物", "keywords": ["人物", "NPC", "角色"]},
    {"title": "地点", "entry_type": "地点", "keywords": ["地点", "地图", "区域"]},
    {"title": "势力", "entry_type": "势力", "keywords": ["势力", "组织", "阵营"]},
    {"title": "事件", "entry_type": "事件", "keywords": ["事件", "历史", "冲突"]},
    {"title": "物品", "entry_type": "物品", "keywords": ["物品", "装备", "资源"]},
    {"title": "技能/法术", "entry_type": "技能/法术", "keywords": ["技能", "法术", "能力"]},
    {"title": "职业/等级", "entry_type": "职业/等级", "keywords": ["职业", "等级", "成长"]},
    {"title": "概念术语", "entry_type": "概念术语", "keywords": ["概念", "术语", "名词"]},
    {"title": "时间线", "entry_type": "时间线", "keywords": ["时间线", "年表", "纪年"]},
    {"title": "关系图谱", "entry_type": "关系图谱", "keywords": ["关系", "图谱", "关联"]},
    {"title": "AI生成", "entry_type": "AI生成", "keywords": ["AI生成", "补全", "待确认"]},
    {"title": "字段模板", "entry_type": "字段模板", "keywords": ["字段", "模板", "自定义"]},
    {"title": "标签筛选", "entry_type": "标签筛选", "keywords": ["标签", "筛选", "检索"]},
    {"title": "全局搜索", "entry_type": "全局搜索", "keywords": ["搜索", "索引", "召回"]},
]


UNIVERSAL_TEMPLATE_ENCYCLOPEDIA = {
    "name": "通用世界观百科模板",
    "description": "公用 wiki 级世界观资料库模板。用于约束 AI 依据玩家手填、来源证据、条目关系、时间线和规则推进剧情，缺失字段只可补全为待确认内容。",
    "is_official": 1,
}


def _meta(entry_type: str, module: dict | None = None, **extra) -> dict:
    base = deepcopy(COMMON_SOURCE_META)
    schema = UNIVERSAL_ENTRY_SCHEMAS.get(entry_type, {})
    module = module or {}
    base.update(
        {
            "schema_version": 2,
            "module_id": module.get("module_id", ""),
            "module_title": module.get("title", ""),
            "submodules": list(module.get("submodules", [])),
            "template_required_fields": list(schema.get("required", [])),
            "anti_hallucination_rule": schema.get("anti_hallucination_rule", ""),
            "manual_fields_optional": True,
            "user_editable_fields": ["title", "summary", "content", "tags", "meta_json", "custom_fields"],
            "source_trust_level": "template",
            "verification_status": "template",
        }
    )
    base.update(extra)
    return base


def _build_template_entry(module: dict, index: int) -> dict:
    entry_type = module["entry_type"]
    submodule_lines = "\n".join(f"- {item}" for item in module["submodules"])
    is_first_release = module["title"].startswith("二十") or any(item in FIRST_RELEASE_MODULES for item in module["submodules"])
    return {
        "title": module["title"],
        "entry_type": entry_type,
        "summary": module["summary"],
        "content": (
            f"我用本模块维护「{module['title']}」相关资料。\n\n"
            f"子模块：\n{submodule_lines}\n\n"
            "填写规则：用户手动输入的字段全部保留；字段可以为空；AI 只能补齐空字段，并必须把补全内容标记为 AI补全/待确认。"
        ),
        "tags": f"模板,{module['title'].split('、', 1)[-1]},{entry_type},防编造",
        "is_featured": 1 if is_first_release else 0,
        "sort_order": index,
        "meta_json": _meta(entry_type, module, recommended_first_release=is_first_release),
    }


UNIVERSAL_TEMPLATE_ENTRIES: list[dict] = [
    _build_template_entry(module, index) for index, module in enumerate(UNIVERSAL_TEMPLATE_MODULES)
]


def build_universal_template_entries() -> list[dict]:
    return deepcopy(UNIVERSAL_TEMPLATE_ENTRIES)


def build_public_template_prompt() -> str:
    module_names = "、".join(item["title"] for item in UNIVERSAL_TEMPLATE_MODULES)
    first_release = "、".join(FIRST_RELEASE_MODULES)
    return (
        "必须按公用世界观数据库模板组织信息。\n"
        f"一级模块：{module_names}。\n"
        f"首版至少覆盖：{first_release}。\n"
        "用户手动输入的名称、设定、描述、标签和字段是最高优先级事实，禁止改写、覆盖或偷偷纠正。\n"
        "缺失字段可以由 AI 补全，但必须标注为 AI补全/待确认；没有来源的信息不能冒充官方设定。"
    )

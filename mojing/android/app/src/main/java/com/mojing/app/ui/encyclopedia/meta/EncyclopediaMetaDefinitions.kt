package com.mojing.app.ui.encyclopedia.meta

/**
 * 百科各 `entry_type` 的 meta_json 结构化字段定义；含统一「来源」区。
 * 未列出的键可通过「扩展字段」区或「原始 Meta JSON」编辑。
 */
enum class MetaFieldKind { Text, TextArea, Number, Select, Tags }

data class MetaField(
    val key: String,
    val label: String,
    val kind: MetaFieldKind,
    val options: List<String> = emptyList(),
    /** Select：存库仍为英文码，界面展示中文 */
    val optionLabels: Map<String, String> = emptyMap(),
)

object EncyclopediaMetaDefinitions {

    private val SOURCE_TRUST_LABELS = mapOf(
        "official" to "官方",
        "wiki" to "维基 / 百科",
        "community" to "社区整理",
        "manual" to "手工录入",
        "unverified" to "未核实",
        "template" to "模板占位",
    )
    private val VERIFICATION_STATUS_LABELS = mapOf(
        "verified" to "已核实",
        "fetched" to "已抓取",
        "pending" to "待核对",
        "manual_unverified" to "手工未核实",
        "template" to "模板占位",
    )

    private val SRC = listOf(
        MetaField("source_url", "来源网址", MetaFieldKind.Text),
        MetaField("source_page_title", "来源页面标题", MetaFieldKind.Text),
        MetaField("source_retrieved_at", "获取时间", MetaFieldKind.Text),
        MetaField(
            "source_trust_level",
            "可信度",
            MetaFieldKind.Select,
            listOf("official", "wiki", "community", "manual", "unverified", "template"),
            SOURCE_TRUST_LABELS,
        ),
        MetaField(
            "verification_status",
            "核验状态",
            MetaFieldKind.Select,
            listOf("verified", "fetched", "pending", "manual_unverified", "template"),
            VERIFICATION_STATUS_LABELS,
        ),
        MetaField("canon_scope", "设定写到哪", MetaFieldKind.Text),
        MetaField("canon_conflicts", "冲突版本", MetaFieldKind.Tags),
        MetaField("unknown_fields", "其他字段", MetaFieldKind.Tags),
    )

    fun fieldsFor(entryType: String): List<MetaField> =
        (CORE[entryType] ?: CORE["concept"]!!) + SRC

    /**
     * 供 [com.mojing.app.domain.engine.AiCompleter] `encyclopedia_entry_meta` 使用：
     * 扁平字段名 → 中文说明（含类型提示），与编辑页扩展区一致。
     */
    fun aiFieldMapForCompleter(entryType: String): Map<String, String> {
        val et = if (CORE.containsKey(entryType)) entryType else "concept"
        return fieldsFor(et).associate { f ->
            val hint = when (f.kind) {
                MetaFieldKind.Tags -> "JSON 字符串数组，如 [\"a\",\"b\"]"
                MetaFieldKind.Number -> "数字"
                MetaFieldKind.Select ->
                    "只能从下列之一取值：" + f.options.joinToString("、")
                MetaFieldKind.TextArea -> "可多段的文字"
                MetaFieldKind.Text -> "单行或短文本"
            }
            f.key to "${f.label}（$hint）"
        }
    }

    private val CORE = mapOf(
        "world" to listOf(
            MetaField("alias", "别称", MetaFieldKind.Tags),
            MetaField("core_rules", "基础规则", MetaFieldKind.TextArea),
            MetaField("world_laws", "世界法则", MetaFieldKind.TextArea),
            MetaField("era_background", "时代背景", MetaFieldKind.TextArea),
            MetaField("civilization_stage", "文明阶段", MetaFieldKind.Text),
            MetaField("tech_magic_level", "科技/魔法水平", MetaFieldKind.TextArea),
            MetaField("world_map_structure", "世界地图结构", MetaFieldKind.TextArea),
            MetaField("cosmology", "维度/位面", MetaFieldKind.TextArea),
            MetaField("timeline_summary", "历史年表", MetaFieldKind.TextArea),
        ),
        "character" to listOf(
            MetaField("alias", "别名/称号", MetaFieldKind.Tags),
            MetaField("character_type", "类型", MetaFieldKind.Select, listOf("主角", "配角", "反派", "NPC", "重要历史人物", "神明/高阶存在")),
            MetaField("race", "种族", MetaFieldKind.Text),
            MetaField("gender", "性别", MetaFieldKind.Select, listOf("男", "女", "未知", "非适用")),
            MetaField("faction", "所属势力", MetaFieldKind.Text),
            MetaField("appearance", "外貌", MetaFieldKind.TextArea),
            MetaField("personality", "性格", MetaFieldKind.TextArea),
            MetaField("background", "背景故事", MetaFieldKind.TextArea),
            MetaField("abilities", "能力/绝技", MetaFieldKind.Tags),
        ),
        "location" to listOf(
            MetaField("alias", "别称", MetaFieldKind.Tags),
            MetaField("location_type", "类型", MetaFieldKind.Select, listOf("国家", "城市", "村镇", "遗迹", "秘境", "位面", "其他")),
            MetaField("region", "所属区域", MetaFieldKind.Text),
            MetaField("controller", "控制势力", MetaFieldKind.Text),
            MetaField("population", "人口/规模", MetaFieldKind.Text),
            MetaField("landmarks", "地标", MetaFieldKind.Tags),
            MetaField("local_rules", "本地规则", MetaFieldKind.TextArea),
        ),
        "faction" to listOf(
            MetaField("alias", "别称", MetaFieldKind.Tags),
            MetaField("faction_type", "类型", MetaFieldKind.Select, listOf("国家", "宗门", "教会", "公会", "秘密组织", "其他")),
            MetaField("leader", "现任领袖", MetaFieldKind.Text),
            MetaField("doctrine", "宗旨/教义", MetaFieldKind.TextArea),
            MetaField("history", "历史沿革", MetaFieldKind.TextArea),
            MetaField("allies", "联盟", MetaFieldKind.Tags),
            MetaField("enemies", "敌对", MetaFieldKind.Tags),
        ),
        "event" to listOf(
            MetaField("event_type", "类型", MetaFieldKind.Select, listOf("大事件", "战争", "灾难", "主线剧情事件", "支线事件", "其他")),
            MetaField("time_label", "时间标记", MetaFieldKind.Text),
            MetaField("location", "发生地点", MetaFieldKind.Text),
            MetaField("participants", "参与方", MetaFieldKind.Tags),
            MetaField("causes", "起因", MetaFieldKind.TextArea),
            MetaField("result", "结果", MetaFieldKind.TextArea),
            MetaField("impact", "影响", MetaFieldKind.TextArea),
        ),
        "item" to listOf(
            MetaField("alias", "别称", MetaFieldKind.Tags),
            MetaField("item_type", "类型", MetaFieldKind.Select, listOf("武器", "防具", "饰品", "消耗品", "神器", "其他")),
            MetaField("rarity", "稀有度", MetaFieldKind.Text),
            MetaField("appearance", "外观", MetaFieldKind.TextArea),
            MetaField("effects", "效果", MetaFieldKind.TextArea),
            MetaField("limitations", "限制", MetaFieldKind.TextArea),
        ),
        "skill" to listOf(
            MetaField("skill_type", "类型", MetaFieldKind.Select, listOf("技能", "法术", "天赋", "禁术", "其他")),
            MetaField("energy_system", "能量体系", MetaFieldKind.Text),
            MetaField("prerequisites", "学习条件", MetaFieldKind.Tags),
            MetaField("cost_rules", "消耗规则", MetaFieldKind.TextArea),
            MetaField("effects", "效果", MetaFieldKind.TextArea),
            MetaField("side_effects", "副作用/风险", MetaFieldKind.TextArea),
        ),
        "profession" to listOf(
            MetaField("system_type", "体系类型", MetaFieldKind.Select, listOf("职业", "职阶", "等级体系", "境界体系")),
            MetaField("promotion_conditions", "晋升条件", MetaFieldKind.TextArea),
            MetaField("limitations", "职业限制", MetaFieldKind.TextArea),
            MetaField("skill_pool", "职业技能池", MetaFieldKind.Tags),
        ),
        "concept" to listOf(
            MetaField("alias", "别称", MetaFieldKind.Tags),
            MetaField("concept_type", "概念类型", MetaFieldKind.Select, listOf("专有名词", "世界概念", "魔法概念", "禁忌概念", "其他")),
            MetaField("definition", "定义", MetaFieldKind.TextArea),
            MetaField("scope", "这个词用在哪", MetaFieldKind.TextArea),
            MetaField("mechanism", "机制", MetaFieldKind.TextArea),
            MetaField("examples", "例子", MetaFieldKind.Tags),
        ),
        "creature" to listOf(
            MetaField("alias", "别称", MetaFieldKind.Tags),
            MetaField("habitat", "栖息地", MetaFieldKind.Text),
            MetaField("threat_level", "威胁等级", MetaFieldKind.Text),
            MetaField("traits", "特征", MetaFieldKind.TextArea),
        ),
        "timeline" to listOf(
            MetaField("calendar", "历法", MetaFieldKind.Text),
            MetaField("time_label", "时间标记", MetaFieldKind.Text),
            MetaField("time_order", "排序值", MetaFieldKind.Number),
            MetaField("branch", "时间线分支", MetaFieldKind.Text),
        ),
    )
}

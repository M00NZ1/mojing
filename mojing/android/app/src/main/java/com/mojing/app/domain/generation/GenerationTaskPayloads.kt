package com.mojing.app.domain.generation

/** [com.mojing.app.data.local.entity.GenerationTaskEntity.payloadJson] — 百科扩展 meta 批量补全 */
data class EncyclopediaMetaBatchPayload(
    val encyclopediaId: Long,
    val entryIds: List<Long>,
)

/** 角色「AI 补全人设」一键（快照入队，避免未保存编辑与执行时不一致） */
data class CharacterPersonaAiPayload(
    val characterId: Long,
    val name: String,
    val personaPrompt: String,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    /** 非空且 >0 时从该百科库拼节选 digest 注入 extraContext（见设置「默认绑定百科」） */
    val contextEncyclopediaId: Long? = null,
)

/** 世界观模板「AI 补全世界书」一键 */
data class WorldTemplatePromptAiPayload(
    val templateRowId: Long,
    val label: String,
    val category: String,
    val summary: String,
    val worldPrompt: String,
    val gameplayMode: String,
    val extraContext: String,
    val contextEncyclopediaId: Long? = null,
    /** 入队时数据库中的目标字段，用于防止迟到的 AI 结果覆盖后续手动编辑。 */
    val expectedSummary: String? = null,
    val expectedWorldPrompt: String? = null,
)

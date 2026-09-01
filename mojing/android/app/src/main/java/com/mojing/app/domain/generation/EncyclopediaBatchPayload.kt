package com.mojing.app.domain.generation

/** [com.mojing.app.data.local.entity.GenerationTaskEntity.payloadJson] */
data class EncyclopediaBatchPayload(
    val encyclopediaId: Long,
    val encyclopediaName: String,
    /** 百科设定：名称、类型、世界书等拼好的背景 */
    val worldBackground: String,
    val entryType: String,
    val count: Int,
    val minWords: Int,
    val maxWords: Int,
    val userContext: String,
    /** 生成前用户自由补充（选填）；与 [userContext] 一并交给模型，旧任务缺省为空 */
    val preGenNotes: String? = null,
    /**
     * `entries`：写入百科条目；`timeline`：写入时间线。
     * null/空/未知时按条目（兼容旧任务 payload）。
     */
    val outputMode: String? = null,
)

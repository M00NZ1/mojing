package com.mojing.app.domain.generation

/** 批量生成时合并「生成前补充」与「世界观上下文」等用户侧说明（供队列与单测共用）。 */
fun combinedUserContextForBatch(payload: EncyclopediaBatchPayload): String {
    val pre = payload.preGenNotes?.trim().orEmpty()
    val ctx = payload.userContext.trim()
    return when {
        pre.isNotEmpty() && ctx.isNotEmpty() ->
            "【用户生成前补充】\n$pre\n\n【世界观 / 其它上下文】\n$ctx"
        pre.isNotEmpty() -> "【用户生成前补充】\n$pre"
        else -> ctx
    }
}

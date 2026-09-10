package com.mojing.app.domain.encyclopedia

import com.google.gson.JsonParser
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity

data class EncyclopediaSourceReferences(val messageIds: List<Long>, val branchId: String?)

/** 与沉淀批次的十条来源上限一致；旧条目使用单条来源字段。 */
fun EncyclopediaEntryEntity.sourceReferences(): EncyclopediaSourceReferences {
    val meta = runCatching { JsonParser.parseString(metaJson).asJsonObject }.getOrNull()
    val ids = runCatching {
        meta?.getAsJsonArray("source_message_ids")?.asSequence()
            ?.mapNotNull { runCatching { it.asString.toLongOrNull()?.takeIf { id -> id > 0L } }.getOrNull() }
            ?.distinct()?.take(10)?.toList().orEmpty()
    }.getOrDefault(emptyList())
    val anchor = sourceMessageId?.takeIf { it > 0L }
    val sources = if (anchor != null && anchor !in ids) ids.take(9) + anchor else ids
    val branch = runCatching { meta?.get("source_branch_id")?.asString?.takeIf { it.isNotBlank() } }.getOrNull()
    return EncyclopediaSourceReferences(sources, branch)
}

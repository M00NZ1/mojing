package com.mojing.app.domain.encyclopedia

import com.google.gson.JsonParser
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.MessageEntity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.regex.Pattern

data class EncyclopediaSourceReferences(
    val messageIds: List<Long>,
    val branchId: String?,
    val fingerprints: Map<Long, String> = emptyMap(),
)

/** Stable per-message identity used to verify a stored source without retaining its body. */
fun messageSourceFingerprint(message: MessageEntity): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun append(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update((bytes.size shr 24).toByte())
        digest.update((bytes.size shr 16).toByte())
        digest.update((bytes.size shr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }
    append(message.content)
    append(message.structuredContentJson)
    append(message.speakerType)
    append(message.characterId?.toString().orEmpty())
    append(message.branchId)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private val SOURCE_FINGERPRINT_PATTERN = Pattern.compile("[0-9a-f]{64}")

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
    val fingerprintVersion = runCatching { meta?.get("source_fingerprint_version")?.asInt }.getOrNull()
    val fingerprints = runCatching {
        if (fingerprintVersion != 1) return@runCatching emptyMap()
        meta?.getAsJsonArray("source_message_fingerprints")?.asSequence()?.take(10)
            ?.mapNotNull { item -> runCatching {
                val obj = item.asJsonObject
                val id = obj.get("message_id")?.asLong?.takeIf { it > 0L }
                val fingerprint = obj.get("fingerprint")?.asString?.takeIf {
                    SOURCE_FINGERPRINT_PATTERN.matcher(it).matches()
                }
                if (id != null && id in sources && fingerprint != null) id to fingerprint else null
            }.getOrNull() }?.toMap().orEmpty()
    }.getOrDefault(emptyMap())
    return EncyclopediaSourceReferences(sources, branch, fingerprints)
}

package com.mojing.app.domain.engine

import com.google.gson.annotations.SerializedName
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A derived, resumable cursor. A fingerprint mismatch always restarts from original messages. */
data class MemoryCompactionCheckpoint(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("sourceFingerprint") val sourceFingerprint: String,
    @SerializedName("nextChunkIndex") val nextChunkIndex: Int,
    @SerializedName("carriedSummary") val carriedSummary: String,
)

internal fun MemoryCompactionSnapshot.sourceFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun part(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val length = bytes.size
        digest.update(byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(),
            (length ushr 8).toByte(), length.toByte()))
        digest.update(bytes)
    }
    part("memory-compaction-chunks-v1")
    part(sessionId.toString())
    part(branchId)
    part(revision.toString())
    part(limit.toString())
    previous.forEach { segment ->
        part(segment.id.toString())
        part(segment.startMessageId.toString())
        part(segment.endMessageId.toString())
        part(segment.summary)
        part(segment.keyFactsJson)
    }
    sources.forEach { message ->
        part(message.id.toString())
        part(message.content)
        part(message.structuredContentJson)
        part(message.speakerType)
        part(message.characterId?.toString().orEmpty())
        part(message.branchId)
    }
    return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

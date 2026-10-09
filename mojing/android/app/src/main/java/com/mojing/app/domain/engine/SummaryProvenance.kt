package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import java.security.MessageDigest

/** Disposable, versioned metadata in app_config. Missing/mismatched metadata always protects text. */
internal object SummaryProvenance {
    fun key(segment: SessionMemorySegmentEntity): String = "memory_summary_source_v1:${segment.sessionId}:${segment.id}"
    fun fingerprint(segment: SessionMemorySegmentEntity): String {
        val source = listOf(segment.sessionId.toString(), segment.id.toString(), segment.branchId,
            segment.startMessageId.toString(), segment.endMessageId.toString(), segment.summary)
            .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

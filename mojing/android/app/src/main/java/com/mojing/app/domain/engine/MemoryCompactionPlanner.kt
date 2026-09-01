package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.MessageEntity

internal object MemoryCompactionPlanner {
    private val storySpeakerTypes = setOf("user", "character", "narrator")

    fun nextBatch(
        messages: List<MessageEntity>,
        lastCoveredMessageId: Long,
        threshold: Int,
    ): List<MessageEntity> {
        require(threshold > 0)
        val candidates = messages.asSequence()
            .filter { it.id > lastCoveredMessageId && it.speakerType in storySpeakerTypes }
            .sortedBy { it.id }
            .take(threshold)
            .toList()
        return candidates.takeIf { it.size == threshold }.orEmpty()
    }
}

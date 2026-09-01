package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.MessageEntity
import javax.inject.Inject

class SlidingWindowBuilder @Inject constructor() {
    fun buildWindow(
        messages: List<MessageEntity>,
        maxTokens: Int
    ): List<MessageEntity> {
        var budget = maxTokens
        return messages.reversed().takeWhile { msg ->
            val cost = TokenCounter.estimate(msg.content)
            budget -= cost
            budget >= 0
        }.reversed()
    }
}

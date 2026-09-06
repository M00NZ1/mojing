package com.mojing.app.data.local.dao

data class MessageRecallReference(val branchId: String, val label: String, val isCheckpoint: Boolean)

data class MessageRecallImpact(
    val canRecall: Boolean,
    val reason: String = "",
    val referenceCount: Int = 0,
    val references: List<MessageRecallReference> = emptyList(),
    val removesDerivedMessages: Boolean = false,
    val maySelectRemainingReply: Boolean = false,
)

class MessageRecallBlockedException(val reason: String) : IllegalStateException(reason)

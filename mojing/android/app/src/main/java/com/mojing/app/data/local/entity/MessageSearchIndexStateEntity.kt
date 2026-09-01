package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "message_search_index_state")
data class MessageSearchIndexStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val indexVersion: Int,
    val indexedThroughMessageId: Long = 0L,
    val isComplete: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

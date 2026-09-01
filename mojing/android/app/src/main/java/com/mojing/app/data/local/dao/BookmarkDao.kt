package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.MessageBookmarkEntity

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM message_bookmarks WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    suspend fun getBySession(sessionId: Long): List<MessageBookmarkEntity>

    @Query("SELECT * FROM message_bookmarks WHERE messageId = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: Long): MessageBookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MessageBookmarkEntity): Long

    @Query("DELETE FROM message_bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM message_bookmarks WHERE messageId = :messageId")
    suspend fun deleteByMessageId(messageId: Long)
}

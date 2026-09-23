package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.MessageBookmarkEntity

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM message_bookmarks WHERE sessionId = :sessionId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getFirstPage(sessionId: Long, limit: Int): List<MessageBookmarkEntity>

    @Query("SELECT * FROM message_bookmarks WHERE sessionId = :sessionId " +
        "AND (createdAt < :beforeCreatedAt OR (createdAt = :beforeCreatedAt AND id < :beforeId)) " +
        "ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getBefore(sessionId: Long, beforeCreatedAt: Long, beforeId: Long, limit: Int): List<MessageBookmarkEntity>

    @Query("SELECT messageId FROM message_bookmarks WHERE sessionId = :sessionId AND messageId IN (:messageIds)")
    suspend fun getBookmarkedMessageIds(sessionId: Long, messageIds: List<Long>): List<Long>

    @Query("SELECT * FROM message_bookmarks WHERE messageId = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: Long): MessageBookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MessageBookmarkEntity): Long

    @Query("DELETE FROM message_bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM message_bookmarks WHERE messageId = :messageId")
    suspend fun deleteByMessageId(messageId: Long)
}

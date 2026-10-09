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

    /** Searches saved bookmark notes only, using the same keyset cursor as the unfiltered pages. */
    suspend fun searchPage(
        sessionId: Long,
        query: String,
        beforeCreatedAt: Long?,
        beforeId: Long?,
        limit: Int,
    ): List<MessageBookmarkEntity> {
        require(limit >= 0) { "limit must be non-negative" }
        require((beforeCreatedAt == null) == (beforeId == null)) { "bookmark cursor must be complete" }
        if (limit == 0) return emptyList()
        return searchPageInternal(sessionId, query.trim(), beforeCreatedAt, beforeId, limit)
    }

    @Query("SELECT * FROM message_bookmarks WHERE sessionId = :sessionId " +
        "AND note COLLATE NOCASE LIKE '%' || replace(replace(replace(:query, '\\', '\\\\'), '%', '\\%'), '_', '\\_') || '%' ESCAPE '\\' " +
        "AND (:beforeCreatedAt IS NULL OR createdAt < :beforeCreatedAt OR " +
        "(createdAt = :beforeCreatedAt AND id < :beforeId)) " +
        "ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun searchPageInternal(
        sessionId: Long,
        query: String,
        beforeCreatedAt: Long?,
        beforeId: Long?,
        limit: Int,
    ): List<MessageBookmarkEntity>

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

    @Query("UPDATE message_bookmarks SET note = :note WHERE sessionId = :sessionId AND id = :bookmarkId")
    suspend fun updateNote(sessionId: Long, bookmarkId: Long, note: String): Int
}

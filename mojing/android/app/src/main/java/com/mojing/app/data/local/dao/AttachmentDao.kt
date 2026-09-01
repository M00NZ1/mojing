package com.mojing.app.data.local.dao

import androidx.room.*
import com.mojing.app.data.local.entity.MessageAttachmentEntity

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM message_attachments WHERE messageId = :messageId")
    suspend fun getByMessage(messageId: Long): List<MessageAttachmentEntity>

    @Query("SELECT * FROM message_attachments WHERE messageId IN (:messageIds)")
    suspend fun getByMessages(messageIds: List<Long>): List<MessageAttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MessageAttachmentEntity): Long

    @Query("DELETE FROM message_attachments WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM message_attachments WHERE storagePath = :storagePath")
    suspend fun countByStoragePath(storagePath: String): Int

    @Query("SELECT DISTINCT storagePath FROM message_attachments WHERE storagePath IN (:storagePaths)")
    suspend fun getReferencedStoragePaths(storagePaths: List<String>): List<String>
}

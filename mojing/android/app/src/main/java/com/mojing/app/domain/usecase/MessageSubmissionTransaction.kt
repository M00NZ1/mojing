package com.mojing.app.domain.usecase

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import javax.inject.Inject
import javax.inject.Singleton

/** 用户消息与本次全部附件的唯一原子提交入口。 */
@Singleton
class MessageSubmissionTransaction @Inject constructor(
    private val database: AppDatabase,
) {
    suspend operator fun invoke(
        message: MessageEntity,
        attachments: List<MessageAttachmentEntity>,
        onMessageIdAssigned: (Long) -> Unit = {},
    ): Long = database.withTransaction {
        require(message.id == 0L) { "只能提交尚未保存的用户消息" }
        require(attachments.all { attachment ->
            attachment.id == 0L &&
                attachment.messageId == 0L &&
                attachment.storagePath.isNotBlank()
        }) { "待发送附件状态无效" }

        val messageId = database.messageDao().insert(message)
        check(messageId > 0L) { "消息保存失败" }
        onMessageIdAssigned(messageId)

        attachments.forEach { attachment ->
            val attachmentId = database.attachmentDao().insert(
                attachment.copy(messageId = messageId),
            )
            check(attachmentId > 0L) { "附件保存失败" }
        }
        messageId
    }
}

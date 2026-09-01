package com.mojing.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded

/** 会话列表行：主分支最后一条消息预览 + 统计，供列表卡片展示。 */
data class SessionWithListMeta(
    @Embedded val session: SessionEntity,
    @ColumnInfo(name = "last_msg_preview") val lastMessagePreview: String?,
    @ColumnInfo(name = "last_msg_speaker_type") val lastMessageSpeakerType: String?,
    @ColumnInfo(name = "msg_count") val messageCount: Int,
    @ColumnInfo(name = "participant_count") val participantCount: Int,
)

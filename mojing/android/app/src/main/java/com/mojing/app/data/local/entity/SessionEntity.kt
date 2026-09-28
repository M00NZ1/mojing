package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions", indices = [Index("updatedAt"), Index(value = ["creationRequestId"], unique = true)])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "新对话",
    val summary: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val thinkMaxEnabled: Boolean = false,
    /** >0 表示置顶，数值越大越靠前（新置顶在上） */
    val pinnedAt: Long = 0L,
    /**
     * 聊天页「上下文占用」条的分母（仅展示、不参与 API 截断）。
     * 默认 100 万；新建对话时可在表单中填写。
     */
    val displayContextTokenLimit: Int = 1_000_000,
    /** 一次创建表单的稳定编号；旧会话为 null。 */
    val creationRequestId: String? = null,
)

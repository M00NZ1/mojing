package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "characters",
    indices = [Index(value = ["boundEncyclopediaId"])],
)
data class CharacterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val personaPrompt: String = "",
    val apiKey: String = "",
    val apiBaseUrl: String = "",
    val modelName: String = "deepseek-chat",
    val temperature: Float = 0.9f,
    val maxTokens: Int = 1200,
    val topP: Float = 1.0f,
    val topK: Int = 0,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val repetitionPenalty: Float = 1.0f,
    val avatarColor: String = "#F97316",
    val avatarImagePath: String = "",
    /** 角色卡封面大图（约 2∶3）；列表/网格主图优先用此字段，空则回退头像 */
    val cardImagePath: String = "",
    val voiceProfileId: Long? = null,
    val voiceProvider: String = "",
    val voiceApiBaseUrl: String = "",
    val voiceApiKey: String = "",
    val voiceModel: String = "",
    val imageGenEnabled: Boolean = false,
    val imageGenApiKey: String = "",
    val imageGenBaseUrl: String = "",
    val imageGenModel: String = "dall-e-3",
    val thinkMaxEnabled: Boolean = false,
    val thinkMaxModelName: String = "",
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** >0 表示置顶（与 favorite 独立） */
    val pinnedAt: Long = 0L,
    /** >0 表示绑定到某百科库；新建会话选百科后仅可选同库角色；0 为历史未绑定数据 */
    val boundEncyclopediaId: Long = 0L,
)

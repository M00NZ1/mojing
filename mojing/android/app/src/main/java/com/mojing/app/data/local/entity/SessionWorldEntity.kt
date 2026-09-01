package com.mojing.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_worlds",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], ForeignKey.CASCADE)],
    indices = [Index("sessionId", unique = true)]
)
data class SessionWorldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val encyclopediaId: Long? = null,
    val worldPrompt: String = "",
    val templateId: String = "custom",
    val gameplayMode: String = "自由剧情",
    val narratorEnabled: Boolean = false,
    val narratorName: String = "旁白",
    val choiceGenerationEnabled: Boolean = true,
    val maxChoiceCount: Int = 3,
    val suggestedChoicesJson: String = "[]",
    val antiCheatEnabled: Boolean = true,
    val antiCheatPrompt: String = "",
    val worldTimelineJson: String = "[]",
    val autoSedimentEnabled: Boolean = true,
    val sedimentInterval: Int = 20,
    /**
     * 本会话/世界级 API 覆盖（仅本机 Room，加密存储在会话维度）。
     * 解析顺序：角色卡字段 → 下方会话字段 → 设置里的「主 API / 生图 / 语音」。
     * 用于不同会话使用不同 Key，避免多世界串用同一供应商身份。
     */
    val sessionLlmApiKey: String = "",
    val sessionLlmBaseUrl: String = "",
    val sessionImageApiKey: String = "",
    val sessionImageBaseUrl: String = "",
    val sessionImageModel: String = "",
    val sessionVoiceApiKey: String = "",
    val sessionVoiceBaseUrl: String = "",
    val sessionVoiceModel: String = "",
    val sessionVoiceSpeechVoice: String = "",
    val sessionVoicePresetPrefixModel: String = "",
    /** 开启后：角色回复中的 `<GEN_IMAGE>` 会触发自动配图（占位消息 → 出图）；不影响用户手动点生图。 */
    val autoCharacterImageGen: Boolean = false,
    /** 开启后：角色回复中的 `<GEN_SPEECH>` 会生成语音附件；不影响消息长按「朗读」。 */
    val autoCharacterSpeech: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

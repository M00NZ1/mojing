package com.mojing.app.data

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class ChatDraftSnapshot(
    val inputText: String = "",
    val pendingAttachmentPaths: List<String> = emptyList(),
    val pendingSubmissionId: String? = null,
    val narratorGuidance: String = "",
    val imagePrompt: String = "",
)

/** 按会话保存未发送内容；它是草稿单一持久化 owner，不承载已发送消息。 */
@Singleton
class ChatDraftStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(sessionId: Long): ChatDraftSnapshot {
        if (sessionId <= 0L) return ChatDraftSnapshot()
        val raw = preferences.getString(key(sessionId), null) ?: return ChatDraftSnapshot()
        return runCatching {
            val root = JsonParser.parseString(raw).asJsonObject
            if (root.get("version")?.asInt != VERSION) return@runCatching ChatDraftSnapshot()
            val paths = root.getAsJsonArray("pendingAttachmentPaths")
                ?.mapNotNull { element ->
                    element.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotEmpty)
                }
                .orEmpty()
                .distinct()
            ChatDraftSnapshot(
                imagePrompt = root.get("imagePrompt")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                narratorGuidance = root.get("narratorGuidance")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                inputText = root.get("inputText")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                pendingAttachmentPaths = paths,
                pendingSubmissionId = root.get("pendingSubmissionId")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.trim()
                    ?.takeIf { it.length in 1..MAX_SUBMISSION_ID_LENGTH },
            )
        }.getOrDefault(ChatDraftSnapshot())
    }

    fun save(sessionId: Long, snapshot: ChatDraftSnapshot) {
        write(sessionId, snapshot, synchronous = false)
    }

    /** 发送前的交接标记必须先落盘，之后才能把消息提交到 Room。 */
    fun saveBeforeSubmission(sessionId: Long, snapshot: ChatDraftSnapshot): Boolean {
        if (snapshot.pendingSubmissionId.isNullOrBlank()) return false
        return write(sessionId, snapshot, synchronous = true)
    }

    private fun write(sessionId: Long, snapshot: ChatDraftSnapshot, synchronous: Boolean): Boolean {
        if (sessionId <= 0L) return false
        val editor = preferences.edit()
        val submissionId = snapshot.pendingSubmissionId
            ?.trim()
            ?.takeIf { it.length in 1..MAX_SUBMISSION_ID_LENGTH }
        if (snapshot.imagePrompt.isEmpty() && snapshot.narratorGuidance.isEmpty() && snapshot.inputText.isEmpty() && snapshot.pendingAttachmentPaths.isEmpty() && submissionId == null) {
            if (synchronous) return editor.remove(key(sessionId)).commit()
            editor.remove(key(sessionId)).apply()
            return true
        }
        val root = JsonObject().apply {
            addProperty("version", VERSION)
            addProperty("inputText", snapshot.inputText)
            addProperty("narratorGuidance", snapshot.narratorGuidance)
            addProperty("imagePrompt", snapshot.imagePrompt)
            add("pendingAttachmentPaths", JsonArray().also { array ->
                snapshot.pendingAttachmentPaths.distinct().forEach(array::add)
            })
            submissionId?.let { addProperty("pendingSubmissionId", it) }
        }
        if (synchronous) return editor.putString(key(sessionId), root.toString()).commit()
        editor.putString(key(sessionId), root.toString()).apply()
        return true
    }

    private fun key(sessionId: Long): String = "session_$sessionId"

    private companion object {
        const val VERSION = 1
        const val PREFERENCES_NAME = "chat_drafts_v1"
        const val MAX_SUBMISSION_ID_LENGTH = 128
    }
}

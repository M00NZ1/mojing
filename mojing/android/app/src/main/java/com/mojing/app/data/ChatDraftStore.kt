package com.mojing.app.data

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

data class ChatDraftSnapshot(
    val inputText: String = "",
    val pendingAttachmentPaths: List<String> = emptyList(),
    val pendingSubmissionId: String? = null,
    val narratorGuidance: String = "",
    val imagePrompt: String = "",
    val quotedMessageId: Long? = null,
)

data class ReplyRecoverySnapshot(
    val token: String,
    val sessionId: Long,
    val branchId: String,
    val speakerType: String,
    val characterId: Long? = null,
    val anchorMessageId: Long? = null,
    val swipeGroupId: String? = null,
    val swipeSourceMessageId: Long? = null,
    val rawText: String,
    val startedAt: Long,
    val updatedAt: Long,
)

sealed interface ReplyRecoveryLoadResult {
    data object Missing : ReplyRecoveryLoadResult
    data class Valid(val snapshot: ReplyRecoverySnapshot) : ReplyRecoveryLoadResult
    data class Unreadable(val raw: String) : ReplyRecoveryLoadResult
}

/** JSON codec kept independent of Android so recovery records can be checked in JVM tests. */
object ReplyRecoveryCodec {
    private const val VERSION = 1

    fun encode(snapshot: ReplyRecoverySnapshot): String {
        requireValid(snapshot)
        return JsonObject().apply {
            addProperty("version", VERSION)
            addProperty("token", snapshot.token)
            addProperty("sessionId", snapshot.sessionId)
            addProperty("branchId", snapshot.branchId)
            addProperty("speakerType", snapshot.speakerType)
            snapshot.characterId?.let { addProperty("characterId", it) }
            snapshot.anchorMessageId?.let { addProperty("anchorMessageId", it) }
            snapshot.swipeGroupId?.let { addProperty("swipeGroupId", it) }
            snapshot.swipeSourceMessageId?.let { addProperty("swipeSourceMessageId", it) }
            addProperty("rawText", snapshot.rawText)
            addProperty("startedAt", snapshot.startedAt)
            addProperty("updatedAt", snapshot.updatedAt)
        }.toString()
    }

    fun decode(raw: String): ReplyRecoverySnapshot {
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.get("version")?.asInt == VERSION) { "Unknown reply recovery version" }
        val snapshot = ReplyRecoverySnapshot(
            token = root.requiredString("token"),
            sessionId = root.requiredLong("sessionId"),
            branchId = root.requiredString("branchId"),
            speakerType = root.requiredString("speakerType"),
            characterId = root.optionalLong("characterId"),
            anchorMessageId = root.optionalLong("anchorMessageId"),
            swipeGroupId = root.optionalString("swipeGroupId"),
            swipeSourceMessageId = root.optionalLong("swipeSourceMessageId"),
            rawText = root.requiredString("rawText"),
            startedAt = root.requiredLong("startedAt"),
            updatedAt = root.requiredLong("updatedAt"),
        )
        requireValid(snapshot)
        return snapshot
    }

    private fun requireValid(snapshot: ReplyRecoverySnapshot) {
        val token = snapshot.token.trim()
        require(token.isNotEmpty() && runCatching { UUID.fromString(token) }.getOrNull()?.toString() == token.lowercase()) {
            "Invalid reply recovery token"
        }
        require(snapshot.sessionId > 0L) { "Invalid reply recovery session" }
        require(snapshot.branchId.isNotBlank()) { "Invalid reply recovery branch" }
        require(snapshot.speakerType == "character" || snapshot.speakerType == "narrator") {
            "Invalid reply recovery speaker"
        }
        require(snapshot.speakerType == "character" == (snapshot.characterId != null)) {
            "Invalid reply recovery character binding"
        }
        snapshot.characterId?.let { require(it > 0L) { "Invalid reply recovery character" } }
        snapshot.anchorMessageId?.let { require(it > 0L) { "Invalid reply recovery anchor" } }
        snapshot.swipeGroupId?.let { require(it.isNotBlank()) { "Invalid reply recovery swipe group" } }
        snapshot.swipeSourceMessageId?.let { require(it > 0L) { "Invalid reply recovery swipe source" } }
        require((snapshot.swipeGroupId == null) == (snapshot.swipeSourceMessageId == null)) {
            "Incomplete reply recovery swipe binding"
        }
        require(snapshot.rawText.isNotBlank()) { "Empty reply recovery text" }
        require(snapshot.startedAt > 0L && snapshot.updatedAt >= snapshot.startedAt) {
            "Invalid reply recovery timestamps"
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: error("Missing reply recovery field: $name")

    private fun JsonObject.requiredLong(name: String): Long =
        get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: error("Missing reply recovery field: $name")

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.optionalLong(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asLong
}

internal object ReplyRecoveryKeys {
    fun forSession(sessionId: Long): String = "reply_recovery_v1_session_$sessionId"
}

/** 按会话保存未发送内容；它是草稿单一持久化 owner，不承载已发送消息。 */
@Singleton
class ChatDraftStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val recoveryLock = Any()

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
                quotedMessageId = runCatching { root.get("quotedMessageId")?.asLong }.getOrNull()?.takeIf { it > 0L },
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

    /** Reads the one process handoff record without discarding damaged data. */
    fun loadReplyRecovery(sessionId: Long): ReplyRecoveryLoadResult = synchronized(recoveryLock) {
        if (sessionId <= 0L) return@synchronized ReplyRecoveryLoadResult.Missing
        val raw = preferences.getString(ReplyRecoveryKeys.forSession(sessionId), null)
            ?: return@synchronized ReplyRecoveryLoadResult.Missing
        runCatching { ReplyRecoveryCodec.decode(raw) }
            .fold(
                onSuccess = { ReplyRecoveryLoadResult.Valid(it) },
                onFailure = { ReplyRecoveryLoadResult.Unreadable(raw) },
            )
    }

    /** Creates the record only when no record exists; a different pending token is preserved. */
    fun saveReplyRecovery(snapshot: ReplyRecoverySnapshot): Boolean = synchronized(recoveryLock) {
        val encoded = runCatching { ReplyRecoveryCodec.encode(snapshot) }.getOrNull() ?: return@synchronized false
        val key = ReplyRecoveryKeys.forSession(snapshot.sessionId)
        if (preferences.contains(key)) return@synchronized false
        preferences.edit().putString(key, encoded).commit()
    }

    /** Replaces a record synchronously only when the stored token is the same. */
    fun checkpointReplyRecovery(snapshot: ReplyRecoverySnapshot): Boolean = synchronized(recoveryLock) {
        val encoded = runCatching { ReplyRecoveryCodec.encode(snapshot) }.getOrNull() ?: return@synchronized false
        val current = readReplyRecoveryLocked(snapshot.sessionId)
        if (current !is ReplyRecoveryLoadResult.Valid || current.snapshot.token != snapshot.token) return@synchronized false
        preferences.edit().putString(ReplyRecoveryKeys.forSession(snapshot.sessionId), encoded).commit()
    }

    /** Clears a recovery record only when its token still matches the caller's handoff. */
    fun clearReplyRecovery(sessionId: Long, token: String): Boolean = synchronized(recoveryLock) {
        if (sessionId <= 0L) return@synchronized false
        val current = readReplyRecoveryLocked(sessionId)
        if (current !is ReplyRecoveryLoadResult.Valid || current.snapshot.token != token) return@synchronized false
        preferences.edit().remove(ReplyRecoveryKeys.forSession(sessionId)).commit()
    }

    /** Explicit user escape hatch for a damaged record; never removes it implicitly. */
    fun discardUnreadableReplyRecovery(sessionId: Long, raw: String): Boolean = synchronized(recoveryLock) {
        if (sessionId <= 0L) return@synchronized false
        val key = ReplyRecoveryKeys.forSession(sessionId)
        val stored = preferences.getString(key, null) ?: return@synchronized false
        if (stored != raw || runCatching { ReplyRecoveryCodec.decode(stored) }.isSuccess) return@synchronized false
        preferences.edit().remove(key).commit()
    }

    private fun readReplyRecoveryLocked(sessionId: Long): ReplyRecoveryLoadResult {
        val raw = preferences.getString(ReplyRecoveryKeys.forSession(sessionId), null)
            ?: return ReplyRecoveryLoadResult.Missing
        return runCatching { ReplyRecoveryCodec.decode(raw) }
            .fold({ ReplyRecoveryLoadResult.Valid(it) }, { ReplyRecoveryLoadResult.Unreadable(raw) })
    }

    private fun write(sessionId: Long, snapshot: ChatDraftSnapshot, synchronous: Boolean): Boolean {
        if (sessionId <= 0L) return false
        val editor = preferences.edit()
        val submissionId = snapshot.pendingSubmissionId
            ?.trim()
            ?.takeIf { it.length in 1..MAX_SUBMISSION_ID_LENGTH }
        if (snapshot.imagePrompt.isEmpty() && snapshot.narratorGuidance.isEmpty() && snapshot.inputText.isEmpty() && snapshot.quotedMessageId == null && snapshot.pendingAttachmentPaths.isEmpty() && submissionId == null) {
            if (synchronous) return editor.remove(key(sessionId)).commit()
            editor.remove(key(sessionId)).apply()
            return true
        }
        val root = JsonObject().apply {
            addProperty("version", VERSION)
            addProperty("inputText", snapshot.inputText)
            snapshot.quotedMessageId?.takeIf { it > 0L }?.let { addProperty("quotedMessageId", it) }
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

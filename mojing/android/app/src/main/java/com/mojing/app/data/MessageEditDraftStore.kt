package com.mojing.app.data

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class MessageEditDraftScope(val sessionId: Long, val branchId: String, val messageId: Long) {
    init {
        require(sessionId > 0L && branchId.isNotBlank() && messageId > 0L)
    }
}

data class MessageEditDraft(
    val scope: MessageEditDraftScope,
    val sourceFingerprint: String,
    val editedContent: String,
    val originalBody: String,
    val revision: Long = 1L,
)

class UnreadableMessageEditDraft(val file: File, cause: Throwable? = null) :
    IllegalStateException("消息编辑草稿无法读取", cause)

/** File-backed editor drafts; malformed files are deliberately left in place for recovery. */
@Singleton
class MessageEditDraftStore @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.filesDir, "message-edit-drafts-v1")
    private val gson = Gson()
    private val fileMutex = Mutex()

    suspend fun load(scope: MessageEditDraftScope): MessageEditDraft? = withContext(Dispatchers.IO) {
        fileMutex.withLock { loadLocked(scope) }
    }

    private fun loadLocked(scope: MessageEditDraftScope): MessageEditDraft? {
        val file = fileFor(scope)
        if (!file.isFile && !File(file.path + ".bak").isFile) return null
        val raw = try {
            AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (error: Exception) {
            throw UnreadableMessageEditDraft(file, error)
        }
        try {
            val root = JsonParser.parseString(raw).asJsonObject
            require(root.get("version")?.asInt == VERSION)
            val storedScope = MessageEditDraftScope(
                root.get("sessionId")?.asLong ?: error("Missing session id"),
                root.get("branchId")?.asString?.takeIf(String::isNotBlank) ?: error("Missing branch id"),
                root.get("messageId")?.asLong ?: error("Missing message id"),
            )
            require(storedScope == scope)
            val fingerprint = root.get("sourceFingerprint")?.asString?.takeIf(String::isNotBlank)
                ?: error("Missing source fingerprint")
            val edited = root.get("editedContent")?.asString ?: error("Missing edited content")
            val original = root.get("originalBody")?.asString ?: error("Missing original body")
            val revision = root.get("revision")?.asLong ?: error("Missing revision")
            require(revision > 0L)
            return MessageEditDraft(storedScope, fingerprint, edited, original, revision)
        } catch (error: Exception) {
            throw if (error is UnreadableMessageEditDraft) error else UnreadableMessageEditDraft(file, error)
        }
    }

    suspend fun save(draft: MessageEditDraft): MessageEditDraft = withContext(Dispatchers.IO) {
        fileMutex.withLock { saveLocked(draft) }
    }

    private fun saveLocked(draft: MessageEditDraft): MessageEditDraft {
        require(draft.revision > 0L)
        val file = fileFor(draft.scope)
        file.parentFile?.mkdirs()
        val current = loadLocked(draft.scope)
        if (current != null && current.revision > draft.revision) return current
        val atomic = AtomicFile(file)
        val bytes = gson.toJson(mapOf(
            "version" to VERSION,
            "sessionId" to draft.scope.sessionId,
            "branchId" to draft.scope.branchId,
            "messageId" to draft.scope.messageId,
            "sourceFingerprint" to draft.sourceFingerprint,
            "editedContent" to draft.editedContent,
            "originalBody" to draft.originalBody,
            "revision" to draft.revision,
        )).toByteArray(StandardCharsets.UTF_8)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        return draft
    }

    suspend fun clear(scope: MessageEditDraftScope, expectedRevision: Long): Boolean = withContext(Dispatchers.IO) {
        if (expectedRevision <= 0L) return@withContext false
        fileMutex.withLock {
            val current = loadLocked(scope) ?: return@withLock !fileFor(scope).exists() &&
                !File(fileFor(scope).path + ".bak").exists()
            if (current.revision != expectedRevision) return@withLock false
            val file = fileFor(scope)
            AtomicFile(file).delete()
            !file.exists() && !File(file.path + ".bak").exists()
        }
    }

    fun fileFor(scope: MessageEditDraftScope): File = File(directory, "${scopeKey(scope)}.json")

    private fun scopeKey(scope: MessageEditDraftScope): String = sha256(
        "${scope.sessionId}\u0000${scope.branchId}\u0000${scope.messageId}",
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        const val VERSION = 1
        fun sourceFingerprint(scope: MessageEditDraftScope, originalBody: String, structuredContentJson: String): String =
            MessageDigest.getInstance("SHA-256").digest(
                "${scope.sessionId}\u0000${scope.branchId}\u0000${scope.messageId}\u0000$originalBody\u0000$structuredContentJson"
                    .toByteArray(StandardCharsets.UTF_8),
            ).joinToString("") { "%02x".format(it) }
    }
}

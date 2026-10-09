package com.mojing.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.MessageEditDraft
import com.mojing.app.data.MessageEditDraftScope
import com.mojing.app.data.MessageEditDraftStore
import com.mojing.app.data.UnreadableMessageEditDraft
import com.mojing.app.data.local.branch.BranchVisibilityIndexManager
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.engine.ConversationMessageText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class MessageEditDraftState(
    val scope: MessageEditDraftScope? = null,
    val target: MessageEntity? = null,
    val originalBody: String = "",
    val content: String = "",
    val sourceFingerprint: String = "",
    val revision: Long = 0L,
    val persistedRevision: Long = 0L,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val dirty: Boolean = false,
    val hasRecoverableDraft: Boolean = false,
    val recoverableContent: String? = null,
    val sourceMissing: Boolean = false,
    val error: String? = null,
)

private data class LoadedMessageEdit(
    val target: MessageEntity?,
    val draft: MessageEditDraft?,
    val originalBody: String = "",
    val sourceFingerprint: String = "",
)

/** Owns only editor draft durability; message commits remain ChatViewModel's responsibility. */
@HiltViewModel
class MessageEditDraftController internal constructor(
    private val messageDao: MessageDao,
    private val visibility: BranchVisibilityIndexManager,
    private val draftStore: MessageEditDraftStore,
    private val io: kotlinx.coroutines.CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(messageDao: MessageDao, visibility: BranchVisibilityIndexManager, draftStore: MessageEditDraftStore)
        : this(messageDao, visibility, draftStore, Dispatchers.IO)
    private val _state = MutableStateFlow(MessageEditDraftState())
    val state: StateFlow<MessageEditDraftState> = _state.asStateFlow()
    private val writeMutex = Mutex()
    private var writeTail: Job? = null
    private var pendingDraft: MessageEditDraft? = null
    private var pendingOpen: MessageEditDraftScope? = null
    private var openJob: Job? = null
    private var revision = 0L
    private var ownerToken = 0L

    fun open(sessionId: Long, branchId: String, messageId: Long) {
        val scope = MessageEditDraftScope(sessionId, branchId, messageId)
        val previous = _state.value
        if (previous.scope == scope &&
            (previous.isLoading || previous.isSaving || previous.dirty || previous.revision > previous.persistedRevision || pendingDraft != null)
        ) return
        if (previous.scope != null && previous.scope != scope &&
            (previous.isLoading || previous.isSaving || previous.dirty || previous.revision > previous.persistedRevision || pendingDraft != null)
        ) {
            pendingOpen = scope
            if (openJob?.isActive != true) {
                val previousScope = previous.scope
                openJob = viewModelScope.launch {
                    if (flush()) {
                        val next = pendingOpen
                        pendingOpen = null
                        if (next != null && _state.value.scope == previousScope) openNow(next)
                    }
                    openJob = null
                }
            }
            return
        }
        pendingOpen = null
        openNow(scope)
    }

    private fun openNow(scope: MessageEditDraftScope) {
        // Every load is a new owner epoch. A retry or re-open of the same
        // message only reaches here after the previous editor has no pending
        // write, so its queued draft cannot be discarded.
        val token = ++ownerToken
        revision = 0L
        _state.value = MessageEditDraftState(scope = scope, isLoading = true)
        viewModelScope.launch {
            try {
                val loaded = withContext(io) {
                    visibility.ensureReady()
                    val target = if (scope.branchId == "main")
                        messageDao.getMainMessageById(scope.sessionId, scope.messageId)
                    else messageDao.getVisibleMessageById(scope.sessionId, scope.branchId, scope.messageId)
                    val draft = draftStore.load(scope)
                    if (target == null) LoadedMessageEdit(null, draft)
                    else {
                        val original = visibleBody(target)
                        LoadedMessageEdit(
                            target = target,
                            draft = draft,
                            originalBody = original,
                            sourceFingerprint = MessageEditDraftStore.sourceFingerprint(
                                scope, original, target.structuredContentJson,
                            ),
                        )
                    }
                }
                if (token != ownerToken) return@launch
                val target = loaded.target
                val draft = loaded.draft
                if (target == null) {
                    if (draft == null) {
                        _state.value = MessageEditDraftState(scope = scope, sourceMissing = true,
                            error = "消息已不存在，且没有可恢复的编辑草稿。")
                    } else {
                        revision = draft.revision
                        _state.value = MessageEditDraftState(scope = scope, content = draft.editedContent,
                            originalBody = draft.originalBody, sourceFingerprint = draft.sourceFingerprint,
                            revision = draft.revision, persistedRevision = draft.revision,
                            dirty = draft.editedContent != draft.originalBody, hasRecoverableDraft = true,
                            sourceMissing = true, error = "消息已不存在；可复制未保存草稿。")
                    }
                    return@launch
                }
                val original = loaded.originalBody
                val fingerprint = loaded.sourceFingerprint
                if (draft != null && draft.sourceFingerprint != fingerprint) {
                    revision = draft.revision
                    _state.value = MessageEditDraftState(scope = scope, target = target, originalBody = original,
                        content = original, sourceFingerprint = fingerprint, revision = draft.revision,
                        persistedRevision = draft.revision, hasRecoverableDraft = true,
                        recoverableContent = draft.editedContent,
                        error = "原文已改变，已保留旧草稿；请确认后重新编辑。")
                } else {
                    val content = draft?.editedContent ?: original
                    revision = draft?.revision ?: 0L
                    _state.value = MessageEditDraftState(scope = scope, target = target, originalBody = original,
                        content = content, sourceFingerprint = fingerprint, revision = revision,
                        persistedRevision = draft?.revision ?: 0L,
                        dirty = content != original, hasRecoverableDraft = draft != null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: UnreadableMessageEditDraft) {
                if (token == ownerToken) _state.update { it.copy(isLoading = false, error = "编辑草稿无法读取，原文件已保留。") }
            } catch (_: Exception) {
                if (token == ownerToken) _state.update { it.copy(isLoading = false, error = "消息暂时无法读取，请重试。") }
            }
        }
    }

    fun restorePending(sessionId: Long, branchId: String, messageId: Long) = open(sessionId, branchId, messageId)

    fun update(value: String) {
        val current = _state.value
        if (current.scope == null || current.isLoading || current.error?.contains("原文已改变") == true) return
        val nextRevision = maxOf(revision + 1L, current.revision + 1L)
        revision = nextRevision
        _state.update { it.copy(content = value, revision = nextRevision, dirty = value != it.originalBody, error = null) }
        enqueueSave(ownerToken, current.scope, nextRevision, current.sourceFingerprint, value, current.originalBody)
    }

    fun restoreRecoveredDraft() {
        val current = _state.value
        val recovered = current.recoverableContent ?: return
        _state.update {
            it.copy(
                content = recovered,
                dirty = recovered != it.originalBody,
                error = null,
                recoverableContent = null,
            )
        }
        val nextRevision = maxOf(revision + 1L, current.revision + 1L)
        revision = nextRevision
        _state.update { it.copy(revision = nextRevision, isSaving = true) }
        enqueueSave(ownerToken, current.scope ?: return, nextRevision, current.sourceFingerprint, recovered, current.originalBody)
    }

    private fun enqueueSave(token: Long, scope: MessageEditDraftScope, revision: Long, fingerprint: String, content: String, original: String) {
        val draft = MessageEditDraft(scope, fingerprint, content, original, revision)
        pendingDraft = draft
        val previous = writeTail
        writeTail = viewModelScope.launch(io) {
            runCatching {
                previous?.join()
                if (token != ownerToken || _state.value.scope != scope || _state.value.revision != revision) return@runCatching
                val persisted = writeMutex.withLock {
                    val persisted = draftStore.save(draft)
                    persisted.revision == revision
                }
                if (!persisted) return@runCatching
                if (token == ownerToken && _state.value.scope == scope && _state.value.revision == revision) {
                    if (pendingDraft?.revision == revision && pendingDraft?.scope == scope) pendingDraft = null
                    _state.update { it.copy(persistedRevision = revision, isSaving = false, hasRecoverableDraft = true) }
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                if (token == ownerToken && _state.value.scope == scope && _state.value.revision == revision) {
                    _state.update { it.copy(isSaving = false, error = "编辑草稿暂存失败，文字仍保留在当前页面。") }
                }
            }
        }
        _state.update { it.copy(isSaving = true, error = null) }
    }

    suspend fun flush(): Boolean = withContext(NonCancellable) {
        writeTail?.join()
        var current = _state.value
        if (current.scope != null && current.persistedRevision < current.revision) {
            val pending = pendingDraft
            if (pending != null && pending.scope == current.scope && pending.revision == current.revision) {
                enqueueSave(ownerToken, pending.scope, pending.revision, pending.sourceFingerprint, pending.editedContent, pending.originalBody)
                writeTail?.join()
                current = _state.value
            }
        }
        current.scope != null && current.persistedRevision >= current.revision && current.error == null
    }

    suspend fun dismissRetaining(): Boolean = flush()

    suspend fun discard(expectedRevision: Long? = null): Boolean = withContext(NonCancellable) {
        val current = _state.value
        val scope = current.scope ?: return@withContext true
        val requestedRevision = expectedRevision ?: current.revision
        if (requestedRevision <= 0L) return@withContext true
        writeTail?.join()
        val latest = _state.value
        if (latest.scope != scope) return@withContext false
        // Do not let a delayed dismiss clear a draft created after it was
        // requested. The persisted revision may be lower after a failed save;
        // that older file is still safe to discard when the editor revision
        // has not changed.
        if (latest.revision != requestedRevision) return@withContext false
        val clearRevision = latest.persistedRevision
        val cleared = if (clearRevision <= 0L) {
            true
        } else {
            runCatching { draftStore.clear(scope, clearRevision) }.getOrDefault(false)
        }
        if (cleared) {
            pendingDraft = null
            _state.update {
                it.copy(
                    content = it.originalBody,
                    revision = 0L,
                    persistedRevision = 0L,
                    hasRecoverableDraft = false,
                    recoverableContent = null,
                    dirty = false,
                    error = null,
                )
            }
        } else {
            _state.update { it.copy(error = "编辑草稿清理失败，请重试。") }
        }
        cleared
    }

    /** Call only after ChatViewModel reports that its message write committed. */
    suspend fun markSaved(expectedRevision: Long = _state.value.persistedRevision): Boolean = withContext(NonCancellable) {
        val current = _state.value
        val scope = current.scope ?: return@withContext true
        val token = ownerToken
        writeTail?.join()
        val latest = _state.value
        if (token != ownerToken || latest.scope != scope || latest.revision != expectedRevision ||
            latest.persistedRevision != expectedRevision
        ) return@withContext false
        val cleared = runCatching { draftStore.clear(scope, expectedRevision) }.getOrDefault(false)
        if (cleared) {
            pendingDraft = null
            _state.update {
                it.copy(
                    content = it.originalBody,
                    revision = 0L,
                    persistedRevision = 0L,
                    hasRecoverableDraft = false,
                    recoverableContent = null,
                    dirty = false,
                    error = null,
                )
            }
        }
        cleared
    }

    fun visibleBody(message: MessageEntity): String =
        ConversationMessageText.forUserVisibleText(message.content, message.speakerType).trim()
}

package com.mojing.app.ui.encyclopedia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.EntryDraftSnapshot
import com.mojing.app.data.EntryEditDraftStore
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EntryVersionDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import com.mojing.app.domain.encyclopedia.CharacterEncyclopediaSync
import com.mojing.app.domain.encyclopedia.sourceReferences
import com.mojing.app.data.local.entity.EntryVersionEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.repository.ImageRepository
import com.mojing.app.domain.engine.AiCompleter
import com.mojing.app.ui.util.UserFacingStrings
import com.google.gson.JsonParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class EntrySourceTarget(val sessionId: Long, val messageId: Long, val branchId: String)

data class EntryEditState(
    val sourceMessageIds: List<Long> = emptyList(),
    val sourceIndex: Int = 0,
    val sourceTarget: EntrySourceTarget? = null,
    val hasSourceMessage: Boolean = false,
    val sourcePreviewOpen: Boolean = false,
    val sourceLoading: Boolean = false,
    val sourceContent: String? = null,
    val sourceError: String? = null,
    val isConversationNote: Boolean = false,
    val title: String = "",
    val entryType: String = "character",
    val summary: String = "",
    val content: String = "",
    val tags: String = "",
    val confidence: String = "confirmed",
    val metaJson: String = "{}",
    val isFeatured: Boolean = false,
    /** 本地封面路径；与 Web `cover_image_path` / Room `coverImagePath` 对齐 */
    val coverImagePath: String = "",
    val coverPromptHint: String = "",
    val isAiCompleting: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val versionError: String? = null,
    val isGeneratingCover: Boolean = false,
    val isLoaded: Boolean = false,
    val loadError: String? = null,
    val isPersisted: Boolean = false,
    val persistedEntryId: Long = 0L,
    val isDirty: Boolean = false,
    /** 按保存顺序倒序，每页最多十条。 */
    val versions: List<EntryVersionEntity> = emptyList(),
    val hasOlderVersions: Boolean = false,
    val isOlderVersionPage: Boolean = false,
    val isLoadingVersions: Boolean = false,
    val pendingVersion: EntryVersionEntity? = null,
    val snackbar: String? = null,
    /** 用于按百科题材过滤「类型」选项（名称+简介+标签拼接） */
    val encyclopediaHint: String = "",
    val hasPublicLlmKey: Boolean = false,
    val recoverableDraft: EntryDraftSnapshot? = null,
    val draftError: String? = null,
    val draftUnreadable: Boolean = false,
    val isDiscardingDraft: Boolean = false,
)

private fun EntryEditState.toDraftSnapshot() = EntryDraftSnapshot(
    title = title,
    entryType = entryType,
    summary = summary,
    content = content,
    tags = tags,
    confidence = confidence,
    metaJson = metaJson,
    isFeatured = isFeatured,
    coverImagePath = coverImagePath,
)

private fun EntryEditState.withPersistedEntry(entry: EncyclopediaEntryEntity) = copy(
    hasSourceMessage = (entry.sourceSessionId ?: 0) > 0 && entry.sourceReferences().messageIds.isNotEmpty(),
    isConversationNote = CharacterEncyclopediaSync.isConversationNote(entry),
    title = entry.title,
    entryType = entry.entryType,
    summary = entry.summary,
    content = entry.content,
    tags = entry.tags,
    confidence = entry.confidence,
    metaJson = entry.metaJson.ifBlank { "{}" },
    isFeatured = entry.isFeatured,
    coverImagePath = entry.coverImagePath,
    isLoaded = true,
    loadError = null,
    isPersisted = true,
    persistedEntryId = entry.id,
)

private fun EntryEditState.withRecoveredDraft(draft: EntryDraftSnapshot) = copy(
    title = draft.title,
    entryType = draft.entryType,
    summary = draft.summary,
    content = draft.content,
    tags = draft.tags,
    confidence = draft.confidence,
    metaJson = draft.metaJson,
    isFeatured = draft.isFeatured,
    coverImagePath = draft.coverImagePath,
)

@HiltViewModel
class EntryEditViewModel @Inject constructor(
    private val entryDao: EncyclopediaEntryDao,
    private val saveCharacterEntry: SaveCharacterEntryUseCase,
    private val encyclopediaDao: EncyclopediaDao,
    private val entryVersionDao: EntryVersionDao,
    private val aiCompleter: AiCompleter,
    private val secureStorage: SecureStorage,
    private val imageRepository: ImageRepository,
    private val messageDao: com.mojing.app.data.local.dao.MessageDao,
    private val draftStore: EntryEditDraftStore,
) : ViewModel() {
    private val _state = MutableStateFlow(EntryEditState())
    val state: StateFlow<EntryEditState> = _state.asStateFlow()

    private var encId: Long = 0
    private var currentEntry: EncyclopediaEntryEntity? = null
    private var loadRevision = 0L
    private var loadJob: Job? = null
    private var savedDraft = _state.value.toDraftSnapshot()
    private var draftEntryId = 0L
    private val draftWriteMutex = Mutex()
    private var draftWriteRevision = 0L
    private var draftWriteJob: Job? = null
    private var pendingNewDraftTransfer = false
    private var sourceJob: kotlinx.coroutines.Job? = null
    private var sourceRevision = 0L

    fun closeSourcePreview() {
        sourceRevision++
        sourceJob?.cancel()
        sourceJob = null
        _state.value = _state.value.copy(sourcePreviewOpen = false, sourceLoading = false, sourceContent = null, sourceError = null, sourceTarget = null)
    }

    fun openSourcePreview() = loadSourcePreview(null)

    fun showSourceMessage(index: Int) {
        if (!_state.value.sourcePreviewOpen || index !in _state.value.sourceMessageIds.indices) return
        loadSourcePreview(index)
    }

    private fun loadSourcePreview(requestedIndex: Int?) {
        val entry = currentEntry ?: return
        val sessionId = entry.sourceSessionId?.takeIf { it > 0 } ?: return
        val references = entry.sourceReferences()
        if (references.messageIds.isEmpty()) return
        val index = requestedIndex ?: if (_state.value.sourcePreviewOpen) _state.value.sourceIndex
            else references.messageIds.indexOf(entry.sourceMessageId).coerceAtLeast(0)
        val messageId = references.messageIds.getOrNull(index) ?: return
        if (_state.value.sourceLoading) return
        val revision = ++sourceRevision
        _state.value = _state.value.copy(sourcePreviewOpen = true, sourceLoading = true, sourceContent = null, sourceError = null, sourceTarget = null,
            sourceMessageIds = references.messageIds, sourceIndex = index)
        sourceJob = viewModelScope.launch {
            try {
                val message = messageDao.getByIdInSession(messageId, sessionId)
                if (sourceRevision != revision || currentEntry?.id != entry.id) return@launch
                val branchId = references.branchId ?: message?.branchId ?: "main"
                _state.value = _state.value.copy(sourceLoading = false, sourceContent = message?.content,
                    sourceTarget = message?.let { EntrySourceTarget(sessionId, messageId, branchId) },
                    sourceError = if (message == null) "原始对话已不存在，百科内容仍保留。" else null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (sourceRevision == revision) _state.value = _state.value.copy(sourceLoading = false, sourceError = "原文读取失败，请重试。")
            }
        }
    }

    private fun updateDraft(transform: (EntryEditState) -> EntryEditState) {
        if (!_state.value.isLoaded || _state.value.loadError != null || _state.value.recoverableDraft != null ||
            _state.value.draftUnreadable || _state.value.isDiscardingDraft) return
        val next = transform(_state.value)
        _state.value = next.copy(isDirty = next.toDraftSnapshot() != savedDraft, saveError = null)
        persistCurrentDraft()
    }

    private fun persistCurrentDraft() {
        val id = draftEntryId.takeIf { it >= 0L && encId > 0L } ?: return
        val encyclopediaId = encId
        val snapshot = _state.value.toDraftSnapshot()
        val dirty = _state.value.isDirty
        val revision = ++draftWriteRevision
        draftWriteJob?.cancel()
        draftWriteJob = viewModelScope.launch {
            try {
                draftWriteMutex.withLock {
                    if (revision != draftWriteRevision || draftEntryId != id || encId != encyclopediaId) return@withLock
                    if (pendingNewDraftTransfer && id > 0L) {
                        draftStore.syncAfterFirstSave(encyclopediaId, id, snapshot.takeIf { dirty })
                        pendingNewDraftTransfer = false
                    } else if (dirty) draftStore.save(encyclopediaId, id, snapshot)
                    else draftStore.clear(encyclopediaId, id)
                }
                if (revision == draftWriteRevision && draftEntryId == id && encId == encyclopediaId)
                    _state.update { it.copy(draftError = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == draftWriteRevision && draftEntryId == id && encId == encyclopediaId)
                    _state.update { it.copy(draftError = "词条草稿暂存失败，当前输入仍在页面中；可重试或直接保存") }
            }
        }
    }

    fun retryDraftSave() {
        if (_state.value.isLoaded && _state.value.recoverableDraft == null && !_state.value.draftUnreadable) {
            if (pendingNewDraftTransfer && draftEntryId > 0L) viewModelScope.launch { syncDraftAfterSave(encId, draftEntryId) }
            else persistCurrentDraft()
        }
    }

    private suspend fun syncDraftAfterSave(encyclopediaId: Long, entryId: Long) {
        val revision = ++draftWriteRevision
        draftWriteJob?.cancel()
        try {
            draftWriteMutex.withLock {
                if (revision != draftWriteRevision || encId != encyclopediaId || draftEntryId != entryId) return@withLock
                val current = _state.value
                if (pendingNewDraftTransfer) {
                    draftStore.syncAfterFirstSave(encyclopediaId, entryId, current.toDraftSnapshot().takeIf { current.isDirty })
                    pendingNewDraftTransfer = false
                } else if (current.isDirty) draftStore.save(encyclopediaId, entryId, current.toDraftSnapshot())
                else draftStore.clear(encyclopediaId, entryId)
            }
            if (revision == draftWriteRevision) _state.update { it.copy(draftError = null) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (revision == draftWriteRevision) _state.update { it.copy(
                draftError = if (_state.value.isDirty) "词条已保存，但新的修改暂存失败；请重试"
                else "词条已保存，但旧草稿清除失败；请重试清理后离开",
            ) }
        }
    }

    private suspend fun readRecoveryDraft(encyclopediaId: Long, entryId: Long) {
        if (entryId < 0L) return
        val draft = try { draftStore.load(encyclopediaId, entryId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (encId == encyclopediaId && draftEntryId == entryId) _state.update { it.copy(
                draftUnreadable = true,
                draftError = "本机词条草稿无法读取，已保留原始草稿。可重试读取或明确丢弃。",
            ) }
            return
        }
        if (encId != encyclopediaId || draftEntryId != entryId) return
        _state.update { it.copy(
            recoverableDraft = draft?.takeIf { candidate -> candidate != savedDraft },
            draftUnreadable = false,
            draftError = null,
        ) }
        if (draft == savedDraft) {
            try { draftStore.clear(encyclopediaId, entryId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (encId == encyclopediaId && draftEntryId == entryId)
                    _state.update { it.copy(draftError = "已保存词条可用，但旧草稿清除失败；可重试清理") }
            }
        }
    }

    fun retryDraftLoad() {
        val id = draftEntryId.takeIf { it >= 0L } ?: return
        if (!_state.value.draftUnreadable || _state.value.isDiscardingDraft) return
        val encyclopediaId = encId
        viewModelScope.launch { readRecoveryDraft(encyclopediaId, id) }
    }

    fun restoreDraft() {
        if (_state.value.isDiscardingDraft) return
        val draft = _state.value.recoverableDraft ?: return
        val restored = _state.value.withRecoveredDraft(draft).copy(recoverableDraft = null, draftError = null)
        _state.value = restored.copy(isDirty = restored.toDraftSnapshot() != savedDraft)
        persistCurrentDraft()
    }

    fun discardStoredDraft() {
        val id = draftEntryId.takeIf { it >= 0L } ?: return
        if (_state.value.isDiscardingDraft) return
        val encyclopediaId = encId
        _state.update { it.copy(isDiscardingDraft = true) }
        val revision = ++draftWriteRevision
        draftWriteJob?.cancel()
        viewModelScope.launch {
            try {
                draftWriteMutex.withLock { draftStore.clear(encyclopediaId, id) }
                if (revision == draftWriteRevision && encId == encyclopediaId && draftEntryId == id)
                    _state.update { it.copy(recoverableDraft = null, draftUnreadable = false, draftError = null, isDiscardingDraft = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == draftWriteRevision && encId == encyclopediaId && draftEntryId == id)
                    _state.update { it.copy(draftError = "未能丢弃词条草稿，请重试", isDiscardingDraft = false) }
            }
        }
    }

    fun discardChangesAndLeave(onDiscarded: () -> Unit) {
        val id = draftEntryId.takeIf { it >= 0L }
        if (id == null) { onDiscarded(); return }
        if (_state.value.isDiscardingDraft) return
        val encyclopediaId = encId
        _state.update { it.copy(isDiscardingDraft = true) }
        val revision = ++draftWriteRevision
        draftWriteJob?.cancel()
        viewModelScope.launch {
            try {
                draftWriteMutex.withLock {
                    if (pendingNewDraftTransfer && id > 0L) {
                        draftStore.syncAfterFirstSave(encyclopediaId, id, null)
                        pendingNewDraftTransfer = false
                    } else draftStore.clear(encyclopediaId, id)
                }
                if (revision == draftWriteRevision && encId == encyclopediaId && draftEntryId == id) onDiscarded()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == draftWriteRevision && encId == encyclopediaId && draftEntryId == id)
                    _state.update { it.copy(draftError = "草稿清除失败，已留在编辑页；可重试后离开", isDiscardingDraft = false) }
            }
        }
    }

    fun consumeSnackbar() {
        _state.value = _state.value.copy(snackbar = null)
    }

    private fun showSnackbar(msg: String) {
        _state.value = _state.value.copy(snackbar = msg)
    }

    fun load(encyclopediaId: Long, entryId: Long) {
        closeSourcePreview()
        loadJob?.cancel()
        val revision = ++loadRevision
        draftWriteRevision++
        draftWriteJob?.cancel()
        pendingNewDraftTransfer = false
        encId = encyclopediaId
        draftEntryId = entryId
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoaded = false, loadError = null)
            try {
                val enc = encyclopediaDao.getById(encyclopediaId)
                if (revision != loadRevision) return@launch
                if (enc == null) {
                    currentEntry = null
                    val missing = EntryEditState(
                        isLoaded = true,
                        loadError = "找不到所属百科，它可能已经被删除",
                        hasPublicLlmKey = secureStorage.publicApiKey.isNotBlank(),
                    )
                    savedDraft = missing.toDraftSnapshot()
                    _state.value = missing
                    return@launch
                }
                val hint = "${enc.name} ${enc.description} ${enc.genreTags}"
                val entry = if (entryId > 0) entryDao.getById(entryId) else null
                if (revision != loadRevision) return@launch
                if (entryId > 0 && (entry == null || entry.encyclopediaId != encyclopediaId)) {
                    currentEntry = null
                    val missing = EntryEditState(
                        isLoaded = true,
                        loadError = "找不到这个词条，它可能已经被删除或移动",
                        encyclopediaHint = hint,
                        hasPublicLlmKey = secureStorage.publicApiKey.isNotBlank(),
                    )
                    savedDraft = missing.toDraftSnapshot()
                    _state.value = missing
                    return@launch
                }
                currentEntry = entry
                val loaded = if (entry != null) {
                    EntryEditState(
                        encyclopediaHint = hint,
                        hasPublicLlmKey = secureStorage.publicApiKey.isNotBlank(),
                    ).withPersistedEntry(entry).copy(isDirty = false)
                } else {
                    EntryEditState(
                        entryType = "character",
                        isLoaded = true,
                        encyclopediaHint = hint,
                        hasPublicLlmKey = secureStorage.publicApiKey.isNotBlank(),
                    )
                }
                savedDraft = loaded.toDraftSnapshot()
                _state.value = loaded.copy(isLoaded = false)
                readRecoveryDraft(encyclopediaId, entryId)
                if (revision != loadRevision) return@launch
                _state.update { it.copy(isLoaded = true) }
                if (entry != null) loadVersionPage(older = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (revision != loadRevision) return@launch
                currentEntry = null
                val failed = EntryEditState(
                    isLoaded = true,
                    loadError = "读取词条失败，请重试",
                    hasPublicLlmKey = secureStorage.publicApiKey.isNotBlank(),
                )
                savedDraft = failed.toDraftSnapshot()
                _state.value = failed
            }
        }
    }

    fun updateTitle(v: String) = updateDraft { it.copy(title = v) }
    fun updateEntryType(v: String) = updateDraft { it.copy(entryType = v) }
    fun updateSummary(v: String) = updateDraft { it.copy(summary = v) }
    fun updateContent(v: String) = updateDraft { it.copy(content = v) }
    fun updateTags(v: String) = updateDraft { it.copy(tags = v) }
    fun updateConfidence(v: String) = updateDraft { it.copy(confidence = v) }
    fun updateMetaJson(v: String) = updateDraft { it.copy(metaJson = v) }
    fun updateFeatured(v: Boolean) = updateDraft { it.copy(isFeatured = v) }
    fun updateCoverImagePath(v: String) = updateDraft { it.copy(coverImagePath = v) }
    fun updateCoverPromptHint(v: String) { _state.value = _state.value.copy(coverPromptHint = v) }

    fun aiComplete() {
        val s = _state.value
        if (s.title.isBlank()) {
            showSnackbar(UserFacingStrings.entryTitleRequiredForAi())
            return
        }
        if (secureStorage.publicApiKey.isBlank()) {
            showSnackbar(UserFacingStrings.llmKeyMissingForAiComplete())
            return
        }

        _state.value = _state.value.copy(isAiCompleting = true)
        viewModelScope.launch {
            try {
                val result = aiCompleter.complete(
                    apiKey = secureStorage.publicApiKey,
                    baseUrl = secureStorage.publicBaseUrl,
                    model = secureStorage.publicModel,
                    request = AiCompleter.CompleteRequest(
                        targetType = "encyclopedia_entry",
                        entryType = s.entryType,
                        currentData = mapOf(
                            "title" to s.title,
                            "summary" to s.summary,
                            "content" to s.content,
                            "tags" to s.tags
                        ),
                        extraContext = s.title
                    )
                )
                var changed = false
                val promptFields = aiCompleter.fieldKeysFor("encyclopedia_entry", s.entryType)
                var next = _state.value
                val extraContent = mutableListOf<String>()
                promptFields.forEach { key ->
                    (result[key] as? String)?.takeIf { it.isNotBlank() }?.let { value ->
                        when (key) {
                            "summary" -> if (s.summary.isBlank() && next.summary == s.summary) {
                                next = next.copy(summary = value); changed = true
                            }
                            "content" -> if (s.content.isBlank() && next.content == s.content) {
                                next = next.copy(content = value); changed = true
                            }
                            "tags" -> if (s.tags.isBlank() && next.tags == s.tags) {
                                next = next.copy(tags = value); changed = true
                            }
                            else -> extraContent += "【$key】$value"
                        }
                    }
                }
                if (extraContent.isNotEmpty() && s.content.isBlank() && next.content == s.content) {
                    next = next.copy(content = extraContent.joinToString("\n\n"))
                    changed = true
                }
                if (changed) updateDraft { next }
                if (changed) showSnackbar(UserFacingStrings.entryAiApplied())
                else showSnackbar(UserFacingStrings.entryAiNoNewFields())
            } catch (e: Exception) {
                showSnackbar(UserFacingStrings.remoteRequestFailed(e))
            }
            _state.value = _state.value.copy(isAiCompleting = false)
        }
    }

    /**
     * 直连 OpenAI 兼容配图接口（设置中的公共配图线路），裁切为条目封面比例后写入本地路径。
     */
    fun generateEntryCoverViaBackend() {
        val s = _state.value
        if (s.title.isBlank()) {
            showSnackbar(UserFacingStrings.entryTitleRequiredForCover())
            return
        }
        val base = secureStorage.imageBaseUrl.trim().ifBlank { secureStorage.publicBaseUrl.trim() }
        val key = secureStorage.imageApiKey.trim().ifBlank { secureStorage.publicApiKey.trim() }
        val model = secureStorage.imageModel.trim().ifBlank { "dall-e-3" }
        if (base.isBlank() || key.isBlank()) {
            showSnackbar(UserFacingStrings.imageGenKeyMissing())
            return
        }
        val prompt = buildString {
            append("竖版百科或卡牌插图，约 2:3，无文字无水印。条目：")
            append(s.title.take(120))
            append("。类型：")
            append(s.entryType)
            append("。摘要：")
            append(s.summary.trim().take(1200))
            if (s.coverPromptHint.isNotBlank()) {
                append("。补充：")
                append(s.coverPromptHint.trim().take(400))
            }
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isGeneratingCover = true)
            try {
                val coverResult = withContext(Dispatchers.IO) {
                    imageRepository.generateAndSaveImageForCover(
                        apiKey = key,
                        baseUrl = base,
                        prompt = prompt,
                        model = model,
                        size = "1024x1024",
                        quality = "standard",
                    )
                }
                val finalPath = coverResult.getOrElse { error ->
                    showSnackbar(
                        when (error.message) {
                            "图片生成失败：未返回图片地址" -> UserFacingStrings.entryCoverNoUrl()
                            "图片下载失败", "图片裁切保存失败" -> UserFacingStrings.entryCoverSaveFailed()
                            else -> UserFacingStrings.remoteRequestFailed(error)
                        },
                    )
                    return@launch
                }
                updateDraft {
                    it.copy(
                        coverImagePath = finalPath,
                        snackbar = UserFacingStrings.entryCoverGeneratedSaveHint(),
                    )
                }
            } finally {
                _state.value = _state.value.copy(isGeneratingCover = false)
            }
        }
    }

    fun save() {
        val submittedState = _state.value
        if (!submittedState.isLoaded || submittedState.loadError != null || submittedState.isSaving || submittedState.isLoadingVersions ||
            submittedState.isDiscardingDraft || submittedState.recoverableDraft != null || submittedState.draftUnreadable ||
            (submittedState.isPersisted && !submittedState.isDirty)) return
        val submittedDraft = submittedState.toDraftSnapshot()
        if (submittedState.title.isBlank()) {
            _state.value = submittedState.copy(saveError = UserFacingStrings.entryTitleRequired())
            return
        }
        val parsedMeta = runCatching { JsonParser.parseString(submittedState.metaJson.ifBlank { "{}" }) }.getOrNull()
        if (parsedMeta == null || !parsedMeta.isJsonObject) {
            _state.value = submittedState.copy(saveError = "扩展资料 JSON 须为对象 {…}，请修正后重试")
            return
        }
        _state.value = submittedState.copy(isSaving = true, saveError = null)
        viewModelScope.launch {
            try {
                val base = currentEntry ?: EncyclopediaEntryEntity(encyclopediaId = encId)
                val now = System.currentTimeMillis()
                val toSave = base.copy(
                    title = submittedState.title.trim(),
                    entryType = submittedState.entryType,
                    summary = submittedState.summary.trim(),
                    content = submittedState.content,
                    tags = submittedState.tags.trim(),
                    confidence = submittedState.confidence.ifBlank { "confirmed" },
                    metaJson = submittedState.metaJson.ifBlank { "{}" },
                    isFeatured = submittedState.isFeatured,
                    coverImagePath = submittedState.coverImagePath.trim(),
                    updatedAt = now,
                    createdAt = if (base.id == 0L) now else base.createdAt
                )
                val saved = saveCharacterEntry.saveEdited(toSave)
                currentEntry = saved
                if (draftEntryId == 0L && saved.id > 0L) pendingNewDraftTransfer = true
                draftEntryId = saved.id
                val latest = _state.value
                val persisted = latest.withPersistedEntry(saved).copy(
                    isDirty = false,
                    saveError = null,
                )
                savedDraft = persisted.toDraftSnapshot()
                val result = if (latest.toDraftSnapshot() != submittedDraft) {
                    latest.copy(
                        isPersisted = true,
                        persistedEntryId = saved.id,
                        saveError = null,
                        snackbar = "已保存，当前还有未保存的修改",
                    )
                } else {
                    persisted.copy(snackbar = UserFacingStrings.entrySaved())
                }
                _state.value = result.copy(isDirty = result.toDraftSnapshot() != savedDraft)
                syncDraftAfterSave(encId, saved.id)
                // 正文已提交；版本读取失败不能使表单重新变成未保存。
                try {
                    val versionPage = entryVersionDao.getPage(saved.id, Long.MAX_VALUE, 11)
                    _state.value = _state.value.copy(
                        versions = versionPage.take(10),
                        hasOlderVersions = versionPage.size > 10,
                        isOlderVersionPage = false,
                        versionError = null,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _state.value = _state.value.copy(versionError = "条目已保存，历史版本刷新失败")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    isSaving = false,
                    saveError = UserFacingStrings.localSaveFailed("条目"),
                )
                persistCurrentDraft()
            } finally {
                _state.value = _state.value.copy(isSaving = false)
            }
        }
    }

    fun loadVersionPage(older: Boolean) {
        val state = _state.value
        val entry = currentEntry ?: return
        if (state.isLoadingVersions || state.isSaving || (older && !state.hasOlderVersions)) return
        val beforeId = if (older) state.versions.lastOrNull()?.id ?: return else Long.MAX_VALUE
        _state.value = state.copy(isLoadingVersions = true, versionError = null)
        viewModelScope.launch {
            try {
                val page = entryVersionDao.getPage(entry.id, beforeId, 11)
                _state.value = _state.value.copy(versions = page.take(10), hasOlderVersions = page.size > 10,
                    isOlderVersionPage = older)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.value = _state.value.copy(versionError = "历史版本读取失败，请刷新重试") }
            finally { _state.value = _state.value.copy(isLoadingVersions = false) }
        }
    }

    fun dismissVersionReplacement() {
        _state.value = _state.value.copy(pendingVersion = null)
    }

    /** 将历史快照载入表单，替换草稿前确认；保存操作独立执行。 */
    fun applyVersionToForm(v: EntryVersionEntity, replaceDraft: Boolean = false): Boolean {
        val state = _state.value
        if (!state.isLoaded || state.loadError != null || state.isSaving || state.isLoadingVersions ||
            state.isAiCompleting || state.isGeneratingCover || state.isDiscardingDraft ||
            state.recoverableDraft != null || state.draftUnreadable || currentEntry?.id != v.entryId ||
            v !in state.versions) return false
        if (state.isDirty && !replaceDraft) {
            _state.value = state.copy(pendingVersion = v)
            return false
        }
        updateDraft {
            it.copy(
                pendingVersion = null,
                title = v.title,
                summary = v.summary,
                content = v.content,
                tags = v.tags,
                metaJson = v.metaSnapshotJson.ifBlank { "{}" },
                snackbar = UserFacingStrings.entryHistoryVersionLoaded(),
            )
        }
        return true
    }

}

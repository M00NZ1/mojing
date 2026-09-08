package com.mojing.app.ui.encyclopedia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EntryVersionDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import com.mojing.app.domain.encyclopedia.CharacterEncyclopediaSync
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
import javax.inject.Inject

data class EntryEditState(
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
    val isGeneratingCover: Boolean = false,
    val isLoaded: Boolean = false,
    val loadError: String? = null,
    val isPersisted: Boolean = false,
    val isDirty: Boolean = false,
    /** 按保存顺序倒序，每页最多十条。 */
    val versions: List<EntryVersionEntity> = emptyList(),
    val hasOlderVersions: Boolean = false,
    val isOlderVersionPage: Boolean = false,
    val isLoadingVersions: Boolean = false,
    val snackbar: String? = null,
    /** 用于按百科题材过滤「类型」选项（名称+简介+标签拼接） */
    val encyclopediaHint: String = "",
    val hasPublicLlmKey: Boolean = false,
)

private data class EntryDraftSnapshot(
    val title: String,
    val entryType: String,
    val summary: String,
    val content: String,
    val tags: String,
    val confidence: String,
    val metaJson: String,
    val isFeatured: Boolean,
    val coverImagePath: String,
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
) : ViewModel() {
    private val _state = MutableStateFlow(EntryEditState())
    val state: StateFlow<EntryEditState> = _state.asStateFlow()

    private var encId: Long = 0
    private var currentEntry: EncyclopediaEntryEntity? = null
    private var savedDraft = _state.value.toDraftSnapshot()

    private fun updateDraft(transform: (EntryEditState) -> EntryEditState) {
        val next = transform(_state.value)
        _state.value = next.copy(isDirty = next.toDraftSnapshot() != savedDraft)
    }

    fun consumeSnackbar() {
        _state.value = _state.value.copy(snackbar = null)
    }

    private fun showSnackbar(msg: String) {
        _state.value = _state.value.copy(snackbar = msg)
    }

    fun load(encyclopediaId: Long, entryId: Long) {
        encId = encyclopediaId
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoaded = false, loadError = null)
            try {
                val enc = encyclopediaDao.getById(encyclopediaId)
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
                        versions = entryVersionDao.getPage(entry.id, Long.MAX_VALUE, 11),
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
                _state.value = loaded.copy(versions = loaded.versions.take(10), hasOlderVersions = loaded.versions.size > 10)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
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
            (submittedState.isPersisted && !submittedState.isDirty)) return
        val submittedDraft = submittedState.toDraftSnapshot()
        if (submittedState.title.isBlank()) {
            showSnackbar(UserFacingStrings.entryTitleRequired())
            return
        }
        val parsedMeta = runCatching { JsonParser.parseString(submittedState.metaJson.ifBlank { "{}" }) }.getOrNull()
        if (parsedMeta == null || !parsedMeta.isJsonObject) {
            showSnackbar("扩展资料 JSON 须为对象 {…}，请修正后重试")
            return
        }
        _state.value = submittedState.copy(isSaving = true)
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
                val versionPage = entryVersionDao.getPage(saved.id, Long.MAX_VALUE, 11)
                val versions = versionPage.take(10)
                val latest = _state.value
                val persisted = latest.withPersistedEntry(saved).copy(
                    versions = versions,
                    hasOlderVersions = versionPage.size > 10,
                    isOlderVersionPage = false,
                    isSaving = false,
                    isDirty = false,
                )
                savedDraft = persisted.toDraftSnapshot()
                val result = if (latest.toDraftSnapshot() != submittedDraft) {
                    latest.copy(
                        versions = versions,
                        hasOlderVersions = versionPage.size > 10,
                        isOlderVersionPage = false,
                        isSaving = false,
                        isPersisted = true,
                        snackbar = "已保存，当前还有未保存的修改",
                    )
                } else {
                    persisted.copy(snackbar = UserFacingStrings.entrySaved())
                }
                _state.value = result.copy(isDirty = result.toDraftSnapshot() != savedDraft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    isSaving = false,
                    snackbar = UserFacingStrings.localSaveFailed("条目"),
                )
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
        _state.value = state.copy(isLoadingVersions = true)
        viewModelScope.launch {
            try {
                val page = entryVersionDao.getPage(entry.id, beforeId, 11)
                _state.value = _state.value.copy(versions = page.take(10), hasOlderVersions = page.size > 10,
                    isOlderVersionPage = older)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showSnackbar("版本读取失败，请重试") }
            finally { _state.value = _state.value.copy(isLoadingVersions = false) }
        }
    }

    /** 将某一历史快照载入表单（需再点保存才会写回当前条目） */
    fun applyVersionToForm(v: EntryVersionEntity) {
        updateDraft {
            it.copy(
                title = v.title,
                summary = v.summary,
                content = v.content,
                tags = v.tags,
                metaJson = v.metaSnapshotJson.ifBlank { "{}" },
                snackbar = UserFacingStrings.entryHistoryVersionLoaded(),
            )
        }
    }

}

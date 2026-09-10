package com.mojing.app.ui.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.WorldLoreEntryDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.data.remote.AssetItemDto
import com.mojing.app.data.remote.BackendAssetsApi
import com.mojing.app.data.remote.BackendWorldsApi
import com.mojing.app.data.remote.WorldQualityReportDto
import com.mojing.app.domain.generation.GenerationQueueProcessor
import com.mojing.app.domain.generation.WorldTemplatePromptAiPayload
import com.mojing.app.ui.util.UserFacingStrings
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class TemplateEditState(
    val label: String = "",
    val templateId: String = "",
    val category: String = "玄幻",
    val summary: String = "",
    val gameplayMode: String = "自由剧情",
    val worldPrompt: String = "",
    val antiCheatPrompt: String = "",
    val suggestedChoicesJson: String = "[]",
    val coverImagePath: String = "",
    val isAiCompleting: Boolean = false,
    val isSaving: Boolean = false,
    val isLoaded: Boolean = false,
    val loadError: String? = null,
    val isPersisted: Boolean = false,
    val isDirty: Boolean = false,
    val isBackendQualityLoading: Boolean = false,
    val backendQualityReport: WorldQualityReportDto? = null,
    val snackbar: String? = null,
    val coverBuiltinSearchQuery: String = "",
    val coverBuiltinAssets: List<AssetItemDto> = emptyList(),
    val isCoverBuiltinLoading: Boolean = false,
    val hasPublicLlmKey: Boolean = false,
)

private data class TemplateDraftSnapshot(
    val label: String,
    val templateId: String,
    val category: String,
    val summary: String,
    val gameplayMode: String,
    val worldPrompt: String,
    val antiCheatPrompt: String,
    val suggestedChoicesJson: String,
    val coverImagePath: String,
)

private fun TemplateEditState.toDraftSnapshot() = TemplateDraftSnapshot(
    label = label,
    templateId = templateId,
    category = category,
    summary = summary,
    gameplayMode = gameplayMode,
    worldPrompt = worldPrompt,
    antiCheatPrompt = antiCheatPrompt,
    suggestedChoicesJson = suggestedChoicesJson,
    coverImagePath = coverImagePath,
)

private fun TemplateEditState.withPersistedDraft(entity: WorldTemplateEntity) = copy(
    label = entity.label,
    templateId = entity.templateId,
    category = entity.category,
    summary = entity.summary,
    gameplayMode = entity.gameplayMode,
    worldPrompt = entity.worldPrompt,
    antiCheatPrompt = entity.antiCheatPrompt,
    suggestedChoicesJson = entity.suggestedChoicesJson,
    coverImagePath = entity.coverImagePath,
    isLoaded = true,
    loadError = null,
    isPersisted = true,
)

@HiltViewModel
class TemplateEditViewModel @Inject constructor(
    private val templateDao: WorldTemplateDao,
    private val loreEntryDao: WorldLoreEntryDao,
    private val backendWorldsApi: Lazy<BackendWorldsApi>,
    private val backendAssetsApi: BackendAssetsApi,
    private val secureStorage: SecureStorage,
    private val generationQueueProcessor: GenerationQueueProcessor,
) : ViewModel() {
    private val gson = Gson()
    private val _state = MutableStateFlow(TemplateEditState())
    val state: StateFlow<TemplateEditState> = _state.asStateFlow()

    private var currentEntity: WorldTemplateEntity? = null
    private var loadedForRowId: Long? = null
    private var genObserveJob: Job? = null
    private var prevTemplateGenBusy = false
    private var savedDraft = _state.value.toDraftSnapshot()

    private fun updateDraft(transform: (TemplateEditState) -> TemplateEditState) {
        val next = transform(_state.value)
        _state.value = next.copy(isDirty = next.toDraftSnapshot() != savedDraft)
    }

    private fun startTemplateQueueObservation(rowId: Long) {
        genObserveJob?.cancel()
        genObserveJob = null
        if (rowId <= 0L) return
        prevTemplateGenBusy = false
        genObserveJob = viewModelScope.launch {
            generationQueueProcessor.observeActiveForTemplate(rowId).collectLatest { list ->
                val busy = list.isNotEmpty()
                if (prevTemplateGenBusy && !busy && loadedForRowId == rowId) {
                    val t = templateDao.getById(rowId)
                    if (t != null) {
                        val current = _state.value
                        val previousSavedDraft = savedDraft
                        val persisted = current.withPersistedDraft(t).copy(isDirty = false)
                        savedDraft = persisted.toDraftSnapshot()
                        val updated = if (current.toDraftSnapshot() != previousSavedDraft) {
                            current.copy(
                                summary = if (current.summary == previousSavedDraft.summary) t.summary else current.summary,
                                worldPrompt = if (current.worldPrompt == previousSavedDraft.worldPrompt) t.worldPrompt else current.worldPrompt,
                                isPersisted = true,
                            )
                        } else {
                            persisted
                        }
                        currentEntity = t
                        _state.value = updated.copy(
                            isDirty = updated.toDraftSnapshot() != savedDraft,
                        )
                    }
                }
                prevTemplateGenBusy = busy
                _state.value = _state.value.copy(isAiCompleting = busy)
            }
        }
    }

    fun consumeSnackbar() {
        _state.value = _state.value.copy(snackbar = null)
    }

    fun syncPublicLlmKeyFromStorage() {
        _state.value = _state.value.copy(hasPublicLlmKey = secureStorage.publicApiKey.isNotBlank())
    }

    private fun showSnackbar(msg: String) {
        _state.value = _state.value.copy(snackbar = msg)
    }

    fun load(id: Long) {
        val effectiveId = when {
            id > 0L -> id
            loadedForRowId != null && loadedForRowId!! > 0L -> loadedForRowId!!
            else -> 0L
        }
        if (effectiveId > 0L && loadedForRowId == effectiveId && _state.value.isLoaded && currentEntity != null) {
            startTemplateQueueObservation(effectiveId)
            syncPublicLlmKeyFromStorage()
            return
        }
        loadedForRowId = effectiveId
        startTemplateQueueObservation(effectiveId)
        _state.value = _state.value.copy(isLoaded = false, loadError = null)
        viewModelScope.launch {
            try {
                val entity = if (effectiveId > 0) templateDao.getById(effectiveId) else null
                currentEntity = entity
                val loaded = when {
                    entity != null -> TemplateEditState(
                        label = entity.label,
                        templateId = entity.templateId,
                        category = entity.category,
                        summary = entity.summary,
                        gameplayMode = entity.gameplayMode,
                        worldPrompt = entity.worldPrompt,
                        antiCheatPrompt = entity.antiCheatPrompt,
                        suggestedChoicesJson = entity.suggestedChoicesJson,
                        coverImagePath = entity.coverImagePath,
                        isLoaded = true,
                        isPersisted = true,
                        hasPublicLlmKey = secureStorage.publicApiKey.isNotBlank(),
                    )
                    effectiveId <= 0L -> TemplateEditState(
                        templateId = UUID.randomUUID().toString().take(8),
                        isLoaded = true,
                        hasPublicLlmKey = secureStorage.publicApiKey.isNotBlank(),
                    )
                    else -> TemplateEditState(
                        isLoaded = true,
                        loadError = "找不到这个模板，它可能已经被删除",
                        hasPublicLlmKey = secureStorage.publicApiKey.isNotBlank(),
                    )
                }
                savedDraft = loaded.toDraftSnapshot()
                _state.value = loaded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentEntity = null
                val failed = TemplateEditState(
                    isLoaded = true,
                    loadError = "读取模板失败，请重试",
                    hasPublicLlmKey = secureStorage.publicApiKey.isNotBlank(),
                )
                savedDraft = failed.toDraftSnapshot()
                _state.value = failed
            }
        }
    }

    fun updateLabel(v: String) {
        updateDraft { it.copy(label = v) }
    }

    fun updateTemplateId(v: String) {
        updateDraft { it.copy(templateId = v) }
    }

    fun updateCategory(v: String) {
        updateDraft { it.copy(category = v) }
    }

    fun updateSummary(v: String) {
        updateDraft { it.copy(summary = v) }
    }

    fun updateGameplayMode(v: String) {
        updateDraft { it.copy(gameplayMode = v) }
    }

    fun updateWorldPrompt(v: String) {
        updateDraft { it.copy(worldPrompt = v) }
    }

    fun updateAntiCheatPrompt(v: String) {
        updateDraft { it.copy(antiCheatPrompt = v) }
    }

    fun updateSuggestedChoices(v: String) {
        updateDraft { it.copy(suggestedChoicesJson = v) }
    }

    fun updateCoverImage(v: String) {
        updateDraft { it.copy(coverImagePath = v) }
    }

    fun updateCoverBuiltinSearchQuery(v: String) {
        _state.value = _state.value.copy(coverBuiltinSearchQuery = v)
    }

    fun searchBuiltinCoverAssets() {
        if (secureStorage.publicBaseUrl.isBlank()) {
            showSnackbar("请先在设置填写本机服务地址")
            return
        }
        val q = _state.value.coverBuiltinSearchQuery
        viewModelScope.launch {
            _state.value = _state.value.copy(isCoverBuiltinLoading = true)
            val res = backendAssetsApi.listAssets("world_cover", q)
            res.fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(
                        isCoverBuiltinLoading = false,
                        coverBuiltinAssets = list,
                        snackbar = if (list.isEmpty()) "无匹配资源" else null,
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isCoverBuiltinLoading = false,
                        snackbar = UserFacingStrings.remoteRequestFailed(e),
                    )
                },
            )
        }
    }

    fun applyBuiltinCoverStoragePath(path: String) {
        if (path.isBlank()) return
        updateDraft {
            it.copy(
                coverImagePath = path,
                snackbar = "已选择内置封面，请保存",
            )
        }
    }

    /** 在浏览器中打开以下载单模板。 */
    fun exportThisTemplateDownloadUrl(): String? =
        backendWorldsApi.get().worldTemplateExportFileUrl(_state.value.templateId.trim())

    fun showExportTemplateUrlMissing() {
        showSnackbar("请先保存模板，并在设置中填写可访问的本机服务地址")
    }

    fun clearBackendQualityReport() {
        _state.value = _state.value.copy(backendQualityReport = null)
    }

    fun fetchFullBackendQualityReport() {
        val s = _state.value
        if (s.label.isBlank()) {
            showSnackbar(UserFacingStrings.templateLabelRequired())
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isBackendQualityLoading = true, backendQualityReport = null)
            val loreArr = JsonArray()
            val localId = currentEntity?.id
            if (localId != null && localId > 0L) {
                loreEntryDao.getByTemplate(localId).forEach { row ->
                    val o = JsonObject()
                    o.addProperty("title", row.title)
                    o.addProperty("entry_type", row.entryType)
                    o.add("keywords_json", JsonParser.parseString(row.keywordsJson.ifBlank { "[]" }))
                    o.addProperty("content", row.content)
                    o.addProperty("sort_order", row.sortOrder)
                    o.addProperty("is_core", row.isCore)
                    loreArr.add(o)
                }
            }
            val choicesArr = parseSuggestedChoicesArray(s.suggestedChoicesJson)
            val body = JsonObject().apply {
                addProperty("template_id", s.templateId.ifBlank { "preview" })
                addProperty("label", s.label)
                addProperty("category", s.category)
                addProperty("summary", s.summary)
                addProperty("gameplay_mode", s.gameplayMode)
                addProperty("world_prompt", s.worldPrompt)
                addProperty("cover_image_path", s.coverImagePath)
                addProperty("anti_cheat_prompt", s.antiCheatPrompt)
                add("suggested_choices", choicesArr)
                add("lore_entries", loreArr)
            }
            val result = backendWorldsApi.get().reviewWorldQualityJson(body)
            result.fold(
                onSuccess = { rep ->
                    _state.value = _state.value.copy(
                        isBackendQualityLoading = false,
                        backendQualityReport = rep,
                        snackbar = "已拉取完整质量报告",
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isBackendQualityLoading = false,
                        snackbar = UserFacingStrings.remoteRequestFailed(e),
                    )
                },
            )
        }
    }

    private fun parseSuggestedChoicesArray(json: String): JsonArray {
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) {
            return runCatching { JsonParser.parseString(trimmed).asJsonArray }.getOrElse { JsonArray() }
        }
        val arr = JsonArray()
        trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.add(it) }
        return arr
    }

    fun aiCompleteWorldPrompt() {
        val s = _state.value
        val rowId = loadedForRowId ?: run {
            showSnackbar("无法识别模板，请重新进入编辑页")
            return
        }
        if (rowId <= 0L) {
            showSnackbar("请先保存模板，再补全世界设定")
            return
        }
        if (s.isDirty) {
            showSnackbar("请先保存当前修改，再补全世界设定")
            return
        }
        if (s.label.isBlank()) {
            showSnackbar(UserFacingStrings.templateLabelRequired())
            return
        }
        if (secureStorage.publicApiKey.isBlank()) {
            showSnackbar(UserFacingStrings.llmKeyMissingForAiComplete())
            return
        }
        viewModelScope.launch {
            val stored = templateDao.getById(rowId)
            if (stored == null) {
                showSnackbar("模板不存在，请保存后重试")
                return@launch
            }
            _state.value = _state.value.copy(isAiCompleting = true)
            generationQueueProcessor.enqueueWorldTemplatePromptAi(
                WorldTemplatePromptAiPayload(
                    templateRowId = rowId,
                    label = s.label,
                    category = s.category,
                    summary = s.summary,
                    worldPrompt = s.worldPrompt,
                    gameplayMode = s.gameplayMode,
                    extraContext = "${s.category}风格的${s.label}世界设定",
                    contextEncyclopediaId = secureStorage.defaultEncyclopediaIdForAi.takeIf { it > 0L },
                    expectedSummary = stored.summary,
                    expectedWorldPrompt = stored.worldPrompt,
                ),
            )
            showSnackbar("已加入队列，完成后自动写入")
        }
    }

    fun save() {
        val s = _state.value
        if (s.isSaving || s.isAiCompleting || !s.isLoaded || s.loadError != null) return
        if (s.label.isBlank()) {
            showSnackbar(UserFacingStrings.templateLabelRequired())
            return
        }
        if (currentEntity == null && s.templateId.isBlank()) {
            showSnackbar(UserFacingStrings.templateIdRequired())
            return
        }
        val submittedDraft = s.toDraftSnapshot()
        _state.value = s.copy(isSaving = true)
        viewModelScope.launch {
            try {
                val toSave = (currentEntity ?: WorldTemplateEntity()).copy(
                    templateId = s.templateId.trim(),
                    label = s.label.trim(),
                    category = s.category,
                    summary = s.summary,
                    gameplayMode = s.gameplayMode,
                    worldPrompt = s.worldPrompt,
                    antiCheatPrompt = s.antiCheatPrompt,
                    suggestedChoicesJson = s.suggestedChoicesJson,
                    coverImagePath = s.coverImagePath,
                )
                val savedId = templateDao.upsert(toSave)
                val saved = templateDao.getById(savedId)
                    ?: templateDao.getByTemplateId(s.templateId.trim())
                if (saved == null || saved.id <= 0L) {
                    _state.value = _state.value.copy(
                        isSaving = false,
                        snackbar = UserFacingStrings.localSaveFailed("模板"),
                    )
                    return@launch
                }
                currentEntity = saved
                loadedForRowId = saved.id
                startTemplateQueueObservation(saved.id)
                val current = _state.value
                val persisted = current.withPersistedDraft(saved).copy(
                    isSaving = false,
                    isDirty = false,
                )
                savedDraft = persisted.toDraftSnapshot()
                val draftChangedWhileSaving = current.toDraftSnapshot() != submittedDraft
                _state.value = if (draftChangedWhileSaving) {
                    current.copy(
                        isSaving = false,
                        isPersisted = true,
                        isDirty = current.toDraftSnapshot() != savedDraft,
                        snackbar = "已保存，当前还有未保存的修改",
                    )
                } else {
                    persisted.copy(snackbar = UserFacingStrings.saveSuccessGeneric())
                }
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(isSaving = false)
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    isSaving = false,
                    snackbar = UserFacingStrings.localSaveFailed("模板"),
                )
            }
        }
    }
}

package com.mojing.app.ui.character

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.CharacterProfileDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.CharacterProfileEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.remote.BackendSystemProbeApi
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.data.repository.ImageRepository
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.generation.CharacterPersonaAiPayload
import com.mojing.app.data.local.entity.GenerationTaskStatus
import com.mojing.app.domain.generation.GenerationQueueProcessor
import com.mojing.app.domain.util.CharacterCardPngCodec
import com.mojing.app.domain.util.CharacterCardV2Converter
import com.mojing.app.domain.util.CharacterPortableCodec
import com.mojing.app.domain.util.SimpleDocxWriter
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import com.mojing.app.media.BuiltinCharacterImages
import com.mojing.app.ui.common.ApiBasePlaceholder
import com.mojing.app.ui.common.ProbeUiMessages
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.util.ApiRootLines
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.text.Charsets
import java.io.File
import javax.inject.Inject

data class CharacterEditState(
    val name: String = "",
    val personaPrompt: String = "",
    val apiKey: String = "",
    val apiBaseUrl: String = "",
    val modelName: String = "",
    val temperature: String = "0.9",
    val maxTokens: String = "1200",
    val topP: String = "1.0",
    val frequencyPenalty: String = "0.0",
    val presencePenalty: String = "0.0",
    val avatarColor: String = "#F97316",
    val avatarImagePath: String = "",
    val cardImagePath: String = "",
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val isAiCompleting: Boolean = false,
    val isRefreshingPersona: Boolean = false,
    val personaRefreshError: String? = null,
    val isLoaded: Boolean = false,
    val loadError: String? = null,
    val isPersisted: Boolean = false,
    val isDirty: Boolean = false,
    val isPreparingExport: Boolean = false,
    val isExportingSummary: Boolean = false,
    val exportMessage: String? = null,
    val hasPublicTextKey: Boolean = false,
    /** null | "text" | "image" | "voice" — 经本机后端流式探测 */
    val probeBusyChannel: String? = null,
    /** API 探测流式提示（与设置页同源 NDJSON） */
    val probeStreamHint: String = "",
    val probeStreamLines: List<String> = emptyList(),
    /** 一次性 Snackbar，展示后由界面 consume */
    val snackbar: String? = null,
    /** 标准扩展卡 JSON（含内嵌设定片段等），与 `character_profiles.character_card_json` 同步 */
    val characterCardJsonRaw: String = "{}",
    /** 角色级始终走思考/Max 线路（与后端 `think_max_enabled` 对齐） */
    val thinkMaxEnabled: Boolean = false,
    /** 思考模型 id 覆盖；空则用设置中的全局思考模型 */
    val thinkMaxModelName: String = "",
    val imageGenEnabled: Boolean = false,
    val imageGenApiKey: String = "",
    val imageGenBaseUrl: String = "",
    val imageGenModel: String = "",
    val voiceProvider: String = "",
    val voiceApiBaseUrl: String = "",
    val voiceApiKey: String = "",
    val voiceModel: String = "system",
    val isGeneratingCardImage: Boolean = false,
    /** 绑定百科后，会话选该库时仅可选这些角色；并在百科中镜像一条「角色」条目 */
    val boundEncyclopediaId: Long = 0L,
    val encyclopediaOptions: List<EncyclopediaEntity> = emptyList(),
)

internal fun CharacterEditState.samplingError(): String? = listOf(
    "温度" to samplingParameterError(temperature),
    "最大 Token" to samplingParameterError(maxTokens, integer = true),
    "Top P" to samplingParameterError(topP),
    "频率惩罚" to samplingParameterError(frequencyPenalty),
    "存在惩罚" to samplingParameterError(presencePenalty),
).firstOrNull { it.second != null }?.let { "${it.first}：${it.second}" }

private data class CharacterDraftSnapshot(
    val name: String,
    val personaPrompt: String,
    val apiKey: String,
    val apiBaseUrl: String,
    val modelName: String,
    val temperature: String,
    val maxTokens: String,
    val topP: String,
    val frequencyPenalty: String,
    val presencePenalty: String,
    val avatarColor: String,
    val avatarImagePath: String,
    val cardImagePath: String,
    val characterCardJsonRaw: String,
    val thinkMaxEnabled: Boolean,
    val thinkMaxModelName: String,
    val imageGenEnabled: Boolean,
    val imageGenApiKey: String,
    val imageGenBaseUrl: String,
    val imageGenModel: String,
    val voiceProvider: String,
    val voiceApiBaseUrl: String,
    val voiceApiKey: String,
    val voiceModel: String,
    val boundEncyclopediaId: Long,
)

private fun CharacterEditState.toDraftSnapshot() = CharacterDraftSnapshot(
    name = name,
    personaPrompt = personaPrompt,
    apiKey = apiKey,
    apiBaseUrl = apiBaseUrl,
    modelName = modelName,
    temperature = temperature,
    maxTokens = maxTokens,
    topP = topP,
    frequencyPenalty = frequencyPenalty,
    presencePenalty = presencePenalty,
    avatarColor = avatarColor,
    avatarImagePath = avatarImagePath,
    cardImagePath = cardImagePath,
    characterCardJsonRaw = characterCardJsonRaw,
    thinkMaxEnabled = thinkMaxEnabled,
    thinkMaxModelName = thinkMaxModelName,
    imageGenEnabled = imageGenEnabled,
    imageGenApiKey = imageGenApiKey,
    imageGenBaseUrl = imageGenBaseUrl,
    imageGenModel = imageGenModel,
    voiceProvider = voiceProvider,
    voiceApiBaseUrl = voiceApiBaseUrl,
    voiceApiKey = voiceApiKey,
    voiceModel = voiceModel,
    boundEncyclopediaId = boundEncyclopediaId,
)

private fun CharacterEditState.withPersistedDraft(
    entity: CharacterEntity,
    profileJson: String,
) = copy(
    name = entity.name,
    personaPrompt = entity.personaPrompt,
    apiKey = entity.apiKey,
    apiBaseUrl = entity.apiBaseUrl,
    modelName = entity.modelName,
    temperature = entity.temperature.toString(),
    maxTokens = entity.maxTokens.toString(),
    topP = entity.topP.toString(),
    frequencyPenalty = entity.frequencyPenalty.toString(),
    presencePenalty = entity.presencePenalty.toString(),
    avatarColor = entity.avatarColor,
    avatarImagePath = entity.avatarImagePath,
    cardImagePath = entity.cardImagePath,
    characterCardJsonRaw = profileJson,
    thinkMaxEnabled = entity.thinkMaxEnabled,
    thinkMaxModelName = entity.thinkMaxModelName,
    imageGenEnabled = entity.imageGenEnabled,
    imageGenApiKey = entity.imageGenApiKey,
    imageGenBaseUrl = entity.imageGenBaseUrl,
    imageGenModel = entity.imageGenModel,
    voiceProvider = entity.voiceProvider,
    voiceApiBaseUrl = entity.voiceApiBaseUrl,
    voiceApiKey = entity.voiceApiKey,
    voiceModel = entity.voiceModel.ifBlank { "system" },
    boundEncyclopediaId = entity.boundEncyclopediaId,
    isLoaded = true,
    loadError = null,
    isPersisted = true,
)

enum class PortableExportFormat {
    JSON, TXT, DOCX
}

@HiltViewModel
class CharacterEditViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val characterDao: CharacterDao,
    private val characterProfileDao: CharacterProfileDao,
    private val encyclopediaDao: EncyclopediaDao,
    private val saveCharacterBinding: SaveCharacterBindingUseCase,
    private val secureStorage: SecureStorage,
    private val generationQueueProcessor: GenerationQueueProcessor,
    private val llmRetry: LlmRetry,
    private val systemProbeApi: BackendSystemProbeApi,
    private val imageRepository: ImageRepository,
    private val llmApiService: LlmApiService,
) : ViewModel() {
    private val _state = MutableStateFlow(CharacterEditState())
    val state: StateFlow<CharacterEditState> = _state.asStateFlow()

    private var currentEntity: CharacterEntity? = null
    private var lastLoadedCharacterId: Long? = null
    private var genObserveJob: Job? = null
    private var personaWatchdogJob: Job? = null
    private var personaReadRevision = 0L
    private var prevCharacterGenBusy = false
    private var savedDraft = _state.value.toDraftSnapshot()

    private fun updateDraft(transform: (CharacterEditState) -> CharacterEditState) {
        val next = transform(_state.value)
        _state.value = next.copy(isDirty = next.saveError != null || next.toDraftSnapshot() != savedDraft)
    }

    private fun startCharacterQueueObservation(id: Long) {
        genObserveJob?.cancel()
        genObserveJob = null
        personaWatchdogJob?.cancel()
        personaWatchdogJob = null
        if (id <= 0L) return
        prevCharacterGenBusy = false
        genObserveJob = viewModelScope.launch {
            generationQueueProcessor.observeActiveForCharacter(id).collectLatest { list ->
                val busy = list.isNotEmpty()
                if (lastLoadedCharacterId != id) return@collectLatest
                val shouldRefresh = prevCharacterGenBusy && !busy
                if (busy) personaReadRevision++
                if (busy && !prevCharacterGenBusy) {
                    personaWatchdogJob?.cancel()
                    personaWatchdogJob = viewModelScope.launch {
                        delay(PERSONA_UI_WATCHDOG_MS)
                        if (_state.value.isAiCompleting && lastLoadedCharacterId == id) {
                            showSnackbar(UserFacingStrings.characterAiPersonaQueuedLong())
                        }
                    }
                } else if (!busy) {
                    personaWatchdogJob?.cancel()
                    personaWatchdogJob = null
                }
                prevCharacterGenBusy = busy
                _state.value = _state.value.copy(isAiCompleting = busy, isRefreshingPersona = false)
                if (shouldRefresh) {
                    _state.value = _state.value.copy(isRefreshingPersona = true)
                    refreshPersonaResult(id)
                }
            }
        }
    }

    fun retryPersonaRefresh() {
        val id = lastLoadedCharacterId ?: return
        val s = _state.value
        if (s.personaRefreshError == null || s.isRefreshingPersona || s.isAiCompleting || s.isSaving) return
        _state.value = s.copy(isRefreshingPersona = true)
        viewModelScope.launch { refreshPersonaResult(id) }
    }

    private suspend fun refreshPersonaResult(id: Long) {
        val revision = ++personaReadRevision
        try {
            val entity = characterDao.getById(id) ?: throw IllegalStateException("Character missing")
            if (lastLoadedCharacterId != id || revision != personaReadRevision || _state.value.isAiCompleting) return
            val current = _state.value
            val previous = savedDraft
            val persisted = current.withPersistedDraft(entity, previous.characterCardJsonRaw)
            val manuallyEdited = current.personaPrompt != previous.personaPrompt
            val updated = if (current.toDraftSnapshot() != previous) {
                current.copy(personaPrompt = if (manuallyEdited) current.personaPrompt else entity.personaPrompt, isPersisted = true)
            } else persisted
            currentEntity = entity
            savedDraft = persisted.toDraftSnapshot()
            _state.value = updated.copy(personaRefreshError = null,
                isDirty = updated.saveError != null || updated.toDraftSnapshot() != savedDraft)
            if (manuallyEdited) showSnackbar("补全已结束，手动修改的人设已保留")
            else {
                try { notifyPersonaTaskFinished(id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Result is already loaded; task feedback is optional. */ }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (lastLoadedCharacterId == id && revision == personaReadRevision) {
                _state.value = _state.value.copy(personaRefreshError = "暂时无法读取补全结果，编辑内容已保留。请重新读取后继续保存。")
            }
        } finally {
            if (lastLoadedCharacterId == id && revision == personaReadRevision) {
                _state.value = _state.value.copy(isRefreshingPersona = false)
            }
        }
    }

    private suspend fun notifyPersonaTaskFinished(characterId: Long) {
        val task = generationQueueProcessor.getLatestPersonaTaskForCharacter(characterId) ?: return
        if (lastLoadedCharacterId != characterId || _state.value.isAiCompleting) return
        when (task.status) {
            GenerationTaskStatus.COMPLETED -> {
                if (task.errorMessage.trim() == "人设未变化") {
                    showSnackbar(UserFacingStrings.characterAiNoNewPersona())
                } else {
                    showSnackbar(UserFacingStrings.characterAiPersonaApplied())
                }
            }
            GenerationTaskStatus.FAILED ->
                showSnackbar(UserFacingStrings.characterAiPersonaFailed(task.errorMessage))
            GenerationTaskStatus.CANCELLED ->
                showSnackbar("人设补全已取消")
        }
    }

    private companion object {
        const val PERSONA_UI_WATCHDOG_MS = 180_000L
    }

    fun load(id: Long) {
        viewModelScope.launch {
            val current = _state.value
            val alreadyLoaded = current.isLoaded && lastLoadedCharacterId == id &&
                ((id > 0L && currentEntity != null) || (id <= 0L && current.loadError == null))
            if (alreadyLoaded) {
                if (genObserveJob?.isActive != true) startCharacterQueueObservation(id)
                return@launch
            }
            genObserveJob?.cancel()
            personaWatchdogJob?.cancel()
            lastLoadedCharacterId = id
            _state.value = current.copy(isLoaded = false, loadError = null)
            try {
                val entity = if (id > 0L) characterDao.getById(id) else null
                val encs = encyclopediaDao.getAll()
                currentEntity = entity
                val loaded = when {
                    entity != null -> {
                        val profileJson = characterProfileDao.getByCharacter(entity.id)?.characterCardJson?.trim().orEmpty()
                            .ifBlank { "{}" }
                        CharacterEditState(
                            hasPublicTextKey = secureStorage.publicApiKey.isNotBlank(),
                            encyclopediaOptions = encs,
                        ).withPersistedDraft(entity, profileJson).copy(isDirty = false)
                    }
                    id <= 0L -> CharacterEditState(
                        isLoaded = true,
                        hasPublicTextKey = secureStorage.publicApiKey.isNotBlank(),
                        encyclopediaOptions = encs,
                    )
                    else -> CharacterEditState(
                        isLoaded = true,
                        loadError = "找不到这个角色，它可能已经被删除",
                        hasPublicTextKey = secureStorage.publicApiKey.isNotBlank(),
                        encyclopediaOptions = encs,
                    )
                }
                savedDraft = loaded.toDraftSnapshot()
                _state.value = loaded
                startCharacterQueueObservation(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentEntity = null
                val failed = CharacterEditState(
                    isLoaded = true,
                    loadError = "读取角色失败，请重试",
                    hasPublicTextKey = secureStorage.publicApiKey.isNotBlank(),
                )
                savedDraft = failed.toDraftSnapshot()
                _state.value = failed
            }
        }
    }

    fun updateName(v: String) = updateDraft { it.copy(name = v) }
    fun updateBoundEncyclopediaId(v: Long) = updateDraft { it.copy(boundEncyclopediaId = v) }
    fun updatePersonaPrompt(v: String) = updateDraft { it.copy(personaPrompt = v) }
    fun updateApiKey(v: String) = updateDraft { it.copy(apiKey = v) }
    fun updateApiBaseUrl(v: String) = updateDraft { it.copy(apiBaseUrl = v) }
    fun updateModelName(v: String) = updateDraft { it.copy(modelName = v) }
    fun updateThinkMaxEnabled(v: Boolean) = updateDraft { it.copy(thinkMaxEnabled = v) }
    fun updateThinkMaxModelName(v: String) = updateDraft { it.copy(thinkMaxModelName = v) }
    fun updateImageGenEnabled(v: Boolean) = updateDraft { it.copy(imageGenEnabled = v) }
    fun updateImageGenApiKey(v: String) = updateDraft { it.copy(imageGenApiKey = v) }
    fun updateImageGenBaseUrl(v: String) = updateDraft { it.copy(imageGenBaseUrl = v) }
    fun updateImageGenModel(v: String) = updateDraft { it.copy(imageGenModel = v) }
    fun updateVoiceProvider(v: String) = updateDraft { it.copy(voiceProvider = v) }
    fun updateVoiceApiBaseUrl(v: String) = updateDraft { it.copy(voiceApiBaseUrl = v) }
    fun updateVoiceApiKey(v: String) = updateDraft { it.copy(voiceApiKey = v) }
    fun updateVoiceModel(v: String) = updateDraft { it.copy(voiceModel = v) }
    fun updateTemperature(v: String) = updateDraft { it.copy(temperature = v) }
    fun updateMaxTokens(v: String) = updateDraft { it.copy(maxTokens = v) }
    fun updateTopP(v: String) = updateDraft { it.copy(topP = v) }
    fun updateFrequencyPenalty(v: String) = updateDraft { it.copy(frequencyPenalty = v) }
    fun updatePresencePenalty(v: String) = updateDraft { it.copy(presencePenalty = v) }
    fun updateAvatarColor(v: String) = updateDraft { it.copy(avatarColor = v) }
    /**
     * 若竖屏封面与头像曾指向同一文件，改头像前先复制一份给封面，避免「换头像封面跟着变」。
     */
    fun updateAvatarImagePath(v: String) {
        val s = _state.value
        if (v.isBlank()) {
            updateDraft { it.copy(avatarImagePath = "") }
            return
        }
        val splitCard = s.cardImagePath.isNotBlank() &&
            s.avatarImagePath.isNotBlank() &&
            s.cardImagePath == s.avatarImagePath &&
            v != s.avatarImagePath
        if (!splitCard) {
            updateDraft { it.copy(avatarImagePath = v) }
            return
        }
        viewModelScope.launch {
            val forked = withContext(Dispatchers.IO) { forkCharacterImageToOwnCardFile(s.avatarImagePath) }
            updateDraft {
                it.copy(
                    avatarImagePath = v,
                    cardImagePath = forked ?: it.cardImagePath,
                )
            }
        }
    }

    private fun forkCharacterImageToOwnCardFile(srcPath: String): String? =
        runCatching {
            val src = File(srcPath.trim())
            if (!src.exists() || src.length() == 0L) return@runCatching null
            val ext = when {
                srcPath.endsWith(".png", true) -> ".png"
                srcPath.endsWith(".webp", true) -> ".webp"
                else -> ".jpg"
            }
            val dest = File(appContext.filesDir, "character_card_fork_${System.currentTimeMillis()}$ext")
            src.copyTo(dest, overwrite = true)
            dest.absolutePath
        }.getOrNull()
    fun updateCardImagePath(v: String) = updateDraft { it.copy(cardImagePath = v) }
    fun updateCharacterCardJsonRaw(v: String) = updateDraft { it.copy(characterCardJsonRaw = v) }

    /** 复制头像文件为独立卡图，避免日后改头像连带改卡图 */
    fun duplicateAvatarToCardImage() {
        val avatar = _state.value.avatarImagePath
        if (avatar.isBlank()) {
            showSnackbar("请先设置头像或上传头像图片")
            return
        }
        viewModelScope.launch {
            val newPath = withContext(Dispatchers.IO) {
                runCatching {
                    val src = File(avatar)
                    if (!src.exists()) return@runCatching null
                    val ext = if (avatar.endsWith(".png", true)) ".png" else ".jpg"
                    val dest = File(appContext.filesDir, "character_card_copy_${System.currentTimeMillis()}$ext")
                    src.copyTo(dest, overwrite = true)
                    dest.absolutePath
                }.getOrNull()
            }
            if (newPath != null) {
                updateDraft { it.copy(cardImagePath = newPath) }
                showSnackbar("已复制为竖版封面，记得保存")
            } else {
                showSnackbar("复制失败，请检查头像文件是否存在")
            }
        }
    }

    fun applyBuiltinAvatarFromPreset(assetFileName: String) {
        viewModelScope.launch {
            val name = assetFileName.trim()
            if (name.isEmpty()) return@launch
            val path = withContext(Dispatchers.IO) {
                BuiltinCharacterImages.copyPresetAssetToFilesDir(appContext, name)
            }
            if (path != null) {
                val cur = _state.value
                val needFork = cur.cardImagePath.isNotBlank() && cur.avatarImagePath.isNotBlank() &&
                    cur.cardImagePath == cur.avatarImagePath
                val forked = if (needFork) {
                    withContext(Dispatchers.IO) { forkCharacterImageToOwnCardFile(cur.avatarImagePath) }
                } else {
                    null
                }
                updateDraft {
                    it.copy(
                        avatarImagePath = path,
                        cardImagePath = forked ?: it.cardImagePath,
                        snackbar = "已设为头像，请保存",
                    )
                }
            } else {
                showSnackbar("设置失败")
            }
        }
    }

    fun applyBuiltinCardFromPreset(assetFileName: String) {
        viewModelScope.launch {
            val name = assetFileName.trim()
            if (name.isEmpty()) return@launch
            val path = withContext(Dispatchers.IO) {
                BuiltinCharacterImages.copyPresetAssetToFilesDir(appContext, name)
            }
            if (path != null) {
                updateDraft { it.copy(cardImagePath = path, snackbar = "已设为封面，请保存") }
            } else {
                showSnackbar("设置失败")
            }
        }
    }

    fun clearExportMessage() {
        _state.value = _state.value.copy(exportMessage = null)
    }

    fun consumeSnackbar() {
        _state.value = _state.value.copy(snackbar = null)
    }

    private fun showSnackbar(msg: String) {
        _state.value = _state.value.copy(snackbar = msg)
    }

    /** 与聊天侧一致：占位 Base 视为走公共线路 */
    private fun usesPublicLlmRoute(apiBaseUrl: String): Boolean =
        ApiBasePlaceholder.isPlaceholderApiBase(apiBaseUrl)

    /**
     * 角色卡里填了与设置「公共根」相同的网关（非占位）时，仍应按公共文本线路解析模型与多行备选根，
     * 避免沿用卡上默认 model id 与硅基等网关不匹配。
     */
    private fun characterBaseMatchesStoredPublicGateway(characterBaseRaw: String): Boolean {
        val cNorm = llmApiService.normalizeOpenAiCompatibleBase(characterBaseRaw).trim()
        if (cNorm.isEmpty()) return false
        val publicRaw = secureStorage.publicBaseUrl.trim()
        if (publicRaw.isEmpty()) return false
        val candidates = ApiRootLines.splitToOrderedDistinct(publicRaw, llmApiService::normalizeOpenAiCompatibleBase)
        return candidates.any { it.equals(cNorm, ignoreCase = true) }
    }

    fun aiCompletePersona() {
        val s = _state.value
        if (s.isSaving || s.isAiCompleting || s.isRefreshingPersona || s.personaRefreshError != null) return
        if (!s.isPersisted) {
            showSnackbar("请先保存角色，再补全人设")
            return
        }
        if (s.isDirty) {
            showSnackbar("请先保存当前修改，再补全人设")
            return
        }
        val cid = lastLoadedCharacterId
        if (cid == null || cid <= 0L) {
            showSnackbar("无法识别角色，请从列表重新进入编辑页")
            return
        }
        val apiKey = s.apiKey.ifBlank { secureStorage.publicApiKey }
        if (apiKey.isBlank()) {
            showSnackbar(UserFacingStrings.llmKeyMissingForAiComplete())
            return
        }
        viewModelScope.launch {
            if (characterDao.getById(cid) == null) {
                showSnackbar("角色资料已不存在，请返回角色列表重试")
                return@launch
            }
            if (generationQueueProcessor.hasActivePersonaForCharacter(cid)) {
                showSnackbar("该角色已有人设任务在队列中")
                return@launch
            }
            _state.value = _state.value.copy(isAiCompleting = true)
            val publicRoute =
                usesPublicLlmRoute(s.apiBaseUrl) || characterBaseMatchesStoredPublicGateway(s.apiBaseUrl)
            // 走公共网关时必须带设置里的公共模型 id，避免角色卡默认 deepseek-chat 等与硅基等网关不匹配
            val queueModel = if (publicRoute) {
                secureStorage.publicModel.trim().ifBlank { s.modelName.trim() }
            } else {
                s.modelName.trim().ifBlank { secureStorage.publicModel.trim() }
            }
            generationQueueProcessor.enqueueCharacterPersonaAi(
                CharacterPersonaAiPayload(
                    characterId = cid,
                    name = s.name,
                    personaPrompt = s.personaPrompt,
                    apiKey = s.apiKey,
                    baseUrl = if (publicRoute) "" else s.apiBaseUrl.trim(),
                    model = queueModel,
                    contextEncyclopediaId = s.boundEncyclopediaId.takeIf { it > 0L }
                        ?: secureStorage.defaultEncyclopediaIdForAi.takeIf { it > 0L },
                ),
            )
            showSnackbar("已加入队列，完成后自动写入")
        }
    }

    fun save(routeCharacterId: Long, onSaved: () -> Unit = {}) {
        val submittedState = _state.value
        if (submittedState.isSaving || submittedState.isAiCompleting || submittedState.isRefreshingPersona || submittedState.personaRefreshError != null || !submittedState.isLoaded || submittedState.loadError != null) return
        val submittedDraft = submittedState.toDraftSnapshot()
        submittedState.samplingError()?.let {
            showSnackbar(it)
            return
        }
        if (submittedState.name.isBlank()) {
            showSnackbar(UserFacingStrings.characterNameRequired())
            return
        }
        val rawJson = submittedState.characterCardJsonRaw.trim()
        if (rawJson.isNotBlank()) {
            val parsed = runCatching { JsonParser.parseString(rawJson) }.getOrNull()
            if (parsed == null || !parsed.isJsonObject) {
                showSnackbar("扩展设定 JSON 须为对象 {…}，请修正后重试")
                return
            }
        }
        val profileJsonForStorage = rawJson.ifBlank { "{}" }
        val previousProfileJson = savedDraft.characterCardJsonRaw
        _state.value = _state.value.copy(isSaving = true, saveError = null)
        viewModelScope.launch {
            var canLeaveAfterSave = false
            var characterSaved = false
            var savedEntity: CharacterEntity? = null
            try {
                val base = currentEntity
                    ?: (if (routeCharacterId != 0L) characterDao.getById(routeCharacterId) else null)
                    ?: CharacterEntity()
                val toSave = base.copy(
                    name = submittedState.name,
                    personaPrompt = submittedState.personaPrompt,
                    apiKey = submittedState.apiKey,
                    apiBaseUrl = submittedState.apiBaseUrl,
                    modelName = submittedState.modelName,
                    temperature = submittedState.temperature.trim().toFloat(),
                    maxTokens = submittedState.maxTokens.trim().toInt(),
                    topP = submittedState.topP.trim().toFloat(),
                    frequencyPenalty = submittedState.frequencyPenalty.trim().toFloat(),
                    presencePenalty = submittedState.presencePenalty.trim().toFloat(),
                    avatarColor = submittedState.avatarColor,
                    avatarImagePath = submittedState.avatarImagePath,
                    cardImagePath = submittedState.cardImagePath,
                    thinkMaxEnabled = submittedState.thinkMaxEnabled,
                    thinkMaxModelName = submittedState.thinkMaxModelName,
                    imageGenEnabled = submittedState.imageGenEnabled,
                    imageGenApiKey = submittedState.imageGenApiKey,
                    imageGenBaseUrl = submittedState.imageGenBaseUrl,
                    imageGenModel = submittedState.imageGenModel,
                    voiceProvider = submittedState.voiceProvider,
                    voiceApiBaseUrl = submittedState.voiceApiBaseUrl,
                    voiceApiKey = submittedState.voiceApiKey,
                    voiceModel = submittedState.voiceModel,
                    boundEncyclopediaId = submittedState.boundEncyclopediaId,
                    updatedAt = System.currentTimeMillis(),
                )
                val effectiveId = saveCharacterBinding(toSave)
                characterSaved = true
                savedEntity = toSave.copy(id = effectiveId)
                currentEntity = savedEntity
                lastLoadedCharacterId = effectiveId
                val persistedEntity = checkNotNull(characterDao.getById(effectiveId)) { "角色保存后无法读取" }
                savedEntity = persistedEntity
                val profExisting = characterProfileDao.getByCharacter(effectiveId)
                characterProfileDao.upsert(
                    (profExisting ?: CharacterProfileEntity(characterId = effectiveId)).copy(
                        characterCardJson = profileJsonForStorage,
                        extractedAt = System.currentTimeMillis(),
                    ),
                )
                currentEntity = persistedEntity
                lastLoadedCharacterId = effectiveId
                startCharacterQueueObservation(effectiveId)

                val latest = _state.value
                val persisted = latest.withPersistedDraft(persistedEntity, profileJsonForStorage)
                    .copy(isSaving = false, isDirty = false)
                savedDraft = persisted.toDraftSnapshot()
                val hasConcurrentChanges = latest.toDraftSnapshot() != submittedDraft
                val result = if (hasConcurrentChanges) {
                    latest.copy(
                        isSaving = false,
                        isPersisted = true,
                        snackbar = "已保存，当前还有未保存的修改",
                    )
                } else {
                    persisted.copy(snackbar = UserFacingStrings.saveSuccessGeneric())
                }
                _state.value = result.copy(isDirty = result.toDraftSnapshot() != savedDraft)
                canLeaveAfterSave = !_state.value.isDirty
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(isSaving = false)
                throw cancelled
            } catch (_: Exception) {
                val latest = _state.value
                if (characterSaved && savedEntity != null) {
                    val persistedEntity = savedEntity
                    currentEntity = persistedEntity
                    lastLoadedCharacterId = persistedEntity.id
                    startCharacterQueueObservation(persistedEntity.id)
                    val persisted = latest.withPersistedDraft(persistedEntity, previousProfileJson)
                        .copy(isSaving = false, isDirty = false)
                    savedDraft = persisted.toDraftSnapshot()
                    val result = latest.copy(
                        isSaving = false,
                        isPersisted = true,
                        snackbar = "角色已保存，资料更新未完成，请重试",
                        saveError = "角色已保存，资料更新未完成。再次保存会更新同一角色。",
                    )
                    _state.value = result.copy(isDirty = true)
                } else {
                    _state.value = latest.copy(isSaving = false, saveError = "保存失败，编辑内容已保留，请重试。", snackbar = "保存失败，请重试")
                }
            }
            if (canLeaveAfterSave) onSaved()
        }
    }

    private suspend fun mergedEntityForExport(routeCharacterId: Long): CharacterEntity? {
        val s = _state.value
        s.samplingError()?.let { throw IllegalArgumentException(it) }
        val base = currentEntity
            ?: (if (routeCharacterId != 0L) characterDao.getById(routeCharacterId) else null)
            ?: return null
        return base.copy(
            name = s.name,
            personaPrompt = s.personaPrompt,
            apiBaseUrl = s.apiBaseUrl,
            modelName = s.modelName,
            temperature = s.temperature.trim().toFloat(),
            maxTokens = s.maxTokens.trim().toInt(),
            topP = s.topP.trim().toFloat(),
            frequencyPenalty = s.frequencyPenalty.trim().toFloat(),
            presencePenalty = s.presencePenalty.trim().toFloat(),
            avatarColor = s.avatarColor,
            avatarImagePath = s.avatarImagePath,
            cardImagePath = s.cardImagePath,
            thinkMaxEnabled = s.thinkMaxEnabled,
            thinkMaxModelName = s.thinkMaxModelName,
            imageGenEnabled = s.imageGenEnabled,
            imageGenApiKey = s.imageGenApiKey,
            imageGenBaseUrl = s.imageGenBaseUrl,
            imageGenModel = s.imageGenModel,
            voiceProvider = s.voiceProvider,
            voiceApiBaseUrl = s.voiceApiBaseUrl,
            voiceApiKey = s.voiceApiKey,
            voiceModel = s.voiceModel,
            boundEncyclopediaId = s.boundEncyclopediaId,
        )
    }

    private fun collectDump(entity: CharacterEntity, profile: CharacterProfileEntity?): String = buildString {
        appendLine("名称: ${entity.name}")
        appendLine("性格设定(persona_prompt):\n${entity.personaPrompt}")
        appendLine("模型: ${entity.modelName}\nBase: ${entity.apiBaseUrl}")
        if (profile != null) {
            appendLine("原始设定文件名: ${profile.sourceFilename}")
            if (profile.rawPersonaText.isNotBlank()) {
                appendLine("原始设定文本:\n${profile.rawPersonaText.take(80000)}")
            }
            if (profile.characterCardMarkdown.isNotBlank()) {
                appendLine("人物卡 Markdown:\n${profile.characterCardMarkdown.take(20000)}")
            }
        }
    }

    /**
     * 生成便携包字节与建议文件名（不含密钥）。
     */
    fun buildPortableExport(
        routeCharacterId: Long,
        format: PortableExportFormat,
        summary: Boolean,
        onResult: (Pair<String, ByteArray>?) -> Unit
    ) {
        if (_state.value.isPreparingExport) return
        _state.value = _state.value.copy(
            exportMessage = null,
            isPreparingExport = true,
            isExportingSummary = summary,
        )
        viewModelScope.launch {
            var result: Pair<String, ByteArray>? = null
            var shouldReportResult = true
            try {
                val entityRaw = mergedEntityForExport(routeCharacterId) ?: run {
                    _state.update { it.copy(exportMessage = UserFacingStrings.exportCharacterNotLoaded()) }
                    return@launch
                }
                val profile = characterProfileDao.getByCharacter(entityRaw.id)
                val payloadJson: JsonObject = if (summary) {
                    val apiKey = secureStorage.publicApiKey
                    if (apiKey.isBlank()) {
                        _state.update { it.copy(exportMessage = UserFacingStrings.exportSummaryNeedsGlobalKey()) }
                        return@launch
                    }
                    val dump = withContext(Dispatchers.Default) { collectDump(entityRaw, profile) }
                    val raw = llmRetry.chatCompletionWithRetry(
                        apiKey = apiKey,
                        baseUrl = secureStorage.publicBaseUrl,
                        model = secureStorage.publicModel.ifBlank { "deepseek-chat" },
                        messages = listOf(
                            ChatMessage("system", CharacterPortableCodec.SUMMARY_SYSTEM_PROMPT),
                            ChatMessage("user", CharacterPortableCodec.summaryUserPayload(dump)),
                        ),
                        temperature = 0.4f,
                        maxTokens = 8000,
                    )
                    withContext(Dispatchers.Default) {
                        val parsed = CharacterPortableCodec.extractJsonFromLlmResponse(raw)
                        CharacterPortableCodec.mergeSummaryIntoPortable(parsed)
                    }
                } else {
                    withContext(Dispatchers.Default) {
                        CharacterPortableCodec.buildPayload(entityRaw, profile)
                    }
                }
                result = withContext(Dispatchers.Default) {
                    val gson = com.google.gson.Gson()
                    val baseName = (entityRaw.name.ifBlank { "character" })
                        .replace(Regex("[^\\w\\-.一-龥]"), "_")
                        .take(80)
                    val suffix = if (summary) "_summary" else "_portable"
                    val ext = when (format) {
                        PortableExportFormat.JSON -> "json"
                        PortableExportFormat.TXT -> "txt"
                        PortableExportFormat.DOCX -> "docx"
                    }
                    val fileName = "$baseName$suffix.$ext"
                    val bytes = when (format) {
                        PortableExportFormat.JSON -> gson.toJson(payloadJson).toByteArray(Charsets.UTF_8)
                        PortableExportFormat.TXT -> CharacterPortableCodec.portableTxtFromPayload(payloadJson)
                            .toByteArray(Charsets.UTF_8)
                        PortableExportFormat.DOCX -> SimpleDocxWriter.writePlainDocx(
                            CharacterPortableCodec.portableTxtFromPayload(payloadJson),
                        )
                    }
                    fileName to bytes
                }
            } catch (cancelled: CancellationException) {
                shouldReportResult = false
                throw cancelled
            } catch (e: Exception) {
                _state.update { it.copy(exportMessage = UserFacingStrings.exportFailedDetail(e.message)) }
            } finally {
                _state.update { it.copy(isPreparingExport = false, isExportingSummary = false) }
                if (shouldReportResult) onResult(result)
            }
        }
    }

    /** 导出内嵌 JSON 的 PNG 竖版形象卡；底图优先卡图/头像 PNG，否则白底占位。 */
    fun buildTavernPngExport(routeCharacterId: Long, onResult: (Pair<String, ByteArray>?) -> Unit) {
        if (_state.value.isPreparingExport) return
        _state.value = _state.value.copy(
            exportMessage = null,
            isPreparingExport = true,
            isExportingSummary = false,
        )
        viewModelScope.launch {
            var result: Pair<String, ByteArray>? = null
            var shouldReportResult = true
            try {
                val entity = mergedEntityForExport(routeCharacterId) ?: run {
                    _state.update { it.copy(exportMessage = UserFacingStrings.exportCharacterNotLoaded()) }
                    return@launch
                }
                val profile = characterProfileDao.getByCharacter(entity.id)
                val base = withContext(Dispatchers.IO) { loadBasePngBytesForExport(entity) }
                result = withContext(Dispatchers.Default) {
                    val root = CharacterCardV2Converter.buildV2Export(entity, profile)
                    val out = CharacterCardPngCodec.embedCharaJson(base, root)
                    val safe = entity.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        .take(80)
                        .ifBlank { "character" }
                    "$safe.png" to out
                }
            } catch (cancelled: CancellationException) {
                shouldReportResult = false
                throw cancelled
            } catch (e: Exception) {
                _state.update { it.copy(exportMessage = UserFacingStrings.exportFailedDetail(e.message)) }
            } finally {
                _state.update { it.copy(isPreparingExport = false, isExportingSummary = false) }
                if (shouldReportResult) onResult(result)
            }
        }
    }

    private fun loadBasePngBytesForExport(entity: CharacterEntity): ByteArray {
        val paths = listOfNotNull(
            entity.cardImagePath.takeIf { it.isNotBlank() },
            entity.avatarImagePath.takeIf { it.isNotBlank() },
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists() && f.length() > 64) {
                val b = f.readBytes()
                if (CharacterCardPngCodec.isPng(b)) return b
            }
        }
        return minimalWhitePng()
    }

    private fun minimalWhitePng(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    private fun applyProbeStreamEvent(ev: JsonObject) {
        when (ev.get("type")?.asString) {
            "start" -> {
                val total = ev.get("total")?.asInt ?: 0
                val hint = if (total > 1) "共 $total 条候选线路，将依次尝试" else "正在探测…"
                _state.update { it.copy(probeStreamHint = hint) }
            }
            "attempt" -> {
                val idx = ev.get("index")?.asInt ?: 0
                val tot = ev.get("total")?.asInt ?: 0
                val disp = ev.get("display_base")?.asString ?: ev.get("base_url")?.asString ?: ""
                _state.update { it.copy(probeStreamHint = "第 $idx/$tot：$disp") }
            }
            "attempt_result" -> {
                val b = ev.get("base_url")?.asString ?: ""
                val ok = ev.get("ok")?.asBoolean == true
                val err = ev.get("error")?.asString
                val line = "${b.take(56)}${if (b.length > 56) "…" else ""} — ${if (ok) "✓" else "✗ ${err ?: "失败"}"}"
                _state.update { s -> s.copy(probeStreamLines = s.probeStreamLines + line) }
            }
        }
    }

    /** 使用与对话相同的整组连接解析测试当前角色文字线路。 */
    fun probeTextApi(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val st = _state.value
            if (st.probeBusyChannel != null) return@launch
            val connection = com.mojing.app.domain.config.ChatConnectionResolver.resolve(
                characterKey = st.apiKey,
                characterBase = st.apiBaseUrl,
                publicKey = secureStorage.publicApiKey,
                publicBase = secureStorage.publicBaseUrl,
            )
            connection.error?.let { onMessage(it); return@launch }
            val base = connection.baseUrl
            if (base.isBlank()) {
                onMessage(ProbeUiMessages.missingUrl("text"))
                return@launch
            }
            val key = connection.apiKey
            if (key.isBlank()) {
                onMessage(ProbeUiMessages.missingKey("text"))
                return@launch
            }
            val model = connection.model(st.modelName, secureStorage.publicModel, st.thinkMaxEnabled,
                st.thinkMaxModelName, secureStorage.thinkMaxModel).ifBlank { null }
            if (model == null) {
                onMessage(UserFacingStrings.chatMainModelMissing())
                return@launch
            }
            _state.value = st.copy(
                probeBusyChannel = "text",
                probeStreamHint = "正在连接服务器…",
                probeStreamLines = emptyList(),
            )
            val result = systemProbeApi.probePublicApiStream("text", base, key, model, ::applyProbeStreamEvent)
            _state.update {
                it.copy(
                    probeBusyChannel = null,
                    probeStreamHint = "",
                    probeStreamLines = emptyList(),
                )
            }
            result.fold(
                onSuccess = { done ->
                    val ok = done.get("ok")?.asBoolean == true
                    val err = done.get("error")?.asString?.trim().orEmpty()
                    onMessage(ProbeUiMessages.formatProbeDone("text", ok, err, done, base))
                },
                onFailure = { onMessage(it.message ?: "请求失败") },
            )
        }
    }

    /** 生图线路：角色配图字段 → 设置里全局配图 → 公共；不混入对话 Key/Base（与聊天内实际生图一致）。 */
    fun probeImageApi(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val st = _state.value
            val base = st.imageGenBaseUrl.trim()
                .ifBlank { secureStorage.imageBaseUrl.trim() }
                .ifBlank { secureStorage.publicBaseUrl.trim() }
            val key = st.imageGenApiKey.trim()
                .ifBlank { secureStorage.imageApiKey.trim() }
                .ifBlank { secureStorage.publicApiKey.trim() }
            val model = st.imageGenModel.trim()
                .ifBlank { secureStorage.imageModel.trim() }
                .ifBlank { "dall-e-3" }
            if (base.isBlank()) {
                onMessage(ProbeUiMessages.missingUrl("image"))
                return@launch
            }
            if (key.isBlank()) {
                onMessage(ProbeUiMessages.missingKey("image"))
                return@launch
            }
            _state.update {
                it.copy(
                    probeBusyChannel = "image",
                    probeStreamHint = "正在连接服务器…",
                    probeStreamLines = emptyList(),
                )
            }
            val result = systemProbeApi.probePublicApiStream("image", base, key, model, ::applyProbeStreamEvent)
            _state.update {
                it.copy(probeBusyChannel = null, probeStreamHint = "", probeStreamLines = emptyList())
            }
            result.fold(
                onSuccess = { done ->
                    val ok = done.get("ok")?.asBoolean == true
                    val err = done.get("error")?.asString?.trim().orEmpty()
                    onMessage(ProbeUiMessages.formatProbeDone("image", ok, err, done, base))
                },
                onFailure = { onMessage(it.message ?: "请求失败") },
            )
        }
    }

    /**
     * 竖版角色封面：直连 OpenAI 兼容配图接口（设置中的公共配图或角色配图线路），裁切为 2:3 后写入本页预览路径。
     */
    fun generateCardImageViaBackend() {
        val s = _state.value
        if (s.name.isBlank()) {
            showSnackbar("请先填写角色名")
            return
        }
        if (s.personaPrompt.isBlank()) {
            showSnackbar("请先写好人设提示词，再生成竖版封面；否则画面缺少角色设定依据。")
            return
        }
        val base = s.imageGenBaseUrl.trim()
            .ifBlank { secureStorage.imageBaseUrl.trim() }
            .ifBlank { secureStorage.publicBaseUrl.trim() }
        val key = s.imageGenApiKey.trim()
            .ifBlank { secureStorage.imageApiKey.trim() }
            .ifBlank { secureStorage.publicApiKey.trim() }
        val model = s.imageGenModel.trim()
            .ifBlank { secureStorage.imageModel.trim() }
            .ifBlank { "dall-e-3" }
        if (base.isBlank() || key.isBlank()) {
            showSnackbar(UserFacingStrings.imageGenKeyMissing())
            return
        }
        val prompt = buildString {
            append("竖版插画，比例约 2:3，单角色半身或全身，氛围明确，无文字无水印。角色名：")
            append(s.name.take(80))
            append("。人设要点：")
            append(s.personaPrompt.trim().take(1200))
        }
        viewModelScope.launch {
            _state.update { it.copy(isGeneratingCardImage = true) }
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
                            "图片生成失败：未返回图片地址" -> "未获取到图片地址，请检查配图模型与网关是否支持该尺寸"
                            "图片下载失败", "图片裁切保存失败" -> "裁切保存失败"
                            else -> UserFacingStrings.remoteRequestFailed(error)
                        },
                    )
                    return@launch
                }
                updateDraft {
                    it.copy(
                        cardImagePath = finalPath,
                        snackbar = "已生成竖版封面预览，请保存角色",
                    )
                }
            } finally {
                _state.update { it.copy(isGeneratingCardImage = false) }
            }
        }
    }

}

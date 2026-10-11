package com.mojing.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.ModelPlatform
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.ui.common.ProbeUiMessages
import com.mojing.app.ui.theme.AppThemes
import com.mojing.app.data.local.dao.CostRecordDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.remote.BackendSystemProbeApi
import com.mojing.app.domain.config.ApiKeyResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

internal data class CreationDefaultOption(
    val key: String,
    val label: String,
    val id: Long,
    val pinnedAt: Long,
    val updatedAt: Long,
)

internal data class CreationDefaultPage(val rows: List<CreationDefaultOption>, val hasMore: Boolean)

data class CreationDefaultLabels(
    val world: String? = null,
    val encyclopedia: String? = null,
    val worldMissing: Boolean = false,
    val encyclopediaMissing: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

enum class ReadingPreferenceKind { DENSITY, FONT, NARRATOR_ITALIC }

data class ReadingPreferenceSaveRequest(
    val kind: ReadingPreferenceKind,
    val stringValue: String? = null,
    val booleanValue: Boolean? = null,
)

data class ReadingPreferencesSaveNotice(
    val token: String,
    val request: ReadingPreferenceSaveRequest,
)

internal sealed interface SettingsInitializationState {
    data object Loading : SettingsInitializationState
    data object Ready : SettingsInitializationState
    data class Error(val message: String) : SettingsInitializationState
}

private data class SettingsSecureSnapshot(
    val publicApiKey: String,
    val publicBaseUrl: String,
    val publicModel: String,
    val themeMode: String,
    val uiFontScale: Float,
    val activePlatformId: String,
    val userName: String,
    val userDescription: String,
    val userAvatarColor: String,
    val userAvatarImagePath: String,
    val defaultTemperature: String,
    val defaultMaxTokens: String,
    val defaultTopP: String,
    val defaultWorldTemplateId: String,
    val defaultNarratorEnabled: Boolean,
    val defaultChoiceGenerationEnabled: Boolean,
    val defaultAntiCheatEnabled: Boolean,
    val memoryCompactThreshold: Int,
    val universalContextMemoryEnabled: Boolean,
    val maxUploadMb: Int,
    val maxAutoSpeakers: Int,
    val defaultEncyclopediaIdForAi: Long,
    val imageApiKey: String,
    val imageBaseUrl: String,
    val imageModel: String,
    val voiceApiKey: String,
    val voiceBaseUrl: String,
    val voiceModel: String,
    val voiceSpeechVoice: String,
    val voicePresetPrefixModel: String,
    val allowSessionThinkMax: Boolean,
    val thinkMaxModel: String,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val costRecordDao: CostRecordDao,
    private val systemProbeApi: BackendSystemProbeApi,
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val worldTemplateDao: WorldTemplateDao,
    private val encyclopediaDao: EncyclopediaDao,
) : ViewModel() {
    private val _initialization = MutableStateFlow<SettingsInitializationState>(SettingsInitializationState.Loading)
    internal val initialization: StateFlow<SettingsInitializationState> = _initialization.asStateFlow()
    private var initializationJob: Job? = null

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _model = MutableStateFlow("")
    val model: StateFlow<String> = _model.asStateFlow()

    private val _themeMode = MutableStateFlow(AppThemes.normalize("dark"))
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _uiFontScale = MutableStateFlow(1f)
    val uiFontScale: StateFlow<Float> = _uiFontScale.asStateFlow()

    val chatDensity: StateFlow<String> = uiPreferencesRepository.chatDensity.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = "comfortable",
    )
    val chatFont = uiPreferencesRepository.chatFont.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")
    val narratorItalic = uiPreferencesRepository.narratorItalic.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _readingPreferencesSaving = MutableStateFlow(false)
    val readingPreferencesSaving: StateFlow<Boolean> = _readingPreferencesSaving.asStateFlow()
    private val _readingPreferencesSaveNotice = MutableStateFlow<ReadingPreferencesSaveNotice?>(null)
    val readingPreferencesSaveNotice: StateFlow<ReadingPreferencesSaveNotice?> = _readingPreferencesSaveNotice.asStateFlow()
    private var readingPreferencesSaveSerial = 0L
    private var readingPreferencesSaveJob: Job? = null

    fun setChatFont(font: String) {
        saveReadingPreference(ReadingPreferenceSaveRequest(ReadingPreferenceKind.FONT, stringValue = font))
    }

    fun setNarratorItalic(enabled: Boolean) {
        saveReadingPreference(ReadingPreferenceSaveRequest(ReadingPreferenceKind.NARRATOR_ITALIC, booleanValue = enabled))
    }

    private fun isInitialized(): Boolean = _initialization.value is SettingsInitializationState.Ready

    private fun loadSecureSnapshot() {
        if (initializationJob?.isActive == true) return
        _initialization.value = SettingsInitializationState.Loading
        initializationJob = viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    SettingsSecureSnapshot(
                        publicApiKey = secureStorage.publicApiKey,
                        publicBaseUrl = secureStorage.publicBaseUrl,
                        publicModel = secureStorage.publicModel,
                        themeMode = secureStorage.themeMode,
                        uiFontScale = secureStorage.uiFontScale,
                        activePlatformId = secureStorage.activeModelPlatformId(),
                        userName = secureStorage.userName,
                        userDescription = secureStorage.userDescription,
                        userAvatarColor = secureStorage.userAvatarColor,
                        userAvatarImagePath = secureStorage.userAvatarImagePath,
                        defaultTemperature = secureStorage.defaultTemperature,
                        defaultMaxTokens = secureStorage.defaultMaxTokens,
                        defaultTopP = secureStorage.defaultTopP,
                        defaultWorldTemplateId = secureStorage.defaultWorldTemplateId,
                        defaultNarratorEnabled = secureStorage.defaultNarratorEnabled,
                        defaultChoiceGenerationEnabled = secureStorage.defaultChoiceGenerationEnabled,
                        defaultAntiCheatEnabled = secureStorage.defaultAntiCheatEnabled,
                        memoryCompactThreshold = secureStorage.memoryCompactThreshold,
                        universalContextMemoryEnabled = secureStorage.universalContextMemoryEnabled,
                        maxUploadMb = secureStorage.maxUploadMb,
                        maxAutoSpeakers = secureStorage.maxAutoSpeakers,
                        defaultEncyclopediaIdForAi = secureStorage.defaultEncyclopediaIdForAi,
                        imageApiKey = secureStorage.imageApiKey,
                        imageBaseUrl = secureStorage.imageBaseUrl,
                        imageModel = secureStorage.imageModel,
                        voiceApiKey = secureStorage.voiceApiKey,
                        voiceBaseUrl = secureStorage.voiceBaseUrl,
                        voiceModel = secureStorage.voiceModel,
                        voiceSpeechVoice = secureStorage.voiceSpeechVoice,
                        voicePresetPrefixModel = secureStorage.voicePresetPrefixModel,
                        allowSessionThinkMax = secureStorage.allowSessionThinkMax,
                        thinkMaxModel = secureStorage.thinkMaxModel,
                    )
                }
                _apiKey.value = snapshot.publicApiKey
                _baseUrl.value = snapshot.publicBaseUrl
                _model.value = snapshot.publicModel
                _themeMode.value = AppThemes.normalize(snapshot.themeMode)
                _uiFontScale.value = snapshot.uiFontScale
                _activePlatformId.value = snapshot.activePlatformId
                _userName.value = snapshot.userName
                _userDescription.value = snapshot.userDescription
                _userAvatarColor.value = snapshot.userAvatarColor
                _userAvatarImagePath.value = snapshot.userAvatarImagePath
                _defaultTemperature.value = snapshot.defaultTemperature
                _defaultMaxTokens.value = snapshot.defaultMaxTokens
                _defaultTopP.value = snapshot.defaultTopP
                _defaultWorldTemplateId.value = snapshot.defaultWorldTemplateId
                _defaultNarratorEnabled.value = snapshot.defaultNarratorEnabled
                _defaultChoiceGenerationEnabled.value = snapshot.defaultChoiceGenerationEnabled
                _defaultAntiCheatEnabled.value = snapshot.defaultAntiCheatEnabled
                _memoryCompactThreshold.value = snapshot.memoryCompactThreshold.toString()
                _universalContextMemoryEnabled.value = snapshot.universalContextMemoryEnabled
                _maxUploadMb.value = snapshot.maxUploadMb.toString()
                _maxAutoSpeakers.value = snapshot.maxAutoSpeakers.toString()
                _defaultEncyclopediaIdForAi.value = snapshot.defaultEncyclopediaIdForAi.takeIf { it > 0L }?.toString().orEmpty()
                _imageApiKey.value = snapshot.imageApiKey
                _imageBaseUrl.value = snapshot.imageBaseUrl
                _imageModel.value = snapshot.imageModel
                _voiceApiKey.value = snapshot.voiceApiKey
                _voiceBaseUrl.value = snapshot.voiceBaseUrl
                _voiceModel.value = snapshot.voiceModel
                _voiceSpeechVoice.value = snapshot.voiceSpeechVoice
                _voicePresetPrefixModel.value = snapshot.voicePresetPrefixModel
                _allowSessionThinkMax.value = snapshot.allowSessionThinkMax
                _thinkMaxModel.value = snapshot.thinkMaxModel
                _initialization.value = SettingsInitializationState.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _initialization.value = SettingsInitializationState.Error("设置读取失败，请重试")
            } finally {
                initializationJob = null
            }
        }
    }

    fun retryInitialization() {
        if (_initialization.value !is SettingsInitializationState.Error) return
        loadSecureSnapshot()
    }

    fun updateApiKey(value: String) { if (!isInitialized()) return; _apiKey.value = value; secureStorage.publicApiKey = value }
    fun updateBaseUrl(value: String) { if (!isInitialized()) return; _baseUrl.value = value; secureStorage.publicBaseUrl = value }
    fun updateModel(value: String) { if (!isInitialized()) return; _model.value = value; secureStorage.publicModel = value }

    fun modelPlatforms(): List<ModelPlatform> = secureStorage.modelPlatforms()
    private val _activePlatformId = MutableStateFlow("")
    val activePlatformId: StateFlow<String> = _activePlatformId.asStateFlow()

    suspend fun savePlatform(platform: com.mojing.app.data.ModelPlatform, makeDefault: Boolean = false) {
        check(isInitialized()) { "设置尚未读取完成" }
        val remainsActive = platform.id == _activePlatformId.value
        val shouldProjectToDefault = makeDefault || remainsActive
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            secureStorage.saveModelPlatform(platform, makeDefault = shouldProjectToDefault)
        }
        if (shouldProjectToDefault) {
            _apiKey.value = platform.apiKey
            _baseUrl.value = platform.baseUrl
            _model.value = platform.selectedModel
            _activePlatformId.value = platform.id
        }
    }

    suspend fun deletePlatform(platformId: String): List<com.mojing.app.data.ModelPlatform> {
        check(isInitialized()) { "设置尚未读取完成" }
        val wasActive = platformId == _activePlatformId.value
        val remaining = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            secureStorage.deleteModelPlatform(platformId)
        }
        if (wasActive) {
            _apiKey.value = ""
            _baseUrl.value = ""
            _model.value = ""
            _activePlatformId.value = ""
        }
        return remaining
    }

    suspend fun fetchPlatformModels(base: String, key: String) = systemProbeApi.listModels(base, key)

    fun setThemeMode(mode: String) {
        if (!isInitialized()) return
        val m = AppThemes.normalize(mode)
        _themeMode.value = m
        secureStorage.themeMode = m
    }

    fun setUiFontScale(scale: Float) {
        if (!isInitialized()) return
        val s = scale.coerceIn(0.8f, 1.45f)
        _uiFontScale.value = s
        secureStorage.uiFontScale = s
    }

    fun setChatDensity(mode: String) {
        saveReadingPreference(ReadingPreferenceSaveRequest(ReadingPreferenceKind.DENSITY, stringValue = mode))
    }

    fun retryReadingPreferences(token: String) {
        val notice = _readingPreferencesSaveNotice.value ?: return
        if (notice.token != token || _readingPreferencesSaving.value) return
        saveReadingPreference(notice.request)
    }

    fun dismissReadingPreferencesNotice(token: String) {
        if (_readingPreferencesSaveNotice.value?.token == token) {
            _readingPreferencesSaveNotice.value = null
        }
    }

    fun cancelReadingPreferencesSave() {
        if (!_readingPreferencesSaving.value) return
        readingPreferencesSaveSerial++
        readingPreferencesSaveJob?.cancel()
        readingPreferencesSaveJob = null
        _readingPreferencesSaving.value = false
        _readingPreferencesSaveNotice.value = null
    }

    private fun saveReadingPreference(request: ReadingPreferenceSaveRequest) {
        if (_readingPreferencesSaving.value) return
        val serial = ++readingPreferencesSaveSerial
        _readingPreferencesSaveNotice.value = null
        _readingPreferencesSaving.value = true
        readingPreferencesSaveJob = viewModelScope.launch {
            try {
                when (request.kind) {
                    ReadingPreferenceKind.DENSITY -> uiPreferencesRepository.setChatDensity(request.stringValue.orEmpty())
                    ReadingPreferenceKind.FONT -> uiPreferencesRepository.setChatFont(request.stringValue.orEmpty())
                    ReadingPreferenceKind.NARRATOR_ITALIC -> uiPreferencesRepository.setNarratorItalic(request.booleanValue == true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (serial == readingPreferencesSaveSerial) {
                    _readingPreferencesSaveNotice.value = ReadingPreferencesSaveNotice(UUID.randomUUID().toString(), request)
                }
            } finally {
                if (serial == readingPreferencesSaveSerial) {
                    readingPreferencesSaveJob = null
                    _readingPreferencesSaving.value = false
                }
            }
        }
    }

    // User Profile
    private val _userName = MutableStateFlow("玩家")
    val userName: StateFlow<String> = _userName.asStateFlow()
    private val _userDescription = MutableStateFlow("")
    val userDescription: StateFlow<String> = _userDescription.asStateFlow()
    private val _userAvatarColor = MutableStateFlow("#53C7A8")
    val userAvatarColor: StateFlow<String> = _userAvatarColor.asStateFlow()

    private val _userAvatarImagePath = MutableStateFlow("")
    val userAvatarImagePath: StateFlow<String> = _userAvatarImagePath.asStateFlow()

    fun updateUserAvatarImagePath(path: String) {
        if (!isInitialized()) return
        _userAvatarImagePath.value = path
        secureStorage.userAvatarImagePath = path
    }

    private val _profileSaving = MutableStateFlow(false)
    val profileSaving = _profileSaving.asStateFlow()
    private val _profileSaveError = MutableStateFlow<String?>(null)
    val profileSaveError = _profileSaveError.asStateFlow()

    fun updateProfile(name: String, desc: String, color: String, onSaved: () -> Unit = {}) {
        if (!isInitialized()) return
        if (_profileSaving.value) return
        if (name.isBlank()) {
            _profileSaveError.value = "请输入对话中使用的名字"
            return
        }
        _profileSaving.value = true
        _profileSaveError.value = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { secureStorage.saveUserProfile(name.trim(), desc, color) }
                _userName.value = name.trim()
                _userDescription.value = desc
                _userAvatarColor.value = color
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _profileSaveError.value = "资料未保存，请重试"
            } finally {
                _profileSaving.value = false
            }
        }
    }

    // Defaults
    private val _defaultTemperature = MutableStateFlow("0.9")
    val defaultTemperature: StateFlow<String> = _defaultTemperature.asStateFlow()
    private val _defaultMaxTokens = MutableStateFlow("1200")
    val defaultMaxTokens: StateFlow<String> = _defaultMaxTokens.asStateFlow()
    private val _defaultTopP = MutableStateFlow("1.0")
    val defaultTopP: StateFlow<String> = _defaultTopP.asStateFlow()

    fun updateDefaultTemperature(value: String) {
        if (!isInitialized()) return
        _defaultTemperature.value = value
        if (value.trim().toFloatOrNull()?.let { it in 0f..2f } == true) secureStorage.defaultTemperature = value
    }
    fun updateDefaultMaxTokens(value: String) {
        if (!isInitialized()) return
        _defaultMaxTokens.value = value
        if (value.trim().toIntOrNull()?.let { it in 1..200_000 } == true) secureStorage.defaultMaxTokens = value
    }
    fun updateDefaultTopP(value: String) {
        if (!isInitialized()) return
        _defaultTopP.value = value
        if (value.trim().toFloatOrNull()?.let { it in 0f..1f } == true) secureStorage.defaultTopP = value
    }

    private val _defaultWorldTemplateId = MutableStateFlow("custom")
    val defaultWorldTemplateId: StateFlow<String> = _defaultWorldTemplateId.asStateFlow()
    private val _defaultNarratorEnabled = MutableStateFlow(false)
    val defaultNarratorEnabled: StateFlow<Boolean> = _defaultNarratorEnabled.asStateFlow()
    private val _defaultChoiceGenerationEnabled = MutableStateFlow(true)
    val defaultChoiceGenerationEnabled: StateFlow<Boolean> = _defaultChoiceGenerationEnabled.asStateFlow()
    private val _defaultAntiCheatEnabled = MutableStateFlow(true)
    val defaultAntiCheatEnabled: StateFlow<Boolean> = _defaultAntiCheatEnabled.asStateFlow()
    private val _memoryCompactThreshold = MutableStateFlow("120")
    val memoryCompactThreshold: StateFlow<String> = _memoryCompactThreshold.asStateFlow()
    private val _universalContextMemoryEnabled = MutableStateFlow(true)
    val universalContextMemoryEnabled: StateFlow<Boolean> = _universalContextMemoryEnabled.asStateFlow()
    private val _maxUploadMb = MutableStateFlow("20")
    val maxUploadMb: StateFlow<String> = _maxUploadMb.asStateFlow()
    private val _maxAutoSpeakers = MutableStateFlow("2")
    val maxAutoSpeakers: StateFlow<String> = _maxAutoSpeakers.asStateFlow()

    private val _defaultEncyclopediaIdForAi = MutableStateFlow(
        "",
    )
    val defaultEncyclopediaIdForAi: StateFlow<String> = _defaultEncyclopediaIdForAi.asStateFlow()

    private val _creationDefaultLabels = MutableStateFlow(CreationDefaultLabels())
    val creationDefaultLabels: StateFlow<CreationDefaultLabels> = _creationDefaultLabels.asStateFlow()
    private var defaultLabelJob: Job? = null
    private var defaultLabelRevision = 0

    fun loadSelectedCreationDefaults() {
        defaultLabelJob?.cancel()
        val revision = ++defaultLabelRevision
        val worldKey = _defaultWorldTemplateId.value.trim()
        val encyclopediaKey = _defaultEncyclopediaIdForAi.value.trim().toLongOrNull()
        _creationDefaultLabels.value = _creationDefaultLabels.value.copy(loading = true, error = null)
        defaultLabelJob = viewModelScope.launch {
            var worldError = false
            var encyclopediaError = false
            val world = if (worldKey.isBlank() || worldKey == "custom") null else try {
                worldTemplateDao.getDefaultWorldByTemplateId(worldKey)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { worldError = true; null }
            val encyclopedia = if (encyclopediaKey == null || encyclopediaKey <= 0L) null else try {
                encyclopediaDao.getNameById(encyclopediaKey)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { encyclopediaError = true; null }
            if (revision != defaultLabelRevision) return@launch
            val previous = _creationDefaultLabels.value
            _creationDefaultLabels.value = CreationDefaultLabels(
                world = if (worldError) previous.world else world?.label,
                encyclopedia = if (encyclopediaError) previous.encyclopedia else encyclopedia,
                worldMissing = !worldError && worldKey.isNotBlank() && worldKey != "custom" && world == null,
                encyclopediaMissing = !encyclopediaError && encyclopediaKey != null && encyclopediaKey > 0L && encyclopedia == null,
                error = if (worldError || encyclopediaError) "已选默认资料暂时无法读取，请重试" else null,
            )
        }
    }

    internal suspend fun loadDefaultWorldPage(query: String, cursor: CreationDefaultOption?): CreationDefaultPage {
        val rows = worldTemplateDao.getDefaultWorldPage(
            query.trim(), cursor?.pinnedAt, cursor?.updatedAt, cursor?.id, 41,
        )
        return CreationDefaultPage(rows.take(40).map { CreationDefaultOption(
            key = it.templateId, label = it.label, id = it.id,
            pinnedAt = it.pinnedAt, updatedAt = it.updatedAt,
        ) }, rows.size > 40)
    }

    internal suspend fun loadDefaultEncyclopediaPage(query: String, cursor: CreationDefaultOption?): CreationDefaultPage {
        val rows = encyclopediaDao.getCharacterFilterPage(
            query.trim(), cursor?.let { if (it.pinnedAt > 0L) 1 else 0 },
            cursor?.pinnedAt, cursor?.updatedAt, cursor?.id, 41,
        )
        return CreationDefaultPage(rows.take(40).map { CreationDefaultOption(
            key = it.id.toString(), label = it.name, id = it.id,
            pinnedAt = it.pinnedAt, updatedAt = it.updatedAt,
        ) }, rows.size > 40)
    }

    fun updateDefaultWorldTemplateId(value: String) {
        if (!isInitialized()) return
        defaultLabelJob?.cancel()
        defaultLabelRevision++
        _defaultWorldTemplateId.value = value
        secureStorage.defaultWorldTemplateId = value
    }
    internal fun selectDefaultWorld(option: CreationDefaultOption?) {
        updateDefaultWorldTemplateId(option?.key ?: "custom")
        _creationDefaultLabels.value = _creationDefaultLabels.value.copy(
            world = option?.label, worldMissing = false, loading = false, error = null,
        )
    }
    fun updateDefaultNarratorEnabled(value: Boolean) {
        if (!isInitialized()) return
        _defaultNarratorEnabled.value = value
        secureStorage.defaultNarratorEnabled = value
    }
    fun updateDefaultChoiceGenerationEnabled(value: Boolean) {
        if (!isInitialized()) return
        _defaultChoiceGenerationEnabled.value = value
        secureStorage.defaultChoiceGenerationEnabled = value
    }
    fun updateDefaultAntiCheatEnabled(value: Boolean) {
        if (!isInitialized()) return
        _defaultAntiCheatEnabled.value = value
        secureStorage.defaultAntiCheatEnabled = value
    }
    fun updateMemoryCompactThreshold(value: String) {
        if (!isInitialized()) return
        _memoryCompactThreshold.value = value
        value.toIntOrNull()?.takeIf { it in 10..2000 }?.let { secureStorage.memoryCompactThreshold = it }
    }

    fun updateUniversalContextMemoryEnabled(value: Boolean) {
        if (!isInitialized()) return
        _universalContextMemoryEnabled.value = value
        secureStorage.universalContextMemoryEnabled = value
    }

    fun updateMaxUploadMb(value: String) {
        if (!isInitialized()) return
        _maxUploadMb.value = value
        value.toIntOrNull()?.let { secureStorage.maxUploadMb = it }
    }
    fun updateMaxAutoSpeakers(value: String) {
        if (!isInitialized()) return
        _maxAutoSpeakers.value = value
        value.toIntOrNull()?.let { secureStorage.maxAutoSpeakers = it }
    }

    fun updateDefaultEncyclopediaIdForAi(value: String) {
        if (!isInitialized()) return
        defaultLabelJob?.cancel()
        defaultLabelRevision++
        _defaultEncyclopediaIdForAi.value = value.trim()
        val id = value.trim().toLongOrNull() ?: 0L
        secureStorage.defaultEncyclopediaIdForAi = id.coerceAtLeast(0L)
    }
    internal fun selectDefaultEncyclopedia(option: CreationDefaultOption?) {
        updateDefaultEncyclopediaIdForAi(option?.key.orEmpty())
        _creationDefaultLabels.value = _creationDefaultLabels.value.copy(
            encyclopedia = option?.label, encyclopediaMissing = false, loading = false, error = null,
        )
    }

    // Image API
    private val _imageApiKey = MutableStateFlow("")
    val imageApiKey: StateFlow<String> = _imageApiKey.asStateFlow()
    private val _imageBaseUrl = MutableStateFlow("")
    val imageBaseUrl: StateFlow<String> = _imageBaseUrl.asStateFlow()
    private val _imageModel = MutableStateFlow("dall-e-3")
    val imageModel: StateFlow<String> = _imageModel.asStateFlow()

    fun updateImageApiKey(value: String) { if (!isInitialized()) return; _imageApiKey.value = value; secureStorage.imageApiKey = value }
    fun updateImageBaseUrl(value: String) { if (!isInitialized()) return; _imageBaseUrl.value = value; secureStorage.imageBaseUrl = value }
    fun updateImageModel(value: String) { if (!isInitialized()) return; _imageModel.value = value; secureStorage.imageModel = value }

    // Voice API
    private val _voiceApiKey = MutableStateFlow("")
    val voiceApiKey: StateFlow<String> = _voiceApiKey.asStateFlow()
    private val _voiceBaseUrl = MutableStateFlow("")
    val voiceBaseUrl: StateFlow<String> = _voiceBaseUrl.asStateFlow()
    private val _voiceModel = MutableStateFlow("system")
    val voiceModel: StateFlow<String> = _voiceModel.asStateFlow()

    fun updateVoiceApiKey(value: String) { if (!isInitialized()) return; _voiceApiKey.value = value; secureStorage.voiceApiKey = value }
    fun updateVoiceBaseUrl(value: String) { if (!isInitialized()) return; _voiceBaseUrl.value = value; secureStorage.voiceBaseUrl = value }
    fun updateVoiceModel(value: String) { if (!isInitialized()) return; _voiceModel.value = value; secureStorage.voiceModel = value }

    private val _voiceSpeechVoice = MutableStateFlow("")
    val voiceSpeechVoice: StateFlow<String> = _voiceSpeechVoice.asStateFlow()

    fun updateVoiceSpeechVoice(value: String) {
        if (!isInitialized()) return
        _voiceSpeechVoice.value = value
        secureStorage.voiceSpeechVoice = value
    }

    private val _voicePresetPrefixModel = MutableStateFlow("")
    val voicePresetPrefixModel: StateFlow<String> = _voicePresetPrefixModel.asStateFlow()

    fun updateVoicePresetPrefixModel(value: String) {
        if (!isInitialized()) return
        _voicePresetPrefixModel.value = value
        secureStorage.voicePresetPrefixModel = value
    }

    private val _allowSessionThinkMax = MutableStateFlow(false)
    val allowSessionThinkMax: StateFlow<Boolean> = _allowSessionThinkMax.asStateFlow()

    private val _thinkMaxModel = MutableStateFlow("")
    val thinkMaxModel: StateFlow<String> = _thinkMaxModel.asStateFlow()

    init {
        loadSecureSnapshot()
    }

    fun updateAllowSessionThinkMax(value: Boolean) {
        if (!isInitialized()) return
        _allowSessionThinkMax.value = value
        secureStorage.allowSessionThinkMax = value
    }

    fun updateThinkMaxModel(value: String) {
        if (!isInitialized()) return
        _thinkMaxModel.value = value
        secureStorage.thinkMaxModel = value
    }

    // —— 公共 API 连通测试：本机直连多候选根地址；若仍配置了墨境后端则再尝试流式中继 ——

    private val _probeBusyChannel = MutableStateFlow<String?>(null)
    val probeBusyChannel: StateFlow<String?> = _probeBusyChannel.asStateFlow()

    private val _probeStreamHint = MutableStateFlow("")
    val probeStreamHint: StateFlow<String> = _probeStreamHint.asStateFlow()

    private val _probeStreamLines = MutableStateFlow<List<String>>(emptyList())
    val probeStreamLines: StateFlow<List<String>> = _probeStreamLines.asStateFlow()

    private fun resetProbeUi(channel: String) {
        _probeBusyChannel.value = channel
        _probeStreamHint.value = "正在连接服务器…"
        _probeStreamLines.value = emptyList()
    }

    private fun applyProbeEvent(ev: JsonObject) {
        when (ev.get("type")?.asString) {
            "start" -> {
                val total = ev.get("total")?.asInt ?: 0
                _probeStreamHint.value = if (total > 1) "共 $total 条候选线路，将依次尝试" else "正在探测…"
            }
            "attempt" -> {
                val idx = ev.get("index")?.asInt ?: 0
                val tot = ev.get("total")?.asInt ?: 0
                val disp = ev.get("display_base")?.asString ?: ev.get("base_url")?.asString ?: ""
                _probeStreamHint.value = "第 $idx/$tot：$disp"
            }
            "attempt_result" -> {
                val b = ev.get("base_url")?.asString ?: ""
                val ok = ev.get("ok")?.asBoolean == true
                val err = ev.get("error")?.asString
                val line = "${b.take(56)}${if (b.length > 56) "…" else ""} — ${if (ok) "✓" else "✗ ${err ?: "失败"}"}"
                _probeStreamLines.value = _probeStreamLines.value + line
            }
        }
    }

    fun runProbeText(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val base = _baseUrl.value.trim()
            val key = _apiKey.value.trim()
            val model = _model.value.trim()
            if (base.isBlank()) {
                onMessage(ProbeUiMessages.missingUrl("text"))
                return@launch
            }
            if (key.isBlank()) {
                onMessage(ProbeUiMessages.missingKey("text"))
                return@launch
            }
            resetProbeUi("text")
            val result = systemProbeApi.probePublicApiStream("text", base, key, model, ::applyProbeEvent)
            _probeBusyChannel.value = null
            _probeStreamHint.value = ""
            _probeStreamLines.value = emptyList()
            result.fold(
                onSuccess = { done ->
                    val ok = done.get("ok")?.asBoolean == true
                    val err = done.get("error")?.asString?.trim().orEmpty()
                    onMessage(ProbeUiMessages.formatProbeDone("text", ok, err, done, _baseUrl.value))
                },
                onFailure = { onMessage(it.message ?: "请求失败") },
            )
        }
    }

    fun runProbeImage(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val resolved = ApiKeyResolver.resolveImageGenPrimaryResolved(null, null, secureStorage)
            val base = resolved.baseUrlRaw.trim()
            val key = resolved.apiKey.trim()
            val model = resolved.model.trim().ifBlank { "dall-e-3" }
            if (base.isBlank()) {
                onMessage(ProbeUiMessages.missingUrl("image"))
                return@launch
            }
            if (key.isBlank()) {
                onMessage(ProbeUiMessages.missingKey("image"))
                return@launch
            }
            resetProbeUi("image")
            val result = systemProbeApi.probePublicApiStream("image", base, key, model, ::applyProbeEvent)
            _probeBusyChannel.value = null
            _probeStreamHint.value = ""
            _probeStreamLines.value = emptyList()
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

}

package com.mojing.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.ui.common.ProbeUiMessages
import com.mojing.app.ui.theme.AppThemes
import com.mojing.app.data.local.dao.CostRecordDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.data.remote.BackendSystemProbeApi
import dagger.hilt.android.lifecycle.HiltViewModel
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val costRecordDao: CostRecordDao,
    private val systemProbeApi: BackendSystemProbeApi,
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val worldTemplateDao: WorldTemplateDao,
    private val encyclopediaDao: EncyclopediaDao,
) : ViewModel() {
    private val _apiKey = MutableStateFlow(secureStorage.publicApiKey)
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _baseUrl = MutableStateFlow(secureStorage.publicBaseUrl)
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _model = MutableStateFlow(secureStorage.publicModel)
    val model: StateFlow<String> = _model.asStateFlow()

    private val _themeMode = MutableStateFlow(AppThemes.normalize(secureStorage.themeMode))
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _uiFontScale = MutableStateFlow(secureStorage.uiFontScale)
    val uiFontScale: StateFlow<Float> = _uiFontScale.asStateFlow()

    val chatDensity: StateFlow<String> = uiPreferencesRepository.chatDensity.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = "comfortable",
    )
    val chatFont = uiPreferencesRepository.chatFont.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")
    val narratorItalic = uiPreferencesRepository.narratorItalic.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setChatFont(font: String) { viewModelScope.launch { uiPreferencesRepository.setChatFont(font) } }
    fun setNarratorItalic(enabled: Boolean) { viewModelScope.launch { uiPreferencesRepository.setNarratorItalic(enabled) } }

    fun updateApiKey(value: String) { _apiKey.value = value; secureStorage.publicApiKey = value }
    fun updateBaseUrl(value: String) { _baseUrl.value = value; secureStorage.publicBaseUrl = value }
    fun updateModel(value: String) { _model.value = value; secureStorage.publicModel = value }

    fun modelPlatforms() = secureStorage.modelPlatforms()
    private val _activePlatformId = MutableStateFlow(secureStorage.activeModelPlatformId())
    val activePlatformId: StateFlow<String> = _activePlatformId.asStateFlow()

    suspend fun savePlatform(platform: com.mojing.app.data.ModelPlatform) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { secureStorage.saveModelPlatform(platform) }
        _apiKey.value = platform.apiKey
        _baseUrl.value = platform.baseUrl
        _model.value = platform.selectedModel
        _activePlatformId.value = platform.id
    }

    suspend fun fetchPlatformModels(base: String, key: String) = systemProbeApi.listModels(base, key)

    fun setThemeMode(mode: String) {
        val m = AppThemes.normalize(mode)
        _themeMode.value = m
        secureStorage.themeMode = m
    }

    fun setUiFontScale(scale: Float) {
        val s = scale.coerceIn(0.8f, 1.45f)
        _uiFontScale.value = s
        secureStorage.uiFontScale = s
    }

    fun setChatDensity(mode: String) {
        viewModelScope.launch { uiPreferencesRepository.setChatDensity(mode) }
    }

    // User Profile
    private val _userName = MutableStateFlow(secureStorage.userName)
    val userName: StateFlow<String> = _userName.asStateFlow()
    private val _userDescription = MutableStateFlow(secureStorage.userDescription)
    val userDescription: StateFlow<String> = _userDescription.asStateFlow()
    private val _userAvatarColor = MutableStateFlow(secureStorage.userAvatarColor)
    val userAvatarColor: StateFlow<String> = _userAvatarColor.asStateFlow()

    private val _userAvatarImagePath = MutableStateFlow(secureStorage.userAvatarImagePath)
    val userAvatarImagePath: StateFlow<String> = _userAvatarImagePath.asStateFlow()

    fun updateUserAvatarImagePath(path: String) {
        _userAvatarImagePath.value = path
        secureStorage.userAvatarImagePath = path
    }

    private val _profileSaving = MutableStateFlow(false)
    val profileSaving = _profileSaving.asStateFlow()
    private val _profileSaveError = MutableStateFlow<String?>(null)
    val profileSaveError = _profileSaveError.asStateFlow()

    fun updateProfile(name: String, desc: String, color: String, onSaved: () -> Unit = {}) {
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
    private val _defaultTemperature = MutableStateFlow(secureStorage.defaultTemperature)
    val defaultTemperature: StateFlow<String> = _defaultTemperature.asStateFlow()
    private val _defaultMaxTokens = MutableStateFlow(secureStorage.defaultMaxTokens)
    val defaultMaxTokens: StateFlow<String> = _defaultMaxTokens.asStateFlow()
    private val _defaultTopP = MutableStateFlow(secureStorage.defaultTopP)
    val defaultTopP: StateFlow<String> = _defaultTopP.asStateFlow()

    fun updateDefaultTemperature(value: String) { _defaultTemperature.value = value; secureStorage.defaultTemperature = value }
    fun updateDefaultMaxTokens(value: String) { _defaultMaxTokens.value = value; secureStorage.defaultMaxTokens = value }
    fun updateDefaultTopP(value: String) { _defaultTopP.value = value; secureStorage.defaultTopP = value }

    private val _defaultWorldTemplateId = MutableStateFlow(secureStorage.defaultWorldTemplateId)
    val defaultWorldTemplateId: StateFlow<String> = _defaultWorldTemplateId.asStateFlow()
    private val _defaultNarratorEnabled = MutableStateFlow(secureStorage.defaultNarratorEnabled)
    val defaultNarratorEnabled: StateFlow<Boolean> = _defaultNarratorEnabled.asStateFlow()
    private val _defaultChoiceGenerationEnabled = MutableStateFlow(secureStorage.defaultChoiceGenerationEnabled)
    val defaultChoiceGenerationEnabled: StateFlow<Boolean> = _defaultChoiceGenerationEnabled.asStateFlow()
    private val _defaultAntiCheatEnabled = MutableStateFlow(secureStorage.defaultAntiCheatEnabled)
    val defaultAntiCheatEnabled: StateFlow<Boolean> = _defaultAntiCheatEnabled.asStateFlow()
    private val _memoryCompactThreshold = MutableStateFlow(secureStorage.memoryCompactThreshold.toString())
    val memoryCompactThreshold: StateFlow<String> = _memoryCompactThreshold.asStateFlow()
    private val _universalContextMemoryEnabled = MutableStateFlow(secureStorage.universalContextMemoryEnabled)
    val universalContextMemoryEnabled: StateFlow<Boolean> = _universalContextMemoryEnabled.asStateFlow()
    private val _maxUploadMb = MutableStateFlow(secureStorage.maxUploadMb.toString())
    val maxUploadMb: StateFlow<String> = _maxUploadMb.asStateFlow()
    private val _maxAutoSpeakers = MutableStateFlow(secureStorage.maxAutoSpeakers.toString())
    val maxAutoSpeakers: StateFlow<String> = _maxAutoSpeakers.asStateFlow()

    private val _defaultEncyclopediaIdForAi = MutableStateFlow(
        secureStorage.defaultEncyclopediaIdForAi.takeIf { it > 0L }?.toString() ?: "",
    )
    val defaultEncyclopediaIdForAi: StateFlow<String> = _defaultEncyclopediaIdForAi.asStateFlow()

    private val _availableWorldTemplates = MutableStateFlow<List<WorldTemplateEntity>>(emptyList())
    val availableWorldTemplates: StateFlow<List<WorldTemplateEntity>> = _availableWorldTemplates.asStateFlow()
    private val _availableEncyclopedias = MutableStateFlow<List<EncyclopediaEntity>>(emptyList())
    val availableEncyclopedias: StateFlow<List<EncyclopediaEntity>> = _availableEncyclopedias.asStateFlow()
    private val _creationOptionsLoading = MutableStateFlow(false)
    val creationOptionsLoading: StateFlow<Boolean> = _creationOptionsLoading.asStateFlow()
    private val _creationOptionsError = MutableStateFlow<String?>(null)
    val creationOptionsError: StateFlow<String?> = _creationOptionsError.asStateFlow()

    fun loadCreationOptions() {
        if (_creationOptionsLoading.value) return
        viewModelScope.launch {
            _creationOptionsLoading.value = true
            _creationOptionsError.value = null
            val templates = runCatching { worldTemplateDao.getAll() }
            val encyclopedias = runCatching { encyclopediaDao.getAll() }
            templates.onSuccess { _availableWorldTemplates.value = it }
            encyclopedias.onSuccess { _availableEncyclopedias.value = it }
            _creationOptionsError.value = when {
                templates.isFailure && encyclopedias.isFailure -> "世界与百科列表读取失败，请重试"
                templates.isFailure -> "世界列表读取失败，请重试"
                encyclopedias.isFailure -> "百科列表读取失败，请重试"
                else -> null
            }
            _creationOptionsLoading.value = false
        }
    }

    fun updateDefaultWorldTemplateId(value: String) {
        _defaultWorldTemplateId.value = value
        secureStorage.defaultWorldTemplateId = value
    }
    fun updateDefaultNarratorEnabled(value: Boolean) {
        _defaultNarratorEnabled.value = value
        secureStorage.defaultNarratorEnabled = value
    }
    fun updateDefaultChoiceGenerationEnabled(value: Boolean) {
        _defaultChoiceGenerationEnabled.value = value
        secureStorage.defaultChoiceGenerationEnabled = value
    }
    fun updateDefaultAntiCheatEnabled(value: Boolean) {
        _defaultAntiCheatEnabled.value = value
        secureStorage.defaultAntiCheatEnabled = value
    }
    fun updateMemoryCompactThreshold(value: String) {
        _memoryCompactThreshold.value = value
        value.toIntOrNull()?.let { secureStorage.memoryCompactThreshold = it }
    }

    fun updateUniversalContextMemoryEnabled(value: Boolean) {
        _universalContextMemoryEnabled.value = value
        secureStorage.universalContextMemoryEnabled = value
    }

    fun updateMaxUploadMb(value: String) {
        _maxUploadMb.value = value
        value.toIntOrNull()?.let { secureStorage.maxUploadMb = it }
    }
    fun updateMaxAutoSpeakers(value: String) {
        _maxAutoSpeakers.value = value
        value.toIntOrNull()?.let { secureStorage.maxAutoSpeakers = it }
    }

    fun updateDefaultEncyclopediaIdForAi(value: String) {
        _defaultEncyclopediaIdForAi.value = value.trim()
        val id = value.trim().toLongOrNull() ?: 0L
        secureStorage.defaultEncyclopediaIdForAi = id.coerceAtLeast(0L)
    }

    // Image API
    private val _imageApiKey = MutableStateFlow(secureStorage.imageApiKey)
    val imageApiKey: StateFlow<String> = _imageApiKey.asStateFlow()
    private val _imageBaseUrl = MutableStateFlow(secureStorage.imageBaseUrl)
    val imageBaseUrl: StateFlow<String> = _imageBaseUrl.asStateFlow()
    private val _imageModel = MutableStateFlow(secureStorage.imageModel)
    val imageModel: StateFlow<String> = _imageModel.asStateFlow()

    fun updateImageApiKey(value: String) { _imageApiKey.value = value; secureStorage.imageApiKey = value }
    fun updateImageBaseUrl(value: String) { _imageBaseUrl.value = value; secureStorage.imageBaseUrl = value }
    fun updateImageModel(value: String) { _imageModel.value = value; secureStorage.imageModel = value }

    // Voice API
    private val _voiceApiKey = MutableStateFlow(secureStorage.voiceApiKey)
    val voiceApiKey: StateFlow<String> = _voiceApiKey.asStateFlow()
    private val _voiceBaseUrl = MutableStateFlow(secureStorage.voiceBaseUrl)
    val voiceBaseUrl: StateFlow<String> = _voiceBaseUrl.asStateFlow()
    private val _voiceModel = MutableStateFlow(secureStorage.voiceModel)
    val voiceModel: StateFlow<String> = _voiceModel.asStateFlow()

    fun updateVoiceApiKey(value: String) { _voiceApiKey.value = value; secureStorage.voiceApiKey = value }
    fun updateVoiceBaseUrl(value: String) { _voiceBaseUrl.value = value; secureStorage.voiceBaseUrl = value }
    fun updateVoiceModel(value: String) { _voiceModel.value = value; secureStorage.voiceModel = value }

    private val _voiceSpeechVoice = MutableStateFlow(secureStorage.voiceSpeechVoice)
    val voiceSpeechVoice: StateFlow<String> = _voiceSpeechVoice.asStateFlow()

    fun updateVoiceSpeechVoice(value: String) {
        _voiceSpeechVoice.value = value
        secureStorage.voiceSpeechVoice = value
    }

    private val _voicePresetPrefixModel = MutableStateFlow(secureStorage.voicePresetPrefixModel)
    val voicePresetPrefixModel: StateFlow<String> = _voicePresetPrefixModel.asStateFlow()

    fun updateVoicePresetPrefixModel(value: String) {
        _voicePresetPrefixModel.value = value
        secureStorage.voicePresetPrefixModel = value
    }

    private val _allowSessionThinkMax = MutableStateFlow(secureStorage.allowSessionThinkMax)
    val allowSessionThinkMax: StateFlow<Boolean> = _allowSessionThinkMax.asStateFlow()

    private val _thinkMaxModel = MutableStateFlow(secureStorage.thinkMaxModel)
    val thinkMaxModel: StateFlow<String> = _thinkMaxModel.asStateFlow()

    fun updateAllowSessionThinkMax(value: Boolean) {
        _allowSessionThinkMax.value = value
        secureStorage.allowSessionThinkMax = value
    }

    fun updateThinkMaxModel(value: String) {
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
            val base = _imageBaseUrl.value.trim()
            val imageKeyOnly = _imageApiKey.value.trim()
            val dialogKey = _apiKey.value.trim()
            val key = imageKeyOnly.ifBlank { dialogKey }
            val model = _imageModel.value.trim().ifBlank { "dall-e-3" }
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
                    onMessage(ProbeUiMessages.formatProbeDone("image", ok, err, done, _imageBaseUrl.value))
                },
                onFailure = { onMessage(it.message ?: "请求失败") },
            )
        }
    }

}

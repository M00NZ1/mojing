package com.mojing.app.ui.chat

import com.mojing.app.domain.story.NovelChapter
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.ChatDraftSnapshot
import com.mojing.app.data.ChatDraftStore
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.data.local.dao.MessageRecallImpact
import com.mojing.app.data.local.dao.MessageRecallBlockedException
import com.mojing.app.data.local.dao.AttachmentDao
import com.mojing.app.data.local.dao.BookmarkDao
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.ParticipantDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.dao.SessionEventNodeDao
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.repository.ImageRepository
import com.mojing.app.data.remote.ImageApiService
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.engine.MemoryV2Manager
import com.mojing.app.data.local.dao.SessionMemorySegmentDao
import com.mojing.app.data.local.dao.SessionMemoryCorrectionDao
import com.mojing.app.data.local.dao.SessionWorldDao
import com.mojing.app.data.local.dao.SessionBranchDao
import com.mojing.app.data.local.dao.CharacterStateDao
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.data.local.entity.SessionMemoryCorrectionEntity
import com.mojing.app.domain.config.ApiKeyResolver
import com.mojing.app.domain.config.ImageBasePromote
import com.mojing.app.domain.config.ImageGenResolved
import com.mojing.app.domain.config.VoiceTtsParams
import com.mojing.app.data.local.entity.SessionWorldCredentialDraft
import com.mojing.app.data.local.entity.MessageBookmarkEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.domain.engine.CharacterMediaMarkers
import com.mojing.app.domain.engine.ChatEngine
import com.mojing.app.domain.engine.ConversationMessageText
import com.mojing.app.domain.engine.PromptBuilder
import com.mojing.app.domain.engine.StructuredParser
import com.mojing.app.domain.engine.StreamState
import com.mojing.app.domain.engine.TokenCounter
import com.mojing.app.domain.engine.TokenBudgetManager
import com.mojing.app.domain.engine.SlidingWindowBuilder
import com.mojing.app.domain.engine.CharacterSnapshotExtractor
import com.mojing.app.domain.engine.MemoryCompactor
import com.mojing.app.domain.engine.ContextBuilder
import com.mojing.app.domain.engine.SedimentEngine
import com.mojing.app.domain.engine.NarratorEngine
import com.mojing.app.domain.engine.SpeakerScheduler
import com.mojing.app.domain.engine.OutputProcessor
import com.mojing.app.domain.engine.UniversalContextMemoryManager
import com.mojing.app.domain.story.StoryCanon
import com.mojing.app.domain.chat.MainBranchChatExportWriter
import com.mojing.app.domain.chat.TavernChatImportParser
import com.mojing.app.domain.usecase.MessageSubmissionTransaction
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.util.ApiRootLines
import com.mojing.app.util.ChatAttachmentFiles
import com.mojing.app.util.UsbSessionLog
import com.mojing.app.media.AndroidTts
import com.mojing.app.media.HttpTts
import com.mojing.app.media.TtsSpeakText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject

private data class CharacterUiMaps(
    val names: Map<Long, String>,
    val avatars: Map<Long, String>,
    val colors: Map<Long, String>,
    val cardImages: Map<Long, String>,
)

private class GenerationContext(
    val branchId: String,
    val expectedTailMessageId: Long? = null,
    var swipeGroupId: String? = null,
    var swipeSourceMessageId: Long? = null,
    var contextMessagesOverride: List<MessageEntity>? = null,
)

data class TavernChatImportResult(
    val importedCount: Int,
    val duplicate: Boolean,
)

data class GalleryImageSaveResult(
    val requestedCount: Int,
    val savedCount: Int,
) {
    val failedCount: Int get() = requestedCount - savedCount
}

internal suspend fun saveGalleryImageAttachments(
    attachments: List<MessageAttachmentEntity>,
    save: suspend (MessageAttachmentEntity) -> Result<Unit>,
): GalleryImageSaveResult {
    val images = attachments.filter { attachment ->
        attachment.storagePath.isNotBlank() &&
            (attachment.assetType.equals("image", ignoreCase = true) ||
                attachment.mimeType.startsWith("image/", ignoreCase = true))
    }
    var savedCount = 0
    for (attachment in images) {
        if (save(attachment).isSuccess) savedCount++
    }
    return GalleryImageSaveResult(images.size, savedCount)
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val characterDao: CharacterDao,
    private val participantDao: ParticipantDao,
    private val sessionWorldDao: SessionWorldDao,
    private val sessionBranchDao: SessionBranchDao,
    private val memorySegmentDao: SessionMemorySegmentDao,
    private val memoryCorrectionDao: SessionMemoryCorrectionDao,
    private val eventNodeDao: SessionEventNodeDao,
    private val costRecorder: CostRecorder,
    private val chatEngine: ChatEngine,
    private val secureStorage: SecureStorage,
    private val promptBuilder: PromptBuilder,
    private val memoryCompactor: MemoryCompactor,
    private val contextBuilder: ContextBuilder,
    private val tokenBudgetManager: TokenBudgetManager,
    private val slidingWindowBuilder: SlidingWindowBuilder,
    private val snapshotExtractor: CharacterSnapshotExtractor,
    private val memoryV2Manager: MemoryV2Manager,
    private val universalContextMemoryManager: UniversalContextMemoryManager,
    private val sedimentEngine: SedimentEngine,
    private val characterStateDao: CharacterStateDao,
    private val attachmentDao: AttachmentDao,
    private val messageSubmissionTransaction: MessageSubmissionTransaction,
    private val bookmarkDao: BookmarkDao,
    private val imageRepository: ImageRepository,
    private val llmApiService: LlmApiService,
    private val imageApiService: ImageApiService,
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val narratorEngine: NarratorEngine,
    private val chatDraftStore: ChatDraftStore,
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    private companion object {
        const val INITIAL_MESSAGE_WINDOW_SIZE = 80
        const val MESSAGE_PAGE_SIZE = 40
        const val MAX_MESSAGE_WINDOW_SIZE = 200
        const val SEARCH_RESULT_LIMIT = 100
        const val MODEL_CONTEXT_MESSAGE_LIMIT = 400
        const val DRAFT_SUBMISSION_JSON_KEY = "draftSubmissionId"
    }

    private val sessionId: Long = savedStateHandle["sessionId"] ?: 0L
    private val sourceMessageId: Long = savedStateHandle["sourceMessageId"] ?: 0L
    private val sourceBranchId: String = savedStateHandle["sourceBranchId"] ?: ""
    private val _state = MutableStateFlow(ChatContract.State(sessionId = sessionId))
    private val bookmarkRefreshRevision = java.util.concurrent.atomic.AtomicLong()
    private val eventRefreshRevision = java.util.concurrent.atomic.AtomicLong()
    private val correctionRefreshRevision = java.util.concurrent.atomic.AtomicLong()
    val state: StateFlow<ChatContract.State> = _state.asStateFlow()
    private var roundPlatform: com.mojing.app.data.ModelPlatform? = null
    private val _modelSelectionLabel = MutableStateFlow(currentModelLabel())
    val modelSelectionLabel: StateFlow<String> = _modelSelectionLabel.asStateFlow()

    fun availableModelPlatforms() = runCatching { secureStorage.modelPlatforms() }.getOrElse {
        _state.update { it.copy(error = "平台配置暂时无法读取，原数据已保留") }
        emptyList()
    }

    private fun selectedPlatform(): com.mojing.app.data.ModelPlatform? {
        val selection = secureStorage.sessionModelSelection(sessionId) ?: return null
        val platform = secureStorage.modelPlatforms().firstOrNull { it.id == selection.first }
            ?: error("所选平台不存在，请重新选择模型")
        check(selection.second in platform.models) { "所选模型已变更，请重新选择模型" }
        return platform.copy(selectedModel = selection.second)
    }

    private fun currentModelLabel(): String = runCatching {
        selectedPlatform()?.let { "${it.name} · ${it.selectedModel}" }
            ?: "跟随角色与模型设置"
    }.getOrDefault("请选择模型")

    fun currentChatModelSelection(): Pair<String, String>? = runCatching {
        selectedPlatform()?.let { it.id to it.selectedModel }
    }.getOrNull()

    fun refreshModelSelection() {
        _modelSelectionLabel.value = currentModelLabel()
    }

    fun selectChatModel(platformId: String, model: String, onSaved: () -> Unit = {}) {
        saveChatModelSelection({ secureStorage.selectSessionModel(sessionId, platformId, model) }, onSaved)
    }

    fun followConfiguredChatModels(onSaved: () -> Unit = {}) {
        saveChatModelSelection({ secureStorage.clearSessionModelSelection(sessionId) }, onSaved)
    }

    private fun saveChatModelSelection(save: () -> Unit, onSaved: () -> Unit) {
        if (_state.value.modelSelectionSaving) return
        _state.update { it.copy(modelSelectionSaving = true, modelSelectionError = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { save() }
                refreshModelSelection()
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(modelSelectionError = "模型选择未保存，请检查平台配置后重试") }
            } finally {
                _state.update { it.copy(modelSelectionSaving = false) }
            }
        }
    }

    private fun requestPlatform(): com.mojing.app.data.ModelPlatform? =
        if (activeGeneration != null) roundPlatform else selectedPlatform()
    private fun chatConnection(world: SessionWorldEntity?, character: CharacterEntity? = null): com.mojing.app.domain.config.ChatConnection {
        requestPlatform()?.let {
            return com.mojing.app.domain.config.ChatConnectionResolver.complete("所选平台", it.apiKey, it.baseUrl)
        }
        return com.mojing.app.domain.config.ChatConnectionResolver.resolve(
            worldKey = world?.sessionLlmApiKey.orEmpty(), worldBase = world?.sessionLlmBaseUrl.orEmpty(),
            characterKey = character?.apiKey.orEmpty(), characterBase = character?.apiBaseUrl.orEmpty(),
            publicKey = secureStorage.publicApiKey, publicBase = secureStorage.publicBaseUrl,
        )
    }

    /** All AI generation entry points share one Job to prevent concurrent writes. */
    private var generationJob: Job? = null
    private var activeGeneration: GenerationContext? = null
    private var branchTransitionJob: Job? = null
    private var initializationJob: Job? = null
    private var historyLoadJob: Job? = null
    private var messageSearchJob: Job? = null
    private var messageSearchRevision = 0L
    private var activeDraftSubmissionId: String? = null
    private var narratorDraftRevision = 0L
    private var imageDraftRevision = 0L
    private var quoteDraftRevision = 0L

    private fun persistCurrentDraft() {
        val current = _state.value
        runCatching {
            chatDraftStore.save(
                sessionId,
                ChatDraftSnapshot(
                    inputText = current.inputText,
                    quotedMessageId = current.quotingMessage?.id,
                    narratorGuidance = current.narratorGuidance,
                    imagePrompt = current.imagePrompt,
                    pendingAttachmentPaths = current.pendingLocalImagePaths,
                    pendingSubmissionId = activeDraftSubmissionId,
                ),
            )
        }.onFailure {
            _state.update { state ->
                state.copy(error = state.error ?: "草稿未能保存到本机，请检查存储空间")
            }
        }
    }

    private fun beginDraftSubmission(submissionId: String): Boolean {
        activeDraftSubmissionId = submissionId
        val current = _state.value
        val saved = runCatching {
            chatDraftStore.saveBeforeSubmission(
                sessionId,
                ChatDraftSnapshot(
                    inputText = current.inputText,
                    quotedMessageId = current.quotingMessage?.id,
                    narratorGuidance = current.narratorGuidance,
                    imagePrompt = current.imagePrompt,
                    pendingAttachmentPaths = current.pendingLocalImagePaths,
                    pendingSubmissionId = submissionId,
                ),
            )
        }.getOrDefault(false)
        if (!saved) {
            activeDraftSubmissionId = null
            _state.update { state ->
                state.copy(error = "发送前无法保存本机草稿，请检查存储空间")
            }
        }
        return saved
    }

    private fun finishDraftSubmission(submissionId: String) {
        if (activeDraftSubmissionId == submissionId) activeDraftSubmissionId = null
        persistCurrentDraft()
    }

    private fun draftSubmissionStructuredContent(submissionId: String): String = JsonObject().apply {
        addProperty(DRAFT_SUBMISSION_JSON_KEY, submissionId)
    }.toString()

    private fun draftSubmissionSearchMarker(submissionId: String): String =
        "\"$DRAFT_SUBMISSION_JSON_KEY\":\"$submissionId\""

    private fun launchSingleGeneration(
        expectedTailMessageId: Long? = null,
        draftSubmissionId: String? = null,
        block: suspend (GenerationContext) -> Unit,
    ): Boolean {
        if (activeGeneration != null || branchTransitionJob?.isActive == true) return false
        if (_state.value.modelSelectionSaving) {
            _state.update { it.copy(error = "模型选择正在保存，请稍候再发送") }
            return false
        }
        roundPlatform = try { selectedPlatform() } catch (_: Exception) {
            _state.update { it.copy(error = "所选平台或模型已变更，请重新选择后发送") }
            return false
        }
        historyLoadJob?.cancel()
        historyLoadJob = null
        _state.update { it.copy(isLoadingHistory = false) }
        val generation = GenerationContext(
            branchId = currentBranchId(),
            expectedTailMessageId = expectedTailMessageId,
        )
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            try {
                block(generation)
            } catch (_: CancellationException) {
                // stopGeneration owns the immediate UI reset; stale jobs must stay silent.
            } catch (e: Exception) {
                if (generationJob === owner && activeGeneration === generation) {
                    _state.value = _state.value.copy(
                        streamingText = "",
                        error = UserFacingStrings.operationFailed(),
                    )
                    refreshMessagesUi()
                }
            } finally {
                draftSubmissionId?.let(::finishDraftSubmission)
                if (generationJob === owner && activeGeneration === generation) {
                    generationJob = null
                    activeGeneration = null
                    resetGenerationUi(clearError = false)
                }
            }
        }
        generationJob = job
        activeGeneration = generation
        _state.value = _state.value.copy(
            isGenerating = true,
            memoryCompactionChunk = null,
            streamingText = "",
            pendingRoundSpeakers = emptyList(),
            speakerPlanSummary = null,
            error = null,
        )
        RetainedChatSessions.retainGeneration(sessionId, job, appContext) { _state.value.error }
        job.start()
        return true
    }

    private fun launchSessionMaintenance(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY, block = block)
        RetainedChatSessions.retainGeneration(sessionId, job, appContext)
        job.start()
    }

    private fun GenerationContext.ensureCurrent() {
        if (activeGeneration !== this || generationJob?.isActive != true) {
            throw CancellationException("stale generation")
        }
    }

    private suspend fun GenerationContext.ensureExpectedUserTail(): Boolean {
        val expectedId = expectedTailMessageId ?: return true
        ensureCurrent()
        val tail = getMessageTailForBranch(branchId, 1).lastOrNull()
        if (tail?.id == expectedId && tail.speakerType == "user") return true
        _state.value = _state.value.copy(error = "只能继续生成当前故事线最后一条用户消息的回复")
        refreshMessagesUi()
        return false
    }

    private fun resetGenerationUi(clearError: Boolean = true) {
        _state.value = _state.value.copy(
            isGenerating = false,
            memoryCompactionChunk = null,
            streamingText = "",
            pendingRoundSpeakers = emptyList(),
            speakerPlanSummary = null,
            error = if (clearError) null else _state.value.error,
        )
    }

    private fun canMutateRoundConfiguration(): Boolean {
        if (!_state.value.isGenerating) return true
        _state.update {
            it.copy(error = "回复生成期间暂不能调整对话设置，请先停止或等待完成")
        }
        return false
    }

    private fun launchBranchTransition(
        onSuccess: (() -> Unit)? = null,
        block: suspend () -> Unit,
    ): Boolean {
        if (activeGeneration != null || branchTransitionJob?.isActive == true) return false
        historyLoadJob?.cancel()
        historyLoadJob = null
        _state.update { it.copy(isLoadingHistory = false) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            var completed = false
            try {
                block()
                completed = true
            } finally {
                if (branchTransitionJob === owner) branchTransitionJob = null
                if (completed) onSuccess?.invoke()
            }
        }
        branchTransitionJob = job
        job.start()
        return true
    }

    private fun effectiveThinkMax(character: CharacterEntity, sessionThinkMax: Boolean): Boolean {
        if (character.thinkMaxEnabled) return true
        if (!secureStorage.allowSessionThinkMax) return false
        return sessionThinkMax
    }

    private fun characterUsesOwnChatEndpoint(character: CharacterEntity): Boolean {
        val c = character.apiBaseUrl.trim()
        return c.isNotEmpty() && !isPlaceholderApiBase(c)
    }

    /**
     * 主对话模型 id（不含思考覆盖）。
     * 角色未配置 API Key、且未单独指定非占位服务根地址时，实际走的是设置/会话里的线路，
     * 此时应优先用「设置里的公共模型」，避免角色卡默认的 `deepseek-chat` 等与 SiliconFlow 等网关不匹配导致 400。
     */
    private fun resolveMainChatModelId(character: CharacterEntity, connection: com.mojing.app.domain.config.ChatConnection): String {
        requestPlatform()?.let { return it.selectedModel }
        return connection.model(character.modelName, secureStorage.publicModel)
    }

    /**
     * 对话请求使用的模型 id。
     * - 未开思考/Max：主对话模型（角色 → 公共）。
     * - 开启思考/Max：仍用主模型，除非用户在「思考模型覆盖」或设置里填了可选覆盖；**不会**在客户端把模型名改成其它 id。
     *   若当前模型不支持思考/Max，由接口拒绝，再通过 [streamErrorThinkMaxRoute] 提示。
     */
    private fun resolveChatLlmModel(character: CharacterEntity, sessionThinkMax: Boolean, connection: com.mojing.app.domain.config.ChatConnection): String? {
        requestPlatform()?.let { return it.selectedModel }
        return connection.model(character.modelName, secureStorage.publicModel,
            effectiveThinkMax(character, sessionThinkMax), character.thinkMaxModelName,
            secureStorage.thinkMaxModel).ifBlank { null }
    }

    /** 单张聊天附件上限（字节），与 Web `max_upload_mb` 对齐 */
    fun maxAttachmentBytes(): Long =
        secureStorage.maxUploadMb.coerceAtLeast(1).toLong() * 1024L * 1024L

    private fun isPlaceholderApiBase(url: String): Boolean =
        ApiKeyResolver.isPlaceholderApiBase(url)

    /** 多行地址中只要有一行非占位即视为已配置 */
    /** 角色「对话」Base：占位则视为未配置。 */
    private fun resolveEffectiveCharChatBase(character: CharacterEntity): String {
        val c = character.apiBaseUrl.trim()
        return if (!isPlaceholderApiBase(c)) c else ""
    }

    /**
     * 当「公共配图线路」与主线路不同且公共 Key 可用时，允许对生图失败做一次公共兜底重试。
     * 主线路 Key/Base/Model 解析顺序：角色开启配图且填了生图字段 → 本会话「配图」覆盖 → 设置里全局配图 → 公共 Key/根地址；
     * 不混入对话 LLM 的 Key/Base。
     */
    private fun shouldRetryImageGenWithPublic(primary: ImageGenResolved): Boolean {
        val pub = ApiKeyResolver.resolveImageGenStrictPublicResolved(secureStorage)
        if (pub.apiKey.isBlank()) return false
        return primary != pub
    }

    private fun applyImageBasePromotion(promote: ImageBasePromote, usedNormalizedBase: String) {
        when (promote) {
            ImageBasePromote.StorageImage -> {
                val raw = secureStorage.imageBaseUrl
                if (!ApiRootLines.hasMultipleCandidates(raw)) return
                val next = ApiRootLines.promoteLineToFront(raw, usedNormalizedBase, imageApiService::normalizeImageBase)
                if (next != raw) {
                    secureStorage.imageBaseUrl = next
                    UsbSessionLog.i("ChatImageGen", "配图：已将可用根地址置顶（全局配图多行）")
                }
            }
            ImageBasePromote.StoragePublic -> {
                val raw = secureStorage.publicBaseUrl
                if (!ApiRootLines.hasMultipleCandidates(raw)) return
                val next = ApiRootLines.promoteLineToFront(raw, usedNormalizedBase, imageApiService::normalizeImageBase)
                if (next != raw) {
                    secureStorage.publicBaseUrl = next
                    UsbSessionLog.i("ChatImageGen", "配图：已将可用根地址置顶（对话根地址多行，作配图兜底）")
                }
            }
            ImageBasePromote.None -> Unit
        }
    }

    private fun shouldPromoteGlobalChatBaseUrl(world: SessionWorldEntity?, character: CharacterEntity): Boolean {
        val w = world?.sessionLlmBaseUrl?.trim().orEmpty()
        if (w.isNotEmpty() && !isPlaceholderApiBase(w)) return false
        val c = character.apiBaseUrl.trim()
        if (c.isNotEmpty() && !isPlaceholderApiBase(c)) return false
        return ApiRootLines.hasMultipleCandidates(secureStorage.publicBaseUrl)
    }

    private fun promoteGlobalChatBaseIfNeeded(usedNorm: String) {
        val raw = secureStorage.publicBaseUrl
        if (!ApiRootLines.hasMultipleCandidates(raw)) return
        val next = ApiRootLines.promoteLineToFront(raw, usedNorm, llmApiService::normalizeOpenAiCompatibleBase)
        if (next != raw) {
            secureStorage.publicBaseUrl = next
            UsbSessionLog.i("ChatLlm", "对话：已将可用根地址置顶（公共根地址多行）")
        }
    }

    private fun maybePromoteVoiceStackField(vpBaseRaw: String, usedNorm: String) {
        val t = vpBaseRaw.trim()
        val vr = secureStorage.voiceBaseUrl.trim()
        if (vr == t && ApiRootLines.hasMultipleCandidates(vr)) {
            val n = ApiRootLines.promoteLineToFront(vr, usedNorm, HttpTts::normalizeVoiceRoutingBase)
            if (n != vr) secureStorage.voiceBaseUrl = n
            return
        }
        val pr = secureStorage.publicBaseUrl.trim()
        if (pr == t && ApiRootLines.hasMultipleCandidates(pr)) {
            val n = ApiRootLines.promoteLineToFront(pr, usedNorm, HttpTts::normalizeVoiceRoutingBase)
            if (n != pr) secureStorage.publicBaseUrl = n
        }
    }

    private suspend fun generateImageWithPublicFallback(
        prompt: String,
        char: CharacterEntity?,
        world: SessionWorldEntity?,
        characterId: Long?,
    ): ImageGenWithFallbackResult {
        val primary = ApiKeyResolver.resolveImageGenPrimaryResolved(char, world, secureStorage)
        if (primary.apiKey.isBlank()) {
            return ImageGenWithFallbackResult(
                Result.failure(IllegalStateException("no image api key")),
                primary.model,
                false,
            )
        }
        var result = imageRepository.generateImage(
            prompt,
            primary.apiKey,
            primary.baseUrlRaw,
            primary.model,
            sessionId = sessionId,
            characterId = characterId,
            onUsedBase = { applyImageBasePromotion(primary.promote, it) },
        )
        var modelUsed = primary.model
        var succeededOnPublicRetry = false
        if (result.isFailure && shouldRetryImageGenWithPublic(primary)) {
            UsbSessionLog.w(
                "ChatImageGen",
                "primary image line failed, retry public: ${result.exceptionOrNull()?.message}",
            )
            val pub = ApiKeyResolver.resolveImageGenStrictPublicResolved(secureStorage)
            result = imageRepository.generateImage(
                prompt,
                pub.apiKey,
                pub.baseUrlRaw,
                pub.model,
                sessionId = sessionId,
                characterId = characterId,
                onUsedBase = { applyImageBasePromotion(pub.promote, it) },
            )
            modelUsed = pub.model
            succeededOnPublicRetry = result.isSuccess
        }
        return ImageGenWithFallbackResult(result, modelUsed, succeededOnPublicRetry)
    }

    private data class ImageGenWithFallbackResult(
        val result: Result<String>,
        val modelUsed: String,
        val succeededOnPublicRetry: Boolean,
    )

    /** 世界开关开启时：解析 `<GEN_IMAGE>` / `<GEN_SPEECH>` 并插入占位消息与附件。 */
    private suspend fun runAutoCharacterMediaJobs(
        generation: GenerationContext,
        character: CharacterEntity,
        world: SessionWorldEntity?,
        parsed: CharacterMediaMarkers.Parsed,
        sourceReplyMessageId: Long,
    ) {
        val w = world ?: return
        if (w.autoCharacterImageGen && parsed.imagePrompts.isNotEmpty()) {
            for (prompt in parsed.imagePrompts.take(1)) {
                var pendingId: Long? = null
                var generatedPath: String? = null
                var finalized = false
                try {
                    generation.ensureCurrent()
                    withContext(NonCancellable) {
                        pendingId = messageDao.insert(
                            MessageEntity(
                                sessionId = sessionId,
                                speakerType = "character",
                                characterId = character.id,
                                content = "🖼 配图生成中…",
                                structuredContentJson = """{"derived_media_version":1,"derived_media_kind":"image"}""",
                                branchId = generation.branchId,
                                parentMessageId = sourceReplyMessageId,
                                includeInContext = false,
                            ),
                        )
                    }
                    val insertedId = requireNotNull(pendingId)
                    refreshMessagesUi()
                    val primary = ApiKeyResolver.resolveImageGenPrimaryResolved(character, w, secureStorage)
                    if (primary.apiKey.isBlank()) {
                        UsbSessionLog.w(
                            "ChatImageGen",
                            "auto char image: missing key sid=$sessionId char=${character.id}",
                        )
                        messageDao.updateContent(insertedId, "🖼 未配置生图 Key")
                        finalized = true
                        refreshMessagesUi()
                        continue
                    }
                    val attempt = generateImageWithPublicFallback(prompt, character, w, character.id)
                    generation.ensureCurrent()
                    attempt.result.fold(
                        onSuccess = { urlOrB64 ->
                            val local = imageRepository.saveGeneratedImageForSession(urlOrB64, sessionId)
                            generatedPath = local
                            generation.ensureCurrent()
                            if (local != null) {
                                messageDao.updateContent(insertedId, "")
                                attachmentDao.insert(
                                    MessageAttachmentEntity(
                                        messageId = insertedId,
                                        assetType = "image",
                                        fileName = java.io.File(local).name,
                                        mimeType = "image/png",
                                        storagePath = local,
                                        generationPrompt = prompt,
                                        generationModel = attempt.modelUsed,
                                    ),
                                )
                                if (attempt.succeededOnPublicRetry) {
                                    UsbSessionLog.i("ChatImageGen", "auto char image succeeded on public retry sid=$sessionId")
                                }
                            } else {
                                messageDao.updateContent(insertedId, "🖼 配图保存失败")
                            }
                        },
                        onFailure = { e ->
                            UsbSessionLog.e(
                                "ChatImageGen",
                                "auto char image failed sid=$sessionId model=${attempt.modelUsed}",
                                e,
                            )
                            messageDao.updateContent(insertedId, "🖼 配图生成失败")
                        },
                    )
                    finalized = true
                    refreshMessagesUi()
                } finally {
                    if (!finalized) rollbackPendingMedia(pendingId, generatedPath)
                }
            }
        }
        if (w.autoCharacterSpeech && parsed.speechTexts.isNotEmpty()) {
            generation.ensureCurrent()
            speakMessage(parsed.speechTexts.take(2).joinToString("\n"), character.id)
        }
    }

    private suspend fun rollbackPendingMedia(messageId: Long?, storagePath: String?) {
        if (messageId == null && storagePath == null) return
        withContext(NonCancellable) {
            messageId?.let { runCatching { messageDao.delete(it) } }
            storagePath?.let { runCatching { java.io.File(it).delete() } }
            runCatching { refreshMessagesUi() }
        }
    }

    init {
        viewModelScope.launch {
            uiPreferencesRepository.chatDensity.collect { d ->
                _state.update { it.copy(chatDensity = d) }
            }
        }
        retryInitialization()
    }

    fun retryInitialization() {
        if (initializationJob?.isActive == true) return
        _state.update {
            it.copy(
                isReady = false,
                initialLoadError = null,
                sessionNotFound = false,
            )
        }
        initializationJob = viewModelScope.launch {
            try {
                initializeSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isReady = false,
                        initialLoadError = if (sourceMessageId > 0L) "来源对话加载失败，请重试" else "对话加载失败，请重试",
                    )
                }
                runCatching { UsbSessionLog.e("ChatInit", "failed to load session sid=$sessionId", e) }
            }
        }
    }

    private suspend fun initializeSession() {
        val session = sessionDao.getById(sessionId)
        if (session == null) {
            _state.update {
                it.copy(
                    error = "对话不存在或已删除",
                    sessionNotFound = true,
                    isReady = true,
                    initialLoadError = null,
                )
            }
            return
        }
        val restoredDraft = chatDraftStore.load(sessionId)
        val locallyValidAttachmentPaths = ChatAttachmentFiles.validPendingPaths(
            appContext,
            sessionId,
            restoredDraft.pendingAttachmentPaths,
            maxAttachmentBytes(),
        )
        val referencedAttachmentPaths = if (locallyValidAttachmentPaths.isEmpty()) {
            emptySet()
        } else {
            attachmentDao.getReferencedStoragePaths(locallyValidAttachmentPaths).toSet()
        }
        val restoredAttachmentPaths = locallyValidAttachmentPaths.filterNot(referencedAttachmentPaths::contains)
        val distinctDraftAttachmentCount = restoredDraft.pendingAttachmentPaths.distinct().size
        val invalidRestoredAttachmentCount = distinctDraftAttachmentCount - locallyValidAttachmentPaths.size
        val alreadySentAttachmentCount = locallyValidAttachmentPaths.size - restoredAttachmentPaths.size
        val pendingSubmissionId = restoredDraft.pendingSubmissionId
        val draftSubmissionCommitted = pendingSubmissionId != null && messageDao.countDraftSubmission(
            sessionId,
            draftSubmissionSearchMarker(pendingSubmissionId),
        ) > 0
        val restoredInputText = if (draftSubmissionCommitted) "" else restoredDraft.inputText
        val restoredQuote = if (draftSubmissionCommitted) null else restoredDraft.quotedMessageId?.let {
            messageDao.getByIdInSession(it, sessionId)
        }?.takeIf { ChatMessageTextFormat.quoteSnippet(it.content, 120, it.speakerType).isNotBlank() }
        val unavailableQuote = !draftSubmissionCommitted && restoredDraft.quotedMessageId != null && restoredQuote == null

        if (
            quoteDraftRevision == 0L && (unavailableQuote || invalidRestoredAttachmentCount > 0 ||
                alreadySentAttachmentCount > 0 || pendingSubmissionId != null)
        ) {
            chatDraftStore.save(
                sessionId,
                restoredDraft.copy(
                    inputText = restoredInputText,
                    quotedMessageId = restoredQuote?.id,
                    pendingAttachmentPaths = restoredAttachmentPaths,
                    pendingSubmissionId = null,
                ),
            )
        }
        val participants = participantDao.getBySession(sessionId)
        var world = sessionWorldDao.getBySession(sessionId)
        if (world == null) {
            sessionWorldDao.upsert(SessionWorldEntity(sessionId = sessionId))
            world = sessionWorldDao.getBySession(sessionId)
        }
        val branches = sessionBranchDao.getBySession(sessionId)
        val rememberedBranchId = runCatching {
            uiPreferencesRepository.getLastChatBranch(sessionId)
        }.getOrDefault("main")
        if (sourceMessageId > 0L && sourceBranchId.isNotBlank() && sourceBranchId != "main" && branches.none { it.branchId == sourceBranchId }) {
            _state.update { it.copy(isReady = false, initialLoadError = "来源故事线已不存在，请返回百科查看保留的资料。") }
            return
        }
        val requestedBranchId = if (sourceMessageId > 0L && sourceBranchId.isNotBlank()) sourceBranchId else rememberedBranchId
        val initialBranchId = requestedBranchId.takeIf { branchId ->
            branchId == "main" || branches.any { it.branchId == branchId }
        } ?: "main"
        val invalidRememberedBranch = sourceMessageId <= 0L && rememberedBranchId != "main" && initialBranchId == "main"
        if (invalidRememberedBranch) {
            runCatching { uiPreferencesRepository.clearLastChatBranch(sessionId) }
        }
        val memorySegments = memorySegmentDao.getRecentForBranch(sessionId, initialBranchId)
        val memoryCorrections = memoryCorrectionDao.getVisible(sessionId, initialBranchId)
        val eventNodes = eventNodeDao.getForBranch(sessionId, initialBranchId)
        val initialRows = getMessageTailForBranch(initialBranchId, INITIAL_MESSAGE_WINDOW_SIZE + 1)
        val hasOlderMessages = initialRows.size > INITIAL_MESSAGE_WINDOW_SIZE
        val msgs = initialRows.take(INITIAL_MESSAGE_WINDOW_SIZE).asReversed()
        val sourcePreviews = branchSourcePreviews(branches)
        val maps = buildCharacterPresentationMaps(participants)

        val firstChar = participants.firstOrNull()?.characterId?.let { characterDao.getById(it) }
        val displayCap = session.displayContextTokenLimit.takeIf { it > 0 } ?: 1_000_000
        val convEst = msgs.filter { it.includeInContext }
            .sumOf { TokenCounter.estimateScaledPrefix(ConversationMessageText.forDerivedContext(it)) }

        val attMap = attachmentsForMessages(msgs)
        val marks = bookmarkDao.getBySession(sessionId)
        val bookmarkIds = marks.map { it.messageId }.toSet()
        val previews = messagePreviews(bookmarkIds, maxChars = 120)
        val roundChoices = buildRoundChoiceSnapshot(world, msgs)

        _state.value = _state.value.copy(
            sessionTitle = session.title,
            messages = msgs,
            displayLines = msgs.toChatDisplayLines(),
            hasOlderMessages = hasOlderMessages,
            hasNewerMessages = false,
            isLoadingHistory = false,
            messageAttachments = attMap,
            participants = participants, world = world,
            encyclopediaFoundation = contextBuilder.encyclopediaFoundation(world),
            contextMemoryText = universalContextMemoryManager.getFormattedMemory(sessionId, initialBranchId),
            memorySegments = memorySegments, eventNodes = eventNodes, branches = branches,
            memoryCorrections = memoryCorrections,
            currentBranchId = initialBranchId,
            roundChoiceOptions = roundChoices.options,
            roundChoiceMessageId = roundChoices.sourceMessageId,
            branchSourcePreviews = sourcePreviews,
            characterNames = maps.names,
            characterAvatars = maps.avatars,
            characterCardImages = maps.cardImages,
            characterColors = maps.colors,
            bookmarks = marks,
            bookmarkedMessageIds = bookmarkIds,
            bookmarkPreviews = previews,
            userDisplayName = secureStorage.userName,
            userAvatarImagePath = secureStorage.userAvatarImagePath,
            userAvatarColor = secureStorage.userAvatarColor,
            allowSessionThinkMax = secureStorage.allowSessionThinkMax,
            sessionThinkMaxEnabled = session.thinkMaxEnabled,
            characterForcesThinkMax = firstChar?.thinkMaxEnabled == true,
            displayContextTokenLimit = displayCap,
            conversationTokenEstimate = convEst,
            inputText = _state.value.inputText.ifEmpty { restoredInputText },
            quotingMessage = if (quoteDraftRevision == 0L) restoredQuote else _state.value.quotingMessage,
            imagePrompt = if (imageDraftRevision == 0L) restoredDraft.imagePrompt else _state.value.imagePrompt,
            narratorGuidance = if (narratorDraftRevision == 0L) restoredDraft.narratorGuidance else _state.value.narratorGuidance,
            pendingLocalImagePaths = if (_state.value.pendingLocalImagePaths.isEmpty()) {
                restoredAttachmentPaths
            } else {
                _state.value.pendingLocalImagePaths
            },
            error = _state.value.error ?: buildList {
                if (unavailableQuote && quoteDraftRevision == 0L) add("引用原文已不可用，请重新选择；输入草稿已保留")
                if (invalidRestoredAttachmentCount > 0) {
                    add("已移除 $invalidRestoredAttachmentCount 个无法读取的待发送图片")
                }
                if (alreadySentAttachmentCount > 0) {
                    add("已从待发送区移除 $alreadySentAttachmentCount 个已经发送的图片")
                }
                if (draftSubmissionCommitted && restoredDraft.inputText.isNotEmpty()) {
                    add("已从草稿中移除已经发送的文字")
                }
                if (invalidRememberedBranch) {
                    add("上次打开的故事线已不存在，已返回主线")
                }
            }.joinToString("；").ifBlank { null },
            isReady = sourceMessageId <= 0L,
            initialLoadError = null,
        )
        if (sourceMessageId > 0L) {
            val located = loadMessageWindow(initialBranchId, sourceMessageId)
            _state.update { it.copy(isReady = located,
                initialLoadError = if (located) null else "来源消息已删除或不在来源故事线，请返回百科。") }
        }
    }

    private suspend fun buildCharacterPresentationMaps(
        participants: List<SessionParticipantEntity>
    ): CharacterUiMaps {
        val names = mutableMapOf<Long, String>()
        val avatars = mutableMapOf<Long, String>()
        val colors = mutableMapOf<Long, String>()
        val cardImages = mutableMapOf<Long, String>()
        participants.map { it.characterId }.forEach { id ->
            characterDao.getById(id)?.let {
                names[id] = it.name
                avatars[id] = it.avatarImagePath
                colors[id] = it.avatarColor
                val card = it.cardImagePath.trim()
                if (card.isNotEmpty()) cardImages[id] = card
            }
        }
        return CharacterUiMaps(names, avatars, colors, cardImages)
    }

    private suspend fun attachmentsForMessages(
        messages: List<MessageEntity>,
    ): Map<Long, List<MessageAttachmentEntity>> {
        if (messages.isEmpty()) return emptyMap()
        return attachmentDao.getByMessages(messages.map { it.id }).groupBy { it.messageId }
    }

    private suspend fun branchSourcePreviews(
        branches: List<SessionBranchEntity>,
    ): Map<Long, String> = messagePreviews(
        messageIds = branches.map { it.sourceMessageId }.filter { it > 0L }.toSet(),
        maxChars = 56,
    )

    private suspend fun messagePreviews(
        messageIds: Set<Long>,
        maxChars: Int,
    ): Map<Long, String> {
        if (messageIds.isEmpty()) return emptyMap()
        val previews = mutableMapOf<Long, String>()
        for (batch in messageIds.chunked(32)) {
            val sources = messageDao.getPreviewSourcesInSession(sessionId, batch)
            if (sources.isEmpty()) continue
            previews.putAll(withContext(Dispatchers.Default) {
                sources.associate { message ->
                    currentCoroutineContext().ensureActive()
                    message.id to ChatMessageTextFormat.preview(
                        raw = message.content,
                        speakerType = message.speakerType,
                        maxChars = maxChars,
                        emptyText = "（无正文）",
                    )
                }
            })
        }
        return previews
    }

    /** 从数据库重新拉取参与者对应角色的头像/名称（编辑角色后返回聊天页时调用） */
    fun refreshParticipantCharacterMeta() {
        viewModelScope.launch {
            val participants = participantDao.getBySession(sessionId)
            val maps = buildCharacterPresentationMaps(participants)
            val sess = sessionDao.getById(sessionId)
            val firstChar = participants.firstOrNull()?.characterId?.let { characterDao.getById(it) }
            _state.value = _state.value.copy(
                participants = participants,
                characterNames = maps.names,
                characterAvatars = maps.avatars,
                characterCardImages = maps.cardImages,
                characterColors = maps.colors,
                userDisplayName = secureStorage.userName,
                userAvatarImagePath = secureStorage.userAvatarImagePath,
                userAvatarColor = secureStorage.userAvatarColor,
                allowSessionThinkMax = secureStorage.allowSessionThinkMax,
                sessionThinkMaxEnabled = sess?.thinkMaxEnabled == true,
                characterForcesThinkMax = firstChar?.thinkMaxEnabled == true,
            )
        }
    }

    fun setSessionThinkMax(enabled: Boolean, onMessage: (String) -> Unit) {
        if (!canMutateRoundConfiguration()) return
        if (enabled && !secureStorage.allowSessionThinkMax) {
            onMessage("请先在「设置 → 联网与模型」中开启「允许对话页思考/Max」")
            return
        }
        viewModelScope.launch {
            sessionDao.updateThinkMax(sessionId, enabled)
            _state.value = _state.value.copy(sessionThinkMaxEnabled = enabled)
        }
    }

    fun updateNarratorGuidance(text: String) {
        narratorDraftRevision++
        _state.update { it.copy(narratorGuidance = text) }
        persistCurrentDraft()
    }

    fun submitNarratorGuidance(guidance: String): Boolean {
        val revision = narratorDraftRevision
        return requestNarrator(guidance, onGuidanceCommitted = {
            if (revision == narratorDraftRevision && _state.value.narratorGuidance.trim() == guidance.trim()) {
                updateNarratorGuidance("")
            }
        })
    }

    fun updateInput(text: String) {
        if (text != _state.value.inputText) activeDraftSubmissionId = null
        _state.value = _state.value.copy(inputText = text)
        persistCurrentDraft()
    }

    /** Snackbar 展示后调用，避免同一 error 在重组时重复弹出。 */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun appendVoiceText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        activeDraftSubmissionId = null
        val cur = _state.value.inputText
        val sep = if (cur.isBlank() || cur.endsWith("\n")) "" else " "
        _state.value = _state.value.copy(inputText = cur + sep + t)
        persistCurrentDraft()
    }

    fun queueLocalImageAttachment(path: String) {
        if (path.isBlank()) return
        _state.value = _state.value.copy(
            pendingLocalImagePaths = (_state.value.pendingLocalImagePaths + path).distinct()
        )
        persistCurrentDraft()
    }

    fun clearPendingAttachments(onCleared: (() -> Unit)? = null) {
        val pendingPaths = _state.value.pendingLocalImagePaths
        if (pendingPaths.isEmpty()) {
            onCleared?.invoke()
            return
        }
        _state.value = _state.value.copy(pendingLocalImagePaths = emptyList())
        persistCurrentDraft()
        viewModelScope.launch {
            try {
                pendingPaths.forEach { path ->
                    try {
                        if (attachmentDao.countByStoragePath(path) == 0) {
                            withContext(Dispatchers.IO) {
                                ChatAttachmentFiles.deleteOwnedPendingFile(appContext, sessionId, path)
                            }
                        }
                    } catch (_: Exception) {
                        _state.update { state ->
                            state.copy(error = "待发送图片已从输入框移除，但部分本地文件未能清理")
                        }
                    }
                }
            } finally {
                onCleared?.invoke()
            }
        }
    }

    /**
     * 单向导入常见导出的聊天记录（JSON 含消息数组或 JSONL），写入本机；非用户发言归为**首位参与者**角色。
     * 相同内容再次导入当前故事线时返回 duplicate，不重复写入。
     */
    suspend fun importTavernChatText(rawText: String): TavernChatImportResult {
        if (activeGeneration != null || branchTransitionJob?.isActive == true) {
            throw IllegalStateException("请等待当前生成或故事线切换完成后再导入")
        }
        val firstCharId = participantDao.getBySession(sessionId).firstOrNull()?.characterId
            ?: throw IllegalStateException("请先在本会话添加至少一名角色参与者")
        val branchId = currentBranchId()
        val (batchMarker, messages) = withContext(Dispatchers.Default) {
            val rows = TavernChatImportParser.parse(rawText)
            if (rows.isEmpty()) {
                throw IllegalStateException("未解析出任何消息（支持 JSON 根级 mes[] 或 JSONL）")
            }
            val batchId = TavernChatImportParser.stableBatchId(rows)
            val marker = "\"st_import_batch\":\"$batchId\""
            val entities = rows.mapIndexed { index, row ->
                val st = if (row.speaker == "user") "user" else "character"
                val structured = JsonObject().apply {
                    addProperty("st_import_batch", batchId)
                    addProperty("st_import_index", index)
                    add("st_import", row.raw)
                }
                MessageEntity(
                    sessionId = sessionId,
                    speakerType = st,
                    characterId = if (st == "user") null else firstCharId,
                    branchId = branchId,
                    content = row.content,
                    structuredContentJson = structured.toString(),
                )
            }
            marker to entities
        }
        val insertedCount = messageDao.insertImportBatchIfAbsent(
            sessionId = sessionId,
            branchId = branchId,
            batchMarker = batchMarker,
            entities = messages,
        )
        if (insertedCount > 0) {
            sessionDao.bumpUpdatedAt(sessionId)
            refreshMessagesUi()
        }
        return TavernChatImportResult(
            importedCount = insertedCount,
            duplicate = insertedCount == 0,
        )
    }

    private fun currentBranchId(): String =
        _state.value.currentBranchId.ifBlank { "main" }

    private suspend fun withEffectiveSwipeSelections(
        branchId: String,
        messages: List<MessageEntity>,
    ): List<MessageEntity> {
        val groupIds = messages.mapNotNull { it.swipeGroupId?.takeIf(String::isNotBlank) }.distinct()
        if (groupIds.isEmpty()) return messages
        return messages.withEffectiveSwipeSelections(
            messageDao.getEffectiveSwipeSelectionsForGroups(sessionId, branchId, groupIds),
        )
    }

    private suspend fun getMessageTailForBranch(branchId: String, limit: Int): List<MessageEntity> {
        val messages = if (branchId == "main") {
            messageDao.getMainMessagesTail(sessionId, limit)
        } else {
            messageDao.getVisibleMessagesTail(sessionId, branchId, limit)
        }
        return withEffectiveSwipeSelections(branchId, messages)
    }

    private suspend fun getMessagesBefore(
        branchId: String,
        messageId: Long,
        limit: Int,
    ): List<MessageEntity> {
        val messages = if (branchId == "main") {
            messageDao.getMainMessagesBefore(sessionId, messageId, limit)
        } else {
            messageDao.getVisibleMessagesBefore(sessionId, branchId, messageId, limit)
        }
        return withEffectiveSwipeSelections(branchId, messages)
    }

    private suspend fun getMessagesAfter(
        branchId: String,
        messageId: Long,
        limit: Int,
    ): List<MessageEntity> {
        val messages = if (branchId == "main") {
            messageDao.getMainMessagesAfter(sessionId, messageId, limit)
        } else {
            messageDao.getVisibleMessagesAfter(sessionId, branchId, messageId, limit)
        }
        return withEffectiveSwipeSelections(branchId, messages)
    }

    private suspend fun getVisibleMessage(branchId: String, messageId: Long): MessageEntity? =
        if (branchId == "main") messageDao.getMainMessageById(sessionId, messageId)
        else messageDao.getVisibleMessageById(sessionId, branchId, messageId)

    private suspend fun getContextMessagesForBranch(branchId: String): List<MessageEntity> {
        val messages = if (branchId == "main") {
            messageDao.getMainContextTail(sessionId, MODEL_CONTEXT_MESSAGE_LIMIT)
        } else {
            messageDao.getVisibleContextTail(sessionId, branchId, MODEL_CONTEXT_MESSAGE_LIMIT)
        }
        return withEffectiveSwipeSelections(branchId, messages).asReversed()
    }

    private suspend fun getContextMessagesForCurrentBranch(): List<MessageEntity> =
        getContextMessagesForBranch(currentBranchId())

    private suspend fun hasVisibleStoryMessageAfterRegenerationTarget(
        branchId: String,
        target: MessageEntity,
    ): Boolean {
        val groupId = target.swipeGroupId?.takeIf { it.isNotBlank() }
        val groupMessages = groupId?.let { messageDao.getSwipeGroupMessages(sessionId, it) }.orEmpty()
        val sourceReplyIds = if (groupMessages.isEmpty()) {
            setOf(target.id)
        } else {
            groupMessages.mapTo(mutableSetOf()) { it.id }
        }
        val anchorId = groupMessages.minOfOrNull { it.id } ?: target.id
        var cursor = anchorId
        while (true) {
            val page = getMessagesAfter(branchId, cursor, MESSAGE_PAGE_SIZE)
            val hasBlockingMessage = page.any { message ->
                ReplyRegenerationPolicy.blocksRegenerationAfterTarget(
                    message = message,
                    swipeGroupId = groupId,
                    sourceReplyIds = sourceReplyIds,
                )
            }
            if (hasBlockingMessage) return true
            val lastId = page.lastOrNull()?.id ?: return false
            if (lastId <= cursor || page.size < MESSAGE_PAGE_SIZE) return false
            cursor = lastId
        }
    }

    private data class RoundChoiceSnapshot(
        val sourceMessageId: Long? = null,
        val options: List<String> = emptyList(),
    )

    private fun buildRoundChoiceSnapshot(
        world: SessionWorldEntity?,
        msgs: List<MessageEntity>,
    ): RoundChoiceSnapshot {
        if (world?.choiceGenerationEnabled != true) return RoundChoiceSnapshot()
        val tail = msgs.toActiveContextTimeline().lastOrNull()
            ?.takeIf { it.speakerType == "character" || it.speakerType == "narrator" }
            ?: return RoundChoiceSnapshot()
        val max = world.maxChoiceCount.coerceIn(1, 8)
        val options = StructuredParser.parse(tail.content).choices
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(max)
        return if (options.isEmpty()) {
            RoundChoiceSnapshot()
        } else {
            RoundChoiceSnapshot(sourceMessageId = tail.id, options = options)
        }
    }

    private suspend fun refreshMessagesUi(requestedBranchId: String = currentBranchId(), anchorMessageId: Long? = null) {
        val bookmarkRevision = bookmarkRefreshRevision.get()
        val eventRevision = eventRefreshRevision.incrementAndGet()
        val correctionRevision = correctionRefreshRevision.incrementAndGet()
        val branches = sessionBranchDao.getBySession(sessionId)
        val branchId = if (
            requestedBranchId == "main" || branches.any { it.branchId == requestedBranchId }
        ) requestedBranchId else "main"
        val anchor = anchorMessageId?.let { getVisibleMessage(branchId, it) }
        val radius = INITIAL_MESSAGE_WINDOW_SIZE / 2
        val pageRows = if (anchor == null) getMessageTailForBranch(branchId, INITIAL_MESSAGE_WINDOW_SIZE + 1)
            else getMessagesBefore(branchId, anchor.id, radius + 1)
        val afterRows = if (anchor == null) emptyList() else getMessagesAfter(branchId, anchor.id, radius + 1)
        val hasOlderMessages = pageRows.size > if (anchor == null) INITIAL_MESSAGE_WINDOW_SIZE else radius
        val msgs = if (anchor == null) pageRows.take(INITIAL_MESSAGE_WINDOW_SIZE).asReversed()
            else pageRows.take(radius).asReversed() + anchor + afterRows.take(radius)
        val world = sessionWorldDao.getBySession(sessionId)
        val anchors = branches
            .filter { it.sourceMessageId > 0L }
            .groupBy { it.sourceMessageId }
            .mapValues { (_, list) ->
                list.map { b ->
                    BranchAnchor(
                        branchId = b.branchId,
                        label = b.label.ifBlank { b.branchId },
                    )
                }
            }
        val sourcePreviews = branchSourcePreviews(branches)
        val map = attachmentsForMessages(msgs)
        val marks = bookmarkDao.getBySession(sessionId)
        val bookmarkIds = marks.map { it.messageId }.toSet()
        val previews = messagePreviews(bookmarkIds, maxChars = 120)
        val sess = sessionDao.getById(sessionId)
        val participants = participantDao.getBySession(sessionId)
        val firstChar = participants.firstOrNull()?.characterId?.let { characterDao.getById(it) }
        val displayCap = sess?.displayContextTokenLimit?.takeIf { it > 0 } ?: 1_000_000
        val convEst = msgs.filter { it.includeInContext }
            .sumOf { TokenCounter.estimateScaledPrefix(ConversationMessageText.forDerivedContext(it)) }
        val memorySegments = memorySegmentDao.getRecentForBranch(sessionId, branchId)
        val contextMemoryText = universalContextMemoryManager.getFormattedMemory(sessionId, branchId)
        val encyclopediaFoundation = contextBuilder.encyclopediaFoundation(world)
        val memoryCorrections = memoryCorrectionDao.getVisible(sessionId, branchId)
        val roundChoices = buildRoundChoiceSnapshot(world, msgs)
        val events = eventNodeDao.getForBranch(sessionId, branchId)
        _state.update { current -> current.copy(
            messages = msgs,
            displayLines = msgs.toChatDisplayLines(),
            hasOlderMessages = hasOlderMessages,
            hasNewerMessages = anchor != null && afterRows.size > radius,
            isLoadingHistory = false,
            focusedMessageId = anchor?.id,
            searchResults = emptyList(),
            isSearchingMessages = false,
            messageAttachments = map,
            bookmarks = if (bookmarkRefreshRevision.get() == bookmarkRevision) marks else current.bookmarks,
            bookmarkedMessageIds = if (bookmarkRefreshRevision.get() == bookmarkRevision) bookmarkIds else current.bookmarkedMessageIds,
            bookmarkPreviews = if (bookmarkRefreshRevision.get() == bookmarkRevision) previews else current.bookmarkPreviews,
            branches = branches,
            branchSourcePreviews = sourcePreviews,
            currentBranchId = branchId,
            contextMemoryText = contextMemoryText,
            contextMemoryStatus = if (current.currentBranchId == branchId) current.contextMemoryStatus else ContextMemoryStatus.IDLE,
            encyclopediaFoundation = encyclopediaFoundation,
            memorySegments = memorySegments,
            memoryCorrections = if (current.currentBranchId != branchId || correctionRefreshRevision.get() == correctionRevision)
                memoryCorrections else current.memoryCorrections,
            roundChoiceOptions = roundChoices.options,
            roundChoiceMessageId = roundChoices.sourceMessageId,
            branchAnchorsByMessageId = anchors,
            eventNodes = if (current.currentBranchId != branchId || eventRefreshRevision.get() == eventRevision) events else current.eventNodes,
            allowSessionThinkMax = secureStorage.allowSessionThinkMax,
            sessionThinkMaxEnabled = sess?.thinkMaxEnabled == true,
            characterForcesThinkMax = firstChar?.thinkMaxEnabled == true,
            displayContextTokenLimit = displayCap,
            conversationTokenEstimate = convEst,
        ) }
    }

    private fun launchHistoryLoad(
        block: suspend (branchId: String) -> Unit,
    ): Boolean {
        if (activeGeneration != null ||
            historyLoadJob?.isActive == true ||
            branchTransitionJob?.isActive == true
        ) return false
        val branchId = currentBranchId()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            try {
                block(branchId)
            } catch (_: CancellationException) {
                // A branch transition or a newer owner superseded this window request.
            } catch (_: Exception) {
                if (historyLoadJob === owner && currentBranchId() == branchId) {
                    _state.update { it.copy(error = "历史消息加载失败，请重试") }
                }
            } finally {
                if (historyLoadJob === owner) {
                    historyLoadJob = null
                    _state.update { it.copy(isLoadingHistory = false) }
                }
            }
        }
        historyLoadJob = job
        _state.update { it.copy(isLoadingHistory = true) }
        job.start()
        return true
    }

    private suspend fun applyHistoryWindow(
        branchId: String,
        messages: List<MessageEntity>,
        hasOlderMessages: Boolean,
        hasNewerMessages: Boolean,
        focusedMessageId: Long? = null,
    ) {
        if (currentBranchId() != branchId) return
        val normalized = withEffectiveSwipeSelections(
            branchId,
            messages.distinctBy { it.id }.sortedBy { it.id },
        )
        val attachments = attachmentsForMessages(normalized)
        val tokenEstimate = normalized
            .asSequence()
            .filter { it.includeInContext }
            .sumOf { TokenCounter.estimateScaledPrefix(it.content) }
        _state.update { current ->
            if (current.currentBranchId != branchId) return@update current
            val roundChoices = if (hasNewerMessages) {
                RoundChoiceSnapshot()
            } else {
                buildRoundChoiceSnapshot(current.world, normalized)
            }
            current.copy(
                messages = normalized,
                displayLines = normalized.toChatDisplayLines(),
                hasOlderMessages = hasOlderMessages,
                hasNewerMessages = hasNewerMessages,
                messageAttachments = attachments,
                focusedMessageId = focusedMessageId,
                roundChoiceOptions = roundChoices.options,
                roundChoiceMessageId = roundChoices.sourceMessageId,
                conversationTokenEstimate = tokenEstimate,
            )
        }
    }

    fun loadOlderMessages() {
        val snapshot = _state.value
        val anchorId = snapshot.messages.firstOrNull()?.id ?: return
        if (!snapshot.hasOlderMessages) return
        launchHistoryLoad { branchId ->
            val rows = getMessagesBefore(branchId, anchorId, MESSAGE_PAGE_SIZE + 1)
            val olderPage = rows.take(MESSAGE_PAGE_SIZE).asReversed()
            val current = _state.value
            if (current.currentBranchId != branchId) return@launchHistoryLoad
            val merged = (olderPage + current.messages).distinctBy { it.id }.sortedBy { it.id }
            val bounded = merged.take(MAX_MESSAGE_WINDOW_SIZE)
            applyHistoryWindow(
                branchId = branchId,
                messages = bounded,
                hasOlderMessages = rows.size > MESSAGE_PAGE_SIZE,
                hasNewerMessages = current.hasNewerMessages || merged.size > bounded.size,
            )
        }
    }

    fun loadNewerMessages() {
        val snapshot = _state.value
        val anchorId = snapshot.messages.lastOrNull()?.id ?: return
        if (!snapshot.hasNewerMessages) return
        launchHistoryLoad { branchId ->
            val rows = getMessagesAfter(branchId, anchorId, MESSAGE_PAGE_SIZE + 1)
            val newerPage = rows.take(MESSAGE_PAGE_SIZE)
            val current = _state.value
            if (current.currentBranchId != branchId) return@launchHistoryLoad
            val merged = (current.messages + newerPage).distinctBy { it.id }.sortedBy { it.id }
            val bounded = merged.takeLast(MAX_MESSAGE_WINDOW_SIZE)
            applyHistoryWindow(
                branchId = branchId,
                messages = bounded,
                hasOlderMessages = current.hasOlderMessages || merged.size > bounded.size,
                hasNewerMessages = rows.size > MESSAGE_PAGE_SIZE,
            )
        }
    }

    fun returnToLatestMessages(): Boolean =
        launchHistoryLoad { branchId ->
            val rows = getMessageTailForBranch(branchId, INITIAL_MESSAGE_WINDOW_SIZE + 1)
            applyHistoryWindow(
                branchId = branchId,
                messages = rows.take(INITIAL_MESSAGE_WINDOW_SIZE).asReversed(),
                hasOlderMessages = rows.size > INITIAL_MESSAGE_WINDOW_SIZE,
                hasNewerMessages = false,
            )
        }

    fun openMessageInHistory(messageId: Long): Boolean =
        launchHistoryLoad { branchId ->
            if (!loadMessageWindow(branchId, messageId)) {
                _state.update { it.copy(error = "该消息已删除或不在当前故事线") }
            }
        }

    fun openBookmarkedMessage(messageId: Long, onOpened: () -> Unit = {}) {
        if (!_state.value.isReady) return
        val launched = launchBranchTransition {
            _state.update { it.copy(bookmarkLocatingId = messageId) }
            var opened = false
            try {
                val currentBranch = currentBranchId()
                val visible = getVisibleMessage(currentBranch, messageId)
                if (visible != null) {
                    opened = loadMessageWindow(currentBranch, messageId)
                } else {
                    val target = messageDao.getByIdInSession(messageId, sessionId)
                    val branches = sessionBranchDao.getBySession(sessionId)
                    if (target != null && (target.branchId == "main" || branches.any { it.branchId == target.branchId }) &&
                        getVisibleMessage(target.branchId, messageId) != null) {
                        refreshMessagesUi(target.branchId, anchorMessageId = messageId)
                        opened = _state.value.currentBranchId == target.branchId && _state.value.focusedMessageId == messageId
                        if (opened) persistCurrentBranchSelection()
                    }
                }
                if (!opened) _state.update { it.copy(error = "收藏原文已删除或所在故事线已不可用") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(error = "收藏原文定位失败，请重试") }
            } finally {
                _state.update { it.copy(bookmarkLocatingId = null) }
            }
            if (opened) onOpened()
        }
        if (!launched) _state.update { it.copy(error = "当前正在生成或切换故事线，请稍后再定位收藏") }
    }

    private suspend fun loadMessageWindow(branchId: String, messageId: Long): Boolean {
        val target = getVisibleMessage(branchId, messageId) ?: return false
        val radius = INITIAL_MESSAGE_WINDOW_SIZE / 2
        val beforeRows = getMessagesBefore(branchId, target.id, radius + 1)
        val afterRows = getMessagesAfter(branchId, target.id, radius + 1)
        applyHistoryWindow(
            branchId = branchId,
            messages = beforeRows.take(radius).asReversed() + target + afterRows.take(radius),
            hasOlderMessages = beforeRows.size > radius,
            hasNewerMessages = afterRows.size > radius,
            focusedMessageId = target.id,
        )
        return true
    }

    fun clearFocusedMessage() {
        _state.update { it.copy(focusedMessageId = null) }
    }

    fun showSavedImage(): Boolean {
        val notice = _state.value.savedImageNotice ?: return false
        if (currentBranchId() != notice.branchId) return false
        return launchHistoryLoad { branchId ->
            val found = loadMessageWindow(branchId, notice.messageId)
            _state.update {
                if (it.savedImageNotice != notice) it else it.copy(
                    savedImageNotice = null,
                    error = if (found) null else "配图消息已删除或不在当前故事线",
                )
            }
        }
    }

    fun updateImagePrompt(text: String) {
        imageDraftRevision++
        _state.update { it.copy(imagePrompt = text) }
        persistCurrentDraft()
    }

    fun generateAndAttachUserMessage(prompt: String): Boolean {
        if (prompt.isBlank()) return false
        val draftRevision = imageDraftRevision
        return launchSingleGeneration imageGeneration@{ generation ->
            try {
                val firstParticipant = participantDao.getBySession(sessionId).firstOrNull()
                val char = firstParticipant?.characterId?.let { characterDao.getById(it) }
                val world = sessionWorldDao.getBySession(sessionId)
                val primary = ApiKeyResolver.resolveImageGenPrimaryResolved(char, world, secureStorage)
                if (primary.apiKey.isBlank()) {
                    UsbSessionLog.w(
                        "ChatImageGen",
                        "user image gen: missing key sid=$sessionId base=${primary.baseUrlRaw.take(48)}… model=${primary.model}",
                    )
                    _state.value = _state.value.copy(
                        error = UserFacingStrings.imageGenKeyMissing()
                    )
                    return@imageGeneration
                }
                val attempt = generateImageWithPublicFallback(
                    prompt,
                    char,
                    world,
                    char?.id?.takeIf { it > 0L },
                )
                generation.ensureCurrent()
                var insertedMessageId: Long? = null
                var generatedPath: String? = null
                var committed = false
                try {
                    attempt.result.fold(
                        onSuccess = { urlOrB64 ->
                            val local = imageRepository.saveGeneratedImageForSession(urlOrB64, sessionId)
                            generatedPath = local
                            generation.ensureCurrent()
                            if (local == null) {
                                UsbSessionLog.w("ChatImageGen", "user image gen: save to session failed sid=$sessionId")
                                _state.value = _state.value.copy(error = UserFacingStrings.imageSaveFailed())
                                return@fold
                            }
                            withContext(NonCancellable) {
                                val msgId = messageDao.insert(
                                    MessageEntity(
                                        sessionId = sessionId,
                                        speakerType = "user",
                                        content = "\uD83D\uDDBC $prompt",
                                        branchId = generation.branchId,
                                    )
                                )
                                insertedMessageId = msgId
                                attachmentDao.insert(
                                    MessageAttachmentEntity(
                                        messageId = msgId,
                                        assetType = "image",
                                        fileName = java.io.File(local).name,
                                        mimeType = "image/png",
                                        storagePath = local,
                                        generationPrompt = prompt,
                                        generationModel = attempt.modelUsed
                                    )
                                )
                                committed = true
                                if (draftRevision == imageDraftRevision && _state.value.imagePrompt.trim() == prompt.trim()) {
                                    updateImagePrompt("")
                                }
                            }
                            try {
                                refreshMessagesUi(generation.branchId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                generation.ensureCurrent()
                                _state.update { it.copy(savedImageNotice = SavedImageNotice(requireNotNull(insertedMessageId), generation.branchId)) }
                                return@fold
                            }
                            _state.value = _state.value.copy(error = null)
                            if (attempt.succeededOnPublicRetry) {
                                UsbSessionLog.i(
                                    "ChatImageGen",
                                    "user image gen succeeded on public retry (dedicated key unchanged) sid=$sessionId",
                                )
                            }
                        },
                        onFailure = { e ->
                            UsbSessionLog.e(
                                "ChatImageGen",
                                "user image gen failed sid=$sessionId model=${attempt.modelUsed}",
                                e,
                            )
                            _state.value = _state.value.copy(error = UserFacingStrings.streamErrorDetail(e.message))
                        }
                    )
                } finally {
                    if (!committed) rollbackPendingMedia(insertedMessageId, generatedPath)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UsbSessionLog.e("ChatImageGen", "user image gen exception sid=$sessionId", e)
                generation.ensureCurrent()
                _state.value = _state.value.copy(error = UserFacingStrings.streamErrorDetail(e.message))
            }
        }
    }

    private suspend fun tryHttpTtsAcrossVoiceBases(params: VoiceTtsParams, cleaned: String): Boolean {
        val bases = ApiRootLines.splitToOrderedDistinct(params.baseUrl, HttpTts::normalizeVoiceRoutingBase)
            .ifEmpty {
                val sole = HttpTts.normalizeVoiceRoutingBase(params.baseUrl.trim())
                if (sole.isNotEmpty()) listOf(sole) else emptyList()
            }
        for ((i, b) in bases.withIndex()) {
            if (HttpTts.speakHttpTts(appContext, params.apiKey, b, params.model, params.speechVoice, params.presetPrefix, cleaned)) {
                if (i > 0) maybePromoteVoiceStackField(params.baseUrl, b)
                return true
            }
        }
        return false
    }

    private var speechJob: Job? = null

    fun stopSpeaking() {
        speechJob?.cancel()
        speechJob = null
        AndroidTts.stop()
        com.mojing.app.media.TtsPlayer.stop()
    }

    fun currentVoiceChoice(): com.mojing.app.data.VoiceChoice =
        com.mojing.app.data.VoicePreferences(appContext).sessionSelection(sessionId)

    fun selectVoiceChoice(choice: com.mojing.app.data.VoiceChoice, onSaved: () -> Unit) {
        if (_state.value.voiceSelectionSaving) return
        _state.update { it.copy(voiceSelectionSaving = true, voiceSelectionError = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { com.mojing.app.data.VoicePreferences(appContext).saveSession(sessionId, choice) }
                stopSpeaking()
                onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(voiceSelectionError = "语音选择未保存，请重试") } }
            finally { _state.update { it.copy(voiceSelectionSaving = false) } }
        }
    }

    fun speakMessage(text: String, characterId: Long? = null) {
        stopSpeaking()
        speechJob = viewModelScope.launch {
            try {
                val cleaned = TtsSpeakText.normalizeForSpeech(text)
                if (cleaned.isBlank()) {
                    _state.update { it.copy(error = UserFacingStrings.ttsContentEmptyAfterClean()) }
                    return@launch
                }
                val char = characterId?.let { characterDao.getById(it) }
                val preferences = com.mojing.app.data.VoicePreferences(appContext)
                val choice = com.mojing.app.data.resolveVoiceChoice(char?.voiceProvider, char?.voiceModel, preferences.session(sessionId))
                if (choice.engineId == "azure") {
                    val (region, key) = withContext(Dispatchers.IO) { preferences.azureRegion to preferences.azureKey }
                    val ok = com.mojing.app.media.AzureSpeech.speak(appContext, cleaned, region, key, choice.voiceId)
                    if (!ok) _state.update { it.copy(error = "语音播放未完成，请重新朗读") }
                } else {
                    withContext(Dispatchers.Main) {
                        AndroidTts.speakWithVoice(appContext, cleaned, choice) { message -> _state.update { it.copy(error = message) } }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: com.mojing.app.media.AzureSpeech.SpeechException) {
                _state.update { it.copy(error = com.mojing.app.media.AzureSpeech.failureMessage(e)) }
            }
            catch (e: Exception) { _state.update { it.copy(error = UserFacingStrings.remoteRequestFailed(e)) } }
        }
    }

    /** 朗读输入框当前文字（不发送消息） */
    fun previewSpeakInput() {
        speakMessage(_state.value.inputText.trim())
    }

    /**
     * 发送新消息和编辑用户消息共用同一轮发言调度，避免编辑路径另建一套生成 owner。
     */
    private suspend fun runScheduledCharacterRound(
        generation: GenerationContext,
        onRemoteRequestStarted: () -> Unit = {},
    ): Boolean {
        val participants = participantDao.getBySession(sessionId)
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        if (participants.isEmpty()) {
            _state.value = _state.value.copy(error = UserFacingStrings.chatNoParticipant())
            refreshMessagesUi()
            return false
        }
        val contextMessages = getContextMessagesForBranch(generation.branchId)
        val userMsgContent = contextMessages.lastOrNull()?.content.orEmpty()
        val charactersById = participants.mapNotNull { participant ->
            characterDao.getById(participant.characterId)?.let { participant.characterId to it }
        }.toMap()
        val manualMode = secureStorage.speakerTurnMode == "manual"
        val manualCharId = _state.value.manualReplyCharacterId
        val maxSpeakers = secureStorage.maxAutoSpeakers.coerceIn(1, 8)
        val pick = if (manualMode) {
            if (manualCharId != null) {
                SpeakerScheduler.PickResult(
                    characterIds = listOf(manualCharId),
                    clearForceNextParticipantIds = emptyList(),
                    reason = "手动指定角色回复",
                )
            } else {
                refreshMessagesUi()
                return false
            }
        } else {
            SpeakerScheduler.pick(
                participants, charactersById, contextMessages, userMsgContent, maxSpeakers,
            )
        }
        for (participantId in pick.clearForceNextParticipantIds) {
            participantDao.getById(participantId)?.let {
                participantDao.upsert(it.copy(forceNext = false))
            }
        }
        if (pick.clearForceNextParticipantIds.isNotEmpty()) {
            val updatedParticipants = participantDao.getBySession(sessionId)
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            val presentation = buildCharacterPresentationMaps(updatedParticipants)
            _state.value = _state.value.copy(
                participants = updatedParticipants,
                characterNames = presentation.names,
                characterAvatars = presentation.avatars,
                characterCardImages = presentation.cardImages,
                characterColors = presentation.colors,
            )
            refreshMessagesUi()
        }
        val orderedCharacters = pick.characterIds.distinct().mapNotNull { characterDao.getById(it) }
        if (orderedCharacters.isEmpty()) {
            _state.value = _state.value.copy(error = UserFacingStrings.chatNoParticipant())
            refreshMessagesUi()
            return false
        }
        val orderedNames = orderedCharacters.map { it.name.trim().ifBlank { "角色" } }
        val waitingNames = pick.waitingCharacterIds.mapNotNull { id ->
            charactersById[id]?.name?.trim()?.takeIf { it.isNotEmpty() }
        }
        val summary = buildString {
            if (orderedNames.isNotEmpty()) append("本轮：${orderedNames.joinToString("、")}")
            if (waitingNames.isNotEmpty()) {
                if (isNotEmpty()) append("；")
                append("发言率候补：${waitingNames.joinToString("、")}")
            }
            if (isNotEmpty()) append(" · ")
            append(pick.reason)
        }.ifBlank { pick.reason }
        _state.value = _state.value.copy(
            speakerPlanSummary = summary,
            pendingRoundSpeakers = orderedNames,
            manualReplyCharacterId = if (manualMode) null else _state.value.manualReplyCharacterId,
        )
        if (orderedCharacters.size == 1) {
            onRemoteRequestStarted()
            return streamCharacterReply(
                generation,
                orderedCharacters.first(),
                runMemoryCompact = true,
                manageGeneratingFlag = true,
            )
        }
        orderedCharacters.forEachIndexed { index, character ->
            val remaining = orderedNames.drop(index + 1)
            _state.value = _state.value.copy(
                pendingRoundSpeakers = remaining,
                speakerPlanSummary = if (remaining.isNotEmpty()) {
                    "待回复：${remaining.joinToString("、")}"
                } else {
                    null
                },
            )
            onRemoteRequestStarted()
            val ok = streamCharacterReply(
                generation,
                character,
                runMemoryCompact = index == 0,
                manageGeneratingFlag = false,
            )
            if (!ok) {
                val current = _state.value
                _state.value = current.copy(
                    pendingRoundSpeakers = emptyList(),
                    speakerPlanSummary = null,
                    error = UserFacingStrings.chatMultiCharacterStoppedAt(character.name, current.error),
                )
                refreshMessagesUi()
                return false
            }
        }
        _state.value = _state.value.copy(
            pendingRoundSpeakers = emptyList(),
            speakerPlanSummary = null,
        )
        scheduleAutoNarratorIfNeeded(generation.branchId)
        return true
    }

    fun sendMessage() {
        val current = _state.value
        if (!current.isReady || current.sessionNotFound) return
        val inputTextSnapshot = current.inputText
        val text = inputTextSnapshot.trim()
        val pendingImageLocalPaths = current.pendingLocalImagePaths
        if (text.isEmpty() && pendingImageLocalPaths.isEmpty()) {
            if (!_state.value.isGenerating) {
                _state.value = _state.value.copy(error = UserFacingStrings.chatSendNeedContent())
            }
            return
        }
        if (generationJob?.isActive == true || _state.value.isGenerating) return
        val draftSubmissionId = UUID.randomUUID().toString()
        if (!beginDraftSubmission(draftSubmissionId)) return
        val quote = _state.value.quotingMessage
        val submittedQuoteRevision = quoteDraftRevision
        val quotedPrefix = quote?.let { q ->
            val label = when (q.speakerType) {
                "user" -> secureStorage.userName.ifBlank { "?" }
                "narrator" -> _state.value.world?.narratorName?.ifBlank { "\u65c1\u767d" } ?: "\u65c1\u767d"
                else -> q.characterId?.let { _state.value.characterNames[it] } ?: "\u89d2\u8272"
            }
            val snippet = ChatMessageTextFormat.quoteSnippet(q.content, 120, q.speakerType)
            if (snippet.isNotEmpty()) "> $label：$snippet\n\n" else "> $label\n\n"
        }.orEmpty()
        val outboundText = quotedPrefix + text
        if (current.world?.gameplayMode == "小说创作" && pendingImageLocalPaths.isEmpty()) {
            val launched = requestNarrator(
                guidance = outboundText,
                draftSubmissionId = draftSubmissionId,
            ) {
                _state.update { state ->
                    state.copy(
                        inputText = if (activeDraftSubmissionId == draftSubmissionId) "" else state.inputText,
                        quotingMessage = if (quoteDraftRevision == submittedQuoteRevision) null else state.quotingMessage,
                    )
                }
                finishDraftSubmission(draftSubmissionId)
            }
            if (!launched) finishDraftSubmission(draftSubmissionId)
            return
        }


        val launched = launchSingleGeneration(draftSubmissionId = draftSubmissionId) sendGeneration@{ generation ->
            _state.value = _state.value.copy(
                error = null,
                speakerPlanSummary = null,
                pendingRoundSpeakers = emptyList(),
            )
            var pendingMessageId: Long? = null
            var submissionCommitted = false
            var remoteRequestStarted = false
            try {
                val displayContent = when {
                    outboundText.isNotBlank() -> outboundText
                    text.isNotBlank() -> text
                    else -> "[\u56fe\u7247]"
                }
                UsbSessionLog.i(
                    "ChatSend",
                    "session=$sessionId branch=${generation.branchId} textLen=${displayContent.length} images=${pendingImageLocalPaths.size} text=${clipLog(displayContent)}",
                )
                generation.ensureCurrent()
                val attachments = pendingImageLocalPaths.map { path ->
                    val file = java.io.File(path)
                    val mime = when {
                        path.endsWith(".png", ignoreCase = true) -> "image/png"
                        path.endsWith(".webp", ignoreCase = true) -> "image/webp"
                        path.endsWith(".gif", ignoreCase = true) -> "image/gif"
                        else -> "image/jpeg"
                    }
                    MessageAttachmentEntity(
                        messageId = 0L,
                        assetType = "image",
                        fileName = file.name,
                        mimeType = mime,
                        storagePath = path,
                        generationPrompt = "",
                        generationModel = "",
                    )
                }
                val msgId = messageSubmissionTransaction(
                    message = MessageEntity(
                        sessionId = sessionId,
                        speakerType = "user",
                        content = displayContent,
                        structuredContentJson = draftSubmissionStructuredContent(draftSubmissionId),
                        branchId = generation.branchId,
                    ),
                    attachments = attachments,
                    onMessageIdAssigned = { messageId -> pendingMessageId = messageId },
                )
                pendingMessageId = msgId
                generation.ensureCurrent()
                _state.update { current ->
                    current.copy(
                        inputText = if (activeDraftSubmissionId == draftSubmissionId) "" else current.inputText,
                        pendingLocalImagePaths = if (
                            current.pendingLocalImagePaths.take(pendingImageLocalPaths.size) == pendingImageLocalPaths
                        ) {
                            current.pendingLocalImagePaths.drop(pendingImageLocalPaths.size)
                        } else current.pendingLocalImagePaths,
                        quotingMessage = if (quoteDraftRevision == submittedQuoteRevision) null else current.quotingMessage,
                    )
                }
                finishDraftSubmission(draftSubmissionId)
                submissionCommitted = true
                refreshMessagesUi()
                val roundCompleted = runScheduledCharacterRound(generation) {
                    remoteRequestStarted = true
                }
                if (!roundCompleted) return@sendGeneration
            } catch (e: CancellationException) {
                if (!submissionCommitted) {
                    withContext(NonCancellable) {
                        runCatching { pendingMessageId?.let { messageDao.delete(it) } }
                    }
                }
                finishDraftSubmission(draftSubmissionId)
                throw e
            } catch (e: Exception) {
                if (!submissionCommitted) {
                    withContext(NonCancellable) {
                        runCatching { pendingMessageId?.let { messageDao.delete(it) } }
                    }
                }
                finishDraftSubmission(draftSubmissionId)
                generation.ensureCurrent()
                _state.value = _state.value.copy(
                    streamingText = "",
                    error = when {
                        !submissionCommitted -> UserFacingStrings.localSaveFailed("消息")
                        remoteRequestStarted -> UserFacingStrings.remoteRequestFailed(e)
                        else -> UserFacingStrings.localLoadFailed("对话数据")
                    },
                )
                refreshMessagesUi()
            } finally {
                updateGeneratedSessionTitle()
            }
        }
        if (!launched) finishDraftSubmission(draftSubmissionId)
    }

    private suspend fun updateGeneratedSessionTitle() {
        val generatedTitle = _state.value.messages
            .firstOrNull()
            ?.content
            ?.trim()
            ?.take(30)
            .orEmpty()
            .ifBlank { "新对话" }
        sessionDao.touchWithGeneratedTitle(sessionId, generatedTitle)
    }

    private fun clipLog(text: String, limit: Int = 400): String {
        val t = text.trim()
        return if (t.length <= limit) t else t.take(limit) + "…len=" + t.length
    }

    private fun getModelMaxContext(model: String): Int {
        return when {
            model.contains("128k", ignoreCase = true) -> 128000
            model.contains("32k", ignoreCase = true) -> 32000
            model.contains("turbo", ignoreCase = true) -> 8192
            model.contains("gpt-4", ignoreCase = true) -> 8192
            else -> 8192
        }
    }

    /**
     * 流式生成单条角色回复。[manageGeneratingFlag] 为 false 时表示多角色链中的一环，Done 时不清 [ChatContract.State.isGenerating]。
     * @return false 表示应中止后续链（缺 Key、流错误等）
     */
    private suspend fun streamCharacterReply(
        generation: GenerationContext,
        character: CharacterEntity,
        runMemoryCompact: Boolean,
        manageGeneratingFlag: Boolean,
    ): Boolean {
        val world = sessionWorldDao.getBySession(sessionId)
        val connection = chatConnection(world, character)
        connection.error?.let {
            _state.update { state -> state.copy(error = it) }
            return false
        }
        val apiKey = connection.apiKey
        val baseUrlRaw = connection.baseUrl
        val streamBases = ApiRootLines.splitToOrderedDistinct(baseUrlRaw, llmApiService::normalizeOpenAiCompatibleBase)
            .ifEmpty {
                val sole = llmApiService.normalizeOpenAiCompatibleBase(baseUrlRaw.trim())
                if (sole.isNotEmpty()) listOf(sole) else emptyList()
            }
        if (streamBases.isEmpty()) {
            _state.value = _state.value.copy(
                error = UserFacingStrings.streamErrorDetail("对话服务根地址无效或未填写"),
            )
            return false
        }
        val preStreamBase = streamBases.first()
        val sessionEntity = sessionDao.getById(sessionId)
        val sessionThink = sessionEntity?.thinkMaxEnabled == true
        val thinkRoute = effectiveThinkMax(character, sessionThink)
        val model = resolveChatLlmModel(character, sessionThink, connection)
        if (model == null) {
            _state.value = _state.value.copy(
                error = UserFacingStrings.chatMainModelMissing(),
            )
            return false
        }
        if (apiKey.isEmpty()) {
            _state.value = _state.value.copy(error = UserFacingStrings.chatApiKeyMissing())
            return false
        }
        val branchId = generation.branchId
        val allMessages = generation.contextMessagesOverride ?: getContextMessagesForBranch(branchId)

        if (runMemoryCompact) {
            val compactThreshold = secureStorage.memoryCompactThreshold.coerceIn(10, 2000)
            val compacted = try { memoryCompactor.compactIfNeeded(
                sessionId = sessionId,
                branchId = branchId,
                apiKey = apiKey,
                baseUrl = preStreamBase,
                model = model,
                threshold = compactThreshold,
                onProgress = { chunk ->
                    generation.ensureCurrent()
                    _state.update { it.copy(memoryCompactionChunk = chunk) }
                },
            ) } finally {
                if (activeGeneration === generation) {
                    _state.update { it.copy(memoryCompactionChunk = null) }
                }
            }
            if (compacted) {
                generation.ensureCurrent()
                try {
                    val segments = memorySegmentDao.getRecentForBranch(sessionId, branchId)
                    _state.update { if (it.currentBranchId == branchId) it.copy(memorySegments = segments) else it }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 摘要已保存，下一次正常消息刷新时重新读取。 */ }
            }
        }

        var snapshot = snapshotExtractor.extract(allMessages, character, apiKey, preStreamBase, model)
        val currentState = characterStateDao.getBySessionAndCharacter(sessionId, character.id)
        if (snapshot != null) {
            val updatedState = currentState?.copy(
                dynamicStateJson = com.google.gson.Gson().toJson(snapshot),
                emotionalState = snapshot.mood
            ) ?: com.mojing.app.data.local.entity.SessionCharacterStateEntity(
                sessionId = sessionId, characterId = character.id,
                dynamicStateJson = com.google.gson.Gson().toJson(snapshot),
                emotionalState = snapshot.mood
            )
            characterStateDao.upsert(updatedState)
        } else if (currentState != null && currentState.dynamicStateJson.isNotBlank() && currentState.dynamicStateJson != "{}") {
            try {
                snapshot = com.google.gson.Gson().fromJson(
                    currentState.dynamicStateJson,
                    com.mojing.app.domain.engine.CharacterSnapshot::class.java,
                )
            } catch (_: Exception) { }
        }

        val budget = tokenBudgetManager.calculateBudget(getModelMaxContext(model), character.maxTokens)
        val memorySegments = memorySegmentDao.getRecentForBranch(sessionId, branchId)
        val memoryCorrections = memoryCorrectionDao.getVisible(sessionId, branchId)
        val recallQuery = allMessages.takeLast(15).joinToString("\n") {
            ConversationMessageText.forDerivedContext(it)
        }
        val universalMemoryText = universalContextMemoryManager.getFormattedMemory(sessionId, branchId)
        val context = contextBuilder.buildFullContext(
            character = character,
            world = world,
            personaName = secureStorage.userName,
            userDescription = secureStorage.userDescription,
            userMessage = allMessages.lastOrNull()?.content ?: "",
            recallQueryText = recallQuery,
            memorySummary = memorySegments.joinToString("\n") { "- ${it.summary}" },
            memoryCorrections = memoryCorrections,
            universalContextMemoryText = universalMemoryText,
            activeCharacterNames = _state.value.characterNames.values.toList(),
            sessionId = sessionId,
            effectiveModelName = model,
        )
        generation.ensureCurrent()
        recordMemoryCorrectionPromptTrace(
            branchId = branchId,
            responderLabel = character.name,
            responderType = "character",
            corrections = memoryCorrections,
        )

        val recentHistory = slidingWindowBuilder.buildWindow(allMessages, budget.shortTermWindow)

        val replyStartedAt = System.nanoTime()
        var llmHookBase = preStreamBase
        var sawDone = false
        var streamErrorMessage: String? = null
        var receivedText = ""
        run {
        for ((idx, streamBase) in streamBases.withIndex()) {
            streamErrorMessage = null
            if (idx > 0) {
                _state.value = _state.value.copy(streamingText = "")
                UsbSessionLog.i("ChatLlm", "try dialog base ${idx + 1}/${streamBases.size}")
            }
            var lastUiUpdateAt = 0L
            var lastUiLen = 0
            generation.ensureCurrent()
            _state.update { it.copy(lastRequestModel = model, lastRequestPlatform = requestPlatform()?.name) }
            chatEngine.streamGenerateWithMemory(
                sessionId, character, recentHistory, context.systemPrompt, snapshot, budget,
                apiKey, streamBase, model, secureStorage.userName, secureStorage.userDescription,
            ).collect { s ->
            when (s) {
                is StreamState.Generating -> {
                    generation.ensureCurrent()
                    val now = System.currentTimeMillis()
                    receivedText = s.partialText
                                    val len = s.partialText.length
                    val shouldUpdate = (now - lastUiUpdateAt) >= 60L || (len - lastUiLen) >= 80
                    if (shouldUpdate) {
                        lastUiUpdateAt = now
                        lastUiLen = len
                        _state.value = _state.value.copy(streamingText = s.partialText)
                    }
                }
                    is StreamState.Done -> {
                        generation.ensureCurrent()
                        llmHookBase = streamBase
                        val gid = generation.swipeGroupId
                        UsbSessionLog.i(
                            "ChatLlm",
                            "session=$sessionId done base=${streamBase.trim()} model=${model.trim()} rawLen=${s.fullText.length} raw=${clipLog(s.fullText)}",
                        )
                        val parsed = CharacterMediaMarkers.parse(s.fullText)
                        var displayContent = parsed.displayText.trim()
                        displayContent = OutputProcessor.normalizeOptions(displayContent)
                        UsbSessionLog.i(
                            "ChatLlm",
                            "session=$sessionId displayLen=${displayContent.length} display=${clipLog(displayContent)}",
                        )
                        if (displayContent.isBlank()) {
                            streamErrorMessage = "模型返回了空内容"
                            return@collect
                        }
                        val reply = MessageEntity(
                            sessionId = sessionId,
                            speakerType = "character",
                            characterId = character.id,
                            content = displayContent,
                            structuredContentJson = ReplyGenerationMetadata.record(structuredContentJsonFor(displayContent),
                                (System.nanoTime() - replyStartedAt) / 1_000_000, s.usage),
                            branchId = branchId,
                            swipeGroupId = gid,
                            includeInContext = true,
                        )
                        val replyMessageId = if (gid == null) {
                            messageDao.insert(reply)
                        } else {
                            messageDao.insertAndSelectSwipeVariant(
                                entity = reply,
                                branchId = branchId,
                                targetMessageId = requireNotNull(generation.swipeSourceMessageId) {
                                    "缺少重生成来源消息"
                                },
                            )
                        }
                        sawDone = true
                        generation.swipeGroupId = null
                        generation.swipeSourceMessageId = null

                        if (!manageGeneratingFlag) {
                            _state.value = _state.value.copy(streamingText = "")
                        }
                        refreshMessagesUi()
                        runAutoCharacterMediaJobs(
                            generation = generation,
                            character = character,
                            world = world,
                            parsed = parsed,
                            sourceReplyMessageId = replyMessageId,
                        )
                        generation.ensureCurrent()
                        val memoryRevision = universalContextMemoryManager.reserveUpdateRevision(sessionId, branchId)
                        val msgs = getContextMessagesForBranch(branchId)
                        launchSessionMaintenance {
                            val memoryResult = universalContextMemoryManager.updateAfterMessages(
                                sessionId = sessionId,
                                branchId = branchId,
                                expectedRevision = memoryRevision,
                                apiKey = apiKey,
                                baseUrl = llmHookBase,
                                model = model,
                                worldText = world?.worldPrompt.orEmpty(),
                                activeCharacterNames = _state.value.characterNames.values.toList(),
                            )
                            val updatedMemory = universalContextMemoryManager.getFormattedMemory(sessionId, branchId)
                            _state.update { it.withContextMemoryResult(branchId, memoryResult, updatedMemory) }
                            memoryV2Manager.extractEventNodes(
                                sessionId = sessionId,
                                branchId = branchId,
                                characterId = character.id,
                                messages = msgs,
                                apiKey = apiKey,
                                baseUrl = llmHookBase,
                                model = model
                            )
                            refreshEventNodesForBranch(branchId)
                            if (world?.autoSedimentEnabled == true && world.encyclopediaId != null) {
                                sedimentEngine.sedimentFromMessages(
                                    encyclopediaId = world.encyclopediaId,
                                    sessionId = sessionId,
                                    branchId = branchId,
                                    messages = msgs.takeLast(10),
                                    apiKey = apiKey,
                                    baseUrl = llmHookBase,
                                    model = model
                                )
                            }
                        }
                        if (manageGeneratingFlag) {
                            _state.value = _state.value.copy(
                                streamingText = "",
                                pendingRoundSpeakers = emptyList(),
                                speakerPlanSummary = null,
                            )
                            scheduleAutoNarratorIfNeeded(branchId)
                        }
                }
                is StreamState.Error -> {
                    generation.ensureCurrent()
                    streamErrorMessage = s.message
                    UsbSessionLog.w(
                        "ChatLlm",
                        "session=$sessionId error base=${streamBase.trim()} model=${model.trim()} msg=${clipLog(s.message)}",
                    )
                }
            }
        }
            if (sawDone) {
                if (shouldPromoteGlobalChatBaseUrl(world, character) && idx > 0) {
                    promoteGlobalChatBaseIfNeeded(streamBase)
                }
                break
            }
            if (streamErrorMessage != null) {
                val partial = receivedText.trim()
                if (partial.isNotEmpty() || idx >= streamBases.lastIndex) break
            }
        }
        }
        if (!sawDone && streamErrorMessage != null) {
            val retained = retainInterruptedReply(generation, receivedText, "character", character.id)
            _state.value = _state.value.copy(
                streamingText = "",
                error = (if (thinkRoute) UserFacingStrings.streamErrorThinkMaxRoute(streamErrorMessage)
                else UserFacingStrings.streamErrorDetail(streamErrorMessage)) + if (retained) "\n已保留收到的正文，可继续对话。" else "",
            )
            return false
        }
        return true
    }

    private suspend fun retainInterruptedReply(
        generation: GenerationContext,
        text: String,
        speakerType: String,
        characterId: Long?,
    ): Boolean {
        var content = com.mojing.app.domain.engine.InterruptedReply.normalize(text)
        val world = _state.value.world
        if (speakerType == "narrator" && world?.gameplayMode == "小说创作") {
            content = StoryCanon.sanitizeMessageChoices(content, world.worldPrompt)
        }
        if (content.isBlank()) return false
        generation.ensureCurrent()
        val reply = MessageEntity(
            sessionId = sessionId,
            branchId = generation.branchId,
            speakerType = speakerType,
            characterId = characterId,
            content = content,
            structuredContentJson = structuredContentJsonFor(content),
            swipeGroupId = generation.swipeGroupId,
            includeInContext = true,
        )
        if (generation.swipeGroupId == null) {
            messageDao.insert(reply)
        } else {
            messageDao.insertAndSelectSwipeVariant(
                entity = reply, branchId = generation.branchId,
                targetMessageId = requireNotNull(generation.swipeSourceMessageId),
            )
        }
        generation.swipeGroupId = null
        generation.swipeSourceMessageId = null
        refreshMessagesUi()
        return true
    }

    private fun structuredContentJsonFor(content: String): String {
        val reply = StructuredParser.parse(content)
        val o = JsonObject()
        val choices = JsonArray()
        reply.choices.forEach { choices.add(it) }
        o.add("choices", choices)
        return o.toString()
    }

    private fun scheduleAutoNarratorIfNeeded(branchId: String) {
        val world = _state.value.world ?: return
        if (!world.narratorEnabled) return
        viewModelScope.launch {
            val msgs = getContextMessagesForBranch(branchId)
            val lastNarratorIdx = msgs.indexOfLast { it.speakerType == "narrator" }
            val since = if (lastNarratorIdx < 0) msgs.size else msgs.size - lastNarratorIdx - 1
            if (!narratorEngine.shouldNarrate(world, since)) return@launch
            kotlinx.coroutines.delay(450)
            if (_state.value.isGenerating || currentBranchId() != branchId) return@launch
            requestNarrator("")
        }
    }

    private fun shouldPromoteNarratorPublicBases(world: SessionWorldEntity): Boolean {
        val w = world.sessionLlmBaseUrl.trim()
        return w.isEmpty() || isPlaceholderApiBase(w)
    }

    fun requestNarrator(
        guidance: String = "",
        expectedTailMessageId: Long? = null,
        draftSubmissionId: String? = null,
        nextChapter: Boolean = false,
        chapterTitle: String = "",
        onGuidanceCommitted: (() -> Unit)? = null,
    ): Boolean {
        if (_state.value.isGenerating || generationJob?.isActive == true) return false
        val world = _state.value.world ?: return false
        val character = CharacterEntity(name = world.narratorName)

        return launchSingleGeneration(expectedTailMessageId, draftSubmissionId) narratorScope@{ generation ->
            if (!generation.ensureExpectedUserTail()) return@narratorScope
            val resumeChapter = if (nextChapter) getMessageTailForBranch(generation.branchId, 1).lastOrNull()?.takeIf {
                it.branchId == generation.branchId && NovelChapter.incomplete(it.structuredContentJson)
            } else null
            val chapterNumber = if (nextChapter) {
                val latest = if (generation.branchId == "main") messageDao.getMainMaxChapter(sessionId)
                    else messageDao.getBranchMaxChapter(sessionId, generation.branchId)
                resumeChapter?.let { NovelChapter.number(it.structuredContentJson) } ?: (latest.coerceAtLeast(1) + 1)
            } else null
            val guidanceText = guidance.trim()
            val connection = chatConnection(world)
            connection.error?.let {
                _state.update { state -> state.copy(error = it) }
                return@narratorScope
            }
            val apiKey = connection.apiKey
            val baseUrlRaw = connection.baseUrl
            val narrBases = ApiRootLines.splitToOrderedDistinct(baseUrlRaw, llmApiService::normalizeOpenAiCompatibleBase)
                .ifEmpty {
                    val sole = llmApiService.normalizeOpenAiCompatibleBase(baseUrlRaw.trim())
                    if (sole.isNotEmpty()) listOf(sole) else emptyList()
                }
            val model = resolveMainChatModelId(CharacterEntity(modelName = ""), connection)
            if (apiKey.isEmpty()) {
                _state.value = _state.value.copy(error = UserFacingStrings.chatApiKeyMissing())
                return@narratorScope
            }
            if (model.isEmpty()) {
                _state.value = _state.value.copy(error = UserFacingStrings.chatMainModelMissing())
                return@narratorScope
            }
            if (narrBases.isEmpty()) {
                _state.value = _state.value.copy(
                    error = UserFacingStrings.streamErrorDetail("对话服务根地址无效"),
                )
                return@narratorScope
            }
            if (guidanceText.isNotEmpty() && !nextChapter) {
                var guidanceCommitted = false
                try {
                    generation.ensureCurrent()
                    messageDao.insert(
                        MessageEntity(
                            sessionId = sessionId,
                            speakerType = "user",
                            content = guidanceText,
                            structuredContentJson = draftSubmissionId
                                ?.let(::draftSubmissionStructuredContent)
                                ?: "{}",
                            branchId = generation.branchId,
                        ),
                    )
                    guidanceCommitted = true
                    onGuidanceCommitted?.invoke()
                    refreshMessagesUi(generation.branchId)
                    if (currentBranchId() != generation.branchId) {
                        _state.update {
                            it.copy(error = "剧情走向已保存，但当前故事线刷新失败，请重新进入对话")
                        }
                        return@narratorScope
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _state.value = _state.value.copy(
                        error = if (guidanceCommitted) {
                            "剧情走向已保存，但对话刷新失败，请重新进入对话"
                        } else {
                            "剧情走向保存失败，请重试"
                        },
                    )
                    return@narratorScope
                }
            }
            val history = getContextMessagesForBranch(generation.branchId).takeLast(20)
            val universalMemoryText = universalContextMemoryManager.getFormattedMemory(sessionId, generation.branchId)
            val memoryCorrections = memoryCorrectionDao.getVisible(sessionId, generation.branchId)
            val recallQuery = history.joinToString("\n") { ConversationMessageText.forDerivedContext(it) }
                .ifBlank { guidanceText }
            val sharedWorldContext = contextBuilder.searchWorldContext(world, recallQuery)
            val context = PromptBuilder.PromptContext(
                character = character,
                world = world,
                personaName = secureStorage.userName,
                userDescription = secureStorage.userDescription,
                universalContextMemoryText = universalMemoryText,
                memoryCorrections = memoryCorrections,
                encyclopediaHits = sharedWorldContext.encyclopediaHits,
                loreHits = sharedWorldContext.loreHits,
            )
            val narratorPrompt = promptBuilder.buildNarratorPrompt(context, guidance, model, includeUserProfile = false) +
                if (chapterNumber != null) "\n小说名：${_state.value.sessionTitle}。本次续写小说第 $chapterNumber 章。承接已有剧情，写完整连续的小说正文，不回复用户、不生成选项或大纲。第一行给出章节标题，随后正文。" +
                    chapterTitle.trim().takeIf { it.isNotEmpty() }?.let { "指定章节标题：$it" }.orEmpty() +
                    if (resumeChapter != null) "\n上一条是本章未完成草稿。保留已有情节，返回从本章开头到结尾的完整正文。" else ""
                else ""
            generation.ensureCurrent()
            recordMemoryCorrectionPromptTrace(
                branchId = generation.branchId,
                responderLabel = world.narratorName,
                responderType = "narrator",
                corrections = memoryCorrections,
            )
            UsbSessionLog.i(
                "Narrator",
                "session=$sessionId guidanceLen=${guidance.trim().length} useGuidance=${guidance.isNotBlank()} ucmLen=${universalMemoryText.length}",
            )

            var sawDone = false
            var exitNarratorJob = false
            val replyStartedAt = System.nanoTime()
            var narratorReplyCommitted = false
            var receivedText = ""
            var chapterDraftId = resumeChapter?.id
            var lastChapterSave = 0L
            suspend fun saveChapterDraft() {
                if (chapterNumber == null || receivedText.isBlank() || narratorReplyCommitted) return
                if (resumeChapter != null && receivedText.length < resumeChapter.content.length) return
                val title = chapterTitle.trim().ifBlank { resumeChapter?.let { NovelChapter.title(it.structuredContentJson) }.orEmpty().ifBlank { "第 $chapterNumber 章" } }
                val json = NovelChapter.draftMetadata("{}", chapterNumber, title)
                val id = chapterDraftId
                if (id == null) chapterDraftId = messageDao.insert(MessageEntity(sessionId = sessionId, speakerType = "narrator",
                    branchId = generation.branchId, content = receivedText, structuredContentJson = json))
                else messageDao.updateNovelDraft(id, sessionId, generation.branchId, receivedText, json)
                lastChapterSave = System.currentTimeMillis()
            }
            try {
                run {
                    for ((idx, nb) in narrBases.withIndex()) {
                        if (idx > 0) {
                            _state.value = _state.value.copy(streamingText = "")
                            UsbSessionLog.i("ChatLlm", "narrator try base ${idx + 1}/${narrBases.size}")
                        }
                        var lastUiUpdateAt = 0L
                        var lastUiLen = 0
                        var errMsg: String? = null
                        generation.ensureCurrent()
                        _state.update { it.copy(lastRequestModel = model, lastRequestPlatform = requestPlatform()?.name) }
                        chatEngine.streamGenerate(
                            sessionId = sessionId,
                            character = character.copy(personaPrompt = narratorPrompt),
                            historyMessages = history,
                            apiKey = apiKey, baseUrl = nb, model = model,
                            temperature = 0.8f, maxTokens = 12000,
                            personaName = secureStorage.userName,
                            userDescription = secureStorage.userDescription,
                        ).collect { s ->
                            when (s) {
                                is StreamState.Generating -> {
                                    generation.ensureCurrent()
                                    val now = System.currentTimeMillis()
                                    receivedText = s.partialText
                                    if (chapterNumber != null && now - lastChapterSave >= 1500L) saveChapterDraft()
                                    val len = s.partialText.length
                                    val shouldUpdate = (now - lastUiUpdateAt) >= 60L || (len - lastUiLen) >= 80
                                    if (shouldUpdate) {
                                        lastUiUpdateAt = now
                                        lastUiLen = len
                                        _state.value = _state.value.copy(streamingText = s.partialText)
                                    }
                                }
                                is StreamState.Done -> {
                                    generation.ensureCurrent()
                                    sawDone = true
                                    UsbSessionLog.i(
                                        "Narrator",
                                        "session=$sessionId done base=${nb.trim()} model=${model.trim()} rawLen=${s.fullText.length} raw=${clipLog(s.fullText)}",
                                    )
                                    val normalizedNarrContent = OutputProcessor.normalizeOptions(
                                        CharacterMediaMarkers.parse(s.fullText).displayText.trim()
                                            .ifBlank { s.fullText.trim() },
                                    )
                                    val baseNarrContent = if (world.gameplayMode == "小说创作") {
                                        StoryCanon.sanitizeMessageChoices(normalizedNarrContent, world.worldPrompt)
                                    } else {
                                        normalizedNarrContent
                                    }
                                    val chapter = chapterNumber?.let { NovelChapter.generated(it, chapterTitle, baseNarrContent) }
                                    val narrContent = chapter?.second ?: baseNarrContent
                                    val replyMetadata = if (chapter != null) NovelChapter.metadata(structuredContentJsonFor(narrContent), chapterNumber!!, chapter.first)
                                        else structuredContentJsonFor(narrContent)
                                    UsbSessionLog.i(
                                        "Narrator",
                                        "session=$sessionId displayLen=${narrContent.length} display=${clipLog(narrContent)}",
                                    )
                                    val completedReply = MessageEntity(
                                            sessionId = sessionId,
                                            speakerType = "narrator",
                                            content = narrContent,
                                            structuredContentJson = ReplyGenerationMetadata.record(replyMetadata,
                                                (System.nanoTime() - replyStartedAt) / 1_000_000, s.usage),
                                            branchId = generation.branchId,
                                        )
                                    val draftId = chapterDraftId
                                    if (draftId == null) messageDao.insert(completedReply)
                                    else messageDao.updateNovelDraft(draftId, sessionId, generation.branchId, completedReply.content, completedReply.structuredContentJson)
                                    narratorReplyCommitted = true
                                    _state.value = _state.value.copy(streamingText = "")
                                    refreshMessagesUi(generation.branchId)
                                    if (currentBranchId() != generation.branchId) {
                                        _state.update {
                                            it.copy(error = "旁白回复已保存，但当前故事线刷新失败，请重新进入对话")
                                        }
                                        exitNarratorJob = true
                                        return@collect
                                    }
                                    val branchId = generation.branchId
                                    val memoryRevision = universalContextMemoryManager.reserveUpdateRevision(sessionId, branchId)
                                    launchSessionMaintenance {
                                        val memoryResult = universalContextMemoryManager.updateAfterMessages(
                                            sessionId = sessionId,
                                            branchId = branchId,
                                            expectedRevision = memoryRevision,
                                            apiKey = apiKey,
                                            baseUrl = nb,
                                            model = model,
                                            worldText = world.worldPrompt,
                                            activeCharacterNames = _state.value.characterNames.values.toList(),
                                        )
                                        val updatedMemory = universalContextMemoryManager.getFormattedMemory(sessionId, branchId)
                                        _state.update { it.withContextMemoryResult(branchId, memoryResult, updatedMemory) }
                                        val recentMessages = getContextMessagesForBranch(branchId).takeLast(20)
                                        memoryV2Manager.extractEventNodes(sessionId, branchId, null, recentMessages, apiKey, nb, model)
                                        refreshEventNodesForBranch(branchId)
                                        if (world.autoSedimentEnabled && world.encyclopediaId != null) {
                                            sedimentEngine.sedimentFromMessages(
                                                encyclopediaId = world.encyclopediaId, sessionId = sessionId,
                                                branchId = branchId, messages = recentMessages.takeLast(10),
                                                apiKey = apiKey, baseUrl = nb, model = model,
                                            )
                                        }

                                    }
                                }
                                is StreamState.Error -> {
                                    generation.ensureCurrent()
                                    errMsg = s.message
                                    UsbSessionLog.w(
                                        "Narrator",
                                        "session=$sessionId error base=${nb.trim()} model=${model.trim()} msg=${clipLog(s.message)}",
                                    )
                                }
                            }
                        }
                        if (sawDone) {
                            if (shouldPromoteNarratorPublicBases(world) && idx > 0) {
                                promoteGlobalChatBaseIfNeeded(nb)
                            }
                            exitNarratorJob = true
                            break
                        }
                        if (errMsg != null) {
                            val partial = receivedText.trim()
                            if (partial.isEmpty() && idx < narrBases.lastIndex) continue
                            val retained = if (chapterNumber != null) { saveChapterDraft(); chapterDraftId != null } else retainInterruptedReply(generation, receivedText, "narrator", null)
                            _state.value = _state.value.copy(
                                streamingText = "",
                                error = UserFacingStrings.streamErrorDetail(errMsg) + if (retained) "\n已保留收到的正文，可继续对话。" else "",
                            )
                            exitNarratorJob = true
                            break
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                generation.ensureCurrent()
                _state.value = _state.value.copy(
                    streamingText = "",
                    error = when {
                        narratorReplyCommitted -> "旁白回复已保存，但对话刷新失败，请重新进入对话"
                        sawDone -> UserFacingStrings.localSaveFailed("旁白回复")
                        else -> UserFacingStrings.remoteRequestFailed(e)
                    },
                )
                return@narratorScope
            } finally {
                if (chapterNumber != null && !narratorReplyCommitted) withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        saveChapterDraft()
                        if (!_state.value.isGenerating && currentBranchId() == generation.branchId) refreshMessagesUi(generation.branchId)
                    } catch (_: Exception) { _state.update { it.copy(error = "章节草稿保存失败，请复制已显示正文") } }
                }
            }
            if (exitNarratorJob) return@narratorScope
        }
    }

    fun clearNovelMetadataError() {
        if (!_state.value.novelMetadataSaving) _state.update { it.copy(novelMetadataError = null) }
    }

    fun renameNovel(title: String, onSuccess: () -> Unit) {
        if (_state.value.novelMetadataSaving) return
        if (title.isBlank() || _state.value.isGenerating) {
            _state.update { it.copy(novelMetadataError = "请填写名称，并在生成结束后保存") }
            return
        }
        _state.update { it.copy(novelMetadataSaving = true, novelMetadataError = null) }
        viewModelScope.launch {
            try {
                sessionDao.updateTitle(sessionId, title.trim().take(100))
                _state.update { it.copy(sessionTitle = title.trim().take(100)) }
                onSuccess()
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(novelMetadataError = "小说标题保存失败，请重试") } }
            finally { _state.update { it.copy(novelMetadataSaving = false) } }
        }
    }

    fun renameChapter(messageId: Long, title: String, onSuccess: () -> Unit) {
        if (_state.value.novelMetadataSaving) return
        if (title.isBlank() || _state.value.isGenerating) {
            _state.update { it.copy(novelMetadataError = "请填写名称，并在生成结束后保存") }
            return
        }
        _state.update { it.copy(novelMetadataSaving = true, novelMetadataError = null) }
        val branch = currentBranchId()
        viewModelScope.launch {
            var saved = false
            try {
                check(getVisibleMessage(branch, messageId) != null) { "章节已不存在" }
                check(currentBranchId() == branch && !_state.value.isGenerating) { "当前故事线状态已变化" }
                messageDao.renameNovelChapter(messageId, sessionId, title)
                saved = true
                onSuccess()
                refreshMessagesUi(branch)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update {
                if (saved) it.copy(error = "章节名称已保存，对话刷新失败，请重新打开对话")
                else it.copy(novelMetadataError = "章节名称保存失败，请确认章节仍在当前故事线后重试")
            } }
            finally { _state.update { it.copy(novelMetadataSaving = false) } }
        }
    }

    suspend fun exportNovel(output: OutputStream): Long = withContext(Dispatchers.IO) {
        val branch = currentBranchId()
        val title = sessionDao.getById(sessionId)?.title.orEmpty()
        val maxId = getMessageTailForBranch(branch, 1).lastOrNull()?.id ?: 0L
        NovelChapter.export(output, title, maxId) { after, limit ->
            if (branch == "main") messageDao.getMainMessagesAfter(sessionId, after, limit)
            else messageDao.getVisibleMessagesAfter(sessionId, branch, after, limit)
        }
    }

    fun createBranch(fromMessageId: Long) {
        if (_state.value.isGenerating) {
            _state.update { it.copy(error = "当前正在生成，请先停止或等待完成后再创建故事线") }
            return
        }
        val launched = launchBranchTransition branchTransition@{
            val parentBranch = currentBranchId()
            var branchCommitted = false
            try {
                val anchor = getVisibleMessage(parentBranch, fromMessageId)
                if (anchor == null) {
                    _state.update { it.copy(error = "源消息已不存在或不属于当前故事线") }
                    return@branchTransition
                }
                val newBranchId = "branch_${System.currentTimeMillis()}"
                val preview = ChatMessageTextFormat.preview(
                    anchor.content,
                    anchor.speakerType,
                    maxChars = 16,
                )
                val label = if (preview.isNotEmpty()) "分支：$preview" else "分支 #$fromMessageId"
                val branch = com.mojing.app.data.local.entity.SessionBranchEntity(
                    sessionId = sessionId,
                    branchId = newBranchId,
                    label = label,
                    sourceMessageId = fromMessageId,
                    parentBranchId = parentBranch,
                )
                sessionBranchDao.insert(branch)
                branchCommitted = true
                val updatedBranches = sessionBranchDao.getBySession(sessionId)
                _state.value = _state.value.copy(
                    branches = updatedBranches,
                    currentBranchId = newBranchId,
                    error = null,
                )
                refreshMessagesUi()
                persistCurrentBranchSelection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        error = if (branchCommitted) {
                            "故事线已创建，但列表刷新失败，请重新进入对话"
                        } else {
                            "故事线创建失败，请重试"
                        },
                    )
                }
            }
        }
        if (!launched) {
            _state.update { it.copy(error = "当前正在切换故事线，请稍后再创建") }
        }
    }

    suspend fun saveMessageImagesToGallery(messageId: Long): GalleryImageSaveResult {
        return saveGalleryImageAttachments(attachmentDao.getByMessage(messageId)) { attachment ->
            imageRepository.saveLocalImageToGallery(
                filePath = attachment.storagePath,
                originalName = attachment.fileName,
                mimeType = attachment.mimeType,
            )
        }
    }

    fun switchBranch(branchId: String) {
        if (_state.value.isGenerating) {
            _state.update { it.copy(error = "当前正在生成，请先停止或等待完成后再切换故事线") }
            return
        }
        val launched = launchBranchTransition switchTransition@{
            val previousBranchId = currentBranchId()
            try {
                val branches = sessionBranchDao.getBySession(sessionId)
                if (branchId != "main" && branches.none { it.branchId == branchId }) {
                    _state.update { it.copy(error = "故事线已不存在，请刷新后重试") }
                    return@switchTransition
                }
                refreshMessagesUi(branchId)
                if (currentBranchId() != branchId) {
                    _state.update { it.copy(error = "故事线已不存在，已返回主线") }
                    persistCurrentBranchSelection()
                    return@switchTransition
                }
                _state.update { it.copy(error = null) }
                persistCurrentBranchSelection()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        currentBranchId = previousBranchId,
                        error = "故事线切换失败，请重试",
                    )
                }
            }
        }
        if (!launched) {
            _state.update { it.copy(error = "当前正在切换故事线，请稍后再试") }
        }
    }

    private suspend fun persistCurrentBranchSelection() {
        val branchId = currentBranchId()
        runCatching {
            uiPreferencesRepository.setLastChatBranch(sessionId, branchId)
        }.onFailure {
            _state.update { state ->
                state.copy(error = "故事线已切换，但重进位置未能保存")
            }
        }
    }

    fun addParticipant(
        characterId: Long,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (!canMutateRoundConfiguration()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                val character = characterDao.getById(characterId)
                if (character == null) {
                    _state.update { it.copy(error = "角色不存在或已删除，请重新选择") }
                    onResult(false)
                    return@launch
                }
                if (character.boundEncyclopediaId <= 0L) {
                    _state.update { it.copy(error = "该角色尚未绑定世界资料，无法加入当前对话") }
                    onResult(false)
                    return@launch
                }
                val world = sessionWorldDao.getBySession(sessionId)
                val encyclopediaId = world?.encyclopediaId?.takeIf { it > 0L }
                if (encyclopediaId != null && character.boundEncyclopediaId > 0L && character.boundEncyclopediaId != encyclopediaId) {
                    _state.update { it.copy(error = "该角色与当前世界不匹配，请重新选择") }
                    onResult(false)
                    return@launch
                }
                val existing = participantDao.getBySession(sessionId)
                if (existing.none { it.characterId == characterId }) {
                    val maxOrder = existing.maxOfOrNull { it.sortOrder } ?: -1
                    participantDao.upsert(
                        SessionParticipantEntity(
                            sessionId = sessionId,
                            characterId = characterId,
                            sortOrder = maxOrder + 1,
                            talkativeness = 0.7f,
                            speakerStrategy = "natural",
                        ),
                    )
                }
                val updated = participantDao.getBySession(sessionId)
                val maps = buildCharacterPresentationMaps(updated)
                _state.value = _state.value.copy(
                    participants = updated,
                    characterNames = maps.names,
                    characterAvatars = maps.avatars,
                    characterCardImages = maps.cardImages,
                    characterColors = maps.colors,
                )
                onResult(true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = "添加角色失败，请重试") }
                onResult(false)
            }
        }
    }

    fun toggleMute(participantId: Long) {
        if (!canMutateRoundConfiguration()) return
        viewModelScope.launch {
            try {
                val participant = participantDao.getById(participantId)
                if (participant == null) {
                    _state.update { it.copy(error = "该角色已不在当前对话中") }
                    return@launch
                }
                participantDao.upsert(participant.copy(muted = !participant.muted))
                val updated = participantDao.getBySession(sessionId)
                _state.value = _state.value.copy(participants = updated)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = "角色静音状态保存失败，请重试") }
            }
        }
    }

    fun updateParticipantTalkativeness(
        participantId: Long,
        talkativeness: Float,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (!canMutateRoundConfiguration()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                val participant = participantDao.getById(participantId)
                if (participant == null) {
                    _state.update { it.copy(error = "该角色已不在当前对话中") }
                    onResult(false)
                    return@launch
                }
                participantDao.upsert(
                    participant.copy(talkativeness = talkativeness.coerceIn(0.05f, 1f)),
                )
                val updated = participantDao.getBySession(sessionId)
                _state.value = _state.value.copy(participants = updated)
                onResult(true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = "发言率保存失败，请重试") }
                onResult(false)
            }
        }
    }

    fun setManualReplyCharacterId(characterId: Long?) {
        _state.value = _state.value.copy(manualReplyCharacterId = characterId)
    }

    fun setQuotingMessage(message: MessageEntity?) {
        if (message != null && ChatMessageTextFormat.quoteSnippet(message.content, 120, message.speakerType).isBlank()) {
            _state.value = _state.value.copy(error = UserFacingStrings.messageHasNoQuotableText())
            return
        }
        quoteDraftRevision++
        if (message != _state.value.quotingMessage) activeDraftSubmissionId = null
        _state.value = _state.value.copy(quotingMessage = message)
        persistCurrentDraft()
    }

    fun currentSpeakerTurnMode(): String = secureStorage.speakerTurnMode

    fun updateSpeakerTurnMode(mode: String): Boolean {
        if (!canMutateRoundConfiguration()) return false
        secureStorage.speakerTurnMode = mode
        if (mode == "auto") {
            _state.value = _state.value.copy(manualReplyCharacterId = null)
        }
        return true
    }

    fun removeParticipant(participantId: Long) {
        if (!canMutateRoundConfiguration()) return
        viewModelScope.launch {
            try {
                val removed = participantDao.getById(participantId)
                if (removed != null) participantDao.delete(participantId)
                val updated = participantDao.getBySession(sessionId)
                val maps = buildCharacterPresentationMaps(updated)
                _state.value = _state.value.copy(
                    participants = updated,
                    characterNames = maps.names,
                    characterAvatars = maps.avatars,
                    characterCardImages = maps.cardImages,
                    characterColors = maps.colors,
                    manualReplyCharacterId = _state.value.manualReplyCharacterId
                        .takeUnless { it == removed?.characterId },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = "移除角色失败，请重试") }
            }
        }
    }

    suspend fun getAllCharacters(): List<CharacterEntity> = characterDao.getAll()

    fun stopGeneration() {
        val job = generationJob
        generationJob = null
        activeGeneration = null
        job?.cancel()
        resetGenerationUi()
    }

    private fun normalizedCorrectionContent(content: String): String? = content.trim().takeIf { it.isNotEmpty() && it.length <= 2000 }

    private var correctionWriteInFlight = false

    fun saveMemoryCorrection(
        correctionId: Long?,
        content: String,
        branchId: String?,
        sourceMessageId: Long? = null,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (correctionWriteInFlight) {
            onResult(false)
            return
        }
        val normalized = normalizedCorrectionContent(content)
        if (normalized == null) {
            _state.update { it.copy(error = if (content.trim().isEmpty()) "纠正内容不能为空" else "纠正内容最多 2000 字") }
            onResult(false)
            return
        }
        if (_state.value.isGenerating) {
            _state.update { it.copy(error = "当前正在生成，请等待完成后再保存纠正") }
            onResult(false)
            return
        }
        correctionWriteInFlight = true
        viewModelScope.launch {
            val saved = try {
                val now = System.currentTimeMillis()
                val existing = correctionId?.let { id ->
                    memoryCorrectionDao.getById(sessionId, id)
                        ?: throw IllegalStateException("纠正记录不存在")
                }
                if (
                    sourceMessageId != null &&
                    sourceMessageId != existing?.sourceMessageId &&
                    messageDao.getByIdInSession(sourceMessageId, sessionId) == null
                ) {
                    throw IllegalArgumentException("来源消息不属于当前会话或已删除")
                }
                if (correctionId == null) {
                    memoryCorrectionDao.insert(
                        SessionMemoryCorrectionEntity(
                            sessionId = sessionId,
                            branchId = branchId,
                            content = normalized,
                            sourceMessageId = sourceMessageId,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                } else {
                    memoryCorrectionDao.update(
                        requireNotNull(existing).copy(
                            content = normalized,
                            branchId = branchId,
                            sourceMessageId = sourceMessageId,
                            updatedAt = now,
                        ),
                    )
                }
                true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(error = "纠正记忆保存失败，请重试") }
                false
            } finally {
                correctionWriteInFlight = false
            }
            onResult(saved)
            if (saved) {
                try {
                    refreshMemoryCorrectionsOnly()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _state.update { it.copy(error = "纠正已保存，列表刷新失败，请重新打开对话") }
                }
            }
        }
    }

    fun deleteMemoryCorrection(id: Long, onResult: (Boolean) -> Unit = {}) {
        if (_state.value.isGenerating) {
            _state.update { it.copy(error = "当前正在生成，请等待完成后再删除纠正") }
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                check(memoryCorrectionDao.deleteById(sessionId, id) == 1) { "纠正记录不存在" }
                refreshMemoryCorrectionsOnly()
                onResult(true)
            } catch (e: Exception) {
                _state.update { it.copy(error = "纠正记忆删除失败：${e.message?.takeIf { it.isNotBlank() } ?: "未知错误"}") }
                onResult(false)
            }
        }
    }

    private suspend fun refreshMemoryCorrectionsOnly() {
        val branchId = currentBranchId()
        val revision = correctionRefreshRevision.incrementAndGet()
        val corrections = memoryCorrectionDao.getVisible(sessionId, branchId)
        _state.update { current ->
            if (current.currentBranchId == branchId && correctionRefreshRevision.get() == revision)
                current.copy(memoryCorrections = corrections) else current
        }
    }

    private fun recordMemoryCorrectionPromptTrace(
        branchId: String,
        responderLabel: String,
        responderType: String,
        corrections: List<SessionMemoryCorrectionEntity>,
    ) {
        _state.update {
            it.copy(
                lastMemoryCorrectionPromptTrace = MemoryCorrectionPromptTrace(
                    branchId = branchId,
                    responderLabel = responderLabel,
                    responderType = responderType,
                    corrections = corrections.toList(),
                ),
            )
        }
    }
    fun toggleDrawer() { _state.value = _state.value.copy(isDrawerOpen = !_state.value.isDrawerOpen) }
    suspend fun previewMessageRecall(messageId: Long): MessageRecallImpact {
        if (getVisibleMessage(currentBranchId(), messageId) == null) {
            return MessageRecallImpact(false, "消息不存在或不在当前故事线")
        }
        return messageDao.previewRecallInSession(sessionId, messageId)
    }

    fun deleteMessage(messageId: Long, onResult: (Boolean) -> Unit = {}) {
        if (_state.value.isGenerating) {
            _state.update { it.copy(error = "当前正在生成，请先停止或等待完成后再撤回消息") }
            onResult(false)
            return
        }
        val launched = launchBranchTransition {
            var committed = false
            try {
                val beforeRecall = _state.value.messages
                val result = messageDao.recallInSession(sessionId, messageId)
                if (!result.deleted) {
                    _state.update { it.copy(error = "消息不存在或已经撤回") }
                    onResult(false)
                    return@launchBranchTransition
                }
                committed = true
                var cleanupFailed = false
                for (path in result.attachmentStoragePaths.distinct()) {
                    try {
                        if (attachmentDao.countByStoragePath(path) == 0) {
                            val removed = withContext(Dispatchers.IO) {
                                ChatAttachmentFiles.deleteOwnedPersistedMediaFile(appContext, sessionId, path)
                            }
                            if (!removed) cleanupFailed = true
                        }
                    } catch (_: Exception) {
                        cleanupFailed = true
                    }
                }
                val neighbors = beforeRecall.filter { it.id !in result.deletedMessageIds }
                val anchor = neighbors.lastOrNull { it.id < messageId } ?: neighbors.firstOrNull()
                refreshMessagesUi(anchorMessageId = anchor?.id)
                if (cleanupFailed) {
                    _state.update { it.copy(error = "消息已撤回，但部分本地媒体文件未能清理") }
                }
                onResult(true)
            } catch (e: MessageRecallBlockedException) {
                _state.update { it.copy(error = e.reason) }
                onResult(false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        error = if (committed) {
                            "消息已撤回，但列表刷新失败，请重新进入对话"
                        } else {
                            "消息撤回失败，请重试"
                        },
                    )
                }
                onResult(false)
            }
        }
        if (!launched) {
            _state.update { it.copy(error = "当前正在生成或切换故事线，请稍后再撤回消息") }
            onResult(false)
        }
    }

    fun editMessage(
        messageId: Long,
        newContent: String,
        onFailure: (message: String, committed: Boolean) -> Unit = { _, _ -> },
        onSuccess: () -> Unit = {},
    ) {
        val content = newContent.trim()
        if (content.isEmpty()) {
            _state.value = _state.value.copy(error = "消息内容不能为空")
            onFailure("消息内容不能为空", false)
            return
        }
        if (_state.value.isGenerating) {
            _state.update { it.copy(error = "当前正在生成，请先停止或等待完成后再编辑消息") }
            onFailure("当前正在生成，请先停止或等待完成后再编辑消息", false)
            return
        }
        var editedMessage: MessageEntity? = null
        var editCommitted = false
        var editReadyForContinuation = false
        val launched = launchBranchTransition(
            onSuccess = editSuccess@{
                if (!editReadyForContinuation) {
                    onFailure(_state.value.error ?: "消息编辑失败，请重试", editCommitted)
                    return@editSuccess
                }
                onSuccess()
                if (editedMessage?.speakerType != "user") return@editSuccess
                launchSingleGeneration editGeneration@{ generation ->
                    _state.value = _state.value.copy(
                        error = null,
                        speakerPlanSummary = null,
                        pendingRoundSpeakers = emptyList(),
                    )
                    try {
                        runScheduledCharacterRound(generation)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        generation.ensureCurrent()
                        _state.value = _state.value.copy(
                            streamingText = "",
                            error = UserFacingStrings.remoteRequestFailed(e),
                        )
                        refreshMessagesUi()
                    }
                }
            },
        ) editTransition@{
            try {
                val original = getVisibleMessage(currentBranchId(), messageId)
                if (original == null) {
                    _state.value = _state.value.copy(error = "消息不存在或不属于当前故事线")
                    return@editTransition
                }
                if (!ChatMessageTextFormat.hasEditChanges(original.content, original.speakerType, content)) {
                    _state.value = _state.value.copy(error = "内容没有变化")
                    return@editTransition
                }
                val parentBranchId = currentBranchId()
                val branchId = "edit_${original.id}_${UUID.randomUUID().toString().take(12)}"
                val preview = ChatMessageTextFormat.preview(
                    raw = content,
                    speakerType = original.speakerType,
                    maxChars = 16,
                )
                val branch = SessionBranchEntity(
                    sessionId = sessionId,
                    branchId = branchId,
                    label = if (preview.isNotEmpty()) "已编辑：$preview" else "编辑分支 #${original.id}",
                    sourceMessageId = original.id,
                    parentBranchId = parentBranchId,
                )
                val editedSwipeGroupId = original.swipeGroupId?.takeIf { it.isNotBlank() }
                val replacement = original.copy(
                    id = 0,
                    branchId = branchId,
                    regeneratedFromMessageId = original.id,
                    swipeGroupId = editedSwipeGroupId,
                    includeInContext = if (editedSwipeGroupId == null) {
                        original.includeInContext
                    } else {
                        false
                    },
                    content = content,
                    structuredContentJson = if (original.speakerType == "user") {
                        original.structuredContentJson
                    } else {
                        ReplyGenerationMetadata.preserve(original.structuredContentJson, structuredContentJsonFor(content))
                    },
                    createdAt = System.currentTimeMillis(),
                )
                val attachments = attachmentDao.getByMessage(original.id)
                val replacementId = sessionBranchDao.insertEditedBranch(branch, replacement, attachments)
                editCommitted = true
                editedMessage = replacement.copy(id = replacementId)
                sessionDao.bumpUpdatedAt(sessionId)
                refreshMessagesUi(branchId)
                if (currentBranchId() != branchId) {
                    _state.update { it.copy(error = "消息已编辑，但新故事线加载失败，请重新进入对话") }
                    return@editTransition
                }
                persistCurrentBranchSelection()
                editReadyForContinuation = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        error = if (editCommitted) {
                            "消息已编辑，但后续状态更新失败，请重新进入对话"
                        } else {
                            "消息编辑失败，请重试"
                        },
                    )
                }
            }
        }
        if (!launched) {
            _state.update { it.copy(error = "当前正在切换故事线，请稍后再编辑") }
            onFailure("当前正在切换故事线，请稍后再编辑", false)
        }
    }

    fun selectSwipeVariant(
        swipeGroupId: String,
        messageId: Long,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (_state.value.isGenerating) {
            _state.update { it.copy(error = "当前正在生成，请先停止或等待完成后再切换回复版本") }
            onResult(false)
            return
        }
        val launched = launchBranchTransition swipeTransition@{
            val branchId = currentBranchId()
            var selectionCommitted = false
            try {
                val target = getVisibleMessage(branchId, messageId)
                if (target?.swipeGroupId != swipeGroupId) {
                    _state.update { it.copy(error = "该回复版本已不存在或不属于当前故事线") }
                    onResult(false)
                    return@swipeTransition
                }
                val updated = messageDao.selectSwipeVariantForBranch(
                    sessionId = sessionId,
                    branchId = branchId,
                    gid = swipeGroupId,
                    messageId = messageId,
                )
                if (updated == 0) {
                    _state.update { it.copy(error = "该回复版本已不存在") }
                    onResult(false)
                    return@swipeTransition
                }
                selectionCommitted = true
                refreshMessagesUi(branchId)
                if (currentBranchId() != branchId) {
                    _state.update { it.copy(error = "回复版本已切换，但当前故事线刷新失败，请重新进入对话") }
                    onResult(false)
                    return@swipeTransition
                }
                onResult(true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        error = if (selectionCommitted) {
                            "回复版本已切换，但对话刷新失败，请重新进入对话"
                        } else {
                            "回复版本切换失败，请重试"
                        },
                    )
                }
                onResult(false)
            }
        }
        if (!launched) {
            _state.update { it.copy(error = "当前正在生成或切换故事线，请稍后再切换回复版本") }
            onResult(false)
        }
    }

    fun toggleBookmark(messageId: Long) =
        setBookmark(messageId, messageId !in _state.value.bookmarkedMessageIds)

    fun removeBookmark(messageId: Long) = setBookmark(messageId, false)

    private fun setBookmark(messageId: Long, bookmarked: Boolean) {
        if (!_state.value.isReady || messageId in _state.value.bookmarkBusyIds) return
        _state.update { it.copy(bookmarkBusyIds = it.bookmarkBusyIds + messageId) }
        bookmarkRefreshRevision.incrementAndGet()
        viewModelScope.launch {
            try {
                val existing = bookmarkDao.getByMessageId(messageId)
                check(existing == null || existing.sessionId == sessionId)
                val mark: MessageBookmarkEntity?
                var preview: String? = null
                if (bookmarked) {
                    val message = _state.value.messages.firstOrNull { it.id == messageId }
                        ?: messageDao.getById(messageId)
                    check(message?.sessionId == sessionId) { "消息已不存在" }
                    preview = withContext(Dispatchers.Default) {
                        ChatMessageTextFormat.preview(message!!.content, message.speakerType, 120, "（无正文）")
                    }
                    val entry = existing ?: MessageBookmarkEntity(sessionId = sessionId, messageId = messageId, note = "")
                    mark = if (existing == null) entry.copy(id = bookmarkDao.insert(entry)) else entry
                } else {
                    bookmarkDao.deleteByMessageId(messageId)
                    mark = null
                }
                bookmarkRefreshRevision.incrementAndGet()
                _state.update { current ->
                    val marks = (current.bookmarks.filterNot { it.messageId == messageId } + listOfNotNull(mark))
                        .sortedWith(compareByDescending<MessageBookmarkEntity> { it.createdAt }.thenByDescending { it.id })
                    current.copy(
                        bookmarks = marks,
                        bookmarkedMessageIds = marks.mapTo(mutableSetOf()) { it.messageId },
                        bookmarkPreviews = if (preview != null) current.bookmarkPreviews + (messageId to preview)
                            else current.bookmarkPreviews - messageId,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(error = if (bookmarked) "收藏失败，请重试" else "取消收藏失败，请重试") }
            } finally {
                _state.update { it.copy(bookmarkBusyIds = it.bookmarkBusyIds - messageId) }
            }
        }
    }

    fun searchSession(query: String, exactMatch: Boolean = false, beforeMessageId: Long = Long.MAX_VALUE) {
        val previous = _state.value
        clearSearch()
        val q = query.trim()
        if (q.isEmpty()) return
        val revision = messageSearchRevision
        val searchedSession = sessionId
        val branchId = currentBranchId()
        fun isCurrent() = revision == messageSearchRevision && sessionId == searchedSession && currentBranchId() == branchId
        _state.update {
            if (previous.completedSearchQuery == q && previous.searchExactMatch == exactMatch) {
                it.copy(isSearchingMessages = true, searchResults = previous.searchResults,
                    searchPreviews = previous.searchPreviews, completedSearchQuery = q,
                    searchBeforeId = previous.searchBeforeId, searchHasOlder = previous.searchHasOlder,
                    searchExactMatch = exactMatch)
            } else it.copy(isSearchingMessages = true)
        }
        messageSearchJob = viewModelScope.launch {
            try {
                val page = if (branchId == "main") {
                    messageDao.searchMainMessages(searchedSession, q, if (exactMatch) 1 else 0, SEARCH_RESULT_LIMIT + 1, beforeMessageId)
                } else {
                    messageDao.searchVisibleMessages(searchedSession, branchId, q, if (exactMatch) 1 else 0, SEARCH_RESULT_LIMIT + 1, beforeMessageId)
                }
                val hits = page.take(SEARCH_RESULT_LIMIT)
                val previews = withContext(Dispatchers.Default) {
                    hits.associate { it.id to ChatMessageTextFormat.searchPreview(it.content, it.speakerType, q) }
                }
                if (isCurrent()) _state.update { it.copy(searchResults = hits, searchPreviews = previews, completedSearchQuery = q, searchHasOlder = page.size > SEARCH_RESULT_LIMIT, searchBeforeId = beforeMessageId, searchExactMatch = exactMatch, isSearchingMessages = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (isCurrent()) _state.update { it.copy(isSearchingMessages = false, searchError = "搜索未完成，请重试") }
            }
        }
    }

    fun olderSearchResults() {
        val state = _state.value
        if (state.isSearchingMessages || !state.searchHasOlder) return
        val beforeId = state.searchResults.lastOrNull()?.id ?: return
        searchSession(state.completedSearchQuery, state.searchExactMatch, beforeId)
    }

    fun clearSearch() {
        messageSearchRevision++
        messageSearchJob?.cancel()
        messageSearchJob = null
        _state.update { it.copy(searchResults = emptyList(), searchPreviews = emptyMap(), completedSearchQuery = "", searchHasOlder = false, searchBeforeId = Long.MAX_VALUE, isSearchingMessages = false, searchError = null) }
    }

    /** 将主分支按固定快照上界分页写为 UTF-8 JSON；调用方持有并关闭输出流。 */
    suspend fun exportMainBranchJson(output: OutputStream): Long = withContext(Dispatchers.IO) {
        val maxMessageId = messageDao.getMainBranchMaxMessageId(sessionId)
        MainBranchChatExportWriter.write(
            output = output,
            sessionId = sessionId,
            exportedAt = System.currentTimeMillis(),
            maxMessageId = maxMessageId,
            loadPage = { afterMessageId, throughMessageId, limit ->
                messageDao.getMainMessagesForExport(
                    sessionId = sessionId,
                    afterMessageId = afterMessageId,
                    maxMessageId = throughMessageId,
                    limit = limit,
                ).let { page -> withEffectiveSwipeSelections("main", page) }
            },
        )
    }

    fun deleteEventNode(nodeId: Long) {
        val branchId = currentBranchId()
        if (_state.value.eventNodes.none { it.id == nodeId }) return
        viewModelScope.launch {
            try {
                eventNodeDao.deleteById(nodeId)
                _state.update { if (it.currentBranchId == branchId) it.copy(eventNodes = it.eventNodes.filter { event -> event.id != nodeId }) else it }
                refreshEventNodesForBranch(branchId, reportFailure = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { if (it.currentBranchId == branchId) it.copy(error = "事件删除失败，请重试") else it } }
        }
    }

    fun toggleEventNodeResolved(nodeId: Long) {
        val branchId = currentBranchId()
        val target = _state.value.eventNodes.firstOrNull { it.id == nodeId } ?: return
        viewModelScope.launch {
            try {
                eventNodeDao.setResolved(nodeId, !target.resolved)
                _state.update { if (it.currentBranchId == branchId) it.copy(eventNodes = it.eventNodes.map { event -> if (event.id == nodeId) event.copy(resolved = !target.resolved) else event }) else it }
                refreshEventNodesForBranch(branchId, reportFailure = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { if (it.currentBranchId == branchId) it.copy(error = "事件状态保存失败，请重试") else it } }
        }
    }

    private suspend fun refreshEventNodesForBranch(branchId: String, reportFailure: Boolean = false) {
        val revision = eventRefreshRevision.incrementAndGet()
        try {
            val events = eventNodeDao.getForBranch(sessionId, branchId)
            _state.update { if (it.currentBranchId == branchId && eventRefreshRevision.get() == revision) it.copy(eventNodes = events) else it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (reportFailure) _state.update { if (it.currentBranchId == branchId && eventRefreshRevision.get() == revision) it.copy(error = "修改已保存，事件列表刷新失败，可重新进入当前故事线") else it }
        }
    }

    fun rebuildCurrentContextMemory(onDone: (String) -> Unit = {}) {
        if (_state.value.memoryOperationRunning || _state.value.isGenerating) return
        _state.update { it.copy(memoryOperationRunning = true) }
        viewModelScope.launch {
          try {
            val branchId = currentBranchId()
            val world = sessionWorldDao.getBySession(sessionId)
            val firstCharacter = _state.value.participants.firstOrNull()?.let { participant ->
                characterDao.getById(participant.characterId)
            }
            val resolvedCharacter = firstCharacter ?: CharacterEntity()
            val connection = chatConnection(world, resolvedCharacter)
            connection.error?.let { onDone(it); return@launch }
            val apiKey = connection.apiKey
            val baseUrl = connection.baseUrl
            val model = resolveMainChatModelId(resolvedCharacter, connection)
            if (apiKey.isBlank() || baseUrl.isBlank() || model.isBlank()) {
                onDone("当前线路未配置可用对话模型，无法重建记忆")
                return@launch
            }
            val memoryRevision = universalContextMemoryManager.reserveUpdateRevision(sessionId, branchId)
            val ok = universalContextMemoryManager.rebuild(
                sessionId = sessionId,
                branchId = branchId,
                expectedRevision = memoryRevision,
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = model,
                worldText = world?.worldPrompt.orEmpty(),
                activeCharacterNames = _state.value.characterNames.values.toList(),
            )
            val memory = universalContextMemoryManager.getFormattedMemory(sessionId, branchId)
            _state.update {
                if (it.currentBranchId == branchId) it.copy(
                    contextMemoryText = memory,
                    contextMemoryStatus = if (ok) ContextMemoryStatus.UPDATED else ContextMemoryStatus.FAILED,
                ) else it
            }
            onDone(if (ok) "已重建当前会话记忆" else "重建失败，现有记忆未被清空")
          } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { onDone("重建失败，请重试") }
          finally { _state.update { it.copy(memoryOperationRunning = false) } }
        }
    }

    fun clearCurrentContextMemory(onDone: (String) -> Unit = {}) {
        if (_state.value.memoryOperationRunning || _state.value.isGenerating) return
        _state.update { it.copy(memoryOperationRunning = true) }
        val branchId = currentBranchId()
        viewModelScope.launch {
            try {
                universalContextMemoryManager.clear(sessionId, branchId)
                _state.update { if (it.currentBranchId == branchId) it.copy(contextMemoryText = "", contextMemoryStatus = ContextMemoryStatus.IDLE) else it }
                onDone("已清空长期记忆；后续对话会重新整理")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onDone("清空失败，请重试") }
            finally { _state.update { it.copy(memoryOperationRunning = false) } }
        }
    }

    fun saveSessionWorldCredentials(
        draft: SessionWorldCredentialDraft,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (!canMutateRoundConfiguration()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val updated = try {
                val current = sessionWorldDao.getBySession(sessionId)
                    ?: SessionWorldEntity(sessionId = sessionId)
                val pending = current.copy(
                    sessionLlmApiKey = draft.sessionLlmApiKey.trim(),
                    sessionLlmBaseUrl = draft.sessionLlmBaseUrl.trim(),
                    sessionImageApiKey = draft.sessionImageApiKey.trim(),
                    sessionImageBaseUrl = draft.sessionImageBaseUrl.trim(),
                    sessionImageModel = draft.sessionImageModel.trim(),
                    sessionVoiceApiKey = draft.sessionVoiceApiKey.trim(),
                    sessionVoiceBaseUrl = draft.sessionVoiceBaseUrl.trim(),
                    sessionVoiceModel = draft.sessionVoiceModel.trim(),
                    sessionVoiceSpeechVoice = draft.sessionVoiceSpeechVoice.trim(),
                    sessionVoicePresetPrefixModel = draft.sessionVoicePresetPrefixModel.trim(),
                    updatedAt = System.currentTimeMillis(),
                )
                sessionWorldDao.upsert(pending)
                pending
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = "本场线路保存失败，请重试") }
                onResult(false)
                return@launch
            }
            _state.update { it.copy(world = updated) }
            onResult(true)
        }
    }

    fun updateWorldSetting(key: String, value: Boolean) {
        if (!canMutateRoundConfiguration()) return
        viewModelScope.launch {
            try {
                val world = _state.value.world ?: SessionWorldEntity(sessionId = sessionId)
                val updated = when (key) {
                    "narratorEnabled" -> world.copy(narratorEnabled = value)
                    "choiceGenerationEnabled" -> world.copy(choiceGenerationEnabled = value)
                    "antiCheatEnabled" -> world.copy(antiCheatEnabled = value)
                    "autoSedimentEnabled" -> world.copy(autoSedimentEnabled = value)
                    "autoCharacterImageGen" -> world.copy(autoCharacterImageGen = value)
                    "autoCharacterSpeech" -> world.copy(autoCharacterSpeech = value)
                    else -> return@launch
                }
                sessionWorldDao.upsert(updated)
                _state.value = _state.value.copy(world = updated)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = "本场玩法保存失败，请重试") }
            }
        }
    }

    fun handleMessageAction(action: MessageAction) {
        when (action) {
            is MessageAction.Speak -> speakMessage(ChatMessageTextFormat.visibleBody(action.message.content, action.message.speakerType), action.message.characterId)
            is MessageAction.Copy -> Unit // 剪贴板：由 ChatScreen 处理
            is MessageAction.ContinueReply -> {
                val message = action.message
                if (message.speakerType != "user") return
                if (_state.value.world?.gameplayMode == "小说创作") {
                    requestNarrator(expectedTailMessageId = message.id)
                } else {
                    launchSingleGeneration(expectedTailMessageId = message.id) continueGeneration@{ generation ->
                        if (!generation.ensureExpectedUserTail()) return@continueGeneration
                        _state.value = _state.value.copy(
                            error = null,
                            speakerPlanSummary = null,
                            pendingRoundSpeakers = emptyList(),
                        )
                        runScheduledCharacterRound(generation)
                    }
                }
            }
            is MessageAction.Recall -> deleteMessage(action.message.id)
            is MessageAction.Regenerate -> {
                val msg = action.message
                if (msg.speakerType != "character") {
                    _state.value = _state.value.copy(
                        error = "旁白内容请使用编辑或从此处分支调整",
                    )
                    return
                }
                launchSingleGeneration regenerateGeneration@{ generation ->
                    try {
                        if (!ReplyRegenerationPolicy.canRegenerate(
                                messages = _state.value.messages,
                                target = msg,
                                hasNewerMessages = _state.value.hasNewerMessages,
                            )
                        ) {
                            _state.value = _state.value.copy(
                                error = "只能重新生成当前故事线最后一条角色回复；更早内容请使用编辑或从此处分支",
                            )
                            return@regenerateGeneration
                        }
                        if (hasVisibleStoryMessageAfterRegenerationTarget(generation.branchId, msg)) {
                            _state.value = _state.value.copy(
                                error = "只能重新生成当前故事线最后一条角色回复；更早内容请使用编辑或从此处分支",
                            )
                            return@regenerateGeneration
                        }
                        val contextMessages = getContextMessagesForBranch(generation.branchId)
                        if (contextMessages.none { it.id == msg.id }) {
                            _state.value = _state.value.copy(
                                error = "这条回复已不在当前故事线，请刷新后再试",
                            )
                            return@regenerateGeneration
                        }
                        generation.contextMessagesOverride = contextMessages.filterNot { it.id == msg.id }
                        val gid = msg.swipeGroupId?.takeIf { it.isNotBlank() }
                            ?: UUID.randomUUID().toString()
                        // 远程生成成功前不改消息行；Done 后由单一 Room 事务绑定组、
                        // 写入新版本并只更新当前故事线的采用状态。
                        generation.swipeGroupId = gid
                        generation.swipeSourceMessageId = msg.id
                        val characterId = msg.characterId
                        if (characterId == null) {
                            _state.value = _state.value.copy(
                                error = UserFacingStrings.chatCharacterNotFound(),
                            )
                            return@regenerateGeneration
                        }
                        val character = characterDao.getById(characterId)
                        if (character == null) {
                            _state.value = _state.value.copy(
                                error = UserFacingStrings.chatCharacterNotFound(),
                            )
                            return@regenerateGeneration
                        }
                        val ok = streamCharacterReply(
                            generation,
                            character,
                            runMemoryCompact = true,
                            manageGeneratingFlag = true,
                        )
                        if (!ok) return@regenerateGeneration
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        generation.ensureCurrent()
                        _state.value = _state.value.copy(
                            streamingText = "",
                            error = UserFacingStrings.streamErrorDetail(e.message),
                        )
                        refreshMessagesUi()
                    }
                }
            }
            is MessageAction.ToggleBookmark -> toggleBookmark(action.message.id)
            is MessageAction.SelectChoice -> {
                val choice = action.choice.trim()
                val current = _state.value
                if (
                    choice.isEmpty() ||
                    current.hasNewerMessages ||
                    action.sourceMessageId != current.roundChoiceMessageId ||
                    choice !in current.roundChoiceOptions
                ) {
                    _state.update { it.copy(error = "该选项已不属于当前回合，请选择最新回复中的选项") }
                    return
                }
                updateInput(choice)
                sendMessage()
            }
            is MessageAction.Edit -> Unit // 编辑弹窗由 ChatScreen 处理
            is MessageAction.SaveImages -> Unit // 系统权限与相册反馈由 ChatScreen 处理
            is MessageAction.Quote -> setQuotingMessage(action.message)
            is MessageAction.CreateBranch -> createBranch(action.message.id)
            is MessageAction.SwitchToBranch -> switchBranch(action.branchId)
        }
    }
}

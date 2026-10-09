package com.mojing.app.ui.chat

import com.mojing.app.domain.story.NovelChapter
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.ChatDraftSnapshot
import com.mojing.app.data.ChapterInputDraft
import com.mojing.app.data.ChatDraftStore
import com.mojing.app.data.ReplyRecoveryLoadResult
import com.mojing.app.data.ReplyRecoverySnapshot
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.data.local.dao.MessageRecallImpact
import com.mojing.app.data.local.dao.MessageRecallBlockedException
import com.mojing.app.data.local.dao.AttachmentDao
import com.mojing.app.data.local.dao.BookmarkDao
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.NewSessionCharacterOption
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.AutoImageMetadata
import com.mojing.app.data.local.AutoVoiceMetadata
import com.mojing.app.data.local.dao.ReplyRecoveryMetadata
import com.mojing.app.data.local.dao.ParticipantDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.dao.SessionEventNodeDao
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.BranchEventStatusEntity
import com.mojing.app.data.repository.ImageRepository
import com.mojing.app.data.remote.ImageApiService
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.engine.MemoryV2Manager
import com.mojing.app.data.local.dao.SessionMemorySegmentDao
import com.mojing.app.data.local.dao.SessionMemoryCorrectionDao
import com.mojing.app.data.local.dao.SessionWorldDao
import com.mojing.app.data.local.dao.SessionBranchDao
import com.mojing.app.data.local.branch.BranchVisibilityIndexManager
import com.mojing.app.data.local.dao.CharacterStateDao
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.data.local.entity.SessionMemoryCorrectionEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.domain.config.ApiKeyResolver
import com.mojing.app.domain.config.ImageBasePromote
import com.mojing.app.domain.config.ImageGenResolved
import com.mojing.app.domain.config.VoiceTtsParams
import com.mojing.app.data.local.entity.SessionWorldCredentialDraft
import com.mojing.app.data.local.entity.MessageBookmarkEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import com.mojing.app.data.local.entity.contextSelectionKey
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
import com.mojing.app.domain.engine.CharacterSnapshotCadence
import com.mojing.app.domain.engine.MemoryCompactor
import com.mojing.app.domain.engine.SummaryMaintenanceResult
import com.mojing.app.domain.engine.SummaryMaintenanceUseCase
import com.mojing.app.domain.engine.ContextBuilder
import com.mojing.app.domain.engine.SedimentEngine
import com.mojing.app.domain.engine.NarratorEngine
import com.mojing.app.domain.engine.SpeakerScheduler
import com.mojing.app.domain.engine.OutputProcessor
import com.mojing.app.domain.engine.UniversalContextMemoryManager
import com.mojing.app.domain.engine.UniversalContextMemoryUpdateResult
import com.mojing.app.domain.story.StoryCanon
import com.mojing.app.domain.chat.MainBranchChatExportWriter
import com.mojing.app.domain.chat.TavernChatImportParser
import com.mojing.app.domain.usecase.MessageSubmissionTransaction
import com.mojing.app.domain.usecase.MainBranchMediaBundleUseCase
import com.mojing.app.util.ContentDocumentWriter
import android.net.Uri
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.util.ApiRootLines
import com.mojing.app.util.ChatAttachmentFiles
import com.mojing.app.util.UsbSessionLog
import com.mojing.app.media.AndroidTts
import com.mojing.app.media.newmedia.SpeechPlaybackControl
import com.mojing.app.media.HttpTts
import com.mojing.app.media.TtsSpeakText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val thinkMaxEnabledIds: Set<Long>,
    val summaries: Map<Long, String>,
)

internal data class AddParticipantPage(val rows: List<NewSessionCharacterOption>, val hasMore: Boolean)

private const val ADD_PARTICIPANT_PAGE_SIZE = 40

internal fun estimateLoadedContextTokens(messages: List<MessageEntity>, excludedKeys: Set<String>): Int =
    messages.asSequence()
        .filter { it.includeInContext && it.contextSelectionKey() !in excludedKeys }
        .sumOf { TokenCounter.estimateScaledPrefix(ConversationMessageText.forDerivedContext(it)) }

private class GenerationContext(
    val branchId: String,
    val modelPlatforms: List<com.mojing.app.data.ModelPlatform> = emptyList(),
    val expectedTailMessageId: Long? = null,
    val draftSubmissionId: String? = null,
    var swipeGroupId: String? = null,
    var swipeSourceMessageId: Long? = null,
    var contextMessagesOverride: List<MessageEntity>? = null,
    var started: Boolean = false,
    var interruptedReplyText: String = "",
    var interruptedReplySpeakerType: String? = null,
    var interruptedReplyCharacterId: Long? = null,
    var replyRecovery: ReplyRecoverySnapshot? = null,
    var lastRecoveryCheckpointAt: Long = 0L,
    var lastRecoveryCheckpointLength: Int = 0,
)

data class TavernChatImportResult(
    val importedCount: Int,
    val duplicate: Boolean,
    val refreshFailed: Boolean = false,
)

data class MediaBundleProgress(
    val kind: String,
    val stage: String,
    val count: Int = 0,
    val stopping: Boolean = false,
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
    private val savedStateHandle: SavedStateHandle,
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val characterDao: CharacterDao,
    private val participantDao: ParticipantDao,
    private val sessionWorldDao: SessionWorldDao,
    private val sessionBranchDao: SessionBranchDao,
    private val branchVisibilityIndexManager: BranchVisibilityIndexManager,
    private val memorySegmentDao: SessionMemorySegmentDao,
    private val memoryCorrectionDao: SessionMemoryCorrectionDao,
    private val eventNodeDao: SessionEventNodeDao,
    private val costRecorder: CostRecorder,
    private val chatEngine: ChatEngine,
    private val secureStorage: SecureStorage,
    private val promptBuilder: PromptBuilder,
    private val memoryCompactor: MemoryCompactor,
    private val summaryMaintenance: SummaryMaintenanceUseCase,
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
    @param:ApplicationContext private val appContext: Context,
    private val mediaBundleDatabase: com.mojing.app.data.local.AppDatabase? = null,
) : ViewModel() {

    private companion object {
        const val INITIAL_MESSAGE_WINDOW_SIZE = 80
        const val MESSAGE_PAGE_SIZE = 40
        const val BOOKMARK_PAGE_SIZE = 40
        const val MEMORY_SEGMENT_PAGE_SIZE = 16
        // Long derived text: five pages; DAO reads at most 81 rows including lookahead.
        const val MEMORY_SEGMENT_WINDOW_SIZE = MEMORY_SEGMENT_PAGE_SIZE * 5
        const val MAX_MESSAGE_WINDOW_SIZE = 200
        const val MODEL_CONTEXT_MESSAGE_LIMIT = 400
        const val DRAFT_SUBMISSION_JSON_KEY = "draftSubmissionId"
        const val MAX_BOOKMARK_NOTE_LENGTH = 2_000
    }

    private val sessionId: Long = savedStateHandle["sessionId"] ?: 0L
    private val sourceMessageId: Long = savedStateHandle["sourceMessageId"] ?: 0L
    private val sourceBranchId: String = savedStateHandle["sourceBranchId"] ?: ""
    private val _state = MutableStateFlow(
        ChatContract.State(
            sessionId = sessionId,
            bookmarkNoteDrafts = restoreBookmarkNoteDrafts(),
            bookmarkQuery = savedStateHandle["bookmark_query_$sessionId"] ?: "",
            bookmarkReadOnlyId = savedStateHandle.get<Long>("bookmark_reader_id_$sessionId")?.takeIf { it > 0L },
            bookmarkReadOnlyBranchId = savedStateHandle.get<String>("bookmark_reader_branch_$sessionId"),
            bookmarkReadOnlyLoading = savedStateHandle.get<Long>("bookmark_reader_id_$sessionId")?.let { it > 0L } == true,
        ),
    )
    private val bookmarkMutex = Mutex()
    private val characterStateMutex = Mutex()
    private var bookmarkInitialLoadJob: Job? = null
    private val bookmarkRefreshRevision = java.util.concurrent.atomic.AtomicLong()
    private var bookmarkReadOnlyRevision = 0L
    // A reference to the existing locating/branch-transition job, never a second task owner.
    private var bookmarkReadOnlyJob: Job? = null
    private val messageWindowRevision = java.util.concurrent.atomic.AtomicLong()
    internal var tokenEstimateDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
    internal var preparationDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
    private var tokenEstimateJob: Job? = null
    private val tokenEstimateRevision = java.util.concurrent.atomic.AtomicLong()
    private val eventRefreshRevision = java.util.concurrent.atomic.AtomicLong()
    private var eventPanelRequestedBranchId: String? = null
    private val correctionRefreshRevision = java.util.concurrent.atomic.AtomicLong()
    private val memorySummaryListRevision = java.util.concurrent.atomic.AtomicLong()
    private val contextMemoryDisplayRevision = java.util.concurrent.atomic.AtomicLong()
    private val encyclopediaFoundationRevision = java.util.concurrent.atomic.AtomicLong()
    private var manualCompactionJob: Job? = null
    val state: StateFlow<ChatContract.State> = _state.asStateFlow()
    private var mediaBundleJob: Job? = null
    private val _mediaBundleProgress = MutableStateFlow<MediaBundleProgress?>(null)
    val mediaBundleProgress: StateFlow<MediaBundleProgress?> = _mediaBundleProgress.asStateFlow()
    private val _mediaBundleNotice = MutableStateFlow<String?>(null)
    val mediaBundleNotice: StateFlow<String?> = _mediaBundleNotice.asStateFlow()

    fun clearMediaBundleNotice(expected: String? = null) {
        _mediaBundleNotice.update { current -> if (expected == null || current == expected) null else current }
    }

    fun cancelMediaBundle() {
        if (mediaBundleJob?.isActive == true) {
            _mediaBundleProgress.update { it?.copy(stopping = true) }
            mediaBundleJob?.cancel()
        }
    }

    suspend fun stopMediaBundleAndJoin() {
        val owner = mediaBundleJob
        cancelMediaBundle()
        owner?.join()
    }

    private fun rejectDuringMediaBundle(): Boolean {
        if (mediaBundleJob == null) return false
        _state.update { it.copy(error = "请先完成或停止主线媒体包操作") }
        return true
    }

    fun importMainBranchMediaBundle(uri: Uri, expectedBranchId: String): Boolean =
        launchMediaBundle("import", expectedBranchId) { useCase, ensureOwner, progress ->
            val result = useCase.importBundle(
                sessionId, expectedBranchId,
                openInput = { appContext.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法读取媒体包，请重新选择文件") },
                ensureOwner = ensureOwner,
                onProgress = progress,
            )
            if (result.duplicate) "该主线媒体包已导入当前故事线，未重复写入"
            else {
                val refreshFailed = withContext(NonCancellable) {
                    runCatching {
                        sessionDao.bumpUpdatedAt(sessionId)
                        refreshMessagesUi(expectedBranchId)
                    }.isFailure
                }
                "已导入 ${result.messageCount} 条记录和 ${result.mediaCount} 个媒体" +
                    if (refreshFailed) "，请重新打开对话刷新列表" else ""
            }
        }

    fun exportMainBranchMediaBundle(uri: Uri, expectedBranchId: String): Boolean =
        launchMediaBundle("export", expectedBranchId) { useCase, ensureOwner, progress ->
            val count = ContentDocumentWriter.writeStream(appContext, uri) { output ->
                useCase.exportBundle(output, sessionId, ensureOwner, progress)
            }
            "已导出主线记录与媒体（$count 条记录）"
        }

    private fun launchMediaBundle(
        kind: String,
        expectedBranchId: String,
        block: suspend (MainBranchMediaBundleUseCase, suspend () -> Unit, suspend (String, Int) -> Unit) -> String,
    ): Boolean {
        if (rejectDuringMediaBundle()) return false
        if (!_state.value.isReady || _state.value.sessionNotFound || currentBranchId() != expectedBranchId ||
            activeGeneration != null || branchTransitionJob?.isActive == true || initializationJob?.isActive == true) {
            _state.update { it.copy(error = "会话或故事线已变化，请等待载入与生成完成后重新选择文件") }
            return false
        }
        _mediaBundleNotice.value = null
        _mediaBundleProgress.value = MediaBundleProgress(kind, "正在检查文件")
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            val useCase = MainBranchMediaBundleUseCase(appContext, messageDao, attachmentDao,
                characterDao, participantDao, sessionDao, sessionBranchDao, database = mediaBundleDatabase)
            val ensureOwner: suspend () -> Unit = {
                withContext(Dispatchers.Main.immediate) {
                    check(mediaBundleJob === owner && owner?.isActive == true &&
                        currentBranchId() == expectedBranchId && activeGeneration == null &&
                        branchTransitionJob?.isActive != true && !_state.value.sessionNotFound) {
                        "会话或故事线已变化，本次操作已停止"
                    }
                }
            }
            try {
                val notice = block(useCase, ensureOwner) { stage, count ->
                    withContext(Dispatchers.Main.immediate) {
                        if (mediaBundleJob === owner) _mediaBundleProgress.update { it?.copy(stage = stage, count = count) }
                    }
                }
                if (mediaBundleJob === owner) _mediaBundleNotice.value = notice
            } catch (_: CancellationException) {
                val saved = useCase.committedImportResult
                if (saved != null) withContext(NonCancellable) {
                    runCatching { sessionDao.bumpUpdatedAt(sessionId); refreshMessagesUi(expectedBranchId) }
                }
                if (mediaBundleJob === owner) _mediaBundleNotice.value =
                    if (saved != null) "已导入 ${saved.messageCount} 条记录和 ${saved.mediaCount} 个媒体"
                    else if (kind == "import" && useCase.commitOutcomeUnknown) "操作已停止，暂时无法确认保存结果；请重新打开会话检查"
                    else if (kind == "import") "媒体包导入已停止，未提交内容已撤销"
                    else "媒体包导出已停止，目标文件可能不完整，请重新导出"
            } catch (e: Exception) {
                if (mediaBundleJob === owner) _mediaBundleNotice.value = e.message
                    ?.takeIf { message -> message.any { it in '\u4e00'..'\u9fff' } }
                    ?: "媒体包操作失败，请检查文件及可用空间后重试"
            } finally {
                if (mediaBundleJob === owner) {
                    mediaBundleJob = null
                    _mediaBundleProgress.value = null
                }
            }
        }
        mediaBundleJob = job
        job.start()
        return true
    }
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
    private fun chatConnection(world: SessionWorldEntity?, character: CharacterEntity? = null, platform: com.mojing.app.data.ModelPlatform? = requestPlatform()): com.mojing.app.domain.config.ChatConnection {
        platform?.let {
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
    private var autoNarratorJob: Job? = null
    private var activeGeneration: GenerationContext? = null
    /** Single owner for automatic snapshot deletion; closing the sheet never releases it. */
    private var characterStateClearJob: Job? = null
    private var characterStateReadJob: Job? = null
    private var characterStateReadRevision = 0L
    private var pendingReplyRecovery: ReplyRecoverySnapshot? = null
    private var unreadableReplyRecovery: String? = null
    private var branchTransitionJob: Job? = null
    private var initializationJob: Job? = null
    private var historyLoadJob: Job? = null
    private var activeDraftSubmissionId: String? = null
    private var narratorDraftRevision = 0L
    private var imageDraftRevision = 0L
    private var quoteDraftRevision = 0L
    private var quotePreparationJob: Job? = null

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
        if (rejectDuringMediaBundle()) return false
        if (!_state.value.isReady || rejectPendingReplyRecovery()) return false
        if (activeGeneration != null || branchTransitionJob?.isActive == true) return false
        if (characterStateClearJob?.isActive == true) {
            _state.update { it.copy(error = "角色状态正在清除，请稍后再生成回复") }
            return false
        }
        if (_state.value.memoryOperationRunning) {
            _state.update { it.copy(error = "记忆整理中，请稍候再生成回复") }
            return false
        }
        if (_state.value.modelSelectionSaving) {
            _state.update { it.copy(error = "模型选择正在保存，请稍候再发送") }
            return false
        }
        if (_state.value.sessionThinkMaxSaving) {
            _state.update { it.copy(error = "思考/Max 设置正在保存，请稍候再发送") }
            return false
        }
        if (rejectPendingWorldWrite()) return false
        cancelPendingAutoNarrator()
        roundPlatform = try { selectedPlatform() } catch (_: Exception) {
            _state.update { it.copy(error = "所选平台或模型已变更，请重新选择后发送") }
            return false
        }
        val modelPlatforms = try { roundPlatform?.let { listOf(it) } ?: secureStorage.modelPlatforms() } catch (_: Exception) {
            _state.update { it.copy(error = "平台配置暂时无法读取，原数据已保留") }
            return false
        }
        clearImageRetry()
        historyLoadJob?.cancel()
        historyLoadJob = null
        _state.update { it.copy(isLoadingHistory = false, contextBudgetError = null) }
        val generation = GenerationContext(
            branchId = currentBranchId(),
            modelPlatforms = modelPlatforms,
            expectedTailMessageId = expectedTailMessageId,
            draftSubmissionId = draftSubmissionId,
        )
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            generation.started = true
            try {
                block(generation)
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    if (generationJob === owner && activeGeneration === generation) {
                        runCatching {
                            retainInterruptedReply(
                                generation = generation,
                                text = generation.interruptedReplyText,
                                speakerType = generation.interruptedReplySpeakerType,
                                characterId = generation.interruptedReplyCharacterId,
                                allowCancelledOwner = true,
                            )
                        }.onFailure {
                            _state.update { it.copy(error = "中断回复写入或刷新失败，请重新进入对话核对") }
                        }
                    }
                }
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
        RetainedChatSessions.retainGeneration(sessionId, job, appContext) {
            _state.value.error ?: _state.value.imageRetryNotice?.message
        }
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

    private fun rejectPendingWorldWrite(): Boolean {
        if (!_state.value.worldSettingSaving && !_state.value.worldCredentialsSaving) return false
        _state.update { it.copy(error = "本场设置正在保存，请稍候再发送") }
        return true
    }

    private fun launchBranchTransition(
        onSuccess: (() -> Unit)? = null,
        navigationLabel: String? = null,
        invalidateSpeech: Boolean = false,
        block: suspend () -> Unit,
    ): Boolean {
        if (rejectDuringMediaBundle()) return false
        if (activeGeneration != null || branchTransitionJob?.isActive == true) return false
        if (_state.value.replyRecovery != null) {
            _state.update { it.copy(error = "请先保留、复制或丢弃上次中断的回复") }
            return false
        }
        if (invalidateSpeech) stopSpeaking()
        cancelPendingAutoNarrator()
        clearImageRetry()
        // 故事线或原文可能改变，取消本轮整理后由提交时的来源校验保护正式摘要。
        manualCompactionJob?.cancel()
        historyLoadJob?.cancel()
        historyLoadJob = null
        messageWindowRevision.incrementAndGet()
        _state.update { it.copy(isLoadingHistory = false, branchNavigationLabel = navigationLabel) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            var completed = false
            try {
                block()
                completed = true
            } finally {
                if (branchTransitionJob === owner) {
                    branchTransitionJob = null
                    if (navigationLabel != null) _state.update { it.copy(branchNavigationLabel = null) }
                }
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
    private fun resolveMainChatModelId(character: CharacterEntity, connection: com.mojing.app.domain.config.ChatConnection, platform: com.mojing.app.data.ModelPlatform? = requestPlatform()): String {
        platform?.let { return it.selectedModel }
        return connection.model(character.modelName, secureStorage.publicModel)
    }

    /**
     * 对话请求使用的模型 id。
     * - 未开思考/Max：主对话模型（角色 → 公共）。
     * - 开启思考/Max：仍用主模型，除非用户在「思考模型覆盖」或设置里填了可选覆盖；**不会**在客户端把模型名改成其它 id。
     *   若当前模型不支持思考/Max，由接口拒绝，再通过 [streamErrorThinkMaxRoute] 提示。
     */
    private fun resolveChatLlmModel(character: CharacterEntity, sessionThinkMax: Boolean, connection: com.mojing.app.domain.config.ChatConnection, platform: com.mojing.app.data.ModelPlatform? = requestPlatform()): String? {
        platform?.let { return it.selectedModel }
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
                "primary image line failed, retry public: type=${result.exceptionOrNull()?.javaClass?.simpleName}",
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

    private data class AutoImageAttemptLease(
        val messageId: Long,
        val branchId: String,
        val attemptToken: String,
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
                generation.ensureCurrent()
                val attemptToken = UUID.randomUUID().toString()
                runAutoImageAttempt(
                    generation = generation,
                    prompt = prompt,
                    characterId = character.id,
                    characterOverride = character,
                    worldOverride = w,
                    attemptToken = attemptToken,
                ) {
                    messageDao.insert(MessageEntity(
                        sessionId = sessionId,
                        speakerType = "character",
                        characterId = character.id,
                        content = "🖼 配图生成中…",
                        structuredContentJson = AutoImageMetadata.create(
                            prompt, AutoImageMetadata.STATE_RUNNING, attemptToken,
                        ),
                        branchId = generation.branchId,
                        parentMessageId = sourceReplyMessageId,
                        includeInContext = false,
                    )).takeIf { it > 0L }
                }
            }
        }
        if (w.autoCharacterSpeech && parsed.speechTexts.isNotEmpty()) {
            for (text in parsed.speechTexts.take(2)) {
                generation.ensureCurrent()
                val token = UUID.randomUUID().toString()
                runAutoVoiceAttempt(generation, text, character.id, token, character) {
                    messageDao.insert(MessageEntity(
                        sessionId = sessionId, speakerType = "character", characterId = character.id,
                        content = "配音生成中…",
                        structuredContentJson = AutoVoiceMetadata.create(text, AutoVoiceMetadata.STATE_RUNNING, token),
                        branchId = generation.branchId, parentMessageId = sourceReplyMessageId, includeInContext = false,
                    )).takeIf { it > 0L }
                }
            }
        }
    }

    private suspend fun runAutoVoiceAttempt(
        generation: GenerationContext,
        text: String,
        characterId: Long,
        token: String,
        characterOverride: CharacterEntity? = null,
        acquire: suspend () -> Long?,
    ) {
        var messageId: Long? = null
        var files = emptyList<com.mojing.app.media.SynthesizedSpeechFile>()
        try {
            val id = withContext(NonCancellable) { acquire()?.also { messageId = it } } ?: return
            refreshMessagesUi(generation.branchId)
            generation.ensureCurrent()
            val character = requireNotNull(characterOverride ?: characterDao.getById(characterId)) { "配音角色已不存在" }
            val preferences = com.mojing.app.data.VoicePreferences(appContext)
            val (choice, region, key) = withContext(Dispatchers.IO) {
                val selected = preferences.sessionSelection(sessionId)
                val fallback = selected.takeUnless { it.engineId == "inherit" } ?: preferences.global()
                val resolved = com.mojing.app.data.resolveVoiceChoice(character.voiceProvider, character.voiceModel, fallback)
                Triple(resolved, if (resolved.engineId == "azure") preferences.azureRegion else "", if (resolved.engineId == "azure") preferences.azureKey else "")
            }
            generation.ensureCurrent()
            val directory = withContext(Dispatchers.IO) {
                java.io.File(appContext.filesDir, "attachments/$sessionId").canonicalFile.also {
                    require(it.isDirectory || it.mkdirs()) { "配音目录不可用" }
                }
            }
            generation.ensureCurrent()
            files = if (choice.engineId == "azure") {
                com.mojing.app.media.AzureSpeech.synthesizeToFiles(text, region, key, choice.voiceId, directory, token)
            } else AndroidTts.synthesizeToFiles(appContext, text, choice, directory, token)
            generation.ensureCurrent()
            require(files.isNotEmpty()) { "配音没有生成音频" }
            val attachments = withContext(Dispatchers.IO) {
                files.map { part ->
                    require(part.file.isFile && part.file.length() > 0L && part.file.canonicalFile.parentFile == directory && part.mimeType.startsWith("audio/")) { "配音文件无效" }
                    MessageAttachmentEntity(messageId = id, assetType = "voice", fileName = part.file.name,
                        mimeType = part.mimeType, storagePath = part.file.absolutePath,
                        generationPrompt = text, generationModel = choice.engineId + ":" + choice.voiceId)
                }
            }
            generation.ensureCurrent()
            val committed = withContext(NonCancellable) {
                messageDao.completeAutoVoiceGeneration(id, sessionId, generation.branchId, token, attachments).also {
                    if (it) files = emptyList()
                }
            }
            if (!committed) throw IllegalStateException("配音消息已改变")
            refreshMessagesUi(generation.branchId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                messageId?.let { runCatching { messageDao.failAutoVoiceGeneration(it, sessionId, generation.branchId, token, interrupted = true) } }
                withContext(Dispatchers.IO) { files.forEach { it.file.delete() } }
                if (currentBranchId() == generation.branchId) runCatching { refreshMessagesUi(generation.branchId) }
            }
            throw cancelled
        } catch (error: Exception) {
            UsbSessionLog.e("ChatVoiceGen", "auto voice failed sid=$sessionId type=${error.javaClass.simpleName}")
            withContext(NonCancellable) {
                messageId?.let { runCatching { messageDao.failAutoVoiceGeneration(it, sessionId, generation.branchId, token, interrupted = false) } }
                withContext(Dispatchers.IO) { files.forEach { it.file.delete() } }
                if (currentBranchId() == generation.branchId) runCatching { refreshMessagesUi(generation.branchId) }
            }
        }
    }

    fun retryAutoCharacterVoice(messageId: Long): Boolean {
        val message = _state.value.messages.firstOrNull { it.id == messageId } ?: return false
        if (message.branchId != currentBranchId() || message.speakerType != "character" || message.includeInContext || message.parentMessageId == null) return false
        val metadata = AutoVoiceMetadata.parse(message.structuredContentJson)?.takeIf { it.retryable } ?: return false
        val characterId = message.characterId ?: return false
        val text = metadata.text ?: return false
        val expectedToken = metadata.attemptToken ?: return false
        return launchSingleGeneration { generation ->
            val token = UUID.randomUUID().toString()
            runAutoVoiceAttempt(generation, text, characterId, token) {
                messageDao.claimAutoVoiceGeneration(messageId, sessionId, generation.branchId, expectedToken, token)
                    .let { if (it) messageId else null }
            }
        }
    }

    private suspend fun runAutoImageAttempt(
        generation: GenerationContext,
        prompt: String,
        characterId: Long,
        characterOverride: CharacterEntity? = null,
        worldOverride: SessionWorldEntity? = null,
        attemptToken: String,
        acquire: suspend () -> Long?,
    ) {
        var lease: AutoImageAttemptLease? = null
        var generatedPath: String? = null
        try {
            val messageId = withContext(NonCancellable) {
                acquire()?.also {
                    lease = AutoImageAttemptLease(it, generation.branchId, attemptToken)
                }
            } ?: return
            refreshMessagesUi(generation.branchId)
            generation.ensureCurrent()
            val character = characterOverride ?: characterDao.getById(characterId)
            val world = worldOverride ?: sessionWorldDao.getBySession(sessionId)
            requireNotNull(character) { "角色不存在，无法生成配图" }
            requireNotNull(world) { "故事线配置不存在，无法生成配图" }
            val primary = ApiKeyResolver.resolveImageGenPrimaryResolved(character, world, secureStorage)
            require(primary.apiKey.isNotBlank()) { "图片服务未配置密钥" }
            val attempt = generateImageWithPublicFallback(prompt, character, world, character.id)
            generation.ensureCurrent()
            if (currentBranchId() != generation.branchId) return
            val urlOrB64 = attempt.result.getOrElse { throw it }
            generatedPath = imageRepository.saveGeneratedImageForSession(urlOrB64, sessionId)
            val local = requireNotNull(generatedPath) { "配图保存失败" }
            generation.ensureCurrent()
            if (currentBranchId() != generation.branchId) {
                java.io.File(local).delete()
                generatedPath = null
                return
            }
            val committed = withContext(NonCancellable) {
                val result = messageDao.completeAutoImageGeneration(
                    messageId, sessionId, generation.branchId, attemptToken,
                    MessageAttachmentEntity(
                        messageId = messageId,
                        assetType = "image",
                        fileName = java.io.File(local).name,
                        mimeType = "image/png",
                        storagePath = local,
                        generationPrompt = prompt,
                        generationModel = attempt.modelUsed,
                    ),
                )
                if (result) generatedPath = null
                result
            }
            if (!committed) java.io.File(local).delete()
            refreshMessagesUi(generation.branchId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                lease?.takeIf { currentBranchId() == it.branchId }?.let {
                    runCatching {
                        messageDao.failAutoImageGeneration(it.messageId, sessionId, it.branchId, it.attemptToken, interrupted = true)
                    }
                }
                generatedPath?.let { java.io.File(it).delete() }
                if (currentBranchId() == generation.branchId) runCatching { refreshMessagesUi(generation.branchId) }
            }
            throw cancelled
        } catch (error: Exception) {
            UsbSessionLog.e("ChatImageGen", "auto char image exception sid=$sessionId type=${error.javaClass.simpleName}")
            withContext(NonCancellable) {
                lease?.takeIf { currentBranchId() == it.branchId }?.let {
                    runCatching {
                        messageDao.failAutoImageGeneration(it.messageId, sessionId, it.branchId, it.attemptToken, interrupted = false)
                    }
                    runCatching { refreshMessagesUi(it.branchId) }
                }
                generatedPath?.let { java.io.File(it).delete() }
            }
        }
    }

    fun retryAutoCharacterImage(messageId: Long): Boolean {
        val message = _state.value.messages.firstOrNull { it.id == messageId } ?: return false
        if (message.branchId != currentBranchId()) return false
        val metadata = AutoImageMetadata.parse(message.structuredContentJson) ?: return false
        if (!metadata.retryable || message.speakerType != "character" || message.includeInContext || message.parentMessageId == null) return false
        val prompt = metadata.prompt ?: run {
            _state.update { it.copy(error = "这条配图缺少原始提示，无法精确重试") }
            return false
        }
        val characterId = message.characterId ?: return false
        val expectedToken = metadata.attemptToken ?: return false
        return launchSingleGeneration { generation ->
            if (generation.branchId != message.branchId) return@launchSingleGeneration
            val freshToken = UUID.randomUUID().toString()
            runAutoImageAttempt(
                generation = generation,
                prompt = prompt,
                characterId = characterId,
                attemptToken = freshToken,
                acquire = { messageDao.claimAutoImageGeneration(
                    messageId, sessionId, generation.branchId, expectedToken, freshToken,
                ).let { if (it) messageId else null } },
            )
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
        tokenEstimateJob?.cancel()
        tokenEstimateRevision.incrementAndGet()
        eventPanelRequestedBranchId = null
        eventRefreshRevision.incrementAndGet()
        correctionRefreshRevision.incrementAndGet()
        memorySummaryListRevision.incrementAndGet()
        contextMemoryDisplayRevision.incrementAndGet()
        encyclopediaFoundationRevision.incrementAndGet()
        bookmarkRefreshRevision.incrementAndGet()
        ++bookmarkReadOnlyRevision
        bookmarkReadOnlyJob?.cancel()
        bookmarkReadOnlyJob = null
        _state.update { it.copy(bookmarkReadOnlyMessage = null, bookmarkReadOnlyLoading = it.bookmarkReadOnlyId != null) }
        bookmarkInitialLoadJob?.cancel()
        bookmarkInitialLoadJob = null
        _state.update {
            it.copy(
                isReady = false,
                initialLoadError = null,
                sessionNotFound = false,
                conversationTokenEstimate = null,
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
        val restoredQuoteCandidate = if (draftSubmissionCommitted) null else restoredDraft.quotedMessageId?.let {
            messageDao.getByIdInSession(it, sessionId)
        }
        val restoredQuoteSnippet = restoredQuoteCandidate?.takeIf { quoteDraftRevision == 0L }?.let { quote ->
            withContext(preparationDispatcher) {
                ChatMessageTextFormat.quoteSnippet(quote.content, 120, quote.speakerType)
            }
        }
        val restoredQuote = restoredQuoteCandidate?.takeIf { restoredQuoteSnippet?.isNotBlank() == true }
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
        // Do not treat a branch with stale derived visibility segments as an empty story.
        // This also protects the reply-recovery and model-context reads after initialization.
        if (branches.isNotEmpty()) branchVisibilityIndexManager.ensureReady()
        val rememberedBranchId = runCatching {
            uiPreferencesRepository.getLastChatBranch(sessionId)
        }.getOrDefault("main")
        val openInitialSource = sourceMessageId > 0L &&
            savedStateHandle.get<Boolean>("history_navigation_consumed_$sessionId") != true
        if (openInitialSource && sourceBranchId.isNotBlank() && sourceBranchId != "main" && branches.none { it.branchId == sourceBranchId }) {
            _state.update { it.copy(isReady = false, initialLoadError = "来源故事线已不存在，请返回百科查看保留的资料。") }
            return
        }
        val requestedBranchId = if (openInitialSource && sourceBranchId.isNotBlank()) sourceBranchId
            else if (sourceMessageId > 0L) savedStateHandle.get<String>("history_navigation_branch_$sessionId") ?: rememberedBranchId
            else rememberedBranchId
        val initialBranchId = requestedBranchId.takeIf { branchId ->
            branchId == "main" || branches.any { it.branchId == branchId }
        } ?: "main"
        // Restore only after the actual initial line is known; startup temporarily reports main.
        val restoreEventCriteria = savedStateHandle.get<String>("event_criteria_branch_$sessionId") == initialBranchId
        val restoredEventQuery = if (restoreEventCriteria)
            savedStateHandle.get<String>("event_query_$sessionId").orEmpty().take(200) else ""
        val restoredEventFilter = if (restoreEventCriteria)
            savedStateHandle.get<Boolean>("event_resolved_$sessionId") else null
        val savedEventWindowSize = savedStateHandle.get<Int>("event_window_size_$sessionId")
        val savedEventBeforeAt = savedStateHandle.get<Long>("event_window_before_at_$sessionId")
        val savedEventBeforeId = savedStateHandle.get<Long>("event_window_before_id_$sessionId")
        val restoreEventWindow = restoreEventCriteria &&
            savedEventWindowSize in listOf(EVENT_NODE_PAGE_SIZE, EVENT_NODE_PAGE_SIZE * 2, EVENT_NODE_WINDOW_SIZE) &&
            ((savedEventBeforeAt == null && savedEventBeforeId == null) ||
                (savedEventBeforeAt != null && savedEventBeforeId != null && savedEventBeforeId > 0L))
        val summaryWindowSize = savedStateHandle.get<Int>("summary_window_size_$sessionId")
        val summaryBeforeEnd = savedStateHandle.get<Long>("summary_window_before_end_$sessionId")
        val summaryBeforeId = savedStateHandle.get<Long>("summary_window_before_id_$sessionId")
        val restoreSummaryWindow = savedStateHandle.get<String>("summary_window_branch_$sessionId") == initialBranchId &&
            summaryWindowSize in (MEMORY_SEGMENT_PAGE_SIZE..MEMORY_SEGMENT_WINDOW_SIZE step MEMORY_SEGMENT_PAGE_SIZE) &&
            ((summaryBeforeEnd == null && summaryBeforeId == null) ||
                (summaryBeforeEnd != null && summaryBeforeEnd >= 0L && summaryBeforeId != null && summaryBeforeId > 0L))
        // Bookmarks belong to the whole session, including messages from other lines.
        val bookmarkWindowSize = savedStateHandle.get<Int>("bookmark_window_size_$sessionId")
        val bookmarkBeforeAt = savedStateHandle.get<Long>("bookmark_window_before_at_$sessionId")
        val bookmarkBeforeId = savedStateHandle.get<Long>("bookmark_window_before_id_$sessionId")
        val restoreBookmarkWindow = savedStateHandle.get<String>("bookmark_window_query_$sessionId") == _state.value.bookmarkQuery &&
            bookmarkWindowSize in (BOOKMARK_PAGE_SIZE..BOOKMARK_WINDOW_SIZE step BOOKMARK_PAGE_SIZE) &&
            ((bookmarkBeforeAt == null && bookmarkBeforeId == null) ||
                (bookmarkBeforeAt != null && bookmarkBeforeAt >= 0L && bookmarkBeforeId != null && bookmarkBeforeId > 0L))
        val invalidRememberedBranch = sourceMessageId <= 0L && rememberedBranchId != "main" && initialBranchId == "main"
        if (invalidRememberedBranch) {
            runCatching { uiPreferencesRepository.clearLastChatBranch(sessionId) }
        }
        val savedHistoryEnd = savedStateHandle.get<Long>("history_window_end_$sessionId")
        val savedHistorySize = savedStateHandle.get<Int>("history_window_size_$sessionId")
        val savedHistoryAnchor = savedStateHandle.get<Long>("history_window_anchor_$sessionId")
        val anchor = savedHistoryAnchor?.let { getVisibleMessage(initialBranchId, it) }
        val historyEnd = savedHistoryEnd?.takeIf { it in 1 until Long.MAX_VALUE }
            ?.let { getVisibleMessage(initialBranchId, it) }
        val restoreHistory = savedStateHandle.get<String>("history_window_branch_$sessionId") == initialBranchId &&
            savedHistorySize != null && savedHistorySize in 1..MAX_MESSAGE_WINDOW_SIZE &&
            savedHistoryEnd != null && savedHistoryEnd in 1 until Long.MAX_VALUE &&
            historyEnd?.sessionId == sessionId &&
            (savedHistoryAnchor == null || (anchor != null && anchor.sessionId == sessionId && !isInactiveBookmarkedVariant(initialBranchId, anchor)))
        if (!restoreHistory) clearHistoryWindowIntent()
        val initialCapacity = if (restoreHistory) savedHistorySize!! else INITIAL_MESSAGE_WINDOW_SIZE
        val initialRows = if (restoreHistory) getMessagesBefore(initialBranchId, savedHistoryEnd!! + 1, initialCapacity + 1)
            else getMessageTailForBranch(initialBranchId, initialCapacity + 1)
        val hasOlderMessages = initialRows.size > initialCapacity
        val restoredHasNewer = restoreHistory && getMessagesAfter(initialBranchId, savedHistoryEnd!!, 1).isNotEmpty()
        val rawInitialMsgs = initialRows.take(initialCapacity).asReversed()
        val msgs = recoverAutoImageMessages(rawInitialMsgs, initialBranchId)
        val excludedKeys = excludedKeysForWindow(initialBranchId, msgs)
        val maps = buildCharacterPresentationMaps(participants, msgs)

        val displayCap = session.displayContextTokenLimit.takeIf { it > 0 } ?: 1_000_000
        val attMap = attachmentsForMessages(msgs)
        val displayLines = visibleDisplayLines(msgs, attMap)
        val bookmarkIds = bookmarkedIdsForWindow(msgs)
        val roundChoices = withContext(preparationDispatcher) {
            buildRoundChoiceSnapshot(world, msgs.filterNot { it.contextSelectionKey() in excludedKeys })
        }
        val branchAnchors = anchorsForBranches(branches)

        _state.value = _state.value.copy(
            sessionTitle = session.title,
            messages = msgs,
            displayLines = displayLines,
            hasOlderMessages = hasOlderMessages,
            hasNewerMessages = restoredHasNewer,
            historyWindowRestored = restoreHistory,
            focusedMessageId = if (restoreHistory) savedStateHandle.get<Long>("history_window_focus_$sessionId")
                ?.takeIf { id -> msgs.any { it.id == id } } else null,
            isLoadingHistory = false,
            messageAttachments = attMap,
            participants = participants, world = world,
            encyclopediaFoundation = "",
            encyclopediaFoundationLoaded = world?.encyclopediaId == null,
            encyclopediaFoundationLoading = false,
            encyclopediaFoundationLoadError = null,
            contextMemoryText = "",
            contextMemoryLoaded = false,
            contextMemoryLoading = false,
            contextMemoryLoadError = null,
            memorySegments = emptyList(),
            memorySegmentsWindowSize = if (restoreSummaryWindow) summaryWindowSize!! else MEMORY_SEGMENT_PAGE_SIZE,
            memorySegmentsBeforeEndId = if (restoreSummaryWindow) summaryBeforeEnd else null,
            memorySegmentsBeforeId = if (restoreSummaryWindow) summaryBeforeId else null,
            memorySegmentsLoaded = false,
            memorySegmentsLoading = false,
            memorySegmentsHasMore = false,
            memorySegmentsLoadingMore = false,
            memorySegmentsLoadError = null,
            eventNodes = emptyList(),
            eventQuery = restoredEventQuery,
            eventResolvedFilter = restoredEventFilter,
            eventNodesBeforeCreatedAt = if (restoreEventWindow) savedEventBeforeAt else null,
            eventNodesBeforeId = if (restoreEventWindow) savedEventBeforeId else null,
            eventNodesLoaded = false,
            eventNodesWindowSize = if (restoreEventWindow) savedEventWindowSize!! else EVENT_NODE_PAGE_SIZE,
            eventNodesHasMore = false,
            eventNodesLoadingMore = false,
            eventNodesRefreshFailed = false, eventNodesLoadError = null,
            branches = branches,
            branchAnchorsByMessageId = branchAnchors,
            memoryCorrections = emptyList(),
            memoryCorrectionsLoaded = false,
            memoryCorrectionsLoading = false,
            memoryCorrectionsHasMore = false,
            memoryCorrectionsWindowSize = MEMORY_CORRECTION_PAGE_SIZE,
            memoryCorrectionsLoadError = null,
            currentBranchId = initialBranchId,
            roundChoiceOptions = roundChoices.options,
            roundChoiceMessageId = roundChoices.sourceMessageId,
            branchSourcePreviews = emptyMap(),
            branchSourcePreviewsLoading = false,
            branchSourcePreviewsError = null,
            characterNames = maps.names,
            characterAvatars = maps.avatars,
                characterSummaries = maps.summaries,
            characterCardImages = maps.cardImages,
            characterColors = maps.colors,
            bookmarks = emptyList(),
            bookmarksWindowSize = if (restoreBookmarkWindow) bookmarkWindowSize!! else BOOKMARK_PAGE_SIZE,
            bookmarksBeforeCreatedAt = if (restoreBookmarkWindow) bookmarkBeforeAt else null,
            bookmarksBeforeId = if (restoreBookmarkWindow) bookmarkBeforeId else null,
            bookmarksLoaded = false,
            bookmarksHasMore = false,
            bookmarksLoadingMore = false,
            bookmarksRefreshFailed = false, bookmarksLoadError = null,
            bookmarkedMessageIds = bookmarkIds,
            excludedContextKeys = excludedKeys,
            bookmarkPreviews = emptyMap(),
            userDisplayName = secureStorage.userName,
            userAvatarImagePath = secureStorage.userAvatarImagePath,
            userAvatarColor = secureStorage.userAvatarColor,
            allowSessionThinkMax = secureStorage.allowSessionThinkMax,
            sessionThinkMaxEnabled = session.thinkMaxEnabled,
            characterForcesThinkMax = participants.firstOrNull()?.characterId?.let {
                it in maps.thinkMaxEnabledIds
            } == true,
            displayContextTokenLimit = displayCap,
            conversationTokenEstimate = null,
            inputText = _state.value.inputText.ifEmpty { restoredInputText },
            quotingMessage = if (quoteDraftRevision == 0L) restoredQuote else _state.value.quotingMessage,
            quotingSnippet = if (quoteDraftRevision == 0L) restoredQuoteSnippet?.takeIf { restoredQuote != null }
                else _state.value.quotingSnippet,
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
            isReady = false,
            initialLoadError = null,
        )
        reconcileReplyRecovery()
        if (openInitialSource) {
            val located = loadMessageWindow(initialBranchId, sourceMessageId)
            _state.update { it.copy(isReady = located,
                initialLoadError = if (located) null else "来源消息已删除或不在来源故事线，请返回百科。") }
        } else {
            _state.update { it.copy(isReady = true) }
            scheduleConversationTokenEstimate(msgs, excludedKeys, initialBranchId, messageWindowRevision.get())
        }
        if (_state.value.isReady) {
            if (sourceMessageId > 0L) {
                savedStateHandle["history_navigation_branch_$sessionId"] = currentBranchId()
                savedStateHandle["history_navigation_consumed_$sessionId"] = true
            }
            retryBookmarkedReadOnlyMessage()
        }
    }

    private fun scheduleConversationTokenEstimate(
        messages: List<MessageEntity>,
        excludedKeys: Set<String>,
        branchId: String,
        windowRevision: Long,
    ) {
        tokenEstimateJob?.cancel()
        val estimateRevision = tokenEstimateRevision.incrementAndGet()
        tokenEstimateJob = viewModelScope.launch(tokenEstimateDispatcher) {
            val estimate = try {
                estimateLoadedContextTokens(messages, excludedKeys)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                UsbSessionLog.w("ChatTokenEstimate", "window estimate failed type=${failure.javaClass.simpleName}")
                return@launch
            }
            currentCoroutineContext().ensureActive()
            _state.update { current ->
                if (tokenEstimateRevision.get() == estimateRevision &&
                    messageWindowRevision.get() == windowRevision &&
                    current.currentBranchId == branchId && current.messages == messages
                ) current.copy(conversationTokenEstimate = estimate) else current
            }
        }
    }

    private suspend fun buildCharacterPresentationMaps(
        participants: List<SessionParticipantEntity>,
        messages: List<MessageEntity> = _state.value.messages,
    ): CharacterUiMaps {
        val names = mutableMapOf<Long, String>()
        val avatars = mutableMapOf<Long, String>()
        val colors = mutableMapOf<Long, String>()
        val cardImages = mutableMapOf<Long, String>()
        // Removing a speaker from future rounds must not erase their historical identity.
        // Only the bounded message window contributes historical authors.
        val displayIds = (participants.map { it.characterId } + messages.mapNotNull { it.characterId })
            .filter { it > 0L }.distinct()
        val rows = displayIds.chunked(500).flatMap { ids ->
            characterDao.getChatPresentationByIds(ids)
        }
        val rowsById = rows.associateBy { it.id }
        displayIds.forEach { id ->
            val row = rowsById[id] ?: return@forEach
            names[row.id] = row.name
            avatars[row.id] = row.avatarImagePath
            colors[row.id] = row.avatarColor
            val card = row.cardImagePath.trim()
            if (card.isNotEmpty()) cardImages[row.id] = card
        }
        return CharacterUiMaps(names, avatars, colors, cardImages,
            rows.asSequence().filter { it.thinkMaxEnabled }.map { it.id }.toSet(),
            rows.associate { it.id to it.personaPreview })
    }

    private suspend fun attachmentsForMessages(
        messages: List<MessageEntity>,
    ): Map<Long, List<MessageAttachmentEntity>> {
        if (messages.isEmpty()) return emptyMap()
        return attachmentDao.getByMessages(messages.map { it.id }).groupBy { it.messageId }
    }

    private suspend fun branchSourcePreviews(
        branches: List<SessionBranchEntity>,
    ): Map<Long, String> {
        val ids = branches.map { it.sourceMessageId }.filter { it > 0L }.toSet()
        val previews = mutableMapOf<Long, String>()
        for (batch in ids.chunked(32)) {
            currentCoroutineContext().ensureActive()
            val sources = messageDao.getMessagePreviewPrefixesInSession(sessionId, batch)
            previews.putAll(withContext(preparationDispatcher) {
                sources.associate { message ->
                    currentCoroutineContext().ensureActive()
                    message.id to ChatMessageTextFormat.sessionListPreview(
                        message.content, message.speakerType, 56,
                    ).ifBlank { "（无可见摘要）" }
                }
            })
        }
        return previews
    }

    private var branchSourcePreviewJob: Job? = null

    fun loadBranchSourcePreviews() {
        branchSourcePreviewJob?.cancel()
        branchSourcePreviewJob = null
        val branches = _state.value.branches
        if (branches.none { it.sourceMessageId > 0L }) {
            _state.update { it.copy(branchSourcePreviews = emptyMap(), branchSourcePreviewsLoading = false,
                branchSourcePreviewsError = null) }
            return
        }
        _state.update { it.copy(branchSourcePreviews = emptyMap(), branchSourcePreviewsLoading = true,
            branchSourcePreviewsError = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            try {
                val previews = branchSourcePreviews(branches)
                if (branchSourcePreviewJob === owner) {
                    _state.update { current ->
                        if (current.branches != branches) current.copy(branchSourcePreviewsError = "故事线已变化，请重试加载摘要")
                        else current.copy(branchSourcePreviews = previews, branchSourcePreviewsError = null)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (branchSourcePreviewJob === owner) {
                    _state.update { it.copy(branchSourcePreviewsError = "来源片段加载失败，可重试；仍可按名称选择故事线") }
                }
            } finally {
                if (branchSourcePreviewJob === owner) {
                    branchSourcePreviewJob = null
                    _state.update { it.copy(branchSourcePreviewsLoading = false) }
                }
            }
        }
        branchSourcePreviewJob = job
        job.start()
    }

    fun cancelBranchSourcePreviews() {
        branchSourcePreviewJob?.cancel()
        branchSourcePreviewJob = null
        _state.update { it.copy(branchSourcePreviewsLoading = false) }
    }

    private suspend fun messagePreviews(
        messageIds: Set<Long>,
        maxChars: Int,
    ): Map<Long, String> {
        if (messageIds.isEmpty()) return emptyMap()
        val previews = mutableMapOf<Long, String>()
        for (batch in messageIds.chunked(32)) {
            val sources = messageDao.getMessagePreviewPrefixesInSession(sessionId, batch)
            if (sources.isEmpty()) continue
            previews.putAll(withContext(preparationDispatcher) {
                sources.associate { message ->
                    currentCoroutineContext().ensureActive()
                    message.id to ChatMessageTextFormat.sessionListPreview(
                        message.content, message.speakerType, maxChars,
                    ).ifBlank { "（暂无摘要，可打开原文）" }
                }
            })
        }
        return previews
    }

    private suspend fun checkpointReplyRecovery(
        generation: GenerationContext,
        text: String,
        speakerType: String,
        characterId: Long?,
        force: Boolean = false,
    ) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        val previous = generation.replyRecovery
        if (!force && previous != null && now - generation.lastRecoveryCheckpointAt < 1_500L &&
            text.length - generation.lastRecoveryCheckpointLength < 256
        ) return
        val snapshot = if (previous == null) {
            ReplyRecoverySnapshot(
                token = UUID.randomUUID().toString(), sessionId = sessionId, branchId = generation.branchId,
                speakerType = speakerType, characterId = characterId,
                anchorMessageId = getMessageTailForBranch(generation.branchId, 1).lastOrNull()?.id,
                swipeGroupId = generation.swipeGroupId,
                swipeSourceMessageId = generation.swipeSourceMessageId,
                rawText = text, startedAt = now, updatedAt = now,
            )
        } else previous.copy(rawText = text, updatedAt = now.coerceAtLeast(previous.startedAt))
        val saved = if (previous == null) chatDraftStore.saveReplyRecovery(snapshot)
            else chatDraftStore.checkpointReplyRecovery(snapshot)
        if (saved) {
            generation.replyRecovery = snapshot
            generation.lastRecoveryCheckpointAt = now
            generation.lastRecoveryCheckpointLength = text.length
        } else {
            _state.update { it.copy(error = "中断回复暂时无法保存恢复记录，请检查设备存储空间") }
        }
    }

    private suspend fun clearCommittedReplyRecovery(generation: GenerationContext) {
        val snapshot = generation.replyRecovery ?: return
        if (chatDraftStore.clearReplyRecovery(sessionId, snapshot.token)) {
            generation.replyRecovery = null
        } else {
            reconcileReplyRecovery()
        }
    }

    private suspend fun replyRecoveryIssue(snapshot: ReplyRecoverySnapshot): String? {
        if (snapshot.sessionId != sessionId) return "记录不属于当前对话"
        val branches = sessionBranchDao.getBySession(sessionId)
        if (snapshot.branchId != "main" && branches.none { it.branchId == snapshot.branchId }) return "原故事线已不存在"
        if (getMessageTailForBranch(snapshot.branchId, 1).lastOrNull()?.id != snapshot.anchorMessageId) {
            return "原故事线已有新消息或末尾已变化"
        }
        if (snapshot.speakerType == "character" && characterDao.getById(snapshot.characterId!!) == null) {
            return "原角色已不存在"
        }
        if ((snapshot.swipeGroupId == null) != (snapshot.swipeSourceMessageId == null)) {
            return "回复版本来源不完整"
        }
        snapshot.swipeSourceMessageId?.let { sourceId ->
            if (sourceId != snapshot.anchorMessageId || getVisibleMessage(snapshot.branchId, sourceId) == null) {
                return "原回复版本已变化"
            }
        }
        return null
    }

    private fun showReplyRecovery(snapshot: ReplyRecoverySnapshot, issue: String?) {
        pendingReplyRecovery = snapshot
        unreadableReplyRecovery = null
        val branchLabel = _state.value.branches.firstOrNull { it.branchId == snapshot.branchId }?.label
            ?: if (snapshot.branchId == "main") "主线" else snapshot.branchId
        _state.update { it.copy(
            replyRecovery = ReplyRecoveryNotice(
                token = snapshot.token,
                speakerLabel = if (snapshot.speakerType == "narrator") "旁白" else
                    it.characterNames[snapshot.characterId] ?: "角色",
                text = snapshot.rawText, branchLabel = branchLabel, issue = issue,
            ),
            replyRecoveryError = null,
        ) }
    }

    private suspend fun reconcileReplyRecovery() {
        when (val record = chatDraftStore.loadReplyRecovery(sessionId)) {
            is ReplyRecoveryLoadResult.Valid -> {
                val snapshot = record.snapshot
                if (snapshot.sessionId != sessionId) {
                    showReplyRecovery(snapshot, "记录不属于当前对话")
                    return
                }
                val committedId = messageDao.findReplyRecoveryMessageId(sessionId, snapshot.branchId, snapshot.token)
                if (committedId != null) {
                    val cleared = chatDraftStore.clearReplyRecovery(sessionId, snapshot.token)
                    if (!cleared) showReplyRecovery(snapshot, "回复已保存，但恢复记录清理失败")
                    else {
                        pendingReplyRecovery = null
                        _state.update { it.copy(replyRecovery = null, replyRecoveryError = null) }
                    }
                } else showReplyRecovery(snapshot, replyRecoveryIssue(snapshot))
            }
            is ReplyRecoveryLoadResult.Unreadable -> {
                pendingReplyRecovery = null
                unreadableReplyRecovery = record.raw
                _state.update { it.copy(replyRecovery = ReplyRecoveryNotice(
                    speakerLabel = "回复恢复记录", issue = "记录无法读取，可丢弃后继续对话",
                ), replyRecoveryError = null) }
            }
            else -> {
                pendingReplyRecovery = null
                unreadableReplyRecovery = null
                _state.update { it.copy(replyRecovery = null, replyRecoveryError = null) }
            }
        }
    }

    fun keepRecoveredReply() {
        val snapshot = pendingReplyRecovery ?: return
        if (_state.value.replyRecoveryBusy || activeGeneration != null) return
        _state.update { it.copy(replyRecoveryBusy = true, replyRecoveryError = null) }
        viewModelScope.launch {
            try {
                val existing = messageDao.findReplyRecoveryMessageId(sessionId, snapshot.branchId, snapshot.token)
                if (existing == null) {
                    val issue = replyRecoveryIssue(snapshot)
                    if (issue != null) {
                        showReplyRecovery(snapshot, issue)
                        return@launch
                    }
                    var content = com.mojing.app.domain.engine.InterruptedReply.normalize(snapshot.rawText)
                    val world = _state.value.world
                    if (snapshot.speakerType == "narrator" && world?.gameplayMode == "小说创作") {
                        content = StoryCanon.sanitizeMessageChoices(content, world.worldPrompt)
                    }
                    if (content.isBlank()) {
                        showReplyRecovery(snapshot, "没有可写入的正文，请复制或丢弃记录")
                        return@launch
                    }
                    messageDao.insertReplyRecoveryIfAbsent(
                        MessageEntity(sessionId = sessionId, branchId = snapshot.branchId,
                            speakerType = snapshot.speakerType, characterId = snapshot.characterId,
                            content = content, structuredContentJson = structuredContentJsonFor(content),
                            swipeGroupId = snapshot.swipeGroupId, includeInContext = true),
                        snapshot.token, snapshot.swipeSourceMessageId,
                    )
                }
                reconcileReplyRecovery()
                if (currentBranchId() == snapshot.branchId) refreshMessagesUi(snapshot.branchId)
            } catch (_: Exception) {
                _state.update {
                    if (it.replyRecovery == null) it.copy(error = "回复已保存，但列表刷新失败，请重新进入对话")
                    else it.copy(replyRecoveryError = "保留回复失败，请重试或复制正文")
                }
            } finally {
                _state.update { it.copy(replyRecoveryBusy = false) }
            }
        }
    }

    fun discardRecoveredReply() {
        val snapshot = pendingReplyRecovery
        val unreadable = unreadableReplyRecovery
        if (_state.value.replyRecoveryBusy || activeGeneration != null || (snapshot == null && unreadable == null)) return
        _state.update { it.copy(replyRecoveryBusy = true, replyRecoveryError = null) }
        viewModelScope.launch {
            try {
                val cleared = if (snapshot != null) chatDraftStore.clearReplyRecovery(sessionId, snapshot.token)
                    else chatDraftStore.discardUnreadableReplyRecovery(sessionId, requireNotNull(unreadable))
                if (cleared) reconcileReplyRecovery()
                else _state.update { it.copy(replyRecoveryError = "丢弃记录失败，请重试") }
            } catch (_: Exception) {
                _state.update { it.copy(replyRecoveryError = "丢弃记录失败，请重试") }
            } finally {
                _state.update { it.copy(replyRecoveryBusy = false) }
            }
        }
    }

    private suspend fun bookmarkedIdsForWindow(messages: List<MessageEntity>): Set<Long> {
        val ids = messages.map(MessageEntity::id)
        return if (ids.isEmpty()) emptySet() else bookmarkDao.getBookmarkedMessageIds(sessionId, ids).toSet()
    }

    /** Refresh current participants and visible historical authors after character edits. */
    fun refreshParticipantCharacterMeta() {
        viewModelScope.launch {
            val branchId = currentBranchId()
            val windowRevision = messageWindowRevision.get()
            val messages = _state.value.messages
            val participants = participantDao.getBySession(sessionId)
            val maps = buildCharacterPresentationMaps(participants, messages)
            val sess = sessionDao.getById(sessionId)
            // A history transition already loaded the presentation for its own window.
            if (currentBranchId() != branchId || messageWindowRevision.get() != windowRevision ||
                _state.value.messages != messages) return@launch
            _state.value = _state.value.copy(
                participants = participants,
                characterNames = maps.names,
                characterAvatars = maps.avatars,
                characterSummaries = maps.summaries,
                characterCardImages = maps.cardImages,
                characterColors = maps.colors,
                userDisplayName = secureStorage.userName,
                userAvatarImagePath = secureStorage.userAvatarImagePath,
                userAvatarColor = secureStorage.userAvatarColor,
                allowSessionThinkMax = secureStorage.allowSessionThinkMax,
                sessionThinkMaxEnabled = sess?.thinkMaxEnabled == true,
                characterForcesThinkMax = participants.firstOrNull()?.characterId?.let {
                    it in maps.thinkMaxEnabledIds
                } == true,
            )
        }
    }

    fun openCharacterState(characterId: Long, expectedBranchId: String = currentBranchId()) {
        if (expectedBranchId != currentBranchId()) return
        characterStateReadRevision++
        characterStateReadJob?.cancel()
        val readRevision = characterStateReadRevision
        val label = _state.value.branches.firstOrNull { it.branchId == expectedBranchId }?.label
            ?: if (expectedBranchId == "main") "主线" else expectedBranchId
        val previous = _state.value.characterStatePanel
        val sameTarget = previous?.sessionId == sessionId && previous.characterId == characterId &&
            previous.branchId == expectedBranchId
        val clearingOwner = characterStateClearJob?.takeIf { it.isActive }
        val target = if (sameTarget) previous!!.copy(loading = true, error = null, clearing = clearingOwner != null)
        else CharacterStatePanel(sessionId, characterId, expectedBranchId, label)
        _state.update { it.copy(characterStatePanel = target) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                // A same-scope reload must read after the pending deletion, including
                // when the sheet was closed and reopened before Room returned.
                clearingOwner?.join()
                // Room's suspend queries already run on its query executor.
                val entity = if (participantDao.getBySession(sessionId).none { it.characterId == characterId }) null
                    else characterStateDao.getBySessionAndCharacter(sessionId, characterId, expectedBranchId)
                val panel = withContext(preparationDispatcher) {
                    parseCharacterStatePanel(entity, sessionId, characterId, expectedBranchId, label)
                }
                val current = _state.value.characterStatePanel
                if (characterStateReadRevision != readRevision || current?.sessionId != sessionId ||
                    current.characterId != characterId || current.branchId != expectedBranchId ||
                    currentBranchId() != expectedBranchId) return@launch
                _state.update {
                    it.copy(characterStatePanel = panel)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val current = _state.value.characterStatePanel
                if (characterStateReadRevision == readRevision && current?.sessionId == sessionId &&
                    current.characterId == characterId && current.branchId == expectedBranchId) {
                    _state.update { it.copy(characterStatePanel = current.copy(
                        loading = false, error = "读取角色状态失败，内容已保留，请重试",
                    )) }
                }
            }
        }
        characterStateReadJob = job
        // A clear's readback is part of its completion, even if the last screen
        // releases the store between the deletion and the Room query.
        if (clearingOwner != null && RetainedChatSessions.stores.contains(sessionId)) {
            RetainedChatSessions.stores.retainJob(sessionId, job, reportRunning = false)
        }
        job.start()
    }

    fun closeCharacterState() {
        characterStateReadRevision++
        characterStateReadJob?.cancel()
        characterStateReadJob = null
        _state.update { it.copy(characterStatePanel = null) }
    }

    fun clearCharacterState() {
        val target = _state.value.characterStatePanel ?: return
        if (target.loading || target.clearing) return
        if (activeGeneration != null || branchTransitionJob?.isActive == true || _state.value.isGenerating) {
            _state.update { it.copy(characterStatePanel = target.copy(error = "回复生成或故事线切换期间暂不可清除，请稍后重试")) }
            return
        }
        if (characterStateClearJob?.isActive == true) {
            _state.update { it.copy(characterStatePanel = target.copy(error = "已有清除操作正在进行，请稍后重试")) }
            return
        }
        characterStateReadRevision++
        characterStateReadJob?.cancel()
        _state.update { it.copy(characterStatePanel = target.copy(clearing = true, error = null)) }
        val clearJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext()[Job]
            try {
                characterStateMutex.withLock {
                val participantStillPresent = participantDao.getBySession(sessionId).any { it.characterId == target.characterId }
                val sameScopeBeforeDelete = currentBranchId() == target.branchId && activeGeneration == null &&
                    branchTransitionJob?.isActive != true && !_state.value.isGenerating && participantStillPresent
                if (!sameScopeBeforeDelete) {
                    val current = _state.value.characterStatePanel
                    if (current?.characterId == target.characterId && current.branchId == target.branchId) {
                        _state.update { it.copy(characterStatePanel = current.copy(clearing = false, error = "当前故事线或参与角色已变化，未清除状态，请重新打开")) }
                    }
                    return@withLock
                }
                val sameScopeAtDelete = currentBranchId() == target.branchId && activeGeneration == null &&
                    branchTransitionJob?.isActive != true && !_state.value.isGenerating
                if (!sameScopeAtDelete) {
                    val current = _state.value.characterStatePanel
                    if (current?.characterId == target.characterId && current.branchId == target.branchId) {
                        _state.update { it.copy(characterStatePanel = current.copy(clearing = false, error = "当前故事线或参与角色已变化，未清除状态，请重新打开")) }
                    }
                    return@withLock
                }
                val deleted = try {
                    characterStateDao.deleteBySessionCharacterBranch(sessionId, target.characterId, target.branchId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    val current = _state.value.characterStatePanel
                    if (current?.characterId == target.characterId && current.branchId == target.branchId) {
                        _state.update { it.copy(characterStatePanel = current.copy(clearing = false, error = "清除失败，内容已保留，请重试")) }
                    }
                    return@withLock
                }
                val current = _state.value.characterStatePanel
                if (current?.characterId == target.characterId && current.branchId == target.branchId) {
                    if (deleted > 0) openCharacterState(target.characterId, target.branchId)
                    else _state.update { it.copy(characterStatePanel = current.copy(
                        loading = false, clearing = false, error = "当前没有可清除的自动状态",
                    )) }
                }
                }
            } finally {
                if (characterStateClearJob === owner) characterStateClearJob = null
            }
        }
        characterStateClearJob = clearJob
        if (RetainedChatSessions.stores.contains(sessionId)) {
            RetainedChatSessions.stores.retainJob(sessionId, clearJob, reportRunning = false)
        }
        clearJob.start()
    }

    fun setSessionThinkMax(enabled: Boolean, onMessage: (String) -> Unit) {
        if (_state.value.sessionThinkMaxSaving) return
        if (!canMutateRoundConfiguration()) return
        if (enabled && !secureStorage.allowSessionThinkMax) {
            onMessage("请先在「设置 → 联网与模型」中开启「允许对话页思考/Max」")
            return
        }
        _state.update { it.copy(sessionThinkMaxSaving = true, sessionThinkMaxSaveError = null) }
        val writeJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                sessionDao.updateThinkMax(sessionId, enabled)
                _state.update { it.copy(sessionThinkMaxEnabled = enabled) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(sessionThinkMaxSaveError = "思考/Max 设置未保存，请重试") }
            } finally {
                _state.update { it.copy(sessionThinkMaxSaving = false) }
            }
        }
        if (RetainedChatSessions.stores.contains(sessionId)) {
            RetainedChatSessions.stores.retainJob(sessionId, writeJob, reportRunning = false)
        }
        writeJob.start()
    }

    fun updateNarratorGuidance(text: String) {
        narratorDraftRevision++
        _state.update { it.copy(narratorGuidance = text) }
        persistCurrentDraft()
    }

    private fun canContinueFromCurrentWindow(): Boolean {
        val message = when {
            _state.value.isLoadingHistory -> "历史消息加载中，请稍后再续聊"
            _state.value.hasNewerMessages -> "正在查看较早消息，请先回到最新再续聊"
            else -> return true
        }
        _state.update { it.copy(error = message) }
        return false
    }

    private fun rejectPendingReplyRecovery(): Boolean {
        if (_state.value.replyRecovery == null && !_state.value.replyRecoveryBusy) return false
        _state.update { it.copy(error = "请先保留、复制或丢弃上次中断的回复") }
        return true
    }

    fun submitNarratorGuidance(guidance: String): Boolean {
        if (!canContinueFromCurrentWindow()) return false
        val revision = narratorDraftRevision
        return requestNarrator(guidance, onGuidanceCommitted = {
            if (revision == narratorDraftRevision && _state.value.narratorGuidance.trim() == guidance.trim()) {
                updateNarratorGuidance("")
            }
        })
    }

    fun updateInput(text: String, expectedBranchId: String? = null): Boolean {
        if (expectedBranchId != null && currentBranchId() != expectedBranchId) return false
        if (text != _state.value.inputText) activeDraftSubmissionId = null
        _state.value = _state.value.copy(inputText = text)
        persistCurrentDraft()
        return true
    }

    /** Snackbar 展示后调用，避免同一 error 在重组时重复弹出。 */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearContextBudgetError(expected: String) {
        _state.update { if (it.contextBudgetError == expected) it.copy(contextBudgetError = null) else it }
    }

    fun appendVoiceText(text: String, expectedBranchId: String? = null): Boolean {
        if (expectedBranchId != null && currentBranchId() != expectedBranchId) return false
        val t = text.trim()
        if (t.isEmpty()) return false
        activeDraftSubmissionId = null
        val cur = _state.value.inputText
        val sep = if (cur.isBlank() || cur.endsWith("\n")) "" else " "
        _state.value = _state.value.copy(inputText = cur + sep + t)
        persistCurrentDraft()
        return true
    }

    fun isCurrentChatBranch(expectedBranchId: String): Boolean = currentBranchId() == expectedBranchId

    fun queueLocalImageAttachment(path: String, expectedBranchId: String? = null): Boolean {
        if (path.isBlank()) return false
        if (expectedBranchId != null && currentBranchId() != expectedBranchId) return false
        _state.value = _state.value.copy(
            pendingLocalImagePaths = (_state.value.pendingLocalImagePaths + path).distinct()
        )
        persistCurrentDraft()
        return true
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
    suspend fun importTavernChatStream(
        openReader: () -> java.io.Reader,
        onProgress: (String, Int) -> Unit = { _, _ -> },
    ): TavernChatImportResult {
        check(mediaBundleJob == null) { "请先完成或停止主线媒体包操作" }
        if (activeGeneration != null || branchTransitionJob?.isActive == true) {
            throw IllegalStateException("请等待当前生成或故事线切换完成后再导入")
        }
        val firstCharId = participantDao.getBySession(sessionId).firstOrNull()?.characterId
            ?: throw IllegalStateException("请先在本会话添加至少一名角色参与者")
        val branchId = currentBranchId()
        suspend fun report(stage: String, count: Int) = withContext(Dispatchers.Main.immediate) {
            onProgress(stage, count)
        }
        val fingerprint = withContext(Dispatchers.IO) {
            TavernChatImportParser.Fingerprint().also { scan ->
                openReader().use { reader ->
                    TavernChatImportParser.forEachRow(reader) { row ->
                        scan.add(row)
                        if (scan.count % 128 == 0) report("正在检查文件", scan.count)
                    }
                }
            }
        }
        if (fingerprint.count == 0) {
            throw IllegalStateException("未解析出任何消息（支持 JSON 根级 mes[]、messages[] 或 JSONL）")
        }
        report("正在检查文件", fingerprint.count)
        if (currentBranchId() != branchId || activeGeneration != null || branchTransitionJob?.isActive == true) {
            throw IllegalStateException("故事线或生成状态已变化，请重新选择文件导入")
        }
        val insertedCount = withContext(Dispatchers.IO) {
            openReader().use { reader ->
                messageDao.insertImportStreamIfAbsent(
                    sessionId = sessionId,
                    branchId = branchId,
                    batchId = fingerprint.batchId(),
                    expectedCount = fingerprint.count,
                    characterId = firstCharId,
                    reader = reader,
                    onProgress = { count -> report("正在写入记录", count) },
                )
            }
        }
        var refreshFailed = false
        if (insertedCount > 0) {
            refreshFailed = withContext(NonCancellable) {
                runCatching { sessionDao.bumpUpdatedAt(sessionId) }.isFailure
            }
            try {
                refreshMessagesUi(branchId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { refreshFailed = true }
        }
        return TavernChatImportResult(
            importedCount = insertedCount,
            duplicate = insertedCount == 0,
            refreshFailed = refreshFailed,
        )
    }

    private fun currentBranchId(): String =
        _state.value.currentBranchId.ifBlank { "main" }

    private suspend fun excludedKeysForWindow(branchId: String, messages: List<MessageEntity>): Set<String> {
        val keys = messages.map(MessageEntity::contextSelectionKey).distinct()
        return if (keys.isEmpty()) emptySet() else
            messageDao.getExcludedContextKeys(sessionId, branchId, keys).toSet()
    }

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

    private fun anchorsForBranches(branches: List<SessionBranchEntity>): Map<Long, List<BranchAnchor>> = branches
        .filter { it.sourceMessageId > 0L }
        .groupBy { it.sourceMessageId }
        .mapValues { (_, sources) ->
            sources.map { branch -> BranchAnchor(branch.branchId, branch.label.ifBlank { branch.branchId }) }
        }

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

    private suspend fun visibleDisplayLines(
        messages: List<MessageEntity>,
        attachments: Map<Long, List<MessageAttachmentEntity>>,
    ): List<ChatDisplayLine> = withContext(preparationDispatcher) {
        messages.toChatDisplayLines().filter { line ->
            val selected = line.selectedMessage()
            shouldShowCharacterBubbleLine(selected, attachments[selected.id].orEmpty())
        }
    }

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

    private suspend fun refreshMessagesUi(
        requestedBranchId: String = currentBranchId(),
        anchorMessageId: Long? = null,
        switchBranchOnSuccess: Boolean = false,
        requireSourceAnchor: Boolean = false,
    ): Boolean {
        val startingBranchId = currentBranchId()
        if (!switchBranchOnSuccess && requestedBranchId != startingBranchId) return false
        // Navigation owns the window until its refresh finishes; background writers may still finish their writes.
        if (branchTransitionJob?.isActive == true && currentCoroutineContext()[Job] !== branchTransitionJob) return false
        val windowRevision = messageWindowRevision.incrementAndGet()
        val branches = sessionBranchDao.getBySession(sessionId)
        if (requireSourceAnchor && requestedBranchId != "main" && branches.none { it.branchId == requestedBranchId }) return false
        val branchId = if (
            requestedBranchId == "main" || branches.any { it.branchId == requestedBranchId }
        ) requestedBranchId else "main"
        val refreshEventPage = switchBranchOnSuccess ||
            (eventPanelRequestedBranchId == branchId && _state.value.eventNodesLoaded)
        val eventRequest = _state.value.takeIf { it.currentBranchId == branchId }
        val eventRevision = if (refreshEventPage) eventRefreshRevision.incrementAndGet()
            else eventRefreshRevision.get()
        val refreshSummaryPage = !switchBranchOnSuccess && _state.value.let {
            it.currentBranchId == branchId && it.memorySegmentsLoaded
        }
        val summaryRequest = _state.value
        val summaryRevision = if (refreshSummaryPage) memorySummaryListRevision.incrementAndGet()
            else memorySummaryListRevision.get()
        val refreshContextMemory = !switchBranchOnSuccess && _state.value.let {
            it.currentBranchId == branchId && it.contextMemoryLoaded
        }
        val contextRevision = if (refreshContextMemory) contextMemoryDisplayRevision.incrementAndGet()
            else contextMemoryDisplayRevision.get()
        if (refreshContextMemory) {
            _state.update { state -> if (state.currentBranchId == branchId)
                state.copy(contextMemoryLoading = false) else state }
        }
        if (refreshSummaryPage) {
            _state.update { state -> if (state.currentBranchId == branchId)
                state.copy(memorySegmentsLoadingMore = false) else state }
        }
        val anchor = anchorMessageId?.let { getVisibleMessage(branchId, it) }
        if (requireSourceAnchor && anchor == null) return false
        val historyWindow = _state.value.takeIf { current ->
            !switchBranchOnSuccess && anchorMessageId == null && current.currentBranchId == branchId &&
                current.hasNewerMessages && current.messages.isNotEmpty()
        }
        val historyEndId = historyWindow?.messages?.lastOrNull()?.id?.takeIf { it < Long.MAX_VALUE }
        val historySize = historyWindow?.messages?.size?.coerceAtMost(MAX_MESSAGE_WINDOW_SIZE) ?: 0
        val historyRows = historyEndId?.let { getMessagesBefore(branchId, it + 1, historySize + 1) }.orEmpty()
        val preserveHistory = historyRows.isNotEmpty()
        val radius = INITIAL_MESSAGE_WINDOW_SIZE / 2
        val pageRows = when {
            preserveHistory -> historyRows
            anchor != null -> getMessagesBefore(branchId, anchor.id, radius + 1)
            else -> getMessageTailForBranch(branchId, INITIAL_MESSAGE_WINDOW_SIZE + 1)
        }
        val afterRows = when {
            preserveHistory -> getMessagesAfter(branchId, historyEndId!!, 1)
            anchor != null -> getMessagesAfter(branchId, anchor.id, radius + 1)
            else -> emptyList()
        }
        val hasOlderMessages = pageRows.size > when {
            preserveHistory -> historySize
            anchor != null -> radius
            else -> INITIAL_MESSAGE_WINDOW_SIZE
        }
        val rawMsgs = when {
            preserveHistory -> pageRows.take(historySize).asReversed()
            anchor != null -> pageRows.take(radius).asReversed() + anchor + afterRows.take(radius)
            else -> pageRows.take(INITIAL_MESSAGE_WINDOW_SIZE).asReversed()
        }
        val msgs = recoverAutoImageMessages(rawMsgs, branchId)
        val hasNewerMessages = if (preserveHistory) afterRows.isNotEmpty()
            else anchor != null && afterRows.size > radius
        val focusedMessageId = when {
            preserveHistory -> historyWindow?.focusedMessageId?.takeIf { id -> msgs.any { it.id == id } }
            else -> anchor?.id
        }
        val excludedKeys = excludedKeysForWindow(branchId, msgs)
        val world = sessionWorldDao.getBySession(sessionId)
        val anchors = anchorsForBranches(branches)
        val map = attachmentsForMessages(msgs)
        val displayLines = visibleDisplayLines(msgs, map)
        val sess = sessionDao.getById(sessionId)
        val participants = participantDao.getBySession(sessionId)
        val presentation = buildCharacterPresentationMaps(participants, msgs)
        val firstCharacterForcesThinkMax = participants.firstOrNull()?.characterId in presentation.thinkMaxEnabledIds
        val displayCap = sess?.displayContextTokenLimit?.takeIf { it > 0 } ?: 1_000_000
        val memoryPage = if (refreshSummaryPage) try {
            readMemorySummaryWindow(branchId, summaryRequest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } else null
        val contextMemoryText = if (refreshContextMemory) try {
            withContext(preparationDispatcher) {
                universalContextMemoryManager.getFormattedMemory(sessionId, branchId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } else null
        val refreshFoundation = world?.encyclopediaId != null && _state.value.encyclopediaFoundationLoaded
        val foundationRevision = if (refreshFoundation) encyclopediaFoundationRevision.incrementAndGet()
            else encyclopediaFoundationRevision.get()
        if (refreshFoundation) {
            _state.update { state -> if (state.world?.encyclopediaId == world?.encyclopediaId &&
                state.world?.worldPrompt == world?.worldPrompt)
                state.copy(encyclopediaFoundationLoading = false) else state }
        }
        val encyclopediaFoundation = if (refreshFoundation) try {
            withContext(preparationDispatcher) { contextBuilder.encyclopediaFoundation(world) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } else null
        val roundChoices = if (hasNewerMessages) RoundChoiceSnapshot() else withContext(preparationDispatcher) {
            buildRoundChoiceSnapshot(world, msgs.filterNot { it.contextSelectionKey() in excludedKeys })
        }
        val eventWindowSize = eventRequest?.eventNodesWindowSize ?: EVENT_NODE_PAGE_SIZE
        val eventPage = if (refreshEventPage) try {
            readEventPage(branchId, eventRequest?.eventQuery.orEmpty(), eventRequest?.eventResolvedFilter,
                eventRequest?.eventNodesBeforeCreatedAt, eventRequest?.eventNodesBeforeId, eventWindowSize + 1)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _state.update { state -> if (state.currentBranchId == startingBranchId &&
                !state.eventNodesLoaded && state.eventNodesLoadingMore)
                state.copy(eventNodesLoadingMore = false, eventNodesLoadError = "事件读取未完成，请重试")
                else state }
            throw failure
        } else null
        bookmarkMutex.withLock {
            val bookmarkIds = bookmarkedIdsForWindow(msgs)
            if (currentBranchId() != startingBranchId || messageWindowRevision.get() != windowRevision) {
                return@withLock
            }
            if (startingBranchId != branchId && _state.value.bookmarkReadOnlyId != null) closeBookmarkedReadOnlyMessage()
            _state.update { current ->
                // A slower read must not replace a newer refresh or history navigation.
                if (current.currentBranchId != startingBranchId ||
                    messageWindowRevision.get() != windowRevision) return@update current
                val switchingBranch = current.currentBranchId != branchId
                val applySummaryPage = memoryPage != null && current.memorySegmentsLoaded &&
                    memorySummaryListRevision.get() == summaryRevision
                val summaryReadFailed = refreshSummaryPage && memoryPage == null &&
                    memorySummaryListRevision.get() == summaryRevision
                val applyContextMemory = contextMemoryText != null && current.contextMemoryLoaded &&
                    contextMemoryDisplayRevision.get() == contextRevision
                val contextMemoryReadFailed = refreshContextMemory && contextMemoryText == null &&
                    contextMemoryDisplayRevision.get() == contextRevision
                val foundationChanged = current.world?.encyclopediaId != world?.encyclopediaId ||
                    current.world?.worldPrompt != world?.worldPrompt
                val applyFoundation = encyclopediaFoundation != null &&
                    encyclopediaFoundationRevision.get() == foundationRevision
                val foundationReadFailed = refreshFoundation && encyclopediaFoundation == null &&
                    encyclopediaFoundationRevision.get() == foundationRevision
                val applyEventPage = eventPage != null && (switchingBranch ||
                    (eventRefreshRevision.get() == eventRevision && current.eventNodesWindowSize == eventWindowSize))
                current.copy(
                    messages = msgs,
                    characterNames = presentation.names,
                    characterAvatars = presentation.avatars,
                    characterSummaries = presentation.summaries,
                    characterCardImages = presentation.cardImages,
                    characterColors = presentation.colors,
                    excludedContextKeys = excludedKeys,
                    displayLines = displayLines,
                    hasOlderMessages = hasOlderMessages,
                    hasNewerMessages = hasNewerMessages,
                    isLoadingHistory = false,
                    historyWindowRestored = false,
                    focusedMessageId = focusedMessageId,
                    messageAttachments = map,
                    bookmarkedMessageIds = bookmarkIds,
                    branches = branches,
                    currentBranchId = branchId,
                    contextMemoryText = if (switchingBranch) "" else if (applyContextMemory)
                        contextMemoryText!! else current.contextMemoryText,
                    contextMemoryLoaded = if (switchingBranch) false else current.contextMemoryLoaded,
                    contextMemoryLoading = if (switchingBranch) false else current.contextMemoryLoading,
                    contextMemoryLoadError = if (switchingBranch || applyContextMemory) null
                        else if (contextMemoryReadFailed) "长期记忆读取失败，请重试"
                        else current.contextMemoryLoadError,
                    contextMemoryClearError = if (switchingBranch) null else current.contextMemoryClearError,
                    contextMemoryStatus = if (current.currentBranchId == branchId) current.contextMemoryStatus else ContextMemoryStatus.IDLE,
                    encyclopediaFoundation = if (world?.encyclopediaId == null) "" else if (applyFoundation)
                        encyclopediaFoundation!! else if (foundationChanged) "" else current.encyclopediaFoundation,
                    encyclopediaFoundationLoaded = if (world?.encyclopediaId == null) true
                        else if (applyFoundation) true else if (foundationChanged) false
                        else current.encyclopediaFoundationLoaded,
                    encyclopediaFoundationLoading = if (foundationChanged || applyFoundation || foundationReadFailed) false
                        else current.encyclopediaFoundationLoading,
                    encyclopediaFoundationLoadError = if (world?.encyclopediaId == null || applyFoundation) null
                        else if (foundationReadFailed) "百科基础设定读取失败，请重试"
                        else if (foundationChanged) null else current.encyclopediaFoundationLoadError,
                    memorySegments = if (switchingBranch) emptyList() else if (applySummaryPage)
                        memoryPage!!.take(summaryRequest.memorySegmentsWindowSize) else current.memorySegments,
                    memorySegmentsWindowSize = if (switchingBranch) MEMORY_SEGMENT_PAGE_SIZE else current.memorySegmentsWindowSize,
                    memorySegmentsBeforeEndId = if (switchingBranch) null else current.memorySegmentsBeforeEndId,
                    memorySegmentsBeforeId = if (switchingBranch) null else current.memorySegmentsBeforeId,
                    memorySegmentsLoaded = if (switchingBranch) false else current.memorySegmentsLoaded,
                    memorySegmentsLoading = if (switchingBranch) false else current.memorySegmentsLoading,
                    memorySegmentsHasMore = if (switchingBranch) false else if (applySummaryPage)
                        memoryPage!!.size > summaryRequest.memorySegmentsWindowSize else if (summaryReadFailed)
                        false else current.memorySegmentsHasMore,
                    memorySegmentsLoadingMore = if (switchingBranch || applySummaryPage) false
                        else current.memorySegmentsLoadingMore,
                    memorySegmentsLoadError = if (switchingBranch || applySummaryPage) null
                        else if (summaryReadFailed) "摘要读取失败，请重试"
                        else current.memorySegmentsLoadError,
                    memoryCorrections = if (switchingBranch) emptyList()
                        else current.memoryCorrections,
                    memoryCorrectionsLoaded = if (switchingBranch) false else current.memoryCorrectionsLoaded,
                    memoryCorrectionsLoading = if (switchingBranch) false else current.memoryCorrectionsLoading,
                    memoryCorrectionsHasMore = if (switchingBranch) false else current.memoryCorrectionsHasMore,
                    memoryCorrectionsWindowSize = if (switchingBranch) MEMORY_CORRECTION_PAGE_SIZE
                        else current.memoryCorrectionsWindowSize,
                    memoryCorrectionsLoadError = if (switchingBranch) null else current.memoryCorrectionsLoadError,
                    roundChoiceOptions = roundChoices.options,
                    roundChoiceMessageId = roundChoices.sourceMessageId,
                    branchAnchorsByMessageId = anchors,
                    eventQuery = if (switchingBranch) "" else current.eventQuery,
                    eventResolvedFilter = if (switchingBranch) null else current.eventResolvedFilter,
                    eventNodesBeforeCreatedAt = if (switchingBranch) null else current.eventNodesBeforeCreatedAt,
                    eventNodesBeforeId = if (switchingBranch) null else current.eventNodesBeforeId,
                    eventNodes = if (applyEventPage) eventPage!!.take(eventWindowSize)
                        else if (switchingBranch) emptyList() else current.eventNodes,
                    eventNodesLoaded = if (applyEventPage) true else if (switchingBranch) false else current.eventNodesLoaded,
                    eventNodesWindowSize = if (switchingBranch)
                        EVENT_NODE_PAGE_SIZE else current.eventNodesWindowSize,
                    eventNodesHasMore = if (applyEventPage) eventPage!!.size > eventWindowSize
                        else if (switchingBranch) false else current.eventNodesHasMore,
                    eventNodesLoadingMore = if (applyEventPage || switchingBranch) false else current.eventNodesLoadingMore,
                    eventNodesRefreshFailed = if (applyEventPage || switchingBranch) false else current.eventNodesRefreshFailed,
                    eventNodesLoadError = if (applyEventPage || switchingBranch) null else current.eventNodesLoadError,
                    allowSessionThinkMax = secureStorage.allowSessionThinkMax,
                    sessionThinkMaxEnabled = sess?.thinkMaxEnabled == true,
                    characterForcesThinkMax = firstCharacterForcesThinkMax,
                    displayContextTokenLimit = displayCap,
                    conversationTokenEstimate = null,
                )
            }
        }
        if (switchBranchOnSuccess && currentBranchId() == branchId &&
            messageWindowRevision.get() == windowRevision) {
            // Successful navigation already resets criteria. Save that reset so recreation
            // cannot resurrect the previous line's search; failed navigation keeps its intent.
            val current = _state.value
            if (branchId == startingBranchId && current.eventNodesLoaded) saveEventWindow(current)
            else saveEventCriteria(branchId, current.eventQuery, current.eventResolvedFilter)
            eventPanelRequestedBranchId = branchId
            if (branchId != startingBranchId) {
                correctionRefreshRevision.incrementAndGet()
                memorySummaryListRevision.incrementAndGet()
                saveMemorySummaryWindow(current)
                contextMemoryDisplayRevision.incrementAndGet()
            }
        }
        if (_state.value.messages == msgs && currentBranchId() == branchId &&
            messageWindowRevision.get() == windowRevision) {
            saveHistoryWindowIntent(_state.value, focusedMessageId)
            scheduleConversationTokenEstimate(msgs, excludedKeys, branchId, windowRevision)
        }
        return currentBranchId() == branchId && messageWindowRevision.get() == windowRevision &&
            (!requireSourceAnchor || _state.value.focusedMessageId == anchorMessageId)
    }

    private suspend fun recoverAutoImageMessages(
        messages: List<MessageEntity>,
        branchId: String,
    ): List<MessageEntity> {
        if (messages.isEmpty() || activeGeneration != null) return messages
        val imageRecovered = messages.map { message ->
            val metadata = AutoImageMetadata.parse(message.structuredContentJson)
            if (metadata != null &&
                (metadata.state == AutoImageMetadata.STATE_RUNNING ||
                    (metadata.state.isBlank() &&
                        (message.content.contains("配图生成中") || message.content.isBlank()))) &&
                message.branchId == branchId &&
                message.parentMessageId != null &&
                messageDao.markAutoImageRunningInterrupted(message.id, sessionId, branchId)
            ) {
                val recoverable = metadata.prompt?.isNotBlank() == true
                message.copy(
                    content = if (recoverable) "🖼 配图生成中断，可重试" else "🖼 配图已中断",
                    structuredContentJson = AutoImageMetadata.update(
                        message.structuredContentJson,
                        AutoImageMetadata.STATE_INTERRUPTED,
                    ),
                )
            } else message
        }
        return imageRecovered.map { message ->
            val metadata = AutoVoiceMetadata.parse(message.structuredContentJson)
            val running = metadata?.state == AutoVoiceMetadata.STATE_RUNNING ||
                (metadata?.state.orEmpty().isBlank() && metadata?.text == null &&
                    (AutoVoiceMetadata.isLegacyRunningContent(message.content) || (metadata != null && message.content.isBlank())))
            if (running && message.branchId == branchId && message.parentMessageId != null &&
                messageDao.markAutoVoiceRunningInterrupted(message.id, sessionId, branchId, metadata?.attemptToken)) {
                try {
                    cleanupInterruptedVoiceFiles(metadata?.attemptToken)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    UsbSessionLog.w("ChatVoiceGen", "orphan cleanup failed type=${failure.javaClass.simpleName}")
                }
                message.copy(content = if (metadata?.retryable == true || !metadata?.text.isNullOrBlank()) "配音生成中断，可重试" else "配音已中断",
                    structuredContentJson = AutoVoiceMetadata.update(message.structuredContentJson, AutoVoiceMetadata.STATE_INTERRUPTED))
            } else message
        }
    }

    private suspend fun cleanupInterruptedVoiceFiles(token: String?) {
        if (token == null || runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false).not()) return
        withContext(Dispatchers.IO) {
            val directory = java.io.File(appContext.filesDir, "attachments/$sessionId").canonicalFile
            if (!directory.isDirectory) return@withContext
            java.nio.file.Files.newDirectoryStream(directory.toPath(), "gen_voice_${token}_*").use { paths ->
                for (path in paths) {
                    if (java.nio.file.Files.isSymbolicLink(path)) continue
                    val file = path.toFile().canonicalFile
                    if (file.parentFile == directory && file.isFile &&
                        file.name.matches(Regex("gen_voice_${Regex.escape(token)}_[0-9]+\\.(wav|mp3)")) &&
                        attachmentDao.countByStoragePath(file.absolutePath) == 0) file.delete()
                }
            }
        }
    }

    private fun launchHistoryLoad(
        block: suspend (branchId: String, windowRevision: Long) -> Unit,
    ): Boolean {
        if (activeGeneration != null ||
            historyLoadJob?.isActive == true ||
            branchTransitionJob?.isActive == true
        ) return false
        val branchId = currentBranchId()
        val windowRevision = messageWindowRevision.incrementAndGet()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            try {
                block(branchId, windowRevision)
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
        windowRevision: Long = messageWindowRevision.get(),
        resetReadingIntent: Boolean = false,
    ): Boolean {
        if (currentBranchId() != branchId || messageWindowRevision.get() != windowRevision) return false
        val normalized = withEffectiveSwipeSelections(
            branchId,
            messages.distinctBy { it.id }.sortedBy { it.id },
        )
        val excludedKeys = excludedKeysForWindow(branchId, normalized)
        val attachments = attachmentsForMessages(normalized)
        val displayLines = visibleDisplayLines(normalized, attachments)
        val presentation = buildCharacterPresentationMaps(_state.value.participants, normalized)
        val applied = withContext(preparationDispatcher) {
            bookmarkMutex.withLock {
                if (currentBranchId() != branchId || messageWindowRevision.get() != windowRevision) {
                    return@withLock false
                }
                val bookmarkIds = bookmarkedIdsForWindow(normalized)
                if (currentBranchId() != branchId || messageWindowRevision.get() != windowRevision) {
                    return@withLock false
                }
                _state.update { current ->
                    if (current.currentBranchId != branchId || messageWindowRevision.get() != windowRevision)
                        return@update current
                    val roundChoices = if (hasNewerMessages) {
                        RoundChoiceSnapshot()
                    } else {
                        buildRoundChoiceSnapshot(current.world, normalized.filterNot { it.contextSelectionKey() in excludedKeys })
                    }
                    current.copy(
                        messages = normalized,
                        characterNames = presentation.names,
                        characterAvatars = presentation.avatars,
                        characterSummaries = presentation.summaries,
                        characterCardImages = presentation.cardImages,
                        characterColors = presentation.colors,
                        excludedContextKeys = excludedKeys,
                        displayLines = displayLines,
                        hasOlderMessages = hasOlderMessages,
                        hasNewerMessages = hasNewerMessages,
                        messageAttachments = attachments,
                        bookmarkedMessageIds = bookmarkIds,
                        focusedMessageId = focusedMessageId,
                        historyWindowRestored = false,
                        roundChoiceOptions = roundChoices.options,
                        roundChoiceMessageId = roundChoices.sourceMessageId,
                        conversationTokenEstimate = null,
                    )
                }
                true
            }
        }
        if (applied && _state.value.messages == normalized && currentBranchId() == branchId &&
            messageWindowRevision.get() == windowRevision) {
            if (resetReadingIntent) clearHistoryWindowIntent()
            saveHistoryWindowIntent(_state.value, focusedMessageId)
            scheduleConversationTokenEstimate(normalized, excludedKeys, branchId, windowRevision)
        }
        return applied
    }

    fun loadOlderMessages() {
        val snapshot = _state.value
        val anchorId = snapshot.messages.firstOrNull()?.id ?: return
        if (!snapshot.hasOlderMessages) return
        launchHistoryLoad { branchId, windowRevision ->
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
                windowRevision = windowRevision,
            )
        }
    }

    fun loadNewerMessages() {
        val snapshot = _state.value
        val anchorId = snapshot.messages.lastOrNull()?.id ?: return
        if (!snapshot.hasNewerMessages) return
        launchHistoryLoad { branchId, windowRevision ->
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
                windowRevision = windowRevision,
            )
        }
    }

    fun returnToLatestMessages(): Boolean =
        launchHistoryLoad { branchId, windowRevision ->
            val rows = getMessageTailForBranch(branchId, INITIAL_MESSAGE_WINDOW_SIZE + 1)
            applyHistoryWindow(
                branchId = branchId,
                messages = rows.take(INITIAL_MESSAGE_WINDOW_SIZE).asReversed(),
                hasOlderMessages = rows.size > INITIAL_MESSAGE_WINDOW_SIZE,
                hasNewerMessages = false,
                windowRevision = windowRevision,
                resetReadingIntent = true,
            )
        }

    fun openMessageInHistory(messageId: Long): Boolean =
        launchHistoryLoad { branchId, windowRevision ->
            if (!loadMessageWindow(branchId, messageId, windowRevision) &&
                messageWindowRevision.get() == windowRevision) {
                _state.update { it.copy(error = "该消息已删除或不在当前故事线") }
            }
        }

    fun openMessageInHistoryWithResult(messageId: Long, onResult: (Boolean) -> Unit): Boolean =
        launchHistoryLoad { branchId, windowRevision ->
            var opened = false
            try {
                opened = loadMessageWindow(branchId, messageId, windowRevision) &&
                    currentBranchId() == branchId && _state.value.messages.any { it.id == messageId }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The caller keeps its source list open and offers an inline retry for failed reads.
            } finally {
                onResult(opened)
            }
        }

    /** Opens a search hit after validating and switching to its owning story line. */
    fun openMessageInHistoryInBranch(branchId: String, messageId: Long, onResult: (Boolean) -> Unit): Boolean {
        if (_state.value.isGenerating || branchId.isBlank()) return false
        if (currentBranchId() == branchId) return openMessageInHistoryWithResult(messageId, onResult)
        return openResolvedSourceInHistory(messageId, onResult) { branchId }
    }

    /** A correction's scope may be wider than its source line. Keep inherited visible originals in this line. */
    fun openMemorySourceInHistory(messageId: Long, onResult: (Boolean) -> Unit): Boolean {
        if (_state.value.isGenerating) return false
        return openResolvedSourceInHistory(messageId, onResult) {
            val currentBranch = currentBranchId()
            if (getVisibleMessage(currentBranch, messageId) != null) currentBranch
            else messageDao.getByIdInSession(messageId, sessionId)?.branchId
        }
    }

    private fun openResolvedSourceInHistory(
        messageId: Long,
        onResult: (Boolean) -> Unit,
        resolveBranch: suspend () -> String?,
    ): Boolean {
        return launchBranchTransition(navigationLabel = "正在定位原文…", invalidateSpeech = true) {
            var opened = false
            try {
                branchVisibilityIndexManager.ensureReady()
                val branchId = resolveBranch()?.takeIf { it.isNotBlank() } ?: return@launchBranchTransition
                if (branchId != "main" && sessionBranchDao.getByBranch(sessionId, branchId) == null) return@launchBranchTransition
                if (getVisibleMessage(branchId, messageId) == null) return@launchBranchTransition
                opened = refreshMessagesUi(branchId, anchorMessageId = messageId,
                    switchBranchOnSuccess = branchId != currentBranchId(), requireSourceAnchor = true) &&
                    currentBranchId() == branchId && _state.value.focusedMessageId == messageId
                if (opened) persistCurrentBranchSelection()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
            finally { onResult(opened) }
        }
    }

    fun openBookmarkedMessage(messageId: Long, onOpened: () -> Unit = {}) {
        if (!_state.value.isReady) return
        val launched = launchBranchTransition(invalidateSpeech = true) {
            val revision = ++bookmarkReadOnlyRevision
            clearBookmarkReadOnlyIntent()
            val readingBranch = currentBranchId()
            _state.update { it.copy(bookmarkLocatingId = messageId) }
            var opened = false
            try {
                val currentBranch = currentBranchId()
                val visible = getVisibleMessage(currentBranch, messageId)
                if (visible != null) {
                    if (isInactiveBookmarkedVariant(currentBranch, visible)) {
                        if (bookmarkReadOnlyRevision != revision) return@launchBranchTransition
                        showBookmarkReadOnlyMessage(visible, readingBranch)
                        opened = true
                    } else {
                        opened = loadMessageWindow(currentBranch, messageId)
                    }
                } else {
                    val target = messageDao.getByIdInSession(messageId, sessionId)
                    val branches = sessionBranchDao.getBySession(sessionId)
                    if (target != null) {
                        val sourceAvailable = (target.branchId == "main" || branches.any { it.branchId == target.branchId }) &&
                            getVisibleMessage(target.branchId, messageId) != null
                        if (!sourceAvailable || isInactiveBookmarkedVariant(target.branchId, target)) {
                            if (bookmarkReadOnlyRevision != revision) return@launchBranchTransition
                            showBookmarkReadOnlyMessage(target, readingBranch)
                            opened = true
                        } else {
                            refreshMessagesUi(target.branchId, anchorMessageId = messageId, switchBranchOnSuccess = true)
                            opened = _state.value.currentBranchId == target.branchId && _state.value.focusedMessageId == messageId
                            if (opened) persistCurrentBranchSelection()
                        }
                    }
                }
                if (!opened && bookmarkReadOnlyRevision == revision) _state.update { it.copy(error = "收藏原文已删除或所在故事线已不可用") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (bookmarkReadOnlyRevision == revision) _state.update { it.copy(error = "收藏原文定位失败，请重试") }
            } finally {
                if (_state.value.bookmarkLocatingId == messageId) _state.update { it.copy(bookmarkLocatingId = null) }
            }
            if (opened && bookmarkReadOnlyRevision == revision) onOpened()
        }
        if (!launched) _state.update { it.copy(error = "当前正在生成或切换故事线，请稍后再定位收藏") }
    }

    private suspend fun isInactiveBookmarkedVariant(branchId: String, message: MessageEntity): Boolean {
        val groupId = message.swipeGroupId?.takeIf(String::isNotBlank) ?: return false
        val selectedId = messageDao.getEffectiveSwipeSelectionsForGroups(sessionId, branchId, listOf(groupId))
            .firstOrNull { it.swipeGroupId == groupId }?.selectedMessageId
        return selectedId != message.id
    }

    private fun showBookmarkReadOnlyMessage(message: MessageEntity, readingBranch: String) {
        savedStateHandle["bookmark_reader_id_$sessionId"] = message.id
        savedStateHandle["bookmark_reader_branch_$sessionId"] = readingBranch
        _state.update { it.copy(bookmarkReadOnlyId = message.id, bookmarkReadOnlyBranchId = readingBranch,
            bookmarkReadOnlyMessage = message, bookmarkReadOnlyLoading = false, bookmarkReadOnlyError = null) }
    }

    private fun clearBookmarkReadOnlyIntent() {
        savedStateHandle.remove<Long>("bookmark_reader_id_$sessionId")
        savedStateHandle.remove<String>("bookmark_reader_branch_$sessionId")
        _state.update { it.copy(bookmarkReadOnlyId = null, bookmarkReadOnlyBranchId = null,
            bookmarkLocatingId = if (it.bookmarkLocatingId == it.bookmarkReadOnlyId) null else it.bookmarkLocatingId,
            bookmarkReadOnlyMessage = null, bookmarkReadOnlyLoading = false, bookmarkReadOnlyError = null) }
    }

    fun closeBookmarkedReadOnlyMessage() {
        ++bookmarkReadOnlyRevision
        bookmarkReadOnlyJob?.cancel()
        bookmarkReadOnlyJob = null
        clearBookmarkReadOnlyIntent()
    }

    fun retryBookmarkedReadOnlyMessage() {
        val current = _state.value
        val id = current.bookmarkReadOnlyId ?: return
        if (!current.isReady) return
        val branch = current.bookmarkReadOnlyBranchId
        if (branch.isNullOrBlank() || branch != currentBranchId()) {
            closeBookmarkedReadOnlyMessage()
            return
        }
        if (bookmarkReadOnlyJob?.isActive == true) return
        val revision = ++bookmarkReadOnlyRevision
        val launched = launchBranchTransition {
            _state.update { it.copy(bookmarkReadOnlyLoading = true, bookmarkReadOnlyError = null, bookmarkLocatingId = id) }
            fun isCurrent() = bookmarkReadOnlyRevision == revision && _state.value.isReady &&
                _state.value.bookmarkReadOnlyId == id && currentBranchId() == branch
            try {
                // Restoration is always a session-scoped read. Ordinary bookmark navigation can switch lines.
                val message = messageDao.getByIdInSession(id, sessionId)
                if (!isCurrent()) return@launchBranchTransition
                if (message == null || message.sessionId != sessionId) {
                    clearBookmarkReadOnlyIntent()
                    _state.update { it.copy(error = "收藏原文已删除，阅读已关闭") }
                } else {
                    _state.update { it.copy(bookmarkReadOnlyMessage = message) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (isCurrent()) _state.update { it.copy(bookmarkReadOnlyError = "收藏原文读取失败，请重试") }
            } finally {
                if (bookmarkReadOnlyRevision == revision) _state.update { it.copy(bookmarkReadOnlyLoading = false,
                    bookmarkLocatingId = if (it.bookmarkLocatingId == id) null else it.bookmarkLocatingId) }
            }
        }
        if (launched) bookmarkReadOnlyJob = branchTransitionJob
        else _state.update { it.copy(bookmarkReadOnlyLoading = false,
            bookmarkReadOnlyError = "当前正在生成或切换故事线，请稍后重试") }
    }

    private suspend fun loadMessageWindow(
        branchId: String,
        messageId: Long,
        windowRevision: Long = messageWindowRevision.get(),
    ): Boolean {
        val target = getVisibleMessage(branchId, messageId) ?: return false
        val radius = INITIAL_MESSAGE_WINDOW_SIZE / 2
        val beforeRows = getMessagesBefore(branchId, target.id, radius + 1)
        val afterRows = getMessagesAfter(branchId, target.id, radius + 1)
        return applyHistoryWindow(
            branchId = branchId,
            messages = beforeRows.take(radius).asReversed() + target + afterRows.take(radius),
            hasOlderMessages = beforeRows.size > radius,
            hasNewerMessages = afterRows.size > radius,
            focusedMessageId = target.id,
            windowRevision = windowRevision,
        )
    }

    fun clearFocusedMessage() {
        savedStateHandle.remove<Long>("history_window_focus_$sessionId")
        _state.update { it.copy(focusedMessageId = null) }
    }

    fun rememberMessageReadingPosition(branchId: String, endId: Long?, size: Int, anchorId: Long) {
        val current = _state.value
        if (!current.isReady || current.isLoadingHistory || branchTransitionJob?.isActive == true ||
            current.currentBranchId != branchId || current.messages.lastOrNull()?.id != endId ||
            current.messages.size != size || current.messages.none { it.id == anchorId }) return
        saveHistoryWindowIntent(current, anchorId)
    }

    // The window is a bounded read intent, separate from the one-shot focus command.
    // No message bodies enter SavedStateHandle; paging replaces this cursor and capacity.
    private fun saveHistoryWindowIntent(current: ChatContract.State, requestedAnchorId: Long? = current.focusedMessageId) {
        if (sourceMessageId > 0L) savedStateHandle["history_navigation_branch_$sessionId"] = current.currentBranchId
        val end = current.messages.lastOrNull()?.id
        val sameBranch = savedStateHandle.get<String>("history_window_branch_$sessionId") == current.currentBranchId
        val anchor = requestedAnchorId ?: if (sameBranch)
            savedStateHandle.get<Long>("history_window_anchor_$sessionId") else null
        val retainedAnchor = anchor?.takeIf { id -> current.messages.any { it.id == id } }
        if (end == null || end == Long.MAX_VALUE ||
            (!current.hasNewerMessages && current.messages.size <= INITIAL_MESSAGE_WINDOW_SIZE && retainedAnchor == null)) {
            clearHistoryWindowIntent()
            return
        }
        savedStateHandle["history_window_branch_$sessionId"] = current.currentBranchId
        savedStateHandle["history_window_end_$sessionId"] = end
        savedStateHandle["history_window_size_$sessionId"] = current.messages.size.coerceAtMost(MAX_MESSAGE_WINDOW_SIZE)
        if (retainedAnchor != null && current.messages.any { it.id == retainedAnchor &&
                !it.swipeGroupId.isNullOrBlank() && !it.includeInContext }) {
            clearHistoryWindowIntent()
            return
        }
        if (retainedAnchor != null) savedStateHandle["history_window_anchor_$sessionId"] = retainedAnchor
        else savedStateHandle.remove<Long>("history_window_anchor_$sessionId")
        if (current.focusedMessageId != null) savedStateHandle["history_window_focus_$sessionId"] = current.focusedMessageId
        else savedStateHandle.remove<Long>("history_window_focus_$sessionId")
    }

    private fun clearHistoryWindowIntent() {
        savedStateHandle.remove<String>("history_window_branch_$sessionId")
        savedStateHandle.remove<Long>("history_window_end_$sessionId")
        savedStateHandle.remove<Int>("history_window_size_$sessionId")
        savedStateHandle.remove<Long>("history_window_anchor_$sessionId")
        savedStateHandle.remove<Long>("history_window_focus_$sessionId")
    }

    fun showSavedImage(): Boolean {
        val notice = _state.value.savedImageNotice ?: return false
        if (currentBranchId() != notice.branchId) return false
        return launchHistoryLoad { branchId, windowRevision ->
            val found = loadMessageWindow(branchId, notice.messageId, windowRevision)
            _state.update {
                if (it.savedImageNotice != notice || messageWindowRevision.get() != windowRevision) it else it.copy(
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

    private data class ImageRetrySnapshot(
        val token: String,
        val prompt: String,
        val branchId: String,
        val draftRevision: Long,
        val targetsResolved: Boolean = false,
        val characterId: Long? = null,
        val worldId: Long? = null,
        val encyclopediaId: Long? = null,
    )

    private var imageRetrySnapshot: ImageRetrySnapshot? = null

    private fun clearImageRetry() {
        imageRetrySnapshot = null
        _state.update { it.copy(imageRetryNotice = null) }
    }

    fun dismissImageRetry(token: String) {
        if (_state.value.imageRetryNotice?.token == token && imageRetrySnapshot?.token == token) clearImageRetry()
    }

    fun retryFailedImage(token: String): Boolean {
        val snapshot = imageRetrySnapshot ?: return false
        if (_state.value.imageRetryNotice?.token != token || snapshot.token != token ||
            snapshot.branchId != currentBranchId()) return false
        return generateManualImage(snapshot)
    }

    private fun failManualImage(snapshot: ImageRetrySnapshot, message: String) {
        if (imageRetrySnapshot?.token != snapshot.token || currentBranchId() != snapshot.branchId) return
        _state.update { it.copy(error = null, imageRetryNotice = ImageRetryNotice(snapshot.token, message)) }
    }

    fun generateAndAttachUserMessage(prompt: String): Boolean {
        if (prompt.isBlank()) return false
        return generateManualImage(ImageRetrySnapshot(UUID.randomUUID().toString(), prompt, currentBranchId(), imageDraftRevision))
    }

    private fun generateManualImage(original: ImageRetrySnapshot): Boolean {
        val prompt = original.prompt
        val draftRevision = original.draftRevision
        return launchSingleGeneration imageGeneration@{ generation ->
            var snapshot = original.copy(token = UUID.randomUUID().toString())
            imageRetrySnapshot = snapshot
            try {
                val firstParticipant = participantDao.getBySession(sessionId).firstOrNull()
                val char = firstParticipant?.characterId?.let { characterDao.getById(it) }
                val world = sessionWorldDao.getBySession(sessionId)
                generation.ensureCurrent()
                if (original.targetsResolved && (original.characterId != char?.id || original.worldId != world?.id ||
                    original.encyclopediaId != world?.encyclopediaId)) {
                    clearImageRetry()
                    _state.update { it.copy(error = "配图对象已改变，请重新生成") }
                    return@imageGeneration
                }
                snapshot = snapshot.copy(targetsResolved = true, characterId = char?.id, worldId = world?.id, encyclopediaId = world?.encyclopediaId)
                imageRetrySnapshot = snapshot
                val primary = ApiKeyResolver.resolveImageGenPrimaryResolved(char, world, secureStorage)
                if (primary.apiKey.isBlank()) {
                    UsbSessionLog.w(
                        "ChatImageGen",
                        "user image gen: missing key sid=$sessionId model=${primary.model}",
                    )
                    failManualImage(snapshot, UserFacingStrings.imageGenKeyMissing())
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
                                failManualImage(snapshot, UserFacingStrings.imageSaveFailed())
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
                                clearImageRetry()
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
                                "user image gen failed sid=$sessionId model=${attempt.modelUsed} type=${e.javaClass.simpleName}",
                            )
                            failManualImage(snapshot, UserFacingStrings.streamErrorDetail(e.message))
                        }
                    )
                } finally {
                    if (!committed) rollbackPendingMedia(insertedMessageId, generatedPath)
                }
            } catch (e: CancellationException) {
                if (imageRetrySnapshot?.token == snapshot.token) clearImageRetry()
                throw e
            } catch (e: Exception) {
                UsbSessionLog.e("ChatImageGen", "user image gen exception sid=$sessionId type=${e.javaClass.simpleName}")
                generation.ensureCurrent()
                failManualImage(snapshot, UserFacingStrings.streamErrorDetail(e.message))
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
    private var speechRevision = 0L
    private var speechScreenAttached = false

    fun attachSpeechScreen() {
        speechScreenAttached = true
    }

    fun detachSpeechScreen() {
        speechScreenAttached = false
        stopSpeaking()
    }

    private fun canStartSpeech(): Boolean = speechScreenAttached &&
        branchTransitionJob?.isActive != true && !_state.value.voiceSelectionSaving
    private data class SpeechRequest(
        val revision: Long,
        val token: String,
        val text: String,
        val sessionId: Long,
        val branchId: String,
        val characterId: Long?,
        val control: SpeechPlaybackControl = SpeechPlaybackControl(),
    )
    private data class SpeechRetrySnapshot(
        val token: String,
        val text: String,
        val sessionId: Long,
        val branchId: String,
        val characterId: Long?,
        val choice: com.mojing.app.data.VoiceChoice,
        val source: String,
        val attachmentMessageId: Long? = null,
    )
    private var speechRetrySnapshot: SpeechRetrySnapshot? = null
    private val _speechActive = MutableStateFlow(false)
    val speechActive: StateFlow<Boolean> = _speechActive.asStateFlow()
    private var speechControl: SpeechPlaybackControl? = null
    private val _speechPlayback = MutableStateFlow(SpeechPlaybackControl.Snapshot())
    val speechPlayback: StateFlow<SpeechPlaybackControl.Snapshot> = _speechPlayback.asStateFlow()

    fun pauseSpeaking() {
        if (canStartSpeech() && speechJob?.isActive == true) speechControl?.pause()
    }

    fun resumeSpeaking() {
        if (canStartSpeech() && speechJob?.isActive == true) speechControl?.resume()
    }

    fun stopSpeaking(interruptAutomaticSynthesis: Boolean = false) {
        val ownsManualPlayback = speechJob != null || speechControl != null || _speechActive.value
        speechRevision++
        speechControl?.close()
        speechControl = null
        _speechPlayback.value = SpeechPlaybackControl.Snapshot()
        speechJob?.cancel()
        speechJob = null
        _speechActive.value = false
        speechRetrySnapshot = null
        _state.update { it.copy(speechVoiceRequestLabel = "", speechRetryNotice = null) }
        if (ownsManualPlayback || interruptAutomaticSynthesis) {
            AndroidTts.stop()
            com.mojing.app.media.TtsPlayer.stop()
        }
    }

    fun dismissSpeechRetry(token: String) {
        val notice = _state.value.speechRetryNotice
        if (notice?.token == token && speechRetrySnapshot?.token == notice.snapshotToken) {
            speechRetrySnapshot = null
            _state.update { it.copy(speechRetryNotice = null) }
        }
    }

    fun retryFailedSpeech(token: String) {
        if (!canStartSpeech()) return
        val snapshot = speechRetrySnapshot ?: return
        val notice = _state.value.speechRetryNotice ?: return
        if (notice.token != token || notice.snapshotToken != snapshot.token ||
            snapshot.sessionId != sessionId || snapshot.branchId != currentBranchId()) return
        if (speechJob?.isActive == true || _speechActive.value) return
        speakSnapshot(snapshot)
    }

    fun currentVoiceChoice(): com.mojing.app.data.VoiceChoice =
        com.mojing.app.data.VoicePreferences(appContext).sessionSelection(sessionId)

    fun selectVoiceChoice(choice: com.mojing.app.data.VoiceChoice, onSaved: () -> Unit) {
        if (_state.value.voiceSelectionSaving) return
        stopSpeaking()
        _state.update { it.copy(voiceSelectionSaving = true, voiceSelectionError = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { com.mojing.app.data.VoicePreferences(appContext).saveSession(sessionId, choice) }
                onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(voiceSelectionError = "语音选择未保存，请重试") } }
            finally { _state.update { it.copy(voiceSelectionSaving = false) } }
        }
    }

    fun speakMessage(text: String, characterId: Long? = null) {
        if (!canStartSpeech()) return
        stopSpeaking(interruptAutomaticSynthesis = true)
        val request = SpeechRequest(
            revision = ++speechRevision,
            token = UUID.randomUUID().toString(),
            text = text,
            sessionId = sessionId,
            branchId = currentBranchId(),
            characterId = characterId,
        )
        speechControl = request.control
        _speechPlayback.value = SpeechPlaybackControl.Snapshot(phase = SpeechPlaybackControl.Phase.PREPARING)
        _speechActive.value = true
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                withSpeechPlayback(request) {
                    val cleaned = withContext(preparationDispatcher) { TtsSpeakText.normalizeForSpeech(request.text) }
                    if (!isCurrentSpeechRequest(request)) return@withSpeechPlayback
                    if (cleaned.isBlank()) {
                        _state.update { it.copy(error = UserFacingStrings.ttsContentEmptyAfterClean()) }
                        return@withSpeechPlayback
                    }
                    val char = characterId?.let { characterDao.getById(it) }
                    if (!isCurrentSpeechRequest(request)) return@withSpeechPlayback
                    val preferences = com.mojing.app.data.VoicePreferences(appContext)
                    val sessionSelection = preferences.sessionSelection(sessionId)
                    val sessionChoice = sessionSelection.takeUnless { it.engineId == "inherit" } ?: preferences.global()
                    val choice = com.mojing.app.data.resolveVoiceChoice(char?.voiceProvider, char?.voiceModel, sessionChoice)
                    val source = when {
                        com.mojing.app.data.characterHasOwnVoice(char?.voiceProvider) -> "角色设置"
                        sessionSelection.engineId != "inherit" -> "对话设置"
                        else -> "全局设置"
                    }
                    val snapshot = SpeechRetrySnapshot(
                        token = request.token,
                        text = cleaned,
                        sessionId = request.sessionId,
                        branchId = request.branchId,
                        characterId = request.characterId,
                        choice = choice,
                        source = source,
                    )
                    if (!isCurrentSpeechRequest(request)) return@withSpeechPlayback
                    speechRetrySnapshot = snapshot
                    playSpeech(request, snapshot)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (isCurrentSpeechRequest(request)) {
                    _state.update { it.copy(error = UserFacingStrings.remoteRequestFailed(e)) }
                }
            }
            finally {
                if (speechJob === coroutineContext[Job]) {
                    speechJob = null
                    speechControl = null
                    _speechPlayback.value = SpeechPlaybackControl.Snapshot()
                    _speechActive.value = false
                    _state.update { it.copy(speechVoiceRequestLabel = "") }
                }
            }
        }
        speechJob = job
        job.start()
    }

    private fun speakSnapshot(snapshot: SpeechRetrySnapshot) {
        stopSpeaking(interruptAutomaticSynthesis = true)
        val request = SpeechRequest(
            revision = ++speechRevision,
            token = snapshot.token,
            text = snapshot.text,
            sessionId = snapshot.sessionId,
            branchId = snapshot.branchId,
            characterId = snapshot.characterId,
        )
        speechRetrySnapshot = snapshot
        speechControl = request.control
        _speechPlayback.value = SpeechPlaybackControl.Snapshot(phase = SpeechPlaybackControl.Phase.PREPARING)
        _speechActive.value = true
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                withSpeechPlayback(request) { playSpeech(request, snapshot) }
            } catch (e: CancellationException) { throw e }
            finally {
                if (speechJob === coroutineContext[Job]) {
                    speechJob = null
                    speechControl = null
                    _speechPlayback.value = SpeechPlaybackControl.Snapshot()
                    _speechActive.value = false
                    _state.update { it.copy(speechVoiceRequestLabel = "") }
                }
            }
        }
        speechJob = job
        job.start()
    }

    private fun isCurrentSpeechRequest(request: SpeechRequest): Boolean =
        canStartSpeech() && speechRevision == request.revision &&
            request.sessionId == sessionId &&
            request.branchId == currentBranchId()

    /** Progress collection is a child of the existing speech job, never a second playback owner. */
    private suspend fun withSpeechPlayback(request: SpeechRequest, play: suspend () -> Unit) = coroutineScope {
        val progress = launch(start = CoroutineStart.UNDISPATCHED) {
            request.control.snapshot.collect { snapshot ->
                if (isCurrentSpeechRequest(request) && speechControl === request.control) {
                    _speechPlayback.value = if (snapshot.phase == SpeechPlaybackControl.Phase.IDLE) {
                        snapshot.copy(phase = SpeechPlaybackControl.Phase.PREPARING)
                    } else snapshot
                }
            }
        }
        try { play() }
        finally {
            progress.cancel()
            request.control.close()
        }
    }

    private suspend fun playSpeech(request: SpeechRequest, snapshot: SpeechRetrySnapshot) {
        if (!isCurrentSpeechRequest(request)) return
        _state.update { it.copy(speechRetryNotice = null, speechVoiceRequestLabel =
            if (snapshot.attachmentMessageId != null) "正在播放语音附件" else "已请求${snapshot.source}：${snapshot.choice.label()}") }
        try {
            val ok = if (snapshot.attachmentMessageId != null) {
                playStoredVoiceAttachments(request, snapshot.attachmentMessageId)
            } else if (snapshot.choice.engineId == "azure") {
                val preferences = com.mojing.app.data.VoicePreferences(appContext)
                val (region, key) = withContext(Dispatchers.IO) { preferences.azureRegion to preferences.azureKey }
                if (!isCurrentSpeechRequest(request)) return
                com.mojing.app.media.AzureSpeech.speak(appContext, snapshot.text, region, key, snapshot.choice.voiceId, request.control)
            } else {
                if (!isCurrentSpeechRequest(request)) return
                AndroidTts.speakAwaitCompletion(appContext, snapshot.text, snapshot.choice, request.control)
            }
            if (!ok) failSpeech(request, snapshot, when {
                snapshot.attachmentMessageId != null -> "语音附件未播放完成，请重试"
                snapshot.choice.engineId == "azure" -> "语音播放未完成，请重试"
                else -> "系统朗读未完成，请重试"
            })
            else if (isCurrentSpeechRequest(request) && speechRetrySnapshot?.token == snapshot.token) {
                speechRetrySnapshot = null
                _state.update { it.copy(speechRetryNotice = null) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.mojing.app.media.AzureSpeech.SpeechException) {
            failSpeech(request, snapshot, com.mojing.app.media.AzureSpeech.failureMessage(e))
        } catch (e: IllegalStateException) {
            failSpeech(request, snapshot, e.message ?: "系统朗读失败，请重试")
        } catch (e: Exception) {
            failSpeech(request, snapshot, UserFacingStrings.remoteRequestFailed(e))
        }
    }

    private fun failSpeech(request: SpeechRequest, snapshot: SpeechRetrySnapshot, message: String) {
        if (!isCurrentSpeechRequest(request) || speechRetrySnapshot?.token != snapshot.token) return
        _state.update {
            it.copy(speechRetryNotice = SpeechRetryNotice(
                token = UUID.randomUUID().toString(),
                snapshotToken = snapshot.token,
                message = message,
            ))
        }
    }

    fun playVoiceAttachments(messageId: Long) {
        if (!canStartSpeech()) return
        speakSnapshot(SpeechRetrySnapshot(
            token = UUID.randomUUID().toString(), text = "语音附件", sessionId = sessionId,
            branchId = currentBranchId(), characterId = null,
            choice = com.mojing.app.data.VoiceChoice("system"), source = "语音附件",
            attachmentMessageId = messageId,
        ))
    }

    private suspend fun playStoredVoiceAttachments(request: SpeechRequest, messageId: Long): Boolean {
        val message = getVisibleMessage(request.branchId, messageId) ?: return false
        if (message.sessionId != sessionId || !isCurrentSpeechRequest(request)) return false
        val parts = attachmentDao.getByMessage(messageId).filter { it.assetType == "voice" && it.mimeType.startsWith("audio/") }
        if (parts.isEmpty() || !isCurrentSpeechRequest(request)) return false
        var handle: com.mojing.app.media.TtsPlayer.PlaybackHandle? = null
        val control = request.control
        val lease = control.bind(
            onPause = { handle?.let { com.mojing.app.media.TtsPlayer.pause(it) } },
            onResume = { handle?.let { com.mojing.app.media.TtsPlayer.resume(it) } },
            onClose = { handle?.let { com.mojing.app.media.TtsPlayer.stop(it) } },
        ) ?: return false
        try {
            for ((index, part) in parts.withIndex()) {
                if (!isCurrentSpeechRequest(request) || !control.awaitResume(lease)) return false
                val file = java.io.File(part.storagePath)
                if (!withContext(Dispatchers.IO) { file.isFile && file.length() > 0L }) return false
                if (!isCurrentSpeechRequest(request) || !control.owns(lease)) return false
                control.updateIfOwned(lease) { it.copy(phase = SpeechPlaybackControl.Phase.PREPARING, segmentIndex = index + 1, segmentCount = parts.size) }
                val current = com.mojing.app.media.TtsPlayer.playOwned(file, deleteWhenFinished = false,
                    initialPaused = control.isPaused(), onPhaseChanged = { phase ->
                        control.updateIfOwned(lease) { it.copy(phase = when (phase) {
                            com.mojing.app.media.TtsPlayer.Phase.PREPARING -> SpeechPlaybackControl.Phase.PREPARING
                            com.mojing.app.media.TtsPlayer.Phase.PLAYING -> SpeechPlaybackControl.Phase.PLAYING
                            com.mojing.app.media.TtsPlayer.Phase.PAUSED -> SpeechPlaybackControl.Phase.PAUSED
                        }) }
                    }) ?: return false
                handle = current
                if (!com.mojing.app.media.TtsPlayer.awaitCompletion(current)) return false
            }
            return true
        } finally {
            handle?.let { com.mojing.app.media.TtsPlayer.stop(it) }
            control.unbind(lease)
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
        val selectedManualCharacterId = _state.value.manualReplyCharacterId
        if (participants.isEmpty()) {
            if (secureStorage.speakerTurnMode == "manual" && selectedManualCharacterId != null) {
                clearInvalidManualSpeakerSelection(selectedManualCharacterId)
                refreshMessagesUi()
                return false
            }
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
                if (participants.none { it.characterId == manualCharId } || !charactersById.containsKey(manualCharId)) {
                    clearInvalidManualSpeakerSelection(manualCharId)
                    refreshMessagesUi()
                    return false
                }
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
                characterSummaries = presentation.summaries,
                characterCardImages = presentation.cardImages,
                characterColors = presentation.colors,
            )
            refreshMessagesUi()
        }
        val orderedCharacters = pick.characterIds.distinct().mapNotNull { characterDao.getById(it) }
        if (orderedCharacters.isEmpty()) {
            if (manualMode && manualCharId != null) {
                clearInvalidManualSpeakerSelection(manualCharId)
            } else {
                _state.value = _state.value.copy(error = UserFacingStrings.chatNoParticipant())
            }
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
        if (rejectDuringMediaBundle()) return
        val current = _state.value
        if (!current.isReady || current.sessionNotFound) return
        if (rejectPendingReplyRecovery()) return
        if (!canContinueFromCurrentWindow()) return
        if (current.memoryOperationRunning) {
            _state.update { it.copy(error = "记忆整理中，请稍候再发送") }
            return
        }
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
        if (characterStateClearJob?.isActive == true) {
            _state.update { it.copy(error = "角色状态正在清除，请稍后再发送") }
            return
        }
        if (current.quotingMessage != null && current.quotingSnippet == null) {
            _state.update { it.copy(error = "引用正文正在准备，请稍候再发送") }
            return
        }
        val draftSubmissionId = UUID.randomUUID().toString()
        if (rejectPendingWorldWrite()) return
        if (!beginDraftSubmission(draftSubmissionId)) return
        val quote = current.quotingMessage
        val submittedQuoteRevision = quoteDraftRevision
        val quotedPrefix = quote?.let { q ->
            val label = when (q.speakerType) {
                "user" -> secureStorage.userName.ifBlank { "?" }
                "narrator" -> _state.value.world?.narratorName?.ifBlank { "\u65c1\u767d" } ?: "\u65c1\u767d"
                else -> q.characterId?.let { _state.value.characterNames[it] } ?: "\u89d2\u8272"
            }
            val snippet = current.quotingSnippet.orEmpty()
            if (snippet.isNotEmpty()) "> $label：$snippet\n\n" else "> $label\n\n"
        }.orEmpty()
        val outboundText = quotedPrefix + text
        if (current.world?.gameplayMode == "小说创作" && pendingImageLocalPaths.isEmpty()) {
            val launched = requestNarrator(
                guidance = outboundText,
                draftSubmissionId = draftSubmissionId,
                onGuidanceCommitted = {
                    _state.update { state ->
                        state.copy(
                            inputText = if (activeDraftSubmissionId == draftSubmissionId) "" else state.inputText,
                            quotingMessage = if (quoteDraftRevision == submittedQuoteRevision) null else state.quotingMessage,
                            quotingSnippet = if (quoteDraftRevision == submittedQuoteRevision) null else state.quotingSnippet,
                        )
                    }
                    finishDraftSubmission(draftSubmissionId)
                },
            )
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
                if (!validateManualSpeakerSelectionBeforeSubmission()) {
                    finishDraftSubmission(draftSubmissionId)
                    return@sendGeneration
                }
                val displayContent = when {
                    outboundText.isNotBlank() -> outboundText
                    text.isNotBlank() -> text
                    else -> "[\u56fe\u7247]"
                }
                UsbSessionLog.i(
                    "ChatSend",
                    "session=$sessionId branch=${generation.branchId} textLen=${displayContent.length} images=${pendingImageLocalPaths.size}",
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
                        quotingSnippet = if (quoteDraftRevision == submittedQuoteRevision) null else current.quotingSnippet,
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

    /** A selected manual speaker must still be a live participant before the user message is committed. */
    private suspend fun validateManualSpeakerSelectionBeforeSubmission(): Boolean {
        if (secureStorage.speakerTurnMode != "manual") return true
        val selectedId = _state.value.manualReplyCharacterId ?: return true
        val isCurrentParticipant = participantDao.getBySession(sessionId).any { it.characterId == selectedId }
        val characterStillExists = characterDao.getById(selectedId) != null
        if (_state.value.manualReplyCharacterId != selectedId) {
            _state.update { it.copy(error = "发言角色已变更，请重新发送") }
            return false
        }
        if (isCurrentParticipant && characterStillExists) return true
        clearInvalidManualSpeakerSelection(selectedId)
        return false
    }

    private fun clearInvalidManualSpeakerSelection(selectedId: Long) {
        _state.update { state ->
            if (state.manualReplyCharacterId != selectedId) {
                state
            } else {
                state.copy(
                    manualReplyCharacterId = null,
                    participants = state.participants.filterNot { it.characterId == selectedId },
                    error = "选中的发言角色已移出当前对话，请重新选择",
                )
            }
        }
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

    /** Legacy soft history quota only, never evidence of provider capacity. */
    private fun getModelSoftContext(model: String): Int {
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

        val contextWindow = requestPlatform()?.modelContextWindows?.get(model)
            ?: com.mojing.app.domain.config.ModelRequestSettingsResolver.contextWindow(
                generation.modelPlatforms, apiKey, preStreamBase, model)

        if (runMemoryCompact) {
            val compactThreshold = secureStorage.memoryCompactThreshold.coerceIn(10, 2000)
            val compacted = try { memoryCompactor.compactIfNeeded(
                sessionId = sessionId,
                branchId = branchId,
                apiKey = apiKey,
                baseUrl = preStreamBase,
                model = model,
                threshold = compactThreshold,
                contextWindow = contextWindow,
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
                    refreshMemorySummaryPage(branchId)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 摘要已保存，已打开的列表保留重试入口。 */ }
            }
        }

        val currentState = characterStateDao.getBySessionAndCharacter(sessionId, character.id, branchId)
        val lastAttemptId = currentState?.lastSnapshotAttemptUserMessageId ?: 0L
        val recentUserIds = if (branchId == "main") {
            messageDao.getRecentMainUserContextIdsAfter(sessionId, lastAttemptId, CharacterSnapshotCadence.INTERVAL)
        } else {
            messageDao.getRecentVisibleUserContextIdsAfter(sessionId, branchId, lastAttemptId, CharacterSnapshotCadence.INTERVAL)
        }
        val attemptUserMessageId = CharacterSnapshotCadence.dueUserMessageId(recentUserIds)
        var snapshot = attemptUserMessageId?.let {
            snapshotExtractor.extract(allMessages, character, apiKey, preStreamBase, model, contextWindow = contextWindow)
        }
        if (attemptUserMessageId != null) {
            generation.ensureCurrent()
            val updatedState = currentState?.copy(
                lastSnapshotAttemptUserMessageId = attemptUserMessageId,
                snapshotIsValid = snapshot != null || currentState.snapshotIsValid,
                dynamicStateJson = snapshot?.let { com.google.gson.Gson().toJson(it) } ?: currentState.dynamicStateJson,
                emotionalState = snapshot?.mood ?: currentState.emotionalState,
                updatedAt = System.currentTimeMillis(),
            ) ?: com.mojing.app.data.local.entity.SessionCharacterStateEntity(
                sessionId = sessionId, characterId = character.id, branchId = branchId,
                lastSnapshotAttemptUserMessageId = attemptUserMessageId,
                snapshotIsValid = snapshot != null,
                dynamicStateJson = snapshot?.let { com.google.gson.Gson().toJson(it) } ?: "{}",
                emotionalState = snapshot?.mood.orEmpty(),
            )
            characterStateDao.upsert(updatedState)
        }
        if (snapshot == null && currentState != null && currentState.snapshotIsValid && currentState.dynamicStateJson.isNotBlank() && currentState.dynamicStateJson != "{}") {
            try {
                snapshot = com.google.gson.Gson().fromJson(
                    currentState.dynamicStateJson,
                    com.mojing.app.domain.engine.CharacterSnapshot::class.java,
                )
            } catch (_: Exception) { }
        }

        val budget = tokenBudgetManager.calculateBudget(
            contextWindow ?: getModelSoftContext(model), character.maxTokens, contextWindow,
        )
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
            automaticSummary = contextWindow != null && contextBuilder.areAutomaticSummaries(memorySegments),
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
        var contextLimitError = false
        var receivedText = ""
        generation.interruptedReplySpeakerType = "character"
        generation.interruptedReplyCharacterId = character.id
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
                platformId = requestPlatform()?.id,
                promptDocument = context.promptDocument,
            ).collect { s ->
            when (s) {
                is StreamState.Generating -> {
                    generation.ensureCurrent()
                    val now = System.currentTimeMillis()
                    receivedText = s.partialText
                    generation.interruptedReplyText = s.partialText
                    checkpointReplyRecovery(generation, receivedText, "character", character.id)
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
                            "session=$sessionId done model=${model.trim()} rawLen=${s.fullText.length}",
                        )
                        val parsed = CharacterMediaMarkers.parse(s.fullText)
                        var displayContent = parsed.displayText.trim()
                        displayContent = OutputProcessor.normalizeOptions(displayContent)
                        UsbSessionLog.i(
                            "ChatLlm",
                            "session=$sessionId displayLen=${displayContent.length}",
                        )
                        if (displayContent.isBlank()) {
                            streamErrorMessage = "模型返回了空内容"
                            return@collect
                        }
                        generation.interruptedReplyText = s.fullText
                        checkpointReplyRecovery(generation, s.fullText, "character", character.id, force = true)
                        val recoveryToken = generation.replyRecovery?.token
                        val reply = MessageEntity(
                            sessionId = sessionId,
                            speakerType = "character",
                            characterId = character.id,
                            content = displayContent,
                            structuredContentJson = ReplyGenerationMetadata.record(
                                structuredContentJsonFor(displayContent),
                                (System.nanoTime() - replyStartedAt) / 1_000_000, s.usage,
                            ).let { metadata -> recoveryToken?.let { ReplyRecoveryMetadata.withToken(metadata, it) } ?: metadata },
                            branchId = branchId,
                            swipeGroupId = gid,
                            includeInContext = true,
                        )
                        val replyMessageId = withContext(NonCancellable) {
                            val id = if (gid == null) {
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
                            generation.interruptedReplyText = ""
                            clearCommittedReplyRecovery(generation)
                            id
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
                                contextWindow = contextWindow,
                                worldText = world?.worldPrompt.orEmpty(),
                                activeCharacterNames = _state.value.characterNames.values.toList(),
                            )
                            publishContextMemoryResult(branchId, memoryResult)
                            memoryV2Manager.extractEventNodes(
                                sessionId = sessionId,
                                branchId = branchId,
                                characterId = character.id,
                                messages = msgs,
                                apiKey = apiKey,
                                baseUrl = llmHookBase,
                                model = model,
                                contextWindow = contextWindow,
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
                                    model = model,
                                    contextWindow = contextWindow,
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
                    contextLimitError = s.contextLimit
                    UsbSessionLog.w(
                        "ChatLlm",
                        "session=$sessionId error model=${model.trim()} messageLen=${s.message.length}",
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
                if (contextLimitError || partial.isNotEmpty() || idx >= streamBases.lastIndex) break
            }
        }
        }
        if (!sawDone && streamErrorMessage != null) {
            if (contextLimitError) {
                _state.update { it.copy(streamingText = "", contextBudgetError = streamErrorMessage) }
                return false
            }
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
        speakerType: String?,
        characterId: Long?,
        allowCancelledOwner: Boolean = false,
    ): Boolean {
        val resolvedSpeakerType = speakerType ?: return false
        var content = com.mojing.app.domain.engine.InterruptedReply.normalize(text)
        val world = _state.value.world
        if (resolvedSpeakerType == "narrator" && world?.gameplayMode == "小说创作") {
            content = StoryCanon.sanitizeMessageChoices(content, world.worldPrompt)
        }
        if (content.isBlank()) return false
        if (allowCancelledOwner) check(activeGeneration === generation) { "stale generation" }
        else generation.ensureCurrent()
        checkpointReplyRecovery(generation, text, resolvedSpeakerType, characterId, force = true)
        val recoveryToken = generation.replyRecovery?.token
        val reply = MessageEntity(
            sessionId = sessionId,
            branchId = generation.branchId,
            speakerType = resolvedSpeakerType,
            characterId = characterId,
            content = content,
            structuredContentJson = structuredContentJsonFor(content).let { metadata ->
                recoveryToken?.let { ReplyRecoveryMetadata.withToken(metadata, it) } ?: metadata
            },
            swipeGroupId = generation.swipeGroupId,
            includeInContext = true,
        )
        withContext(NonCancellable) {
            if (generation.swipeGroupId == null) {
                messageDao.insert(reply)
            } else {
                messageDao.insertAndSelectSwipeVariant(
                    entity = reply, branchId = generation.branchId,
                    targetMessageId = requireNotNull(generation.swipeSourceMessageId),
                )
            }
            generation.interruptedReplyText = ""
            clearCommittedReplyRecovery(generation)
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
        cancelPendingAutoNarrator()
        val world = _state.value.world ?: return
        if (!world.narratorEnabled) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            try {
                val msgs = getContextMessagesForBranch(branchId)
                val lastNarratorIdx = msgs.indexOfLast { it.speakerType == "narrator" }
                val since = if (lastNarratorIdx < 0) msgs.size else msgs.size - lastNarratorIdx - 1
                if (!narratorEngine.shouldNarrate(world, since)) return@launch
                kotlinx.coroutines.delay(450)
                if (autoNarratorJob !== owner || _state.value.isGenerating ||
                    currentBranchId() != branchId || branchTransitionJob?.isActive == true ||
                    _state.value.world?.narratorEnabled != true) return@launch
                // The next generation may cancel pending narration, but must not cancel its own caller.
                autoNarratorJob = null
                requestNarrator("")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (autoNarratorJob === owner) {
                    _state.update { it.copy(error = "自动旁白准备失败，可手动请求旁白") }
                }
            } finally {
                if (autoNarratorJob === owner) autoNarratorJob = null
            }
        }
        autoNarratorJob = job
        job.start()
    }

    fun cancelPendingAutoNarrator() {
        val pending = autoNarratorJob
        autoNarratorJob = null
        pending?.cancel()
    }

    private fun shouldPromoteNarratorPublicBases(world: SessionWorldEntity): Boolean {
        val w = world.sessionLlmBaseUrl.trim()
        return w.isEmpty() || isPlaceholderApiBase(w)
    }

    fun loadChapterInput(branchId: String): ChapterInputDraft = chatDraftStore.loadChapterInput(sessionId, branchId)

    fun saveChapterInput(branchId: String, title: String, direction: String, synchronous: Boolean = false): Boolean =
        runCatching { chatDraftStore.saveChapterInput(sessionId, branchId, ChapterInputDraft(title, direction), synchronous) }.getOrDefault(false)

    fun requestNextChapter(title: String, direction: String): Boolean {
        val branchId = currentBranchId()
        val input = ChapterInputDraft(title, direction)
        if (!saveChapterInput(branchId, title, direction, synchronous = true)) {
            _state.update { it.copy(error = "章节输入未能保存到本机，请检查存储空间") }
            return false
        }
        return requestNarrator(guidance = direction, nextChapter = true, chapterTitle = title,
            onChapterCommitted = {
                val cleared = runCatching { chatDraftStore.clearChapterInputIfMatching(sessionId, branchId, input) }.getOrDefault(false)
                if (!cleared && runCatching { chatDraftStore.loadChapterInput(sessionId, branchId) == input }.getOrDefault(false)) {
                    _state.update { it.copy(error = it.error ?: "章节已保存，但输入草稿未能清理") }
                }
            })
    }

    private fun chapterForkDraftOwner(branchId: String, messageId: Long): String =
        "chapter_fork:${UUID.nameUUIDFromBytes(branchId.toByteArray(Charsets.UTF_8))}:$messageId"

    fun loadChapterForkInput(branchId: String, messageId: Long): ChapterInputDraft =
        loadChapterInput(chapterForkDraftOwner(branchId, messageId))

    fun saveChapterForkInput(branchId: String, messageId: Long, title: String, direction: String, synchronous: Boolean): Boolean =
        saveChapterInput(chapterForkDraftOwner(branchId, messageId), title, direction, synchronous)

    fun requestChapterFork(messageId: Long, expectedBranchId: String, title: String, direction: String): Boolean {
        if (expectedBranchId != currentBranchId()) return false
        val input = ChapterInputDraft(title, direction)
        if (!saveChapterForkInput(expectedBranchId, messageId, title, direction, true)) {
            _state.update { it.copy(error = "章节输入未能保存到本机，请检查存储空间") }
            return false
        }
        var createdBranch: String? = null
        var ready = false
        return launchBranchTransition(navigationLabel = "正在创建续写故事线…", invalidateSpeech = true,
            onSuccess = {
                val child = createdBranch
                if (ready && child != null && currentBranchId() == child) {
                    if (!requestNarrator(nextChapter = true, chapterTitle = title, guidance = direction,
                            onChapterCommitted = {
                                listOf(child, chapterForkDraftOwner(expectedBranchId, messageId)).forEach { owner ->
                                    val cleared = runCatching { chatDraftStore.clearChapterInputIfMatching(sessionId, owner, input) }.getOrDefault(false)
                                    if (!cleared && runCatching { chatDraftStore.loadChapterInput(sessionId, owner) == input }.getOrDefault(false))
                                        _state.update { it.copy(error = it.error ?: "章节已保存，但输入草稿未能清理") }
                                }
                            })) _state.update { it.copy(error = "续写故事线已创建，请在本线目录继续未完成章节") }
                }
            }) forkTransition@{
            try {
                check(expectedBranchId == currentBranchId()) { "故事线已改变" }
                val original = getVisibleMessage(expectedBranchId, messageId)
                require(original != null && original.sessionId == sessionId && original.speakerType == "narrator" &&
                    original.content.isNotBlank() && NovelChapter.incomplete(original.structuredContentJson) &&
                    NovelChapter.number(original.structuredContentJson) != null) { "目标章节已不可续写" }
                val child = "chapter_${original.id}_${UUID.randomUUID().toString().take(12)}"
                val replacement = original.copy(id = 0, branchId = child,
                    regeneratedFromMessageId = original.id, createdAt = System.currentTimeMillis())
                val cloneId = sessionBranchDao.insertEditedBranch(SessionBranchEntity(sessionId = sessionId,
                    branchId = child, parentBranchId = expectedBranchId, sourceMessageId = original.id,
                    label = "续写：${NovelChapter.title(original.structuredContentJson).ifBlank { "第 ${NovelChapter.number(original.structuredContentJson)} 章" }.take(32)}"),
                    replacement, attachmentDao.getByMessage(original.id))
                createdBranch = child
                check(saveChapterInput(child, title, direction, synchronous = true))
                // Transfer this submission to the child; retries there must not leave a stale source editor draft.
                val draftOwner = chapterForkDraftOwner(expectedBranchId, messageId)
                val transferred = chatDraftStore.clearChapterInputIfMatching(sessionId, draftOwner, input)
                check(transferred || chatDraftStore.loadChapterInput(sessionId, draftOwner) != input)
                sessionDao.bumpUpdatedAt(sessionId)
                check(refreshMessagesUi(child, switchBranchOnSuccess = true) && currentBranchId() == child)
                uiPreferencesRepository.setLastChatBranch(sessionId, child)
                val tail = getMessageTailForBranch(child, 1).lastOrNull()
                check(tail?.id == cloneId && NovelChapter.canResumeTail(child, tail.branchId, tail.structuredContentJson))
                ready = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                _state.update { it.copy(error = if (createdBranch != null)
                    "续写故事线已创建，但尚未开始生成；请从故事线列表进入后继续未完成章节，输入已保留"
                    else "章节已改变或故事线创建失败，输入已保留，请重新打开目录重试") }
            }
        }
    }

    fun requestNarrator(
        guidance: String = "",
        expectedTailMessageId: Long? = null,
        draftSubmissionId: String? = null,
        nextChapter: Boolean = false,
        chapterTitle: String = "",
        onGuidanceCommitted: (() -> Unit)? = null,
        onChapterCommitted: (() -> Unit)? = null,
    ): Boolean {
        if (nextChapter && !canContinueFromCurrentWindow()) return false
        if (_state.value.isGenerating || generationJob?.isActive == true) return false
        val world = _state.value.world ?: return false
        val character = CharacterEntity(name = world.narratorName)

        return launchSingleGeneration(expectedTailMessageId, draftSubmissionId) narratorScope@{ generation ->
            if (!generation.ensureExpectedUserTail()) return@narratorScope
            val resumeChapter = if (nextChapter) getMessageTailForBranch(generation.branchId, 1).lastOrNull()?.takeIf {
                NovelChapter.canResumeTail(generation.branchId, it.branchId, it.structuredContentJson)
            } else null
            val chapterNumber = if (nextChapter) {
                val latest = if (generation.branchId == "main") messageDao.getMainMaxChapter(sessionId)
                    else messageDao.getBranchMaxChapter(sessionId, generation.branchId)
                resumeChapter?.let { NovelChapter.number(it.structuredContentJson) } ?: (latest.coerceAtLeast(0) + 1)
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
            val contextWindow = requestPlatform()?.modelContextWindows?.get(model)
                ?: com.mojing.app.domain.config.ModelRequestSettingsResolver.contextWindow(
                    generation.modelPlatforms, apiKey, narrBases.firstOrNull().orEmpty(), model)
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
            val history = slidingWindowBuilder.takeRecentPreservingTurn(getContextMessagesForBranch(generation.branchId), 20)
            val memorySegments = memorySegmentDao.getRecentForBranch(sessionId, generation.branchId)
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
                sessionId = sessionId,
                recentMemorySegments = memorySegments,
                universalContextMemoryText = universalMemoryText,
                memoryCorrections = memoryCorrections,
                encyclopediaFoundation = sharedWorldContext.encyclopediaFoundation,
                automaticSummary = contextWindow != null && contextBuilder.areAutomaticSummaries(memorySegments),
                encyclopediaHits = sharedWorldContext.encyclopediaHits,
                loreHits = sharedWorldContext.loreHits,
            )
            val narratorDocument = promptBuilder.buildNarratorDocument(context, guidance, model,
                includeUserProfile = false, allowChoices = !nextChapter)
            val chapterInstructions =
                if (chapterNumber != null) "\n小说名：${_state.value.sessionTitle}。本次续写小说第 $chapterNumber 章。承接已有剧情，写完整连续的小说正文，不回复用户、不生成选项或大纲。第一行给出章节标题，随后正文。" +
                    chapterTitle.trim().takeIf { it.isNotEmpty() }?.let { "指定章节标题：$it" }.orEmpty() +
                    if (resumeChapter != null) "\n上一条是本章未完成草稿。保留已有情节，返回从本章开头到结尾的完整正文。" else ""
                else ""
            val narratorPrompt = if (chapterInstructions.isBlank()) narratorDocument else narratorDocument.appendProtected(chapterInstructions, separator = "")
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
            generation.interruptedReplySpeakerType = if (chapterNumber == null) "narrator" else null
            generation.interruptedReplyCharacterId = null
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
                        var contextLimitError = false
                        generation.ensureCurrent()
                        _state.update { it.copy(lastRequestModel = model, lastRequestPlatform = requestPlatform()?.name) }
                        chatEngine.streamGenerate(
                            sessionId = sessionId,
                            character = character.copy(personaPrompt = narratorPrompt.render()),
                            historyMessages = history,
                            apiKey = apiKey, baseUrl = nb, model = model,
                            temperature = 0.8f, maxTokens = 12000,
                            personaName = secureStorage.userName,
                            userDescription = secureStorage.userDescription,
                            platformId = requestPlatform()?.id,
                            contextWindow = contextWindow,
                            promptDocument = narratorPrompt,
                        ).collect { s ->
                            when (s) {
                                is StreamState.Generating -> {
                                    generation.ensureCurrent()
                                    val now = System.currentTimeMillis()
                                    receivedText = s.partialText
                                    generation.interruptedReplyText = s.partialText
                                    if (chapterNumber != null && now - lastChapterSave >= 1500L) saveChapterDraft()
                                    if (chapterNumber == null) checkpointReplyRecovery(generation, receivedText, "narrator", null)
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
                                        "session=$sessionId done model=${model.trim()} rawLen=${s.fullText.length}",
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
                                    if (chapterNumber == null) checkpointReplyRecovery(generation, s.fullText, "narrator", null, force = true)
                                    val recoveryToken = if (chapterNumber == null) generation.replyRecovery?.token else null
                                    val replyMetadata = if (chapter != null) NovelChapter.metadata(structuredContentJsonFor(narrContent), chapterNumber!!, chapter.first)
                                        else structuredContentJsonFor(narrContent)
                                    UsbSessionLog.i(
                                        "Narrator",
                                        "session=$sessionId displayLen=${narrContent.length}",
                                    )
                                    val completedReply = MessageEntity(
                                            sessionId = sessionId,
                                            speakerType = "narrator",
                                            content = narrContent,
                                            structuredContentJson = ReplyGenerationMetadata.record(replyMetadata,
                                                (System.nanoTime() - replyStartedAt) / 1_000_000, s.usage).let { metadata ->
                                                    recoveryToken?.let { ReplyRecoveryMetadata.withToken(metadata, it) } ?: metadata
                                                },
                                            branchId = generation.branchId,
                                        )
                                    generation.interruptedReplyText = s.fullText
                                    val draftId = chapterDraftId
                                    withContext(NonCancellable) {
                                        if (draftId == null) messageDao.insert(completedReply)
                                        else messageDao.updateNovelDraft(draftId, sessionId, generation.branchId, completedReply.content, completedReply.structuredContentJson)
                                        narratorReplyCommitted = true
                                        generation.interruptedReplyText = ""
                                        if (chapterNumber == null) clearCommittedReplyRecovery(generation)
                                    }
                                    if (chapterNumber != null) onChapterCommitted?.invoke()
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
                                            contextWindow = contextWindow,
                                            worldText = world.worldPrompt,
                                            activeCharacterNames = _state.value.characterNames.values.toList(),
                                        )
                                        publishContextMemoryResult(branchId, memoryResult)
                                        val recentMessages = getContextMessagesForBranch(branchId).takeLast(20)
                                        memoryV2Manager.extractEventNodes(sessionId, branchId, null, recentMessages, apiKey, nb, model, contextWindow = contextWindow)
                                        refreshEventNodesForBranch(branchId)
                                        if (world.autoSedimentEnabled && world.encyclopediaId != null) {
                                            sedimentEngine.sedimentFromMessages(
                                                encyclopediaId = world.encyclopediaId, sessionId = sessionId,
                                                branchId = branchId, messages = recentMessages.takeLast(10),
                                                apiKey = apiKey, baseUrl = nb, model = model, contextWindow = contextWindow,
                                            )
                                        }

                                    }
                                }
                                is StreamState.Error -> {
                                    generation.ensureCurrent()
                                    errMsg = s.message
                                    contextLimitError = s.contextLimit
                                    UsbSessionLog.w(
                                        "Narrator",
                                        "session=$sessionId error model=${model.trim()} messageLen=${s.message.length}",
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
                            if (contextLimitError) {
                                _state.update { it.copy(streamingText = "", contextBudgetError = errMsg) }
                                exitNarratorJob = true
                                break
                            }
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
                        e is NovelChapter.EmptyBodyException -> "模型未返回章节正文，请重试"
                        sawDone -> UserFacingStrings.localSaveFailed("旁白回复")
                        else -> UserFacingStrings.remoteRequestFailed(e)
                    },
                )
                return@narratorScope
            } finally {
                if (chapterNumber != null && !narratorReplyCommitted) withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        saveChapterDraft()
                        if (currentBranchId() == generation.branchId) refreshMessagesUi(generation.branchId)
                    } catch (_: Exception) { _state.update { it.copy(error = "章节草稿写入或刷新失败，请重新进入对话核对") } }
                }
            }
            if (exitNarratorJob) return@narratorScope
        }
    }

    fun clearNovelMetadataError() {
        if (!_state.value.novelMetadataSaving) _state.update { it.copy(novelMetadataError = null) }
    }

    fun renameNovel(title: String, onSuccess: () -> Unit) = renameSessionTitleInternal(title, "小说标题", onSuccess)

    fun renameSessionTitle(title: String, onSuccess: () -> Unit) = renameSessionTitleInternal(title, "对话名称", onSuccess)

    private fun renameSessionTitleInternal(title: String, label: String, onSuccess: () -> Unit) {
        if (_state.value.novelMetadataSaving) return
        if (title.isBlank() || _state.value.isGenerating) {
            _state.update { it.copy(novelMetadataError = "请填写$label，并在生成结束后保存") }
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
            catch (_: Exception) { _state.update { it.copy(novelMetadataError = "${label}保存失败，请重试") } }
            finally { _state.update { it.copy(novelMetadataSaving = false) } }
        }
    }

    fun renameChapter(messageId: Long, title: String, onSuccess: () -> Unit) =
        renameChapter(messageId, title, currentBranchId(), null, onSuccess)

    fun renameChapter(messageId: Long, title: String, expectedBranchId: String,
        expectedSourceBranchId: String?, onSuccess: () -> Unit) {
        if (_state.value.novelMetadataSaving) return
        if (title.isBlank() || _state.value.isGenerating) {
            _state.update { it.copy(novelMetadataError = "请填写名称，并在生成结束后保存") }
            return
        }
        if (expectedBranchId != currentBranchId()) {
            _state.update { it.copy(novelMetadataError = "故事线已改变，请重新打开目录后修改") }
            return
        }
        _state.update { it.copy(novelMetadataSaving = true, novelMetadataError = null) }
        val branch = expectedBranchId
        var saved = false
        // Hold the existing action owner only through the source write. Later UI reads use
        // their revision guards, so committed saves do not prevent navigation during refresh.
        val launched = launchBranchTransition(onSuccess = {
            if (saved) viewModelScope.launch {
                try {
                    onSuccess()
                    refreshMessagesUi(branch)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { _state.update { it.copy(error = "章节名称已保存，对话刷新失败，请重新打开对话") } }
                finally { _state.update { it.copy(novelMetadataSaving = false) } }
            }
        }) {
            try {
                val source = getVisibleMessage(branch, messageId)
                check(source != null && source.sessionId == sessionId &&
                    (expectedSourceBranchId == null || source.branchId == expectedSourceBranchId)) { "章节来源已变化" }
                check(currentBranchId() == branch && !_state.value.isGenerating) { "当前故事线状态已变化" }
                messageDao.renameNovelChapter(messageId, sessionId, title)
                saved = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(novelMetadataError = "章节名称保存失败，请确认章节仍在当前故事线后重试") } }
            finally { if (!saved) _state.update { it.copy(novelMetadataSaving = false) } }
        }
        if (!launched) {
            _state.update { it.copy(novelMetadataSaving = false, novelMetadataError = "当前正在生成或切换故事线，请稍后重试") }
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
        val launched = launchBranchTransition(
            navigationLabel = "正在创建并打开故事线…",
            invalidateSpeech = true,
        ) branchTransition@{
            val parentBranch = currentBranchId()
            var branchCommitted = false
            var branchAvailableInList = false
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
                branchAvailableInList = updatedBranches.any { it.branchId == newBranchId }
                _state.update { it.copy(branches = updatedBranches) }
                refreshMessagesUi(newBranchId, switchBranchOnSuccess = true)
                check(currentBranchId() == newBranchId) { "新故事线尚未加载" }
                persistCurrentBranchSelection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        error = if (branchAvailableInList) {
                            "故事线已创建，但打开失败，请从故事线列表重试"
                        } else if (branchCommitted) {
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

    suspend fun saveMessageImagesToGallery(messageId: Long, expectedBranchId: String? = null): GalleryImageSaveResult {
        if (expectedBranchId != null && currentBranchId() != expectedBranchId) {
            return GalleryImageSaveResult(requestedCount = 0, savedCount = 0)
        }
        return saveGalleryImageAttachments(attachmentDao.getByMessage(messageId)) { attachment ->
            imageRepository.saveLocalImageToGallery(
                filePath = attachment.storagePath,
                originalName = attachment.fileName,
                mimeType = attachment.mimeType,
            )
        }
    }

    fun switchBranch(branchId: String, onResult: (String?) -> Unit = {}) {
        if (_state.value.isGenerating) {
            val reason = "当前正在生成，请先停止或等待完成后再切换故事线"
            _state.update { it.copy(error = reason) }
            onResult(reason)
            return
        }
        val launched = launchBranchTransition(
            navigationLabel = "正在打开故事线…",
            invalidateSpeech = true,
        ) switchTransition@{
            val previousBranchId = currentBranchId()
            var switched = false
            try {
                val branches = sessionBranchDao.getBySession(sessionId)
                _state.update { it.copy(branches = branches) }
                if (branchId != "main" && branches.none { it.branchId == branchId }) {
                    _state.update { it.copy(error = "故事线已不存在，请刷新后重试") }
                    return@switchTransition
                }
                refreshMessagesUi(branchId, switchBranchOnSuccess = true)
                if (currentBranchId() != branchId) {
                    _state.update { it.copy(error = "故事线已不存在，已返回主线") }
                    persistCurrentBranchSelection()
                    return@switchTransition
                }
                _state.update { it.copy(error = null) }
                persistCurrentBranchSelection()
                switched = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        currentBranchId = previousBranchId,
                        error = "故事线切换失败，请重试",
                    )
                }
            } finally {
                onResult(if (switched) null else _state.value.error ?: "故事线未能打开，请重试")
            }
        }
        if (!launched) {
            val reason = if (_state.value.replyRecovery != null) _state.value.error ?: "请先处理上次中断的回复"
                else "当前正在切换故事线，请稍后再试"
            _state.update { it.copy(error = reason) }
            onResult(reason)
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

    internal suspend fun loadAddParticipantPage(query: String, cursor: NewSessionCharacterOption?): AddParticipantPage {
        val encyclopediaId = sessionWorldDao.getBySession(sessionId)?.encyclopediaId?.takeIf { it > 0L }
        val rows = characterDao.getAddParticipantPage(
            sessionId, encyclopediaId, query.trim(), cursor?.pinnedAt, cursor?.favorite,
            cursor?.createdAt, cursor?.id, ADD_PARTICIPANT_PAGE_SIZE + 1,
        )
        return AddParticipantPage(rows.take(ADD_PARTICIPANT_PAGE_SIZE), rows.size > ADD_PARTICIPANT_PAGE_SIZE)
    }

    fun addParticipant(
        characterId: Long,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (_state.value.participantAdding || !canMutateRoundConfiguration()) {
            onResult(false)
            return
        }
        _state.update { it.copy(participantAdding = true, participantAddError = null, participantAddedId = null) }
        val writeJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val character = characterDao.getById(characterId)
                if (character == null) {
                    _state.update { it.copy(error = "角色不存在或已删除，请重新选择", participantAddError = "角色不存在或已删除，请重新选择") }
                    onResult(false)
                    return@launch
                }
                if (character.boundEncyclopediaId <= 0L) {
                    _state.update { it.copy(error = "该角色尚未绑定世界资料，无法加入当前对话", participantAddError = "该角色尚未绑定世界资料，无法加入当前对话") }
                    onResult(false)
                    return@launch
                }
                val world = sessionWorldDao.getBySession(sessionId)
                val encyclopediaId = world?.encyclopediaId?.takeIf { it > 0L }
                if (encyclopediaId != null && character.boundEncyclopediaId > 0L && character.boundEncyclopediaId != encyclopediaId) {
                    _state.update { it.copy(error = "该角色与当前世界不匹配，请重新选择", participantAddError = "该角色与当前世界不匹配，请重新选择") }
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
                characterSummaries = maps.summaries,
                    characterCardImages = maps.cardImages,
                    characterColors = maps.colors,
                    participantAddedId = characterId,
                )
                onResult(true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = "添加角色失败，请重试", participantAddError = "添加角色失败，请重试") }
                onResult(false)
            } finally { _state.update { it.copy(participantAdding = false) } }
        }
        if (RetainedChatSessions.stores.contains(sessionId)) {
            RetainedChatSessions.stores.retainJob(sessionId, writeJob, reportRunning = false)
        }
        writeJob.start()
    }

    fun clearParticipantAddFeedback() {
        if (!_state.value.participantAdding) {
            _state.update { it.copy(participantAddError = null, participantAddedId = null) }
        }
    }

    fun toggleMute(participantId: Long) {
        if (isParticipantWritePending(participantId) || !canMutateRoundConfiguration()) return
        _state.update { it.copy(participantMuteSaving = it.participantMuteSaving + participantId) }
        val writeJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val participant = participantDao.getById(participantId)
                if (participant == null || participant.sessionId != sessionId) {
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
            } finally {
                _state.update { it.copy(participantMuteSaving = it.participantMuteSaving - participantId) }
            }
        }
        if (RetainedChatSessions.stores.contains(sessionId)) {
            RetainedChatSessions.stores.retainJob(sessionId, writeJob, reportRunning = false)
        }
        writeJob.start()
    }

    private fun isParticipantWritePending(participantId: Long): Boolean =
        participantId in _state.value.participantMuteSaving ||
            participantId in _state.value.participantRemoving ||
            _state.value.participantTalkativenessSaving.containsKey(participantId)

    fun updateParticipantTalkativeness(
        participantId: Long,
        talkativeness: Float,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (isParticipantWritePending(participantId) || !canMutateRoundConfiguration()) {
            onResult(false)
            return
        }
        val requested = talkativeness.coerceIn(0.05f, 1f)
        _state.update { it.copy(participantTalkativenessSaving = it.participantTalkativenessSaving + (participantId to requested)) }
        val writeJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val participant = participantDao.getById(participantId)
                if (participant == null || participant.sessionId != sessionId) {
                    _state.update { it.copy(error = "该角色已不在当前对话中") }
                    onResult(false)
                    return@launch
                }
                participantDao.upsert(
                    participant.copy(talkativeness = requested),
                )
                val updated = participantDao.getBySession(sessionId)
                _state.value = _state.value.copy(participants = updated)
                onResult(true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = "发言率保存失败，请重试") }
                onResult(false)
            } finally {
                _state.update { it.copy(participantTalkativenessSaving = it.participantTalkativenessSaving - participantId) }
            }
        }
        if (RetainedChatSessions.stores.contains(sessionId)) {
            RetainedChatSessions.stores.retainJob(sessionId, writeJob, reportRunning = false)
        }
        writeJob.start()
    }

    fun setManualReplyCharacterId(characterId: Long?) {
        _state.value = _state.value.copy(manualReplyCharacterId = characterId)
    }

    fun setQuotingMessage(message: MessageEntity?) {
        val immediateSnippet = message?.takeIf {
            it.content.length <= ChatMessageTextFormat.ASYNC_BODY_CHAR_THRESHOLD
        }?.let { ChatMessageTextFormat.quoteSnippet(it.content, 120, it.speakerType) }
        if (message != null && immediateSnippet != null && immediateSnippet.isBlank()) {
            _state.value = _state.value.copy(error = UserFacingStrings.messageHasNoQuotableText())
            return
        }
        quotePreparationJob?.cancel()
        quoteDraftRevision++
        val revision = quoteDraftRevision
        if (message != _state.value.quotingMessage) activeDraftSubmissionId = null
        _state.value = _state.value.copy(quotingMessage = message, quotingSnippet = immediateSnippet)
        persistCurrentDraft()
        if (message == null || immediateSnippet != null) return
        quotePreparationJob = viewModelScope.launch {
            val snippet = try {
                withContext(preparationDispatcher) {
                    ChatMessageTextFormat.quoteSnippet(message.content, 120, message.speakerType)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (quoteDraftRevision != revision || _state.value.quotingMessage?.id != message.id) return@launch
            if (snippet.isNullOrBlank()) {
                quoteDraftRevision++
                _state.update { it.copy(
                    quotingMessage = null,
                    quotingSnippet = null,
                    error = if (snippet == null) "引用正文准备失败，请重新选择" else UserFacingStrings.messageHasNoQuotableText(),
                ) }
                persistCurrentDraft()
            } else {
                _state.update { it.copy(
                    quotingSnippet = snippet,
                    error = if (it.error == "引用正文正在准备，请稍候再发送") null else it.error,
                ) }
            }
        }
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
        if (isParticipantWritePending(participantId) || !canMutateRoundConfiguration()) return
        _state.update { it.copy(participantRemoving = it.participantRemoving + participantId) }
        val writeJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val removed = participantDao.getById(participantId)
                if (removed != null && removed.sessionId != sessionId) {
                    _state.update { it.copy(error = "该角色已不在当前对话中") }
                    return@launch
                }
                if (removed != null) participantDao.delete(participantId)
                val updated = participantDao.getBySession(sessionId)
                val maps = buildCharacterPresentationMaps(updated)
                _state.value = _state.value.copy(
                    participants = updated,
                    characterNames = maps.names,
                    characterAvatars = maps.avatars,
                characterSummaries = maps.summaries,
                    characterCardImages = maps.cardImages,
                    characterColors = maps.colors,
                    manualReplyCharacterId = _state.value.manualReplyCharacterId
                        .takeUnless { it == removed?.characterId },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = "移除角色失败，请重试") }
            } finally {
                _state.update { it.copy(participantRemoving = it.participantRemoving - participantId) }
            }
        }
        if (RetainedChatSessions.stores.contains(sessionId)) {
            RetainedChatSessions.stores.retainJob(sessionId, writeJob, reportRunning = false)
        }
        writeJob.start()
    }

    fun stopGeneration() {
        cancelPendingAutoNarrator()
        clearImageRetry()
        val job = generationJob ?: return
        if (activeGeneration?.started != true) {
            activeGeneration?.draftSubmissionId?.let(::finishDraftSubmission)
            generationJob = null
            activeGeneration = null
            job.cancel()
            resetGenerationUi()
        } else {
            job.cancel()
        }
    }

    private fun normalizedCorrectionContent(content: String): String? = content.trim().takeIf { it.isNotEmpty() && it.length <= 2000 }

    private var correctionWriteInFlight = false

    data class CorrectionSaveReceipt(val requestId: String, val saved: Boolean)
    private val _correctionSaveReceipt = MutableStateFlow<CorrectionSaveReceipt?>(null)
    val correctionSaveReceipt = _correctionSaveReceipt.asStateFlow()
    private var correctionEditorRequestId: String? = null

    fun knowsCorrectionEditorRequest(requestId: String): Boolean = correctionEditorRequestId == requestId

    // The existing write owner keeps the result available to a recreated editor;
    // a callback captured by the disposed Composition cannot close its successor.
    fun saveMemoryCorrectionFromEditor(
        requestId: String,
        correctionId: Long?,
        content: String,
        branchId: String?,
        sourceMessageId: Long?,
    ) {
        if (correctionEditorRequestId == requestId) return
        correctionEditorRequestId = requestId
        saveMemoryCorrection(correctionId, content, branchId, sourceMessageId) { saved ->
            _correctionSaveReceipt.value = CorrectionSaveReceipt(requestId, saved)
        }
    }

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
        val writeJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
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
                onResult(false)
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
                    _state.update { it.copy(error = "纠正已保存，列表刷新失败，请在用户纠正页重试加载") }
                }
            }
        }
        if (RetainedChatSessions.stores.contains(sessionId)) {
            RetainedChatSessions.stores.retainJob(sessionId, writeJob, reportRunning = false)
        }
        writeJob.start()
    }

    fun deleteMemoryCorrection(id: Long, onResult: (Boolean) -> Unit = {}) {
        if (_state.value.isGenerating) {
            _state.update { it.copy(error = "当前正在生成，请等待完成后再删除纠正") }
            onResult(false)
            return
        }
        viewModelScope.launch {
            val deleted = try {
                check(memoryCorrectionDao.deleteById(sessionId, id) == 1) { "纠正记录不存在" }
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                _state.update { it.copy(error = "纠正记忆删除失败：${e.message?.takeIf { it.isNotBlank() } ?: "未知错误"}") }
                false
            }
            onResult(deleted)
            if (!deleted) return@launch
            _state.update { state -> state.copy(memoryCorrections = state.memoryCorrections.filterNot { it.id == id }) }
            try { refreshMemoryCorrectionsOnly() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { it.copy(error = "纠正已删除，列表刷新失败，请在用户纠正页重试加载") }
            }
        }
    }

    fun loadMemoryCorrectionsIfNeeded() {
        val current = _state.value
        if (!current.isReady || current.memoryCorrectionsLoaded || current.memoryCorrectionsLoading) return
        val branchId = current.currentBranchId
        val revision = correctionRefreshRevision.incrementAndGet()
        _state.update { state -> if (state.currentBranchId == branchId)
            state.copy(memoryCorrectionsLoading = true, memoryCorrectionsLoadError = null) else state }
        viewModelScope.launch {
            try {
                val page = memoryCorrectionDao.getVisibleFirstPage(
                    sessionId, branchId, MEMORY_CORRECTION_PAGE_SIZE + 1,
                )
                _state.update { state -> if (state.currentBranchId == branchId &&
                    correctionRefreshRevision.get() == revision) state.copy(
                    memoryCorrections = page.take(MEMORY_CORRECTION_PAGE_SIZE),
                    memoryCorrectionsLoaded = true,
                    memoryCorrectionsHasMore = page.size > MEMORY_CORRECTION_PAGE_SIZE,
                    memoryCorrectionsWindowSize = MEMORY_CORRECTION_PAGE_SIZE,
                    memoryCorrectionsLoadError = null,
                ) else state }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { state -> if (state.currentBranchId == branchId &&
                    correctionRefreshRevision.get() == revision)
                    state.copy(memoryCorrectionsLoadError = "用户纠正读取失败，请重试") else state }
            } finally {
                _state.update { state -> if (state.currentBranchId == branchId &&
                    correctionRefreshRevision.get() == revision)
                    state.copy(memoryCorrectionsLoading = false) else state }
            }
        }
    }

    fun loadMoreMemoryCorrections() {
        val current = _state.value
        if (!current.memoryCorrectionsLoaded) {
            loadMemoryCorrectionsIfNeeded()
            return
        }
        if (current.memoryCorrectionsLoadError != null && !current.memoryCorrectionsHasMore &&
            !current.memoryCorrectionsLoading) {
            _state.update { it.copy(memoryCorrectionsLoading = true, memoryCorrectionsLoadError = null) }
            viewModelScope.launch {
                try { refreshMemoryCorrectionsOnly() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The panel keeps its retryable read error. */ }
            }
            return
        }
        if (!current.isReady || !current.memoryCorrectionsHasMore || current.memoryCorrectionsLoading) return
        val branchId = current.currentBranchId
        val tail = current.memoryCorrections.lastOrNull() ?: return
        val revision = correctionRefreshRevision.get()
        val windowSize = current.memoryCorrectionsWindowSize
        _state.update { it.copy(memoryCorrectionsLoading = true, memoryCorrectionsLoadError = null) }
        viewModelScope.launch {
            try {
                val page = memoryCorrectionDao.getVisibleBefore(
                    sessionId, branchId, tail.createdAt, tail.id, MEMORY_CORRECTION_PAGE_SIZE + 1,
                )
                _state.update { state -> if (state.currentBranchId == branchId &&
                    correctionRefreshRevision.get() == revision &&
                    state.memoryCorrections.lastOrNull()?.id == tail.id &&
                    state.memoryCorrectionsWindowSize == windowSize) state.copy(
                    memoryCorrections = (state.memoryCorrections + page.take(MEMORY_CORRECTION_PAGE_SIZE))
                        .distinctBy { it.id },
                    memoryCorrectionsHasMore = page.size > MEMORY_CORRECTION_PAGE_SIZE,
                    memoryCorrectionsWindowSize = windowSize + MEMORY_CORRECTION_PAGE_SIZE,
                ) else state }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { state -> if (state.currentBranchId == branchId &&
                    correctionRefreshRevision.get() == revision)
                    state.copy(memoryCorrectionsLoadError = "更多用户纠正读取失败，请重试") else state }
            } finally {
                _state.update { state -> if (state.currentBranchId == branchId &&
                    correctionRefreshRevision.get() == revision)
                    state.copy(memoryCorrectionsLoading = false) else state }
            }
        }
    }

    private suspend fun refreshMemoryCorrectionsOnly() {
        val displayed = _state.value
        if (!displayed.memoryCorrectionsLoaded && !displayed.memoryCorrectionsLoading) return
        val branchId = currentBranchId()
        val revision = correctionRefreshRevision.incrementAndGet()
        try {
            val windowSize = _state.value.takeIf { it.currentBranchId == branchId }
                ?.memoryCorrectionsWindowSize ?: MEMORY_CORRECTION_PAGE_SIZE
            val page = memoryCorrectionDao.getVisibleFirstPage(sessionId, branchId, windowSize + 1)
            _state.update { current ->
                if (current.currentBranchId == branchId && correctionRefreshRevision.get() == revision &&
                    current.memoryCorrectionsWindowSize == windowSize) current.copy(
                    memoryCorrections = page.take(windowSize),
                    memoryCorrectionsLoaded = true,
                    memoryCorrectionsHasMore = page.size > windowSize,
                    memoryCorrectionsLoading = false,
                    memoryCorrectionsLoadError = null,
                ) else current
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            _state.update { state -> if (state.currentBranchId == branchId &&
                correctionRefreshRevision.get() == revision) state.copy(
                memoryCorrectionsHasMore = false,
                memoryCorrectionsLoading = false,
                memoryCorrectionsLoadError = "纠正列表刷新失败，请重试",
            ) else state }
            throw failure
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
                val deletedIds = result.deletedMessageIds.toSet()
                if (_state.value.quotingMessage?.id in deletedIds) {
                    setQuotingMessage(null)
                }
                bookmarkMutex.withLock {
                    val removedBookmarkIds = _state.value.bookmarks
                        .filter { it.messageId in deletedIds }
                        .map { it.id }
                    removedBookmarkIds.forEach { savedStateHandle.remove<String>(bookmarkNoteDraftKey(it)) }
                    _state.update { current -> current.copy(
                        bookmarks = current.bookmarks.filterNot { it.messageId in deletedIds },
                        bookmarkedMessageIds = current.bookmarkedMessageIds - deletedIds,
                        bookmarkPreviews = current.bookmarkPreviews.filterKeys { it !in deletedIds },
                        bookmarkNoteDrafts = current.bookmarkNoteDrafts - removedBookmarkIds.toSet(),
                        bookmarkNoteErrors = current.bookmarkNoteErrors - removedBookmarkIds.toSet(),
                    ) }
                }
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
            invalidateSpeech = true,
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
                refreshMessagesUi(branchId, switchBranchOnSuccess = true)
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
                refreshMessagesUi(branchId, anchorMessageId = messageId.takeIf { _state.value.hasNewerMessages })
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

    fun setMessageContextExcluded(
        messageId: Long,
        excluded: Boolean,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (_state.value.isGenerating) {
            _state.update { it.copy(error = "当前正在生成，请等待完成后再调整上下文") }
            onResult(false)
            return
        }
        val launched = launchBranchTransition contextTransition@{
            val branchId = currentBranchId()
            var committed = false
            try {
                committed = messageDao.setContextExcluded(sessionId, branchId, messageId, excluded)
                if (!committed) {
                    _state.update { it.copy(error = "该消息已不在当前故事线，请刷新后重试") }
                    onResult(false)
                    return@contextTransition
                }
                refreshMessagesUi(branchId)
                onResult(currentBranchId() == branchId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = if (committed)
                    "上下文设置已保存，但对话刷新失败，请重新进入对话" else "上下文设置失败，请重试") }
                onResult(false)
            }
        }
        if (!launched) {
            _state.update { it.copy(error = "当前正在生成或切换故事线，请稍后再调整上下文") }
            onResult(false)
        }
    }

    fun toggleBookmark(messageId: Long) =
        setBookmark(messageId, messageId !in _state.value.bookmarkedMessageIds)

    fun removeBookmark(messageId: Long) = setBookmark(messageId, false)

    private fun bookmarkNoteDraftKey(bookmarkId: Long): String =
        "bookmarkNoteDraft:$sessionId:$bookmarkId"

    private fun restoreBookmarkNoteDrafts(): Map<Long, String> =
        savedStateHandle.keys()
            .asSequence()
            .filter { it.startsWith("bookmarkNoteDraft:$sessionId:") }
            .mapNotNull { key ->
                key.substringAfterLast(':').toLongOrNull()?.let { id ->
                    savedStateHandle.get<String>(key)?.let { id to it.take(MAX_BOOKMARK_NOTE_LENGTH) }
                }
            }
            .toMap()

    fun updateBookmarkNoteDraft(bookmarkId: Long, note: String) {
        if (_state.value.bookmarks.none { it.id == bookmarkId }) return
        val normalized = note.take(MAX_BOOKMARK_NOTE_LENGTH)
        savedStateHandle[bookmarkNoteDraftKey(bookmarkId)] = normalized
        _state.update {
            it.copy(bookmarkNoteDrafts = it.bookmarkNoteDrafts + (bookmarkId to normalized),
                bookmarkNoteErrors = it.bookmarkNoteErrors - bookmarkId)
        }
    }

    fun saveBookmarkNote(bookmarkId: Long, note: String, onResult: (Boolean) -> Unit = {}) {
        val current = _state.value
        val bookmark = current.bookmarks.firstOrNull { it.id == bookmarkId }
        if (!current.isReady || bookmark == null || bookmark.sessionId != sessionId) {
            _state.update { it.copy(bookmarkNoteErrors = it.bookmarkNoteErrors + (bookmarkId to "这条收藏已不可用，请刷新后重试")) }
            onResult(false)
            return
        }
        if (bookmarkId in current.bookmarkNoteSavingIds) return
        val normalized = note.take(MAX_BOOKMARK_NOTE_LENGTH)
        _state.update {
            it.copy(
                bookmarkNoteSavingIds = it.bookmarkNoteSavingIds + bookmarkId,
                bookmarkNoteErrors = it.bookmarkNoteErrors - bookmarkId,
            )
        }
        viewModelScope.launch {
            var saved = false
            try {
                bookmarkMutex.withLock {
                    val updated = bookmarkDao.updateNote(sessionId, bookmarkId, normalized)
                    check(updated == 1) { "收藏已不存在" }
                    saved = true
                    _state.update { state ->
                        val hasNewerDraft = state.bookmarkNoteDrafts[bookmarkId]?.let { it != normalized } == true
                        if (!hasNewerDraft) savedStateHandle.remove<String>(bookmarkNoteDraftKey(bookmarkId))
                        state.copy(
                            bookmarks = state.bookmarks.map { row ->
                                if (row.id == bookmarkId && row.sessionId == sessionId) row.copy(note = normalized) else row
                            },
                            bookmarkNoteDrafts = if (hasNewerDraft) state.bookmarkNoteDrafts else state.bookmarkNoteDrafts - bookmarkId,
                            bookmarkNoteErrors = state.bookmarkNoteErrors - bookmarkId,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { state ->
                    state.copy(bookmarkNoteErrors = state.bookmarkNoteErrors + (bookmarkId to "备注保存失败，请重试"))
                }
            } finally {
                _state.update { it.copy(bookmarkNoteSavingIds = it.bookmarkNoteSavingIds - bookmarkId) }
                onResult(saved)
            }
            if (saved && (_state.value.bookmarkQuery.isNotBlank() || _state.value.bookmarksBeforeId != null)) {
                reloadBookmarkPage()
            }
        }
    }

    private suspend fun readBookmarkPage(
        query: String, beforeCreatedAt: Long?, beforeId: Long?, limit: Int,
    ): List<MessageBookmarkEntity> = when {
        query.isNotBlank() -> bookmarkDao.searchPage(sessionId, query, beforeCreatedAt, beforeId, limit)
        beforeCreatedAt == null -> bookmarkDao.getFirstPage(sessionId, limit)
        else -> bookmarkDao.getBefore(sessionId, beforeCreatedAt, requireNotNull(beforeId), limit)
    }

    private fun saveBookmarkWindow(current: ChatContract.State) {
        savedStateHandle["bookmark_window_query_$sessionId"] = current.bookmarkQuery
        savedStateHandle["bookmark_window_size_$sessionId"] = current.bookmarksWindowSize
        if (current.bookmarksBeforeCreatedAt == null) savedStateHandle.remove<Long>("bookmark_window_before_at_$sessionId")
        else savedStateHandle["bookmark_window_before_at_$sessionId"] = current.bookmarksBeforeCreatedAt
        if (current.bookmarksBeforeId == null) savedStateHandle.remove<Long>("bookmark_window_before_id_$sessionId")
        else savedStateHandle["bookmark_window_before_id_$sessionId"] = current.bookmarksBeforeId
    }

    fun updateBookmarkQuery(query: String) {
        val normalized = query.take(200)
        if (!_state.value.isReady || normalized == _state.value.bookmarkQuery) return
        savedStateHandle["bookmark_query_$sessionId"] = normalized
        bookmarkRefreshRevision.incrementAndGet()
        bookmarkInitialLoadJob?.cancel()
        bookmarkInitialLoadJob = null
        _state.update { it.copy(bookmarkQuery = normalized, bookmarks = emptyList(),
            bookmarkPreviews = emptyMap(), bookmarksLoaded = false,
            bookmarksHasMore = false, bookmarksLoadingMore = false, bookmarksRefreshFailed = false, bookmarksLoadError = null,
            bookmarksWindowSize = BOOKMARK_PAGE_SIZE, bookmarksBeforeCreatedAt = null, bookmarksBeforeId = null) }
        saveBookmarkWindow(_state.value)
        loadBookmarksIfNeeded()
    }

    fun resetBookmarkWindow() {
        if (!_state.value.isReady) return
        _state.update { it.copy(bookmarksWindowSize = BOOKMARK_PAGE_SIZE,
            bookmarksBeforeCreatedAt = null, bookmarksBeforeId = null) }
        saveBookmarkWindow(_state.value)
        reloadBookmarkPage(resetWindow = true)
    }

    fun loadBookmarksIfNeeded() {
        if (!_state.value.isReady || _state.value.bookmarksLoaded || bookmarkInitialLoadJob?.isActive == true) return
        reloadBookmarkPage()
    }

    private fun reloadBookmarkPage(resetWindow: Boolean = false) {
        val request = _state.value
        if (!request.isReady) return
        val revision = bookmarkRefreshRevision.incrementAndGet()
        bookmarkInitialLoadJob?.cancel()
        val size = if (resetWindow) BOOKMARK_PAGE_SIZE else request.bookmarksWindowSize
        _state.update { it.copy(bookmarksLoadingMore = true, bookmarksRefreshFailed = false, bookmarksLoadError = null) }
        bookmarkInitialLoadJob = viewModelScope.launch {
            val owner = currentCoroutineContext()[Job]
            try {
                bookmarkMutex.withLock {
                    val page = readBookmarkPage(request.bookmarkQuery, request.bookmarksBeforeCreatedAt,
                        request.bookmarksBeforeId, size + 1)
                    val marks = page.take(size)
                    val previews = messagePreviews(marks.mapTo(mutableSetOf()) { it.messageId }, maxChars = 120)
                    currentCoroutineContext().ensureActive()
                    _state.update { state -> if (bookmarkRefreshRevision.get() == revision) state.copy(
                        bookmarks = marks, bookmarksLoaded = true, bookmarksWindowSize = size,
                        bookmarksHasMore = page.size > size, bookmarkPreviews = previews,
                    ) else state }
                    if (bookmarkRefreshRevision.get() == revision) saveBookmarkWindow(_state.value)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { if (bookmarkRefreshRevision.get() == revision)
                it.copy(bookmarksRefreshFailed = request.bookmarksLoaded, bookmarksLoadError = "收藏读取失败，请重试") else it } }
            finally {
                if (bookmarkInitialLoadJob === owner) bookmarkInitialLoadJob = null
                _state.update { if (bookmarkRefreshRevision.get() == revision)
                    it.copy(bookmarksLoadingMore = false) else it }
            }
        }
    }

    fun loadMoreBookmarks() {
        val request = _state.value
        if (!request.bookmarksLoaded) { loadBookmarksIfNeeded(); return }
        if (!request.isReady || request.bookmarksLoadingMore) return
        if (request.bookmarksRefreshFailed) { reloadBookmarkPage(); return }
        if (!request.bookmarksHasMore) return
        val revision = bookmarkRefreshRevision.get()
        val tail = request.bookmarks.lastOrNull()
        _state.update { it.copy(bookmarksLoadingMore = true, bookmarksRefreshFailed = false, bookmarksLoadError = null) }
        viewModelScope.launch {
            try {
                bookmarkMutex.withLock {
                    val page = readBookmarkPage(request.bookmarkQuery, tail?.createdAt, tail?.id, BOOKMARK_PAGE_SIZE + 1)
                    val next = page.take(BOOKMARK_PAGE_SIZE)
                    val previews = messagePreviews(next.mapTo(mutableSetOf()) { it.messageId }, maxChars = 120)
                    currentCoroutineContext().ensureActive()
                    _state.update { state ->
                        if (bookmarkRefreshRevision.get() != revision || state.bookmarks.lastOrNull()?.id != tail?.id) state
                        else {
                            val window = appendBookmarkPage(state.bookmarks, next,
                                state.bookmarksBeforeCreatedAt, state.bookmarksBeforeId)
                            val visibleIds = window.rows.mapTo(mutableSetOf()) { it.messageId }
                            state.copy(bookmarks = window.rows,
                                bookmarksWindowSize = (state.bookmarksWindowSize + BOOKMARK_PAGE_SIZE).coerceAtMost(BOOKMARK_WINDOW_SIZE), bookmarksHasMore = page.size > BOOKMARK_PAGE_SIZE,
                                bookmarksBeforeCreatedAt = window.beforeCreatedAt, bookmarksBeforeId = window.beforeId,
                                bookmarkPreviews = (state.bookmarkPreviews + previews).filterKeys { it in visibleIds })
                        }
                    }
                    val accepted = _state.value
                    if (bookmarkRefreshRevision.get() == revision)
                        saveBookmarkWindow(accepted)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { if (bookmarkRefreshRevision.get() == revision)
                it.copy(bookmarksLoadError = "较早收藏读取失败，请重试") else it } }
            finally { _state.update { if (bookmarkRefreshRevision.get() == revision)
                it.copy(bookmarksLoadingMore = false) else it } }
        }
    }

    private fun setBookmark(messageId: Long, bookmarked: Boolean) {
        if (!_state.value.isReady || messageId in _state.value.bookmarkBusyIds) return
        _state.update { it.copy(bookmarkBusyIds = it.bookmarkBusyIds + messageId) }
        viewModelScope.launch {
            try {
                bookmarkMutex.withLock {
                val existing = bookmarkDao.getByMessageId(messageId)
                check(existing == null || existing.sessionId == sessionId)
                val mark: MessageBookmarkEntity?
                var preview: String? = null
                if (bookmarked) {
                    val source = messageDao.getMessagePreviewPrefixesInSession(sessionId, listOf(messageId))
                        .firstOrNull { it.id == messageId }
                    check(source != null) { "消息已不存在" }
                    preview = withContext(preparationDispatcher) {
                        ChatMessageTextFormat.sessionListPreview(source.content, source.speakerType, 120)
                            .ifBlank { "（暂无摘要，可打开原文）" }
                    }
                    val entry = existing ?: MessageBookmarkEntity(sessionId = sessionId, messageId = messageId, note = "")
                    mark = if (existing == null) entry.copy(id = bookmarkDao.insert(entry)) else entry
                } else {
                    bookmarkDao.deleteByMessageId(messageId)
                    mark = null
                }
                _state.update { current ->
                    val visibleMark = mark?.takeIf { current.bookmarkQuery.isBlank() && current.bookmarksBeforeId == null }
                    val marks = (current.bookmarks.filterNot { it.messageId == messageId } + listOfNotNull(visibleMark))
                        .sortedWith(compareByDescending<MessageBookmarkEntity> { it.createdAt }.thenByDescending { it.id })
                        .take(BOOKMARK_WINDOW_SIZE)
                    if (!bookmarked) {
                        current.bookmarks.firstOrNull { it.messageId == messageId }
                            ?.let { savedStateHandle.remove<String>(bookmarkNoteDraftKey(it.id)) }
                    }
                    current.copy(
                        bookmarks = marks,
                        bookmarksWindowSize = maxOf(current.bookmarksWindowSize,
                            ((marks.size + BOOKMARK_PAGE_SIZE - 1) / BOOKMARK_PAGE_SIZE * BOOKMARK_PAGE_SIZE).coerceIn(BOOKMARK_PAGE_SIZE, BOOKMARK_WINDOW_SIZE)),
                        bookmarkedMessageIds = if (bookmarked) current.bookmarkedMessageIds + messageId
                            else current.bookmarkedMessageIds - messageId,
                        bookmarkPreviews = (if (preview != null) current.bookmarkPreviews + (messageId to preview)
                            else current.bookmarkPreviews - messageId).filterKeys { id -> marks.any { it.messageId == id } },
                        bookmarkNoteDrafts = if (bookmarked) current.bookmarkNoteDrafts
                            else current.bookmarkNoteDrafts - (current.bookmarks.firstOrNull { it.messageId == messageId }?.id ?: -1L),
                        bookmarkNoteErrors = if (bookmarked) current.bookmarkNoteErrors
                            else current.bookmarkNoteErrors - (current.bookmarks.firstOrNull { it.messageId == messageId }?.id ?: -1L),
                    )
                }
                }
                if (_state.value.bookmarksLoaded) saveBookmarkWindow(_state.value)
                if (_state.value.bookmarksLoaded && (_state.value.bookmarkQuery.isNotBlank() || _state.value.bookmarksBeforeId != null)) {
                    reloadBookmarkPage()
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
        if (nodeId in _state.value.eventBusyIds) return
        val target = _state.value.eventNodes.firstOrNull { it.id == nodeId } ?: return
        if (target.branchId != branchId) {
            _state.update { it.copy(eventActionErrors = it.eventActionErrors + (nodeId to "请在来源故事线中删除这条继承事件")) }
            return
        }
        _state.update { it.copy(eventBusyIds = it.eventBusyIds + nodeId, eventActionErrors = it.eventActionErrors - nodeId) }
        val writeJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                eventNodeDao.deleteById(nodeId)
                _state.update { if (it.currentBranchId == branchId) it.copy(eventNodes = it.eventNodes.filter { event -> event.id != nodeId }) else it }
                refreshEventNodesForBranch(branchId, reportFailure = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { if (it.currentBranchId == branchId) it.copy(eventActionErrors = it.eventActionErrors + (nodeId to "事件删除失败，请重试")) else it }
                return@launch
            }
            finally { _state.update { it.copy(eventBusyIds = it.eventBusyIds - nodeId) } }
        }
        if (RetainedChatSessions.stores.contains(sessionId)) {
            RetainedChatSessions.stores.retainJob(sessionId, writeJob, reportRunning = false)
        }
        writeJob.start()
    }

    fun toggleEventNodeResolved(nodeId: Long) {
        val branchId = currentBranchId()
        if (nodeId in _state.value.eventBusyIds) return
        val target = _state.value.eventNodes.firstOrNull { it.id == nodeId } ?: return
        _state.update { it.copy(eventBusyIds = it.eventBusyIds + nodeId, eventActionErrors = it.eventActionErrors - nodeId) }
        val writeJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                if (target.branchId == branchId) {
                    eventNodeDao.setResolved(nodeId, !target.resolved)
                } else {
                    eventNodeDao.upsertStatusOverride(
                        BranchEventStatusEntity(sessionId, branchId, nodeId, !target.resolved),
                    )
                }
                _state.update { if (it.currentBranchId == branchId) it.copy(eventNodes = it.eventNodes.map { event -> if (event.id == nodeId) event.copy(resolved = !target.resolved) else event }) else it }
                refreshEventNodesForBranch(branchId, reportFailure = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { if (it.currentBranchId == branchId) it.copy(eventActionErrors = it.eventActionErrors + (nodeId to "事件状态保存失败，请重试")) else it }
                return@launch
            }
            finally { _state.update { it.copy(eventBusyIds = it.eventBusyIds - nodeId) } }
        }
        if (RetainedChatSessions.stores.contains(sessionId)) {
            RetainedChatSessions.stores.retainJob(sessionId, writeJob, reportRunning = false)
        }
        writeJob.start()
    }

    private suspend fun readEventPage(
        branchId: String, query: String, resolved: Boolean?,
        beforeCreatedAt: Long? = null, beforeId: Long? = null, limit: Int,
    ): List<SessionEventNodeEntity> = if (query.isBlank() && resolved == null)
        eventNodeDao.getPageForBranch(sessionId, branchId, beforeCreatedAt, beforeId, limit)
    else eventNodeDao.getFilteredPageForBranch(sessionId, branchId, query.trim(), resolved,
        beforeCreatedAt, beforeId, limit)

    fun updateEventQuery(query: String) = updateEventCriteria(query.take(200), _state.value.eventResolvedFilter)

    fun updateEventResolvedFilter(resolved: Boolean?) = updateEventCriteria(_state.value.eventQuery, resolved)

    fun resetEventWindow() = updateEventCriteria(_state.value.eventQuery, _state.value.eventResolvedFilter, true)

    private fun saveEventCriteria(branchId: String, query: String, resolved: Boolean?) {
        savedStateHandle["event_criteria_branch_$sessionId"] = branchId
        savedStateHandle["event_query_$sessionId"] = query.take(200)
        if (resolved == null) savedStateHandle.remove<Boolean>("event_resolved_$sessionId")
        else savedStateHandle["event_resolved_$sessionId"] = resolved
        savedStateHandle.remove<Int>("event_window_size_$sessionId")
        savedStateHandle.remove<Long>("event_window_before_at_$sessionId")
        savedStateHandle.remove<Long>("event_window_before_id_$sessionId")
    }

    private fun saveEventWindow(current: ChatContract.State) {
        // A bounded descriptor only; Room remains the source of event rows. Saving
        // criteria with it scopes the cursor to the same line/query/filter.
        saveEventCriteria(current.currentBranchId, current.eventQuery, current.eventResolvedFilter)
        savedStateHandle["event_window_size_$sessionId"] = current.eventNodesWindowSize
        current.eventNodesBeforeCreatedAt?.let { savedStateHandle["event_window_before_at_$sessionId"] = it }
        current.eventNodesBeforeId?.let { savedStateHandle["event_window_before_id_$sessionId"] = it }
    }

    private fun updateEventCriteria(query: String, resolved: Boolean?, force: Boolean = false) {
        val current = _state.value
        if (!current.isReady || (!force && current.eventQuery == query && current.eventResolvedFilter == resolved)) return
        saveEventCriteria(current.currentBranchId, query, resolved)
        eventRefreshRevision.incrementAndGet()
        _state.update { it.copy(eventQuery = query, eventResolvedFilter = resolved,
            eventNodes = emptyList(), eventNodesLoaded = false, eventNodesWindowSize = EVENT_NODE_PAGE_SIZE,
            eventNodesBeforeCreatedAt = null, eventNodesBeforeId = null,
            eventNodesHasMore = false, eventNodesLoadingMore = false, eventNodesRefreshFailed = false, eventNodesLoadError = null) }
        loadEventNodesIfNeeded()
    }

    private suspend fun refreshEventNodesForBranch(branchId: String, reportFailure: Boolean = false) {
        val current = _state.value
        if (current.currentBranchId != branchId) return
        val revision = eventRefreshRevision.incrementAndGet()
        val requestedSize = current.eventNodesWindowSize
        try {
            val page = readEventPage(branchId, current.eventQuery, current.eventResolvedFilter,
                current.eventNodesBeforeCreatedAt, current.eventNodesBeforeId, requestedSize + 1)
            _state.update {
                if (it.currentBranchId == branchId && eventRefreshRevision.get() == revision &&
                    it.eventNodesWindowSize == requestedSize) it.copy(
                    eventNodes = page.take(requestedSize), eventNodesLoaded = true,
                    eventNodesHasMore = page.size > requestedSize,
                    eventNodesLoadingMore = false, eventNodesRefreshFailed = false, eventNodesLoadError = null,
                ) else it
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            _state.update {
                if (it.currentBranchId == branchId && eventRefreshRevision.get() == revision &&
                    it.eventNodesWindowSize == requestedSize) it.copy(
                    eventNodesLoadingMore = false,
                    eventNodesRefreshFailed = true, eventNodesLoadError = "事件列表刷新失败，请重试",
                    error = if (reportFailure) "修改已保存，事件列表刷新失败，请重试" else it.error,
                ) else it
            }
        }
    }

    fun loadEventNodesIfNeeded() {
        val current = _state.value
        if (!current.isReady || current.eventNodesLoaded || current.eventNodesLoadingMore) return
        val branchId = current.currentBranchId
        eventPanelRequestedBranchId = branchId
        val revision = eventRefreshRevision.incrementAndGet()
        _state.update { state -> if (state.currentBranchId == branchId)
            state.copy(eventNodesLoadingMore = true, eventNodesRefreshFailed = false, eventNodesLoadError = null) else state }
        viewModelScope.launch {
            try {
                val page = readEventPage(branchId, current.eventQuery, current.eventResolvedFilter,
                    current.eventNodesBeforeCreatedAt, current.eventNodesBeforeId,
                    limit = current.eventNodesWindowSize + 1)
                _state.update { state -> if (state.currentBranchId == branchId && eventRefreshRevision.get() == revision)
                    state.copy(eventNodes = page.take(current.eventNodesWindowSize), eventNodesLoaded = true,
                        eventNodesHasMore = page.size > current.eventNodesWindowSize,
                    ) else state }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { state -> if (state.currentBranchId == branchId && eventRefreshRevision.get() == revision)
                    state.copy(eventNodesLoadError = "事件读取失败，请重试") else state }
            } finally {
                _state.update { state -> if (state.currentBranchId == branchId && eventRefreshRevision.get() == revision)
                    state.copy(eventNodesLoadingMore = false) else state }
            }
        }
    }

    fun loadMoreEventNodes() {
        val current = _state.value
        if (!current.eventNodesLoaded) { loadEventNodesIfNeeded(); return }
        if (!current.isReady || current.eventNodesLoadingMore) return
        if (current.eventNodesRefreshFailed) {
            viewModelScope.launch { refreshEventNodesForBranch(current.currentBranchId) }
            return
        }
        if (!current.eventNodesHasMore) return
        val branchId = current.currentBranchId
        val tail = current.eventNodes.lastOrNull() ?: return
        val revision = eventRefreshRevision.get()
        _state.update { it.copy(eventNodesLoadingMore = true, eventNodesRefreshFailed = false, eventNodesLoadError = null) }
        viewModelScope.launch {
            try {
                val page = readEventPage(branchId, current.eventQuery, current.eventResolvedFilter,
                    tail.createdAt, tail.id, EVENT_NODE_PAGE_SIZE + 1)
                _state.update { state ->
                    if (eventRefreshRevision.get() != revision || state.currentBranchId != branchId ||
                        state.eventNodes.lastOrNull()?.id != tail.id) state
                    else {
                        val window = appendEventPage(state.eventNodes, page.take(EVENT_NODE_PAGE_SIZE),
                            state.eventNodesBeforeCreatedAt, state.eventNodesBeforeId)
                        state.copy(eventNodes = window.rows,
                            eventNodesWindowSize = (state.eventNodesWindowSize + EVENT_NODE_PAGE_SIZE).coerceAtMost(EVENT_NODE_WINDOW_SIZE),
                            eventNodesBeforeCreatedAt = window.beforeCreatedAt, eventNodesBeforeId = window.beforeId,
                            eventNodesHasMore = page.size > EVENT_NODE_PAGE_SIZE)
                    }
                }
                val accepted = _state.value
                if (eventRefreshRevision.get() == revision && accepted.currentBranchId == branchId &&
                    accepted.eventNodes.lastOrNull()?.id == (page.take(EVENT_NODE_PAGE_SIZE).lastOrNull()?.id ?: tail.id)) {
                    saveEventWindow(accepted)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { state -> if (eventRefreshRevision.get() == revision && state.currentBranchId == branchId)
                    state.copy(eventNodesLoadError = "较早事件读取失败，请重试") else state }
            } finally {
                _state.update { state -> if (eventRefreshRevision.get() == revision && state.currentBranchId == branchId)
                    state.copy(eventNodesLoadingMore = false) else state }
            }
        }
    }

    fun loadEncyclopediaFoundationIfNeeded(force: Boolean = false) {
        val current = _state.value
        if (!current.isReady || current.encyclopediaFoundationLoading ||
            (current.encyclopediaFoundationLoaded && !force)) return
        val world = current.world
        val encyclopediaId = world?.encyclopediaId
        if (world == null || encyclopediaId == null) {
            _state.update { it.copy(encyclopediaFoundation = "",
                encyclopediaFoundationLoaded = true, encyclopediaFoundationLoadError = null) }
            return
        }
        val revision = encyclopediaFoundationRevision.incrementAndGet()
        val worldPrompt = world.worldPrompt
        _state.update { it.copy(encyclopediaFoundationLoading = true,
            encyclopediaFoundationLoadError = null) }
        viewModelScope.launch {
            try {
                val foundation = withContext(preparationDispatcher) {
                    contextBuilder.encyclopediaFoundation(world)
                }
                _state.update { state -> if (encyclopediaFoundationRevision.get() == revision &&
                    state.world?.encyclopediaId == encyclopediaId && state.world?.worldPrompt == worldPrompt)
                    state.copy(encyclopediaFoundation = foundation,
                        encyclopediaFoundationLoaded = true, encyclopediaFoundationLoadError = null)
                    else state }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { state -> if (encyclopediaFoundationRevision.get() == revision &&
                    state.world?.encyclopediaId == encyclopediaId && state.world?.worldPrompt == worldPrompt)
                    state.copy(encyclopediaFoundationLoadError = "百科基础设定读取失败，请重试")
                    else state }
            } finally {
                _state.update { state -> if (encyclopediaFoundationRevision.get() == revision &&
                    state.world?.encyclopediaId == encyclopediaId && state.world?.worldPrompt == worldPrompt)
                    state.copy(encyclopediaFoundationLoading = false) else state }
            }
        }
    }

    fun loadContextMemoryIfNeeded(force: Boolean = false) {
        val current = _state.value
        if (!current.isReady || current.contextMemoryLoading ||
            (current.contextMemoryLoaded && !force)) return
        val branchId = current.currentBranchId
        val revision = contextMemoryDisplayRevision.incrementAndGet()
        _state.update { state -> if (state.currentBranchId == branchId)
            state.copy(contextMemoryLoading = true, contextMemoryLoadError = null) else state }
        viewModelScope.launch {
            try {
                val memory = withContext(preparationDispatcher) {
                    universalContextMemoryManager.getFormattedMemory(sessionId, branchId)
                }
                _state.update { state -> if (state.currentBranchId == branchId &&
                    contextMemoryDisplayRevision.get() == revision) state.copy(
                    contextMemoryText = memory,
                    contextMemoryLoaded = true,
                    contextMemoryLoadError = null,
                ) else state }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { state -> if (state.currentBranchId == branchId &&
                    contextMemoryDisplayRevision.get() == revision)
                    state.copy(contextMemoryLoadError = "长期记忆读取失败，请重试") else state }
            } finally {
                _state.update { state -> if (state.currentBranchId == branchId &&
                    contextMemoryDisplayRevision.get() == revision)
                    state.copy(contextMemoryLoading = false) else state }
            }
        }
    }

    private suspend fun publishContextMemoryResult(
        branchId: String,
        result: UniversalContextMemoryUpdateResult,
    ) {
        val current = _state.value
        val refreshDisplay = result != UniversalContextMemoryUpdateResult.SUPERSEDED &&
            current.currentBranchId == branchId &&
            (current.contextMemoryLoaded || current.contextMemoryLoading)
        val revision = if (refreshDisplay) contextMemoryDisplayRevision.incrementAndGet()
            else contextMemoryDisplayRevision.get()
        val memory = if (refreshDisplay) try {
            withContext(preparationDispatcher) {
                universalContextMemoryManager.getFormattedMemory(sessionId, branchId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } else null
        _state.update { state ->
            val updated = state.withContextMemoryResult(branchId, result, state.contextMemoryText)
            if (!refreshDisplay || state.currentBranchId != branchId ||
                contextMemoryDisplayRevision.get() != revision) updated
            else updated.copy(
                contextMemoryText = memory ?: state.contextMemoryText,
                contextMemoryLoaded = memory != null || state.contextMemoryLoaded,
                contextMemoryLoading = false,
                contextMemoryLoadError = if (memory == null) "长期记忆读取失败，请重试" else null,
            )
        }
    }

    private suspend fun readMemorySummaryWindow(branchId: String, request: ChatContract.State): List<SessionMemorySegmentEntity> =
        if (request.memorySegmentsBeforeEndId != null && request.memorySegmentsBeforeId != null)
            memorySegmentDao.getOlderForBranch(sessionId, branchId, request.memorySegmentsBeforeEndId,
                request.memorySegmentsBeforeId, request.memorySegmentsWindowSize + 1)
        else memorySegmentDao.getRecentForBranch(sessionId, branchId, request.memorySegmentsWindowSize + 1)

    private fun saveMemorySummaryWindow(current: ChatContract.State) {
        savedStateHandle["summary_window_branch_$sessionId"] = current.currentBranchId
        savedStateHandle["summary_window_size_$sessionId"] = current.memorySegmentsWindowSize
        if (current.memorySegmentsBeforeEndId == null) savedStateHandle.remove<Long>("summary_window_before_end_$sessionId")
        else savedStateHandle["summary_window_before_end_$sessionId"] = current.memorySegmentsBeforeEndId
        if (current.memorySegmentsBeforeId == null) savedStateHandle.remove<Long>("summary_window_before_id_$sessionId")
        else savedStateHandle["summary_window_before_id_$sessionId"] = current.memorySegmentsBeforeId
    }

    fun resetMemorySummaryWindow() {
        val current = _state.value
        if (!current.isReady || current.isGenerating || current.memoryOperationRunning) return
        memorySummaryListRevision.incrementAndGet()
        val reset = current.copy(memorySegments = emptyList(), memorySegmentsLoaded = false,
            memorySegmentsLoading = false, memorySegmentsLoadingMore = false,
            memorySegmentsWindowSize = MEMORY_SEGMENT_PAGE_SIZE,
            memorySegmentsBeforeEndId = null, memorySegmentsBeforeId = null,
            memorySegmentsHasMore = false, memorySegmentsLoadError = null)
        _state.value = reset
        saveMemorySummaryWindow(reset)
        loadMemorySummariesIfNeeded()
    }

    fun loadMemorySummariesIfNeeded() {
        val current = _state.value
        if (!current.isReady || current.memorySegmentsLoaded || current.memorySegmentsLoading) return
        val branchId = current.currentBranchId
        val revision = memorySummaryListRevision.incrementAndGet()
        _state.update { state -> if (state.currentBranchId == branchId)
            state.copy(memorySegmentsLoading = true, memorySegmentsLoadingMore = false,
                memorySegmentsLoadError = null) else state }
        viewModelScope.launch {
            try {
                val page = readMemorySummaryWindow(branchId, current)
                _state.update { state -> if (state.currentBranchId == branchId &&
                    memorySummaryListRevision.get() == revision) state.copy(
                    memorySegments = page.take(current.memorySegmentsWindowSize),
                    memorySegmentsLoaded = true,
                    memorySegmentsHasMore = page.size > current.memorySegmentsWindowSize,
                    memorySegmentsLoadError = null,
                ) else state }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { state -> if (state.currentBranchId == branchId &&
                    memorySummaryListRevision.get() == revision)
                    state.copy(memorySegmentsLoadError = "摘要读取失败，请重试") else state }
            } finally {
                _state.update { state -> if (state.currentBranchId == branchId &&
                    memorySummaryListRevision.get() == revision)
                    state.copy(memorySegmentsLoading = false) else state }
            }
        }
    }

    private suspend fun refreshMemorySummaryPage(branchId: String) {
        val current = _state.value
        if (current.currentBranchId != branchId ||
            (!current.memorySegmentsLoaded && !current.memorySegmentsLoading &&
                current.memorySegmentsLoadError == null)) return
        val revision = memorySummaryListRevision.incrementAndGet()
        _state.update { state -> if (state.currentBranchId == branchId)
            state.copy(memorySegmentsLoading = true, memorySegmentsLoadingMore = false,
                memorySegmentsLoadError = null) else state }
        try {
            val page = readMemorySummaryWindow(branchId, current)
            _state.update { state -> if (state.currentBranchId == branchId &&
                memorySummaryListRevision.get() == revision) state.copy(
                memorySegments = page.take(current.memorySegmentsWindowSize),
                memorySegmentsLoaded = true,
                memorySegmentsLoading = false,
                memorySegmentsHasMore = page.size > current.memorySegmentsWindowSize,
                memorySegmentsLoadingMore = false,
                memorySegmentsLoadError = null,
            ) else state }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            _state.update { state -> if (state.currentBranchId == branchId &&
                memorySummaryListRevision.get() == revision) state.copy(
                memorySegmentsLoading = false,
                memorySegmentsHasMore = false,
                memorySegmentsLoadError = "摘要列表刷新失败，请重试",
            ) else state }
            throw failure
        } finally {
            _state.update { state -> if (state.currentBranchId == branchId &&
                memorySummaryListRevision.get() == revision)
                state.copy(memorySegmentsLoading = false) else state }
        }
    }

    fun loadMoreMemorySummaries() {
        val current = _state.value
        if (!current.memorySegmentsLoaded) {
            loadMemorySummariesIfNeeded()
            return
        }
        if (current.memorySegmentsLoadError != null && !current.memorySegmentsHasMore &&
            !current.memorySegmentsLoading && !current.memorySegmentsLoadingMore) {
            viewModelScope.launch {
                try { refreshMemorySummaryPage(current.currentBranchId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The panel keeps its retryable read error. */ }
            }
            return
        }
        if (!current.isReady || current.isGenerating || current.memoryOperationRunning ||
            !current.memorySegmentsHasMore || current.memorySegmentsLoading ||
            current.memorySegmentsLoadingMore) return
        val branchId = current.currentBranchId
        val tail = current.memorySegments.lastOrNull() ?: return
        val revision = memorySummaryListRevision.get()
        _state.update { it.copy(memorySegmentsLoadingMore = true, memorySegmentsLoadError = null) }
        viewModelScope.launch {
            try {
                val page = memorySegmentDao.getOlderForBranch(sessionId, branchId, tail.endMessageId, tail.id, MEMORY_SEGMENT_PAGE_SIZE + 1)
                _state.update { state ->
                    if (memorySummaryListRevision.get() != revision || state.currentBranchId != branchId ||
                        state.memorySegments.lastOrNull()?.id != tail.id) state
                    else {
                        val combined = (state.memorySegments + page.take(MEMORY_SEGMENT_PAGE_SIZE)).distinctBy { it.id }
                        val dropped = (combined.size - MEMORY_SEGMENT_WINDOW_SIZE).coerceAtLeast(0)
                        val boundary = combined.getOrNull(dropped - 1)
                        state.copy(
                            memorySegments = combined.drop(dropped),
                            memorySegmentsWindowSize = (state.memorySegmentsWindowSize + MEMORY_SEGMENT_PAGE_SIZE).coerceAtMost(MEMORY_SEGMENT_WINDOW_SIZE),
                            memorySegmentsBeforeEndId = boundary?.endMessageId ?: state.memorySegmentsBeforeEndId,
                            memorySegmentsBeforeId = boundary?.id ?: state.memorySegmentsBeforeId,
                            memorySegmentsHasMore = page.size > MEMORY_SEGMENT_PAGE_SIZE,
                        )
                    }
                }
                val accepted = _state.value
                if (accepted.currentBranchId == branchId && memorySummaryListRevision.get() == revision &&
                    accepted.memorySegments.lastOrNull()?.id != tail.id) saveMemorySummaryWindow(accepted)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { if (it.currentBranchId == branchId &&
                memorySummaryListRevision.get() == revision)
                it.copy(memorySegmentsLoadError = "较早摘要读取失败，请重试") else it } }
            finally { _state.update { if (it.currentBranchId == branchId &&
                memorySummaryListRevision.get() == revision)
                it.copy(memorySegmentsLoadingMore = false) else it } }
        }
    }

    /** Edits only a summary owned by the visible story line. The original messages remain authoritative. */
    suspend fun resolveMemorySummaryEditor(segmentId: Long, branchId: String): SessionMemorySegmentEntity? {
        if (!_state.value.isReady || _state.value.currentBranchId != branchId) return null
        branchVisibilityIndexManager.ensureReady()
        val segment = if (branchId == "main") memorySegmentDao.getById(segmentId)
            else memorySegmentDao.getVisibleById(sessionId, branchId, segmentId)
        return segment?.takeIf {
            _state.value.isReady && _state.value.currentBranchId == branchId &&
                it.sessionId == sessionId && it.branchId == branchId
        }
    }

    fun editMemorySummary(segment: SessionMemorySegmentEntity, summary: String, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val current = _state.value
        if (!current.isReady || current.isGenerating || current.memoryOperationRunning ||
            current.currentBranchId != segment.branchId || summary.isBlank()) return
        _state.update { it.copy(memoryOperationRunning = true, memorySummaryEditSavedId = null, memorySummaryEditSavedText = null) }
        val writeJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                branchVisibilityIndexManager.ensureReady()
                check(_state.value.currentBranchId == segment.branchId && !_state.value.isGenerating)
                when (summaryMaintenance.edit(sessionId, segment.branchId, segment.id, segment.summary, summary)) {
                    SummaryMaintenanceResult.Updated -> {
                        _state.update { it.copy(memorySummaryEditSavedId = segment.id, memorySummaryEditSavedText = summary.trim()) }
                        val message = try {
                            refreshMemorySummaryPage(segment.branchId)
                            "摘要已更新，相关上下文将在下次整理时重建"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { "摘要已保存，列表读取失败，请重试读取" }
                        onDone(true, message)
                    }
                    SummaryMaintenanceResult.NotOwned -> onDone(false, "只能修改当前故事线自行生成的摘要")
                    SummaryMaintenanceResult.Conflict -> onDone(false, "摘要已被其他操作更新，请重新打开后再编辑")
                    SummaryMaintenanceResult.Deleted -> onDone(false, "摘要状态已变化，请刷新列表")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { onDone(false, failure.message ?: "摘要保存失败，请重试") }
            finally { _state.update { it.copy(memoryOperationRunning = false) } }
        }
        if (RetainedChatSessions.stores.contains(sessionId)) {
            RetainedChatSessions.stores.retainJob(sessionId, writeJob, reportRunning = false)
        }
        writeJob.start()
    }

    /** Deletes the selected owned summary and later derived summaries on that line. */
    fun deleteMemorySummary(segment: SessionMemorySegmentEntity, onDone: (String) -> Unit = {}) {
        val current = _state.value
        if (!current.isReady || current.isGenerating || current.memoryOperationRunning ||
            current.currentBranchId != segment.branchId) return
        _state.update { it.copy(memoryOperationRunning = true) }
        viewModelScope.launch {
            try {
                branchVisibilityIndexManager.ensureReady()
                check(_state.value.currentBranchId == segment.branchId && !_state.value.isGenerating)
                when (summaryMaintenance.delete(sessionId, segment.branchId, segment.id, segment.summary)) {
                    SummaryMaintenanceResult.Deleted -> {
                        refreshMemorySummaryPage(segment.branchId)
                        onDone("摘要及后续自动摘要已删除，原始对话仍保留")
                    }
                    SummaryMaintenanceResult.NotOwned -> onDone("只能删除当前故事线自行生成的摘要")
                    SummaryMaintenanceResult.Conflict -> onDone("摘要已不存在或被其他操作更新，请刷新列表")
                    SummaryMaintenanceResult.Updated -> onDone("摘要状态已变化，请刷新列表")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { onDone(failure.message ?: "摘要删除失败，请重试") }
            finally { _state.update { it.copy(memoryOperationRunning = false) } }
        }
    }

    fun continueCurrentStorySummary(onDone: (String) -> Unit = {}) {
        if (_state.value.memoryOperationRunning || _state.value.isGenerating ||
            activeGeneration != null || branchTransitionJob?.isActive == true) return
        val branchId = currentBranchId()
        val maintenancePlatform = try { requestPlatform() } catch (_: Exception) {
            onDone("所选平台或模型已变更，请重新选择后重试")
            return
        }
        _state.update { it.copy(memoryOperationRunning = true, manualCompactionRunning = true, manualCompactionChunk = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            try {
                val threshold = secureStorage.memoryCompactThreshold.coerceIn(10, 2000)
                val pending = memoryCompactor.pendingBatch(sessionId, branchId, threshold)
                if (pending.available < pending.required) {
                    onDone("还需 ${pending.required - pending.available} 条对话才会生成下一段摘要")
                    return@launch
                }
                val world = sessionWorldDao.getBySession(sessionId)
                val sessionThink = sessionDao.getById(sessionId)?.thinkMaxEnabled == true
                val character = _state.value.participants.firstOrNull()?.characterId?.let { characterDao.getById(it) }
                if (character == null) {
                    onDone("当前会话没有可用角色，无法整理摘要")
                    return@launch
                }
                val connection = chatConnection(world, character, maintenancePlatform)
                connection.error?.let { onDone(it); return@launch }
                val model = resolveChatLlmModel(character, sessionThink, connection, maintenancePlatform)
                val baseUrl = ApiRootLines.splitToOrderedDistinct(connection.baseUrl, llmApiService::normalizeOpenAiCompatibleBase)
                    .firstOrNull() ?: llmApiService.normalizeOpenAiCompatibleBase(connection.baseUrl.trim())
                if (connection.apiKey.isBlank() || baseUrl.isBlank() || model.isNullOrBlank()) {
                    onDone("当前线路未配置可用对话模型，无法整理摘要")
                    return@launch
                }
                val compacted = memoryCompactor.compactIfNeeded(
                    sessionId = sessionId,
                    branchId = branchId,
                    apiKey = connection.apiKey,
                    baseUrl = baseUrl,
                    model = model,
                    contextWindow = maintenancePlatform?.modelContextWindows?.get(model),
                    threshold = threshold,
                    scanHistoricalGaps = true,
                    onProgress = { chunk ->
                        _state.update { if (it.currentBranchId == branchId) it.copy(manualCompactionChunk = chunk) else it }
                    },
                )
                if (compacted) {
                    try {
                        refreshMemorySummaryPage(branchId)
                        onDone("当前故事线摘要已更新")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { onDone("摘要已保存，列表刷新失败，请重试读取") }
                } else {
                    onDone("本轮整理尚未完成，可再次继续；原文不受影响")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onDone("摘要整理失败，可重试；原文不受影响") }
            finally {
                if (manualCompactionJob === owner) {
                    manualCompactionJob = null
                    _state.update { it.copy(memoryOperationRunning = false, manualCompactionRunning = false, manualCompactionChunk = null) }
                }
            }
        }
        manualCompactionJob = job
        job.start()
    }

    fun stopCurrentStorySummary() {
        manualCompactionJob?.cancel()
    }

    fun rebuildCurrentContextMemory(onDone: (String) -> Unit = {}) {
        if (_state.value.memoryOperationRunning || _state.value.isGenerating || activeGeneration != null) return
        val maintenancePlatform = try { requestPlatform() } catch (_: Exception) {
            onDone("所选平台或模型已变更，请重新选择后重试")
            return
        }
        _state.update { it.copy(memoryOperationRunning = true) }
        viewModelScope.launch {
          try {
            val branchId = currentBranchId()
            val world = sessionWorldDao.getBySession(sessionId)
            val firstCharacter = _state.value.participants.firstOrNull()?.let { participant ->
                characterDao.getById(participant.characterId)
            }
            val resolvedCharacter = firstCharacter ?: CharacterEntity()
            val connection = chatConnection(world, resolvedCharacter, maintenancePlatform)
            connection.error?.let { onDone(it); return@launch }
            val apiKey = connection.apiKey
            val baseUrl = connection.baseUrl
            val model = resolveMainChatModelId(resolvedCharacter, connection, maintenancePlatform)
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
                contextWindow = maintenancePlatform?.modelContextWindows?.get(model),
                worldText = world?.worldPrompt.orEmpty(),
                activeCharacterNames = _state.value.characterNames.values.toList(),
            )
            val revision = if (_state.value.currentBranchId == branchId)
                contextMemoryDisplayRevision.incrementAndGet() else null
            val memory = try {
                withContext(preparationDispatcher) {
                    universalContextMemoryManager.getFormattedMemory(sessionId, branchId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            _state.update { state ->
                if (revision == null || state.currentBranchId != branchId ||
                    contextMemoryDisplayRevision.get() != revision) state
                else state.copy(
                    contextMemoryText = memory ?: state.contextMemoryText,
                    contextMemoryLoaded = memory != null || state.contextMemoryLoaded,
                    contextMemoryLoading = false,
                    contextMemoryLoadError = if (memory == null) "长期记忆读取失败，请重试" else null,
                    contextMemoryStatus = if (ok) ContextMemoryStatus.UPDATED else ContextMemoryStatus.FAILED,
                )
            }
            onDone(when {
                ok && memory == null -> "记忆已重建，显示刷新失败，请重试读取"
                ok -> "已重建当前会话记忆"
                else -> "重建失败，现有记忆未被清空"
            })
          } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { onDone("重建失败，请重试") }
          finally { _state.update { it.copy(memoryOperationRunning = false) } }
        }
    }

    fun clearCurrentContextMemory(expectedBranchId: String? = null, onDone: (String) -> Unit = {}) {
        if (_state.value.memoryOperationRunning || _state.value.isGenerating) return
        val branchId = expectedBranchId ?: currentBranchId()
        if (_state.value.currentBranchId != branchId) {
            onDone("故事线已切换，未清空记忆，请重试")
            return
        }
        _state.update { it.copy(memoryOperationRunning = true) }
        _state.update { it.copy(contextMemoryClearError = null) }
        viewModelScope.launch {
            try {
                if (_state.value.currentBranchId != branchId) {
                    onDone("故事线已切换，未清空记忆，请重试")
                    return@launch
                }
                universalContextMemoryManager.clear(sessionId, branchId)
                if (_state.value.currentBranchId == branchId) contextMemoryDisplayRevision.incrementAndGet()
                _state.update { if (it.currentBranchId == branchId) it.copy(
                    contextMemoryText = "",
                    contextMemoryLoaded = true,
                    contextMemoryLoading = false,
                    contextMemoryLoadError = null,
                    contextMemoryClearError = null,
                    contextMemoryStatus = ContextMemoryStatus.IDLE,
                ) else it }
                onDone("已清空长期记忆；后续对话会重新整理")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { if (it.currentBranchId == branchId) it.copy(
                    contextMemoryClearError = "长期记忆清空失败，请重试",
                    contextMemoryStatus = ContextMemoryStatus.FAILED,
                ) else it }
                onDone("清空失败，请重试")
            }
            finally { _state.update { it.copy(memoryOperationRunning = false) } }
        }
    }

    fun saveSessionWorldCredentials(
        draft: SessionWorldCredentialDraft,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (_state.value.worldCredentialsSaving || _state.value.worldSettingSaving || !canMutateRoundConfiguration()) {
            onResult(false)
            return
        }
        _state.update { it.copy(worldCredentialsSaving = true) }
        val writeJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val updated = run {
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
                }
                _state.update { it.copy(world = updated) }
                onResult(true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = "本场线路保存失败，请重试") }
                onResult(false)
                return@launch
            } finally { _state.update { it.copy(worldCredentialsSaving = false) } }
        }
        if (RetainedChatSessions.stores.contains(sessionId)) {
            RetainedChatSessions.stores.retainJob(sessionId, writeJob, reportRunning = false)
        }
        writeJob.start()
    }

    fun updateWorldSetting(key: String, value: Boolean) {
        if (key !in setOf("narratorEnabled", "choiceGenerationEnabled", "antiCheatEnabled",
                "autoSedimentEnabled", "autoCharacterImageGen", "autoCharacterSpeech")) return
        if (_state.value.worldSettingSaving || _state.value.worldCredentialsSaving) return
        if (!canMutateRoundConfiguration()) return
        _state.update { it.copy(worldSettingSaving = true) }
        val writeJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val world = sessionWorldDao.getBySession(sessionId) ?: SessionWorldEntity(sessionId = sessionId)
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
                _state.update { it.copy(world = updated) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = "本场玩法保存失败，请重试") }
            } finally { _state.update { it.copy(worldSettingSaving = false) } }
        }
        if (RetainedChatSessions.stores.contains(sessionId)) {
            RetainedChatSessions.stores.retainJob(sessionId, writeJob, reportRunning = false)
        }
        writeJob.start()
    }

    fun handleMessageAction(action: MessageAction) {
        when (action) {
            is MessageAction.Speak -> speakMessage(ChatMessageTextFormat.visibleBody(action.message.content, action.message.speakerType), action.message.characterId)
            is MessageAction.RetryAutoImage -> { retryAutoCharacterImage(action.message.id) }
            is MessageAction.RetryAutoVoice -> { retryAutoCharacterVoice(action.message.id) }
            is MessageAction.PlayVoiceAttachments -> { playVoiceAttachments(action.message.id) }
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
            is MessageAction.SetContextExcluded -> setMessageContextExcluded(action.message.id, action.excluded)
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

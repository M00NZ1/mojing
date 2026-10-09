package com.mojing.app.ui.story

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.StoryOpeningDraftStore
import com.mojing.app.data.StoryOpeningInputDraft
import com.mojing.app.data.StoryOpeningInputDraftStore
import com.mojing.app.data.StoryOpeningGenerationState
import com.mojing.app.data.UnreadableStoryDraft
import com.mojing.app.data.UnreadableStoryInputDraft
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.dao.NewSessionCharacterOption
import com.mojing.app.data.local.dao.NewSessionWorldOption
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.domain.story.StoryWritingRequest
import com.mojing.app.domain.story.StoryWritingUseCase
import com.mojing.app.domain.story.StoryWritingProgress
import com.mojing.app.domain.story.StoryCanon
import com.mojing.app.domain.story.StoryOpeningDraft
import com.mojing.app.domain.story.StoryOpeningRecord
import com.mojing.app.domain.usecase.CreateSessionUseCase
import com.mojing.app.ui.session.NewSessionCharacterPage
import com.mojing.app.ui.session.NewSessionWorldPage
import com.mojing.app.ui.session.NewSessionWorldSelection
import com.mojing.app.ui.session.NEW_SESSION_PICKER_PAGE_SIZE
import com.mojing.app.ui.util.UserFacingStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

data class StorySimulationState(
    val premise: String = "",
    val direction: String = "",
    val tone: String = StoryOpeningInputDraft.DEFAULT_TONE,
    val chapterCount: Int = 3,
    val selectedTemplate: WorldTemplateEntity? = null,
    val selectedEncyclopedia: EncyclopediaEntity? = null,
    val selectedCharacterIdsAvailable: Set<Long> = emptySet(),
    val selectedCharacterCards: List<com.mojing.app.data.local.dao.ChatCharacterPresentationRow> = emptyList(),
    val selectionsResolved: Boolean = false,
    val selectionsLoading: Boolean = false,
    val selectionsError: String? = null,
    val selectedTemplateId: Long? = null,
    val selectedEncyclopediaId: Long? = null,
    val selectedCharacterIds: Set<Long> = emptySet(),
    val isGenerating: Boolean = false,
    val isSaving: Boolean = false,
    val hasPendingStory: Boolean = false,
    val savedSessionId: Long? = null,
    val savedSessionMissing: Boolean = false,
    val isRestoring: Boolean = true,
    val recoveryError: String? = null,
    val canCopyRecoveryData: Boolean = false,
    val recoveredStory: Boolean = false,
    val recoveredInputDraft: Boolean = false,
    val hasInputDraft: Boolean = false,
    val storyTitle: String = "",
    val draftPersisted: Boolean = false,
    val error: String? = null,
    val generationStage: String? = null,
    val generationModel: String? = null,
    val generationElapsedMs: Long = 0L,
    val firstContentDelayMs: Long? = null,
    val receivedChars: Int = 0,
    val completedChapters: Int = 0,
    val totalChapters: Int = 0,
    val preview: String = "",
    val completedChapterDrafts: List<com.mojing.app.domain.story.StoryChapter> = emptyList(),
    val partialPreview: String = "",
    val generationContentAvailable: Boolean = false,
    val hasInterruptedGeneration: Boolean = false,
    val requestToken: Long = 0L,
    val inputRevision: Long = 0L,
)

internal fun StorySimulationState.hasUnavailableSelections(): Boolean =
    selectionsResolved && selectionsError == null && (
        (selectedTemplateId != null && selectedTemplate?.id != selectedTemplateId) ||
            (selectedEncyclopediaId != null && selectedEncyclopedia?.id != selectedEncyclopediaId) ||
            selectedCharacterIds.any { it !in selectedCharacterIdsAvailable }
        )

private data class StoryCreationContext(
    val premise: String,
    val direction: String,
    val tone: String,
    val chapterCount: Int,
    val template: WorldTemplateEntity?,
    val encyclopedia: EncyclopediaEntity?,
    val characters: List<CharacterEntity>,
)

private data class StoryGenerationProgressSnapshot(
    val token: Long,
    val requestId: String,
    val progress: StoryWritingProgress,
)

@HiltViewModel
class StorySimulationViewModel internal constructor(
    private val storyWriting: StoryWritingUseCase,
    private val secureStorage: SecureStorage,
    private val templateDao: WorldTemplateDao,
    private val encyclopediaDao: EncyclopediaDao,
    private val characterDao: CharacterDao,
    private val createSession: CreateSessionUseCase,
    private val draftStore: StoryOpeningDraftStore,
    private val inputDraftStore: StoryOpeningInputDraftStore,
    private val worker: kotlinx.coroutines.CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(storyWriting: StoryWritingUseCase, secureStorage: SecureStorage, templateDao: WorldTemplateDao,
        encyclopediaDao: EncyclopediaDao, characterDao: CharacterDao, createSession: CreateSessionUseCase,
        draftStore: StoryOpeningDraftStore, inputDraftStore: StoryOpeningInputDraftStore)
        : this(storyWriting, secureStorage, templateDao, encyclopediaDao, characterDao, createSession, draftStore, inputDraftStore, Dispatchers.IO)
    private val _state = MutableStateFlow(StorySimulationState())
    val state: StateFlow<StorySimulationState> = _state.asStateFlow()
    private var selectionJob: Job? = null
    private var selectionRevision = 0
    private val creationJob = AtomicReference<Job?>(null)
    private var pendingStory: StoryOpeningDraft? = null
    private var savedDraftId: String? = null
    private var unreadableDraft: String? = null
    private var unreadableInputDraft: String? = null
    private var restoreJob: Job? = null
    private var generationToken = 0L
    private var generationStartedAtNanos = 0L
    private var activeGenerationId: String? = null
    private var activeGenerationInput: StoryOpeningInputDraft? = null
    private var lastPersistedPreviewLength = 0
    private var lastPersistedPreviewElapsedMs = 0L
    private val generationPersistMutex = Mutex()
    private val latestGenerationProgress = AtomicReference<StoryGenerationProgressSnapshot?>(null)
    private val latestCompletedChapterDrafts = AtomicReference<List<com.mojing.app.domain.story.StoryChapter>>(emptyList())
    private val generationWriteMutex = Mutex()
    private var generationWriteTail: Job? = null
    private val generationWriteFailure = AtomicReference<Throwable?>(null)
    private data class FailedGenerationDelta(val requestId: String, val batchIndex: Int, val delta: String, val offset: Int?)
    private val failedGenerationDeltas = mutableListOf<FailedGenerationDelta>()

    init {
        retryRecovery()
    }

    fun retryRecovery() {
        if (restoreJob?.isActive == true || creationJob.get() != null || pendingStory != null) return
        restoreJob = viewModelScope.launch {
            _state.update { it.copy(isRestoring = true, recoveryError = null) }
            try {
                val record = draftStore.load()
                unreadableDraft = null
                unreadableInputDraft = null
                when (record) {
                    null -> {
                        val generation = inputDraftStore.loadGeneration()?.takeIf { it.requestId.isNotBlank() }
                        val input = if (generation == null) inputDraftStore.load() else null
                        val recoveredChapters = generation?.let { inputDraftStore.loadCompletedChapters(it) }.orEmpty()
                        val recoveredRaw = generation?.let { inputDraftStore.loadGenerationContent(it) }
                        val recoveredBatches = generation?.let { inputDraftStore.loadGenerationBatches(it) }.orEmpty()
                        val parsedRecovery = withContext(worker) {
                            com.mojing.app.domain.story.StoryRecoveryParser.parse(recoveredBatches, recoveredChapters.size, recoveredChapters)
                        }
                        val restoredChapters = recoveredChapters + parsedRecovery.chapters
                        val restoredPartial = parsedRecovery.partialChapter?.content
                            ?: generation?.let { if (it.contentFileName == null) it.preview else "" }.orEmpty()
                        _state.update { current ->
                            if (generation != null) current.copy(isRestoring = false, canCopyRecoveryData = false,
                                recoveredInputDraft = true, hasInputDraft = true, hasInterruptedGeneration = true,
                                premise = generation.input.premise, direction = generation.input.direction, tone = generation.input.tone,
                                chapterCount = generation.input.chapterCount, selectedTemplateId = generation.input.templateId,
                                selectedEncyclopediaId = generation.input.encyclopediaId, selectedCharacterIds = generation.input.characterIds,
                                generationModel = generation.model, generationStage = "上次生成中断", receivedChars = generation.receivedChars,
                                generationElapsedMs = generation.elapsedMs, preview = generation.preview,
                                completedChapterDrafts = restoredChapters,
                                partialPreview = restoredPartial,
                                generationContentAvailable = recoveredBatches.isNotEmpty())
                            else if (input == null) current.copy(isRestoring = false, canCopyRecoveryData = false)
                            else current.copy(isRestoring = false, canCopyRecoveryData = false, recoveredInputDraft = true,
                                hasInputDraft = true, premise = input.premise, direction = input.direction, tone = input.tone,
                                chapterCount = input.chapterCount, selectedTemplateId = input.templateId,
                                selectedEncyclopediaId = input.encyclopediaId, selectedCharacterIds = input.characterIds)
                        }
                        if (generation != null && generation.input.chapterCount <= 3 && recoveredBatches.size == 1) {
                            val complete = recoveredRaw?.let { raw ->
                                withContext(worker) {
                                    runCatching { storyWriting.parse(raw, generation.input.chapterCount, generation.input.premise) }.getOrNull()
                                }
                            }
                            if (complete != null) {
                                runCatching {
                                    val context = creationContext(_state.value)
                                    val world = buildString {
                                        context.template?.let { appendLine("世界模板：${it.label}"); appendLine(it.summary); appendLine(it.worldPrompt) }
                                        context.encyclopedia?.let { appendLine("世界百科：${it.name}"); appendLine(it.description); appendLine(it.worldPrompt) }
                                    }.trim()
                                    val characters = context.characters.joinToString("\n") { "${it.name}：${it.personaPrompt.take(1600)}" }
                                    pendingStory = StoryOpeningDraft(premise = context.premise, direction = context.direction, tone = context.tone,
                                        template = context.template, encyclopediaId = context.encyclopedia?.id, characterIds = context.characters.map { it.id },
                                        worldPrompt = StoryCanon.persistentWorldPrompt(context.premise, listOf(world, characters).filter(String::isNotBlank).joinToString("\n")),
                                        result = complete, model = generation.model)
                                    _state.update { it.copy(hasPendingStory = true, hasInterruptedGeneration = false,
                                        storyTitle = complete.title, draftPersisted = false, generationStage = "完整正文待保存",
                                        preview = complete.chapters.joinToString("\n\n") { chapter -> "${chapter.title}\n${chapter.content}" }) }
                                }
                            }
                        }
                    }
                    is StoryOpeningRecord.Pending -> {
                        val draft = record.draft
                        pendingStory = draft
                        _state.update { it.copy(isRestoring = false, hasPendingStory = true, recoveredStory = true,
                            recoveredInputDraft = true, draftPersisted = true, hasInputDraft = true,
                            premise = draft.premise, direction = draft.direction, tone = draft.tone,
                            chapterCount = draft.result.chapters.size, storyTitle = draft.result.title,
                            selectedTemplateId = draft.template?.id, selectedEncyclopediaId = draft.encyclopediaId,
                            selectedCharacterIds = draft.characterIds.toSet(),
                            generationModel = draft.model, generationStage = "待保存的小说", canCopyRecoveryData = false,
                            preview = draft.result.chapters.joinToString("\n\n") { chapter -> chapter.content }.takeLast(MAX_PREVIEW_CHARS)) }
                    }
                    is StoryOpeningRecord.Saved -> {
                        savedDraftId = record.id
                        _state.update { it.copy(isRestoring = false, savedSessionId = record.sessionId,
                            storyTitle = record.title, generationStage = "小说已保存", recoveredStory = true,
                            savedSessionMissing = !record.sessionExists, canCopyRecoveryData = false) }
                    }
                }
                refreshSelections(removeIncompatible = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                unreadableDraft = (error as? UnreadableStoryDraft)?.raw
                unreadableInputDraft = (error as? UnreadableStoryInputDraft)?.raw
                _state.update { it.copy(isRestoring = false, recoveryError = "上次创作暂时无法读取，请重试。",
                    canCopyRecoveryData = unreadableDraft != null || unreadableInputDraft != null) }
            }
        }
    }

    internal suspend fun loadWorldPage(query: String, cursor: NewSessionWorldOption?): NewSessionWorldPage {
        val rows = templateDao.getNewSessionWorldPage(
            query.trim(), cursor?.kind, cursor?.pinnedAt, cursor?.updatedAt, cursor?.id, NEW_SESSION_PICKER_PAGE_SIZE + 1,
        )
        return NewSessionWorldPage(rows.take(NEW_SESSION_PICKER_PAGE_SIZE), rows.size > NEW_SESSION_PICKER_PAGE_SIZE)
    }

    internal suspend fun loadWorldSelection(option: NewSessionWorldOption): NewSessionWorldSelection = when (option.kind) {
        0 -> NewSessionWorldSelection(
            encyclopedia = requireNotNull(encyclopediaDao.getById(option.id)) { "World no longer exists" },
        )
        1 -> NewSessionWorldSelection(
            template = requireNotNull(templateDao.getById(option.id)) { "World no longer exists" },
        )
        else -> error("Unknown world option")
    }

    internal suspend fun loadCharacterPage(
        encyclopediaId: Long?, query: String, cursor: NewSessionCharacterOption?,
    ): NewSessionCharacterPage {
        val rows = characterDao.getNewSessionPickerPage(
            encyclopediaId, query.trim(), cursor?.pinnedAt, cursor?.favorite,
            cursor?.createdAt, cursor?.id, NEW_SESSION_PICKER_PAGE_SIZE + 1,
        )
        return NewSessionCharacterPage(rows.take(NEW_SESSION_PICKER_PAGE_SIZE), rows.size > NEW_SESSION_PICKER_PAGE_SIZE)
    }

    fun retrySelections() = refreshSelections(removeIncompatible = false)

    private fun refreshSelections(removeIncompatible: Boolean) {
        selectionJob?.cancel()
        val revision = ++selectionRevision
        val snapshot = _state.value
        if (snapshot.selectedTemplateId == null && snapshot.selectedEncyclopediaId == null && snapshot.selectedCharacterIds.isEmpty()) {
            _state.update { it.copy(selectedTemplate = null, selectedEncyclopedia = null,
                selectedCharacterIdsAvailable = emptySet(), selectedCharacterCards = emptyList(), selectionsResolved = true,
                selectionsLoading = false, selectionsError = null) }
            return
        }
        _state.update { it.copy(selectionsLoading = true, selectionsResolved = false, selectionsError = null) }
        selectionJob = viewModelScope.launch {
            try {
                val template = snapshot.selectedTemplateId?.let { templateDao.getById(it) }
                val encyclopedia = snapshot.selectedEncyclopediaId?.let { encyclopediaDao.getById(it) }
                val validCharacters = snapshot.selectedCharacterIds.toList().chunked(400).flatMap { ids ->
                    characterDao.existingIdsForNewSession(ids, snapshot.selectedEncyclopediaId)
                }.toSet()
                val characterCards = validCharacters.toList().chunked(400).flatMap { ids -> characterDao.getChatPresentationByIds(ids) }
                if (revision != selectionRevision) return@launch
                if (removeIncompatible && validCharacters != snapshot.selectedCharacterIds) {
                    updateInput { current ->
                        if (current.selectedTemplateId == snapshot.selectedTemplateId &&
                            current.selectedEncyclopediaId == snapshot.selectedEncyclopediaId &&
                            current.selectedCharacterIds == snapshot.selectedCharacterIds
                        ) current.copy(selectedCharacterIds = validCharacters) else current
                    }
                }
                _state.update { current ->
                    if (current.selectedTemplateId != snapshot.selectedTemplateId ||
                        current.selectedEncyclopediaId != snapshot.selectedEncyclopediaId) current
                    else current.copy(selectedTemplate = template, selectedEncyclopedia = encyclopedia,
                        selectedCharacterIdsAvailable = validCharacters, selectedCharacterCards = characterCards, selectionsResolved = true,
                        selectionsLoading = false, selectionsError = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (revision == selectionRevision) _state.update { it.copy(selectionsLoading = false,
                    selectionsError = "已选资料暂时无法读取，请重试") }
            }
        }
    }

    private class UnavailableStorySelection : Exception()

    private suspend fun creationContext(state: StorySimulationState): StoryCreationContext {
        val template = state.selectedTemplateId?.let { templateDao.getById(it) ?: throw UnavailableStorySelection() }
        val encyclopedia = state.selectedEncyclopediaId?.let { encyclopediaDao.getById(it) ?: throw UnavailableStorySelection() }
        val characters = state.selectedCharacterIds.sorted().map { id ->
            characterDao.getById(id) ?: throw UnavailableStorySelection()
        }
        if (encyclopedia != null && characters.any {
                it.boundEncyclopediaId > 0L && it.boundEncyclopediaId != encyclopedia.id
            }) throw UnavailableStorySelection()
        return StoryCreationContext(state.premise, state.direction, state.tone, state.chapterCount,
            template, encyclopedia, characters)
    }

    private fun updateInput(transform: (StorySimulationState) -> StorySimulationState) {
        var changedState: StorySimulationState? = null
        _state.update { current ->
            if (current.isRestoring || current.recoveryError != null || current.hasPendingStory ||
                current.isSaving || current.savedSessionId != null) current
            else {
                val next = transform(current)
                val changed = next.premise != current.premise || next.direction != current.direction ||
                    next.tone != current.tone || next.chapterCount != current.chapterCount ||
                    next.selectedTemplateId != current.selectedTemplateId || next.selectedEncyclopediaId != current.selectedEncyclopediaId ||
                    next.selectedCharacterIds != current.selectedCharacterIds
                next.copy(error = null, inputRevision = current.inputRevision + if (changed) 1 else 0,
                    hasInputDraft = if (changed) next.inputDraft() != StoryOpeningInputDraft.EMPTY else current.hasInputDraft)
                    .also { if (changed) changedState = it }
            }
        }
        changedState?.let { changed ->
            try { inputDraftStore.save(changed.inputDraft()) }
            catch (_: Exception) { _state.update { it.copy(error = "创作设定暂存失败，请重试编辑或生成前检查本机存储。") } }
        }
    }

    private fun StorySimulationState.inputDraft() = StoryOpeningInputDraft(
        premise, direction, tone, chapterCount, selectedTemplateId, selectedEncyclopediaId, selectedCharacterIds,
    )

    fun updatePremise(value: String) = updateInput { it.copy(premise = value) }
    fun updateDirection(value: String) = updateInput { it.copy(direction = value) }
    fun updateTone(value: String) = updateInput { it.copy(tone = value) }
    /** UI accepts the product presets 1/3/5/10; domain generation performs its own validation. */
    fun updateChapterCount(value: Int) = updateInput { it.copy(chapterCount = value.coerceIn(1, 10)) }
    internal fun selectWorld(selection: NewSessionWorldSelection) {
        updateInput { current -> current.copy(
            selectedTemplateId = selection.template?.id,
            selectedEncyclopediaId = selection.encyclopedia?.id,
            selectedTemplate = selection.template,
            selectedEncyclopedia = selection.encyclopedia,
        ) }
        refreshSelections(removeIncompatible = true)
    }

    fun setCharacterSelection(ids: Set<Long>) {
        updateInput { it.copy(selectedCharacterIds = ids) }
        refreshSelections(removeIncompatible = false)
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun createStory(onCreated: (Long) -> Unit) {
        if (creationJob.get() != null || _state.value.isRestoring || _state.value.recoveryError != null) return
        if (_state.value.savedSessionId != null) { openSavedStory(onCreated); return }
        if (pendingStory != null) {
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
                try { savePendingStory(onCreated) }
                finally { releaseCreationJob() }
            }
            if (!creationJob.compareAndSet(null, job)) return
            if (!job.start() && creationJob.compareAndSet(job, null)) {
                _state.update { it.copy(isSaving = false) }
            }
            return
        }
        val snapshot = _state.value
        if (snapshot.premise.isBlank()) {
            _state.update { it.copy(error = "请先填写故事背景") }
            return
        }
        if (snapshot.hasUnavailableSelections()) {
            _state.update { it.copy(error = "所选世界或角色当前不可用，请重新选择或重试加载") }
            return
        }
        val apiKey = secureStorage.publicApiKey.trim()
        val baseUrl = secureStorage.publicBaseUrl.trim()
        val model = secureStorage.publicModel.trim()
        if (apiKey.isBlank()) {
            _state.update { it.copy(error = "请先在设置填写对话 API Key") }
            return
        }
        if (baseUrl.isBlank() || model.isBlank()) {
            _state.update { it.copy(error = "请先在设置补全对话 Base URL 和模型") }
            return
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                _state.update { it.copy(isGenerating = true, error = null, generationStage = "核对已选资料") }
                val requestContext = try {
                    creationContext(snapshot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: UnavailableStorySelection) {
                    _state.update { it.copy(isGenerating = false,
                        error = "所选世界或角色当前不可用，请重新选择或重试加载", generationStage = null) }
                    refreshSelections(removeIncompatible = false)
                    return@launch
                } catch (_: Exception) {
                    _state.update { it.copy(isGenerating = false,
                        error = "已选资料读取失败，请重试生成", generationStage = null) }
                    return@launch
                }
                val token = ++generationToken
                val generationId = UUID.randomUUID().toString()
                val generationInput = snapshot.inputDraft()
                activeGenerationId = generationId
                activeGenerationInput = generationInput
                latestGenerationProgress.set(null)
                latestCompletedChapterDrafts.set(emptyList())
                lastPersistedPreviewLength = 0
                lastPersistedPreviewElapsedMs = 0L
                generationStartedAtNanos = System.nanoTime()
                generationWriteFailure.set(null)
                synchronized(failedGenerationDeltas) { failedGenerationDeltas.clear() }
                generationWriteTail = null
                _state.update { it.copy(isGenerating = true, isSaving = false, error = null, generationStage = "等待模型响应", generationModel = model, generationElapsedMs = 0L, firstContentDelayMs = null, receivedChars = 0, completedChapters = 0, totalChapters = snapshot.chapterCount.coerceIn(1, 10), requestToken = token) }
                try { inputDraftStore.commit(generationInput) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    _state.update { it.copy(error = "创作设定暂存失败，请重试后再生成。", generationStage = "暂存失败") }
                    return@launch
                }
                try {
                    inputDraftStore.beginGeneration(StoryOpeningGenerationState(
                        requestId = generationId, input = generationInput, preview = "", model = model,
                        stage = "等待模型响应", receivedChars = 0, elapsedMs = 0L,
                    ))
                } catch (_: Exception) {
                    _state.update { it.copy(error = "生成状态暂存失败，请重试后再生成。", generationStage = "暂存失败", isGenerating = false) }
                    return@launch
                }
                _state.update { it.copy(hasInterruptedGeneration = false, preview = "",
                    completedChapterDrafts = emptyList(), partialPreview = "", generationContentAvailable = false) }
                launch(kotlinx.coroutines.Dispatchers.Default) {
                    while (isActive && _state.value.requestToken == token && _state.value.isGenerating) {
                        _state.update { current -> if (current.requestToken == token && current.isGenerating) current.copy(generationElapsedMs = maxOf(current.generationElapsedMs, elapsedMs())) else current }
                        delay(250L)
                    }
                }
                val supplementalContext = buildString {
                    requestContext.template?.let {
                        appendLine("世界模板：${it.label}")
                        appendLine(it.summary)
                        appendLine(it.worldPrompt)
                    }
                    requestContext.encyclopedia?.let {
                        appendLine("世界百科：${it.name}")
                        appendLine(it.description)
                        appendLine(it.worldPrompt)
                    }
                }.trim()
                val characterContext = requestContext.characters.joinToString("\n") { character ->
                    "${character.name}：${character.personaPrompt.take(1600)}"
                }
                val result = try {
                    storyWriting.write(
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = model,
                        request = StoryWritingRequest(
                            premise = requestContext.premise,
                            direction = requestContext.direction,
                            tone = requestContext.tone,
                            chapterCount = requestContext.chapterCount,
                            worldContext = supplementalContext,
                            characterContext = characterContext,
                            personaName = secureStorage.userName,
                            userDescription = secureStorage.userDescription,
                        ),
                        onProgress = { progress -> updateGenerationProgress(progress, token, generationId, generationInput) },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val formatError = e is com.mojing.app.domain.story.StoryWritingException
                    com.mojing.app.util.UsbSessionLog.w(
                        "StoryWriting",
                        "chapters=${requestContext.chapterCount} stage=${if (formatError) "parse" else "request"} model=${safeModelMetadata(model)} elapsed=${_state.value.generationElapsedMs} firstContent=${_state.value.firstContentDelayMs ?: -1} received=${_state.value.receivedChars} diagnostics=${com.mojing.app.domain.engine.LlmFailureDiagnostics.summary(e)}",
                    )
                    if (_state.value.inputRevision == snapshot.inputRevision) {
                        val error = if (formatError) e.message ?: "小说返回格式不正确，请重试"
                            else UserFacingStrings.remoteRequestFailed(e)
                        _state.update { it.copy(error = error, generationStage = "失败") }
                    }
                    null
                }
                if (result == null) {
                    flushGenerationPreview()
                    _state.update { it.copy(hasInterruptedGeneration = activeGenerationId != null) }
                    return@launch
                }
                ensureActive()
                if (_state.value.inputRevision != snapshot.inputRevision) {
                        _state.update { it.copy(isGenerating = false, generationStage = "已丢弃（输入已变化）", error = "输入或绑定已变化，请重新生成") }
                    return@launch
                }
                val worldContext = listOf(supplementalContext, characterContext).filter(String::isNotBlank).joinToString("\n")
                pendingStory = StoryOpeningDraft(premise = requestContext.premise, direction = requestContext.direction,
                    tone = requestContext.tone, template = requestContext.template, encyclopediaId = requestContext.encyclopedia?.id,
                    characterIds = requestContext.characters.map { it.id }, worldPrompt = StoryCanon.persistentWorldPrompt(requestContext.premise, worldContext),
                    result = result, model = model)
                _state.update { it.copy(isGenerating = false, hasPendingStory = true, hasInterruptedGeneration = false, storyTitle = result.title, draftPersisted = false) }
                savePendingStory(onCreated)
            } catch (_: CancellationException) {
                flushGenerationPreview()
                _state.update { it.copy(isGenerating = false,
                    hasInterruptedGeneration = activeGenerationId != null && it.savedSessionId == null && !it.hasPendingStory,
                    generationStage = if (it.savedSessionId != null) "已保存" else "已停止") }
            } catch (_: Exception) {
                flushGenerationPreview()
                _state.update { it.copy(error = it.error ?: "创作未能完成，请重试", generationStage = "失败",
                    hasInterruptedGeneration = activeGenerationId != null && it.savedSessionId == null && !it.hasPendingStory) }
            } finally {
                releaseCreationJob()
            }
        }
        if (!creationJob.compareAndSet(null, job)) return
        if (!job.start() && creationJob.compareAndSet(job, null)) {
            _state.update { it.copy(isGenerating = false, isSaving = false) }
        }
    }

    private suspend fun releaseCreationJob() {
        if (creationJob.compareAndSet(currentCoroutineContext()[Job], null)) {
            _state.update { it.copy(isGenerating = false, isSaving = false) }
        }
    }

    private suspend fun savePendingStory(onCreated: (Long) -> Unit) {
        val pending = pendingStory ?: return
        val result = pending.result
        _state.update { it.copy(isGenerating = false, isSaving = true, generationStage = "保存到本地", error = null) }
        try {
            withContext(NonCancellable) {
                if (!_state.value.draftPersisted) {
                    draftStore.persist(pending)
                    _state.update { it.copy(draftPersisted = true) }
                }
                val now = System.currentTimeMillis()
                val setup = buildString {
                    append("【故事背景】\n${pending.premise.trim()}")
                    pending.direction.trim().takeIf(String::isNotEmpty)?.let { append("\n\n【接下来希望发生】\n$it") }
                    pending.tone.trim().takeIf(String::isNotEmpty)?.let { append("\n\n【文风与节奏】\n$it") }
                }
                val messages = listOf(MessageEntity(sessionId = 0L, speakerType = "user", content = setup, createdAt = now)) +
                    result.chapters.mapIndexed { index, chapter ->
                        val incomplete = chapter.number in result.incompleteChapterNumbers
                        val choices = if (index == result.chapters.lastIndex && !incomplete) result.nextChoices else emptyList()
                        MessageEntity(sessionId = 0L, speakerType = "narrator",
                            content = storyWriting.toMessageContent(chapter, choices),
                            structuredContentJson = storyWriting.toStructuredJson(chapter, choices, incomplete), createdAt = now + index + 1)
                    }
                val created = createSession.create(
                    title = "小说 · ${result.title.trim().ifBlank { pending.premise.trim().take(24) }}",
                    summary = pending.premise.trim(), gameplayMode = "小说创作", template = pending.template,
                    encyclopediaId = pending.encyclopediaId, narratorEnabled = true, narratorName = "小说作者",
                    choiceEnabled = true, maxChoices = 3, antiCheatEnabled = true,
                    characterIds = pending.characterIds, allowNoParticipants = true,
                    worldPromptOverride = pending.worldPrompt, initialMessages = messages, storyDraftId = pending.id,
                )
                val id = (created as? CreateSessionUseCase.Result.Created)?.sessionId
                    ?: error("Story session could not be created")
                val generationRequestId = withContext(worker) { inputDraftStore.loadGeneration()?.requestId }
                inputDraftStore.clearGeneration(generationRequestId)
                pendingStory = null
                savedDraftId = pending.id
                _state.update { it.copy(hasPendingStory = false, savedSessionId = id, generationStage = "已保存", isSaving = false) }
            }
            currentCoroutineContext().ensureActive()
            openSavedStory(onCreated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _state.update { it.copy(isSaving = false, generationStage = "保存失败", error = "正文已生成，保存未完成。可重试保存或复制完整正文。") }
        }
    }

    private fun openSavedStory(onCreated: (Long) -> Unit) {
        val id = _state.value.savedSessionId ?: return
        if (_state.value.savedSessionMissing) return
        _state.update { it.copy(error = null) }
        try { onCreated(id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _state.update { it.copy(error = "小说已保存，请重新打开会话。", generationStage = "已保存") } }
    }

    fun pendingStoryText(): String = pendingStory?.result?.let { result ->
        buildString {
            appendLine(result.title)
            result.chapters.forEach { append("\n${it.title}\n\n${it.content}\n") }
            append("\n后续走向\n")
            append(result.nextChoices.joinToString("\n"))
        }
    }.orEmpty().ifBlank { interruptedStoryText() }

    /** Full durable response is only exposed when it was written to the request file. */
    fun interruptedStoryText(): String = buildString {
        val current = _state.value
        current.completedChapterDrafts.forEach { chapter ->
            if (isNotEmpty()) append("\n\n")
            append(chapter.title).append("\n\n").append(chapter.content)
        }
        if (current.partialPreview.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(current.partialPreview)
        }
    }.ifBlank { _state.value.preview }

    /** Reads the durable source only when the user asks to copy it; never blocks Compose. */
    fun loadInterruptedStoryText(onLoaded: (String) -> Unit) {
        viewModelScope.launch(worker) {
            val generation = inputDraftStore.loadGeneration()
            val batches = generation?.let { inputDraftStore.loadGenerationBatches(it) }.orEmpty()
            val raw = batches.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
                ?: generation?.let { inputDraftStore.loadGenerationContent(it) }
            val text = if (!raw.isNullOrBlank()) raw else interruptedStoryText()
            withContext(Dispatchers.Main.immediate) { onLoaded(text) }
        }
    }

    /** Promote only parser-confirmed earlier chapters into the normal idempotent draft path. */
    fun saveCompletedInterruptedChapters(onCreated: (Long) -> Unit) {
        val snapshot = _state.value
        if (!snapshot.hasInterruptedGeneration ||
            (snapshot.completedChapterDrafts.isEmpty() && snapshot.partialPreview.isBlank()) ||
            snapshot.isSaving || snapshot.isGenerating || pendingStory != null || creationJob.get() != null) return
        var claimed = false
        _state.update { current ->
            if (current.hasInterruptedGeneration && !current.isSaving && !current.isGenerating && pendingStory == null) {
                claimed = true
                current.copy(isSaving = true)
            } else current
        }
        if (!claimed) return
        viewModelScope.launch {
            try {
                val context = creationContext(snapshot)
                val supplemental = buildString {
                    context.template?.let { appendLine("世界模板：${it.label}"); appendLine(it.summary); appendLine(it.worldPrompt) }
                    context.encyclopedia?.let { appendLine("世界百科：${it.name}"); appendLine(it.description); appendLine(it.worldPrompt) }
                }.trim()
                val characters = context.characters.joinToString("\n") { "${it.name}：${it.personaPrompt.take(1600)}" }
                val world = listOf(supplemental, characters).filter(String::isNotBlank).joinToString("\n")
                val title = snapshot.storyTitle.ifBlank { context.premise.trim().take(24).ifBlank { "未命名小说" } }
                val generation = inputDraftStore.loadGeneration()
                val recovered = generation?.let { generationState ->
                    withContext(worker) {
                        com.mojing.app.domain.story.StoryRecoveryParser.parse(
                            inputDraftStore.loadGenerationBatches(generationState), snapshot.completedChapterDrafts.size,
                            snapshot.completedChapterDrafts)
                    }
                }
                val chapters = snapshot.completedChapterDrafts.toMutableList().apply {
                    recovered?.chapters.orEmpty().forEach { chapter ->
                        if (none { it.number == chapter.number }) add(chapter.copy(number = size + 1))
                    }
                    val partial = recovered?.partialChapter?.content?.takeIf(String::isNotBlank)
                        ?: if (generation?.contentFileName == null) snapshot.partialPreview.trim() else null
                    partial?.takeIf(String::isNotBlank)?.let { add(com.mojing.app.domain.story.StoryChapter(size + 1, recovered?.partialChapter?.title ?: "中断片段", it)) }
                }
                val incomplete = recovered?.partialChapter?.let { partial ->
                    chapters.lastOrNull()?.takeIf { it.content == partial.content }?.let { setOf(it.number) }
                }.orEmpty()
                pendingStory = StoryOpeningDraft(
                    premise = context.premise, direction = context.direction, tone = context.tone,
                    template = context.template, encyclopediaId = context.encyclopedia?.id,
                    characterIds = context.characters.map { it.id },
                    worldPrompt = StoryCanon.persistentWorldPrompt(context.premise, world),
                    result = com.mojing.app.domain.story.StoryWritingResult(title, chapters, emptyList(), incomplete),
                    model = snapshot.generationModel.orEmpty(),
                )
                _state.update { it.copy(hasInterruptedGeneration = false, hasPendingStory = true, storyTitle = title,
                    generationStage = "已收到草稿待保存", preview = chapters.joinToString("\n\n") { "${it.title}\n${it.content}" }) }
                createStory(onCreated)
            } catch (_: CancellationException) { throw CancellationException() }
            catch (_: Exception) { _state.update { it.copy(isSaving = false, error = "已完成章节未能准备保存，请重试。") } }
        }
    }

    fun retryInterruptedGeneration(onCreated: (Long) -> Unit) {
        if (!_state.value.hasInterruptedGeneration || _state.value.isSaving || _state.value.isGenerating) return
        createStory(onCreated)
    }

    fun discardInterruptedGeneration() {
        if (!_state.value.hasInterruptedGeneration || _state.value.isSaving || _state.value.isGenerating) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            try {
                inputDraftStore.clearGeneration()
                activeGenerationId = null
                _state.update { it.copy(hasInterruptedGeneration = false, generationStage = null, generationModel = null,
                    generationElapsedMs = 0L, receivedChars = 0, preview = "", recoveredInputDraft = false,
                    completedChapterDrafts = emptyList(), partialPreview = "", generationContentAvailable = false) }
            } catch (_: Exception) {
                _state.update { it.copy(error = "中断记录未能清除，请重试。") }
            } finally { _state.update { it.copy(isSaving = false) } }
        }
    }

    suspend fun discardPendingStory(): Boolean {
        if (_state.value.isSaving || _state.value.isGenerating || pendingStory == null) return false
        val pending = pendingStory ?: return false
        _state.update { it.copy(isSaving = true) }
        return try {
            inputDraftStore.commit(_state.value.inputDraft())
            inputDraftStore.clearGeneration()
            if (_state.value.draftPersisted) draftStore.discard(pending.id)
            pendingStory = null
            _state.update { it.copy(hasPendingStory = false, error = null, preview = "", generationStage = null,
                generationModel = null, storyTitle = "", draftPersisted = false, recoveredStory = false) }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _state.update { it.copy(error = "正文未能放弃，请重试。") }; false }
        finally { _state.update { it.copy(isSaving = false) } }
    }

    fun recoveryDataText(): String = unreadableDraft ?: unreadableInputDraft.orEmpty()

    suspend fun flushInputDraftBeforeLeaving(): Boolean {
        val current = _state.value
        if (current.isRestoring || current.recoveryError != null || current.isGenerating || current.isSaving) return false
        if (current.hasPendingStory || current.savedSessionId != null) return true
        return try {
            if (!flushGenerationPreview()) false
            else { inputDraftStore.commit(current.inputDraft()); true }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _state.update { it.copy(error = "创作设定暂存失败，请重试后再离开。") }; false }
    }

    fun clearUnavailableSelections() = updateInput { current ->
        val templateId = current.selectedTemplateId?.takeIf { it == current.selectedTemplate?.id }
        val encyclopediaId = current.selectedEncyclopediaId?.takeIf { it == current.selectedEncyclopedia?.id }
        current.copy(selectedTemplateId = templateId, selectedEncyclopediaId = encyclopediaId,
            selectedCharacterIds = current.selectedCharacterIds intersect current.selectedCharacterIdsAvailable,
            selectedTemplate = current.selectedTemplate?.takeIf { it.id == templateId },
            selectedEncyclopedia = current.selectedEncyclopedia?.takeIf { it.id == encyclopediaId })
    }

    suspend fun clearInputDraft(): Boolean {
        if (_state.value.isRestoring || _state.value.isGenerating || _state.value.isSaving || _state.value.hasPendingStory || _state.value.savedSessionId != null) return false
        _state.update { it.copy(isSaving = true) }
        return try {
            inputDraftStore.clear()
            selectionJob?.cancel()
            selectionRevision++
            _state.update { StorySimulationState(isRestoring = false, selectionsResolved = true) }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _state.update { it.copy(error = "草稿未能清除，请重试。") }; false }
        finally { _state.update { it.copy(isSaving = false) } }
    }

    fun startNewStory() {
        if (_state.value.isSaving || _state.value.isRestoring || creationJob.get() != null) return
        val id = savedDraftId ?: return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            try {
                inputDraftStore.clear()
                draftStore.clearSavedReceipt(id)
                inputDraftStore.clearGeneration()
                savedDraftId = null
                selectionJob?.cancel()
                selectionRevision++
                _state.update { StorySimulationState(isRestoring = false, selectionsResolved = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(error = "暂时无法开始新作，请重试。") } }
            finally { _state.update { it.copy(isSaving = false) } }
        }
    }

    suspend fun discardUnreadableDraft(): Boolean {
        val raw = unreadableDraft ?: unreadableInputDraft ?: return false
        if (_state.value.isRestoring || _state.value.isSaving) return false
        _state.update { it.copy(isSaving = true) }
        return try {
            if (unreadableDraft != null) draftStore.discardUnreadable(raw)
            else inputDraftStore.discardUnreadable(raw)
            unreadableDraft = null
            unreadableInputDraft = null
            _state.update { it.copy(recoveryError = null, canCopyRecoveryData = false) }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _state.update { it.copy(recoveryError = "恢复内容未能清除，请重试。") }; false }
        finally { _state.update { it.copy(isSaving = false) } }
    }

    fun stopGeneration(): Boolean {
        val job = creationJob.get()
        if (!_state.value.isGenerating || job?.isActive != true) return false
        job.cancel()
        return true
    }

    /** Navigation waits for the cancellation handler and the latest preview write. */
    suspend fun stopGenerationAndWaitForPreview(): Boolean {
        val job = creationJob.get()
        if (_state.value.isGenerating && job?.isActive == true) job.cancel()
        job?.join()
        return flushInputDraftBeforeLeaving()
    }

    private fun updateGenerationProgress(
        progress: StoryWritingProgress, token: Long, generationId: String, input: StoryOpeningInputDraft,
    ) {
        if (_state.value.requestToken != token || !_state.value.isGenerating) return
        _state.update {
            if (it.requestToken != token || !it.isGenerating) it else it.copy(
                generationStage = progress.stage,
                generationModel = progress.model,
                generationElapsedMs = maxOf(it.generationElapsedMs, progress.elapsedMs),
                firstContentDelayMs = progress.firstContentDelayMs,
                receivedChars = progress.receivedChars,
                completedChapters = progress.completedChapters,
                totalChapters = progress.totalChapters,
                preview = progress.preview.takeLast(MAX_PREVIEW_CHARS),
                completedChapterDrafts = progress.completedChapterDrafts,
                partialPreview = progress.partialPreview,
                generationContentAvailable = it.generationContentAvailable || progress.rawDelta != null,
            )
        }
        latestCompletedChapterDrafts.set(progress.completedChapterDrafts.toList())
        progress.rawDelta?.let { delta ->
            if (activeGenerationId == generationId) {
                val previous = generationWriteTail
                generationWriteTail = viewModelScope.launch(worker) {
                    runCatching {
                        previous?.join()
                        check(generationWriteMutex.withLock { inputDraftStore.appendGenerationDelta(generationId, delta, progress.batchIndex, offset = progress.rawOffset) })
                    }.onFailure {
                        synchronized(failedGenerationDeltas) { failedGenerationDeltas += FailedGenerationDelta(generationId, progress.batchIndex, delta, progress.rawOffset) }
                        generationWriteFailure.compareAndSet(null, it)
                    }
                }
            }
        }
        if (progress.completedChapterDrafts.isNotEmpty() && activeGenerationId == generationId) {
            val chapters = progress.completedChapterDrafts.toList()
            val previous = generationWriteTail
            generationWriteTail = viewModelScope.launch(worker) {
                runCatching {
                    previous?.join()
                    check(generationWriteMutex.withLock { inputDraftStore.persistCompletedChapters(generationId, chapters) })
                }.onFailure { generationWriteFailure.compareAndSet(null, it) }
            }
        }
        if (_state.value.requestToken != token || !_state.value.isGenerating) return
        latestGenerationProgress.updateAndGet { previous ->
            if (previous == null || previous.token < token ||
                (previous.token == token && progress.receivedChars >= previous.progress.receivedChars)
            ) StoryGenerationProgressSnapshot(token, generationId, progress) else previous
        }
        val preview = progress.preview.takeLast(MAX_PREVIEW_CHARS)
        if (preview.isBlank() || (preview.length - lastPersistedPreviewLength < PREVIEW_PERSIST_CHARS &&
                progress.elapsedMs - lastPersistedPreviewElapsedMs < PREVIEW_PERSIST_INTERVAL_MS)) return
        persistGenerationPreview(token, generationId, input, preview, progress)
    }

    private fun persistGenerationPreview(
        token: Long, generationId: String, input: StoryOpeningInputDraft,
        preview: String, progress: StoryWritingProgress,
    ) {
        viewModelScope.launch(worker) {
            try {
                generationPersistMutex.withLock {
                    val saved = inputDraftStore.persistGenerationPreview(StoryOpeningGenerationState(
                        requestId = generationId, input = input, preview = preview,
                        model = progress.model, stage = progress.stage, receivedChars = progress.receivedChars,
                        elapsedMs = progress.elapsedMs,
                    ))
                    if (_state.value.requestToken == token && activeGenerationId == generationId) {
                        if (saved) {
                            lastPersistedPreviewLength = preview.length
                            lastPersistedPreviewElapsedMs = progress.elapsedMs
                        } else _state.update { it.copy(error = "生成预览暂存失败，正文仍会继续生成。") }
                    }
                }
            } catch (_: Exception) {
                if (_state.value.requestToken == token && activeGenerationId == generationId) {
                    _state.update { it.copy(error = "生成预览暂存失败，正文仍会继续生成。") }
                }
            }
        }
    }

    private suspend fun flushGenerationPreview(): Boolean = withContext(NonCancellable) {
        generationWriteTail?.join()
        val failed = synchronized(failedGenerationDeltas) { failedGenerationDeltas.toList() }
        if (failed.isNotEmpty()) {
            val retried = failed.all { item ->
                runCatching { inputDraftStore.appendGenerationDelta(item.requestId, item.delta, item.batchIndex, offset = item.offset) }.getOrDefault(false)
            }
            if (!retried) {
                _state.update { it.copy(error = "生成正文未能完整写入本机，请留在当前页重试或复制现有内容。") }
                return@withContext false
            }
            synchronized(failedGenerationDeltas) { failedGenerationDeltas.clear() }
        }
        // Raw deltas and parsed chapter snapshots can fail independently. Flushing
        // one must not clear the other failure without persisting the latest snapshot.
        val chapters = latestCompletedChapterDrafts.get()
        if (chapters.isNotEmpty()) {
            val generationId = activeGenerationId
            val retried = generationId != null && runCatching {
                check(generationWriteMutex.withLock {
                    inputDraftStore.persistCompletedChapters(generationId, chapters)
                })
            }.isSuccess
            if (!retried) {
                _state.update { it.copy(error = "生成正文的已完成章节未能完整写入本机，请留在当前页重试或复制现有内容。") }
                return@withContext false
            }
        }
        generationWriteFailure.set(null)
        val generationId = activeGenerationId ?: return@withContext true
        val input = activeGenerationInput ?: return@withContext true
        val latest = latestGenerationProgress.get()?.takeIf {
            it.token == _state.value.requestToken && it.requestId == generationId
        } ?: return@withContext true
        val progress = latest.progress
        val preview = progress.preview.takeLast(MAX_PREVIEW_CHARS)
        if (preview.isBlank()) return@withContext true
        try {
            generationPersistMutex.withLock {
                check(inputDraftStore.persistGenerationPreview(StoryOpeningGenerationState(
                    requestId = generationId, input = input, preview = preview,
                    model = progress.model, stage = progress.stage, receivedChars = progress.receivedChars,
                    elapsedMs = progress.elapsedMs,
                ))) { "Generation preview was not saved for the active request" }
            }
            true
        } catch (_: Exception) {
            _state.update { it.copy(error = "生成预览暂存失败，请留在当前页复制预览，或重试离开。") }
            false
        }
    }

    private fun elapsedMs() = (System.nanoTime() - generationStartedAtNanos) / 1_000_000L
    private fun safeModelMetadata(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_PREVIEW_CHARS = 12_000
        const val PREVIEW_PERSIST_CHARS = 256
        const val PREVIEW_PERSIST_INTERVAL_MS = 1_000L
    }
}

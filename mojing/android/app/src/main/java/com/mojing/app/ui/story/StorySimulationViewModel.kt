package com.mojing.app.ui.story

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.StoryOpeningDraftStore
import com.mojing.app.data.UnreadableStoryDraft
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.WorldTemplateDao
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
import com.mojing.app.ui.util.UserFacingStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
import java.util.concurrent.atomic.AtomicReference

data class StoryOptionLoadState<T>(
    val items: List<T> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

data class StorySimulationState(
    val premise: String = "",
    val direction: String = "",
    val tone: String = "有画面感、人物动机清楚、适合连续长篇创作",
    val chapterCount: Int = 2,
    val templates: StoryOptionLoadState<WorldTemplateEntity> = StoryOptionLoadState(),
    val encyclopedias: StoryOptionLoadState<EncyclopediaEntity> = StoryOptionLoadState(),
    val characters: StoryOptionLoadState<CharacterEntity> = StoryOptionLoadState(),
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
    val storyTitle: String = "",
    val draftPersisted: Boolean = false,
    val error: String? = null,
    val generationStage: String? = null,
    val generationModel: String? = null,
    val generationElapsedMs: Long = 0L,
    val firstContentDelayMs: Long? = null,
    val receivedChars: Int = 0,
    val preview: String = "",
    val requestToken: Long = 0L,
    val inputRevision: Long = 0L,
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

@HiltViewModel
class StorySimulationViewModel @Inject constructor(
    private val storyWriting: StoryWritingUseCase,
    private val secureStorage: SecureStorage,
    private val templateDao: WorldTemplateDao,
    private val encyclopediaDao: EncyclopediaDao,
    private val characterDao: CharacterDao,
    private val createSession: CreateSessionUseCase,
    private val draftStore: StoryOpeningDraftStore,
) : ViewModel() {
    private val _state = MutableStateFlow(StorySimulationState())
    val state: StateFlow<StorySimulationState> = _state.asStateFlow()
    private var templateLoadJob: Job? = null
    private var encyclopediaLoadJob: Job? = null
    private var characterLoadJob: Job? = null
    private val creationJob = AtomicReference<Job?>(null)
    private var pendingStory: StoryOpeningDraft? = null
    private var savedDraftId: String? = null
    private var unreadableDraft: String? = null
    private var restoreJob: Job? = null
    private var generationToken = 0L
    private var generationStartedAtNanos = 0L

    init {
        retryRecovery()
        retryTemplates()
        retryEncyclopedias()
        retryCharacters()
    }

    fun retryRecovery() {
        if (restoreJob?.isActive == true || creationJob.get() != null || pendingStory != null) return
        restoreJob = viewModelScope.launch {
            _state.update { it.copy(isRestoring = true, recoveryError = null) }
            try {
                val record = draftStore.load()
                unreadableDraft = null
                when (record) {
                    null -> _state.update { it.copy(isRestoring = false, canCopyRecoveryData = false) }
                    is StoryOpeningRecord.Pending -> {
                        val draft = record.draft
                        pendingStory = draft
                        _state.update { it.copy(isRestoring = false, hasPendingStory = true, recoveredStory = true,
                            draftPersisted = true, premise = draft.premise, direction = draft.direction, tone = draft.tone,
                            chapterCount = draft.result.chapters.size, storyTitle = draft.result.title,
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
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                unreadableDraft = (error as? UnreadableStoryDraft)?.raw
                _state.update { it.copy(isRestoring = false, recoveryError = "上次创作暂时无法读取，请重试。",
                    canCopyRecoveryData = unreadableDraft != null) }
            }
        }
    }

    fun retryTemplates() {
        if (templateLoadJob?.isActive == true) return
        templateLoadJob = viewModelScope.launch {
            _state.update { it.copy(templates = it.templates.copy(isLoading = true, error = null)) }
            try {
                val items = templateDao.getAll()
                _state.update { current ->
                    current.copy(
                        templates = StoryOptionLoadState(items = items, isLoading = false),
                        selectedTemplateId = current.selectedTemplateId?.takeIf { selected -> items.any { it.id == selected } },
                    )
                }
            } catch (_: Exception) {
                _state.update { it.copy(templates = it.templates.copy(isLoading = false, error = optionLoadError("世界模板"))) }
            }
        }
    }

    fun retryEncyclopedias() {
        if (encyclopediaLoadJob?.isActive == true) return
        encyclopediaLoadJob = viewModelScope.launch {
            _state.update { it.copy(encyclopedias = it.encyclopedias.copy(isLoading = true, error = null)) }
            try {
                val items = encyclopediaDao.getAll()
                _state.update { current ->
                    val selectedId = current.selectedEncyclopediaId?.takeIf { selected -> items.any { it.id == selected } }
                    current.copy(
                        encyclopedias = StoryOptionLoadState(items = items, isLoading = false),
                        selectedEncyclopediaId = selectedId,
                        selectedCharacterIds = current.selectedCharacterIds.filterTo(mutableSetOf()) { characterId ->
                            selectedId == null || current.characters.items.firstOrNull { it.id == characterId }?.let { it.boundEncyclopediaId <= 0L || it.boundEncyclopediaId == selectedId } == true
                        },
                    )
                }
            } catch (_: Exception) {
                _state.update { it.copy(encyclopedias = it.encyclopedias.copy(isLoading = false, error = optionLoadError("百科"))) }
            }
        }
    }

    fun retryCharacters() {
        if (characterLoadJob?.isActive == true) return
        characterLoadJob = viewModelScope.launch {
            _state.update { it.copy(characters = it.characters.copy(isLoading = true, error = null)) }
            try {
                val items = characterDao.getAll()
                _state.update { current ->
                    current.copy(
                        characters = StoryOptionLoadState(items = items, isLoading = false),
                        selectedCharacterIds = current.selectedCharacterIds.filterTo(mutableSetOf()) { characterId ->
                            items.firstOrNull { it.id == characterId }?.let { character ->
                                current.selectedEncyclopediaId == null || character.boundEncyclopediaId <= 0L || character.boundEncyclopediaId == current.selectedEncyclopediaId
                            } == true
                        },
                    )
                }
            } catch (_: Exception) {
                _state.update { it.copy(characters = it.characters.copy(isLoading = false, error = optionLoadError("角色"))) }
            }
        }
    }

    private fun optionLoadError(label: String): String = "${label}加载失败，请重试"

    private fun creationContext(state: StorySimulationState): StoryCreationContext = StoryCreationContext(
        premise = state.premise,
        direction = state.direction,
        tone = state.tone,
        chapterCount = state.chapterCount,
        template = state.templates.items.firstOrNull { it.id == state.selectedTemplateId },
        encyclopedia = state.encyclopedias.items.firstOrNull { it.id == state.selectedEncyclopediaId },
        characters = state.characters.items.filter { it.id in state.selectedCharacterIds }.sortedBy { it.id },
    )

    private fun updateInput(transform: (StorySimulationState) -> StorySimulationState) {
        _state.update { current ->
            if (current.isRestoring || current.recoveryError != null || current.hasPendingStory || current.isSaving || current.savedSessionId != null) current
            else {
                val next = transform(current)
                val changed = next.premise != current.premise || next.direction != current.direction ||
                    next.tone != current.tone || next.chapterCount != current.chapterCount ||
                    next.selectedTemplateId != current.selectedTemplateId || next.selectedEncyclopediaId != current.selectedEncyclopediaId ||
                    next.selectedCharacterIds != current.selectedCharacterIds
                next.copy(error = null, inputRevision = current.inputRevision + if (changed) 1 else 0)
            }
        }
    }

    fun updatePremise(value: String) = updateInput { it.copy(premise = value) }
    fun updateDirection(value: String) = updateInput { it.copy(direction = value) }
    fun updateTone(value: String) = updateInput { it.copy(tone = value) }
    fun updateChapterCount(value: Int) = updateInput { it.copy(chapterCount = value.coerceIn(1, 3)) }
    fun selectTemplate(id: Long?) = updateInput { it.copy(selectedTemplateId = id) }

    fun selectEncyclopedia(id: Long?) = updateInput { current ->
        current.copy(
            selectedEncyclopediaId = id,
            selectedCharacterIds = current.selectedCharacterIds.filterTo(mutableSetOf()) { characterId ->
                id == null || current.characters.items.firstOrNull { it.id == characterId }?.let { it.boundEncyclopediaId <= 0L || it.boundEncyclopediaId == id } == true
            },
        )
    }

    fun toggleCharacter(id: Long) = updateInput { current ->
        val selected = current.selectedCharacterIds.toMutableSet()
        if (!selected.add(id)) selected.remove(id)
        current.copy(selectedCharacterIds = selected)
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
        val requestContext = creationContext(snapshot)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val token = ++generationToken
                generationStartedAtNanos = System.nanoTime()
                _state.update { it.copy(isGenerating = true, isSaving = false, error = null, generationStage = "等待模型响应", generationModel = model, generationElapsedMs = 0L, firstContentDelayMs = null, receivedChars = 0, preview = "", requestToken = token) }
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
                        ),
                        onProgress = { progress -> updateGenerationProgress(progress, token) },
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
                if (result == null) return@launch
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
                _state.update { it.copy(isGenerating = false, hasPendingStory = true, storyTitle = result.title, draftPersisted = false) }
                savePendingStory(onCreated)
            } catch (_: CancellationException) {
                _state.update { it.copy(isGenerating = false, generationStage = if (it.savedSessionId != null) "已保存" else "已停止") }
            } catch (_: Exception) {
                _state.update { it.copy(error = "创作未能完成，请重试", generationStage = "失败") }
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
                        val choices = if (index == result.chapters.lastIndex) result.nextChoices else emptyList()
                        MessageEntity(sessionId = 0L, speakerType = "narrator",
                            content = storyWriting.toMessageContent(chapter, choices),
                            structuredContentJson = storyWriting.toStructuredJson(chapter, choices), createdAt = now + index + 1)
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
    }.orEmpty()

    suspend fun discardPendingStory(): Boolean {
        if (_state.value.isSaving || _state.value.isGenerating || pendingStory == null) return false
        val pending = pendingStory ?: return false
        _state.update { it.copy(isSaving = true) }
        return try {
            if (_state.value.draftPersisted) draftStore.discard(pending.id)
            pendingStory = null
            _state.update { it.copy(hasPendingStory = false, error = null, preview = "", generationStage = null,
                generationModel = null, storyTitle = "", draftPersisted = false, recoveredStory = false) }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _state.update { it.copy(error = "正文未能放弃，请重试。") }; false }
        finally { _state.update { it.copy(isSaving = false) } }
    }

    fun recoveryDataText(): String = unreadableDraft.orEmpty()

    fun startNewStory() {
        if (_state.value.isSaving || _state.value.isRestoring || creationJob.get() != null) return
        val id = savedDraftId ?: return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            try {
                draftStore.clearSavedReceipt(id)
                savedDraftId = null
                _state.update { StorySimulationState(isRestoring = false, templates = it.templates, encyclopedias = it.encyclopedias, characters = it.characters) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(error = "暂时无法开始新作，请重试。") } }
            finally { _state.update { it.copy(isSaving = false) } }
        }
    }

    suspend fun discardUnreadableDraft(): Boolean {
        val raw = unreadableDraft ?: return false
        if (_state.value.isRestoring || _state.value.isSaving) return false
        _state.update { it.copy(isSaving = true) }
        return try {
            draftStore.discardUnreadable(raw)
            unreadableDraft = null
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

    private fun updateGenerationProgress(progress: StoryWritingProgress, token: Long) {
        _state.update {
            if (it.requestToken != token || !it.isGenerating) it else it.copy(
                generationStage = progress.stage,
                generationModel = progress.model,
                generationElapsedMs = maxOf(it.generationElapsedMs, progress.elapsedMs),
                firstContentDelayMs = progress.firstContentDelayMs,
                receivedChars = progress.receivedChars,
                preview = progress.preview.takeLast(MAX_PREVIEW_CHARS),
            )
        }
    }

    private fun elapsedMs() = (System.nanoTime() - generationStartedAtNanos) / 1_000_000L
    private fun safeModelMetadata(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    private companion object { const val MAX_PREVIEW_CHARS = 12_000 }
}

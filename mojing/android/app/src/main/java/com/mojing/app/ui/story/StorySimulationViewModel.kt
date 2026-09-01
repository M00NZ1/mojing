package com.mojing.app.ui.story

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.domain.story.StoryWritingRequest
import com.mojing.app.domain.story.StoryWritingUseCase
import com.mojing.app.domain.story.StoryCanon
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
    val error: String? = null,
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
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
) : ViewModel() {
    private val _state = MutableStateFlow(StorySimulationState())
    val state: StateFlow<StorySimulationState> = _state.asStateFlow()
    private var templateLoadJob: Job? = null
    private var encyclopediaLoadJob: Job? = null
    private var characterLoadJob: Job? = null
    private val creationJob = AtomicReference<Job?>(null)

    init {
        retryTemplates()
        retryEncyclopedias()
        retryCharacters()
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
                            selectedId == null || current.characters.items.firstOrNull { it.id == characterId }?.boundEncyclopediaId == selectedId
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
                val items = characterDao.getAllBound()
                _state.update { current ->
                    current.copy(
                        characters = StoryOptionLoadState(items = items, isLoading = false),
                        selectedCharacterIds = current.selectedCharacterIds.filterTo(mutableSetOf()) { characterId ->
                            items.firstOrNull { it.id == characterId }?.let { character ->
                                current.selectedEncyclopediaId == null || character.boundEncyclopediaId == current.selectedEncyclopediaId
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
        _state.update { transform(it).copy(error = null) }
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
                id == null || current.characters.items.firstOrNull { it.id == characterId }?.boundEncyclopediaId == id
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
        if (creationJob.get() != null) return
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
                _state.update { it.copy(isGenerating = true, isSaving = false, error = null) }
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
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (creationContext(_state.value) == requestContext) {
                        _state.update { it.copy(error = UserFacingStrings.remoteRequestFailed(e)) }
                    }
                    null
                }
                if (result == null) return@launch
                ensureActive()
                if (creationContext(_state.value) != requestContext) {
                    _state.update { it.copy(isGenerating = false, error = "输入或绑定已变化，请重新生成") }
                    return@launch
                }
                _state.update { it.copy(isGenerating = false, isSaving = true) }
                val sessionId = withContext(NonCancellable) {
                    val created = createSession.create(
                        title = "小说 · ${result.title.trim().ifBlank { requestContext.premise.trim().take(24) }}",
                        summary = requestContext.premise.trim(),
                        gameplayMode = "小说创作",
                        template = requestContext.template,
                        encyclopediaId = requestContext.encyclopedia?.id,
                        narratorEnabled = true,
                        narratorName = "小说作者",
                        choiceEnabled = true,
                        maxChoices = 3,
                        antiCheatEnabled = true,
                        characterIds = requestContext.characters.map { it.id },
                        allowNoParticipants = true,
                        worldPromptOverride = StoryCanon.persistentWorldPrompt(
                            requestContext.premise,
                            listOf(supplementalContext, characterContext)
                                .filter(String::isNotBlank)
                                .joinToString("\n\n"),
                        ),
                    )
                    val savedSessionId = (created as? CreateSessionUseCase.Result.Created)?.sessionId
                        ?: throw IllegalStateException("无法创建小说会话")
                    val now = System.currentTimeMillis()
                    val setup = buildString {
                        append("【故事背景】\n${requestContext.premise.trim()}")
                        requestContext.direction.trim().takeIf(String::isNotEmpty)?.let { append("\n\n【接下来希望发生】\n$it") }
                        requestContext.tone.trim().takeIf(String::isNotEmpty)?.let { append("\n\n【文风与节奏】\n$it") }
                    }
                    messageDao.insert(MessageEntity(sessionId = savedSessionId, speakerType = "user", content = setup, createdAt = now))
                    result.chapters.forEachIndexed { index, chapter ->
                        val choices = if (index == result.chapters.lastIndex) result.nextChoices else emptyList()
                        messageDao.insert(
                            MessageEntity(
                                sessionId = savedSessionId,
                                speakerType = "narrator",
                                content = storyWriting.toMessageContent(chapter, choices),
                                structuredContentJson = storyWriting.toStructuredJson(chapter, choices),
                                createdAt = now + index + 1,
                            ),
                        )
                    }
                    sessionDao.bumpUpdatedAt(savedSessionId)
                    savedSessionId
                }
                ensureActive()
                onCreated(sessionId)
            } catch (_: CancellationException) {
                // Stopping remote generation is a normal user action.
            } catch (_: Exception) {
                _state.update { it.copy(error = "小说已生成，但保存到本机会话失败，请重试") }
            } finally {
                val currentJob = currentCoroutineContext()[Job]
                if (creationJob.compareAndSet(currentJob, null)) {
                    _state.update { it.copy(isGenerating = false, isSaving = false) }
                }
            }
        }
        if (!creationJob.compareAndSet(null, job)) return
        if (!job.start() && creationJob.compareAndSet(job, null)) {
            _state.update { it.copy(isGenerating = false, isSaving = false) }
        }
    }

    fun stopGeneration(): Boolean {
        val job = creationJob.get()
        if (!_state.value.isGenerating || job?.isActive != true) return false
        job.cancel()
        return true
    }
}

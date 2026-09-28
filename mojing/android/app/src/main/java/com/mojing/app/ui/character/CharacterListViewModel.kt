package com.mojing.app.ui.character

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.CharacterListItem
import com.mojing.app.data.local.dao.CharacterProfileDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaFilterOption
import com.mojing.app.data.local.dao.EncyclopediaNameOption
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.CharacterProfileEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.domain.usecase.SmartImportUseCase
import com.mojing.app.domain.usecase.CreateSessionUseCase
import com.mojing.app.domain.usecase.DeleteCharacterUseCase
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import com.mojing.app.domain.util.CharacterCardPngCodec
import com.mojing.app.domain.util.CharacterCardV2Converter
import com.mojing.app.domain.util.CharacterPortableCodec
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.text.Charsets

enum class CharacterLibrarySort { RECOMMENDED, RECENT, NAME }

data class CharacterLibraryPage(
    val items: List<CharacterListItem> = emptyList(),
    val pageIndex: Int = 0,
    val hasNext: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
)

data class CharacterFilterPage(
    val rows: List<EncyclopediaFilterOption>,
    val hasMore: Boolean,
)

private const val CHARACTER_LIBRARY_PAGE_SIZE = 40
private const val CHARACTER_FILTER_PAGE_SIZE = 40

internal fun newCharacterDraft(encyclopediaId: Long = 0L): CharacterEntity =
    CharacterEntity(name = "新角色", boundEncyclopediaId = encyclopediaId)

data class CharacterImportResult(
    val message: String,
    val importedIds: List<Long> = emptyList(),
    val hasFailure: Boolean = false,
)

internal object CharacterExportCodec {
    data class ExportedCharacter(
        val name: String,
        val personaPrompt: String,
        val apiBaseUrl: String,
        val modelName: String,
        val temperature: Float,
        val maxTokens: Int,
        val topP: Float,
        val topK: Int,
        val frequencyPenalty: Float,
        val presencePenalty: Float,
        val repetitionPenalty: Float,
        val avatarColor: String,
        val avatarImagePath: String,
        val cardImagePath: String,
        val voiceProvider: String,
        val voiceApiBaseUrl: String,
        val voiceModel: String,
        val imageGenEnabled: Boolean,
        val imageGenBaseUrl: String,
        val imageGenModel: String,
        val thinkMaxEnabled: Boolean,
        val thinkMaxModelName: String,
        val boundEncyclopediaName: String,
    ) {
        fun toEntity(boundEncyclopediaId: Long): CharacterEntity = CharacterEntity(
            name = name,
            personaPrompt = personaPrompt,
            apiBaseUrl = apiBaseUrl,
            modelName = modelName,
            temperature = temperature,
            maxTokens = maxTokens,
            topP = topP,
            topK = topK,
            frequencyPenalty = frequencyPenalty,
            presencePenalty = presencePenalty,
            repetitionPenalty = repetitionPenalty,
            avatarColor = avatarColor,
            avatarImagePath = avatarImagePath,
            cardImagePath = cardImagePath,
            voiceProvider = voiceProvider,
            voiceApiBaseUrl = voiceApiBaseUrl,
            voiceModel = voiceModel,
            imageGenEnabled = imageGenEnabled,
            imageGenBaseUrl = imageGenBaseUrl,
            imageGenModel = imageGenModel,
            thinkMaxEnabled = thinkMaxEnabled,
            thinkMaxModelName = thinkMaxModelName,
            boundEncyclopediaId = boundEncyclopediaId,
            apiKey = "",
            voiceApiKey = "",
            imageGenApiKey = "",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    private val gson = Gson()

    fun toJson(characters: List<CharacterEntity>, encyclopedias: List<EncyclopediaNameOption>): String {
        val encNameById = encyclopedias.associate { it.id to it.name }
        val data = characters.map { character ->
            mapOf(
                "name" to character.name,
                "personaPrompt" to character.personaPrompt,
                "apiBaseUrl" to character.apiBaseUrl,
                "modelName" to character.modelName,
                "temperature" to character.temperature,
                "maxTokens" to character.maxTokens,
                "topP" to character.topP,
                "topK" to character.topK,
                "frequencyPenalty" to character.frequencyPenalty,
                "presencePenalty" to character.presencePenalty,
                "repetitionPenalty" to character.repetitionPenalty,
                "avatarColor" to character.avatarColor,
                "avatarImagePath" to character.avatarImagePath,
                "cardImagePath" to character.cardImagePath,
                "voiceProvider" to character.voiceProvider,
                "voiceApiBaseUrl" to character.voiceApiBaseUrl,
                "voiceModel" to character.voiceModel,
                "imageGenEnabled" to character.imageGenEnabled,
                "imageGenBaseUrl" to character.imageGenBaseUrl,
                "imageGenModel" to character.imageGenModel,
                "thinkMaxEnabled" to character.thinkMaxEnabled,
                "thinkMaxModelName" to character.thinkMaxModelName,
                // 用百科名称而不是旧数据库 ID，避免在另一台设备上误绑同号百科。
                "boundEncyclopediaName" to encNameById[character.boundEncyclopediaId].orEmpty(),
            )
        }
        return gson.toJson(mapOf("version" to 2, "type" to "characters", "data" to data))
    }

    fun fromJson(json: String): List<ExportedCharacter> {
        val root = JsonParser.parseString(json)
        val data = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("data")
            else -> null
        } ?: throw IllegalArgumentException("未找到角色数据")
        return data.mapNotNull characterItem@ { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@characterItem null
            val name = obj.string("name").trim()
            if (name.isEmpty()) return@characterItem null
            ExportedCharacter(
                name = name,
                personaPrompt = obj.string("personaPrompt"),
                apiBaseUrl = obj.string("apiBaseUrl"),
                modelName = obj.string("modelName").ifBlank { "deepseek-chat" },
                temperature = obj.float("temperature", 0.9f),
                maxTokens = obj.int("maxTokens", 1200),
                topP = obj.float("topP", 1.0f),
                topK = obj.int("topK", 0),
                frequencyPenalty = obj.float("frequencyPenalty", 0.0f),
                presencePenalty = obj.float("presencePenalty", 0.0f),
                repetitionPenalty = obj.float("repetitionPenalty", 1.0f),
                avatarColor = obj.string("avatarColor").ifBlank { "#F97316" },
                avatarImagePath = obj.string("avatarImagePath"),
                cardImagePath = obj.string("cardImagePath"),
                voiceProvider = obj.string("voiceProvider"),
                voiceApiBaseUrl = obj.string("voiceApiBaseUrl"),
                voiceModel = obj.string("voiceModel"),
                imageGenEnabled = obj.boolean("imageGenEnabled"),
                imageGenBaseUrl = obj.string("imageGenBaseUrl"),
                imageGenModel = obj.string("imageGenModel").ifBlank { "dall-e-3" },
                thinkMaxEnabled = obj.boolean("thinkMaxEnabled"),
                thinkMaxModelName = obj.string("thinkMaxModelName"),
                boundEncyclopediaName = obj.string("boundEncyclopediaName"),
            )
        }
    }

    fun resolveBoundEncyclopediaId(name: String, encyclopedias: List<EncyclopediaNameOption>): Long {
        val normalized = name.trim()
        if (normalized.isEmpty()) return 0L
        val matches = encyclopedias.filter { it.name.trim().equals(normalized, ignoreCase = true) }
        return matches.singleOrNull()?.id ?: 0L
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.float(name: String, default: Float): Float =
        get(name)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asFloat ?: default

    private fun JsonObject.int(name: String, default: Int): Int =
        get(name)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asInt ?: default

    private fun JsonObject.boolean(name: String): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asBoolean == true
}
@HiltViewModel
class CharacterListViewModel @Inject constructor(
    private val characterDao: CharacterDao,
    private val characterProfileDao: CharacterProfileDao,
    private val encyclopediaDao: EncyclopediaDao,
    private val entryDao: EncyclopediaEntryDao,
    private val deleteCharacter: DeleteCharacterUseCase,
    private val saveCharacterBinding: SaveCharacterBindingUseCase,
    private val smartImportUseCase: SmartImportUseCase,
    private val createSessionUseCase: CreateSessionUseCase,
    private val uiPreferencesRepository: UiPreferencesRepository,
) : ViewModel() {
    private val gson = Gson()

    private val _filterEncyclopediaId = MutableStateFlow<Long?>(null)
    val filterEncyclopediaId: StateFlow<Long?> = _filterEncyclopediaId.asStateFlow()

    private val _selectedFilterName = MutableStateFlow<String?>(null)
    val selectedFilterName: StateFlow<String?> = _selectedFilterName.asStateFlow()

    private val _startingCharacterId = MutableStateFlow<Long?>(null)
    val startingCharacterId: StateFlow<Long?> = _startingCharacterId.asStateFlow()

    private val _creatingCharacter = MutableStateFlow(false)
    val creatingCharacter: StateFlow<Boolean> = _creatingCharacter.asStateFlow()

    private val _deletingCharacterId = MutableStateFlow<Long?>(null)
    val deletingCharacterId: StateFlow<Long?> = _deletingCharacterId.asStateFlow()

    private val _page = MutableStateFlow(CharacterLibraryPage())
    val page: StateFlow<CharacterLibraryPage> = _page.asStateFlow()
    private var sort = CharacterLibrarySort.RECOMMENDED
    private val pageCursors = mutableListOf<CharacterListItem?>(null)
    private var pageJob: Job? = null
    private var pageRevision = 0L
    private var retryPageIndex: Int? = null

    fun refreshList(keepVisible: Boolean = true) {
        pageCursors.clear()
        pageCursors += null
        retryPageIndex = null
        if (!keepVisible) _page.value = CharacterLibraryPage()
        loadPage(0)
    }

    fun nextPage() {
        val current = _page.value
        if (current.loading || current.error != null || !current.hasNext) return
        val cursor = current.items.lastOrNull() ?: return
        val next = current.pageIndex + 1
        if (pageCursors.size <= next) pageCursors += cursor else pageCursors[next] = cursor
        loadPage(next)
    }

    fun previousPage() {
        val current = _page.value
        if (!current.loading && current.error == null && current.pageIndex > 0 && current.pageIndex < pageCursors.size) {
            loadPage(current.pageIndex - 1)
        }
    }

    fun retryPage() = loadPage(retryPageIndex ?: _page.value.pageIndex)

    private fun loadPage(index: Int) {
        pageJob?.cancel()
        val revision = ++pageRevision
        val cursor = pageCursors[index]
        val filter = _filterEncyclopediaId.value
        val requestedSort = sort
        _page.value = _page.value.copy(loading = true, error = null)
        pageJob = viewModelScope.launch {
            try {
                val rows = when (requestedSort) {
                    CharacterLibrarySort.RECOMMENDED -> characterDao.getLibraryRecommendedPage(
                        filter, cursor?.pinnedAt?.let { if (it > 0) 1 else 0 }, cursor?.pinnedAt,
                        cursor?.favorite, cursor?.createdAt, cursor?.id, CHARACTER_LIBRARY_PAGE_SIZE + 1,
                    )
                    CharacterLibrarySort.RECENT -> characterDao.getLibraryRecentPage(
                        filter, cursor?.pinnedAt?.let { if (it > 0) 1 else 0 },
                        cursor?.createdAt, cursor?.id, CHARACTER_LIBRARY_PAGE_SIZE + 1,
                    )
                    CharacterLibrarySort.NAME -> characterDao.getLibraryNamePage(
                        filter, cursor?.pinnedAt?.let { if (it > 0) 1 else 0 },
                        cursor?.name, cursor?.id, CHARACTER_LIBRARY_PAGE_SIZE + 1,
                    )
                }
                if (revision == pageRevision) {
                    retryPageIndex = null
                    _page.value = CharacterLibraryPage(
                        items = rows.take(CHARACTER_LIBRARY_PAGE_SIZE),
                        pageIndex = index,
                        hasNext = rows.size > CHARACTER_LIBRARY_PAGE_SIZE,
                        loading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (revision == pageRevision) {
                    retryPageIndex = index
                    _page.value = _page.value.copy(loading = false, error = "角色加载失败，请重试")
                }
            }
        }
    }

    /** `"list"` 或 `"grid"`，持久化在 DataStore，离开页面后保持 */
    val characterListLayout = uiPreferencesRepository.characterListLayout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "list")

    suspend fun loadEncyclopediaFilterPage(query: String, cursor: EncyclopediaFilterOption?): CharacterFilterPage {
        val rows = encyclopediaDao.getCharacterFilterPage(
            query.trim(), cursor?.pinnedAt?.let { if (it > 0) 1 else 0 }, cursor?.pinnedAt,
            cursor?.updatedAt, cursor?.id, CHARACTER_FILTER_PAGE_SIZE + 1,
        )
        return CharacterFilterPage(rows.take(CHARACTER_FILTER_PAGE_SIZE), rows.size > CHARACTER_FILTER_PAGE_SIZE)
    }

    fun refreshSelectedFilterName() {
        val id = _filterEncyclopediaId.value ?: return
        viewModelScope.launch {
            try {
                val name = encyclopediaDao.getNameById(id)
                if (_filterEncyclopediaId.value == id) _selectedFilterName.value = name
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* Keep the selected ID visible when its label cannot be read. */ }
        }
    }

    fun setEncyclopediaFilter(encyclopediaId: Long?, name: String? = null) {
        val next = encyclopediaId?.takeIf { it > 0L }
        _selectedFilterName.value = when {
            next == null -> null
            name != null -> name
            next == _filterEncyclopediaId.value -> _selectedFilterName.value
            else -> null
        }
        if (_filterEncyclopediaId.value != next) {
            _filterEncyclopediaId.value = next
            refreshList(keepVisible = false)
        }
    }

    fun setSortOrder(next: CharacterLibrarySort) {
        if (sort != next) {
            sort = next
            refreshList(keepVisible = false)
        }
    }

    fun toggleCharacterListLayout() {
        viewModelScope.launch {
            val next = if (characterListLayout.value == "grid") "list" else "grid"
            uiPreferencesRepository.setCharacterListLayout(next)
        }
    }

    /** 新建角色；百科 ID 为 0 表示暂不绑定。返回新行 id（Room insert 返回值）。 */
    fun createNew(
        encyclopediaId: Long = 0L,
        onCreated: (Long) -> Unit = {},
        onFailed: (String) -> Unit = {},
    ) {
        if (_creatingCharacter.value) return
        _creatingCharacter.value = true
        viewModelScope.launch {
            val effectiveId = try {
                val row = newCharacterDraft(encyclopediaId)
                saveCharacterBinding(row)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailed("创建角色失败，请重试")
                null
            } finally {
                _creatingCharacter.value = false
            } ?: return@launch
            onCreated(effectiveId)
        }
    }

    fun delete(id: Long, onDeleted: () -> Unit = {}, onFailed: (String) -> Unit = {}) {
        if (_deletingCharacterId.value != null) return
        _deletingCharacterId.value = id
        viewModelScope.launch {
            val deleted = try {
                deleteCharacter(id)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailed("删除角色失败，请重试")
                false
            } finally {
                _deletingCharacterId.value = null
            }
            if (deleted) {
                onDeleted()
                refreshList()
            }
        }
    }

    fun setCharacterPinned(id: Long, pinned: Boolean, onFailed: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val c = characterDao.getById(id) ?: return@launch
                characterDao.upsert(c.copy(pinnedAt = if (pinned) System.currentTimeMillis() else 0L))
                refreshList()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailed("置顶操作失败，请重试")
            }
        }
    }

    fun toggleFavorite(id: Long, onFailed: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                characterDao.toggleFavorite(id)
                refreshList()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailed("收藏操作失败，请重试")
            }
        }
    }

    fun startChat(
        characterId: Long,
        onCreated: (Long) -> Unit,
        onNeedsEncyclopedia: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        if (_startingCharacterId.value != null) return
        _startingCharacterId.value = characterId
        viewModelScope.launch {
            try {
                when (val result = createSessionUseCase.createForCharacter(characterId)) {
                    is CreateSessionUseCase.Result.Created -> onCreated(result.sessionId)
                    CreateSessionUseCase.Result.UnboundCharacter -> onNeedsEncyclopedia()
                    CreateSessionUseCase.Result.CharacterNotFound -> onFailed("找不到这个角色")
                    CreateSessionUseCase.Result.EncyclopediaMismatch -> onFailed("角色与百科不一致")
                    CreateSessionUseCase.Result.EmptyParticipants -> onFailed("角色尚未准备好")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailed("创建对话失败，请重试")
            } finally {
                _startingCharacterId.value = null
            }
        }
    }

    suspend fun exportJson(): String {
        val characters = characterDao.getAll()
        val encyclopedias = encyclopediaDao.getAllNameOptions()
        return withContext(Dispatchers.Default) {
            CharacterExportCodec.toJson(characters, encyclopedias)
        }
    }

    suspend fun importFromDocument(bytes: ByteArray, fileHint: String?): CharacterImportResult {
        importTavernPngIfApplicable(bytes, fileHint)?.let { return it }
        tryImportPortable(bytes, fileHint)?.let { return it }
        val text = withContext(Dispatchers.Default) {
            com.mojing.app.domain.util.DocxTextExtractor.tryExtractPlainText(bytes)
                ?: bytes.toString(Charsets.UTF_8)
        }
        return performImport(text)
    }

    fun importJson(text: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            tryImportPortableText(text)?.let {
                onResult(it.message)
                return@launch
            }
            onResult(performImport(text).message)
        }
    }

    private suspend fun tryImportPortable(bytes: ByteArray, hint: String?): CharacterImportResult? {
        val lower = hint?.lowercase() ?: ""
        val text = withContext(Dispatchers.Default) {
            when {
                lower.endsWith(".docx") ||
                    (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) -> {
                    com.mojing.app.domain.util.DocxTextExtractor.tryExtractPlainText(bytes)
                }
                else -> runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            }
        }
        return text?.let { tryImportPortableText(it) }
    }

    private suspend fun tryImportPortableText(text: String): CharacterImportResult? {
        val parsed = withContext(Dispatchers.Default) {
            runCatching {
                val obj: JsonObject = if (text.trimStart().startsWith("{")) {
                    gson.fromJson(text, JsonObject::class.java)
                } else {
                    CharacterPortableCodec.parseTxt(text)
                }
                if (CharacterPortableCodec.isPortableKind(obj.get("kind")?.asString)) {
                    CharacterPortableCodec.parsePortableJson(obj)
                } else {
                    CharacterPortableCodec.tryParseTavernLike(obj)
                }
            }.getOrNull()
        }
        if (parsed == null) return null
        return persistParsedPortable(parsed, portableLabel = "便携包")
    }

    /** 自 PNG 内嵌 JSON 导入角色与档案。 */
    private suspend fun importTavernPngIfApplicable(bytes: ByteArray, fileHint: String?): CharacterImportResult? {
        if (!CharacterCardPngCodec.isPng(bytes)) return null
        val fname = fileHint?.substringAfterLast('/')?.substringAfterLast('\\')?.ifBlank { null } ?: "character.png"
        val parsed = withContext(Dispatchers.Default) {
            CharacterCardPngCodec.readCharaCardJsonRoot(bytes)
                ?.let { root -> CharacterCardV2Converter.jsonRootToParsedPortable(root, fname) }
        } ?: return null
        return persistParsedPortable(parsed, portableLabel = "PNG 形象卡")
    }

    private suspend fun persistParsedPortable(
        parsed: CharacterPortableCodec.ParsedPortable,
        portableLabel: String,
    ): CharacterImportResult {
        val base = CharacterPortableCodec.normalizedNameBase(parsed.name)
        val names = characterDao.getNamesStartingWith(base).toHashSet()
        val name = CharacterPortableCodec.allocateUniqueName(names, base)
        val entity = CharacterEntity(
            name = name,
            personaPrompt = parsed.personaPrompt,
            apiBaseUrl = parsed.apiBaseUrl?.takeIf { it.isNotBlank() } ?: "",
            modelName = parsed.modelName?.takeIf { it.isNotBlank() } ?: "deepseek-chat",
            temperature = parsed.temperature ?: 0.9f,
            maxTokens = parsed.maxTokens ?: 1200,
            avatarColor = parsed.avatarColor?.takeIf { it.isNotBlank() } ?: "#F97316",
            avatarImagePath = parsed.avatarImagePath?.takeIf { it.isNotBlank() }.orEmpty(),
            cardImagePath = parsed.cardImagePath?.takeIf { it.isNotBlank() }.orEmpty(),
        )
        val effectiveId = saveCharacterBinding(entity)
        var profileFailed = false
        parsed.profile?.let { pr ->
            try {
                characterProfileDao.upsert(
                    CharacterProfileEntity(
                        characterId = effectiveId,
                        sourceFilename = pr.sourceFilename.take(255),
                        rawPersonaText = pr.rawPersonaText,
                        characterCardMarkdown = pr.characterCardMarkdown,
                        characterCardJson = pr.characterCardJson,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                profileFailed = true
            }
        }
        return CharacterImportResult(
            message = if (profileFailed) "已导入${portableLabel}「$name」，附加档案保存失败，请检查角色详情"
                else "已导入${portableLabel}「$name」（请在编辑页绑定百科）",
            importedIds = listOf(effectiveId),
            hasFailure = profileFailed,
        )
    }

    private suspend fun performImport(text: String): CharacterImportResult {
        val importedIds = mutableListOf<Long>()
        var unboundCount = 0
        return try {
            val json = smartImportUseCase.parseToStructuredJson(text, "character")
            val data = withContext(Dispatchers.Default) { CharacterExportCodec.fromJson(json) }
            val encyclopedias = encyclopediaDao.getAllNameOptions()
            data.forEach { exported ->
                val boundId = CharacterExportCodec.resolveBoundEncyclopediaId(
                    exported.boundEncyclopediaName,
                    encyclopedias,
                )
                importedIds += saveCharacterBinding(exported.toEntity(boundId))
                if (boundId == 0L) unboundCount++
            }
            if (importedIds.isEmpty()) CharacterImportResult("未找到可导入的角色", hasFailure = true)
            else CharacterImportResult(
                "成功导入 ${importedIds.size} 个角色" +
                    (if (unboundCount > 0) "，其中 $unboundCount 个待绑定百科" else ""),
                importedIds.toList(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (importedIds.isNotEmpty()) {
                CharacterImportResult("已导入 ${importedIds.size} 个角色，其余导入失败，请检查文件或重试", importedIds.toList(), hasFailure = true)
            } else {
                CharacterImportResult("导入失败，请检查文件格式后重试", hasFailure = true)
            }
        }
    }
}

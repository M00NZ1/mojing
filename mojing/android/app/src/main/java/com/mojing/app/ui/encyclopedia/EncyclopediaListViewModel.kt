package com.mojing.app.ui.encyclopedia

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.dao.EncyclopediaLibraryItem
import com.mojing.app.data.local.dao.GenerationTaskDao
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.remote.BackendEncyclopediaApi
import com.mojing.app.data.repository.ImageRepository
import com.mojing.app.data.remote.resolveBackendApiRoot
import com.mojing.app.data.remote.resolveMediaUrlAgainstPublicBase
import com.mojing.app.util.ApiRootLines
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.domain.usecase.SmartImportUseCase
import com.mojing.app.domain.usecase.ImportEncyclopediaJsonUseCase
import com.mojing.app.domain.usecase.EncyclopediaImportResult
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.ui.util.KeyedOperationOwner
import com.mojing.app.util.ContentDocumentReader
import com.google.gson.stream.JsonWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.StringWriter
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

// World prompts and entry bodies are large; export reads only one small page of each at a time.
private const val ENCYCLOPEDIA_EXPORT_BATCH_SIZE = 32

enum class WorldLibrarySort(val key: String, val label: String) {
    PINNED("pinned", "置顶优先"), UPDATED("updated", "最近更新"), NAME("name", "名称")
}

data class EncyclopediaLibraryState(
    val items: List<EncyclopediaLibraryItem> = emptyList(),
    val query: String = "",
    val onlyPinned: Boolean = false,
    val sort: WorldLibrarySort = WorldLibrarySort.PINNED,
    val pageIndex: Int = 0,
    val hasNext: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

enum class EncyclopediaImportOutcome { SUCCESS, FAILURE, CANCELLED }

data class EncyclopediaDocumentImportState(
    val running: Boolean = false,
    val cancelling: Boolean = false,
    val worlds: Int = 0,
    val entries: Int = 0,
    val result: String? = null,
    val outcome: EncyclopediaImportOutcome? = null,
)

internal object EncyclopediaExportCodec {
    fun toJson(
        encyclopedias: List<EncyclopediaEntity>,
        entriesByEncyclopediaId: Map<Long, List<EncyclopediaEntryEntity>>,
    ): String {
        val output = StringWriter()
        val writer = JsonWriter(output)
        begin(writer)
        encyclopedias.forEach { encyclopedia ->
            beginEncyclopedia(writer, encyclopedia)
            entriesByEncyclopediaId[encyclopedia.id].orEmpty().forEach { entry -> writeEntry(writer, entry) }
            endEncyclopedia(writer)
        }
        end(writer)
        writer.flush()
        return output.toString()
    }

    fun begin(writer: JsonWriter) {
        writer.beginObject()
        writer.name("version").value(2)
        writer.name("type").value("encyclopedias")
        writer.name("data").beginArray()
    }

    fun beginEncyclopedia(writer: JsonWriter, encyclopedia: EncyclopediaEntity) {
        writer.beginObject()
        writer.name("name").value(encyclopedia.name)
        writer.name("description").value(encyclopedia.description)
        writer.name("coverImagePath").value(encyclopedia.coverImagePath)
        writer.name("genreTags").value(encyclopedia.genreTags)
        writer.name("worldPrompt").value(encyclopedia.worldPrompt)
        writer.name("gameplayMode").value(encyclopedia.gameplayMode)
        writer.name("antiCheatPrompt").value(encyclopedia.antiCheatPrompt)
        writer.name("narratorConfigJson").value(encyclopedia.narratorConfigJson)
        writer.name("entries").beginArray()
    }

    fun writeEntry(writer: JsonWriter, entry: EncyclopediaEntryEntity) {
        writer.beginObject()
        writer.name("title").value(entry.title)
        writer.name("entryType").value(entry.entryType)
        writer.name("summary").value(entry.summary)
        writer.name("content").value(entry.content)
        writer.name("tags").value(entry.tags)
        writer.name("confidence").value(entry.confidence)
        writer.name("isFeatured").value(entry.isFeatured)
        writer.name("changeNote").value(entry.changeNote)
        writer.name("coverImagePath").value(entry.coverImagePath)
        writer.endObject()
    }

    fun endEncyclopedia(writer: JsonWriter) {
        writer.endArray()
        writer.endObject()
    }

    fun end(writer: JsonWriter) {
        writer.endArray()
        writer.endObject()
    }

}
@HiltViewModel
class EncyclopediaListViewModel @Inject constructor(
    private val encyclopediaDao: EncyclopediaDao,
    private val entryDao: EncyclopediaEntryDao,
    private val characterDao: CharacterDao,
    private val importEncyclopediaJson: ImportEncyclopediaJsonUseCase,
    private val smartImportUseCase: SmartImportUseCase,
    private val secureStorage: SecureStorage,
    private val backendEncyclopediaApi: BackendEncyclopediaApi,
    private val imageRepository: ImageRepository,
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val generationTaskDao: GenerationTaskDao,
    private val deleteWorld: com.mojing.app.domain.usecase.DeleteWorldUseCase,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val createMutex = Mutex()
    private val coverGenerationOwner = KeyedOperationOwner<Long>()
    val coverGeneratingEncyclopediaIds: StateFlow<Set<Long>> = coverGenerationOwner.activeKeys

    private val libraryPageSize = 24 // Image-heavy world cards use the project list standard.
    private val _library = MutableStateFlow(EncyclopediaLibraryState(
        query = savedStateHandle.get<String>("library_query").orEmpty(),
        onlyPinned = savedStateHandle.get<Boolean>("library_pinned") ?: false,
        sort = WorldLibrarySort.entries.firstOrNull { it.key == savedStateHandle.get<String>("library_sort") } ?: WorldLibrarySort.PINNED,
    ))
    val library: StateFlow<EncyclopediaLibraryState> = _library.asStateFlow()
    private val _documentImport = MutableStateFlow(EncyclopediaDocumentImportState())
    val documentImport: StateFlow<EncyclopediaDocumentImportState> = _documentImport.asStateFlow()
    private var documentImportJob: Job? = null
    private val pageCursors = restoreCursors()
    private var loadJob: Job? = null
    private var loadRevision = 0
    private var pendingPageIndex = 0

    fun setSearchQuery(query: String) {
        val normalized = query.trim()
        if (normalized == _library.value.query) return
        resetCriteria(_library.value.copy(query = normalized))
    }

    fun setOnlyPinned(pinned: Boolean) {
        if (pinned != _library.value.onlyPinned) resetCriteria(_library.value.copy(onlyPinned = pinned))
    }

    fun setSort(sort: WorldLibrarySort) {
        if (sort != _library.value.sort) resetCriteria(_library.value.copy(sort = sort))
    }

    private fun resetCriteria(state: EncyclopediaLibraryState) {
        _library.value = EncyclopediaLibraryState(query = state.query, onlyPinned = state.onlyPinned, sort = state.sort)
        pageCursors.clear()
        pageCursors.add(null)
        savePosition(0)
        loadPage(0)
    }

    // Only cursor identity is saved. Room is queried again after recreation; no card bodies in Bundle.
    private fun restoreCursors(): MutableList<EncyclopediaLibraryItem?> {
        val pairs = savedStateHandle.get<LongArray>("library_cursors") ?: return mutableListOf(null)
        val names = savedStateHandle.get<ArrayList<String>>("library_names") ?: return mutableListOf(null)
        val index = savedStateHandle.get<Int>("library_page") ?: 0
        if (pairs.size % 3 != 0 || pairs.size / 3 != names.size || names.size > 127 ||
            names.sumOf { it.length } > 16_384 || index != names.size) return mutableListOf(null)
        val result = mutableListOf<EncyclopediaLibraryItem?>(null)
        for (i in names.indices) {
            val id = pairs[i * 3]
            if (id <= 0 || pairs[i * 3 + 1] < 0) return mutableListOf(null)
            result.add(EncyclopediaLibraryItem(id, names[i], "", pairs[i * 3 + 1], pairs[i * 3 + 2], "", ""))
        }
        return result
    }

    private fun savePosition(index: Int) {
        val state = _library.value
        savedStateHandle["library_query"] = state.query
        savedStateHandle["library_pinned"] = state.onlyPinned
        savedStateHandle["library_sort"] = state.sort.key
        val candidates = if (index < 128) pageCursors.take(index + 1).filterNotNull() else emptyList()
        val canSave = index < 128 && candidates.sumOf { it.name.length } <= 16_384
        val cursors = if (canSave) candidates else emptyList()
        savedStateHandle["library_page"] = if (canSave) index else 0
        savedStateHandle["library_cursors"] = cursors.flatMap { listOf(it.id, it.pinnedAt, it.updatedAt) }.toLongArray()
        savedStateHandle["library_names"] = ArrayList(cursors.map { it.name })
    }

    fun nextPage() {
        val state = _library.value
        if (state.loading || state.error != null || !state.hasNext) return
        val cursor = state.items.lastOrNull() ?: return
        while (pageCursors.size > state.pageIndex + 1) pageCursors.removeAt(pageCursors.lastIndex)
        pageCursors.add(cursor)
        loadPage(state.pageIndex + 1)
    }

    fun previousPage() {
        val state = _library.value
        if (state.loading || state.pageIndex == 0) return
        loadPage(state.pageIndex - 1)
    }

    fun retryPage() = loadPage(pendingPageIndex)

    private fun loadPage(index: Int, fallbackToPreviousWhenEmpty: Boolean = false) {
        loadJob?.cancel()
        pendingPageIndex = index
        val revision = ++loadRevision
        val current = _library.value
        _library.value = current.copy(loading = true, error = null)
        val cursor = pageCursors.getOrNull(index)
        loadJob = viewModelScope.launch {
            try {
                val rows = encyclopediaDao.getLibraryPage(
                    query = current.query,
                    cursorPinned = cursor?.let { if (current.sort == WorldLibrarySort.PINNED && it.pinnedAt > 0) 1 else 0 },
                    cursorPinnedAt = cursor?.let { if (current.sort == WorldLibrarySort.PINNED) it.pinnedAt else 0L },
                    cursorUpdatedAt = cursor?.updatedAt,
                    cursorId = cursor?.id,
                    limit = libraryPageSize + 1,
                    onlyPinned = current.onlyPinned,
                    sort = current.sort.key,
                    cursorName = cursor?.let { if (current.sort == WorldLibrarySort.NAME) it.name else "" },
                )
                if (revision != loadRevision) return@launch
                if (fallbackToPreviousWhenEmpty && rows.isEmpty() && index > 0) {
                    while (pageCursors.size > index) pageCursors.removeAt(pageCursors.lastIndex)
                    loadPage(index - 1, fallbackToPreviousWhenEmpty = true)
                    return@launch
                }
                _library.value = current.copy(
                    items = rows.take(libraryPageSize),
                    pageIndex = index,
                    hasNext = rows.size > libraryPageSize,
                    loading = false,
                    loaded = true,
                    error = null,
                )
                while (pageCursors.size > index + 1) pageCursors.removeAt(pageCursors.lastIndex)
                savePosition(index)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (revision == loadRevision) {
                    _library.value = current.copy(loading = false, error = "世界列表加载失败，请重试")
                }
            }
        }
    }

    private val _hasPublicLlmKey = MutableStateFlow(false)
    val hasPublicLlmKey: StateFlow<Boolean> = _hasPublicLlmKey.asStateFlow()

    fun syncPublicLlmKeyFromStorage() {
        _hasPublicLlmKey.value = secureStorage.publicApiKey.isNotBlank()
    }

    val encyclopediaListLayout = uiPreferencesRepository.encyclopediaListLayout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "list")

    val pendingGenerationTaskCount = generationTaskDao.observeActiveCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun toggleEncyclopediaListLayout() {
        viewModelScope.launch {
            val next = if (encyclopediaListLayout.value == "grid") "list" else "grid"
            uiPreferencesRepository.setEncyclopediaListLayout(next)
        }
    }

    init {
        syncPublicLlmKeyFromStorage()
        loadPage(pageCursors.lastIndex, fallbackToPreviousWhenEmpty = true)
    }

    suspend fun createNew(): Result<Long> = createMutex.withLock {
        val entity = EncyclopediaEntity(name = "新百科库")
        try {
            val id = encyclopediaDao.upsert(entity)
            if (id <= 0L) return@withLock Result.failure(IllegalStateException("百科创建失败，请重试"))
            refresh()
            Result.success(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IllegalStateException("百科创建失败，请重试"))
        }
    }

    suspend fun rename(id: Long, name: String): String? {
        if (name.isBlank()) return "请输入百科名称"
        return try {
            if (encyclopediaDao.updateName(id, name.trim(), System.currentTimeMillis()) == 0) "百科已不存在"
            else { refresh(); null }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { "名称保存失败，请重试" }
    }

    suspend fun delete(id: Long): String? = try {
        val targetQuery = _library.value.query
        val targetPageIndex = _library.value.pageIndex
        val targetLoadRevision = loadRevision
        val error = deleteWorld(id)
        if (error == null && _library.value.query == targetQuery &&
            _library.value.pageIndex == targetPageIndex && loadRevision == targetLoadRevision
        ) {
            loadPage(targetPageIndex, fallbackToPreviousWhenEmpty = true)
        }
        error
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        "世界删除失败，请重试"
    }

    fun setEncyclopediaPinned(id: Long, pinned: Boolean) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                if (encyclopediaDao.updatePinned(id, if (pinned) now else 0L, now) > 0) {
                    refresh()
                } else {
                    _library.value = _library.value.copy(error = "世界已不存在，请重试")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _library.value = _library.value.copy(error = "置顶状态保存失败，请重试")
            }
        }
    }

    fun refresh() {
        syncPublicLlmKeyFromStorage()
        pageCursors.clear()
        pageCursors.add(null)
        savePosition(0)
        loadPage(0)
    }

    fun refreshCurrentPage() = loadPage(_library.value.pageIndex, fallbackToPreviousWhenEmpty = true)

    suspend fun updateEncyclopediaCover(id: Long, localPath: String): String = withContext(NonCancellable) {
        val path = localPath.trim()
        if (path.isBlank()) return@withContext UserFacingStrings.localSaveFailed("封面")
        try {
            val now = System.currentTimeMillis()
            if (encyclopediaDao.updateCover(id, path, now) <= 0) {
                runCatching { File(path).delete() }
                return@withContext if (encyclopediaDao.getById(id) == null) {
                    "百科库已删除，封面未保存"
                } else {
                    UserFacingStrings.localSaveFailed("封面")
                }
            }
            refresh()
            "已更新封面"
        } catch (_: Exception) {
            runCatching { File(path).delete() }
            UserFacingStrings.localSaveFailed("封面")
        }
    }

    /** 优先走墨境 FastAPI 预览；只有预览未生成图片时才改用设置里的配图线路。 */
    fun generateEncyclopediaCoverAi(id: Long, onResult: (String) -> Unit): Boolean {
        if (!coverGenerationOwner.tryStart(id)) return false
        viewModelScope.launch {
            try {
                onResult(generateEncyclopediaCoverAiInternal(id))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(UserFacingStrings.remoteRequestFailed(e))
            } finally {
                coverGenerationOwner.finish(id)
            }
        }
        return true
    }

    internal suspend fun generateEncyclopediaCoverAiInternal(id: Long): String = withContext(Dispatchers.IO) {
        val enc = try {
            encyclopediaDao.getById(id)
        } catch (_: Exception) {
            return@withContext UserFacingStrings.localLoadFailed("百科库")
        } ?: return@withContext "未找到百科库"
        if (enc.name.isBlank()) return@withContext "请先给百科库起名，再尝试 AI 封面"

        fun publicRootLine(): String = ApiRootLines.split(secureStorage.publicBaseUrl).firstOrNull()?.trim()?.trimEnd('/')
            ?: secureStorage.publicBaseUrl.trim().trimEnd('/')

        val backendRoot = resolveBackendApiRoot(secureStorage)
        if (backendRoot.isNotBlank()) {
            val worldSummary = buildString {
                if (enc.description.isNotBlank()) append(enc.description.trim())
                if (enc.worldPrompt.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(enc.worldPrompt.trim())
                }
            }.toString().take(4000).ifBlank { enc.genreTags }

            val res = backendEncyclopediaApi.previewEntryCoverImage(
                title = enc.name,
                entryType = "world",
                summary = worldSummary,
                promptHint = enc.genreTags.trim().ifBlank { "世界设定" },
            )
            val jo = res.getOrNull()
            if (jo != null) {
                val err = jo.get("error")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                if (err.isBlank()) {
                    val arr = jo.getAsJsonArray("urls")
                    val url = arr?.takeIf { it.size() > 0 }?.get(0)?.asJsonPrimitive?.asString?.trim().orEmpty()
                    if (url.isNotBlank()) {
                        val root = publicRootLine()
                        val abs = resolveMediaUrlAgainstPublicBase(root, url)
                        val finalPath = imageRepository.downloadAndSaveImageForCover(abs).getOrElse { error ->
                            return@withContext when (error.message) {
                                "图片下载失败" -> UserFacingStrings.entryCoverDownloadFailed()
                                "图片裁切保存失败" -> UserFacingStrings.entryCoverSaveFailed()
                                else -> UserFacingStrings.remoteRequestFailed(error)
                            }
                        }
                        return@withContext persistEncyclopediaCover(id, enc.coverImagePath, finalPath)
                    }
                }
            }
        }

        val base = secureStorage.imageBaseUrl.trim().ifBlank { secureStorage.publicBaseUrl.trim() }
        val key = secureStorage.imageApiKey.trim().ifBlank { secureStorage.publicApiKey.trim() }
        val model = secureStorage.imageModel.trim().ifBlank { "dall-e-3" }
        if (base.isBlank() || key.isBlank()) {
            return@withContext UserFacingStrings.imageGenKeyMissing()
        }
        val prompt = buildString {
            append("竖版百科或卡牌插图，约 2:3，无文字无水印。世界百科库：")
            append(enc.name.take(120))
            append("。简介与设定：")
            append(
                listOfNotNull(
                    enc.description.trim().takeIf { it.isNotBlank() },
                    enc.genreTags.trim().takeIf { it.isNotBlank() },
                    enc.worldPrompt.trim().takeIf { it.isNotBlank() },
                ).joinToString(" ").take(1200),
            )
        }
        val finalPath = imageRepository.generateAndSaveImageForCover(
            apiKey = key,
            baseUrl = base,
            prompt = prompt,
            model = model,
            size = "1024x1024",
            quality = "standard",
        ).getOrElse { error ->
            return@withContext when (error.message) {
                "图片生成失败：未返回图片地址" -> UserFacingStrings.entryCoverNoUrl()
                "图片下载失败" -> UserFacingStrings.entryCoverDownloadFailed()
                "图片裁切保存失败" -> UserFacingStrings.entryCoverSaveFailed()
                else -> UserFacingStrings.remoteRequestFailed(error)
            }
        }
        persistEncyclopediaCover(id, enc.coverImagePath, finalPath)
    }

    private suspend fun persistEncyclopediaCover(id: Long, expectedPath: String, finalPath: String): String {
        val rejectedMessage = withContext(NonCancellable) {
            try {
                val updated = encyclopediaDao.updateCoverIfUnchanged(
                    id = id,
                    expectedPath = expectedPath,
                    newPath = finalPath,
                    updatedAt = System.currentTimeMillis(),
                )
                if (updated > 0) return@withContext null
                runCatching { File(finalPath).delete() }
                if (encyclopediaDao.getById(id) == null) {
                    "百科库已删除，未保存封面"
                } else {
                    "封面已在生成期间更新，未覆盖当前封面"
                }
            } catch (_: Exception) {
                runCatching { File(finalPath).delete() }
                UserFacingStrings.generatedCoverLocalSaveFailed()
            }
        }
        if (rejectedMessage != null) return rejectedMessage
        refresh()
        return "封面已生成并保存"
    }

    suspend fun exportJson(output: OutputStream) = withContext(Dispatchers.IO) {
        val writer = JsonWriter(OutputStreamWriter(output, Charsets.UTF_8))
        EncyclopediaExportCodec.begin(writer)
        var worldCursor: EncyclopediaEntity? = null
        do {
            currentCoroutineContext().ensureActive()
            val worlds = encyclopediaDao.getExportPage(
                cursorGroup = worldCursor?.let { if (it.pinnedAt > 0) 0 else 1 },
                cursorPinnedAt = worldCursor?.pinnedAt,
                cursorUpdatedAt = worldCursor?.updatedAt,
                cursorId = worldCursor?.id,
                limit = ENCYCLOPEDIA_EXPORT_BATCH_SIZE,
            )
            worlds.forEach { encyclopedia ->
                currentCoroutineContext().ensureActive()
                EncyclopediaExportCodec.beginEncyclopedia(writer, encyclopedia)
                var entryCursor = 0L
                do {
                    currentCoroutineContext().ensureActive()
                    val entries = entryDao.getExportPage(
                        encyclopedia.id, entryCursor, ENCYCLOPEDIA_EXPORT_BATCH_SIZE,
                    )
                    entries.forEach { entry ->
                        currentCoroutineContext().ensureActive()
                        EncyclopediaExportCodec.writeEntry(writer, entry)
                    }
                    entryCursor = entries.lastOrNull()?.id ?: entryCursor
                } while (entries.size == ENCYCLOPEDIA_EXPORT_BATCH_SIZE)
                EncyclopediaExportCodec.endEncyclopedia(writer)
            }
            worldCursor = worlds.lastOrNull()
        } while (worlds.size == ENCYCLOPEDIA_EXPORT_BATCH_SIZE)
        EncyclopediaExportCodec.end(writer)
        writer.flush()
    }

    fun startDocumentImport(context: Context, uri: Uri) {
        if (_documentImport.value.running) return
        _documentImport.value = EncyclopediaDocumentImportState(running = true)
        val applicationContext = context.applicationContext
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val committedResult = AtomicReference<EncyclopediaImportResult?>()
            try {
                val result = ContentDocumentReader.readStream(applicationContext, uri) { input ->
                    importDocument(
                        input,
                        onProgress = { progress ->
                            _documentImport.value = _documentImport.value.copy(
                                worlds = progress.worlds,
                                entries = progress.entries,
                            )
                        },
                        onCommitted = committedResult::set,
                    )
                }
                _documentImport.value = EncyclopediaDocumentImportState(
                    result = importResultMessage(result),
                    outcome = EncyclopediaImportOutcome.SUCCESS,
                )
            } catch (cancelled: CancellationException) {
                val saved = committedResult.get()
                _documentImport.value = if (saved != null) {
                    EncyclopediaDocumentImportState(
                        result = "取消时导入已完成。${importResultMessage(saved)}",
                        outcome = EncyclopediaImportOutcome.SUCCESS,
                    )
                } else {
                    EncyclopediaDocumentImportState(
                        result = "已取消导入。若文件刚好完成提交，请先核对世界列表再重试。",
                        outcome = EncyclopediaImportOutcome.CANCELLED,
                    )
                }
                throw cancelled
            } catch (error: Exception) {
                val saved = committedResult.get()
                _documentImport.value = if (saved != null) {
                    EncyclopediaDocumentImportState(
                        result = "${importResultMessage(saved)} 已保存；结束文件读取时出现异常，请核对世界列表。",
                        outcome = EncyclopediaImportOutcome.SUCCESS,
                    )
                } else {
                    EncyclopediaDocumentImportState(
                        result = "导入失败：${error.message ?: "无法读取或保存文件"}",
                        outcome = EncyclopediaImportOutcome.FAILURE,
                    )
                }
            } finally {
                documentImportJob = null
            }
        }
        documentImportJob = job
        job.start()
    }

    fun cancelDocumentImport() {
        if (!_documentImport.value.running || _documentImport.value.cancelling) return
        _documentImport.value = _documentImport.value.copy(cancelling = true)
        documentImportJob?.cancel()
    }

    fun dismissDocumentImportResult() {
        if (!_documentImport.value.running) _documentImport.value = EncyclopediaDocumentImportState()
    }

    suspend fun importDocument(
        input: InputStream,
        onProgress: (EncyclopediaImportResult) -> Unit = {},
        onCommitted: (EncyclopediaImportResult) -> Unit = {},
    ): EncyclopediaImportResult {
        val source = PushbackInputStream(input, 3)
        val first = firstContentByte(source)
        if (first < 0) throw IllegalArgumentException("导入文件为空")
        source.unread(first)
        return if (first == '{'.code || first == '['.code) {
            importStructuredJson(source, onProgress, onCommitted)
        } else {
            val text = ContentDocumentReader.readBytes(
                source, ContentDocumentReader.STRUCTURED_TEXT_IMPORT_MAX_BYTES,
            ).toString(Charsets.UTF_8)
            importJson(text, onProgress, onCommitted)
        }
    }

    private suspend fun importJson(
        text: String,
        onProgress: (EncyclopediaImportResult) -> Unit,
        onCommitted: (EncyclopediaImportResult) -> Unit,
    ): EncyclopediaImportResult {
        val json = smartImportUseCase.parseToStructuredJson(text, "encyclopedia")
        return importStructuredJson(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)), onProgress, onCommitted)
    }

    private suspend fun importStructuredJson(
        input: InputStream,
        onProgress: (EncyclopediaImportResult) -> Unit,
        onCommitted: (EncyclopediaImportResult) -> Unit,
    ): EncyclopediaImportResult {
        val result = importEncyclopediaJson.import(input, onProgress, onCommitted)
        refresh()
        return result
    }

    private fun importResultMessage(result: EncyclopediaImportResult): String =
        "导入 ${result.worlds} 个百科，共 ${result.entries} 个词条"

    private fun firstContentByte(source: PushbackInputStream): Int {
        var value = source.read()
        while (value == ' '.code || value == '\n'.code || value == '\r'.code || value == '\t'.code) {
            value = source.read()
        }
        if (value == 0xEF) {
            val second = source.read()
            val third = source.read()
            if (second == 0xBB && third == 0xBF) {
                value = source.read()
                while (value == ' '.code || value == '\n'.code || value == '\r'.code || value == '\t'.code) {
                    value = source.read()
                }
            } else {
                if (third >= 0) source.unread(third)
                if (second >= 0) source.unread(second)
            }
        }
        return value
    }
}

package com.mojing.app.ui.encyclopedia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
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
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.ui.util.KeyedOperationOwner
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

internal object EncyclopediaExportCodec {
    data class ExportedEntry(
        val title: String,
        val entryType: String,
        val summary: String,
        val content: String,
        val tags: String,
        val confidence: String,
        val isFeatured: Boolean,
        val changeNote: String,
        val coverImagePath: String,
    )

    data class ExportedEncyclopedia(
        val name: String,
        val description: String,
        val coverImagePath: String,
        val genreTags: String,
        val worldPrompt: String,
        val gameplayMode: String,
        val antiCheatPrompt: String,
        val narratorConfigJson: String,
        val entries: List<ExportedEntry>,
    )

    private val gson = Gson()

    fun toJson(
        encyclopedias: List<EncyclopediaEntity>,
        entriesByEncyclopediaId: Map<Long, List<EncyclopediaEntryEntity>>,
    ): String {
        val data = encyclopedias.map { encyclopedia ->
            mapOf(
                "name" to encyclopedia.name,
                "description" to encyclopedia.description,
                "coverImagePath" to encyclopedia.coverImagePath,
                "genreTags" to encyclopedia.genreTags,
                "worldPrompt" to encyclopedia.worldPrompt,
                "gameplayMode" to encyclopedia.gameplayMode,
                "antiCheatPrompt" to encyclopedia.antiCheatPrompt,
                "narratorConfigJson" to encyclopedia.narratorConfigJson,
                "entries" to entriesByEncyclopediaId[encyclopedia.id].orEmpty().map { entry ->
                    mapOf(
                        "title" to entry.title,
                        "entryType" to entry.entryType,
                        "summary" to entry.summary,
                        "content" to entry.content,
                        "tags" to entry.tags,
                        "confidence" to entry.confidence,
                        "isFeatured" to entry.isFeatured,
                        "changeNote" to entry.changeNote,
                        "coverImagePath" to entry.coverImagePath,
                    )
                },
            )
        }
        return gson.toJson(mapOf("version" to 2, "type" to "encyclopedias", "data" to data))
    }

    fun fromJson(json: String): List<ExportedEncyclopedia> {
        val root = JsonParser.parseString(json)
        val data = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("data")
            else -> null
        } ?: throw IllegalArgumentException("未找到百科数据")
        return data.mapNotNull encyclopediaItem@ { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@encyclopediaItem null
            val name = obj.string("name").trim()
            if (name.isEmpty()) return@encyclopediaItem null
            val entries = obj.getAsJsonArray("entries")?.mapNotNull entryItem@ { entryElement ->
                val entry = entryElement.takeIf { it.isJsonObject }?.asJsonObject ?: return@entryItem null
                ExportedEntry(
                    title = entry.string("title"),
                    entryType = entry.string("entryType").ifBlank { "world" },
                    summary = entry.string("summary"),
                    content = entry.string("content"),
                    tags = entry.string("tags"),
                    confidence = entry.string("confidence").ifBlank { "confirmed" },
                    isFeatured = entry.boolean("isFeatured"),
                    changeNote = entry.string("changeNote"),
                    coverImagePath = entry.string("coverImagePath"),
                )
            }.orEmpty()
            ExportedEncyclopedia(
                name = name,
                description = obj.string("description"),
                coverImagePath = obj.string("coverImagePath"),
                genreTags = obj.string("genreTags"),
                worldPrompt = obj.string("worldPrompt"),
                gameplayMode = obj.string("gameplayMode").ifBlank { "自由剧情" },
                antiCheatPrompt = obj.string("antiCheatPrompt"),
                narratorConfigJson = obj.string("narratorConfigJson").ifBlank { "{}" },
                entries = entries,
            )
        }
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.boolean(name: String): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asBoolean == true
}
@HiltViewModel
class EncyclopediaListViewModel @Inject constructor(
    private val encyclopediaDao: EncyclopediaDao,
    private val entryDao: EncyclopediaEntryDao,
    private val characterDao: CharacterDao,
    private val saveCharacterEntry: SaveCharacterEntryUseCase,
    private val smartImportUseCase: SmartImportUseCase,
    private val secureStorage: SecureStorage,
    private val backendEncyclopediaApi: BackendEncyclopediaApi,
    private val imageRepository: ImageRepository,
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val generationTaskDao: GenerationTaskDao,
    private val deleteWorld: com.mojing.app.domain.usecase.DeleteWorldUseCase,
) : ViewModel() {
    private val coverGenerationOwner = KeyedOperationOwner<Long>()
    val coverGeneratingEncyclopediaIds: StateFlow<Set<Long>> = coverGenerationOwner.activeKeys

    private val _encyclopedias = MutableStateFlow<List<EncyclopediaEntity>>(emptyList())
    val encyclopedias: StateFlow<List<EncyclopediaEntity>> = _encyclopedias.asStateFlow()

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
        viewModelScope.launch { _encyclopedias.value = encyclopediaDao.getAll() }
    }

    fun createNew() {
        viewModelScope.launch {
            encyclopediaDao.upsert(EncyclopediaEntity(name = "新百科库"))
            _encyclopedias.value = encyclopediaDao.getAll()
        }
    }

    suspend fun rename(id: Long, name: String): String? {
        if (name.isBlank()) return "请输入百科名称"
        return try {
            if (encyclopediaDao.updateName(id, name.trim(), System.currentTimeMillis()) == 0) "百科已不存在"
            else { _encyclopedias.value = encyclopediaDao.getAll(); null }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { "名称保存失败，请重试" }
    }

    suspend fun delete(id: Long): String? = try {
        val error = deleteWorld(id)
        if (error == null) _encyclopedias.value = encyclopediaDao.getAll()
        error
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        "世界删除失败，请重试"
    }

    fun setEncyclopediaPinned(id: Long, pinned: Boolean) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (encyclopediaDao.updatePinned(id, if (pinned) now else 0L, now) > 0) {
                _encyclopedias.value = encyclopediaDao.getAll()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            syncPublicLlmKeyFromStorage()
            _encyclopedias.value = encyclopediaDao.getAll()
        }
    }

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
            _encyclopedias.value = runCatching { encyclopediaDao.getAll() }.getOrElse {
                _encyclopedias.value.map { encyclopedia ->
                    if (encyclopedia.id == id) encyclopedia.copy(coverImagePath = path, updatedAt = now) else encyclopedia
                }
            }
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
        _encyclopedias.value = encyclopediaDao.getAll()
        return "封面已生成并保存"
    }

    suspend fun exportJson(): String {
        val encyclopedias = encyclopediaDao.getAll()
        val entriesById = encyclopedias.associate { encyclopedia ->
            encyclopedia.id to entryDao.getByEncyclopedia(encyclopedia.id)
        }
        return withContext(Dispatchers.Default) {
            EncyclopediaExportCodec.toJson(encyclopedias, entriesById)
        }
    }

    suspend fun importJson(text: String): String = try {
        val json = smartImportUseCase.parseToStructuredJson(text, "encyclopedia")
        val data = withContext(Dispatchers.Default) { EncyclopediaExportCodec.fromJson(json) }
        var encCount = 0
        var entryCount = 0
        data.forEach { exported ->
            val encyclopediaId = encyclopediaDao.upsert(
                EncyclopediaEntity(
                    name = exported.name,
                    description = exported.description,
                    coverImagePath = exported.coverImagePath,
                    genreTags = exported.genreTags,
                    worldPrompt = exported.worldPrompt,
                    gameplayMode = exported.gameplayMode,
                    antiCheatPrompt = exported.antiCheatPrompt,
                    narratorConfigJson = exported.narratorConfigJson,
                ),
            )
            encCount++
            exported.entries.forEach { entry ->
                saveCharacterEntry(
                    EncyclopediaEntryEntity(
                        encyclopediaId = encyclopediaId,
                        title = entry.title,
                        entryType = entry.entryType,
                        summary = entry.summary,
                        content = entry.content,
                        tags = entry.tags,
                        confidence = entry.confidence,
                        isFeatured = entry.isFeatured,
                        changeNote = entry.changeNote,
                        coverImagePath = entry.coverImagePath,
                        // 不导入旧库的 meta/source ID，避免把角色或会话绑定到当前库的同号记录。
                        metaJson = "{}",
                        sourceSessionId = null,
                        sourceMessageId = null,
                    ),
                )
                entryCount++
            }
        }
        _encyclopedias.value = encyclopediaDao.getAll()
        "导入 $encCount 个百科，共 $entryCount 个词条"
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        "导入异常: ${e.message}"
    }
}

package com.mojing.app.ui.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.dao.LegacyWorldMappingDao
import com.mojing.app.data.local.entity.WorldLoreEntryEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.data.remote.BackendWorldsApi
import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.data.repository.ImageRepository
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.usecase.SaveWorldTemplatePackageUseCase
import com.mojing.app.domain.usecase.PromoteWorldTemplateUseCase
import com.mojing.app.domain.usecase.SmartImportUseCase
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.ui.util.KeyedOperationOwner
import com.mojing.app.util.UsbSessionLog
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@HiltViewModel
class WorkbenchViewModel @Inject constructor(
    private val templateDao: WorldTemplateDao,
    private val legacyWorldMappingDao: LegacyWorldMappingDao,
    private val promoteWorldTemplate: PromoteWorldTemplateUseCase,
    private val smartImportUseCase: SmartImportUseCase,
    private val secureStorage: SecureStorage,
    private val backendWorldsApi: Lazy<BackendWorldsApi>,
    private val imageRepository: ImageRepository,
    private val llmRetry: LlmRetry,
    private val uiPreferencesRepository: UiPreferencesRepository,
    private val saveWorldTemplatePackage: SaveWorldTemplatePackageUseCase,
) : ViewModel() {
    private val coverGenerationOwner = KeyedOperationOwner<Long>()
    val coverGeneratingTemplateIds: StateFlow<Set<Long>> = coverGenerationOwner.activeKeys

    private val _templates = MutableStateFlow<List<WorldTemplateEntity>>(emptyList())
    val templates: StateFlow<List<WorldTemplateEntity>> = _templates.asStateFlow()
    val promotedTemplateIds: StateFlow<Map<Long, Long>> = legacyWorldMappingDao.observeAll().map { rows -> rows.associate { it.worldTemplateId to it.encyclopediaId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun promoteTemplate(id: Long, onResult: (Long?, String) -> Unit) {
        viewModelScope.launch {
            try {
                val encyclopedia = promoteWorldTemplate(id)
                onResult(encyclopedia.id, "已归入世界「${encyclopedia.name}」")
            } catch (e: Exception) {
                onResult(null, e.message ?: "归入世界失败，请重试")
            }
        }
    }

    suspend fun canonicalIdForTemplate(id: Long): Long? = legacyWorldMappingDao.getByTemplateId(id)?.encyclopediaId

    val workbenchListLayout = uiPreferencesRepository.workbenchListLayout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "list")

    fun toggleWorkbenchListLayout() {
        viewModelScope.launch {
            val next = if (workbenchListLayout.value == "grid") "list" else "grid"
            uiPreferencesRepository.setWorkbenchListLayout(next)
        }
    }

    private val _plainImportBusy = MutableStateFlow(false)
    val plainImportBusy: StateFlow<Boolean> = _plainImportBusy.asStateFlow()

    private val _generateBusy = MutableStateFlow(false)
    val generateBusy: StateFlow<Boolean> = _generateBusy.asStateFlow()
    private val _generateSaving = MutableStateFlow(false)
    val generateSaving: StateFlow<Boolean> = _generateSaving.asStateFlow()
    private val worldGenerationJob = AtomicReference<Job?>(null)

    init { viewModelScope.launch { _templates.value = templateDao.getAll() } }

    fun refresh() { viewModelScope.launch { _templates.value = templateDao.getAll() } }

    fun deleteTemplate(id: Long) {
        viewModelScope.launch {
            if (legacyWorldMappingDao.getByTemplateId(id) != null) return@launch
            templateDao.delete(id)
            _templates.value = templateDao.getAll()
        }
    }

    fun setTemplatePinned(id: Long, pinned: Boolean) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (templateDao.updatePinned(id, if (pinned) now else 0L, now) > 0) {
                _templates.value = templateDao.getAll()
            }
        }
    }

    /** 使用设置中的对话模型生成世界 JSON，并写入本地模板与设定条目。 */
    fun generateWorldRemote(
        worldType: String,
        coreTheme: String,
        tone: String,
        extra: String,
        label: String,
        onResult: (String) -> Unit,
    ) {
        if (!_generateBusy.compareAndSet(expect = false, update = true)) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (coreTheme.isBlank()) {
                    onResult("请填写核心主题")
                    return@launch
                }
                val apiKey = secureStorage.publicApiKey.trim()
                if (apiKey.isBlank()) {
                    onResult("请先在「设置」填写 API Key")
                    return@launch
                }
                val baseUrl = secureStorage.publicBaseUrl.trim()
                if (baseUrl.isBlank()) {
                    onResult("请先在「设置」填写对话服务根地址")
                    return@launch
                }
                val model = secureStorage.publicModel.trim().ifBlank { "deepseek-chat" }
                val userPrompt = buildString {
                    appendLine("请根据以下约束生成一个完整的世界观包，输出**仅包含一个 JSON 对象**，不要 Markdown 围栏。")
                    appendLine("世界类型: $worldType")
                    appendLine("核心主题: $coreTheme")
                    appendLine("基调: $tone")
                    if (extra.isNotBlank()) appendLine("额外要求: $extra")
                    if (label.isNotBlank()) appendLine("建议世界名称/标签: $label")
                    appendLine()
                    appendLine(WORLD_GEN_JSON_SCHEMA_HINT)
                }
                UsbSessionLog.i(
                    "WorkbenchGen",
                    "start worldType=${worldType.trim()} coreThemeLen=${coreTheme.length} toneLen=${tone.length} extraLen=${extra.length} labelLen=${label.length} prompt=${clipLog(userPrompt)}",
                )
                data class Outcome(val message: String, val templates: List<WorldTemplateEntity>?)
                val outcome = withContext(Dispatchers.IO) {
                    val raw = llmRetry.chatCompletionWithRetry(
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = model,
                        messages = listOf(
                            ChatMessage("system", WORLD_GEN_SYSTEM),
                            ChatMessage("user", userPrompt),
                        ),
                        temperature = 0.65f,
                        maxTokens = 8192,
                    )
                    UsbSessionLog.i(
                        "WorkbenchGen",
                        "rawLen=${raw.length} raw=${clipLog(raw)}",
                    )
                    val root = extractTopLevelJsonObject(raw)
                        ?: return@withContext Outcome("模型未返回可解析的 JSON，请缩短「详细要求」后重试", null)
                    if (!root.has("template") || !root.get("template").isJsonObject) {
                        return@withContext Outcome("返回 JSON 缺少 template 对象，请重试", null)
                    }
                    currentCoroutineContext().ensureActive()
                    _generateSaving.value = true
                    withContext(NonCancellable) {
                        val msg = persistWorldGenerationPackage(root)
                        val list = templateDao.getAll()
                        Outcome(msg, list)
                    }
                }
                currentCoroutineContext().ensureActive()
                if (outcome.templates != null) {
                    _templates.value = outcome.templates
                }
                onResult(outcome.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UsbSessionLog.e("WorkbenchGen", "failed", e)
                onResult("生成失败: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                val currentJob = currentCoroutineContext()[Job]
                if (worldGenerationJob.compareAndSet(currentJob, null)) {
                    _generateSaving.value = false
                    _generateBusy.value = false
                }
            }
        }
        worldGenerationJob.set(job)
        if (!job.start() && worldGenerationJob.compareAndSet(job, null)) {
            _generateBusy.value = false
        }
    }

    /** 取消当前世界生成；草稿由界面持有，因此取消不会清空草稿。 */
    fun cancelWorldGeneration(): Boolean {
        if (_generateSaving.value) return false
        val job = worldGenerationJob.get() ?: return false
        if (!job.isActive) return false
        job.cancel()
        return true
    }

    fun isCompanionBackendConfigured(): Boolean = backendWorldsApi.get().isCompanionBackendConfigured()

    /** 「智能生成」Tab 所需：与文本导入相同的直连 LLM 配置 */
    fun hasLlmForWorldGenerate(): Boolean =
        secureStorage.publicApiKey.isNotBlank() && secureStorage.publicBaseUrl.isNotBlank()

    private suspend fun persistWorldGenerationPackage(root: JsonObject): String {
        val t = root.getAsJsonObject("template") ?: return "响应缺少 template"
        val tid = jsonString(t.get("template_id")).trim().ifEmpty { UUID.randomUUID().toString().take(12) }
        val choicesEl = t.get("suggested_choices")
        val choicesJson = when {
            choicesEl != null && choicesEl.isJsonArray -> choicesEl.toString()
            choicesEl != null && choicesEl.isJsonPrimitive -> {
                val arr = JsonArray()
                arr.add(choicesEl.asJsonPrimitive.asString)
                arr.toString()
            }
            else -> "[]"
        }
        val now = System.currentTimeMillis()
        val entity = WorldTemplateEntity(
            templateId = tid,
            label = jsonString(t.get("label")).ifBlank { "未命名世界" },
            category = jsonString(t.get("category")),
            summary = jsonString(t.get("summary")),
            gameplayMode = jsonString(t.get("gameplay_mode")).ifBlank { "自由剧情" },
            worldPrompt = jsonString(t.get("world_prompt")),
            antiCheatPrompt = jsonString(t.get("anti_cheat_prompt")),
            coverImagePath = jsonString(t.get("cover_image_path")),
            suggestedChoicesJson = choicesJson,
            createdAt = now,
            updatedAt = now,
        )
        val loreArr = root.getAsJsonArray("lore_entries") ?: JsonArray()
        val loreEntries = mutableListOf<WorldLoreEntryEntity>()
        loreArr.forEachIndexed { idx, el ->
            if (!el.isJsonObject) return@forEachIndexed
            val lo = el.asJsonObject
            val kw = lo.get("keywords_json")
            val kwStr = when {
                kw != null && kw.isJsonArray -> kw.toString()
                kw != null && kw.isJsonPrimitive && kw.asJsonPrimitive.isString ->
                    runCatching { JsonParser.parseString(kw.asString).toString() }.getOrElse { "[]" }
                else -> "[]"
            }
            loreEntries += WorldLoreEntryEntity(
                worldTemplateId = 0L,
                title = jsonString(lo.get("title")).ifBlank { "Lore ${idx + 1}" },
                entryType = jsonString(lo.get("entry_type")).ifBlank { "设定" },
                keywordsJson = kwStr,
                content = jsonString(lo.get("content")),
                sortOrder = jsonInt(lo.get("sort_order"), idx),
                isCore = jsonBool(lo.get("is_core")),
            )
        }
        val saved = saveWorldTemplatePackage(entity, loreEntries)
        return "已写入本地「${saved.template.label}」（设定条目 ${saved.loreCount} 条）"
    }

    suspend fun exportJson(): String {
        val templatesSnapshot = _templates.value
        return withContext(Dispatchers.Default) {
            val data = templatesSnapshot.map { t ->
                mapOf(
                    "label" to t.label, "category" to t.category,
                    "summary" to t.summary, "gameplayMode" to t.gameplayMode,
                    "worldPrompt" to t.worldPrompt, "antiCheatPrompt" to t.antiCheatPrompt,
                    "suggestedChoicesJson" to t.suggestedChoicesJson,
                    "coverImagePath" to t.coverImagePath,
                )
            }
            Gson().toJson(mapOf("version" to 1, "type" to "templates", "data" to data))
        }
    }

    suspend fun importJson(text: String): String = try {
        applyImportedTemplateJson(text.trim())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        "导入异常: ${e.message}"
    }

    /**
     * 将非 JSON 的长文本经模型转为标准模板包 JSON 后导入（需先在设置填写联网 API Key）。
     */
    fun importPlainWorldText(text: String, onResult: (String) -> Unit) {
        if (!_plainImportBusy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                if (text.isBlank()) {
                    onResult("请先粘贴或输入世界观设定文本")
                    return@launch
                }
                if (secureStorage.publicApiKey.isBlank()) {
                    onResult("请先在「设置」填写联网 API Key，以便将长文本解析为模板")
                    return@launch
                }
                val trimmed = text.trim()
                val json = if (trimmed.startsWith("{") || trimmed.startsWith("[")) trimmed
                else smartImportUseCase.parseToStructuredJson(trimmed, "template")
                onResult(applyImportedTemplateJson(json))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult("导入异常: ${e.message}")
            } finally {
                _plainImportBusy.value = false
            }
        }
    }

    private suspend fun applyImportedTemplateJson(jsonText: String): String {
        val json = jsonText.trim()
        if (!json.startsWith("{") && !json.startsWith("[")) {
            return "解析失败或格式不正确"
        }
        val map = withContext(Dispatchers.Default) {
            Gson().fromJson(json, Map::class.java) as? Map<*, *>
        }
            ?: return "JSON格式不正确"
        val data = map["data"] as? List<*>
            ?: return "未找到模板数据"
        val importedTemplates = withContext(Dispatchers.Default) {
            data.mapNotNull { item ->
                val obj = item as? Map<*, *> ?: return@mapNotNull null
                val label = (obj["label"] as? String)?.trim().orEmpty()
                if (label.isEmpty()) return@mapNotNull null
                WorldTemplateEntity(
                    templateId = (obj["templateId"] as? String) ?: UUID.randomUUID().toString().take(8),
                    label = label,
                    category = (obj["category"] as? String) ?: "",
                    summary = (obj["summary"] as? String) ?: "",
                    gameplayMode = (obj["gameplayMode"] as? String) ?: "自由剧情",
                    worldPrompt = (obj["worldPrompt"] as? String) ?: "",
                    antiCheatPrompt = (obj["antiCheatPrompt"] as? String) ?: "",
                    coverImagePath = (obj["coverImagePath"] as? String) ?: "",
                    suggestedChoicesJson = (obj["suggestedChoicesJson"] as? String) ?: "[]",
                )
            }
        }
        var count = 0
        importedTemplates.forEach { template ->
            templateDao.upsert(template)
            count++
        }
        _templates.value = templateDao.getAll()
        return if (count > 0) "成功导入 $count 个模板" else "未解析出有效模板（需含 label 字段）"
    }

    private fun jsonString(e: JsonElement?): String =
        if (e != null && e.isJsonPrimitive) e.asJsonPrimitive.asString else ""

    private fun jsonInt(e: JsonElement?, default: Int): Int =
        if (e != null && e.isJsonPrimitive) {
            val p = e.asJsonPrimitive
            when {
                p.isNumber -> p.asInt
                p.isString -> p.asString.toIntOrNull() ?: default
                else -> default
            }
        } else {
            default
        }

    private fun jsonBool(e: JsonElement?): Boolean =
        if (e != null && e.isJsonPrimitive) {
            val p = e.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asInt != 0
                p.isString -> p.asString == "true" || p.asString == "1"
                else -> false
            }
        } else {
            false
        }

    /** 浏览器下载：仅含自定义模板的打包文件。 */
    fun backendExportBundleUrlCustomOnly(): String? =
        backendWorldsApi.get().worldTemplateBundleExportFileUrl(includeBuiltin = false)

    /** 浏览器下载：含内置模板的打包文件。 */
    fun backendExportBundleUrlWithBuiltin(): String? =
        backendWorldsApi.get().worldTemplateBundleExportFileUrl(includeBuiltin = true)

    /** 浏览器下载：单个模板文件。 */
    fun backendExportSingleTemplateUrl(templateIdStr: String): String? =
        backendWorldsApi.get().worldTemplateExportFileUrl(templateIdStr)

    suspend fun updateTemplateCover(id: Long, localPath: String): String = withContext(NonCancellable) {
        if (legacyWorldMappingDao.getByTemplateId(id) != null) {
            runCatching { File(localPath).delete() }
            return@withContext "已归入世界，封面请在世界百科中维护"
        }
        val path = localPath.trim()
        if (path.isBlank()) return@withContext UserFacingStrings.localSaveFailed("封面")
        try {
            val now = System.currentTimeMillis()
            if (templateDao.updateCover(id, path, now) <= 0) {
                runCatching { File(path).delete() }
                return@withContext if (templateDao.getById(id) == null) {
                    "模板已删除，封面未保存"
                } else {
                    UserFacingStrings.localSaveFailed("封面")
                }
            }
            _templates.value = runCatching { templateDao.getAll() }.getOrElse {
                _templates.value.map { template ->
                    if (template.id == id) template.copy(coverImagePath = path, updatedAt = now) else template
                }
            }
            "已更新封面"
        } catch (_: Exception) {
            runCatching { File(path).delete() }
            UserFacingStrings.localSaveFailed("封面")
        }
    }

    /** 与百科条目封面一致：直连配图 API，裁切后写入 `coverImagePath`。 */
    fun generateTemplateCoverAi(id: Long, onResult: (String) -> Unit): Boolean {
        if (!coverGenerationOwner.tryStart(id)) return false
        viewModelScope.launch {
            try {
                onResult(generateTemplateCoverAiInternal(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(UserFacingStrings.remoteRequestFailed(e))
            } finally {
                coverGenerationOwner.finish(id)
            }
        }
        return true
    }

    private suspend fun generateTemplateCoverAiInternal(id: Long): String = withContext(Dispatchers.IO) {
        val t = try {
            templateDao.getById(id)
        } catch (_: Exception) {
            return@withContext UserFacingStrings.localLoadFailed("模板")
        } ?: return@withContext "未找到模板"
        val title = t.label.ifBlank { t.templateId }
        if (title.isBlank()) return@withContext "请先填写模板名称"
        val base = secureStorage.imageBaseUrl.trim().ifBlank { secureStorage.publicBaseUrl.trim() }
        val key = secureStorage.imageApiKey.trim().ifBlank { secureStorage.publicApiKey.trim() }
        val model = secureStorage.imageModel.trim().ifBlank { "dall-e-3" }
        if (base.isBlank() || key.isBlank()) {
            return@withContext UserFacingStrings.imageGenKeyMissing()
        }
        val worldSummary = buildString {
            if (t.summary.isNotBlank()) append(t.summary.trim())
            if (t.worldPrompt.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(t.worldPrompt.trim())
            }
        }.toString().take(4000).ifBlank { t.category }
        val prompt = buildString {
            append("竖版书籍封面风格插画，约 2:3，无文字无水印。世界：")
            append(title.take(120))
            append("。类型：")
            append("${t.category} ${t.gameplayMode}".trim().ifBlank { "世界模板" })
            append("。内容要点：")
            append(worldSummary.take(1800))
        }
        return@withContext try {
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
                    "图片下载失败", "图片裁切保存失败" -> UserFacingStrings.entryCoverSaveFailed()
                    else -> UserFacingStrings.remoteRequestFailed(error)
                }
            }
            persistTemplateCover(id, t.coverImagePath, finalPath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UserFacingStrings.remoteRequestFailed(e)
        }
    }

    private suspend fun persistTemplateCover(id: Long, expectedPath: String, finalPath: String): String {
        val rejectedMessage = withContext(NonCancellable) {
            try {
                val updated = templateDao.updateCoverIfUnchanged(
                    id = id,
                    expectedPath = expectedPath,
                    newPath = finalPath,
                    updatedAt = System.currentTimeMillis(),
                )
                if (updated > 0) return@withContext null
                runCatching { File(finalPath).delete() }
                if (templateDao.getById(id) == null) {
                    "模板已删除，未保存封面"
                } else {
                    "封面已在生成期间更新，未覆盖当前封面"
                }
            } catch (_: Exception) {
                runCatching { File(finalPath).delete() }
                UserFacingStrings.generatedCoverLocalSaveFailed()
            }
        }
        if (rejectedMessage != null) return rejectedMessage
        _templates.value = templateDao.getAll()
        return "封面已生成并保存"
    }

    companion object {
        private const val WORLD_GEN_SYSTEM =
            "你是世界观与 TRPG 设定设计师。必须只输出合法 JSON（UTF-8），不要代码围栏、不要解释性前后文。"

        private val WORLD_GEN_JSON_SCHEMA_HINT = """
顶层结构必须为：
{
  "template": {
    "template_id": "短英文或数字 id",
    "label": "世界显示名",
    "category": "题材分类",
    "summary": "两三句概述",
    "gameplay_mode": "自由剧情 或 其他",
    "world_prompt": "给模型用的长世界书主提示，可含规则与氛围",
    "anti_cheat_prompt": "防越权/保持设定的短提示",
    "cover_image_path": "",
    "suggested_choices": ["开场选项1","开场选项2"]
  },
  "lore_entries": [
    {
      "title": "设定条目标题",
      "entry_type": "设定",
      "keywords_json": [],
      "content": "正文",
      "sort_order": 0,
      "is_core": true
    }
  ]
}
keywords_json 必须是 JSON 数组（可为字符串数组或空数组）。至少 3 条 lore_entries，内容具体可玩。""".trimIndent()

        private fun extractTopLevelJsonObject(text: String): JsonObject? {
            val t = text.trim()
            val start = t.indexOf('{')
            val end = t.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { JsonParser.parseString(t.substring(start, end + 1)).asJsonObject }.getOrNull()
        }

        private fun clipLog(text: String, limit: Int = 400): String {
            val t = text.trim()
            return if (t.length <= limit) t else t.take(limit) + "…len=" + t.length
        }
    }
}

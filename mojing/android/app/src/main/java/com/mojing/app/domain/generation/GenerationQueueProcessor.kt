package com.mojing.app.domain.generation

import com.mojing.app.data.SecureStorage
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.dao.GenerationTaskDao
import com.mojing.app.data.local.dao.TimelineEventDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.entity.TimelineEventEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskKinds
import com.mojing.app.data.local.entity.GenerationTaskStatus
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.encyclopedia.EncyclopediaBatchReferenceComposer
import com.mojing.app.domain.encyclopedia.EncyclopediaEntryMetaMerge
import com.mojing.app.domain.engine.AiCompleter
import com.mojing.app.domain.engine.BatchGenerator
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import com.mojing.app.ui.encyclopedia.meta.EncyclopediaMetaDefinitions
import com.mojing.app.util.ApiRootLines
import com.mojing.app.util.UsbSessionLog
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class GenerationQueueProcessor @Inject constructor(
    private val taskDao: GenerationTaskDao,
    private val entryDao: EncyclopediaEntryDao,
    private val encyclopediaDao: EncyclopediaDao,
    private val characterDao: CharacterDao,
    private val worldTemplateDao: WorldTemplateDao,
    private val timelineEventDao: TimelineEventDao,
    private val batchGenerator: BatchGenerator,
    private val aiCompleter: AiCompleter,
    private val secureStorage: SecureStorage,
    private val llmApiService: LlmApiService,
    private val saveCharacterBinding: SaveCharacterBindingUseCase,
    private val saveCharacterEntry: SaveCharacterEntryUseCase,
    private val database: com.mojing.app.data.local.AppDatabase,
) {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queuePaused = AtomicBoolean(secureStorage.generationQueuePaused)
    private val pauseMutex = Mutex()
    private val _pausedState = kotlinx.coroutines.flow.MutableStateFlow(queuePaused.get())
    val pausedState: kotlinx.coroutines.flow.StateFlow<Boolean> = _pausedState

    init {
        scope.launch { runLoop() }
    }

    fun observeActiveCount() = taskDao.observeActiveCount()

    fun observeActiveForEncyclopedia(encId: Long) = taskDao.observeActiveForEncyclopedia(encId)

    fun observeActiveForCharacter(characterId: Long) = taskDao.observeActiveForCharacter(characterId)

    suspend fun getLatestPersonaTaskForCharacter(characterId: Long): GenerationTaskEntity? =
        taskDao.getLatestPersonaTaskForCharacter(characterId)

    fun observeActiveForTemplate(templateRowId: Long) = taskDao.observeActiveForTemplate(templateRowId)

    fun isQueuePaused(): Boolean = queuePaused.get()

    suspend fun countActiveTasks(): Int = taskDao.countActive()

    suspend fun cancelTask(id: Long): Boolean =
        taskDao.cancelTask(id, now()) > 0

    suspend fun hasActivePersonaForCharacter(characterId: Long): Boolean =
        characterId > 0L && taskDao.countActivePersonaForCharacter(characterId) > 0

    /** 失败任务保留已保存进度，原记录重新排队。 */
    suspend fun requeueFailedTask(task: GenerationTaskEntity): Boolean {
        if (task.status != GenerationTaskStatus.FAILED) return false
        val total = resolveRetryTotal(task) ?: return false
        return taskDao.requeueFailed(task.id, total, now()) > 0
    }

    /** 供 UI 展示重新排队后的总数（始终为 payload 原始 count，非剩余条数）。 */
    fun resolveRetryTotalForUi(task: GenerationTaskEntity): Int =
        resolveRetryTotal(task) ?: task.progressTotal.coerceAtLeast(1)

    private fun resolveRetryTotal(task: GenerationTaskEntity): Int? = when (task.taskKind) {
        GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES -> {
            runCatching { gson.fromJson(task.payloadJson, EncyclopediaBatchPayload::class.java).count }
                .getOrNull()?.coerceAtLeast(1)
        }
        GenerationTaskKinds.ENCYCLOPEDIA_META_FILL -> {
            runCatching { gson.fromJson(task.payloadJson, EncyclopediaMetaBatchPayload::class.java).entryIds.size }
                .getOrNull()?.coerceAtLeast(1)
        }
        GenerationTaskKinds.CHARACTER_PERSONA_AI,
        GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI -> 1
        else -> null
    }

    suspend fun pauseAll() = pauseMutex.withLock {
        withContext(Dispatchers.IO) { secureStorage.generationQueuePaused = true }
        queuePaused.set(true)
        _pausedState.value = true
    }

    suspend fun resumeAll() = pauseMutex.withLock {
        taskDao.resumePausedToQueued(now())
        withContext(Dispatchers.IO) { secureStorage.generationQueuePaused = false }
        queuePaused.set(false)
        _pausedState.value = false
    }

    /** Finish the current saved step before pausing; resuming never replays saved work. */
    private suspend fun pauseAtCheckpoint(): Boolean = pauseMutex.withLock {
        if (!queuePaused.get()) return@withLock false
        taskDao.pauseRunningTasks(now())
        true
    }

    private suspend fun commitStep(taskId: Long, done: Int, total: Int, save: suspend () -> Unit): Boolean =
        database.withTransaction {
            if (taskDao.getById(taskId)?.status != GenerationTaskStatus.RUNNING) return@withTransaction false
            save()
            taskDao.updateProgress(taskId, done, total, now())
            true
        }

    suspend fun enqueueEncyclopediaBatch(
        encyclopediaId: Long,
        encyclopediaName: String,
        worldBackground: String,
        entryType: String,
        count: Int,
        minWords: Int,
        maxWords: Int,
        userContext: String,
        outputMode: String? = null,
        preGenNotes: String? = null,
    ): Long {
        val mw = minWords.coerceIn(50, 5000)
        val xw = maxWords.coerceIn(mw, 8000)
        val modeNorm = (outputMode ?: "entries").trim().lowercase()
        val payload = EncyclopediaBatchPayload(
            encyclopediaId = encyclopediaId,
            encyclopediaName = encyclopediaName,
            worldBackground = worldBackground,
            entryType = entryType,
            count = count.coerceIn(1, 200),
            minWords = mw,
            maxWords = xw,
            userContext = userContext,
            preGenNotes = preGenNotes?.trim()?.takeIf { it.isNotBlank() },
            outputMode = modeNorm,
        )
        val titleTag = if (modeNorm == "timeline") "时间线" else entryType
        val title = "百科「$encyclopediaName」·${titleTag}×${payload.count}"
        val t = now()
        val row = GenerationTaskEntity(
            taskKind = GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES,
            title = title,
            status = GenerationTaskStatus.QUEUED,
            progressDone = 0,
            progressTotal = payload.count,
            payloadJson = gson.toJson(payload),
            targetEncyclopediaId = encyclopediaId,
            targetCharacterId = null,
            targetWorldTemplateId = null,
            createdAt = t,
            updatedAt = t,
        )
        return taskDao.insert(row)
    }

    suspend fun enqueueEncyclopediaMetaFill(encyclopediaId: Long, encyclopediaName: String, entryIds: List<Long>): Long {
        val ids = entryIds.distinct().filter { it > 0 }
        val payload = EncyclopediaMetaBatchPayload(encyclopediaId = encyclopediaId, entryIds = ids)
        val title = "百科「${encyclopediaName.ifBlank { "百科" }}」·扩展字段×${ids.size}"
        val t = now()
        val row = GenerationTaskEntity(
            taskKind = GenerationTaskKinds.ENCYCLOPEDIA_META_FILL,
            title = title,
            status = GenerationTaskStatus.QUEUED,
            progressDone = 0,
            progressTotal = ids.size.coerceAtLeast(1),
            payloadJson = gson.toJson(payload),
            targetEncyclopediaId = encyclopediaId,
            targetCharacterId = null,
            targetWorldTemplateId = null,
            createdAt = t,
            updatedAt = t,
        )
        return taskDao.insert(row)
    }

    suspend fun enqueueCharacterPersonaAi(payload: CharacterPersonaAiPayload): Long {
        val title = "角色「${payload.name.ifBlank { "未命名" }}」·AI 人设"
        val t = now()
        val row = GenerationTaskEntity(
            taskKind = GenerationTaskKinds.CHARACTER_PERSONA_AI,
            title = title,
            status = GenerationTaskStatus.QUEUED,
            progressDone = 0,
            progressTotal = 1,
            payloadJson = gson.toJson(payload),
            targetEncyclopediaId = payload.contextEncyclopediaId?.takeIf { it > 0L },
            targetCharacterId = payload.characterId,
            targetWorldTemplateId = null,
            createdAt = t,
            updatedAt = t,
        )
        return taskDao.insert(row)
    }

    suspend fun enqueueWorldTemplatePromptAi(payload: WorldTemplatePromptAiPayload): Long {
        val title = "模板「${payload.label.ifBlank { "世界观" }}」·AI 世界书"
        val t = now()
        val row = GenerationTaskEntity(
            taskKind = GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI,
            title = title,
            status = GenerationTaskStatus.QUEUED,
            progressDone = 0,
            progressTotal = 1,
            payloadJson = gson.toJson(payload),
            targetEncyclopediaId = payload.contextEncyclopediaId?.takeIf { it > 0L },
            targetCharacterId = null,
            targetWorldTemplateId = payload.templateRowId,
            createdAt = t,
            updatedAt = t,
        )
        return taskDao.insert(row)
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            try {
                taskDao.reclaimStaleRunning(
                    now() - STALE_RUNNING_MS,
                    "任务超时（运行过久已自动结束，请重试）",
                    now(),
                )
                if (queuePaused.get()) {
                    delay(350)
                    continue
                }
                val next = taskDao.peekNextQueued()
                if (next == null) {
                    delay(450)
                    continue
                }
                val claimed = taskDao.claimIfQueued(next.id, now())
                if (claimed == 0) continue
                val tid = next.id
                try {
                    when (next.taskKind) {
                        GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES -> runEncyclopediaTask(tid)
                        GenerationTaskKinds.ENCYCLOPEDIA_META_FILL -> runEncyclopediaMetaFillTask(tid)
                        GenerationTaskKinds.CHARACTER_PERSONA_AI -> runCharacterPersonaTask(tid)
                        GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI -> runWorldTemplatePromptTask(tid)
                        else -> taskDao.setTerminal(
                            tid,
                            GenerationTaskStatus.FAILED,
                            "未知任务类型: ${next.taskKind}",
                            now(),
                        )
                    }
                } catch (e: Exception) {
                    UsbSessionLog.e("GenQueue", "task $tid", e)
                    val cur = taskDao.getById(tid)
                    if (cur?.status == GenerationTaskStatus.RUNNING) {
                        taskDao.setTerminal(
                            tid,
                            GenerationTaskStatus.FAILED,
                            (e.message ?: "任务异常中断").take(500),
                            now(),
                        )
                    }
                }
            } catch (e: Exception) {
                UsbSessionLog.e("GenQueue", "runLoop", e)
                delay(800)
            }
        }
    }

    private suspend fun runEncyclopediaTask(taskId: Long) {
        val task = taskDao.getById(taskId) ?: return
        val payload = runCatching { gson.fromJson(task.payloadJson, EncyclopediaBatchPayload::class.java) }
            .getOrNull()
        if (payload == null) {
            taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, "任务数据损坏", now())
            return
        }
        val apiKey = secureStorage.publicApiKey.trim()
        val bases = publicChatBases()
        val model = secureStorage.publicModel.trim()
        if (apiKey.isEmpty()) {
            taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, "未配置 API Key", now())
            return
        }
        val mode = (payload.outputMode ?: "entries").trim().lowercase()
        if (mode == "timeline") {
            runEncyclopediaTimelineGenerationTask(taskId, payload, apiKey, bases, model)
            return
        }
        val total = payload.count
        var done = task.progressDone.coerceIn(0, total)
        taskDao.updateProgress(taskId, done, total, now())
        while (done < total) {
            val cur = taskDao.getById(taskId) ?: return
            if (cur.status == GenerationTaskStatus.CANCELLED) return
            if (cur.status == GenerationTaskStatus.PAUSED || pauseAtCheckpoint()) return
            done = cur.progressDone.coerceIn(0, total)
            val chunk = batchChunkSize(total, done, payload.maxWords)
            val referenceBlock = EncyclopediaBatchReferenceComposer.buildReferenceBlock(
                entryDao = entryDao,
                encyclopediaId = payload.encyclopediaId,
                targetEntryType = payload.entryType,
                userContext = combinedUserContextForBatch(payload),
            )
            val baseWorld = buildWorldPrompt(payload)
            val worldBlock = when {
                referenceBlock.isNotEmpty() && baseWorld.isNotEmpty() -> "$baseWorld\n\n$referenceBlock"
                referenceBlock.isNotEmpty() -> referenceBlock
                else -> baseWorld
            }
            val items = withContext(Dispatchers.IO) {
                fetchBatchItemsWithRetry(
                    apiKey = apiKey,
                    bases = bases,
                    model = model,
                    payload = payload,
                    chunk = chunk,
                    worldBlock = worldBlock,
                )
            }
            if (items.isEmpty()) {
                taskDao.setTerminal(
                    taskId,
                    GenerationTaskStatus.FAILED,
                    "模型返回空（$done/$total），请检查网络、模型或调小单次条数",
                    now(),
                )
                return
            }
            var progressed = false
            var ts = System.currentTimeMillis()
            for (item in items) {
                if (taskDao.getById(taskId)?.status == GenerationTaskStatus.CANCELLED) return
                if (done >= total) break
                val titleRaw = (item["title"] as? String) ?: (item["name"] as? String)
                val title = titleRaw?.trim().orEmpty().ifBlank { "条目 ${done + 1}" }
                val summary = (item["summary"] as? String)?.trim().orEmpty()
                val content = (item["content"] as? String)?.trim().orEmpty()
                val tags = when (val tg = item["tags"]) {
                    is String -> tg.trim()
                    is List<*> -> tg.joinToString(",") { it?.toString()?.trim().orEmpty() }
                    else -> ""
                }
                ts += 1L
                if (!commitStep(taskId, done + 1, total) {
                    saveCharacterEntry(
                        EncyclopediaEntryEntity(
                            encyclopediaId = payload.encyclopediaId,
                            title = title,
                            entryType = payload.entryType,
                            summary = summary,
                            content = content,
                            tags = tags,
                            createdAt = ts,
                            updatedAt = ts,
                        ),
                    )
                }) return
                done++
                progressed = true
            }
            if (!progressed) {
                taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, "模型返回的条目无法解析", now())
                return
            }
        }
        val end = taskDao.getById(taskId) ?: return
        if (end.status == GenerationTaskStatus.CANCELLED) return
        taskDao.setTerminal(taskId, GenerationTaskStatus.COMPLETED, "", now())
    }

    private suspend fun runEncyclopediaTimelineGenerationTask(
        taskId: Long,
        payload: EncyclopediaBatchPayload,
        apiKey: String,
        bases: List<String>,
        model: String,
    ) {
        val task = taskDao.getById(taskId) ?: return
        val total = payload.count
        var done = task.progressDone.coerceIn(0, total)
        taskDao.updateProgress(taskId, done, total, now())
        var nextAutoSort = (timelineEventDao.getByEncyclopedia(payload.encyclopediaId).maxOfOrNull { it.sortOrder } ?: -1) + 1
        while (done < total) {
            val cur = taskDao.getById(taskId) ?: return
            if (cur.status == GenerationTaskStatus.CANCELLED) return
            if (cur.status == GenerationTaskStatus.PAUSED || pauseAtCheckpoint()) return
            val chunk = min(3, total - done)
            val referenceBlock = EncyclopediaBatchReferenceComposer.buildReferenceBlock(
                entryDao = entryDao,
                encyclopediaId = payload.encyclopediaId,
                targetEntryType = "event",
                userContext = combinedUserContextForBatch(payload),
            )
            val baseWorld = buildWorldPrompt(payload)
            val worldBlock = when {
                referenceBlock.isNotEmpty() && baseWorld.isNotEmpty() -> "$baseWorld\n\n$referenceBlock"
                referenceBlock.isNotEmpty() -> referenceBlock
                else -> baseWorld
            }
            val items = withContext(Dispatchers.IO) {
                var picked: List<Map<String, Any>> = emptyList()
                for ((idx, base) in bases.withIndex()) {
                    val got = runCatching {
                        batchGenerator.generateTimelineEventsBatch(
                            apiKey = apiKey,
                            baseUrl = base,
                            model = model,
                            encyclopediaId = payload.encyclopediaId,
                            count = chunk,
                            worldPrompt = worldBlock,
                            minWords = payload.minWords,
                            maxWords = payload.maxWords,
                            extraUserContext = combinedUserContextForBatch(payload),
                        )
                    }.getOrElse { emptyList() }
                    if (got.isNotEmpty()) {
                        if (idx > 0) promotePublicBaseIfNeeded(base)
                        picked = got
                        break
                    }
                }
                picked
            }
            if (items.isEmpty()) {
                taskDao.setTerminal(
                    taskId,
                    GenerationTaskStatus.FAILED,
                    "时间线生成返回空（$done/$total），请检查网络与模型",
                    now(),
                )
                return
            }
            var progressed = false
            for (item in items) {
                if (taskDao.getById(taskId)?.status == GenerationTaskStatus.CANCELLED) return
                if (done >= total) break
                val titleRaw = (item["title"] as? String) ?: (item["name"] as? String)
                val title = titleRaw?.trim().orEmpty().ifBlank { "事件 ${done + 1}" }
                val description = (item["description"] as? String)?.trim().orEmpty()
                    .ifBlank { (item["content"] as? String)?.trim().orEmpty() }
                val eventTime = (item["eventTime"] as? String)?.trim().orEmpty()
                    .ifBlank { (item["time"] as? String)?.trim().orEmpty() }
                    .ifBlank { "未标注时间" }
                val explicitOrder = (item["sortOrder"] as? Number)?.toInt()
                val sortOrder = explicitOrder ?: nextAutoSort++
                val ts = System.currentTimeMillis()
                if (!commitStep(taskId, done + 1, total) {
                    timelineEventDao.upsert(
                        TimelineEventEntity(
                            encyclopediaId = payload.encyclopediaId,
                            entryId = null,
                            title = title.take(500),
                            description = description,
                            eventTime = eventTime.take(400),
                            sortOrder = sortOrder,
                            createdAt = ts,
                        ),
                    )
                }) return
                done++
                progressed = true
            }
            if (!progressed) {
                taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, "时间线模型输出无法解析", now())
                return
            }
        }
        val end = taskDao.getById(taskId) ?: return
        if (end.status == GenerationTaskStatus.CANCELLED) return
        taskDao.setTerminal(taskId, GenerationTaskStatus.COMPLETED, "", now())
    }

    private suspend fun runEncyclopediaMetaFillTask(taskId: Long) {
        val task = taskDao.getById(taskId) ?: return
        val payload = runCatching { gson.fromJson(task.payloadJson, EncyclopediaMetaBatchPayload::class.java) }
            .getOrNull()
        if (payload == null || payload.entryIds.isEmpty()) {
            taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, "任务数据损坏或条目列表为空", now())
            return
        }
        val apiKey = secureStorage.publicApiKey.trim()
        val bases = publicChatBases()
        val model = secureStorage.publicModel.trim()
        if (apiKey.isEmpty()) {
            taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, "未配置 API Key", now())
            return
        }
        val enc = encyclopediaDao.getById(payload.encyclopediaId)
        val worldHint =
            "${enc?.name.orEmpty()} ${enc?.description.orEmpty()} ${enc?.genreTags.orEmpty()} ${enc?.worldPrompt.orEmpty()}"
                .trim().take(8000)
        val total = payload.entryIds.size
        var done = task.progressDone.coerceIn(0, total)
        taskDao.updateProgress(taskId, done, total, now())
        var touchedRows = 0
        val allowedMetaKeys = mutableSetOf<String>()
        for (entryId in payload.entryIds.drop(done)) {
            val cur = taskDao.getById(taskId) ?: return
            if (cur.status == GenerationTaskStatus.CANCELLED) return
            if (cur.status == GenerationTaskStatus.PAUSED || pauseAtCheckpoint()) return
            val latest = entryDao.getById(entryId)
            if (latest == null || latest.encyclopediaId != payload.encyclopediaId) {
                if (!commitStep(taskId, done + 1, total) {}) return
                done++
                continue
            }
            allowedMetaKeys.clear()
            allowedMetaKeys.addAll(EncyclopediaMetaDefinitions.fieldsFor(latest.entryType).map { it.key })
            try {
                val metaObj = EncyclopediaEntryMetaMerge.parseMetaJson(latest.metaJson)
                val result = withContext(Dispatchers.IO) {
                    aiCompleteAcrossBases(
                        apiKey = apiKey,
                        model = model,
                        bases = bases,
                        request = AiCompleter.CompleteRequest(
                            targetType = "encyclopedia_entry_meta",
                            entryType = latest.entryType,
                            currentData = mapOf(
                                "title" to latest.title,
                                "summary" to latest.summary,
                                "tags" to latest.tags,
                                "content_preview" to latest.content.take(4000),
                                "existing_meta_json" to metaObj.toString(),
                            ),
                            extraContext = worldHint,
                        ),
                        promotePublic = true,
                    )
                }
                if (result.isNotEmpty()) {
                    val metaPatch = result.filterKeys { it in allowedMetaKeys }
                    val metaChanged = EncyclopediaEntryMetaMerge.mergeMetaPatch(metaObj, metaPatch)
                    var next: EncyclopediaEntryEntity = latest
                    var rowChanged = metaChanged
                    (result["title"] as? String)?.trim()?.takeIf { it.isNotBlank() && it != latest.title }?.let {
                        next = next.copy(title = it)
                        rowChanged = true
                    }
                    (result["summary"] as? String)?.trim()?.takeIf { it.isNotBlank() && it != latest.summary }?.let {
                        next = next.copy(summary = it)
                        rowChanged = true
                    }
                    (result["tags"] as? String)?.trim()?.takeIf { it != latest.tags }?.let {
                        next = next.copy(tags = it)
                        rowChanged = true
                    }
                    (result["content"] as? String)?.trim()?.takeIf { it.isNotBlank() && it != latest.content }?.let {
                        next = next.copy(content = it)
                        rowChanged = true
                    }
                    if (rowChanged) {
                        val nowTs = System.currentTimeMillis()
                        if (!commitStep(taskId, done + 1, total) {
                            saveCharacterEntry(next.copy(metaJson = metaObj.toString(), updatedAt = nowTs))
                        }) return
                        touchedRows++
                    }
                }
                check(result.isNotEmpty()) { "模型没有返回可用内容" }
            } catch (_: Exception) {
                taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, "当前条目未完成，可从已保存进度继续尝试", now())
                return
            }
            if (!commitStep(taskId, done + 1, total) {}) return
            done++
        }
        val end = taskDao.getById(taskId) ?: return
        if (end.status == GenerationTaskStatus.CANCELLED) return
        val hint =
            if (touchedRows == 0) {
                "提示：未写入任何新字段（模型可能未返回 JSON，或扩展 meta/标题等已较满）"
            } else {
                ""
            }
        taskDao.setTerminal(taskId, GenerationTaskStatus.COMPLETED, hint, now())
    }

    private suspend fun runCharacterPersonaTask(taskId: Long) {
        val task = taskDao.getById(taskId) ?: return
        val payload = runCatching { gson.fromJson(task.payloadJson, CharacterPersonaAiPayload::class.java) }
            .getOrNull()
        if (payload == null) {
            taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, "任务数据损坏", now())
            return
        }
        val apiKey = payload.apiKey.ifBlank { secureStorage.publicApiKey }.trim()
        val baseRaw = payload.baseUrl.ifBlank { secureStorage.publicBaseUrl }.trim()
        val bases = publicChatBases(baseRaw)
        val model = payload.model.ifBlank { secureStorage.publicModel }.trim()
        val promotePublic = payload.baseUrl.isBlank()
        if (apiKey.isEmpty()) {
            taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, "未配置 API Key", now())
            return
        }
        taskDao.updateProgress(taskId, 0, 1, now())
        try {
            val encId = payload.contextEncyclopediaId?.takeIf { it > 0L } ?: 0L
            val digestParts = mutableListOf<String>()
            digestParts.add("创建一个有深度的角色人设")
            if (encId > 0L) {
                val block = EncyclopediaBatchReferenceComposer.buildReferenceBlock(
                    entryDao = entryDao,
                    encyclopediaId = encId,
                    targetEntryType = "character",
                    userContext = "${payload.name} ${payload.personaPrompt}",
                    digestTokenBudget = 780,
                )
                if (block.isNotEmpty()) {
                    digestParts.add("【绑定百科设定参考（节选）】\n$block")
                }
            }
            val mergedExtra = digestParts.joinToString("\n\n")
            val request = AiCompleter.CompleteRequest(
                targetType = "character",
                currentData = mapOf(
                    "name" to payload.name,
                    "persona_prompt" to payload.personaPrompt,
                ),
                extraContext = mergedExtra,
            )
            var result: Map<String, Any> = emptyMap()
            var completedCall = false
            var lastErr: Exception? = null
            for ((idx, base) in bases.withIndex()) {
                try {
                    result = withContext(Dispatchers.IO) {
                        withTimeout(PERSONA_AI_TIMEOUT_MS) {
                            aiCompleter.complete(apiKey, base, model, request)
                        }
                    }
                    if (promotePublic && idx > 0) promotePublicBaseIfNeeded(base)
                    completedCall = true
                    break
                } catch (e: TimeoutCancellationException) {
                    throw Exception("请求超时，请检查网络或稍后重试")
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            if (!completedCall) throw lastErr ?: Exception("人设生成失败")
            val newPrompt = (result["persona_prompt"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                ?: (result["personaPrompt"] as? String)?.trim().orEmpty()
            if (newPrompt.isBlank() || newPrompt == payload.personaPrompt) {
                taskDao.setTerminal(taskId, GenerationTaskStatus.COMPLETED, "人设未变化", now())
                return
            }
            if (taskDao.getById(taskId)?.status == GenerationTaskStatus.CANCELLED) return
            val entity = characterDao.getById(payload.characterId)
            if (entity == null) {
                taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, "角色已删除", now())
                return
            }
            var applied = false
            if (!commitStep(taskId, 1, 1) {
                val latest = characterDao.getById(payload.characterId)
                if (latest != null && latest.personaPrompt == payload.personaPrompt) {
                    saveCharacterBinding(latest.copy(personaPrompt = newPrompt, updatedAt = System.currentTimeMillis()))
                    applied = true
                }
            }) return
            if (!applied) {
                taskDao.setTerminal(taskId, GenerationTaskStatus.COMPLETED, "角色已在生成期间编辑或删除，未覆盖当前内容", now())
                return
            }
            taskDao.setTerminal(taskId, GenerationTaskStatus.COMPLETED, "", now())
        } catch (e: Exception) {
            val raw = e.message?.trim().orEmpty().ifBlank { "人设生成失败" }
            // 仅匹配常见「模型 id 不存在」英文/错误码，避免把含 "model" 与 "not exist" 的其它 400 误当成模型不匹配
            val modelMissing = raw.contains("Model does not exist", ignoreCase = true) ||
                raw.contains("model_not_found", ignoreCase = true) ||
                raw.contains("model_not_available", ignoreCase = true) ||
                raw.contains("\"code\":\"model_not_found\"", ignoreCase = true)
            val msg = if (modelMissing) {
                "$raw（若使用设置里的公共网关，请确认队列任务用的是「设置-公共模型」中的模型 id。）"
            } else {
                raw
            }
            taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, msg, now())
        }
    }

    private suspend fun runWorldTemplatePromptTask(taskId: Long) {
        val task = taskDao.getById(taskId) ?: return
        val payload = runCatching { gson.fromJson(task.payloadJson, WorldTemplatePromptAiPayload::class.java) }
            .getOrNull()
        if (payload == null) {
            taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, "任务数据损坏", now())
            return
        }
        val apiKey = secureStorage.publicApiKey.trim()
        val bases = publicChatBases()
        val model = secureStorage.publicModel.trim()
        if (apiKey.isEmpty()) {
            taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, "未配置 API Key", now())
            return
        }
        taskDao.updateProgress(taskId, 0, 1, now())
        try {
            val baseExtra = payload.extraContext.ifBlank { "${payload.category}风格的${payload.label}世界设定" }
            val encId = payload.contextEncyclopediaId?.takeIf { it > 0L } ?: 0L
            UsbSessionLog.i(
                "GenWorldTemplate",
                "start taskId=$taskId templateRowId=${payload.templateRowId} labelLen=${payload.label.length} categoryLen=${payload.category.length} summaryLen=${payload.summary.length} worldPromptLen=${payload.worldPrompt.length} encId=$encId baseExtraLen=${baseExtra.length}",
            )
            val extraMerged = if (encId > 0L) {
                val block = EncyclopediaBatchReferenceComposer.buildReferenceBlock(
                    entryDao = entryDao,
                    encyclopediaId = encId,
                    targetEntryType = "concept",
                    userContext = "${payload.label} ${payload.category} ${payload.summary} $baseExtra",
                    digestTokenBudget = 900,
                )
                if (block.isNotEmpty()) "$baseExtra\n\n【绑定百科设定参考（节选）】\n$block" else baseExtra
            } else {
                baseExtra
            }
            val result = withContext(Dispatchers.IO) {
                aiCompleteAcrossBases(
                    apiKey = apiKey,
                    model = model,
                    bases = bases,
                    request = AiCompleter.CompleteRequest(
                        targetType = "world_template",
                        currentData = mapOf(
                            "label" to payload.label,
                            "category" to payload.category,
                            "summary" to payload.summary,
                            "worldPrompt" to payload.worldPrompt,
                            "gameplay_mode" to payload.gameplayMode,
                        ),
                        extraContext = extraMerged,
                    ),
                    promotePublic = true,
                )
            }
            UsbSessionLog.i(
                "GenWorldTemplate",
                "result taskId=$taskId keys=${result.keys.joinToString(",")} rawSummaryLen=${(result["summary"] as? String)?.length ?: 0} rawWorldPromptLen=${(result["worldPrompt"] as? String)?.length ?: (result["world_prompt"] as? String)?.length ?: 0}",
            )
            if (result.isEmpty()) {
                taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, "模型返回空，请检查网络、模型或 Base URL 配置", now())
                return
            }
            if (taskDao.getById(taskId)?.status == GenerationTaskStatus.CANCELLED) return
            val tmpl = worldTemplateDao.getById(payload.templateRowId) ?: run {
                taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, "模板已删除", now())
                return
            }
            var next = tmpl
            var changed = false
            val newSummary = (result["summary"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                ?: (result["Summary"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            val newWp = (result["worldPrompt"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                ?: (result["world_prompt"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            UsbSessionLog.i(
                "GenWorldTemplate",
                "diff taskId=$taskId newSummaryLen=${newSummary?.length ?: 0} newWorldPromptLen=${newWp?.length ?: 0} changedSummary=${newSummary != null && newSummary != payload.summary} changedWorldPrompt=${newWp != null && newWp != payload.worldPrompt}",
            )
            newSummary?.takeIf { it != payload.summary }?.let {
                next = next.copy(summary = it)
                changed = true
            }
            newWp?.takeIf { it != payload.worldPrompt }?.let {
                next = next.copy(worldPrompt = it)
                changed = true
            }
            if (!changed) {
                taskDao.setTerminal(taskId, GenerationTaskStatus.COMPLETED, "模型未修改摘要或世界书", now())
                return
            }
            var updated = 0
            if (!commitStep(taskId, 1, 1) {
                updated = worldTemplateDao.updateGeneratedContentIfUnchanged(
                    id = tmpl.id,
                    expectedSummary = payload.expectedSummary ?: payload.summary,
                    expectedWorldPrompt = payload.expectedWorldPrompt ?: payload.worldPrompt,
                    summary = next.summary,
                    worldPrompt = next.worldPrompt,
                    updatedAt = System.currentTimeMillis(),
                )
            }) return
            if (updated == 0) {
                if (worldTemplateDao.getById(tmpl.id) == null) {
                    taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, "模板已删除", now())
                } else {
                    taskDao.setTerminal(
                        taskId,
                        GenerationTaskStatus.COMPLETED,
                        "模板已在生成期间编辑，未覆盖当前内容",
                        now(),
                    )
                }
                return
            }
            taskDao.setTerminal(taskId, GenerationTaskStatus.COMPLETED, "", now())
        } catch (e: Exception) {
            taskDao.setTerminal(taskId, GenerationTaskStatus.FAILED, e.message ?: "模板 AI 失败", now())
        }
    }

    private fun buildWorldPrompt(p: EncyclopediaBatchPayload): String = buildString {
        if (p.encyclopediaName.isNotBlank()) appendLine("百科名称：${p.encyclopediaName}")
        if (p.worldBackground.isNotBlank()) append(p.worldBackground)
    }.toString().trim()

    private fun publicChatBases(raw: String = secureStorage.publicBaseUrl): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        return ApiRootLines.splitToOrderedDistinct(trimmed, llmApiService::normalizeOpenAiCompatibleBase)
    }

    private fun promotePublicBaseIfNeeded(usedNormalizedBase: String) {
        val raw = secureStorage.publicBaseUrl
        if (!ApiRootLines.hasMultipleCandidates(raw)) return
        val promoted = ApiRootLines.promoteLineToFront(
            raw,
            usedNormalizedBase,
            llmApiService::normalizeOpenAiCompatibleBase,
        )
        if (promoted != raw) {
            secureStorage.publicBaseUrl = promoted
            UsbSessionLog.i("GenQueue", "public LLM base: promoted successful line to front")
        }
    }

    private suspend fun aiCompleteAcrossBases(
        apiKey: String,
        model: String,
        bases: List<String>,
        request: AiCompleter.CompleteRequest,
        promotePublic: Boolean,
    ): Map<String, Any> {
        if (bases.isEmpty()) return emptyMap()
        var last = emptyMap<String, Any>()
        for ((idx, base) in bases.withIndex()) {
            try {
                last = aiCompleter.complete(apiKey, base, model, request)
                if (last.isNotEmpty()) {
                    if (promotePublic && idx > 0) promotePublicBaseIfNeeded(base)
                    return last
                }
            } catch (_: Exception) {
                // try next base
            }
        }
        return last
    }

    private suspend fun fetchBatchItemsWithRetry(
        apiKey: String,
        bases: List<String>,
        model: String,
        payload: EncyclopediaBatchPayload,
        chunk: Int,
        worldBlock: String,
    ): List<Map<String, Any>> {
        val ctx = combinedUserContextForBatch(payload)
        repeat(3) { attempt ->
            for ((idx, base) in bases.withIndex()) {
                val got = runCatching {
                    batchGenerator.generateBatch(
                        apiKey = apiKey,
                        baseUrl = base,
                        model = model,
                        encyclopediaId = payload.encyclopediaId,
                        entryType = payload.entryType,
                        count = chunk,
                        worldPrompt = worldBlock,
                        minWords = payload.minWords,
                        maxWords = payload.maxWords,
                        extraUserContext = ctx,
                    )
                }.getOrElse { emptyList() }
                if (got.isNotEmpty()) {
                    if (idx > 0) promotePublicBaseIfNeeded(base)
                    return got
                }
            }
            if (attempt < 2) delay(1200L * (attempt + 1))
        }
        return emptyList()
    }

    private fun batchChunkSize(total: Int, done: Int, maxWords: Int): Int {
        val remaining = (total - done).coerceAtLeast(1)
        return when {
            maxWords >= 800 -> min(2, remaining)
            total >= 20 -> min(3, remaining)
            total >= 10 -> min(4, remaining)
            else -> min(5, remaining)
        }
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val PERSONA_AI_TIMEOUT_MS = 120_000L
        const val STALE_RUNNING_MS = 15 * 60 * 1000L
    }
}

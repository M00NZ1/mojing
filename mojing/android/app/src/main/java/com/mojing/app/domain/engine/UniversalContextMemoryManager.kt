package com.mojing.app.domain.engine

import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionContextMemoryDao
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionContextMemoryEntity
import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.util.UsbSessionLog
import com.google.gson.Gson
import com.google.gson.JsonParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

enum class UniversalContextMemoryUpdateResult {
    UPDATED,
    NO_CHANGES,
    REQUIRES_FULL_REBUILD,
    SUPERSEDED,
    FAILED,
}

@Singleton
class UniversalContextMemoryManager @Inject constructor(
    private val dao: SessionContextMemoryDao,
    private val messageDao: MessageDao,
    private val llmRetry: LlmRetry,
) {
    private companion object {
        const val REBUILD_BATCH_SIZE = 40
        const val AUTOMATIC_REBUILD_MESSAGE_LIMIT = 120
    }

    private val gson = Gson()

    suspend fun getFormattedMemory(sessionId: Long, branchId: String): String {
        val entity = dao.getBySessionAndBranch(sessionId, branchId) ?: return ""
        if (!entity.isValid) return ""
        return UniversalContextMemoryFormatter.format(entity.toModel())
    }

    suspend fun clear(sessionId: Long, branchId: String) {
        dao.clearAndAdvanceRevision(sessionId, branchId, System.currentTimeMillis())
        UsbSessionLog.i("UCM", "cleared sid=$sessionId branch=$branchId")
    }

    /** 必须在读取本轮消息快照之前调用，保证后发更新与故事线变更都能淘汰旧结果。 */
    suspend fun reserveUpdateRevision(sessionId: Long, branchId: String): Long =
        dao.reserveNextRevision(sessionId, branchId, System.currentTimeMillis())

    suspend fun rebuild(
        sessionId: Long,
        branchId: String,
        expectedRevision: Long,
        apiKey: String,
        baseUrl: String,
        model: String,
        worldText: String,
        activeCharacterNames: List<String>,
    ): Boolean {
        val storedEntity = dao.getBySessionAndBranch(sessionId, branchId)
        if ((storedEntity?.revision ?: 0L) != expectedRevision) return false

        return try {
            var cursor = 0L
            var sourceStartMessageId = 0L
            var stagedMemory: UniversalContextMemory? = null
            var pageCount = 0
            while (true) {
                if (!revisionMatches(sessionId, branchId, expectedRevision)) {
                    UsbSessionLog.w(
                        "UCM",
                        "rebuild superseded sid=$sessionId branch=$branchId revision=$expectedRevision",
                    )
                    return false
                }
                val page = messageDao.getNextStoryContextBatch(
                    sessionId = sessionId,
                    branchId = branchId,
                    afterMessageId = cursor,
                    limit = REBUILD_BATCH_SIZE,
                )
                if (page.isEmpty()) break
                if (sourceStartMessageId == 0L) sourceStartMessageId = page.first().id
                stagedMemory = requestMemory(
                    operation = "rebuild",
                    sessionId = sessionId,
                    branchId = branchId,
                    oldMemory = stagedMemory,
                    messages = page,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    model = model,
                    worldText = worldText,
                    activeCharacterNames = activeCharacterNames,
                ) ?: return false
                cursor = page.last().id
                pageCount += 1
            }
            val rebuiltMemory = stagedMemory ?: return false
            commitMemory(
                storedEntity = storedEntity,
                memory = rebuiltMemory,
                sessionId = sessionId,
                branchId = branchId,
                expectedRevision = expectedRevision,
                sourceStartMessageId = sourceStartMessageId,
                sourceEndMessageId = cursor,
                operation = "rebuild",
            ).also { committed ->
                if (committed) {
                    UsbSessionLog.i(
                        "UCM",
                        "rebuild complete sid=$sessionId branch=$branchId pages=$pageCount start=$sourceStartMessageId end=$cursor",
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            UsbSessionLog.e("UCM", "rebuild failed sid=$sessionId branch=$branchId", e)
            false
        }
    }

    suspend fun updateAfterMessages(
        sessionId: Long,
        branchId: String,
        expectedRevision: Long,
        apiKey: String,
        baseUrl: String,
        model: String,
        worldText: String,
        activeCharacterNames: List<String>,
    ): UniversalContextMemoryUpdateResult {
        val storedEntity = dao.getBySessionAndBranch(sessionId, branchId)
        if ((storedEntity?.revision ?: 0L) != expectedRevision) {
            UsbSessionLog.w(
                "UCM",
                "update superseded before request sid=$sessionId branch=$branchId revision=$expectedRevision",
            )
            return UniversalContextMemoryUpdateResult.SUPERSEDED
        }
        val reusableEntity = storedEntity?.takeIf { it.isValid }
        val resetExisting = reusableEntity == null
        val afterMessageId = reusableEntity?.sourceEndMessageId?.coerceAtLeast(0L) ?: 0L

        return try {
            val pending = messageDao.getNextStoryContextBatch(
                sessionId = sessionId,
                branchId = branchId,
                afterMessageId = afterMessageId,
                limit = AUTOMATIC_REBUILD_MESSAGE_LIMIT + 1,
            )
            if (pending.isEmpty()) return UniversalContextMemoryUpdateResult.NO_CHANGES
            if (pending.size > AUTOMATIC_REBUILD_MESSAGE_LIMIT) {
                UsbSessionLog.w(
                    "UCM",
                    "update requires full rebuild sid=$sessionId branch=$branchId after=$afterMessageId pending>${AUTOMATIC_REBUILD_MESSAGE_LIMIT}",
                )
                return UniversalContextMemoryUpdateResult.REQUIRES_FULL_REBUILD
            }

            var stagedMemory = reusableEntity?.toModel()
            for (page in pending.chunked(REBUILD_BATCH_SIZE)) {
                if (!revisionMatches(sessionId, branchId, expectedRevision)) {
                    return UniversalContextMemoryUpdateResult.SUPERSEDED
                }
                stagedMemory = requestMemory(
                    operation = if (resetExisting) "recover" else "update",
                    sessionId = sessionId,
                    branchId = branchId,
                    oldMemory = stagedMemory,
                    messages = page,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    model = model,
                    worldText = worldText,
                    activeCharacterNames = activeCharacterNames,
                ) ?: return UniversalContextMemoryUpdateResult.FAILED
            }
            val memory = stagedMemory ?: return UniversalContextMemoryUpdateResult.FAILED
            val startId = reusableEntity?.sourceStartMessageId
                ?.takeIf { it > 0L }
                ?: pending.first().id
            if (
                commitMemory(
                    storedEntity = storedEntity,
                    memory = memory,
                    sessionId = sessionId,
                    branchId = branchId,
                    expectedRevision = expectedRevision,
                    sourceStartMessageId = startId,
                    sourceEndMessageId = pending.last().id,
                    operation = if (resetExisting) "recover" else "update",
                )
            ) {
                UniversalContextMemoryUpdateResult.UPDATED
            } else {
                UniversalContextMemoryUpdateResult.SUPERSEDED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            UsbSessionLog.e("UCM", "update failed sid=$sessionId branch=$branchId", e)
            UniversalContextMemoryUpdateResult.FAILED
        }
    }

    private suspend fun requestMemory(
        operation: String,
        sessionId: Long,
        branchId: String,
        oldMemory: UniversalContextMemory?,
        messages: List<MessageEntity>,
        apiKey: String,
        baseUrl: String,
        model: String,
        worldText: String,
        activeCharacterNames: List<String>,
    ): UniversalContextMemory? {
        if (messages.isEmpty()) return oldMemory
        val oldMemoryText = UniversalContextMemoryFormatter.format(oldMemory)
        val conversationText = messages.joinToString("\n") { msg ->
            "${msg.speakerType}: ${ConversationMessageText.forDerivedContext(msg).take(1200)}"
        }
        val prompt = UniversalContextMemoryPrompt.buildUpdatePrompt(
            oldMemoryText = oldMemoryText,
            conversationText = conversationText,
            worldText = worldText,
            activeCharacterNames = activeCharacterNames,
        )
        val llmMessages = listOf(
            ChatMessage("system", "你是通用上下文记忆维护器。"),
            ChatMessage("user", prompt),
        )
        UsbSessionLog.i(
            "UCM",
            "$operation page sid=$sessionId branch=$branchId msgs=${messages.size} oldLen=${oldMemoryText.length} start=${messages.first().id} end=${messages.last().id}",
        )
        val raw = llmRetry.chatCompletionWithRetry(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            messages = llmMessages,
            temperature = 0.4f,
            maxTokens = 1800,
        )
        val json = extractTopLevelJsonObject(raw) ?: run {
            UsbSessionLog.w("UCM", "$operation parse miss sid=$sessionId branch=$branchId")
            return null
        }
        return parseMemoryFromJson(json).also { parsed ->
            if (parsed == null) UsbSessionLog.w("UCM", "$operation parse null sid=$sessionId branch=$branchId")
        }
    }

    private suspend fun revisionMatches(sessionId: Long, branchId: String, expectedRevision: Long): Boolean =
        (dao.getBySessionAndBranch(sessionId, branchId)?.revision ?: 0L) == expectedRevision

    private suspend fun commitMemory(
        storedEntity: SessionContextMemoryEntity?,
        memory: UniversalContextMemory,
        sessionId: Long,
        branchId: String,
        expectedRevision: Long,
        sourceStartMessageId: Long,
        sourceEndMessageId: Long,
        operation: String,
    ): Boolean {
        val now = System.currentTimeMillis()
        val entity = SessionContextMemoryEntity(
            id = storedEntity?.id ?: 0,
            sessionId = sessionId,
            branchId = branchId,
            globalSummary = memory.globalSummary,
            userStateJson = gson.toJson(memory.userState),
            characterStatesJson = gson.toJson(memory.characterStates),
            relationshipStatesJson = gson.toJson(memory.relationshipStates),
            worldStateJson = gson.toJson(memory.worldState),
            recentTimelineJson = gson.toJson(memory.recentCompressedTimeline),
            openThreadsJson = gson.toJson(memory.openThreads),
            continuityRulesJson = gson.toJson(memory.continuityRules),
            sourceStartMessageId = sourceStartMessageId,
            sourceEndMessageId = sourceEndMessageId,
            memoryVersion = 1,
            isValid = true,
            revision = expectedRevision,
            createdAt = storedEntity?.createdAt ?: now,
            updatedAt = now,
        )
        if (!dao.replaceIfRevisionMatches(entity, expectedRevision)) {
            UsbSessionLog.w(
                "UCM",
                "$operation superseded sid=$sessionId branch=$branchId revision=$expectedRevision",
            )
            return false
        }
        UsbSessionLog.i(
            "UCM",
            "$operation success sid=$sessionId branch=$branchId newLen=${UniversalContextMemoryFormatter.format(memory).length}",
        )
        return true
    }

    fun parseMemoryFromJson(json: String): UniversalContextMemory? = try {
        gson.fromJson(json, UniversalContextMemory::class.java)
    } catch (_: Exception) {
        null
    }

    private fun extractTopLevelJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = text.substring(start, end + 1)
        return try {
            JsonParser.parseString(candidate).asJsonObject.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun SessionContextMemoryEntity.toModel(): UniversalContextMemory = UniversalContextMemory(
        globalSummary = globalSummary,
        userState = runCatching { gson.fromJson(userStateJson, UniversalUserState::class.java) }.getOrDefault(UniversalUserState()),
        characterStates = runCatching {
            gson.fromJson(characterStatesJson, Array<UniversalCharacterState>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList()),
        relationshipStates = runCatching {
            gson.fromJson(relationshipStatesJson, Array<UniversalRelationshipState>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList()),
        worldState = runCatching { gson.fromJson(worldStateJson, UniversalWorldState::class.java) }.getOrDefault(UniversalWorldState()),
        recentCompressedTimeline = runCatching {
            gson.fromJson(recentTimelineJson, Array<UniversalTimelineItem>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList()),
        openThreads = runCatching {
            gson.fromJson(openThreadsJson, Array<String>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList()),
        continuityRules = runCatching {
            gson.fromJson(continuityRulesJson, Array<String>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList()),
    )
}

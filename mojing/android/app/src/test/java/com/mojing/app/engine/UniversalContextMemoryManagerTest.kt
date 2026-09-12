package com.mojing.app.engine

import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionContextMemoryDao
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionContextMemoryEntity
import com.mojing.app.data.remote.ChatMessage
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.engine.UniversalContextMemoryManager
import com.mojing.app.domain.engine.UniversalContextMemoryUpdateResult
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalContextMemoryManagerTest {
    private val dao = mockk<SessionContextMemoryDao>(relaxed = true)
    private val messageDao = mockk<MessageDao>()
    private val llmRetry = mockk<LlmRetry>()
    private val manager = UniversalContextMemoryManager(dao, messageDao, llmRetry)

    private fun allowRevisionCommit() {
        coEvery { dao.replaceIfRevisionMatches(any(), any()) } returns true
    }

    private fun memoryJson(summary: String, continuityRule: String = ""): String = """
        {
          "globalSummary":"$summary",
          "userState":{},
          "characterStates":[],
          "relationshipStates":[],
          "worldState":{},
          "recentCompressedTimeline":[],
          "openThreads":[],
          "continuityRules":[${if (continuityRule.isBlank()) "" else "\"$continuityRule\""}]
        }
    """.trimIndent()

    private fun storyMessages(ids: LongRange): List<MessageEntity> = ids.map { id ->
        MessageEntity(
            id = id,
            sessionId = 7L,
            speakerType = if (id % 2L == 0L) "character" else "user",
            content = "剧情$id",
        )
    }

    private suspend fun update(expectedRevision: Long = 0L): UniversalContextMemoryUpdateResult =
        manager.updateAfterMessages(
            sessionId = 7L,
            branchId = "main",
            expectedRevision = expectedRevision,
            apiKey = "k",
            baseUrl = "https://api.test.com",
            model = "m",
            worldText = "世界设定",
            activeCharacterNames = listOf("角色A"),
        )

    private suspend fun rebuild(expectedRevision: Long = 0L): Boolean = manager.rebuild(
        sessionId = 7L,
        branchId = "main",
        expectedRevision = expectedRevision,
        apiKey = "k",
        baseUrl = "https://api.test.com",
        model = "m",
        worldText = "世界设定",
        activeCharacterNames = listOf("角色A"),
    )

    @Test
    fun parseMemoryFromJsonReturnsNullForInvalidJson() {
        assertNull(manager.parseMemoryFromJson("not-json"))
    }

    @Test
    fun parseMemoryFromJsonReturnsModelForValidJson() {
        val parsed = manager.parseMemoryFromJson(memoryJson("测试摘要", "不要漂移"))

        assertEquals("测试摘要", parsed?.globalSummary)
        assertEquals(listOf("不要漂移"), parsed?.continuityRules)
    }

    @Test
    fun realProviderMemoryPreservesMultipleEvidenceItems() {
        val raw = checkNotNull(javaClass.getResource("/memory/siliconflow-v3_2.json")).readText()
        val expected = com.google.gson.JsonParser.parseString(raw).asJsonObject
            .getAsJsonArray("relationshipStates")[0].asJsonObject.getAsJsonArray("evidence")
            .joinToString("\n") { it.asString }
        val memory = checkNotNull(manager.parseMemoryFromJson(raw))
        assertEquals(expected, memory.relationshipStates.first().evidence)
        assertTrue(memory.recentCompressedTimeline.isNotEmpty())
    }

    @Test
    fun knownFactStringBecomesOneFactWithoutInventingItems() {
        val raw = memoryJson("摘要").replace("\"userState\":{}", "\"userState\":{\"knownFacts\":\"仓库中的日志已找到\"}")
        assertEquals(listOf("仓库中的日志已找到"), manager.parseMemoryFromJson(raw)?.userState?.knownFacts)
    }

    @Test
    fun parseMemoryFromJsonAcceptsFenceWrapperAndEncodedMemory() {
        val payload = memoryJson("兼容摘要")
        assertEquals("兼容摘要", manager.parseMemoryFromJson("```json\n$payload\n```")?.globalSummary)
        assertEquals("兼容摘要", manager.parseMemoryFromJson("{\"data\":${Gson().toJson(payload)}}")?.globalSummary)
    }

    @Test
    fun parseMemoryFromJsonRejectsIncompleteOrWrongShapeWrapper() {
        assertNull(manager.parseMemoryFromJson("{\"data\":{\"globalSummary\":\"伪记忆\"}}"))
        assertNull(manager.parseMemoryFromJson(memoryJson("伪记忆").replace("\"openThreads\":[]", "\"openThreads\":{}")))
    }

    @Test
    fun nestedNullsAndWrongElementTypesCannotBecomeUsableMemory() {
        val invalid = listOf(
            memoryJson("摘要").replace("\"userState\":{}", "\"userState\":{\"knownFacts\":[null]}"),
            memoryJson("摘要").replace("\"characterStates\":[]", "\"characterStates\":[{\"name\":5}]"),
            memoryJson("摘要").replace("\"openThreads\":[]", "\"openThreads\":[{}]"),
        )
        invalid.forEach { assertNull(manager.parseMemoryFromJson(it)) }
    }

    @Test
    fun lateParseFailureAfterRevisionChangeDoesNotCooldownTheNextUpdate() = runTest {
        var revision = 0L
        coEvery { dao.getBySessionAndBranch(7L, "main") } answers {
            SessionContextMemoryEntity(sessionId = 7L, branchId = "main", revision = revision)
        }
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 0L, 121) } returns storyMessages(1L..1L)
        coEvery { llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any(), jsonOutput = true) } coAnswers {
            revision = 1L
            "invalid output"
        }
        assertEquals(UniversalContextMemoryUpdateResult.SUPERSEDED, update())
        allowRevisionCommit()
        coEvery { llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any(), jsonOutput = true) } returns memoryJson("新事实")
        assertEquals(UniversalContextMemoryUpdateResult.UPDATED, update(1L))
    }

    @Test
    fun changedKeyCanRecoverImmediatelyAndAutomaticFailureKeepsOldMemory() = runTest {
        val old = SessionContextMemoryEntity(sessionId = 7L, branchId = "main", globalSummary = "旧事实")
        coEvery { dao.getBySessionAndBranch(7L, "main") } returns old
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 0L, 121) } returns storyMessages(1L..1L)
        coEvery { llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any(), jsonOutput = true) } returns "invalid"
        assertEquals(UniversalContextMemoryUpdateResult.FAILED, update())
        coVerify(exactly = 0) { dao.replaceIfRevisionMatches(any(), any()) }
        allowRevisionCommit()
        coEvery { llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any(), jsonOutput = true) } returns memoryJson("新事实")
        assertEquals(UniversalContextMemoryUpdateResult.UPDATED, manager.updateAfterMessages(
            7L, "main", 0L, "new-key", "https://api.test.com", "m", "世界", emptyList(),
        ))
    }

    @Test
    fun invalidatedMemoryIsNotFormattedForThePrompt() = runTest {
        coEvery { dao.getBySessionAndBranch(7L, "main") } returns SessionContextMemoryEntity(
            sessionId = 7L,
            branchId = "main",
            globalSummary = "旧版本剧情",
            isValid = false,
        )

        assertEquals("", manager.getFormattedMemory(7L, "main"))
    }

    @Test
    fun updateAfterMessagesPersistsParsedMemory() = runTest {
        allowRevisionCommit()
        val requestMessages = slot<List<ChatMessage>>()
        val oldEntity = SessionContextMemoryEntity(
            sessionId = 7L,
            branchId = "main",
            globalSummary = "旧摘要",
            continuityRulesJson = Gson().toJson(listOf("旧规则")),
        )
        val newMessages = listOf(
            MessageEntity(id = 1L, sessionId = 7L, speakerType = "user", content = "第一句"),
            MessageEntity(
                id = 2L,
                sessionId = 7L,
                speakerType = "character",
                content = "<SPEECH>第二句</SPEECH><CHOICES type=\"actions\"><OPTION>未选择动作</OPTION></CHOICES>",
            ),
        )
        coEvery { dao.getBySessionAndBranch(7L, "main") } returns oldEntity
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 0L, 121) } returns newMessages
        coEvery {
            llmRetry.chatCompletionWithRetry(
                apiKey = any(),
                baseUrl = any(),
                model = any(),
                messages = capture(requestMessages),
                temperature = any(),
                maxTokens = any(),
                maxRetries = any(),
                jsonOutput = true,
            )
        } returns memoryJson("新摘要", "不要误判关系")

        assertEquals(UniversalContextMemoryUpdateResult.UPDATED, update())

        coVerify(exactly = 1) {
            dao.replaceIfRevisionMatches(match {
                it.sessionId == 7L &&
                    it.branchId == "main" &&
                    it.globalSummary == "新摘要" &&
                    it.sourceStartMessageId == 1L &&
                    it.sourceEndMessageId == 2L
            }, 0L)
        }
        val prompt = requestMessages.captured.joinToString("\n") { it.content }
        assertTrue(prompt.contains("第二句"))
        assertFalse(prompt.contains("未选择动作"))
        assertFalse(prompt.contains("<CHOICES"))
    }

    @Test
    fun updatePreservesFullAccumulatedSourceRange() = runTest {
        allowRevisionCommit()
        val oldEntity = SessionContextMemoryEntity(
            id = 11L,
            sessionId = 7L,
            branchId = "main",
            globalSummary = "旧摘要",
            sourceStartMessageId = 2L,
            sourceEndMessageId = 20L,
        )
        coEvery { dao.getBySessionAndBranch(7L, "main") } returns oldEntity
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 20L, 121) } returns listOf(
            MessageEntity(id = 24L, sessionId = 7L, content = "新上下文"),
        )
        coEvery {
            llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any(), jsonOutput = true)
        } returns memoryJson("续写摘要")

        assertEquals(UniversalContextMemoryUpdateResult.UPDATED, update())

        coVerify(exactly = 1) {
            dao.replaceIfRevisionMatches(match {
                it.id == 11L &&
                    it.sourceStartMessageId == 2L &&
                    it.sourceEndMessageId == 24L
            }, 0L)
        }
    }

    @Test
    fun updateRebuildsAnInvalidatedRecordWithoutReusingItsFactsOrSourceRange() = runTest {
        allowRevisionCommit()
        val requestMessages = slot<List<ChatMessage>>()
        val invalidated = SessionContextMemoryEntity(
            id = 11L,
            sessionId = 7L,
            branchId = "main",
            globalSummary = "不应继续使用的旧版本剧情",
            sourceStartMessageId = 2L,
            sourceEndMessageId = 20L,
            isValid = false,
            createdAt = 123L,
        )
        coEvery { dao.getBySessionAndBranch(7L, "main") } returns invalidated
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 0L, 121) } returns listOf(
            MessageEntity(id = 50L, sessionId = 7L, content = "采用版本后的第一条"),
            MessageEntity(id = 51L, sessionId = 7L, speakerType = "character", content = "采用版本后的回复"),
        )
        coEvery {
            llmRetry.chatCompletionWithRetry(
                apiKey = any(),
                baseUrl = any(),
                model = any(),
                messages = capture(requestMessages),
                temperature = any(),
                maxTokens = any(),
                maxRetries = any(),
                jsonOutput = true,
            )
        } returns memoryJson("按新版本重建")

        assertEquals(UniversalContextMemoryUpdateResult.UPDATED, update())
        assertFalse(requestMessages.captured.joinToString("\n") { it.content }.contains(invalidated.globalSummary))
        coVerify(exactly = 1) {
            dao.replaceIfRevisionMatches(match {
                it.id == 11L &&
                    it.globalSummary == "按新版本重建" &&
                    it.sourceStartMessageId == 50L &&
                    it.sourceEndMessageId == 51L &&
                    it.memoryVersion == 1 &&
                    it.isValid &&
                    it.createdAt == 123L
            }, 0L)
        }
    }

    @Test
    fun staleRemoteResultCannotReactivateMemoryAfterTheStorylineRevisionChanges() = runTest {
        val stored = SessionContextMemoryEntity(
            id = 11L,
            sessionId = 7L,
            branchId = "main",
            globalSummary = "请求开始时的记忆",
            revision = 4L,
        )
        coEvery { dao.getBySessionAndBranch(7L, "main") } returns stored
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 0L, 121) } returns listOf(
            MessageEntity(id = 30L, sessionId = 7L, content = "旧请求输入"),
        )
        coEvery {
            llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any(), jsonOutput = true)
        } returns memoryJson("已经过期的远程结果")
        coEvery { dao.replaceIfRevisionMatches(any(), 4L) } returns false

        assertEquals(UniversalContextMemoryUpdateResult.SUPERSEDED, update(expectedRevision = 4L))
        coVerify(exactly = 1) {
            dao.replaceIfRevisionMatches(match { it.globalSummary == "已经过期的远程结果" }, 4L)
        }
    }

    @Test
    fun cancellationDoesNotTurnIntoAReportedMemoryFailure() = runTest {
        coEvery { dao.getBySessionAndBranch(7L, "main") } returns null
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 0L, 121) } returns listOf(
            MessageEntity(id = 1L, sessionId = 7L, content = "停止"),
        )
        coEvery {
            llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any(), jsonOutput = true)
        } throws CancellationException("stop")

        var propagated = false
        try {
            update()
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
        coVerify(exactly = 0) { dao.replaceIfRevisionMatches(any(), any()) }
    }

    @Test
    fun automaticUpdateRequiresManualRebuildInsteadOfTruncatingALongBacklog() = runTest {
        coEvery { dao.getBySessionAndBranch(7L, "main") } returns null
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 0L, 121) } returns storyMessages(1L..121L)

        assertEquals(UniversalContextMemoryUpdateResult.REQUIRES_FULL_REBUILD, update())
        coVerify(exactly = 0) {
            llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any(), jsonOutput = true)
        }
        coVerify(exactly = 0) { dao.replaceIfRevisionMatches(any(), any()) }
    }

    @Test
    fun automaticParseFailureIsDeferredUntilManualRecovery() = runTest {
        val routeModel = "cooldown-model"
        coEvery { dao.getBySessionAndBranch(7L, "main") } returns null
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 0L, 121) } returns listOf(
            MessageEntity(id = 1L, sessionId = 7L, content = "需要整理"),
        )
        coEvery {
            llmRetry.chatCompletionWithRetry(any(), any(), routeModel, any(), any(), any(), any(), jsonOutput = true)
        } returns "拒答"

        assertEquals(UniversalContextMemoryUpdateResult.FAILED, updateForModel(routeModel))
        assertEquals(UniversalContextMemoryUpdateResult.DEFERRED, updateForModel(routeModel))
        coVerify(exactly = 1) { llmRetry.chatCompletionWithRetry(any(), any(), routeModel, any(), any(), any(), any(), jsonOutput = true) }
        manager.clearAutomaticFailureCooldown(7L, "main")
        assertEquals(UniversalContextMemoryUpdateResult.FAILED, updateForModel(routeModel))
        coVerify(exactly = 2) { llmRetry.chatCompletionWithRetry(any(), any(), routeModel, any(), any(), any(), any(), jsonOutput = true) }
    }

    private suspend fun updateForModel(model: String): UniversalContextMemoryUpdateResult =
        manager.updateAfterMessages(7L, "main", 0L, "k", "https://api.test.com", model, "世界设定", listOf("角色A"))

    @Test
    fun rebuildFailureKeepsExistingMemory() = runTest {
        val oldEntity = SessionContextMemoryEntity(
            id = 11L,
            sessionId = 7L,
            branchId = "main",
            globalSummary = "仍可使用的旧记忆",
        )
        coEvery { dao.getBySessionAndBranch(7L, "main") } returns oldEntity
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 0L, 40) } returns listOf(
            MessageEntity(id = 10L, sessionId = 7L, content = "重新整理"),
        )
        coEvery {
            llmRetry.chatCompletionWithRetry(any(), any(), any(), any(), any(), any(), any(), jsonOutput = true)
        } throws IllegalStateException("offline")

        assertFalse(rebuild())
        coVerify(exactly = 0) { dao.clearAndAdvanceRevision(any(), any(), any()) }
        coVerify(exactly = 0) { dao.replaceIfRevisionMatches(any(), any()) }
    }

    @Test
    fun rebuildWithNoMessagesKeepsExistingMemory() = runTest {
        coEvery { dao.getBySessionAndBranch(7L, "main") } returns null
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 0L, 40) } returns emptyList()

        assertFalse(rebuild())
        coVerify(exactly = 0) { dao.clearAndAdvanceRevision(any(), any(), any()) }
        coVerify(exactly = 0) { dao.replaceIfRevisionMatches(any(), any()) }
    }

    @Test
    fun rebuildReplacesExistingMemoryOnlyAfterSuccessfulResponse() = runTest {
        allowRevisionCommit()
        val requestMessages = slot<List<ChatMessage>>()
        val oldEntity = SessionContextMemoryEntity(
            id = 11L,
            sessionId = 7L,
            branchId = "main",
            globalSummary = "不应进入重建请求的旧摘要",
            sourceStartMessageId = 2L,
            sourceEndMessageId = 20L,
            createdAt = 123L,
        )
        val page = listOf(
            MessageEntity(id = 10L, sessionId = 7L, content = "第一条"),
            MessageEntity(id = 24L, sessionId = 7L, speakerType = "character", content = "第二条"),
        )
        coEvery { dao.getBySessionAndBranch(7L, "main") } returns oldEntity
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 0L, 40) } returns page
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 24L, 40) } returns emptyList()
        coEvery {
            llmRetry.chatCompletionWithRetry(
                apiKey = any(),
                baseUrl = any(),
                model = any(),
                messages = capture(requestMessages),
                temperature = any(),
                maxTokens = any(),
                maxRetries = any(),
                jsonOutput = true,
            )
        } returns memoryJson("重建后的摘要")

        assertTrue(rebuild())
        assertFalse(requestMessages.captured.joinToString("\n") { it.content }.contains(oldEntity.globalSummary))
        coVerify(exactly = 0) { dao.clearAndAdvanceRevision(any(), any(), any()) }
        coVerify(exactly = 1) {
            dao.replaceIfRevisionMatches(match {
                it.id == 11L &&
                    it.globalSummary == "重建后的摘要" &&
                    it.sourceStartMessageId == 10L &&
                    it.sourceEndMessageId == 24L &&
                    it.createdAt == 123L
            }, 0L)
        }
    }

    @Test
    fun rebuildReadsTheWholeStoryInBoundedPagesAndCommitsOnce() = runTest {
        allowRevisionCommit()
        val requests = mutableListOf<List<ChatMessage>>()
        val firstPage = storyMessages(1L..40L)
        val secondPage = storyMessages(41L..41L)
        coEvery { dao.getBySessionAndBranch(7L, "main") } returns null
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 0L, 40) } returns firstPage
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 40L, 40) } returns secondPage
        coEvery { messageDao.getNextStoryContextBatch(7L, "main", 41L, 40) } returns emptyList()
        coEvery {
            llmRetry.chatCompletionWithRetry(
                apiKey = any(),
                baseUrl = any(),
                model = any(),
                messages = capture(requests),
                temperature = any(),
                maxTokens = any(),
                maxRetries = any(),
                jsonOutput = true,
            )
        } returnsMany listOf(memoryJson("第一页摘要"), memoryJson("完整摘要"))

        assertTrue(rebuild())

        assertEquals(2, requests.size)
        assertTrue(requests[1].joinToString("\n") { it.content }.contains("第一页摘要"))
        coVerify(exactly = 1) {
            dao.replaceIfRevisionMatches(match {
                it.globalSummary == "完整摘要" &&
                    it.sourceStartMessageId == 1L &&
                    it.sourceEndMessageId == 41L
            }, 0L)
        }
    }
}

package com.mojing.app.repository

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.repository.ChatRepository
import com.mojing.app.domain.engine.ChatEngine
import com.mojing.app.domain.engine.StreamState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val sessionDao = mockk<SessionDao>(relaxed = true)
    private val chatEngine = mockk<ChatEngine>()
    private val secureStorage = mockk<SecureStorage> {
        every { publicApiKey } returns "sk-test"
        every { publicBaseUrl } returns "https://api.test.com"
        every { publicModel } returns "test-model"
        every { userName } returns "玩家"
        every { userDescription } returns ""
    }
    private val repo = ChatRepository(messageDao, sessionDao, chatEngine, secureStorage)

    @Test
    fun streamGenerateNormalFlow() = runTest {
        coEvery { messageDao.getMainContextTail(1, 400) } returns emptyList()
        coEvery {
            chatEngine.streamGenerate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns flowOf(
            StreamState.Generating("你好"),
            StreamState.Done("你好呀！")
        )

        val character = CharacterEntity(id = 1, name = "测试角色", temperature = 0.8f, maxTokens = 2048)
        val states = repo.streamGenerate(1, character).toList()

        assertTrue(states.any { it is StreamState.Generating })
        assertTrue(states.last() is StreamState.Done)
    }

    @Test
    fun streamGenerateNoApiKey() = runTest {
        val noKeyStorage = mockk<SecureStorage> {
            every { publicApiKey } returns ""
            every { publicBaseUrl } returns ""
            every { publicModel } returns ""
        }
        val repoNoKey = ChatRepository(messageDao, sessionDao, chatEngine, noKeyStorage)
        val character = CharacterEntity(id = 1, name = "test", apiKey = "")

        val states = repoNoKey.streamGenerate(1, character).toList()
        assertTrue(states.last() is StreamState.Error)
    }

    @Test
    fun streamGenerateUsesVisibleBranchContext() = runTest {
        val visible = listOf(MessageEntity(id = 7, sessionId = 1, branchId = "branch-a", content = "分支上下文"))
        coEvery { messageDao.getVisibleContextTail(1, "branch-a", 400) } returns visible
        coEvery {
            chatEngine.streamGenerate(
                sessionId = 1,
                character = any(),
                historyMessages = visible,
                apiKey = any(),
                baseUrl = any(),
                model = any(),
                temperature = any(),
                maxTokens = any(),
                personaName = any(),
                userDescription = any(),
            )
        } returns flowOf(StreamState.Done("完成"))

        repo.streamGenerate(
            sessionId = 1,
            character = CharacterEntity(id = 1),
            branchId = "branch-a",
            branchSourceMessageId = 6,
        ).toList()

        coVerify {
            messageDao.insert(match {
                it.branchId == "branch-a" && it.parentMessageId == 6L && it.speakerType == "character"
            })
        }
    }

    @Test
    fun resolveApiConfigUsesCharacterFirst() {
        val character = CharacterEntity(id = 1, name = "角色", apiKey = "char-key")
        val (key, _, _) = repo.resolveApiConfig(character)
        assertEquals("char-key", key)
    }

    @Test
    fun resolveApiConfigFallsBackToGlobal() {
        val character = CharacterEntity(id = 1, name = "角色", apiKey = "", apiBaseUrl = "", modelName = "")
        val (key, url, model) = repo.resolveApiConfig(character)
        assertEquals("sk-test", key)
        assertEquals("https://api.test.com", url)
        assertEquals("test-model", model)
    }

    @Test
    fun saveUserMessageReturnsId() = runTest {
        coEvery { messageDao.insert(any()) } returns 42L
        val id = repo.saveUserMessage(1, "测试消息")
        assertEquals(42L, id)
        coVerify(exactly = 1) { sessionDao.touchWithGeneratedTitle(1, "测试消息", any()) }
        coVerify(exactly = 0) { sessionDao.updateTitle(any(), any(), any()) }
    }
}

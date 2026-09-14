package com.mojing.app.ui.chat.search

import com.mojing.app.data.local.entity.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal class QueuedSearchDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
    fun runCurrent() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultFormatterTest {
    @Test fun longReplyWaitsForWorkerAndOnlyRetainsBoundedSnippet() = runTest {
        val cpu = QueuedSearchDispatcher()
        val formatter = SearchResultFormatter(cpu)
        val body = "前文".repeat(50000) + "夜雨落在窗前" + "后文".repeat(50000)
        val result = async { formatter.format(listOf(MessageEntity(id = 8, sessionId = 3,
            speakerType = "narrator", content = body, searchNormalized = body, searchTerms = body)), "夜雨") }
        runCurrent()
        assertFalse("UI scheduler must not perform the body parsing", result.isCompleted)
        cpu.runCurrent()
        runCurrent()
        val hit = result.await().single()
        assertEquals(8L, hit.message.id)
        assertTrue(hit.snippet.contains("夜雨"))
        assertTrue(hit.snippet.length < 300)
        assertEquals("", hit.message.content)
        assertEquals("", hit.message.searchNormalized)
        assertEquals("", hit.message.searchTerms)
    }
}

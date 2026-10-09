package com.mojing.app.data

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionSetupDraftStoreTest {
    private val draft = SessionSetupDraft("original", "题\n目", 7, 9, listOf(12, 3), true, "旁白名", false, "", false, "1000000", 12, true, true, false)

    @Test fun inputOrderAndEmptyValuesSurviveANewStore() = runBlocking {
        val f = Fixture(); f.store().save(draft)
        assertEquals(draft, f.store().load())
    }

    @Test fun oldCreationCallbackCannotClearANewerSetup() = runBlocking {
        val f = Fixture(); f.store().save(draft.copy(requestId = "newer"))
        f.store().clear("original")
        assertEquals("newer", f.store().load()!!.requestId)
        f.store().clear("newer"); assertNull(f.store().load())
    }

    @Test fun failedCommitReportsFailureAndKeepsPriorCheckpoint() = runBlocking {
        val f = Fixture(); f.store().save(draft); f.commitSucceeds = false
        assertTrue(runCatching { f.store().save(draft.copy(title = "变更")) }.isFailure)
        assertEquals(draft, f.store().load())
        assertTrue(runCatching { f.store().clear(draft.requestId) }.isFailure)
        assertEquals(draft, f.store().load())
    }

    @Test fun unreadableVersionIsPreservedAndReported() = runBlocking {
        val f = Fixture(); f.values["draft"] = "{\"version\":2,\"requestId\":\"original\"}"
        assertTrue(runCatching { f.store().load() }.isFailure)
        assertTrue(runCatching { f.store().clear("original") }.isFailure)
        assertTrue(f.values.containsKey("draft"))
    }

    private class Fixture {
        val values = mutableMapOf<String, String?>()
        var commitSucceeds = true
        private val context = mockk<Context>()
        private val preferences = mockk<SharedPreferences>()
        init {
            every { context.getSharedPreferences(any(), any()) } returns preferences
            every { preferences.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
            every { preferences.edit() } answers {
                val next = values.toMutableMap()
                val editor = mockk<SharedPreferences.Editor>()
                every { editor.putString(any(), any()) } answers { next[firstArg()] = secondArg(); editor }
                every { editor.remove(any()) } answers { next.remove(firstArg()); editor }
                every { editor.commit() } answers { if (commitSucceeds) { values.clear(); values.putAll(next) }; commitSucceeds }
                editor
            }
        }
        fun store() = SessionSetupDraftStore(context)
    }
}

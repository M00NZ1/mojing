package com.mojing.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatDraftStoreInstrumentedTest {

    @Test
    fun guidanceOnlyDraftIsScopedAndOldDraftRemainsReadable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ChatDraftStore(context)
        val prefs = context.getSharedPreferences("chat_drafts_v1", 0)
        try {
            prefs.edit().putString("session_8290002", """{"version":1,"inputText":"旧正文"}""").commit()
            assertEquals(ChatDraftSnapshot(inputText = "旧正文"), store.load(8290002))
            store.save(8290003, ChatDraftSnapshot(narratorGuidance = "新的方向"))
            assertEquals("新的方向", ChatDraftStore(context).load(8290003).narratorGuidance)
            assertEquals("旧正文", store.load(8290002).inputText)
            assertEquals("", store.load(8290002).narratorGuidance)
        } finally {
            store.save(8290002, ChatDraftSnapshot())
            store.save(8290003, ChatDraftSnapshot())
        }
    }

    @Test
    fun draftSurvivesStoreRecreationAndCanBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionId = 8_290_001L
        val snapshot = ChatDraftSnapshot(
            inputText = "进程重建后继续写",
            narratorGuidance = "夜晚传来脚步声",
            pendingAttachmentPaths = listOf("/tmp/a.png", "/tmp/b.png"),
            pendingSubmissionId = "submission-8290001",
        )
        val firstStore = ChatDraftStore(context)
        try {
            assertTrue(firstStore.saveBeforeSubmission(sessionId, snapshot))

            assertEquals(snapshot, ChatDraftStore(context).load(sessionId))
        } finally {
            firstStore.save(sessionId, ChatDraftSnapshot())
        }
        assertEquals(ChatDraftSnapshot(), ChatDraftStore(context).load(sessionId))
    }
}

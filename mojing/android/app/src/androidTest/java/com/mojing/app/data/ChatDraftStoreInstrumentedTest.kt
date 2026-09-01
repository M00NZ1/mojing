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
    fun draftSurvivesStoreRecreationAndCanBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionId = 8_290_001L
        val snapshot = ChatDraftSnapshot(
            inputText = "进程重建后继续写",
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

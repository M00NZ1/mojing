package com.mojing.app.data

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MessageEditDraftStoreInstrumentedTest {
    @get:Rule
    val activityRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun realApplicationFilesUseAtomicRoundTripAndRevisionGuard() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = MessageEditDraftStore(context)
        val scope = MessageEditDraftScope(
            sessionId = 9_100_001L,
            branchId = "instrumented-message-edit",
            messageId = 9_100_002L,
        )
        val draft = MessageEditDraft(scope, "source-v1", "编辑后的长文本", "原文", revision = 7L)

        store.save(draft)
        try {
            assertEquals(draft, store.load(scope))
            assertTrue(store.fileFor(scope).isFile)
            assertFalse(store.clear(scope, expectedRevision = 6L))
            assertTrue(store.fileFor(scope).isFile)
            assertTrue(store.clear(scope, expectedRevision = 7L))
            assertEquals(null, store.load(scope))
        } finally {
            store.fileFor(scope).delete()
        }
    }

    @Test
    fun atomicBackupIsRecoveredWhenBaseFileIsMissing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = MessageEditDraftStore(context)
        val scope = MessageEditDraftScope(9_100_003L, "instrumented-backup", 9_100_004L)
        val draft = MessageEditDraft(scope, "source", "备份正文", "原文", revision = 2L)
        store.save(draft)
        val file = store.fileFor(scope)
        try {
            file.copyTo(java.io.File(file.path + ".bak"), overwrite = true)
            file.delete()
            assertEquals(draft, store.load(scope))
        } finally {
            file.delete()
            java.io.File(file.path + ".bak").delete()
        }
    }

    @Test
    fun twentyThousandCharacterDraftSurvivesActivityRecreationAndNewStore() = runBlocking {
        val context = activityRule.activity.applicationContext
        val store = MessageEditDraftStore(context)
        val scope = MessageEditDraftScope(9_100_005L, "activity-recreation", 9_100_006L)
        val body = "长".repeat(20_000)
        val draft = MessageEditDraft(scope, "source-v1", body, "原文", revision = 1L)
        store.save(draft)
        val recreatedStore = MessageEditDraftStore(context)
        try {
            activityRule.activityRule.scenario.recreate()
            activityRule.waitForIdle()
            assertEquals(draft, recreatedStore.load(scope))
            assertEquals(20_000, recreatedStore.load(scope)!!.editedContent.length)
        } finally {
            recreatedStore.clear(scope, 1L)
            recreatedStore.fileFor(scope).delete()
        }
    }
}

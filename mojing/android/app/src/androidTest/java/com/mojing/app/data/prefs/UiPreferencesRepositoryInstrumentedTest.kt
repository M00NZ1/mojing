package com.mojing.app.data.prefs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiPreferencesRepositoryInstrumentedTest {

    @Test
    fun lastChatBranchSurvivesRepositoryRecreationAndMainClearsIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionId = 8_300_001L
        val firstRepository = UiPreferencesRepository(context)
        try {
            firstRepository.setLastChatBranch(sessionId, "branch-a")

            assertEquals("branch-a", UiPreferencesRepository(context).getLastChatBranch(sessionId))

            firstRepository.setLastChatBranch(sessionId, "main")
            assertEquals("main", UiPreferencesRepository(context).getLastChatBranch(sessionId))
        } finally {
            firstRepository.clearLastChatBranch(sessionId)
        }
    }
}

package com.mojing.app.data.prefs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
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

    @Test
    fun readingStyleSurvivesRepositoryRecreationAndInvalidFontFallsBackToSystem() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = UiPreferencesRepository(context)
        val oldFont = repository.chatFont.first()
        val oldNarratorItalic = repository.narratorItalic.first()
        try {
            repository.setChatFont("serif")
            repository.setNarratorItalic(true)

            val recreated = UiPreferencesRepository(context)
            assertEquals("serif", recreated.chatFont.first())
            assertEquals(true, recreated.narratorItalic.first())

            recreated.setChatFont("unsupported-font")
            assertEquals("system", UiPreferencesRepository(context).chatFont.first())
        } finally {
            repository.setChatFont(oldFont)
            repository.setNarratorItalic(oldNarratorItalic)
        }
    }
}

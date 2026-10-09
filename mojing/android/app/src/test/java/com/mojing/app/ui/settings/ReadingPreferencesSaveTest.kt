package com.mojing.app.ui.settings

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.data.local.dao.CostRecordDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.remote.BackendSystemProbeApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPreferencesSaveTest {
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val costRecordDao = mockk<CostRecordDao>(relaxed = true)
    private val systemProbeApi = mockk<BackendSystemProbeApi>(relaxed = true)
    private val uiPreferencesRepository = mockk<UiPreferencesRepository>(relaxed = true)
    private val worldTemplateDao = mockk<WorldTemplateDao>(relaxed = true)
    private val encyclopediaDao = mockk<EncyclopediaDao>(relaxed = true)
    private val dispatcher = StandardTestDispatcher()
    private val storedFont = MutableStateFlow("mono")
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { uiPreferencesRepository.chatDensity } returns flowOf("comfortable")
        every { uiPreferencesRepository.chatFont } returns storedFont
        every { uiPreferencesRepository.narratorItalic } returns flowOf(false)
        viewModel = SettingsViewModel(
            secureStorage,
            costRecordDao,
            systemProbeApi,
            uiPreferencesRepository,
            worldTemplateDao,
            encyclopediaDao,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun failureKeepsOldFlowAndRetryUsesOriginalRequest() = runTest(dispatcher) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.chatFont.collect() }
        advanceUntilIdle()
        assertEquals("mono", viewModel.chatFont.value)
        var attempts = 0
        coEvery { uiPreferencesRepository.setChatFont("serif") } coAnswers {
            attempts++
            if (attempts == 1) error("write failed")
            storedFont.value = "serif"
        }

        viewModel.setChatFont("serif")
        advanceUntilIdle()
        val notice = viewModel.readingPreferencesSaveNotice.value
        assertNotNull(notice)
        assertEquals("serif", notice!!.request.stringValue)
        assertEquals("mono", viewModel.chatFont.value)
        assertFalse(viewModel.readingPreferencesSaving.value)

        viewModel.retryReadingPreferences(notice.token)
        advanceUntilIdle()
        assertNull(viewModel.readingPreferencesSaveNotice.value)
        assertEquals("serif", viewModel.chatFont.value)
        coVerify(exactly = 2) { uiPreferencesRepository.setChatFont("serif") }
    }

    @Test
    fun consecutiveFailuresCreateFreshNoticeTokens() = runTest(dispatcher) {
        coEvery { uiPreferencesRepository.setChatDensity("reader") } throws IllegalStateException("write failed")

        viewModel.setChatDensity("reader")
        advanceUntilIdle()
        val first = viewModel.readingPreferencesSaveNotice.value!!.token
        viewModel.retryReadingPreferences(first)
        advanceUntilIdle()
        val second = viewModel.readingPreferencesSaveNotice.value!!.token

        assertNotEquals(first, second)
        coVerify(exactly = 2) { uiPreferencesRepository.setChatDensity("reader") }
    }

    @Test
    fun duplicateSelectionWhileSavingWritesOnce() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { uiPreferencesRepository.setChatFont("mono") } coAnswers {
            started.complete(Unit)
            release.await()
        }

        viewModel.setChatFont("mono")
        started.await()
        viewModel.setChatFont("serif")
        viewModel.setChatFont("mono")
        coVerify(exactly = 1) { uiPreferencesRepository.setChatFont("mono") }
        coVerify(exactly = 0) { uiPreferencesRepository.setChatFont("serif") }
        assertTrue(viewModel.readingPreferencesSaving.value)

        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.readingPreferencesSaving.value)
    }

    @Test
    fun oldNoticeCannotRetryAfterNewSelection() = runTest(dispatcher) {
        coEvery { uiPreferencesRepository.setChatFont("serif") } throws IllegalStateException("font failed")
        coEvery { uiPreferencesRepository.setChatDensity("reader") } returns Unit

        viewModel.setChatFont("serif")
        advanceUntilIdle()
        val oldToken = viewModel.readingPreferencesSaveNotice.value!!.token
        viewModel.setChatDensity("reader")
        advanceUntilIdle()

        assertNull(viewModel.readingPreferencesSaveNotice.value)
        viewModel.retryReadingPreferences(oldToken)
        advanceUntilIdle()
        coVerify(exactly = 1) { uiPreferencesRepository.setChatFont("serif") }
        coVerify(exactly = 1) { uiPreferencesRepository.setChatDensity("reader") }
    }

    @Test
    fun cancellationRestoresControlsWithoutFailureNotice() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { uiPreferencesRepository.setNarratorItalic(true) } coAnswers {
            started.complete(Unit)
            release.await()
        }

        viewModel.setNarratorItalic(true)
        started.await()
        assertTrue(viewModel.readingPreferencesSaving.value)
        viewModel.cancelReadingPreferencesSave()
        advanceUntilIdle()

        assertFalse(viewModel.readingPreferencesSaving.value)
        assertNull(viewModel.readingPreferencesSaveNotice.value)
        release.complete(Unit)
    }
}

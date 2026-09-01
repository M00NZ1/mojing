package com.mojing.app.ui.encyclopedia

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.dao.EntryRelationDao
import com.mojing.app.data.local.dao.TimelineEventDao
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.encyclopedia.WorldInfoAiConverter
import com.mojing.app.domain.generation.GenerationQueueProcessor
import com.mojing.app.domain.usecase.DeleteEncyclopediaEntryUseCase
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EncyclopediaDetailViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        encyclopediaDao: EncyclopediaDao,
        entryDao: EncyclopediaEntryDao = mockk(relaxed = true),
        relationDao: EntryRelationDao = mockk(relaxed = true),
        timelineDao: TimelineEventDao = mockk(relaxed = true),
    ): EncyclopediaDetailViewModel {
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        every { secureStorage.publicApiKey } returns ""
        val queue = mockk<GenerationQueueProcessor>(relaxed = true)
        every { queue.observeActiveForEncyclopedia(any()) } returns flowOf(emptyList())
        return EncyclopediaDetailViewModel(
            encyclopediaDao = encyclopediaDao,
            entryDao = entryDao,
            saveCharacterEntry = mockk<SaveCharacterEntryUseCase>(relaxed = true),
            deleteEncyclopediaEntry = mockk<DeleteEncyclopediaEntryUseCase>(relaxed = true),
            entryRelationDao = relationDao,
            timelineEventDao = timelineDao,
            secureStorage = secureStorage,
            generationQueueProcessor = queue,
            llmApiService = mockk<LlmApiService>(relaxed = true),
            worldInfoAiConverter = mockk<WorldInfoAiConverter>(relaxed = true),
        )
    }

    @Test
    fun missingEncyclopediaShowsRecoverableLoadError() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns null
        }
        val viewModel = createViewModel(encyclopediaDao)

        viewModel.load(3L)

        assertTrue(viewModel.state.value.isLoaded)
        assertNotNull(viewModel.state.value.loadError)
        assertEquals(null, viewModel.state.value.encyclopedia)
    }

    @Test
    fun readFailureDoesNotAppearAsAnEmptyEncyclopedia() = runTest(dispatcher) {
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } throws IllegalStateException("database unavailable")
        }
        val viewModel = createViewModel(encyclopediaDao)

        viewModel.load(3L)

        assertTrue(viewModel.state.value.isLoaded)
        assertNotNull(viewModel.state.value.loadError)
        assertTrue(viewModel.state.value.entries.isEmpty())
    }

    @Test
    fun successfulLoadPublishesOneCompleteSnapshot() = runTest(dispatcher) {
        val encyclopedia = EncyclopediaEntity(id = 3L, name = "雾海")
        val entry = EncyclopediaEntryEntity(id = 9L, encyclopediaId = 3L, title = "潮汐钟")
        val encyclopediaDao = mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns encyclopedia
        }
        val entryDao = mockk<EncyclopediaEntryDao>(relaxed = true) {
            coEvery { getByEncyclopedia(3L) } returns listOf(entry)
            coEvery { getSedimentEntries(3L) } returns emptyList()
        }
        val relationDao = mockk<EntryRelationDao>(relaxed = true) {
            coEvery { getByEncyclopedia(3L) } returns emptyList()
        }
        val timelineDao = mockk<TimelineEventDao>(relaxed = true) {
            coEvery { getByEncyclopedia(3L) } returns emptyList()
        }
        val viewModel = createViewModel(encyclopediaDao, entryDao, relationDao, timelineDao)

        viewModel.load(3L)

        assertTrue(viewModel.state.value.isLoaded)
        assertEquals(null, viewModel.state.value.loadError)
        assertEquals("雾海", viewModel.state.value.encyclopedia?.name)
        assertEquals(listOf(entry), viewModel.state.value.entries)
        assertFalse(viewModel.state.value.pickerEntries.isEmpty())
    }
}

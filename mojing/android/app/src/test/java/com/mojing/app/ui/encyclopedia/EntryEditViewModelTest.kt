package com.mojing.app.ui.encyclopedia

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.dao.EntryVersionDao
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.repository.ImageRepository
import com.mojing.app.domain.engine.AiCompleter
import com.mojing.app.domain.usecase.SaveCharacterEntryUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class EntryEditViewModelTest {
    @Test
    fun loadedConversationNoteKeepsItsLabelWhileEditingConfidence() = runTest {
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3,
            title = "线索", sourceSessionId = 7, confidence = "inferred")
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val viewModel = createViewModel(encyclopediaDao(), dao)
        viewModel.load(3, 8)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isConversationNote)
        viewModel.updateConfidence("confirmed")
        assertTrue(viewModel.state.value.isConversationNote)
    }

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
        entryDao: EncyclopediaEntryDao,
        versionDao: EntryVersionDao = mockk(relaxed = true),
        saveEntry: SaveCharacterEntryUseCase = mockk(relaxed = true),
        aiCompleter: AiCompleter = mockk(relaxed = true),
        publicKey: String = "",
    ): EntryEditViewModel {
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        every { secureStorage.publicApiKey } returns publicKey
        return EntryEditViewModel(
            entryDao = entryDao,
            saveCharacterEntry = saveEntry,
            encyclopediaDao = encyclopediaDao,
            entryVersionDao = versionDao,
            aiCompleter = aiCompleter,
            secureStorage = secureStorage,
            imageRepository = mockk<ImageRepository>(relaxed = true),
        )
    }

    private fun encyclopediaDao(entity: EncyclopediaEntity? = EncyclopediaEntity(id = 3L, name = "雾海")) =
        mockk<EncyclopediaDao> {
            coEvery { getById(3L) } returns entity
        }

    @Test
    fun missingEncyclopediaShowsLoadError() = runTest(dispatcher) {
        val viewModel = createViewModel(
            encyclopediaDao = encyclopediaDao(null),
            entryDao = mockk(relaxed = true),
        )

        viewModel.load(3L, 0L)

        assertTrue(viewModel.state.value.isLoaded)
        assertNotNull(viewModel.state.value.loadError)
        assertFalse(viewModel.state.value.isPersisted)
    }

    @Test
    fun missingExistingEntryDoesNotOpenANewDraft() = runTest(dispatcher) {
        val entryDao = mockk<EncyclopediaEntryDao> {
            coEvery { getById(9L) } returns null
        }
        val viewModel = createViewModel(encyclopediaDao(), entryDao)

        viewModel.load(3L, 9L)

        assertNotNull(viewModel.state.value.loadError)
        assertFalse(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)
    }

    @Test
    fun existingEntryTracksRealDraftChangesAndClearsAfterSave() = runTest(dispatcher) {
        val original = EncyclopediaEntryEntity(id = 9L, encyclopediaId = 3L, title = "雾港")
        val persisted = original.copy(title = "雾港城")
        val entryDao = mockk<EncyclopediaEntryDao> {
            coEvery { getById(9L) } returnsMany listOf(original, original)
        }
        val versionDao = mockk<EntryVersionDao> {
            coEvery { getByEntry(9L) } returns emptyList()
            coEvery { maxVersionForEntry(9L) } returns 0
            coEvery { insert(any()) } returns 1L
        }
        val save = mockk<SaveCharacterEntryUseCase> {
            coEvery { this@mockk.invoke(any()) } returns persisted
        }
        val viewModel = createViewModel(encyclopediaDao(), entryDao, versionDao, save)

        viewModel.load(3L, 9L)
        assertFalse(viewModel.state.value.isDirty)
        viewModel.updateTitle("雾港城")
        assertTrue(viewModel.state.value.isDirty)
        viewModel.updateTitle("雾港")
        assertFalse(viewModel.state.value.isDirty)

        viewModel.updateTitle("雾港城")
        viewModel.save()
        assertTrue(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)
    }

    @Test
    fun newEntryBecomesPersistedAfterFirstSave() = runTest(dispatcher) {
        val saved = EncyclopediaEntryEntity(id = 12L, encyclopediaId = 3L, title = "潮汐钟")
        val entryDao = mockk<EncyclopediaEntryDao>(relaxed = true)
        val versionDao = mockk<EntryVersionDao> {
            coEvery { getByEntry(12L) } returns emptyList()
        }
        val save = mockk<SaveCharacterEntryUseCase> {
            coEvery { this@mockk.invoke(any()) } returns saved
        }
        val viewModel = createViewModel(encyclopediaDao(), entryDao, versionDao, save)

        viewModel.load(3L, 0L)
        viewModel.updateTitle("潮汐钟")
        viewModel.save()

        assertTrue(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)
        assertEquals("潮汐钟", viewModel.state.value.title)
    }

    @Test
    fun saveKeepsChangesMadeWhilePersistenceIsInFlight() = runTest(dispatcher) {
        val original = EncyclopediaEntryEntity(id = 9L, encyclopediaId = 3L, title = "雾港")
        val persisted = original.copy(title = "雾港城")
        val saveRelease = CompletableDeferred<Unit>()
        val entryDao = mockk<EncyclopediaEntryDao> {
            coEvery { getById(9L) } returnsMany listOf(original, original)
        }
        val versionDao = mockk<EntryVersionDao> {
            coEvery { getByEntry(9L) } returns emptyList()
            coEvery { maxVersionForEntry(9L) } returns 0
            coEvery { insert(any()) } returns 1L
        }
        val save = mockk<SaveCharacterEntryUseCase> {
            coEvery { this@mockk.invoke(any()) } coAnswers {
                saveRelease.await()
                persisted
            }
        }
        val viewModel = createViewModel(encyclopediaDao(), entryDao, versionDao, save)

        viewModel.load(3L, 9L)
        viewModel.updateTitle("雾港城")
        viewModel.save()
        assertTrue(viewModel.state.value.isSaving)
        viewModel.updateTitle("雾港城·北区")
        saveRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals("雾港城·北区", viewModel.state.value.title)
        assertTrue(viewModel.state.value.isPersisted)
        assertTrue(viewModel.state.value.isDirty)
    }

    @Test
    fun aiCompletionFillsBlankContentWithoutOverwritingUserText() = runTest(dispatcher) {
        val ai = mockk<AiCompleter> {
            every { fieldKeysFor("encyclopedia_entry", "character") } returns setOf("alias", "race")
            coEvery { complete(any(), any(), any(), any()) } returns mapOf(
                "alias" to "潮生",
                "race" to "人类",
            )
        }
        val viewModel = createViewModel(
            encyclopediaDao = encyclopediaDao(),
            entryDao = mockk(relaxed = true),
            aiCompleter = ai,
            publicKey = "test-key",
        )

        viewModel.load(3L, 0L)
        viewModel.updateTitle("潮汐钟守")
        viewModel.aiComplete()

        assertTrue(viewModel.state.value.content.contains("【alias】潮生"))
        assertTrue(viewModel.state.value.content.contains("【race】人类"))
        assertTrue(viewModel.state.value.isDirty)

        viewModel.updateContent("用户写下的正文")
        viewModel.aiComplete()
        assertEquals("用户写下的正文", viewModel.state.value.content)
    }
}

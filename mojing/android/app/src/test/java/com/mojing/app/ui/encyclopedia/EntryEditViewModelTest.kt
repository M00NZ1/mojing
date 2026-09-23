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
import io.mockk.coVerify
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
    @Test fun confirmingConversationNoteKeepsSourcesAndRetriesFailedSave() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3, title = "雾港线索", confidence = "inferred",
            sourceSessionId = 42, sourceMessageId = 9, metaJson = """{"source_message_ids":[6,9]}""")
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val saver = mockk<SaveCharacterEntryUseCase>()
        coEvery { saver.saveEdited(any()) } throws IllegalStateException("write")
        val vm = createViewModel(encyclopediaDao(), dao, saveEntry = saver)
        vm.load(3, 8)
        vm.updateConfidence("confirmed")
        vm.save()
        assertTrue(vm.state.value.isDirty)
        assertNotNull(vm.state.value.saveError)
        vm.consumeSnackbar()
        assertNotNull(vm.state.value.saveError)
        assertEquals("confirmed", vm.state.value.confidence)
        coEvery { saver.saveEdited(any()) } answers { firstArg<EncyclopediaEntryEntity>() }
        vm.save()
        assertFalse(vm.state.value.isDirty)
        assertEquals(null, vm.state.value.saveError)
        assertTrue(vm.state.value.isConversationNote)
        coVerify(exactly = 2) { saver.saveEdited(match {
            it.confidence == "confirmed" && it.sourceSessionId == 42L && it.sourceMessageId == 9L && it.metaJson == entry.metaJson
        }) }
    }

    @Test fun sourcePagesReadIndividuallyAndSkipMissingSource() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3, sourceSessionId = 42, sourceMessageId = 9,
            metaJson = """{"source_message_ids":[6,7,9],"source_branch_id":"story"}""")
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val messages = mockk<com.mojing.app.data.local.dao.MessageDao>()
        coEvery { messages.getByIdInSession(any(), 42) } answers {
            val id = firstArg<Long>()
            if (id == 7L) null else com.mojing.app.data.local.entity.MessageEntity(id = id, sessionId = 42, content = "原文$id")
        }
        val vm = createViewModel(encyclopediaDao(), dao, messageDao = messages)
        vm.load(3, 8)
        vm.openSourcePreview()
        assertEquals(listOf(6L, 7L, 9L), vm.state.value.sourceMessageIds)
        assertEquals(2, vm.state.value.sourceIndex)
        coVerify(exactly = 0) { messages.getByIdInSession(6, 42) }
        vm.showSourceMessage(1)
        assertEquals(null, vm.state.value.sourceTarget)
        assertNotNull(vm.state.value.sourceError)
        vm.showSourceMessage(0)
        assertEquals("原文6", vm.state.value.sourceContent)
        assertEquals(EntrySourceTarget(42, 6, "story"), vm.state.value.sourceTarget)
        vm.showSourceMessage(-1)
        assertEquals(0, vm.state.value.sourceIndex)
        vm.closeSourcePreview()
        vm.showSourceMessage(2)
        assertFalse(vm.state.value.sourcePreviewOpen)
    }

    @Test fun sourcePreviewKeepsDraftAndAllowsRetry() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3, sourceSessionId = 42, sourceMessageId = 6,
            metaJson = """{"source_branch_id":"story-2"}""")
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val messages = mockk<com.mojing.app.data.local.dao.MessageDao>()
        coEvery { messages.getByIdInSession(6, 42) } throws IllegalStateException("read")
        val vm = createViewModel(encyclopediaDao(), dao, messageDao = messages)
        vm.load(3, 8)
        vm.updateTitle("未保存修改")
        assertTrue(vm.state.value.hasSourceMessage)
        vm.openSourcePreview()
        assertEquals("原文读取失败，请重试。", vm.state.value.sourceError)
        coEvery { messages.getByIdInSession(6, 42) } returns com.mojing.app.data.local.entity.MessageEntity(id = 6, sessionId = 42, content = "原始剧情")
        vm.openSourcePreview()
        assertEquals("原始剧情", vm.state.value.sourceContent)
        assertEquals(EntrySourceTarget(42, 6, "story-2"), vm.state.value.sourceTarget)
        vm.closeSourcePreview()
        assertFalse(vm.state.value.sourcePreviewOpen)
        assertEquals("未保存修改", vm.state.value.title)
        assertTrue(vm.state.value.isDirty)
        coEvery { messages.getByIdInSession(6, 42) } returns null
        vm.openSourcePreview()
        assertEquals("原始对话已不存在，百科内容仍保留。", vm.state.value.sourceError)
    }

    @Test fun closingSourcePreviewRejectsLateContent() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3, sourceSessionId = 42, sourceMessageId = 6)
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val messages = mockk<com.mojing.app.data.local.dao.MessageDao>()
        val gate = CompletableDeferred<Unit>()
        coEvery { messages.getByIdInSession(6, 42) } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            com.mojing.app.data.local.entity.MessageEntity(id = 6, sessionId = 42, content = "迟到原文")
        }
        val vm = createViewModel(encyclopediaDao(), dao, messageDao = messages)
        vm.load(3, 8)
        vm.openSourcePreview()
        vm.openSourcePreview()
        coVerify(exactly = 1) { messages.getByIdInSession(6, 42) }
        vm.closeSourcePreview()
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.sourcePreviewOpen)
        assertEquals(null, vm.state.value.sourceContent)
    }

    @Test
    fun loadingVersionRequiresDraftReplacementAndRejectsOtherEntries() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3, title = "当前正文")
        val version = com.mojing.app.data.local.entity.EntryVersionEntity(id = 6, entryId = 8, title = "历史正文")
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val versions = mockk<EntryVersionDao> { coEvery { getPage(8, any(), 11) } returns listOf(version) }
        val viewModel = createViewModel(encyclopediaDao(), dao, versions)
        viewModel.load(3, 8)
        viewModel.updateTitle("未保存正文")
        assertFalse(viewModel.applyVersionToForm(version))
        assertEquals("未保存正文", viewModel.state.value.title)
        assertEquals(version, viewModel.state.value.pendingVersion)
        viewModel.dismissVersionReplacement()
        assertEquals(null, viewModel.state.value.pendingVersion)
        assertFalse(viewModel.applyVersionToForm(version.copy(entryId = 99), true))
        assertTrue(viewModel.applyVersionToForm(version, true))
        assertEquals("历史正文", viewModel.state.value.title)
        assertTrue(viewModel.state.value.isDirty)
        assertEquals(null, viewModel.state.value.pendingVersion)
    }

    @Test
    fun versionPagesReplaceWindowAndKeepDraft() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 8, encyclopediaId = 3, title = "百科")
        val dao = mockk<EncyclopediaEntryDao> { coEvery { getById(8) } returns entry }
        val rows = (25L downTo 1L).map {
            com.mojing.app.data.local.entity.EntryVersionEntity(id = it, entryId = 8, version = it.toInt())
        }
        val versions = mockk<EntryVersionDao> {
            coEvery { getPage(8, any(), 11) } coAnswers {
                rows.filter { it.id < secondArg<Long>() }.take(11)
            }
        }
        val viewModel = createViewModel(encyclopediaDao(), dao, versions)
        viewModel.load(3, 8)
        assertEquals(10, viewModel.state.value.versions.size)
        viewModel.updateTitle("未保存标题")
        viewModel.loadVersionPage(true)
        assertEquals(15L, viewModel.state.value.versions.first().id)
        assertEquals(10, viewModel.state.value.versions.size)
        viewModel.loadVersionPage(true)
        assertEquals(5, viewModel.state.value.versions.size)
        assertFalse(viewModel.state.value.hasOlderVersions)
        viewModel.loadVersionPage(false)
        assertEquals(25L, viewModel.state.value.versions.first().id)
        assertEquals("未保存标题", viewModel.state.value.title)
        assertTrue(viewModel.state.value.isDirty)
    }

    @Test
    fun repeatedSaveCreatesOneEntryAndFailureAllowsRetry() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        var failSave = true
        val save = mockk<SaveCharacterEntryUseCase> {
            coEvery { this@mockk.saveEdited(any()) } coAnswers {
                release.await()
                if (failSave) error("write failed")
                firstArg<EncyclopediaEntryEntity>().copy(id = 12)
            }
        }
        val viewModel = createViewModel(encyclopediaDao(), mockk(relaxed = true), saveEntry = save)
        viewModel.load(3, 0)
        viewModel.updateTitle("潮汐钟")
        viewModel.save()
        viewModel.save()
        coVerify(exactly = 1) { save.saveEdited(any()) }
        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isSaving)
        assertTrue(viewModel.state.value.isDirty)
        failSave = false
        viewModel.save()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isPersisted)
        assertFalse(viewModel.state.value.isDirty)
        viewModel.save()
        coVerify(exactly = 2) { save.saveEdited(any()) }
    }

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
        messageDao: com.mojing.app.data.local.dao.MessageDao = mockk(relaxed = true),
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
            messageDao = messageDao,
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
            coEvery { getPage(9L, any(), any()) } returns emptyList()
            coEvery { maxVersionForEntry(9L) } returns 0
            coEvery { insert(any()) } returns 1L
        }
        val save = mockk<SaveCharacterEntryUseCase> {
            coEvery { this@mockk.saveEdited(any()) } returns persisted
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
    fun openingEntryDoesNotWaitForHistoryAndHistoryFailureKeepsDraft() = runTest(dispatcher) {
        val entry = EncyclopediaEntryEntity(id = 9L, encyclopediaId = 3L, title = "雾港", content = "已保存正文")
        val entries = mockk<EncyclopediaEntryDao> { coEvery { getById(9L) } returns entry }
        val release = CompletableDeferred<Unit>()
        val versions = mockk<EntryVersionDao> {
            coEvery { getPage(9L, any(), any()) } coAnswers {
                release.await()
                throw IllegalStateException("history unavailable")
            }
        }
        val vm = createViewModel(encyclopediaDao(), entries, versions)
        vm.load(3L, 9L)
        assertTrue(vm.state.value.isLoaded)
        assertTrue(vm.state.value.isPersisted)
        assertTrue(vm.state.value.isLoadingVersions)
        assertEquals("已保存正文", vm.state.value.content)
        vm.updateContent("读取期间的修改")
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(null, vm.state.value.loadError)
        assertNotNull(vm.state.value.versionError)
        assertEquals("读取期间的修改", vm.state.value.content)
        assertTrue(vm.state.value.isDirty)
        coEvery { versions.getPage(9L, any(), any()) } returns emptyList()
        vm.loadVersionPage(older = false)
        assertEquals(null, vm.state.value.versionError)
        assertEquals("读取期间的修改", vm.state.value.content)
        coVerify(exactly = 1) { entries.getById(9L) }
    }

    @Test
    fun versionRefreshFailureKeepsCommittedEntryAndRetriesOnlyRead() = runTest(dispatcher) {
        val saved = EncyclopediaEntryEntity(id = 12L, encyclopediaId = 3L, title = "潮汐钟")
        val versions = mockk<EntryVersionDao> {
            coEvery { getPage(12L, any(), any()) } throws IllegalStateException("read")
        }
        val saver = mockk<SaveCharacterEntryUseCase> {
            coEvery { saveEdited(any()) } returns saved
        }
        val vm = createViewModel(encyclopediaDao(), mockk(relaxed = true), versions, saver)
        vm.load(3L, 0L)
        vm.updateTitle("潮汐钟")
        vm.save()
        assertTrue(vm.state.value.isPersisted)
        assertFalse(vm.state.value.isDirty)
        assertFalse(vm.state.value.isSaving)
        assertEquals(null, vm.state.value.saveError)
        assertNotNull(vm.state.value.versionError)
        vm.save()
        vm.updateContent("刷新时保留的新修改")
        coEvery { versions.getPage(12L, any(), any()) } returns emptyList()
        vm.loadVersionPage(older = false)
        assertEquals(null, vm.state.value.versionError)
        assertEquals("刷新时保留的新修改", vm.state.value.content)
        assertTrue(vm.state.value.isDirty)
        coVerify(exactly = 1) { saver.saveEdited(any()) }
    }

    @Test
    fun newEntryBecomesPersistedAfterFirstSave() = runTest(dispatcher) {
        val saved = EncyclopediaEntryEntity(id = 12L, encyclopediaId = 3L, title = "潮汐钟")
        val entryDao = mockk<EncyclopediaEntryDao>(relaxed = true)
        val versionDao = mockk<EntryVersionDao> {
            coEvery { getPage(12L, any(), any()) } returns emptyList()
        }
        val save = mockk<SaveCharacterEntryUseCase> {
            coEvery { this@mockk.saveEdited(any()) } returns saved
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
            coEvery { getPage(9L, any(), any()) } returns emptyList()
            coEvery { maxVersionForEntry(9L) } returns 0
            coEvery { insert(any()) } returns 1L
        }
        val save = mockk<SaveCharacterEntryUseCase> {
            coEvery { this@mockk.saveEdited(any()) } coAnswers {
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

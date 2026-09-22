package com.mojing.app.ui.world

import com.mojing.app.data.WorldEditDraft
import com.mojing.app.data.WorldEditDraftStore
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.entity.EncyclopediaEntity
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorldSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dao = mockk<EncyclopediaDao>(relaxed = true)
    private val drafts = mockk<WorldEditDraftStore>(relaxed = true)
    private val world = EncyclopediaEntity(id = 1L, name = "原世界", worldPrompt = "原设定", updatedAt = 10L)
    private val draft = WorldEditDraft("草稿世界", "简介", "新设定", "自由剧情", "规则")

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { dao.getById(1L) } returns world
        every { drafts.load(any()) } returns null
    }
    @After fun cleanup() { Dispatchers.resetMain() }

    private suspend fun loaded(): WorldSettingsViewModel {
        val vm = WorldSettingsViewModel(dao, drafts)
        vm.load(1L)
        vm.state.first { !it.loading }
        return vm
    }

    @Test fun restoredDraftNeedsExplicitSaveAndSurvivesSaveConflict() = runTest(dispatcher) {
        every { drafts.load(1L) } returns draft
        val vm = loaded()
        assertEquals("原世界", vm.state.value.name)
        vm.restoreDraft()
        assertEquals("新设定", vm.state.value.worldPrompt)
        assertTrue(vm.state.value.dirty)
        coVerify(exactly = 0) { dao.updateWorldSettings(any(), any(), any(), any(), any(), any(), any(), any()) }
        coEvery { dao.updateWorldSettings(any(), any(), any(), any(), any(), any(), any(), any()) } returns 0
        vm.save()
        advanceUntilIdle()
        assertNotNull(vm.state.value.saveError)
        assertEquals("新设定", vm.state.value.worldPrompt)
        verify(exactly = 0) { drafts.clear(1L) }
    }

    @Test fun successfulSaveRemainsSuccessfulWhenDraftCleanupFails() = runTest(dispatcher) {
        val vm = loaded()
        vm.updateName("新名称")
        coEvery { dao.updateWorldSettings(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        every { drafts.clear(1L) } throws IllegalStateException("storage failure")
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.saved)
        assertFalse(vm.state.value.dirty)
        assertNull(vm.state.value.saveError)
        assertNotNull(vm.state.value.draftError)
        every { drafts.clear(1L) } just Runs
        vm.retryDraft()
        assertNull(vm.state.value.draftError)
    }

    @Test fun failedDraftWriteRetainsInputAndCanRetry() = runTest(dispatcher) {
        val vm = loaded()
        every { drafts.save(1L, any()) } throws IllegalStateException("storage failure")
        vm.updateWorldPrompt("未暂存的内容")
        assertEquals("未暂存的内容", vm.state.value.worldPrompt)
        assertNotNull(vm.state.value.draftError)
        every { drafts.save(1L, any()) } just Runs
        vm.retryDraft()
        assertNull(vm.state.value.draftError)
        verify { drafts.save(1L, match { it.prompt == "未暂存的内容" }) }
    }

    @Test fun failedDiscardKeepsRecoverableDraft() = runTest(dispatcher) {
        every { drafts.load(1L) } returns draft
        val vm = loaded()
        every { drafts.clear(1L) } throws IllegalStateException("storage failure")
        vm.discardDraft()
        assertEquals(draft, vm.state.value.recoverableDraft)
        assertNotNull(vm.state.value.draftError)
    }

    @Test fun switchingWorldsDoesNotWriteDraftToPreviousWorld() = runTest(dispatcher) {
        val vm = loaded()
        vm.updateName("世界一草稿")
        coEvery { dao.getById(2L) } returns world.copy(id = 2L, name = "世界二")
        vm.load(2L)
        vm.state.first { !it.loading && it.world?.id == 2L }
        vm.updateName("世界二草稿")
        verify { drafts.save(1L, match { it.name == "世界一草稿" }) }
        verify { drafts.save(2L, match { it.name == "世界二草稿" }) }
        verify(exactly = 0) { drafts.save(1L, match { it.name == "世界二草稿" }) }
    }

    @Test fun invalidWorldDoesNotStayLoading() {
        val vm = WorldSettingsViewModel(dao, drafts)
        vm.load(0L)
        assertFalse(vm.state.value.loading)
        assertNotNull(vm.state.value.error)
    }
}

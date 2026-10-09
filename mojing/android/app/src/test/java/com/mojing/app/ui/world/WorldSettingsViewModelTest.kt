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
        coEvery { drafts.load(any()) } returns null
    }
    @After fun cleanup() { Dispatchers.resetMain() }

    private suspend fun loaded(): WorldSettingsViewModel {
        val vm = WorldSettingsViewModel(dao, drafts)
        vm.load(1L)
        vm.state.first { !it.loading }
        return vm
    }

    private fun savedDraft() = WorldEditDraft(world.name, world.description, world.worldPrompt, world.gameplayMode, world.antiCheatPrompt)

    @Test fun identicalLeftoverDraftDoesNotBlockSavedWorld() = runTest(dispatcher) {
        coEvery { drafts.load(1L) } returns savedDraft()
        val vm = loaded()
        advanceUntilIdle()
        assertNull(vm.state.value.recoverableDraft)
        assertFalse(vm.state.value.dirty)
        coVerify(exactly = 1) { drafts.clear(1L) }
        vm.updateName("可编辑的世界")
        assertEquals("可编辑的世界", vm.state.value.name)
        assertTrue(vm.state.value.dirty)
    }

    @Test fun identicalDraftCleanupFailureKeepsWorldUsableAndCanRetry() = runTest(dispatcher) {
        coEvery { drafts.load(1L) } returns savedDraft()
        coEvery { drafts.clear(1L) } throws IllegalStateException("storage failure")
        val vm = loaded()
        advanceUntilIdle()
        assertNull(vm.state.value.recoverableDraft)
        assertNull(vm.state.value.error)
        assertEquals(world, vm.state.value.world)
        assertNotNull(vm.state.value.draftError)
        coEvery { drafts.clear(1L) } returns Unit
        vm.retryDraft()
        advanceUntilIdle()
        assertNull(vm.state.value.draftError)
        vm.updateWorldPrompt("仍可编辑")
        assertEquals("仍可编辑", vm.state.value.worldPrompt)
    }

    @Test fun differenceInAnyWorldFieldStillRequiresDraftDecision() = runTest(dispatcher) {
        val saved = savedDraft()
        val variants = listOf(saved.copy(name = "新名称"), saved.copy(description = "新简介"),
            saved.copy(prompt = "新设定"), saved.copy(gameplay = "新玩法"), saved.copy(rules = "新规则"))
        for (variant in variants) {
            coEvery { drafts.load(1L) } returns variant
            val vm = loaded()
            advanceUntilIdle()
            assertEquals(variant, vm.state.value.recoverableDraft)
            vm.updateName("不可覆盖")
            assertEquals(world.name, vm.state.value.name)
        }
        coVerify(exactly = 0) { drafts.clear(1L) }
    }

    @Test fun restoredDraftNeedsExplicitSaveAndSurvivesSaveConflict() = runTest(dispatcher) {
        coEvery { drafts.load(1L) } returns draft
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
        coVerify(exactly = 0) { drafts.clear(1L) }
    }

    @Test fun successfulSaveRemainsSuccessfulWhenDraftCleanupFails() = runTest(dispatcher) {
        val vm = loaded()
        vm.updateName("新名称")
        coEvery { dao.updateWorldSettings(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { drafts.clear(1L) } throws IllegalStateException("storage failure")
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.saved)
        assertFalse(vm.state.value.dirty)
        assertNull(vm.state.value.saveError)
        assertNotNull(vm.state.value.draftError)
        coEvery { drafts.clear(1L) } returns Unit
        vm.retryDraft()
        advanceUntilIdle()
        assertNull(vm.state.value.draftError)
    }

    @Test fun failedDraftWriteRetainsInputAndCanRetry() = runTest(dispatcher) {
        val vm = loaded()
        coEvery { drafts.save(1L, any()) } throws IllegalStateException("storage failure")
        vm.updateWorldPrompt("未暂存的内容")
        advanceUntilIdle()
        assertEquals("未暂存的内容", vm.state.value.worldPrompt)
        assertNotNull(vm.state.value.draftError)
        coEvery { drafts.save(1L, any()) } returns Unit
        vm.retryDraft()
        advanceUntilIdle()
        assertNull(vm.state.value.draftError)
        coVerify { drafts.save(1L, match { it.prompt == "未暂存的内容" }) }
    }

    @Test fun failedDiscardKeepsRecoverableDraft() = runTest(dispatcher) {
        coEvery { drafts.load(1L) } returns draft
        val vm = loaded()
        coEvery { drafts.clear(1L) } throws IllegalStateException("storage failure")
        vm.discardDraft()
        advanceUntilIdle()
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
        advanceUntilIdle()
        coVerify { drafts.save(2L, match { it.name == "世界二草稿" }) }
        coVerify(exactly = 0) { drafts.save(1L, match { it.name == "世界二草稿" }) }
    }

    @Test fun flushFailureDoesNotAuthorizeLeavingAndKeepsInput() = runTest(dispatcher) {
        val vm = loaded()
        vm.updateWorldPrompt("离页前内容")
        advanceUntilIdle()
        coEvery { drafts.save(1L, any()) } throws IllegalStateException("commit failed")
        var left = false
        vm.flushDraft { left = it }
        advanceUntilIdle()
        assertFalse(left)
        assertEquals("离页前内容", vm.state.value.worldPrompt)
        assertNotNull(vm.state.value.draftError)
        assertFalse(vm.state.value.draftFlushing)
    }

    @Test fun invalidWorldDoesNotStayLoading() {
        val vm = WorldSettingsViewModel(dao, drafts)
        vm.load(0L)
        assertFalse(vm.state.value.loading)
        assertNotNull(vm.state.value.error)
    }
}

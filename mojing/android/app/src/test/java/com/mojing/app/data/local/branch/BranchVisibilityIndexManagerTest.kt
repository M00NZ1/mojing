package com.mojing.app.data.local.branch

import com.mojing.app.data.local.dao.SessionBranchDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BranchVisibilityIndexManagerTest {

    private val branchDao = mockk<SessionBranchDao>()

    @Test
    fun successfulRepairIsOwnedOncePerProcess() = runTest {
        var calls = 0
        coEvery { branchDao.repairVisibilitySegmentsIfNeeded() } coAnswers {
            calls += 1
            false
        }
        val manager = BranchVisibilityIndexManager(branchDao)

        manager.ensureReady()
        manager.ensureReady()

        assertEquals(1, calls)
        coVerify(exactly = 1) { branchDao.repairVisibilitySegmentsIfNeeded() }
    }

    @Test
    fun failedRepairCanBeRetried() = runTest {
        var calls = 0
        coEvery { branchDao.repairVisibilitySegmentsIfNeeded() } coAnswers {
            calls += 1
            if (calls == 1) error("repair failed")
            true
        }
        val manager = BranchVisibilityIndexManager(branchDao)

        runCatching { manager.ensureReady() }
        manager.ensureReady()
        manager.ensureReady()

        assertEquals(2, calls)
        coVerify(exactly = 2) { branchDao.repairVisibilitySegmentsIfNeeded() }
    }
}

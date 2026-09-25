package com.mojing.app.data.local.branch

import com.mojing.app.data.local.dao.SessionBranchDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BranchVisibilityIndexManager @Inject constructor(
    private val branchDao: SessionBranchDao,
) {
    private val repairMutex = Mutex()

    @Volatile
    private var ready = false

    /**
     * Makes the derived branch visibility segments safe to read for this process.
     *
     * The flag is intentionally process-local: the DAO still verifies the actual
     * database state, while this barrier prevents concurrent callers from starting
     * the same repair and avoids re-running it on every screen entry. A failed or
     * cancelled repair leaves [ready] false so the next caller can retry.
     */
    suspend fun ensureReady() {
        if (ready) return

        withContext(Dispatchers.IO) {
            if (ready) return@withContext
            repairMutex.withLock {
                if (ready) return@withLock
                branchDao.repairVisibilitySegmentsIfNeeded()
                ready = true
            }
        }
    }
}

package com.mojing.app.data.local.branch

import com.mojing.app.data.local.dao.SessionBranchDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BranchVisibilityIndexManager @Inject constructor(
    private val branchDao: SessionBranchDao,
) {
    suspend fun repairIfNeeded(): Boolean = branchDao.repairVisibilitySegmentsIfNeeded()
}

package com.mojing.app.data.local.branch

import com.mojing.app.data.local.entity.BranchVisibilitySegmentEntity
import com.mojing.app.data.local.entity.SessionBranchEntity

/** 把分支父链折叠为少量可索引区段；缺父或循环沿用旧查询的“到此停止”语义。 */
object BranchVisibilityPlanner {
    private const val MAIN_BRANCH = "main"

    fun plan(sessionId: Long, branches: List<SessionBranchEntity>): List<BranchVisibilitySegmentEntity> {
        require(sessionId > 0L)
        val sessionBranches = branches.filter { it.sessionId == sessionId }
        val byId = sessionBranches.groupBy(SessionBranchEntity::branchId)
        require(byId.none { (branchId, rows) -> branchId.isBlank() || branchId == MAIN_BRANCH || rows.size > 1 })
        val lookup = byId.mapValues { (_, rows) -> rows.single() }

        return buildList {
            sessionBranches.forEach { target ->
                add(
                    BranchVisibilitySegmentEntity(
                        sessionId = sessionId,
                        targetBranchId = target.branchId,
                        sourceBranchId = target.branchId,
                        maxMessageId = Long.MAX_VALUE,
                    ),
                )

                val visited = mutableSetOf(target.branchId)
                var child = target
                while (child.parentBranchId.isNotBlank() && visited.add(child.parentBranchId)) {
                    val parentId = child.parentBranchId
                    add(
                        BranchVisibilitySegmentEntity(
                            sessionId = sessionId,
                            targetBranchId = target.branchId,
                            sourceBranchId = parentId,
                            maxMessageId = child.sourceMessageId,
                        ),
                    )
                    if (parentId == MAIN_BRANCH) break
                    child = lookup[parentId] ?: break
                }
            }
        }
    }

    fun planAll(branches: List<SessionBranchEntity>): List<BranchVisibilitySegmentEntity> =
        branches.groupBy(SessionBranchEntity::sessionId)
            .toSortedMap()
            .flatMap { (sessionId, rows) -> plan(sessionId, rows) }
}

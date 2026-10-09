package com.mojing.app.data.local.branch

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.data.local.entity.SessionContextMemoryEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Call inside the branch transaction, after all replacements and selections are final. */
object BranchContextMemoryInheritance {
    // Match the existing UCM source batch size: message bodies may be long chapters.
    const val PAGE_SIZE = 40

    suspend fun isCompatible(
        branch: SessionBranchEntity,
        memory: SessionContextMemoryEntity,
        readPage: suspend (branchId: String, afterId: Long, limit: Int) -> List<MessageEntity>,
    ): Boolean {
        if (!memory.isValid || memory.memoryVersion != 1 || memory.sessionId != branch.sessionId ||
            memory.branchId != branch.parentBranchId || branch.branchId == branch.parentBranchId ||
            memory.sourceStartMessageId <= 0L || memory.sourceEndMessageId < memory.sourceStartMessageId ||
            memory.sourceEndMessageId > branch.sourceMessageId
        ) return false

        var cursor = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val parent = readPage(branch.parentBranchId, cursor, PAGE_SIZE)
            val child = readPage(branch.branchId, cursor, PAGE_SIZE)
            // Equality includes source ID/branch, content, structured content and swipe group.
            if (parent != child) return false
            if (parent.isEmpty()) return cursor == memory.sourceEndMessageId
            if (cursor == 0L && parent.first().id != memory.sourceStartMessageId) return false
            if (parent.size > PAGE_SIZE || parent.any { it.sessionId != branch.sessionId ||
                    it.id <= cursor || it.id > memory.sourceEndMessageId } ||
                parent.zipWithNext().any { (a, b) -> a.id >= b.id }
            ) return false
            cursor = parent.last().id
        }
    }
}

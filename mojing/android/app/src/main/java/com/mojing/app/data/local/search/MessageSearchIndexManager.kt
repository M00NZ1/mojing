package com.mojing.app.data.local.search

import com.mojing.app.data.local.dao.MessageDao
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

/** 后台分批补齐可重建的消息搜索索引；同一进程只允许一个重建 owner。 */
@Singleton
class MessageSearchIndexManager @Inject constructor(
    private val messageDao: MessageDao,
) {
    private val rebuildMutex = Mutex()
    private val pendingSessionSearches = AtomicInteger()

    suspend fun rebuildIfNeeded() = withContext(Dispatchers.IO) {
        while (true) {
            currentCoroutineContext().ensureActive()
            // A search needs only its current session. Let it take the next batch slot.
            while (pendingSessionSearches.get() > 0) delay(10)
            val result = rebuildMutex.withLock {
                // A search may have arrived while the background rebuild waited for this lock.
                if (pendingSessionSearches.get() > 0) null else messageDao.rebuildSearchIndexBatch()
            }
            if (result?.isComplete == true) return@withContext
            yield()
        }
    }

    suspend fun ensureSessionReady(sessionId: Long) = withContext(Dispatchers.IO) {
        pendingSessionSearches.incrementAndGet()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val result = rebuildMutex.withLock {
                    messageDao.rebuildSessionSearchIndexBatch(sessionId)
                }
                if (result.isComplete) return@withContext
                yield()
            }
        } finally {
            pendingSessionSearches.decrementAndGet()
        }
    }
}

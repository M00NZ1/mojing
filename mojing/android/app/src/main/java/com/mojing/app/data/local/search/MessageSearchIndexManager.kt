package com.mojing.app.data.local.search

import com.mojing.app.data.local.dao.MessageDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
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

    suspend fun rebuildIfNeeded() = withContext(Dispatchers.IO) {
        rebuildMutex.withLock {
            while (true) {
                currentCoroutineContext().ensureActive()
                val result = messageDao.rebuildSearchIndexBatch()
                if (result.isComplete) return@withLock
                yield()
            }
        }
    }
}

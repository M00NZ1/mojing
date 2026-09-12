package com.mojing.app.data

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.ConfigEntity
import com.mojing.app.domain.story.StoryOpeningDraft
import com.mojing.app.domain.story.StoryOpeningDraftCodec
import com.mojing.app.domain.story.StoryOpeningRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

class UnreadableStoryDraft(val raw: String) : IllegalStateException("Story draft cannot be read")

@Singleton
class StoryOpeningDraftStore @Inject constructor(private val database: AppDatabase) {
    suspend fun load(): StoryOpeningRecord? = withContext(Dispatchers.IO) {
        val raw = database.configDao().get(StoryOpeningDraftCodec.KEY)?.valueJson ?: return@withContext null
        val record = try { StoryOpeningDraftCodec.decode(raw) } catch (_: Exception) { throw UnreadableStoryDraft(raw) }
        if (record is StoryOpeningRecord.Saved) record.copy(sessionExists = database.sessionDao().getById(record.sessionId) != null)
        else record
    }

    suspend fun persist(draft: StoryOpeningDraft) = withContext(Dispatchers.IO) {
        val encoded = StoryOpeningDraftCodec.encode(StoryOpeningRecord.Pending(draft))
        StoryOpeningDraftCodec.decode(encoded)
        database.withTransaction {
            val existing = database.configDao().get(StoryOpeningDraftCodec.KEY)?.valueJson
            if (existing != null) {
                val record = StoryOpeningDraftCodec.decode(existing)
                check(record.id == draft.id && (record is StoryOpeningRecord.Saved || existing == encoded)) {
                    "Another story draft is already stored"
                }
            } else database.configDao().set(ConfigEntity(StoryOpeningDraftCodec.KEY, encoded))
        }
    }

    suspend fun discard(id: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val raw = database.configDao().get(StoryOpeningDraftCodec.KEY)?.valueJson ?: return@withTransaction
            val record = StoryOpeningDraftCodec.decode(raw)
            check(record.id == id && record is StoryOpeningRecord.Pending) { "Story draft changed" }
            database.configDao().delete(StoryOpeningDraftCodec.KEY)
        }
    }

    suspend fun clearSavedReceipt(id: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val raw = database.configDao().get(StoryOpeningDraftCodec.KEY)?.valueJson ?: return@withTransaction
            val record = StoryOpeningDraftCodec.decode(raw)
            check(record.id == id && record is StoryOpeningRecord.Saved) { "Story draft changed" }
            database.configDao().delete(StoryOpeningDraftCodec.KEY)
        }
    }

    suspend fun discardUnreadable(expectedRaw: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val raw = database.configDao().get(StoryOpeningDraftCodec.KEY)?.valueJson ?: return@withTransaction
            check(raw == expectedRaw) { "Story draft changed" }
            database.configDao().delete(StoryOpeningDraftCodec.KEY)
        }
    }
}

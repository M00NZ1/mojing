package com.mojing.app.domain.engine

import androidx.room.withTransaction
import com.google.gson.Gson
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.dao.*
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.MessageEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

data class SedimentSnapshot(val encyclopediaId: Long, val sessionId: Long, val branchId: String, val revision: Long, val sources: List<MessageEntity>)

class SedimentStore @Inject constructor(private val database: AppDatabase) {
    suspend fun read(encyclopediaId: Long, sessionId: Long, branchId: String, sources: List<MessageEntity>): SedimentSnapshot? = database.withTransaction {
        if (sources.isEmpty() || sources.size > 10 || sources.any { it.sessionId != sessionId || it.id <= 0 }) return@withTransaction null
        val snapshot = SedimentSnapshot(encyclopediaId, sessionId, branchId,
            database.messageDao().getContextMemoryForInvalidation(sessionId, branchId)?.revision ?: 0L, sources)
        snapshot.takeIf { valid(it, database.messageDao(), database.sessionWorldDao(), database.encyclopediaDao()) }
    }

    suspend fun commit(snapshot: SedimentSnapshot, entries: List<EncyclopediaEntryEntity>): Int = database.withTransaction {
        persistValidated(snapshot, entries, database.messageDao(), database.sessionWorldDao(), database.encyclopediaDao(), database.encyclopediaEntryDao())
    }

    internal companion object {
        private suspend fun valid(snapshot: SedimentSnapshot, messages: MessageDao, worlds: SessionWorldDao, encyclopedias: EncyclopediaDao): Boolean {
            val (encyclopediaId, sessionId, branchId, revision, sources) = snapshot
            if (branchId.isBlank() || sources.size !in 1..10 || sources.any { it.sessionId != sessionId || it.id <= 0 }) return false
            val world = worlds.getBySession(sessionId) ?: return false
            if (!world.autoSedimentEnabled || world.encyclopediaId != encyclopediaId || encyclopedias.getById(encyclopediaId) == null) return false
            if ((messages.getContextMemoryForInvalidation(sessionId, branchId)?.revision ?: 0L) != revision) return false
            val ids = sources.map { it.id }
            if (ids.distinct().size != ids.size) return false
            val current = if (branchId == "main") messages.getMainEventSources(sessionId, ids) else messages.getVisibleEventSources(sessionId, branchId, ids)
            fun versions(rows: List<MessageEntity>) = rows.associate { it.id to listOf(it.content, it.structuredContentJson, it.speakerType, it.characterId, it.branchId) }
            return versions(current) == versions(sources)
        }

        suspend fun persistValidated(snapshot: SedimentSnapshot, entries: List<EncyclopediaEntryEntity>, messages: MessageDao, worlds: SessionWorldDao, encyclopedias: EncyclopediaDao, dao: EncyclopediaEntryDao): Int {
            require(entries.size <= 8 && entries.all { it.id == 0L && it.title.isNotBlank() && it.content.isNotBlank() })
            if (!valid(snapshot, messages, worlds, encyclopedias)) return 0
            val meta = Gson().toJson(linkedMapOf("sediment_version" to 1, "source_branch_id" to snapshot.branchId,
                "source_message_ids" to snapshot.sources.map { it.id }, "source_revision" to snapshot.revision))
            var added = 0
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                if (dao.findSedimentDuplicate(snapshot.encyclopediaId, snapshot.sessionId, entry.title, entry.content, meta) != null) continue
                // 自动推断仅新增条目；角色设定的修改由明确的编辑保存操作负责。
                dao.upsert(entry.copy(encyclopediaId = snapshot.encyclopediaId, confidence = "inferred",
                    sourceSessionId = snapshot.sessionId, sourceMessageId = snapshot.sources.last().id, metaJson = meta))
                added++
            }
            currentCoroutineContext().ensureActive()
            return added
        }
    }
}

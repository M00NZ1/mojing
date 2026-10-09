package com.mojing.app.domain.usecase

import androidx.room.withTransaction
import com.google.gson.Gson
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.dao.WorldLoreMergeRow
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.WorldLoreEntryEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.data.local.entity.LegacyLoreMappingEntity
import com.mojing.app.data.local.entity.LegacyWorldMappingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.security.MessageDigest

enum class WorldMergeField { DESCRIPTION, WORLD_PROMPT, GAMEPLAY_MODE, ANTI_CHEAT_PROMPT, COVER }

data class WorldTemplateMergePreview(
    val templateId: Long,
    val targetWorld: EncyclopediaEntity,
    val template: WorldTemplateEntity,
    val sourceToken: String,
    val targetToken: String,
    val loreTotal: Int,
    val loreToAdd: Int,
    val loreConflicts: Int,
)

data class WorldTemplateMergeLoreRow(
    val id: Long,
    val title: String,
    val entryType: String,
    val conflict: Boolean,
)

data class WorldTemplateMergeResult(
    val world: EncyclopediaEntity,
    val added: Int,
    val skipped: Int,
)

class WorldTemplateAlreadyMappedException(val mappedWorldId: Long) :
    IllegalStateException("世界模板已归入其他世界: $mappedWorldId")

class WorldTemplateMergePreviewStaleException :
    IllegalStateException("世界模板或目标世界已变化，请重新预览")

/** Previews and transactionally merges a legacy template into a user-selected world. */
@Singleton
class MergeWorldTemplateUseCase @Inject constructor(
    private val database: AppDatabase,
    private val promote: PromoteWorldTemplateUseCase,
) {
    suspend fun preview(templateId: Long, targetWorldId: Long): WorldTemplateMergePreview =
        database.withTransaction {
            val template = database.worldTemplateDao().getById(templateId)
                ?: error("世界模板不存在: $templateId")
            val target = database.encyclopediaDao().getById(targetWorldId)
                ?: error("百科世界不存在: $targetWorldId")
            checkMapping(templateId, targetWorldId)
            snapshot(template, target)
        }

    suspend fun lorePage(
        preview: WorldTemplateMergePreview,
        afterId: Long = 0L,
        limit: Int = 24,
    ): List<WorldTemplateMergeLoreRow> {
        val pageLimit = limit.coerceIn(1, PAGE_LIMIT)
        val loreDao = database.worldLoreEntryDao()
        val page = loreDao.getMergePage(preview.templateId, afterId, pageLimit)
        val plan = buildPageConflictPlan(preview.templateId, preview.targetWorld.id, page)
        return page.map { lore ->
            WorldTemplateMergeLoreRow(
                id = lore.id,
                title = lore.title,
                entryType = promote.mapEntryType(lore.entryType),
                conflict = plan.isConflict(lore.id, lore.title),
            )
        }
    }

    suspend fun apply(
        preview: WorldTemplateMergePreview,
        fields: Set<WorldMergeField>,
    ): WorldTemplateMergeResult = database.withTransaction {
        val template = database.worldTemplateDao().getById(preview.templateId)
            ?: error("世界模板不存在: ${preview.templateId}")
        val target = database.encyclopediaDao().getById(preview.targetWorld.id)
            ?: error("百科世界不存在: ${preview.targetWorld.id}")
        val existingMapping = database.legacyWorldMappingDao().getByTemplateId(preview.templateId)
        if (existingMapping != null) {
            if (existingMapping.encyclopediaId != target.id) throw WorldTemplateAlreadyMappedException(existingMapping.encyclopediaId)
            val skipped = countCurrentSkips(template.id)
            return@withTransaction WorldTemplateMergeResult(target, added = 0, skipped = skipped)
        }
        checkMapping(preview.templateId, target.id)
        val current = snapshot(template, target)
        if (current.sourceToken != preview.sourceToken || current.targetToken != preview.targetToken) {
            throw WorldTemplateMergePreviewStaleException()
        }

        val mappingDao = database.legacyWorldMappingDao()
        if (existingMapping == null) {
            mappingDao.insert(LegacyWorldMappingEntity(template.id, target.id, current.sourceToken))
            val afterInsert = mappingDao.getByTemplateId(template.id)
                ?: error("世界映射写入后无法读回")
            if (afterInsert.encyclopediaId != target.id) throw WorldTemplateAlreadyMappedException(afterInsert.encyclopediaId)
        }

        var added = 0
        var skipped = 0
        var afterId = 0L
        val loreDao = database.worldLoreEntryDao()
        val entryDao = database.encyclopediaEntryDao()
        while (true) {
            val page = loreDao.getMergeContentPage(template.id, afterId, PAGE_LIMIT)
            if (page.isEmpty()) break
            val plan = buildPageConflictPlan(
                template.id,
                target.id,
                page.map { WorldLoreMergeRow(it.id, it.title, it.entryType) },
            )
            for (lore in page) {
                afterId = lore.id
                if (plan.isConflict(lore.id, lore.title)) {
                    skipped++
                    continue
                }
                val entryId = entryDao.upsert(promote.toCanonicalEntry(target.id, template, lore))
                val mappingInserted = mappingDao.insertLore(
                    LegacyLoreMappingEntity(lore.id, entryId, promote.hashLore(lore)),
                )
                if (mappingInserted == -1L) skipped++ else added++
            }
        }

        val updated = applyFields(target, template, fields, added)
        val actualCount = entryDao.countEntries(target.id)
        database.encyclopediaDao().upsert(updated.copy(entryCount = actualCount))
        WorldTemplateMergeResult(
            world = database.encyclopediaDao().getById(target.id) ?: error("百科更新后无法读回"),
            added = added,
            skipped = skipped,
        )
    }

    private suspend fun snapshot(
        template: WorldTemplateEntity,
        target: EncyclopediaEntity,
    ): WorldTemplateMergePreview {
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        digestValue(sourceDigest, templateSource(template))
        val targetDigest = MessageDigest.getInstance("SHA-256")
        digestValue(targetDigest, targetSource(target))
        val loreDao = database.worldLoreEntryDao()
        val entryDao = database.encyclopediaEntryDao()
        var afterId = 0L
        val stats = database.worldLoreEntryDao().getMergeStats(template.id, target.id)
        while (true) {
            val page = loreDao.getMergeContentPage(template.id, afterId, PAGE_LIMIT)
            if (page.isEmpty()) break
            afterId = page.last().id
            digestValue(sourceDigest, page.map { lore -> mapOf(
                    "id" to lore.id,
                    "updatedAt" to lore.updatedAt,
                    "semantics" to promote.loreSemantics(lore),
                ) })
        }
        var targetAfterId = 0L
        while (true) {
            val page = entryDao.getMergePage(target.id, targetAfterId, PAGE_LIMIT)
            if (page.isEmpty()) break
            targetAfterId = page.last().id
            digestValue(targetDigest, page.map { row ->
                mapOf("id" to row.id, "title" to row.title, "type" to row.entryType, "updatedAt" to row.updatedAt)
            })
        }
        return WorldTemplateMergePreview(
            templateId = template.id,
            targetWorld = target,
            template = template,
            sourceToken = hex(sourceDigest),
            targetToken = hex(targetDigest),
            loreTotal = stats.total,
            loreToAdd = stats.toAdd,
            loreConflicts = stats.conflicts,
        )
    }

    private data class ConflictPlan(
        val mappedIds: Set<Long>,
        val firstIds: Map<String, Long>,
        val targetTitles: Set<String>,
    ) {
        fun isConflict(id: Long, title: String): Boolean {
            val normalized = title.trim(' ')
            return id in mappedIds || firstIds[normalized] != id || normalized in targetTitles
        }
    }

    private suspend fun buildPageConflictPlan(
        templateId: Long,
        targetId: Long,
        page: List<WorldLoreMergeRow>,
    ): ConflictPlan {
        val titles = page.map { sqlTrim(it.title) }.distinct()
        val firstIds = database.worldLoreEntryDao()
            .getFirstIdsForTrimmedTitles(templateId, titles)
            .associate { it.trimmedTitle to it.firstId }
        val targetTitles = database.encyclopediaEntryDao()
            .getExistingTrimmedTitles(targetId, titles)
            .mapTo(mutableSetOf()) { it.trimmedTitle }
        val mappedIds = database.legacyWorldMappingDao()
            .getMappedLoreIds(page.map { it.id })
            .toSet()
        return ConflictPlan(mappedIds, firstIds, targetTitles)
    }

    private suspend fun countCurrentSkips(templateId: Long): Int {
        var skipped = 0
        var afterId = 0L
        while (true) {
            val page = database.worldLoreEntryDao().getMergePage(templateId, afterId, PAGE_LIMIT)
            if (page.isEmpty()) break
            for (lore in page) {
                afterId = lore.id
                skipped++
            }
        }
        return skipped
    }

    private fun sqlTrim(value: String): String = value.trim(' ')

    private suspend fun checkMapping(templateId: Long, targetId: Long) {
        val mapping = database.legacyWorldMappingDao().getByTemplateId(templateId)
        if (mapping != null && mapping.encyclopediaId != targetId) throw WorldTemplateAlreadyMappedException(mapping.encyclopediaId)
    }

    private fun applyFields(target: EncyclopediaEntity, template: WorldTemplateEntity, fields: Set<WorldMergeField>, added: Int): EncyclopediaEntity {
        val now = System.currentTimeMillis()
        return target.copy(
            description = if (WorldMergeField.DESCRIPTION in fields) template.summary else target.description,
            worldPrompt = if (WorldMergeField.WORLD_PROMPT in fields) template.worldPrompt else target.worldPrompt,
            gameplayMode = if (WorldMergeField.GAMEPLAY_MODE in fields) template.gameplayMode else target.gameplayMode,
            antiCheatPrompt = if (WorldMergeField.ANTI_CHEAT_PROMPT in fields) template.antiCheatPrompt else target.antiCheatPrompt,
            coverImagePath = if (WorldMergeField.COVER in fields) template.coverImagePath else target.coverImagePath,
            entryCount = target.entryCount + added,
            updatedAt = now,
        )
    }

    private fun templateSource(template: WorldTemplateEntity) = mapOf(
        "id" to template.id, "templateId" to template.templateId, "label" to template.label,
        "category" to template.category, "summary" to template.summary, "gameplayMode" to template.gameplayMode,
        "worldPrompt" to template.worldPrompt, "coverImagePath" to template.coverImagePath,
        "suggestedChoicesJson" to template.suggestedChoicesJson, "antiCheatPrompt" to template.antiCheatPrompt,
        "isBuiltin" to template.isBuiltin, "createdAt" to template.createdAt, "updatedAt" to template.updatedAt,
        "pinnedAt" to template.pinnedAt,
    )

    private fun targetSource(target: EncyclopediaEntity) = mapOf(
        "id" to target.id, "name" to target.name, "description" to target.description,
        "coverImagePath" to target.coverImagePath, "isOfficial" to target.isOfficial, "genreTags" to target.genreTags,
        "worldPrompt" to target.worldPrompt, "gameplayMode" to target.gameplayMode, "antiCheatPrompt" to target.antiCheatPrompt,
        "narratorConfigJson" to target.narratorConfigJson, "createdAt" to target.createdAt, "updatedAt" to target.updatedAt,
        "pinnedAt" to target.pinnedAt,
    )

    private suspend fun digestValue(digest: MessageDigest, value: Any) = withContext(Dispatchers.Default) {
        digest.update(Gson().toJson(value).toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
    }

    private suspend fun hex(digest: MessageDigest): String = withContext(Dispatchers.Default) {
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PAGE_LIMIT = 64
    }
}

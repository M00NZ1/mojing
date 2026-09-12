package com.mojing.app.domain.usecase

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.WorldLoreEntryEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
import javax.inject.Inject
import javax.inject.Singleton

data class SavedWorldTemplatePackage(
    val template: WorldTemplateEntity,
    val loreCount: Int,
)

/** 世界模板与其完整 Lore 集合的唯一整包替换事务。 */
@Singleton
class SaveWorldTemplatePackageUseCase @Inject constructor(
    private val database: AppDatabase,
    private val promoteWorld: PromoteWorldTemplateUseCase,
) {
    suspend operator fun invoke(
        template: WorldTemplateEntity,
        loreEntries: List<WorldLoreEntryEntity>,
    ): SavedWorldTemplatePackage = database.withTransaction {
        val templateDao = database.worldTemplateDao()
        val loreDao = database.worldLoreEntryDao()
        val templateId = template.templateId.trim()
        require(templateId.isNotEmpty()) { "模板 ID 不能为空" }
        require(template.worldPrompt.isNotBlank()) { "返回的世界主提示为空，请重新生成" }
        require(loreEntries.size >= 3) { "返回的世界设定不足 3 条，请重新生成" }
        loreEntries.forEachIndexed { index, lore ->
            require(lore.title.isNotBlank() && lore.content.isNotBlank()) {
                "第 ${index + 1} 条世界设定缺少标题或正文，请重新生成"
            }
        }

        val existing = templateDao.getByTemplateId(templateId)
        require(existing == null || database.legacyWorldMappingDao().getByTemplateId(existing.id) == null) {
            "这个世界已归入世界资料，请使用新的世界标识保存"
        }
        val toSave = template.copy(
            id = existing?.id ?: 0L,
            templateId = templateId,
            isBuiltin = existing?.isBuiltin ?: template.isBuiltin,
            createdAt = existing?.createdAt ?: template.createdAt,
            pinnedAt = existing?.pinnedAt ?: template.pinnedAt,
        )
        val insertedId = templateDao.upsert(toSave)
        val effectiveId = existing?.id ?: insertedId
        check(effectiveId > 0L) { "世界模板保存失败" }
        val saved = templateDao.getById(effectiveId)
            ?: error("世界模板保存后无法读回")

        loreDao.deleteByWorldTemplateId(effectiveId)
        loreEntries.forEach { lore ->
            loreDao.upsert(
                lore.copy(
                    id = 0L,
                    worldTemplateId = effectiveId,
                ),
            )
        }
        promoteWorld(effectiveId)
        SavedWorldTemplatePackage(saved, loreEntries.size)
    }
}

package com.mojing.app.domain.usecase

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.domain.encyclopedia.CharacterEncyclopediaSync
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 角色与百科镜像的唯一持久化入口；角色行、旧镜像清理和新镜像写入在同一事务内完成。
 */
@Singleton
class SaveCharacterBindingUseCase @Inject constructor(
    private val database: AppDatabase,
) {
    suspend operator fun invoke(character: CharacterEntity): Long = database.withTransaction {
        val characterDao = database.characterDao()
        val entryDao = database.encyclopediaEntryDao()
        val previous = character.id.takeIf { it > 0L }?.let { characterDao.getById(it) }

        val rowId = characterDao.upsert(character)
        val effectiveId = character.id.takeIf { it > 0L } ?: rowId
        check(effectiveId > 0L) { "角色保存失败" }

        val previousEncyclopediaId = previous?.boundEncyclopediaId ?: 0L
        if (previousEncyclopediaId > 0L && previousEncyclopediaId != character.boundEncyclopediaId) {
            CharacterEncyclopediaSync.removeCharacterMirrors(
                entryDao = entryDao,
                encyclopediaId = previousEncyclopediaId,
                characterId = effectiveId,
            )
        }

        if (character.boundEncyclopediaId > 0L) {
            CharacterEncyclopediaSync.syncCharacterToEntry(
                entryDao = entryDao,
                character = character.copy(id = effectiveId),
            )
        }
        effectiveId
    }
}

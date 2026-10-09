package com.mojing.app.domain.usecase

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.domain.encyclopedia.CharacterEncyclopediaSync
import javax.inject.Inject
import javax.inject.Singleton

/** 删除百科条目；若它拥有绑定角色，则在同一事务中删除角色和全部 linked 镜像。 */
@Singleton
class DeleteEncyclopediaEntryUseCase @Inject constructor(
    private val database: AppDatabase,
) {
    suspend operator fun invoke(id: Long, encyclopediaId: Long): Boolean = database.withTransaction {
        val entryDao = database.encyclopediaEntryDao()
        val characterDao = database.characterDao()
        val entry = entryDao.getById(id) ?: return@withTransaction false
        if (entry.encyclopediaId != encyclopediaId) return@withTransaction false
        val linkedCharacterId = entry
            .takeIf { it.entryType == "character" }
            ?.let { CharacterEncyclopediaSync.readLinkedCharacterId(it.metaJson) }
            ?.takeIf { it > 0L }
        val linkedCharacter = linkedCharacterId?.let { characterDao.getById(it) }

        if (linkedCharacter != null && linkedCharacter.boundEncyclopediaId == entry.encyclopediaId) {
            CharacterEncyclopediaSync.removeCharacterMirrors(
                entryDao,
                entry.encyclopediaId,
                linkedCharacter.id,
            )
            characterDao.delete(linkedCharacter.id)
        } else {
            entryDao.delete(id)
        }
        true
    }
}

package com.mojing.app.domain.usecase

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.domain.encyclopedia.CharacterEncyclopediaSync
import javax.inject.Inject
import javax.inject.Singleton

/** 删除角色及其绑定百科中的镜像条目；角色列表统一从这里进入删除链。 */
@Singleton
class DeleteCharacterUseCase @Inject constructor(
    private val database: AppDatabase,
) {
    suspend operator fun invoke(id: Long) = database.withTransaction {
        val characterDao = database.characterDao()
        val encyclopediaEntryDao = database.encyclopediaEntryDao()
        val character = characterDao.getById(id)
        val encyclopediaId = character?.boundEncyclopediaId ?: 0L
        if (encyclopediaId > 0L) {
            CharacterEncyclopediaSync.removeCharacterMirrors(
                entryDao = encyclopediaEntryDao,
                encyclopediaId = encyclopediaId,
                characterId = id,
            )
        }
        characterDao.delete(id)
    }
}

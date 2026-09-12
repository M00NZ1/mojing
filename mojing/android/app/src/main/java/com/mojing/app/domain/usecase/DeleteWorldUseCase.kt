package com.mojing.app.domain.usecase

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.mojing.app.data.local.AppDatabase
import javax.inject.Inject

class DeleteWorldUseCase @Inject constructor(private val database: AppDatabase) {
    suspend operator fun invoke(id: Long): String? = database.withTransaction {
        val referenced = database.query(SimpleSQLiteQuery(
            "SELECT EXISTS(SELECT 1 FROM characters WHERE boundEncyclopediaId = ? " +
                "UNION ALL SELECT 1 FROM session_worlds WHERE encyclopediaId = ? " +
                "UNION ALL SELECT 1 FROM legacy_world_mappings WHERE encyclopediaId = ?)",
            arrayOf(id, id, id),
        )).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) != 0 }
        if (referenced) return@withTransaction "这个世界仍关联角色、对话或工坊资料，请先解除关联"
        database.encyclopediaDao().delete(id)
        null
    }
}

package com.mojing.app.domain.usecase

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

sealed interface DeleteWorldTemplateResult {
    data object Deleted : DeleteWorldTemplateResult
    data object Protected : DeleteWorldTemplateResult
    data object NotFound : DeleteWorldTemplateResult
    data class Failed(val cause: Throwable) : DeleteWorldTemplateResult
}

class DeleteWorldTemplateUseCase @Inject constructor(
    private val database: AppDatabase,
) {
    suspend operator fun invoke(templateId: Long): DeleteWorldTemplateResult = try {
        database.withTransaction {
            val templateDao = database.worldTemplateDao()
            val template = templateDao.getById(templateId)
                ?: return@withTransaction DeleteWorldTemplateResult.NotFound
            if (database.legacyWorldMappingDao().getByTemplateId(template.id) != null) {
                return@withTransaction DeleteWorldTemplateResult.Protected
            }

            database.worldLoreEntryDao().deleteByWorldTemplateId(template.id)
            check(templateDao.delete(template.id) == 1) { "模板删除未完成" }
            DeleteWorldTemplateResult.Deleted
        }
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        DeleteWorldTemplateResult.Failed(cause)
    }
}

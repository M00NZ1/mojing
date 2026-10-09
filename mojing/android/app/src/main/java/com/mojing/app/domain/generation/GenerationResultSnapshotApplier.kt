package com.mojing.app.domain.generation

import androidx.room.withTransaction
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.GenerationTaskDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import javax.inject.Inject
import javax.inject.Singleton

data class GenerationResultApplicationPreview(
    val taskId: Long,
    val result: GenerationResultSnapshot,
    val currentPersona: String? = null,
    val currentSummary: String? = null,
    val currentWorld: String? = null,
    val targetExists: Boolean,
    val alreadyApplied: Boolean,
)

/** The single transactional owner for applying persisted generation results. */
@Singleton
class GenerationResultSnapshotApplier @Inject constructor(
    private val database: AppDatabase,
    private val taskDao: GenerationTaskDao,
    private val characterDao: CharacterDao,
    private val worldTemplateDao: WorldTemplateDao,
    private val saveCharacterBinding: SaveCharacterBindingUseCase,
) {
    suspend fun preview(taskId: Long): GenerationResultApplicationPreview? = database.withTransaction {
        val task = taskDao.getById(taskId) ?: return@withTransaction null
        val result = GenerationResultSnapshotCodec.decode(task.resultJson) ?: return@withTransaction null
        if (result.taskKind != task.taskKind) return@withTransaction null
        when (result) {
            is GenerationResultSnapshot.CharacterPersona -> {
                val target = task.targetCharacterId?.let { characterDao.getById(it) }
                GenerationResultApplicationPreview(taskId, result, currentPersona = target?.personaPrompt,
                    targetExists = target != null, alreadyApplied = task.resultAppliedAt != null)
            }
            is GenerationResultSnapshot.WorldTemplate -> {
                val target = task.targetWorldTemplateId?.let { worldTemplateDao.getById(it) }
                GenerationResultApplicationPreview(taskId, result, currentSummary = target?.summary,
                    currentWorld = target?.worldPrompt, targetExists = target != null,
                    alreadyApplied = task.resultAppliedAt != null)
            }
        }
    }

    suspend fun apply(
        taskId: Long,
        expectedPersonaPrompt: String? = null,
        expectedSummary: String? = null,
        expectedWorldPrompt: String? = null,
    ): GenerationQueueProcessor.ResultApplyOutcome = database.withTransaction {
        val task = taskDao.getById(taskId) ?: return@withTransaction GenerationQueueProcessor.ResultApplyOutcome.TargetMissing
        if (task.resultAppliedAt != null) return@withTransaction GenerationQueueProcessor.ResultApplyOutcome.AlreadyApplied
        val snapshot = GenerationResultSnapshotCodec.decode(task.resultJson)
            ?: return@withTransaction GenerationQueueProcessor.ResultApplyOutcome.InvalidResult
        if (task.status != com.mojing.app.data.local.entity.GenerationTaskStatus.COMPLETED || snapshot.taskKind != task.taskKind)
            return@withTransaction GenerationQueueProcessor.ResultApplyOutcome.InvalidResult
        val applied = when (snapshot) {
            is GenerationResultSnapshot.CharacterPersona -> {
                val target = task.targetCharacterId?.let { characterDao.getById(it) }
                    ?: return@withTransaction GenerationQueueProcessor.ResultApplyOutcome.TargetMissing
                if (expectedPersonaPrompt == null || target.personaPrompt != expectedPersonaPrompt) {
                    return@withTransaction GenerationQueueProcessor.ResultApplyOutcome.StalePreview
                }
                saveCharacterBinding(target.copy(personaPrompt = snapshot.personaPrompt, updatedAt = System.currentTimeMillis()))
                true
            }
            is GenerationResultSnapshot.WorldTemplate -> {
                val target = task.targetWorldTemplateId?.let { worldTemplateDao.getById(it) }
                    ?: return@withTransaction GenerationQueueProcessor.ResultApplyOutcome.TargetMissing
                val expectedSummaryValue = expectedSummary ?: return@withTransaction GenerationQueueProcessor.ResultApplyOutcome.StalePreview
                val expectedWorldValue = expectedWorldPrompt ?: return@withTransaction GenerationQueueProcessor.ResultApplyOutcome.StalePreview
                if (target.summary != expectedSummaryValue || target.worldPrompt != expectedWorldValue) {
                    return@withTransaction GenerationQueueProcessor.ResultApplyOutcome.StalePreview
                }
                worldTemplateDao.updateGeneratedContentIfUnchanged(
                    id = target.id,
                    expectedSummary = expectedSummaryValue,
                    expectedWorldPrompt = expectedWorldValue,
                    summary = snapshot.summary ?: target.summary,
                    worldPrompt = snapshot.worldPrompt ?: target.worldPrompt,
                    updatedAt = System.currentTimeMillis(),
                ) == 1
            }
        }
        if (!applied) return@withTransaction GenerationQueueProcessor.ResultApplyOutcome.StalePreview
        check(taskDao.markResultApplied(taskId, System.currentTimeMillis(), System.currentTimeMillis()) == 1) {
            "结果标记失败"
        }
        GenerationQueueProcessor.ResultApplyOutcome.Applied
    }
}

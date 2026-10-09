package com.mojing.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mojing.app.data.local.entity.GenerationTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GenerationTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: GenerationTaskEntity): Long

    @Query("SELECT * FROM generation_tasks ORDER BY id DESC")
    fun observeAll(): Flow<List<GenerationTaskEntity>>

    /**
     * 任务列表：含进行中、失败与近期已完成（便于核对结果）；按 id 倒序，最多 150 条。
     */
    @Query(
        """
        SELECT * FROM generation_tasks
        WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED', 'FAILED', 'COMPLETED', 'CANCELLED')
        ORDER BY CASE WHEN status IN ('RUNNING', 'PAUSED', 'QUEUED') THEN 0 ELSE 1 END, id DESC
        LIMIT 150
        """,
    )
    fun observeQueueVisible(): Flow<List<GenerationTaskEntity>>

    @Query("""
        SELECT * FROM generation_tasks WHERE id < :beforeId
        AND (:filter = 0 OR (:filter = 1 AND status IN ('QUEUED','RUNNING','PAUSED'))
            OR (:filter = 2 AND status = 'FAILED'))
        ORDER BY id DESC LIMIT 51
    """)
    fun observeHistoryPage(beforeId: Long, filter: Int): Flow<List<GenerationTaskEntity>>

    @Query(
        """
        SELECT * FROM generation_tasks WHERE id < :beforeId
        AND (:filter = 0 OR (:filter = 1 AND status IN ('QUEUED','RUNNING','PAUSED'))
            OR (:filter = 2 AND status = 'FAILED'))
        AND (:keyword = '' OR instr(lower(title), lower(:keyword)) > 0)
        AND (:taskKind IS NULL OR taskKind = :taskKind)
        ORDER BY id DESC LIMIT 51
        """,
    )
    fun observeHistoryPageFiltered(
        beforeId: Long,
        filter: Int,
        keyword: String,
        taskKind: String?,
    ): Flow<List<GenerationTaskEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM generation_tasks
        WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED')
        """,
    )
    suspend fun countActive(): Int

    @Query(
        """
        SELECT COUNT(*) FROM generation_tasks
        WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED')
        """,
    )
    fun observeActiveCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM generation_tasks
        WHERE targetEncyclopediaId = :encId
          AND status IN ('QUEUED', 'RUNNING', 'PAUSED')
        ORDER BY id DESC
        LIMIT 5
        """,
    )
    fun observeActiveForEncyclopedia(encId: Long): Flow<List<GenerationTaskEntity>>

    @Query(
        """
        SELECT * FROM generation_tasks
        WHERE targetCharacterId = :characterId
          AND status IN ('QUEUED', 'RUNNING', 'PAUSED')
        ORDER BY id DESC
        LIMIT 5
        """,
    )
    fun observeActiveForCharacter(characterId: Long): Flow<List<GenerationTaskEntity>>

    @Query(
        """
        SELECT * FROM generation_tasks
        WHERE targetCharacterId = :characterId
          AND taskKind = 'character_persona_ai'
        ORDER BY id DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestPersonaTaskForCharacter(characterId: Long): GenerationTaskEntity?

    @Query(
        """
        UPDATE generation_tasks
        SET status = 'FAILED', errorMessage = :err, updatedAt = :now
        WHERE status = 'RUNNING' AND updatedAt < :staleBefore
        """,
    )
    suspend fun reclaimStaleRunning(staleBefore: Long, err: String, now: Long): Int

    @Query(
        """
        SELECT * FROM generation_tasks
        WHERE targetWorldTemplateId = :templateRowId
          AND status IN ('QUEUED', 'RUNNING', 'PAUSED')
        ORDER BY id DESC
        LIMIT 5
        """,
    )
    fun observeActiveForTemplate(templateRowId: Long): Flow<List<GenerationTaskEntity>>

    @Query("SELECT * FROM generation_tasks WHERE id = :id")
    suspend fun getById(id: Long): GenerationTaskEntity?

    @Query("SELECT * FROM generation_tasks WHERE status = 'QUEUED' ORDER BY id ASC LIMIT 1")
    suspend fun peekNextQueued(): GenerationTaskEntity?

    @Query(
        """
        UPDATE generation_tasks
        SET status = 'RUNNING', updatedAt = :now
        WHERE id = :id AND status = 'QUEUED'
        """,
    )
    suspend fun claimIfQueued(id: Long, now: Long): Int

    @Query(
        """
        UPDATE generation_tasks
        SET status = :status, errorMessage = :err, updatedAt = :now
        WHERE id = :id AND status NOT IN ('CANCELLED', 'COMPLETED')
        """,
    )
    suspend fun setTerminal(id: Long, status: String, err: String, now: Long)

    @Query("""
        UPDATE generation_tasks
        SET resultJson = :resultJson, status = :status, errorMessage = :err, updatedAt = :now
        WHERE id = :id AND status = 'RUNNING'
    """)
    suspend fun saveResultAndTerminal(id: Long, resultJson: String, status: String, err: String, now: Long): Int

    @Query("""
        UPDATE generation_tasks
        SET resultAppliedAt = :appliedAt, updatedAt = :now
        WHERE id = :id AND resultJson <> '' AND resultAppliedAt IS NULL
    """)
    suspend fun markResultApplied(id: Long, appliedAt: Long, now: Long): Int

    @Query(
        """
        UPDATE generation_tasks
        SET progressDone = :done, progressTotal = :total, updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun updateProgress(id: Long, done: Int, total: Int, now: Long)

    @Query(
        """
        UPDATE generation_tasks
        SET status = 'PAUSED', updatedAt = :now
        WHERE status = 'RUNNING'
        """,
    )
    suspend fun pauseRunningTasks(now: Long): Int

    @Query(
        """
        UPDATE generation_tasks
        SET status = 'QUEUED', updatedAt = :now
        WHERE status = 'PAUSED'
        """,
    )
    suspend fun resumePausedToQueued(now: Long): Int

    @Query(
        """
        UPDATE generation_tasks
        SET status = 'CANCELLED', updatedAt = :now
        WHERE id = :id AND status IN ('QUEUED', 'RUNNING', 'PAUSED')
        """,
    )
    suspend fun cancelTask(id: Long, now: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM generation_tasks
        WHERE targetCharacterId = :characterId
          AND taskKind = 'character_persona_ai'
          AND status IN ('QUEUED', 'RUNNING', 'PAUSED')
        """,
    )
    suspend fun countActivePersonaForCharacter(characterId: Long): Int

    /** 失败任务从已保存的进度继续，总数保持 payload 原始 count。 */
    @Query(
        """
        UPDATE generation_tasks
        SET status = 'QUEUED', progressTotal = :total,
            errorMessage = '', updatedAt = :now
        WHERE id = :id AND status = 'FAILED'
        """,
    )
    suspend fun requeueFailed(id: Long, total: Int, now: Long): Int
}

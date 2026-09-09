package com.mojing.app.domain.generation

import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.entity.GenerationTaskEntity
import com.mojing.app.data.local.entity.GenerationTaskKinds
import javax.inject.Inject

sealed interface GenerationResultTarget {
    data class Character(val id: Long) : GenerationResultTarget
    data class World(val id: Long) : GenerationResultTarget
    data class Encyclopedia(val id: Long) : GenerationResultTarget
}

class GenerationResultResolver @Inject constructor(
    private val characters: CharacterDao,
    private val worlds: WorldTemplateDao,
    private val encyclopedias: EncyclopediaDao,
) {
    suspend fun resolve(task: GenerationTaskEntity): GenerationResultTarget? = when (task.taskKind) {
        GenerationTaskKinds.CHARACTER_PERSONA_AI -> task.targetCharacterId?.takeIf { it > 0L }
            ?.let { id -> characters.getById(id)?.let { GenerationResultTarget.Character(id) } }
        GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI -> task.targetWorldTemplateId?.takeIf { it > 0L }
            ?.let { id -> worlds.getById(id)?.let { GenerationResultTarget.World(id) } }
        GenerationTaskKinds.ENCYCLOPEDIA_ENTRIES, GenerationTaskKinds.ENCYCLOPEDIA_META_FILL ->
            task.targetEncyclopediaId?.takeIf { it > 0L }
                ?.let { id -> encyclopedias.getById(id)?.let { GenerationResultTarget.Encyclopedia(id) } }
        else -> null
    }
}

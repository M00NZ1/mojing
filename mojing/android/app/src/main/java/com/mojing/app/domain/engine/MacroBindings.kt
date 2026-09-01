package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.CharacterEntity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** 与后端 `macro_service.get_available_macros` 对齐的占位符展开表（键为 `{{...}}` 内的小写标识）。 */
object MacroBindings {
    fun forCharacterSession(
        character: CharacterEntity,
        personaName: String,
        userDescription: String,
        sessionId: Long?,
        modelForMacro: String,
    ): Map<String, String> {
        val rawPersona = character.personaPrompt
        val now = LocalDateTime.now()
        val timeStr = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        val dateStr = now.format(DateTimeFormatter.ISO_LOCAL_DATE)
        return mapOf(
            "user" to personaName,
            "user_description" to userDescription,
            "char" to character.name,
            "char_description" to rawPersona,
            "time" to timeStr,
            "date" to dateStr,
            "session_id" to (sessionId?.toString().orEmpty()),
            "model" to modelForMacro,
            "random" to Random.nextInt(1, 101).toString(),
        )
    }
}

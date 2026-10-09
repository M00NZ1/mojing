package com.mojing.app.domain.generation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.data.local.entity.GenerationTaskKinds

/** Small, versioned allow-list for generated fields persisted with a task. */
sealed interface GenerationResultSnapshot {
    val taskKind: String

    data class CharacterPersona(val personaPrompt: String) : GenerationResultSnapshot {
        override val taskKind: String = GenerationTaskKinds.CHARACTER_PERSONA_AI
    }

    data class WorldTemplate(val summary: String?, val worldPrompt: String?) : GenerationResultSnapshot {
        override val taskKind: String = GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI
    }
}

object GenerationResultSnapshotCodec {
    private const val VERSION = 1

    fun encode(snapshot: GenerationResultSnapshot): String {
        val root = JsonObject().apply {
            addProperty("schemaVersion", VERSION)
            addProperty("taskKind", snapshot.taskKind)
            add("fields", JsonObject().apply {
                when (snapshot) {
                    is GenerationResultSnapshot.CharacterPersona -> addProperty("personaPrompt", snapshot.personaPrompt)
                    is GenerationResultSnapshot.WorldTemplate -> {
                        snapshot.summary?.let { addProperty("summary", it) }
                        snapshot.worldPrompt?.let { addProperty("worldPrompt", it) }
                    }
                }
            })
        }
        return root.toString()
    }

    fun decode(raw: String): GenerationResultSnapshot? = runCatching {
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.keySet() == setOf("schemaVersion", "taskKind", "fields"))
        val version = root.get("schemaVersion")
        require(version.isJsonPrimitive && version.asJsonPrimitive.isNumber)
        require(version.asDouble == VERSION.toDouble())
        val kind = root.get("taskKind").asString
        val fields = root.get("fields").asJsonObject
        when (kind) {
            GenerationTaskKinds.CHARACTER_PERSONA_AI -> {
                require(fields.keySet() == setOf("personaPrompt"))
                val value = fields.get("personaPrompt")
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                require(value.asString.isNotBlank())
                GenerationResultSnapshot.CharacterPersona(value.asString)
            }
            GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI -> {
                require(fields.keySet().all { it == "summary" || it == "worldPrompt" })
                require(fields.size() > 0)
                fields.entrySet().forEach { (_, value) ->
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                    require(value.asString.isNotBlank())
                }
                GenerationResultSnapshot.WorldTemplate(
                    summary = fields.get("summary")?.takeUnless { it.isJsonNull }?.asString,
                    worldPrompt = fields.get("worldPrompt")?.takeUnless { it.isJsonNull }?.asString,
                )
            }
            else -> null
        }
    }.getOrNull()
}

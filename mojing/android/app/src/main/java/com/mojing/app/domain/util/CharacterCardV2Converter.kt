package com.mojing.app.domain.util

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.CharacterProfileEntity
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Character Card V2 ↔ 本项目 `CharacterEntity` / `CharacterProfileEntity` 字段映射，
 * 与后端 `character_card_service.convert_v2_to_internal` / `convert_internal_to_v2` 对齐。
 */
object CharacterCardV2Converter {

    private const val V2_SPEC = "chara_card_v2"

    fun jsonRootToParsedPortable(root: JsonObject, sourceFilename: String): CharacterPortableCodec.ParsedPortable {
        val data = root.getAsJsonObject("data") ?: root
        val name = data.get("name")?.asString?.trim().orEmpty()
        val description = data.get("description")?.asString.orEmpty()
        val personality = data.get("personality")?.asString.orEmpty()
        val scenario = data.get("scenario")?.asString.orEmpty()
        val firstMes = data.get("first_mes")?.asString.orEmpty()
        val mesExample = data.get("mes_example")?.asString.orEmpty()
        val systemPrompt = data.get("system_prompt")?.asString.orEmpty()
        val postHistory = data.get("post_history_instructions")?.asString.orEmpty()
        val creatorNotes = data.get("creator_notes")?.asString.orEmpty()

        val personaParts = mutableListOf<String>()
        if (scenario.isNotBlank()) personaParts.add("【场景】$scenario")
        if (description.isNotBlank()) personaParts.add(description)
        if (personality.isNotBlank()) personaParts.add(personality)
        var personaPrompt = personaParts.joinToString("\n\n").trim()
        if (firstMes.isNotBlank()) personaPrompt += "\n\n【开场白】\n$firstMes"
        if (mesExample.isNotBlank()) personaPrompt += "\n\n【对话示例】\n$mesExample"
        if (systemPrompt.isNotBlank()) personaPrompt += "\n\n【系统提示】\n$systemPrompt"
        if (postHistory.isNotBlank()) personaPrompt += "\n\n【后置指令】\n$postHistory"
        if (creatorNotes.isNotBlank()) personaPrompt += "\n\n【创作者备注】\n$creatorNotes"
        personaPrompt = personaPrompt.trim()

        val rawPersona = listOf(description, personality, scenario, firstMes, mesExample)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        val cardJson = com.google.gson.Gson().toJson(root)
        val profile = CharacterPortableCodec.ParsedPortable.ProfileSlice(
            sourceFilename = sourceFilename.take(255),
            rawPersonaText = rawPersona.ifBlank { personaPrompt },
            characterCardMarkdown = "",
            characterCardJson = cardJson,
        )
        return CharacterPortableCodec.ParsedPortable(
            name = name,
            personaPrompt = personaPrompt.ifBlank { "（卡内未含人设正文，请补充）" },
            modelName = null,
            apiBaseUrl = null,
            temperature = null,
            maxTokens = null,
            avatarColor = null,
            avatarImagePath = null,
            cardImagePath = null,
            profile = profile,
        )
    }

    fun buildV2Export(entity: CharacterEntity, profile: CharacterProfileEntity?): JsonObject {
        val gson = com.google.gson.Gson()
        val data: JsonObject = profile?.characterCardJson?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching {
                val parsed = JsonParser.parseString(raw).asJsonObject
                when {
                    parsed.has("data") -> gson.fromJson(gson.toJson(parsed.get("data")), JsonObject::class.java)
                    parsed.has("name") -> gson.fromJson(gson.toJson(parsed), JsonObject::class.java)
                    else -> null
                }
            }.getOrNull()
        } ?: JsonObject()

        data.addProperty("name", entity.name)
        if (!data.has("description") || data.get("description")?.asString.isNullOrBlank()) {
            data.addProperty("description", entity.personaPrompt.take(500))
        }
        if (!data.has("personality")) data.addProperty("personality", "")
        if (!data.has("scenario")) data.addProperty("scenario", "")
        if (!data.has("first_mes")) data.addProperty("first_mes", "")
        if (!data.has("mes_example")) data.addProperty("mes_example", "")
        if (!data.has("system_prompt")) data.addProperty("system_prompt", "")
        if (!data.has("post_history_instructions")) data.addProperty("post_history_instructions", "")
        if (!data.has("alternate_greetings")) data.add("alternate_greetings", JsonArray())
        if (!data.has("tags")) data.add("tags", JsonArray())
        if (!data.has("creator")) data.addProperty("creator", "本应用 (Android)")
        data.addProperty("creator_notes", "由 Android 客户端导出，人物 ID: ${entity.id}")
        if (!data.has("character_book")) data.add("character_book", JsonNull.INSTANCE)

        val root = JsonObject()
        root.addProperty("spec", V2_SPEC)
        root.addProperty("spec_version", "2.0")
        root.add("data", data)
        return root
    }
}

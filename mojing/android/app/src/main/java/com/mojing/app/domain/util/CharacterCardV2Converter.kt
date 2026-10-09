package com.mojing.app.domain.util

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.CharacterProfileEntity
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.GsonBuilder

/**
 * Character Card V2 ↔ 本项目 `CharacterEntity` / `CharacterProfileEntity` 字段映射，
 * 与后端 `character_card_service.convert_v2_to_internal` / `convert_internal_to_v2` 对齐。
 */
object CharacterCardV2Converter {

    private const val V2_SPEC = "chara_card_v2"
    private val cardGson = GsonBuilder().serializeNulls().create()

    /** Null contributes no text, while the original card JSON retains it verbatim. */
    private fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    fun jsonRootToParsedPortable(root: JsonObject, sourceFilename: String): CharacterPortableCodec.ParsedPortable {
        val data = root.getAsJsonObject("data") ?: root
        val name = data.text("name").trim()
        val description = data.text("description")
        val personality = data.text("personality")
        val scenario = data.text("scenario")
        val firstMes = data.text("first_mes")
        val mesExample = data.text("mes_example")
        val systemPrompt = data.text("system_prompt")
        val postHistory = data.text("post_history_instructions")

        val personaParts = mutableListOf<String>()
        if (scenario.isNotBlank()) personaParts.add("【场景】$scenario")
        if (description.isNotBlank()) personaParts.add(description)
        if (personality.isNotBlank()) personaParts.add(personality)
        var personaPrompt = personaParts.joinToString("\n\n").trim()
        if (firstMes.isNotBlank()) personaPrompt += "\n\n【开场白】\n$firstMes"
        if (mesExample.isNotBlank()) personaPrompt += "\n\n【对话示例】\n$mesExample"
        if (systemPrompt.isNotBlank()) personaPrompt += "\n\n【系统提示】\n$systemPrompt"
        if (postHistory.isNotBlank()) personaPrompt += "\n\n【后置指令】\n$postHistory"
        personaPrompt = personaPrompt.trim()

        val rawPersona = listOf(description, personality, scenario, firstMes, mesExample)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        val cardJson = cardGson.toJson(root)
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
        val originalRoot = profile?.characterCardJson?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        }
        val originalData = runCatching {
            when {
                originalRoot?.has("data") == true -> originalRoot.getAsJsonObject("data").deepCopy()
                originalRoot?.has("name") == true -> originalRoot.deepCopy()
                else -> null
            }
        }.getOrNull()
        val data = originalData ?: JsonObject()
        val originalPersona = originalData?.let {
            runCatching { jsonRootToParsedPortable(it, "").personaPrompt }.getOrNull()
        }

        data.addProperty("name", entity.name)
        // The editor owns one complete persona. Keep an unchanged card's structure;
        // changed personas must not re-import stale structured sections a second time.
        if (originalPersona != entity.personaPrompt) {
            data.addProperty("description", entity.personaPrompt)
            listOf("personality", "scenario", "first_mes", "mes_example", "system_prompt", "post_history_instructions")
                .forEach { data.addProperty(it, "") }
        }
        if (!data.has("description")) data.addProperty("description", "")
        if (!data.has("personality")) data.addProperty("personality", "")
        if (!data.has("scenario")) data.addProperty("scenario", "")
        if (!data.has("first_mes")) data.addProperty("first_mes", "")
        if (!data.has("mes_example")) data.addProperty("mes_example", "")
        if (!data.has("system_prompt")) data.addProperty("system_prompt", "")
        if (!data.has("post_history_instructions")) data.addProperty("post_history_instructions", "")
        if (!data.has("alternate_greetings")) data.add("alternate_greetings", JsonArray())
        if (!data.has("tags")) data.add("tags", JsonArray())
        if (!data.has("creator")) data.addProperty("creator", "本应用 (Android)")
        if (!data.has("creator_notes")) data.addProperty("creator_notes", "")
        if (!data.has("character_version")) data.addProperty("character_version", "")
        if (!data.has("extensions")) data.add("extensions", JsonObject())
        if (!data.has("character_book")) data.add("character_book", JsonNull.INSTANCE)

        val root = originalRoot?.takeIf { it.get("data")?.isJsonObject == true }?.deepCopy() ?: JsonObject()
        root.addProperty("spec", V2_SPEC)
        root.addProperty("spec_version", "2.0")
        root.add("data", data)
        return root
    }
}

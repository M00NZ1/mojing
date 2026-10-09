package com.mojing.app.domain.util

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.CharacterProfileEntity
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonElement

/**
 * 与 Web/后端 `mojing_character_portable` v1 对齐的便携角色包编解码。
 */
object CharacterPortableCodec {
    const val KIND = "mojing_character_portable"
    const val LEGACY_KIND = "mojing_character_portable"
    private val PORTABLE_KINDS = setOf(KIND, LEGACY_KIND)
    const val VERSION = 1
    private val TXT_HEADER = "# $KIND v$VERSION"
    private const val TXT_SEP = "\n---\n"
    private val gson = com.google.gson.GsonBuilder().serializeNulls().create()

    fun buildPayload(entity: CharacterEntity, profile: CharacterProfileEntity?): JsonObject {
        val o = JsonObject()
        o.addProperty("kind", KIND)
        o.addProperty("version", VERSION)
        o.addProperty("name", entity.name)
        o.addProperty("persona_prompt", entity.personaPrompt)
        o.addProperty("model_name", entity.modelName)
        o.addProperty("api_base_url", entity.apiBaseUrl)
        o.addProperty("temperature", entity.temperature)
        o.addProperty("max_tokens", entity.maxTokens)
        o.addProperty("top_p", entity.topP)
        o.addProperty("frequency_penalty", entity.frequencyPenalty)
        o.addProperty("presence_penalty", entity.presencePenalty)
        o.addProperty("avatar_color", entity.avatarColor)
        o.addProperty("notes", "包里不带 Key，导入后自己在角色里填。")
        if (entity.avatarImagePath.isNotBlank()) {
            o.addProperty("avatar_image_path", entity.avatarImagePath)
        }
        if (entity.cardImagePath.isNotBlank()) {
            o.addProperty("card_image_path", entity.cardImagePath)
        }
        if (profile != null) {
            val p = JsonObject()
            p.addProperty("source_filename", profile.sourceFilename)
            p.addProperty("raw_persona_text", profile.rawPersonaText)
            p.addProperty("character_card_markdown", profile.characterCardMarkdown)
            val ccj = try {
                gson.fromJson(profile.characterCardJson, JsonObject::class.java)
            } catch (_: Exception) {
                JsonObject()
            }
            p.add("character_card_json", ccj ?: JsonObject())
            o.add("profile", p)
        }
        return o
    }

    fun toJsonString(entity: CharacterEntity, profile: CharacterProfileEntity?): String =
        gson.toJson(buildPayload(entity, profile))

    fun toTxt(entity: CharacterEntity, profile: CharacterProfileEntity?): String {
        val meta = buildPayload(entity, profile).apply { remove("persona_prompt") }
        val persona = entity.personaPrompt
        return "$TXT_HEADER\n${gson.toJson(meta)}$TXT_SEP$persona"
    }

    fun portableTxtFromPayload(o: JsonObject): String {
        val meta = gson.fromJson(gson.toJson(o), JsonObject::class.java)
        val persona = meta.remove("persona_prompt")?.asString ?: ""
        return "$TXT_HEADER\n${gson.toJson(meta)}$TXT_SEP$persona"
    }

    fun normalizedNameBase(base: String): String = base.trim().ifBlank { "未命名" }.take(120)

    fun allocateUniqueName(existingNames: Collection<String>, base: String): String {
        val clean = normalizedNameBase(base)
        if (clean !in existingNames) return clean
        for (i in 2..4999) {
            val c = "${clean}_$i".take(120)
            if (c !in existingNames) return c
        }
        return "${clean.take(100)}_${System.currentTimeMillis()}"
    }

    /**
     * @return Pair(character fields as partial entity updates via new entity builder, profile optional)
     */
    data class ParsedPortable(
        val name: String,
        val personaPrompt: String,
        val modelName: String?,
        val apiBaseUrl: String?,
        val temperature: Float?,
        val maxTokens: Int?,
        val avatarColor: String?,
        val avatarImagePath: String? = null,
        val cardImagePath: String? = null,
        val profile: ProfileSlice?,
        val topP: Float? = null,
        val frequencyPenalty: Float? = null,
        val presencePenalty: Float? = null,
    ) {
        data class ProfileSlice(
            val sourceFilename: String,
            val rawPersonaText: String,
            val characterCardMarkdown: String,
            val characterCardJson: String
        )
    }

    fun isPortableKind(kind: String?): Boolean = kind in PORTABLE_KINDS

    fun parseOptionalMaxTokens(value: JsonElement?): Int? {
        if (value == null || value.isJsonNull) return null
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isEmpty()) return null
        return runCatching { value.asBigDecimal.intValueExact() }.getOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("角色最大 Token 必须是正整数")
    }

    /** Same finite-number contract as the character editor; provider ranges are not guessed. */
    fun parseOptionalSampling(value: JsonElement?, label: String): Float? {
        if (value == null || value.isJsonNull) return null
        val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        if (primitive?.isString == true && primitive.asString.isEmpty()) return null
        val number = primitive?.takeIf { it.isNumber || it.isString }
            ?.asString?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }
        return number ?: throw IllegalArgumentException("角色${label}必须是有效数字")
    }

    fun parsePortableJson(root: JsonObject): ParsedPortable {
        if (!isPortableKind(root.get("kind")?.asString)) {
            throw IllegalArgumentException("不是墨境便携 JSON（缺少或无效 kind）")
        }
        if (root.get("version")?.asInt != VERSION) {
            throw IllegalArgumentException("暂不支持的版本: ${root.get("version")}")
        }
        val prof = root.getAsJsonObject("profile")
        val profileSlice = if (prof != null) {
            ParsedPortable.ProfileSlice(
                sourceFilename = prof.get("source_filename")?.asString ?: "imported.json",
                rawPersonaText = prof.get("raw_persona_text")?.asString ?: "",
                characterCardMarkdown = prof.get("character_card_markdown")?.asString ?: "",
                characterCardJson = prof.get("character_card_json")?.let { gson.toJson(it) } ?: "{}"
            )
        } else null
        return ParsedPortable(
            name = root.get("name")?.asString ?: "",
            personaPrompt = root.get("persona_prompt")?.asString ?: "",
            modelName = root.get("model_name")?.asString,
            apiBaseUrl = root.get("api_base_url")?.asString,
            temperature = parseOptionalSampling(root.get("temperature"), "温度"),
            maxTokens = parseOptionalMaxTokens(root.get("max_tokens")),
            topP = parseOptionalSampling(root.get("top_p"), "Top P"),
            frequencyPenalty = parseOptionalSampling(root.get("frequency_penalty"), "频率惩罚"),
            presencePenalty = parseOptionalSampling(root.get("presence_penalty"), "存在惩罚"),
            avatarColor = root.get("avatar_color")?.asString,
            avatarImagePath = root.get("avatar_image_path")?.takeIf { !it.isJsonNull }?.asString,
            cardImagePath = root.get("card_image_path")?.takeIf { !it.isJsonNull }?.asString,
            profile = profileSlice
        )
    }

    fun parseTxt(text: String): JsonObject {
        val raw = text.trim()
        val legacyHeader = "# $LEGACY_KIND v$VERSION"
        val headerRest = when {
            raw.startsWith(TXT_HEADER) -> raw.removePrefix(TXT_HEADER).trimStart()
            raw.startsWith(legacyHeader) -> raw.removePrefix(legacyHeader).trimStart()
            else -> null
        }
        if (headerRest == null) {
            val lines = raw.lines()
            val name = lines.firstOrNull()?.trim()?.take(120) ?: "未命名"
            val persona = lines.drop(1).joinToString("\n").trim()
            val o = JsonObject()
            o.addProperty("kind", KIND)
            o.addProperty("version", VERSION)
            o.addProperty("name", name)
            o.addProperty("persona_prompt", persona)
            return o
        }
        val rest = headerRest
        val idx = rest.indexOf(TXT_SEP)
        if (idx < 0) throw IllegalArgumentException("TXT 缺少 --- 分隔")
        val meta = gson.fromJson(rest.substring(0, idx).trim(), JsonObject::class.java)
        val persona = rest.substring(idx + TXT_SEP.length).trim()
        meta.addProperty("persona_prompt", persona)
        return meta
    }

    /** 酒馆式 JSON：顶层或 data 下含 name / description 等 */
    fun tryParseTavernLike(root: JsonObject, sourceFilename: String = "imported.json"): ParsedPortable? {
        val data = root.getAsJsonObject("data") ?: root
        data.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return null
        return CharacterCardV2Converter.jsonRootToParsedPortable(root, sourceFilename)
    }

    fun mergeSummaryIntoPortable(summaryJson: JsonObject, entity: CharacterEntity? = null): JsonObject {
        val out = JsonObject()
        out.addProperty("kind", KIND)
        out.addProperty("version", VERSION)
        out.addProperty("name", summaryJson.get("name")?.asString ?: "")
        out.addProperty("persona_prompt", summaryJson.get("persona_prompt")?.asString ?: "")
        out.addProperty("model_name", summaryJson.get("model_name")?.asString ?: "")
        out.addProperty("api_base_url", summaryJson.get("api_base_url")?.asString ?: "")
        out.addProperty("notes", summaryJson.get("notes")?.asString ?: "")
        entity?.let {
            out.addProperty("temperature", it.temperature)
            out.addProperty("max_tokens", it.maxTokens)
            out.addProperty("top_p", it.topP)
            out.addProperty("frequency_penalty", it.frequencyPenalty)
            out.addProperty("presence_penalty", it.presencePenalty)
        }
        return out
    }

    const val SUMMARY_SYSTEM_PROMPT = """你是「角色卡便携导出」助手。用户要把角色设定整理成可分享给他人、并能被墨境再导入的结构。

硬性规则：
1. 只输出一个 JSON 对象，不要 Markdown、不要解释。
2. JSON 必须符合以下键（缺失的用空字符串）：
   - kind: 固定字符串 "mojing_character_portable"
   - version: 固定数字 1
   - name: 角色名称（简短）
   - persona_prompt: 合并后的完整角色设定正文，用中文；保留重要设定，可删减重复与对话示例；总长度建议不超过 8000 字。
   - model_name, api_base_url: 若原文有且适合分享则填入，否则填空字符串（不要编造 Key）。
   - notes: 一两句给导入者的说明（中文），提醒对方导入后自行配置 API Key。
3. 绝对不要输出任何 api key、token、密码字段。
4. persona_prompt 必须自洽、可直接用于角色扮演。"""

    fun extractJsonFromLlmResponse(raw: String): JsonObject {
        var s = raw.trim()
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
        fence.find(s)?.let { s = it.groupValues[1].trim() }
        return gson.fromJson(s, JsonObject::class.java)
    }

    fun summaryUserPayload(dump: String): String =
        "以下是当前角色的原始资料（可能很长）。请生成符合规则的 JSON：\n\n${dump.take(120000)}"
}

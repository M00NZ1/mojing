package com.mojing.app.domain.story

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.data.local.entity.WorldTemplateEntity
import java.util.UUID

/** 已完成开篇的保存快照；只包含正文与玩法，不保存模型凭据或角色配置。 */
data class StoryOpeningDraft(
    val id: String = UUID.randomUUID().toString(),
    val premise: String,
    val direction: String,
    val tone: String,
    val template: WorldTemplateEntity?,
    val encyclopediaId: Long?,
    val characterIds: List<Long>,
    val worldPrompt: String,
    val result: StoryWritingResult,
    val model: String,
)

sealed interface StoryOpeningRecord {
    val id: String
    data class Pending(val draft: StoryOpeningDraft) : StoryOpeningRecord { override val id get() = draft.id }
    data class Saved(override val id: String, val sessionId: Long, val title: String, val sessionExists: Boolean = true) : StoryOpeningRecord
}

object StoryOpeningDraftCodec {
    const val KEY = "story_opening_draft_v1"
    private val gson = Gson()

    fun encode(record: StoryOpeningRecord): String = JsonObject().apply {
        addProperty("version", 1)
        addProperty("id", record.id)
        when (record) {
            is StoryOpeningRecord.Pending -> {
                addProperty("state", "pending")
                add("draft", gson.toJsonTree(record.draft))
            }
            is StoryOpeningRecord.Saved -> {
                addProperty("state", "saved")
                addProperty("sessionId", record.sessionId)
                addProperty("title", record.title)
            }
        }
    }.toString()

    fun decode(raw: String): StoryOpeningRecord {
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.get("version").toString() == "1") { "Unsupported story draft version" }
        val id = root.get("id").asString
        require(UUID.fromString(id).toString() == id)
        return when (root.get("state").asString) {
            "pending" -> {
                val payload = root.getAsJsonObject("draft")
                requireStrings(payload, "id", "premise", "direction", "tone", "worldPrompt", "model")
                val result = payload.getAsJsonObject("result")
                requireStrings(result, "title")
                result.getAsJsonArray("chapters").forEach { requireStrings(it.asJsonObject, "title", "content") }
                result.getAsJsonArray("nextChoices").forEach { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }
                payload.get("template")?.takeUnless { it.isJsonNull }?.asJsonObject?.let {
                    requireStrings(it, "templateId", "label", "summary", "gameplayMode", "worldPrompt", "antiCheatPrompt", "suggestedChoicesJson")
                }
                val draft = gson.fromJson(payload, StoryOpeningDraft::class.java)
                require(draft.id == id && draft.premise.isNotBlank())
                require(draft.result.title.isNotBlank() && draft.result.chapters.size in 1..10)
                require(draft.result.chapters.all { it.number > 0 && it.title.isNotBlank() && it.content.isNotBlank() })
                require(draft.characterIds.all { it > 0 } && draft.result.nextChoices.all { it.isNotBlank() })
                StoryOpeningRecord.Pending(draft)
            }
            "saved" -> StoryOpeningRecord.Saved(id, root.get("sessionId").asLong, root.get("title").asString)
                .also { require(it.sessionId > 0) }
            else -> error("Unsupported story draft state")
        }
    }

    private fun requireStrings(objectValue: JsonObject, vararg names: String) {
        names.forEach { name ->
            val value = objectValue.get(name)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid story draft field" }
        }
    }
}

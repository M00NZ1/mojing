package com.mojing.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class CharacterDraftSnapshot(
    @SerializedName("name") val name: String = "",
    @SerializedName("personaPrompt") val personaPrompt: String = "",
    @SerializedName("apiKey") val apiKey: String = "",
    @SerializedName("apiBaseUrl") val apiBaseUrl: String = "",
    @SerializedName("modelName") val modelName: String = "",
    @SerializedName("temperature") val temperature: String = "0.9",
    @SerializedName("maxTokens") val maxTokens: String = "1200",
    @SerializedName("topP") val topP: String = "1.0",
    @SerializedName("frequencyPenalty") val frequencyPenalty: String = "0.0",
    @SerializedName("presencePenalty") val presencePenalty: String = "0.0",
    @SerializedName("avatarColor") val avatarColor: String = "#F97316",
    @SerializedName("avatarImagePath") val avatarImagePath: String = "",
    @SerializedName("cardImagePath") val cardImagePath: String = "",
    @SerializedName("characterCardJsonRaw") val characterCardJsonRaw: String = "{}",
    @SerializedName("thinkMaxEnabled") val thinkMaxEnabled: Boolean = false,
    @SerializedName("thinkMaxModelName") val thinkMaxModelName: String = "",
    @SerializedName("imageGenEnabled") val imageGenEnabled: Boolean = false,
    @SerializedName("imageGenApiKey") val imageGenApiKey: String = "",
    @SerializedName("imageGenBaseUrl") val imageGenBaseUrl: String = "",
    @SerializedName("imageGenModel") val imageGenModel: String = "",
    @SerializedName("voiceProvider") val voiceProvider: String = "",
    @SerializedName("voiceApiBaseUrl") val voiceApiBaseUrl: String = "",
    @SerializedName("voiceApiKey") val voiceApiKey: String = "",
    @SerializedName("voiceModel") val voiceModel: String = "system",
    @SerializedName("boundEncyclopediaId") val boundEncyclopediaId: Long = 0L,
)

private data class StoredCharacterDraft(
    @SerializedName("version") val version: Int,
    @SerializedName("snapshot") val snapshot: CharacterDraftSnapshot,
)

/** Only unsaved editor input lives here. Saved character rows remain the source of truth. */
@Singleton
class CharacterEditDraftStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("character_edit_drafts_v1", Context.MODE_PRIVATE)
    private val gson = Gson()

    suspend fun load(characterId: Long): CharacterDraftSnapshot? = withContext(Dispatchers.IO) {
        if (characterId <= 0L) return@withContext null
        val raw = preferences.getString(key(characterId), null) ?: return@withContext null
        val stored = runCatching {
            val root = JsonParser.parseString(raw).asJsonObject
            check(root.get("version")?.asInt == 1)
            val snapshot = root.getAsJsonObject("snapshot")
            val fields = listOf("name", "personaPrompt", "apiKey", "apiBaseUrl", "modelName",
                "temperature", "maxTokens", "topP", "frequencyPenalty", "presencePenalty",
                "avatarColor", "avatarImagePath", "cardImagePath", "characterCardJsonRaw",
                "thinkMaxEnabled", "thinkMaxModelName", "imageGenEnabled", "imageGenApiKey",
                "imageGenBaseUrl", "imageGenModel", "voiceProvider", "voiceApiBaseUrl",
                "voiceApiKey", "voiceModel", "boundEncyclopediaId")
            check(fields.all { snapshot.get(it)?.isJsonPrimitive == true })
            gson.fromJson(raw, StoredCharacterDraft::class.java)
        }.getOrNull() ?: error("角色草稿无法读取，请保留本机数据并重试")
        stored.snapshot
    }

    suspend fun save(characterId: Long, draft: CharacterDraftSnapshot) = withContext(Dispatchers.IO) {
        require(characterId > 0L)
        check(preferences.edit().putString(key(characterId), gson.toJson(StoredCharacterDraft(1, draft))).commit()) {
            "角色草稿暂存失败"
        }
    }

    suspend fun clear(characterId: Long) = withContext(Dispatchers.IO) {
        if (characterId <= 0L) return@withContext
        check(preferences.edit().remove(key(characterId)).commit()) { "角色草稿清除失败" }
    }

    private fun key(id: Long) = "character_$id"
}

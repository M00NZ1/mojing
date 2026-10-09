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

data class TemplateEditDraft(
    @SerializedName("label") val label: String = "",
    @SerializedName("templateId") val templateId: String = "",
    @SerializedName("category") val category: String = "玄幻",
    @SerializedName("summary") val summary: String = "",
    @SerializedName("gameplayMode") val gameplayMode: String = "自由剧情",
    @SerializedName("worldPrompt") val worldPrompt: String = "",
    @SerializedName("antiCheatPrompt") val antiCheatPrompt: String = "",
    @SerializedName("suggestedChoicesJson") val suggestedChoicesJson: String = "[]",
    @SerializedName("coverImagePath") val coverImagePath: String = "",
)

private data class StoredTemplateDraft(
    @SerializedName("version") val version: Int,
    @SerializedName("snapshot") val snapshot: TemplateEditDraft,
)

/** Only unsaved template editor input lives here; saved templates remain in Room. */
@Singleton
class TemplateEditDraftStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("template_edit_drafts_v1", Context.MODE_PRIVATE)
    private val gson = Gson()

    suspend fun load(templateRowId: Long): TemplateEditDraft? = withContext(Dispatchers.IO) {
        loadRaw(keyForExisting(templateRowId))
    }

    suspend fun loadNew(): TemplateEditDraft? = withContext(Dispatchers.IO) {
        loadRaw(NEW_KEY)
    }

    suspend fun save(templateRowId: Long, draft: TemplateEditDraft) = withContext(Dispatchers.IO) {
        require(templateRowId > 0L)
        saveRaw(keyForExisting(templateRowId), draft)
    }

    suspend fun saveNew(draft: TemplateEditDraft) = withContext(Dispatchers.IO) {
        saveRaw(NEW_KEY, draft)
    }

    suspend fun clear(templateRowId: Long) = withContext(Dispatchers.IO) {
        if (templateRowId > 0L) clearRaw(keyForExisting(templateRowId))
    }

    suspend fun clearNew() = withContext(Dispatchers.IO) {
        clearRaw(NEW_KEY)
    }

    /** Move the new-editor draft to the assigned Room row while preserving edits made during save. */
    suspend fun syncAfterFirstSave(savedTemplateRowId: Long, remainingDraft: TemplateEditDraft?) = withContext(Dispatchers.IO) {
        require(savedTemplateRowId > 0L)
        val editor = preferences.edit().remove(NEW_KEY)
        if (remainingDraft == null) editor.remove(keyForExisting(savedTemplateRowId))
        else editor.putString(keyForExisting(savedTemplateRowId), gson.toJson(StoredTemplateDraft(1, remainingDraft)))
        check(editor.commit()) { "模板草稿转移失败" }
    }

    private fun loadRaw(key: String): TemplateEditDraft? {
        val raw = preferences.getString(key, null) ?: return null
        val stored = runCatching {
            val root = JsonParser.parseString(raw).asJsonObject
            check(root.get("version")?.asInt == 1)
            val snapshot = root.getAsJsonObject("snapshot")
            val fields = listOf("label", "templateId", "category", "summary", "gameplayMode",
                "worldPrompt", "antiCheatPrompt", "suggestedChoicesJson", "coverImagePath")
            check(fields.all { snapshot.get(it)?.isJsonPrimitive == true })
            gson.fromJson(raw, StoredTemplateDraft::class.java)
        }.getOrNull() ?: error("模板草稿无法读取，请保留本机数据并重试")
        return stored.snapshot
    }

    private fun saveRaw(key: String, draft: TemplateEditDraft) {
        check(preferences.edit().putString(key, gson.toJson(StoredTemplateDraft(1, draft))).commit()) {
            "模板草稿暂存失败"
        }
    }

    private fun clearRaw(key: String) {
        check(preferences.edit().remove(key).commit()) { "模板草稿清除失败" }
    }

    private fun keyForExisting(id: Long) = "template_$id"

    private companion object { const val NEW_KEY = "template_new" }
}

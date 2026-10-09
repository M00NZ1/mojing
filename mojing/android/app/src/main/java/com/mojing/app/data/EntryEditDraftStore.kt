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

data class EntryDraftSnapshot(
    @SerializedName("title") val title: String = "",
    @SerializedName("entryType") val entryType: String = "character",
    @SerializedName("summary") val summary: String = "",
    @SerializedName("content") val content: String = "",
    @SerializedName("tags") val tags: String = "",
    @SerializedName("confidence") val confidence: String = "confirmed",
    @SerializedName("metaJson") val metaJson: String = "{}",
    @SerializedName("isFeatured") val isFeatured: Boolean = false,
    @SerializedName("coverImagePath") val coverImagePath: String = "",
    /** 生图时的临时补充说明；旧 v1 草稿缺少该字段时按空字符串兼容。 */
    @SerializedName("coverPromptHint") val coverPromptHint: String = "",
)

private data class StoredEntryDraft(
    @SerializedName("version") val version: Int,
    @SerializedName("snapshot") val snapshot: EntryDraftSnapshot,
)

/** An unsaved editor snapshot; Room remains the source of saved entries. */
@Singleton
class EntryEditDraftStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("entry_edit_drafts_v1", Context.MODE_PRIVATE)
    private val gson = Gson()

    suspend fun load(encyclopediaId: Long, entryId: Long): EntryDraftSnapshot? = withContext(Dispatchers.IO) {
        if (encyclopediaId <= 0L || entryId < 0L) return@withContext null
        val raw = preferences.getString(key(encyclopediaId, entryId), null) ?: return@withContext null
        val stored = runCatching {
            val root = JsonParser.parseString(raw).asJsonObject
            check(root.get("version")?.asInt == 1)
            val snapshot = root.getAsJsonObject("snapshot")
            val fields = listOf("title", "entryType", "summary", "content", "tags", "confidence",
                "metaJson", "isFeatured", "coverImagePath")
            check(fields.all { snapshot.get(it)?.isJsonPrimitive == true })
            val decoded = gson.fromJson(raw, StoredEntryDraft::class.java)
            decoded.copy(snapshot = decoded.snapshot.copy(coverPromptHint = decoded.snapshot.coverPromptHint.orEmpty()))
        }.getOrNull() ?: error("词条草稿无法读取，请保留本机数据并重试")
        stored.snapshot
    }

    suspend fun save(encyclopediaId: Long, entryId: Long, draft: EntryDraftSnapshot) = withContext(Dispatchers.IO) {
        require(encyclopediaId > 0L && entryId >= 0L)
        check(preferences.edit().putString(key(encyclopediaId, entryId), gson.toJson(StoredEntryDraft(1, draft))).commit()) {
            "词条草稿暂存失败"
        }
    }

    suspend fun clear(encyclopediaId: Long, entryId: Long) = withContext(Dispatchers.IO) {
        if (encyclopediaId <= 0L || entryId < 0L) return@withContext
        check(preferences.edit().remove(key(encyclopediaId, entryId)).commit()) { "词条草稿清除失败" }
    }

    /** Move the pre-save draft key to the assigned entry ID in one preferences write. */
    suspend fun syncAfterFirstSave(encyclopediaId: Long, savedEntryId: Long, remainingDraft: EntryDraftSnapshot?) = withContext(Dispatchers.IO) {
        require(encyclopediaId > 0L && savedEntryId > 0L)
        val editor = preferences.edit().remove(key(encyclopediaId, 0L))
        if (remainingDraft == null) editor.remove(key(encyclopediaId, savedEntryId))
        else editor.putString(key(encyclopediaId, savedEntryId), gson.toJson(StoredEntryDraft(1, remainingDraft)))
        check(editor.commit()) { "词条草稿转移失败" }
    }

    private fun key(encyclopediaId: Long, entryId: Long) = "encyclopedia_${encyclopediaId}_entry_$entryId"
}

package com.mojing.app.ui

import android.content.SharedPreferences
import android.os.Build
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.local.entity.*
import com.mojing.app.ui.chat.ChatViewModel
import com.mojing.app.ui.chat.RetainedChatSessions
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** UUID fixture only; durable private config journal is restored in a separate host-run phase. */
class MemorySummaryRequestAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val run get() = args.getString("memoryRun").orEmpty().also { UUID.fromString(it) }
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "memory-request-$run.json")
    private val keys get() = listOf("public_api_key", "public_base_url", "public_model", "speaker_turn_mode") +
        if (args.getString("totalBudgetCapture") == "true") listOf("model_platforms_v1") else emptyList()
    private val preferences get() = rule.activity.secureStorage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
        .get(rule.activity.secureStorage) as SharedPreferences
    private fun guard() {
        assumeTrue(args.getString("memoryRequestCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "memory-request-$run.pending")
        FileOutputStream(pending).use { it.write(j.toString().toByteArray()); it.fd.sync() }
        check(pending.renameTo(journalFile))
    }
    private fun budgetScreenshot(label: String) {
        rule.waitForIdle()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        FileOutputStream(File(context.getExternalFilesDir(null), "total-budget-$run-$label.png")).use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
    private fun model(id: Long): ChatViewModel {
        val stores = RetainedChatSessions.stores
        val entries = stores.javaClass.getDeclaredField("entries").apply { isAccessible = true }.get(stores) as Map<*, *>
        val entry = checkNotNull(entries[id])
        return entry.javaClass.getDeclaredField("model").apply { isAccessible = true }.get(entry) as ChatViewModel
    }
    @Test fun sendCharacterReplyWithPersistedBranchSummary() {
        sendReplyWithPersistedBranchSummary(narrator = false)
    }
    @Test fun sendNarratorReplyWithPersistedBranchSummary() {
        sendReplyWithPersistedBranchSummary(narrator = true)
    }
    @Test fun sendNovelChapterWithPersistedBranchSummary() {
        sendReplyWithPersistedBranchSummary(narrator = true, novel = true)
    }
    @Test fun sendLongCharacterInputWithPersistedBranchSummary() {
        sendReplyWithPersistedBranchSummary(narrator = false, longInput = true)
    }
    @Test fun editExcludedInputKeepsExclusionsInActualBranchRequest() {
        sendReplyWithPersistedBranchSummary(narrator = false, editExcluded = true)
    }
    @Test fun rejectConfiguredCapacityThenContinueWithPreservedInput() {
        check(args.getString("totalBudgetCapture") == "true")
        sendReplyWithPersistedBranchSummary(narrator = false, longInput = true, budgetCheck = true)
    }
    private fun sendReplyWithPersistedBranchSummary(narrator: Boolean, novel: Boolean = false, longInput: Boolean = false, editExcluded: Boolean = false, budgetCheck: Boolean = false) {
        guard()
        check(!journalFile.exists())
        check(RetainedChatSessions.running.value.isEmpty())
        val base = args.getString("localBase").orEmpty()
        check(base.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/v1")))
        val original = JsonObject()
        keys.forEach { key -> original.add(key, JsonObject().apply {
            addProperty("present", preferences.contains(key))
            if (preferences.contains(key)) addProperty("value", preferences.getString(key, null))
        }) }
        val j = JsonObject().apply { addProperty("run", run); add("original", original); addProperty("phase", "snapshot") }
        persist(j)
        val id = runBlocking(Dispatchers.IO) {
            val character = db.characterDao().upsert(CharacterEntity(name = "摘要角色-$run", personaPrompt = "SYNTHETIC_$run"))
            j.addProperty("character", character); persist(j)
            val id = db.sessionDao().insert(SessionEntity(title = "摘要请求-$run", creationRequestId = run))
            j.addProperty("session", id); persist(j)
            db.withTransaction {
                db.participantDao().upsert(SessionParticipantEntity(sessionId = id, characterId = character, forceNext = true))
                db.sessionWorldDao().upsert(SessionWorldEntity(sessionId = id, narratorEnabled = false,
                    narratorName = "摘要旁白-$run",
                    gameplayMode = if (novel) "小说创作" else "自由剧情",
                    choiceGenerationEnabled = novel, autoSedimentEnabled = false,
                    sessionLlmApiKey = "local-memory-only", sessionLlmBaseUrl = base))
                val msg = db.messageDao().insert(MessageEntity(sessionId = id, speakerType = "user", content = "SEED_$run"))
                db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(sessionId = id,
                    startMessageId = msg, endMessageId = msg, summary = "MAIN_SUMMARY_$run：{{char}}答应带{{user}}去北塔。"))
                db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(sessionId = id, branchId = "sibling-$run",
                    startMessageId = msg, endMessageId = msg, summary = "SIBLING_SUMMARY_$run"))
                if (novel) db.messageDao().insert(MessageEntity(sessionId = id, speakerType = "narrator",
                    content = "第一章\nSEED_CHAPTER_$run", structuredContentJson = com.mojing.app.domain.story.NovelChapter.metadata("{}", 1, "第一章")))
                if (editExcluded) {
                    val earlier = db.messageDao().insert(MessageEntity(sessionId = id, content = "EXCLUDED_EARLIER_$run"))
                    check(db.messageDao().setContextExcluded(id, "main", earlier, true))
                    val source = db.messageDao().insert(MessageEntity(sessionId = id, content = "EXCLUDED_ORIGINAL_$run"))
                    j.addProperty("editSource", source); persist(j)
                }
            }
            id
        }
        check(preferences.edit().putString("public_api_key", "local-memory-only").putString("public_base_url", base)
            .putString("public_model", "local-memory-model").putString("speaker_turn_mode", "auto").commit())
        if (budgetCheck) {
            val platform = com.mojing.app.data.ModelPlatform("budget-$run", "预算平台-$run", base, "local-memory-only",
                listOf("local-memory-model"), "local-memory-model", mapOf("local-memory-model" to 4000))
            runBlocking(Dispatchers.IO) {
                rule.activity.secureStorage.saveModelPlatform(platform, makeDefault = false)
                rule.activity.secureStorage.selectSessionModel(id, platform.id, platform.selectedModel)
            }
        }
        j.addProperty("phase", "configured"); persist(j)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(10_000) { rule.onAllNodesWithText("摘要请求-$run").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("摘要请求-$run").onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() && model(id).state.value.isReady }
        val input = if (longInput) "LONG_START_$run\n" + "长".repeat(2700) + "\nLONG_END_$run" else "SEND_$run"
        if (editExcluded) {
            val source = j.get("editSource").asLong
            val excluded = java.util.concurrent.atomic.AtomicBoolean(false)
            instrumentation.runOnMainSync { model(id).setMessageContextExcluded(source, true) { excluded.set(it) } }
            rule.waitUntil(10_000) { excluded.get() }
            instrumentation.runOnMainSync { model(id).editMessage(source, "EXCLUDED_EDITED_$run") }
        } else if (novel) {
            rule.onAllNodesWithContentDescription("小说目录").onFirst().performClick()
            rule.onAllNodesWithText("生成下一章").onFirst().performClick()
            rule.onAllNodesWithText("开始生成").onFirst().performClick()
        } else if (narrator) {
            rule.onAllNodesWithContentDescription("更多输入工具").onFirst().performClick()
            rule.onAllNodesWithText("生成旁白").onFirst().performClick()
            rule.onAllNodesWithText("自动生成旁白").onFirst().performClick()
        } else {
            rule.onAllNodesWithContentDescription("消息输入").onFirst().performTextInput(input)
            rule.waitUntil(5_000) { model(id).state.value.inputText == input }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitForIdle()
            rule.onAllNodesWithContentDescription("发送").onFirst().performClick()
        }
        if (budgetCheck) {
            rule.waitUntil(15_000) { rule.onAllNodesWithText("超过已设置的上下文总容量", substring = true).fetchSemanticsNodes().isNotEmpty() }
            budgetScreenshot("rejected")
            rule.waitUntil(10_000) { !model(id).state.value.isGenerating && id !in RetainedChatSessions.running.value }
            check(runBlocking(Dispatchers.IO) {
                db.messageDao().getBranchMessages(id, "main").count { it.speakerType == "user" && it.content == input } == 1 &&
                    db.messageDao().getBranchMessages(id, "main").none { it.speakerType == "character" && it.content.contains("REPLY_$run") }
            })
            // Existing settings UI writes the capacity through its production save owner.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitForIdle()
            rule.onAllNodesWithText("设置").filter(hasClickAction()).onLast().performClick()
            rule.onAllNodesWithText("平台与模型").onFirst().performClick()
            rule.onAllNodesWithText("预算平台-$run").onFirst().performClick()
            rule.onAllNodesWithText("配置").onLast().performClick()
            rule.onAllNodesWithText("设置上下文容量（可选）").onFirst().performScrollTo().performClick()
            rule.onNodeWithTag("context-capacity-local-memory-model").performScrollTo()
            rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("context-capacity-local-memory-model")), useUnmergedTree = true)
                .performTextReplacement("20000")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            budgetScreenshot("capacity")
            rule.onAllNodesWithText("保存平台").onFirst().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("编辑平台").fetchSemanticsNodes().isEmpty() }
            check(rule.activity.secureStorage.modelPlatforms().single { it.id == "budget-$run" }.modelContextWindows["local-memory-model"] == 20000)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitForIdle()
            rule.onAllNodesWithText("对话").filter(hasClickAction()).onLast().performClick()
            rule.onAllNodesWithText("摘要请求-$run").onFirst().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() && model(id).state.value.isReady }
            rule.onAllNodesWithContentDescription("消息输入").onFirst().performTextInput("CONTINUE_$run")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.onAllNodesWithContentDescription("发送").onFirst().performClick()
        }
        rule.waitUntil(25_000) { runBlocking(Dispatchers.IO) {
            db.messageDao().getBranchMessages(id, model(id).state.value.currentBranchId).any {
                it.speakerType == (if (narrator) "narrator" else "character") && it.content.contains("REPLY_$run")
            }
        } }
        rule.waitUntil(10_000) { !model(id).state.value.isGenerating && id !in RetainedChatSessions.running.value }
        j.addProperty("phase", "generated"); persist(j)
        if (editExcluded) check(runBlocking(Dispatchers.IO) {
            val branch = model(id).state.value.currentBranchId
            val edited = db.messageDao().getBranchMessages(id, branch).single { it.content == "EXCLUDED_EDITED_$run" }
            branch != "main" && db.messageDao().getVisibleContextTail(id, branch, 20).none { it.content.startsWith("EXCLUDED_") } &&
                db.messageDao().getExcludedContextKeys(id, branch, listOf(edited.contextSelectionKey())).isNotEmpty() &&
                db.messageDao().getMainMessageById(id, j.get("editSource").asLong)?.content == "EXCLUDED_ORIGINAL_$run"
        })
        if (longInput) check(runBlocking(Dispatchers.IO) {
            db.messageDao().getBranchMessages(id, "main").count { it.speakerType == "user" && it.content == input } == 1
        })
        check(runBlocking(Dispatchers.IO) { db.sessionMemorySegmentDao().getRecentForBranch(id, "main").single().summary.startsWith("MAIN_SUMMARY_$run") })
        if (novel) check(runBlocking(Dispatchers.IO) {
            db.messageDao().getBranchMessages(id, "main").single { it.content.contains("REPLY_$run") }.let {
                com.mojing.app.domain.story.NovelChapter.number(it.structuredContentJson) == 2 && !it.content.contains("<CHOICES>")
            }
        })
    }

    @Test fun rollbackExactFixtureAndConfiguration() {
        guard()
        val j = JsonParser.parseString(journalFile.readText()).asJsonObject
        check(j.get("run").asString == run)
        val id = j.get("session")?.asLong ?: 0L
        if (id > 0L) {
            instrumentation.runOnMainSync { RetainedChatSessions.stores.stop(id) }
            rule.waitUntil(10_000) { id !in RetainedChatSessions.running.value }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitForIdle()
        }
        val original = j.getAsJsonObject("original")
        val editor = preferences.edit()
        keys.forEach { key -> val v = original.getAsJsonObject(key)
            if (v.get("present").asBoolean) editor.putString(key, v.get("value").asString) else editor.remove(key)
        }
        check(editor.commit())
        keys.forEach { key -> val v = original.getAsJsonObject(key)
            check(preferences.contains(key) == v.get("present").asBoolean)
            if (v.get("present").asBoolean) check(preferences.getString(key, null) == v.get("value").asString)
        }
        runBlocking(Dispatchers.IO) {
            db.withTransaction {
                if (id > 0L) {
                    val owned = db.sessionDao().getById(id)
                    check(owned == null || owned.creationRequestId == run)
                    db.openHelper.writableDatabase.execSQL("DELETE FROM llm_cost_records WHERE sessionId = ?", arrayOf(id))
                    db.sessionDao().delete(id)
                    check(db.sessionDao().getById(id) == null)
                }
                j.get("character")?.asLong?.let { character ->
                    val owned = db.characterDao().getById(character)
                    check(owned == null || owned.name == "摘要角色-$run")
                    db.characterDao().delete(character)
                }
            }
        }
        if (id > 0L) check(context.getSharedPreferences("chat_drafts_v1", 0).edit()
            .remove("session_$id").remove("reply_recovery_v1_session_$id").remove("chapter_input_v1_${id}_main").commit())
        if (args.getString("totalBudgetCapture") == "true" && id > 0L) {
            check(preferences.edit().remove("chat_platform_$id").remove("chat_model_$id").commit())
            check(rule.activity.secureStorage.sessionModelSelection(id) == null)
        }
        j.addProperty("phase", "rolled-back"); persist(j)
    }
}

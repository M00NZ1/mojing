package com.mojing.app.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.MessageEditDraftScope
import com.mojing.app.data.MessageEditDraftStore
import com.mojing.app.data.StoryOpeningInputDraftStore
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.ui.chat.RetainedChatSessions
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CorrectionSourceAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("sourceRun").orEmpty().also { UUID.fromString(it) }
    private val title get() = "纠正航记${run.take(8)}"
    private val branch get() = "source_$run"
    private val mainBody get() = "MAIN_$run：主线记录灯塔。"
    private val sourceBody get() = "SOURCE_$run：子线独有原文，守灯人已知道新航路。"
    private val correction get() = "CORRECTION_$run：守灯人已知道新航路，不能再写作未知。"
    private val summary get() = "SUMMARY_$run：守灯人尚不知道新航路。"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val prefs get() = UiPreferencesRepository(context)
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val journalFile get() = File(context.filesDir, "correction-source-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "correction-source-$run").apply { mkdirs() }
    private fun guard() {
        check(args.getString("sourceCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "correction-source-$run.pending")
        FileOutputStream(pending).use { it.write(j.toString().toByteArray()); it.fd.sync() }
        check(pending.renameTo(journalFile))
    }
    private fun journal() = JsonParser.parseString(journalFile.readText()).asJsonObject.also { check(it.get("run").asString == run) }
    private fun branchPrefs() = JsonObject().apply { runBlocking(Dispatchers.IO) { prefs.lastChatBranches.first() }.forEach { (id, b) -> addProperty(id.toString(), b) } }
    private fun back() { inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); rule.waitForIdle() }
    private fun openMemory(section: String) {
        rule.onNodeWithContentDescription("会话设置与资料").performClick(); waitText("记忆")
        rule.onAllNodesWithText("记忆").filter(hasClickAction()).onLast().performClick()
        rule.onAllNodesWithText(section, substring = true).filter(hasClickAction()).onFirst().performClick()
    }
    private fun selectMain() {
        rule.onNodeWithContentDescription("会话菜单").performClick(); waitText("故事线")
        rule.onNodeWithText("故事线").performClick(); waitText("主线剧情")
        rule.onNodeWithText("主线剧情").performClick(); waitText(mainBody)
    }
    private fun snapshot(p: SharedPreferences) = JsonObject().apply {
        p.all.forEach { (key, value) -> add(key, JsonObject().apply {
            when (value) {
                is String -> { addProperty("type", "string"); addProperty("value", value) }
                is Boolean -> { addProperty("type", "boolean"); addProperty("value", value) }
                else -> error("unexpected draft type")
            }
        }) }
    }
    private fun assertUnchanged(p: SharedPreferences, original: JsonObject) {
        check(p.all.keys == original.keySet())
        original.entrySet().forEach { (key, value) ->
            val row = value.asJsonObject
            if (row.get("type").asString == "string") check(p.all[key] is String && p.getString(key, null) == row.get("value").asString)
            else check(p.all[key] is Boolean && p.getBoolean(key, false) == row.get("value").asBoolean)
        }
    }
    private fun waitText(text: String) {
        rule.waitUntil(10000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun hideKeyboard() {
        rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0) }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle(); inst.waitForIdleSync()
        repeat(2) {
            val committed = CountDownLatch(1)
            rule.runOnUiThread {
                val decor = rule.activity.window.decorView
                decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }; decor.invalidate()
            }
            check(committed.await(3, TimeUnit.SECONDS))
        }
        inst.uiAutomation.waitForIdle(300, 5000)
        val bitmap = checkNotNull(inst.uiAutomation.takeScreenshot())
        try { FileOutputStream(output.resolve("$name.png")).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun openStory() {
        rule.waitUntil(10000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasText("对话") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        waitText("故事库")
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(title)
        hideKeyboard()
        rule.waitUntil(10000) { rule.onAllNodesWithText(title).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(title).filter(!hasSetTextAction()).onLast().performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun wholeConversationCorrectionOpensItsSourceLine() {
        guard(); check(!journalFile.exists())
        try {
            val j = JsonObject().apply {
                addProperty("run", run); add("originalWorldDrafts", snapshot(worldPrefs)); add("originalCharacterDrafts", snapshot(characterPrefs)); add("originalChatDrafts", snapshot(chatPrefs)); add("originalBranchPrefs", branchPrefs())
            }; persist(j)
            val sid = runBlocking(Dispatchers.IO) { db.withTransaction {
                val id = db.sessionDao().insert(SessionEntity(title = title, creationRequestId = run)); j.addProperty("session", id); persist(j)
                db.sessionWorldDao().upsert(SessionWorldEntity(sessionId = id, narratorEnabled = false))
                val mid = db.messageDao().insert(MessageEntity(sessionId = id, speakerType = "narrator", content = mainBody)); j.addProperty("main", mid); persist(j)
                val bid = db.sessionBranchDao().insert(SessionBranchEntity(sessionId = id, branchId = branch, label = "航路子线", sourceMessageId = mid)); j.addProperty("branch", bid); persist(j)
                val source = db.messageDao().insert(MessageEntity(sessionId = id, branchId = branch, speakerType = "narrator", content = sourceBody)); j.addProperty("source", source); persist(j)
                val segment = db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(sessionId = id, branchId = branch, startMessageId = source, endMessageId = source, summary = summary)); j.addProperty("segment", segment); persist(j)
                id
            } }
            runBlocking(Dispatchers.IO) { prefs.setLastChatBranch(sid, branch) }
            openStory(); waitText(sourceBody); openMemory("自动摘要"); waitText(summary)
            rule.onNodeWithText("纠正这段记忆").performScrollTo().performClick(); waitText("新增用户纠正")
            rule.onNodeWithText("整个对话").performClick()
            rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTextReplacement(correction)
            back(); rule.onNodeWithText("保存纠正").performClick()
            rule.waitUntil(10000) { runBlocking(Dispatchers.IO) { db.sessionMemoryCorrectionDao().getVisible(sid, "main").size } == 1 }
            val row = runBlocking(Dispatchers.IO) { db.sessionMemoryCorrectionDao().getVisible(sid, "main").single() }
            check(row.content == correction && row.branchId == null && row.sourceMessageId == j.get("source").asLong)
            j.addProperty("correction", row.id); j.add("correctionRow", com.google.gson.Gson().toJsonTree(row)); persist(j)
            rule.waitUntil(10000) { rule.onAllNodesWithText("新增用户纠正").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithContentDescription("关闭").performClick()
            selectMain(); openMemory("用户纠正"); waitText(correction)
            screenshot("whole-conversation-correction-on-main")
            rule.onNodeWithText("查看来源").performScrollTo().performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithText("原文不可用或加载失败").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText(sourceBody).fetchSemanticsNodes().isNotEmpty() }
            if (args.getString("expectOldFailure") == "true") {
                waitText("原文不可用或加载失败"); screenshot("before-source-not-found")
                check(runBlocking(Dispatchers.IO) { prefs.getLastChatBranch(sid) } == "main")
                j.addProperty("phase", "old-failure-confirmed"); persist(j)
                output.resolve("before.txt").writeText("currentMainSourceChildFails=true\ncorrectionRetained=true\n")
                rule.onNodeWithContentDescription("关闭").performClick(); rule.onNodeWithContentDescription("返回会话主页").performClick(); return
            }
            waitText(sourceBody)
            rule.waitUntil(10000) { rule.onAllNodesWithText("查看来源").fetchSemanticsNodes().isEmpty() }
            check(runBlocking(Dispatchers.IO) { prefs.getLastChatBranch(sid) } == branch)
            check(runBlocking(Dispatchers.IO) { db.sessionMemoryCorrectionDao().getVisible(sid, branch).single() } == row)
            screenshot("source-line-open-and-drawer-closed")
            rule.onNodeWithContentDescription("返回会话主页").performClick(); waitText("故事库"); openStory(); waitText(sourceBody)
            check(runBlocking(Dispatchers.IO) { prefs.getLastChatBranch(sid) } == branch)
            screenshot("reopened-source-line")
            // Controlled missing-source boundary after successful source navigation; retain the correction.
            selectMain()
            runBlocking(Dispatchers.IO) { db.messageDao().delete(j.get("source").asLong) }
            openMemory("用户纠正"); waitText(correction)
            rule.onNodeWithText("查看来源").performScrollTo().performClick(); waitText("原文不可用或加载失败")
            screenshot("missing-source-keeps-correction")
            check(runBlocking(Dispatchers.IO) { db.sessionMemoryCorrectionDao().getVisible(sid, "main").single() } == row)
            check(runBlocking(Dispatchers.IO) { prefs.getLastChatBranch(sid) } == "main")
            rule.onNodeWithContentDescription("关闭").performClick(); rule.onNodeWithContentDescription("返回会话主页").performClick()
            j.addProperty("phase", "verified"); persist(j)
            output.resolve("verified.txt").writeText("correctionCreatedBySummaryUi=true\nscopeAndSourceExact=true\nsourceLineOpened=true\ndrawerClosed=true\nreopenSameBranch=true\nmissingSourceKeepsCorrection=true\n")
        } catch (failure: Throwable) {
            output.resolve("failure.txt").writeText(failure.stackTraceToString())
            runCatching { screenshot("failure-window") }; throw failure
        }
    }
    @Test fun rollbackOnlyOwnedSessionAndPreferences() {
        guard(); val j = journal()
        j.get("session")?.asLong?.let { sid -> runBlocking(Dispatchers.IO) {
            val row = db.sessionDao().getById(sid); check(row == null || row.title == title && row.creationRequestId == run)
            db.sessionDao().delete(sid); prefs.clearLastChatBranch(sid)
            check(db.sessionDao().getById(sid) == null)
            check(db.sessionMemoryCorrectionDao().getVisible(sid, "main").isEmpty())
        }
            chatPrefs.all.keys.filter { it == "session_$sid" || it.startsWith("chapter_input_v1_${sid}_") || it == "reply_recovery_v1_session_$sid" }.forEach { check(chatPrefs.edit().remove(it).commit()) }
        }
        assertUnchanged(worldPrefs, j.getAsJsonObject("originalWorldDrafts")); assertUnchanged(characterPrefs, j.getAsJsonObject("originalCharacterDrafts")); assertUnchanged(chatPrefs, j.getAsJsonObject("originalChatDrafts"))
        check(branchPrefs() == j.getAsJsonObject("originalBranchPrefs"))
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("uuidSessionRemoved=true\notherDraftsAndBranchesExact=true\n")
    }
}

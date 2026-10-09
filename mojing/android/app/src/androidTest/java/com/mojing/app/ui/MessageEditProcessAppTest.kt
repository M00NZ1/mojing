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

class MessageEditProcessAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("editRun").orEmpty().also { UUID.fromString(it) }
    private val title get() = "修订航记${run.take(8)}"
    private val original get() = "EDIT_ORIGINAL_$run：守灯人仍留在港口。"
    private val edited get() = "EDIT_BEGIN_$run\n" + "守灯人确认明日航行的约定，并记录各自知道的边界。\n".repeat(900) + "EDIT_END_$run"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val store get() = MessageEditDraftStore(context)
    private val prefs get() = UiPreferencesRepository(context)
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val journalFile get() = File(context.filesDir, "edit-process-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "edit-process-$run").apply { mkdirs() }
    private fun scope(j: JsonObject) = MessageEditDraftScope(j.get("session").asLong, "main", j.get("message").asLong)
    private fun guard() {
        check(args.getString("editCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "edit-process-$run.pending")
        FileOutputStream(pending).use { it.write(j.toString().toByteArray()); it.fd.sync() }
        check(pending.renameTo(journalFile))
    }
    private fun journal() = JsonParser.parseString(journalFile.readText()).asJsonObject.also { check(it.get("run").asString == run) }
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
    private fun fileHashes() = JsonObject().apply {
        File(context.filesDir, "message-edit-drafts-v1").listFiles()?.filter { it.isFile }?.forEach {
            addProperty(it.name, MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) })
        }
    }
    private fun branchPrefs() = JsonObject().apply { runBlocking(Dispatchers.IO) { prefs.lastChatBranches.first() }.forEach { (id, branch) -> addProperty(id.toString(), branch) } }
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
    private fun openEditor() {
        waitText(original); rule.onNodeWithText(original).performClick(); waitText("编辑")
        rule.onNodeWithText("编辑").performClick(); waitText("编辑消息")
    }
    @Test fun retainLongDraftUntilHostTerminatesProcess() {
        guard(); check(!journalFile.exists())
        val j = JsonObject().apply {
            addProperty("run", run); addProperty("seedPid", Process.myPid())
            add("originalWorldDrafts", snapshot(worldPrefs)); add("originalCharacterDrafts", snapshot(characterPrefs)); add("originalChatDrafts", snapshot(chatPrefs))
            add("originalEditFiles", fileHashes()); add("originalBranchPrefs", branchPrefs())
        }; persist(j)
        runBlocking(Dispatchers.IO) { db.withTransaction {
            val sid = db.sessionDao().insert(SessionEntity(title = title, creationRequestId = run))
            j.addProperty("session", sid); persist(j)
            db.sessionWorldDao().upsert(SessionWorldEntity(sessionId = sid, narratorEnabled = false))
            val mid = db.messageDao().insert(MessageEntity(sessionId = sid, speakerType = "narrator", content = original))
            j.addProperty("message", mid); persist(j)
            j.add("originalMessage", com.google.gson.Gson().toJsonTree(db.messageDao().getById(mid)))
            persist(j)
        } }
        openStory(); openEditor()
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTextReplacement(edited)
        rule.runOnUiThread { rule.activity.currentFocus?.clearFocus() }; hideKeyboard()
        rule.onNodeWithContentDescription("关闭消息编辑").performClick(); waitText("保留草稿")
        screenshot("retain-long-edit-confirmation")
        rule.onNodeWithText("保留草稿").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("编辑消息").fetchSemanticsNodes().isEmpty() && rule.onAllNodesWithText("保留草稿").fetchSemanticsNodes().isEmpty() }
        waitText(original)
        rule.onNodeWithContentDescription("返回会话主页").performClick(); waitText("故事库")
        val draft = checkNotNull(runBlocking(Dispatchers.IO) { store.load(scope(j)) })
        check(draft.editedContent == edited && draft.originalBody == original)
        runBlocking(Dispatchers.IO) {
            check(db.sessionBranchDao().getBySession(j.get("session").asLong).isEmpty())
            check(db.messageDao().getMainBranchMessages(j.get("session").asLong).size == 1)
        }
        j.addProperty("revision", draft.revision); j.addProperty("phase", "ready-for-kill"); persist(j)
        output.resolve("ready.txt").writeText("pid=${Process.myPid()}\nrun=$run\ndurableEdit=true\n")
        SystemClock.sleep(55000); error("host did not terminate owned process")
    }
    @Test fun newProcessRestoresLongDraftSavesAndReturnsToMain() {
        guard(); val j = journal(); check(Process.myPid() != j.get("seedPid").asInt)
        val sid = j.get("session").asLong
        check(runBlocking(Dispatchers.IO) { store.load(scope(j)) }?.editedContent == edited)
        openStory(); openEditor()
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().assertTextContains(edited)
        screenshot("restored-long-edit")
        rule.onNodeWithText("创建编辑分支").assertIsEnabled().performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("编辑消息").fetchSemanticsNodes().isEmpty() }
        val branch = runBlocking(Dispatchers.IO) {
            val originalRow = com.google.gson.Gson().fromJson(j.get("originalMessage"), MessageEntity::class.java)
            check(db.messageDao().getById(j.get("message").asLong) == originalRow)
            val branch = db.sessionBranchDao().getBySession(sid).single()
            check(branch.branchId.startsWith("edit_") && branch.parentBranchId == "main" && branch.sourceMessageId == originalRow.id)
            val replacement = db.messageDao().getBranchMessages(sid, branch.branchId).single()
            check(replacement.content == edited && replacement.regeneratedFromMessageId == originalRow.id)
            branch
        }
        rule.waitUntil(10000) { runBlocking(Dispatchers.IO) { store.load(scope(j)) } == null }
        check(!store.fileFor(scope(j)).exists() && !File(store.fileFor(scope(j)).path + ".bak").exists())
        rule.waitUntil(10000) { rule.onAllNodesWithText(edited, substring = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("saved-long-edit-branch")
        rule.onNodeWithContentDescription("返回会话主页").performClick(); waitText("故事库")
        openStory()
        rule.waitUntil(10000) { rule.onAllNodesWithText(edited, substring = true).fetchSemanticsNodes().isNotEmpty() }
        check(runBlocking(Dispatchers.IO) { db.sessionBranchDao().getBySession(sid).size } == 1)
        rule.onNodeWithContentDescription("会话菜单").performClick(); waitText("故事线")
        rule.onNodeWithText("故事线").performClick(); waitText("选择故事线")
        rule.onNodeWithText("主线剧情").performClick(); waitText(original)
        screenshot("main-line-original-unchanged")
        check(runBlocking(Dispatchers.IO) { db.messageDao().getMainBranchMessages(sid).single().content } == original)
        j.addProperty("branch", branch.branchId); j.addProperty("phase", "verified"); persist(j)
        output.resolve("verified.txt").writeText("newPid=${Process.myPid()}\nlongDraftExact=true\noneEditBranch=true\nmainRowUnchanged=true\nreopenNoDuplicate=true\ndraftCleared=true\n")
    }
    @Test fun rollbackOnlyOwnedSessionAndDraft() {
        guard(); val j = journal(); rule.waitForIdle()
        val sid = j.get("session")?.asLong
        if (sid != null) {
            runBlocking(Dispatchers.IO) {
                val row = db.sessionDao().getById(sid); check(row == null || row.title == title && row.creationRequestId == run)
                db.messageDao().getMainBranchMessages(sid).forEach { check(it.content == original) }
                db.sessionBranchDao().getBySession(sid).forEach { branch ->
                    check(branch.parentBranchId == "main" && branch.sourceMessageId == j.get("message").asLong)
                    db.messageDao().getBranchMessages(sid, branch.branchId).forEach { check(it.content == edited) }
                }
                db.sessionDao().delete(sid)
                prefs.clearLastChatBranch(sid)
                if (j.has("message")) store.load(scope(j))?.let { check(it.editedContent == edited); check(store.clear(scope(j), it.revision)) }
            }
            chatPrefs.all.keys.filter { it == "session_$sid" || it.startsWith("chapter_input_v1_${sid}_") || it == "reply_recovery_v1_session_$sid" }.forEach { check(chatPrefs.edit().remove(it).commit()) }
            check(runBlocking(Dispatchers.IO) { db.sessionDao().getById(sid) } == null)
        }
        assertUnchanged(worldPrefs, j.getAsJsonObject("originalWorldDrafts")); assertUnchanged(characterPrefs, j.getAsJsonObject("originalCharacterDrafts")); assertUnchanged(chatPrefs, j.getAsJsonObject("originalChatDrafts"))
        check(fileHashes() == j.getAsJsonObject("originalEditFiles")); check(branchPrefs() == j.getAsJsonObject("originalBranchPrefs"))
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("ownedSessionRemoved=true\notherDraftsFilesBranchesExact=true\n")
    }
}

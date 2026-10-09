package com.mojing.app.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.CharacterEditDraftStore
import com.mojing.app.data.StoryOpeningInputDraftStore
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import com.mojing.app.domain.encyclopedia.CharacterEncyclopediaSync
import com.mojing.app.ui.chat.RetainedChatSessions
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Durable unsaved character editor recovery followed by real UI save and start-chat. */
class CharacterDraftProcessAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("characterRun").orEmpty().also { UUID.fromString(it) }
    private val title get() = "守灯${run.take(8)}"
    private val marker get() = "CHARACTER_DRAFT_$run"
    private val persona get() = "$marker\n" + "守灯者记住远行者的约定，习惯以克制的语气诉说潮汐。\n".repeat(32) + "PERSONA_END_${run.take(8)}"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val store get() = CharacterEditDraftStore(context)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val journalFile get() = File(context.filesDir, "character-process-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "character-process-$run").apply { mkdirs() }
    private fun guard() {
        check(args.getString("characterCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "character-process-$run.pending")
        FileOutputStream(pending).use { it.write(j.toString().toByteArray()); it.fd.sync() }
        check(pending.renameTo(journalFile))
    }
    private fun journal() = JsonParser.parseString(journalFile.readText()).asJsonObject.also { check(it.get("run").asString == run) }
    private fun snapshot(p: android.content.SharedPreferences) = JsonObject().apply {
        p.all.forEach { (key, value) -> check(value is String); addProperty(key, value) }
    }
    private fun assertUnchanged(p: android.content.SharedPreferences, original: JsonObject) {
        check(p.all.keys == original.keySet()) { "unrelated draft key set changed" }
        original.entrySet().forEach { (key, value) -> check(p.getString(key, null) == value.asString) { "unrelated draft changed" } }
    }
    private fun waitForText(text: String) {
        rule.waitUntil(10000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun openEditor() {
        rule.waitForIdle()
        rule.waitUntil(10000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasText("创作") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        waitForText("角色"); rule.onNodeWithText("角色").performClick()
        rule.waitUntil(10000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(title)
        rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0) }
        rule.waitUntil(10000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().size >= 2 && rule.onAllNodesWithText(title).onLast().isDisplayed() }
        rule.onAllNodesWithText(title).onLast().performClick()
        waitForText("角色详情")
        rule.onNodeWithContentDescription("编辑角色").performClick()
        waitForText("编辑角色")
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
    @Test fun durableUnsavedPersonaUntilHostTerminatesProcess() {
        guard(); check(!journalFile.exists())
        val j = JsonObject().apply {
            addProperty("run", run); addProperty("seedPid", Process.myPid()); addProperty("phase", "snapshot")
            add("originalCharacterDrafts", snapshot(characterPrefs)); add("originalChatDrafts", snapshot(chatPrefs))
        }
        persist(j)
        val cid = runBlocking(Dispatchers.IO) { db.withTransaction {
            val wid = db.encyclopediaDao().upsert(EncyclopediaEntity(name = "灯塔${run.take(8)}", description = marker, worldPrompt = marker))
            val id = SaveCharacterBindingUseCase(db)(CharacterEntity(name = title, personaPrompt = marker, boundEncyclopediaId = wid))
            check(!characterPrefs.contains("character_$id"))
            j.addProperty("world", wid); j.addProperty("character", id); j.addProperty("phase", "fixture-created"); persist(j)
            id
        } }
        openEditor()
        rule.onNodeWithText("全屏编辑").performScrollTo().performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("返回编辑表单").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTextReplacement(persona)
        rule.onNodeWithText("完成").performClick()
        waitForText("编辑角色")
        rule.waitUntil(10000) { runBlocking(Dispatchers.IO) { store.load(cid)?.personaPrompt == persona } }
        check(runBlocking(Dispatchers.IO) { db.characterDao().getById(cid)?.personaPrompt } == marker)
        screenshot("unsaved-persona-form")
        j.addProperty("phase", "ready-for-kill"); persist(j)
        output.resolve("ready.txt").writeText("pid=${Process.myPid()}\nrun=$run\ndurablePersona=true\n")
        SystemClock.sleep(55000)
        error("host did not terminate the owned process")
    }
    @Test fun newProcessRestoresSavesAndStartsChat() {
        guard(); val j = journal(); check(j.get("phase").asString == "ready-for-kill")
        check(Process.myPid() != j.get("seedPid").asInt)
        val cid = j.get("character").asLong; val wid = j.get("world").asLong
        check(runBlocking(Dispatchers.IO) { store.load(cid)?.personaPrompt } == persona)
        openEditor(); waitForText("发现未保存的角色草稿")
        screenshot("recover-unsaved-persona")
        rule.onNodeWithText("恢复草稿").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("恢复草稿").fetchSemanticsNodes().isEmpty() }
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).filter(hasText(persona)).onFirst().assertTextContains(persona)
        // Real return dialog saves through the production owner and returns to the same detail page.
        rule.onNodeWithContentDescription("返回").performClick()
        waitForText("保存角色修改？")
        screenshot("save-and-leave-confirmation")
        rule.onNodeWithText("保存并离开").performClick()
        waitForText("角色详情")
        check(runBlocking(Dispatchers.IO) { db.characterDao().getById(cid)?.let { it.personaPrompt == persona && it.boundEncyclopediaId == wid } } == true)
        runBlocking(Dispatchers.IO) {
            val mirrorIds = CharacterEncyclopediaSync.findCharacterMirrorIds(db.encyclopediaEntryDao(), wid, cid)
            check(mirrorIds.size == 1)
            check(db.encyclopediaEntryDao().getById(mirrorIds.single())?.content == persona)
        }
        check(runBlocking(Dispatchers.IO) { store.load(cid) } == null)
        rule.onNodeWithText(persona).assertExists()
        rule.onNodeWithText("所属世界 · 灯塔${run.take(8)}").assertExists()
        screenshot("saved-character-detail")
        rule.onNodeWithText("开始对话").performScrollTo().performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }
        val sessions = runBlocking(Dispatchers.IO) { db.sessionDao().getRecentForCharacter(cid) }
        check(sessions.size == 1)
        val sid = sessions.single().id
        check(sessions.single().title == "$title · 新故事")
        check(runBlocking(Dispatchers.IO) { db.participantDao().getBySession(sid).map { it.characterId } } == listOf(cid))
        check(runBlocking(Dispatchers.IO) { db.sessionWorldDao().getBySession(sid)?.encyclopediaId } == wid)
        check(runBlocking(Dispatchers.IO) { db.messageDao().getNextStoryContextBatch(sid, "main", 0, 10).isEmpty() })
        j.addProperty("session", sid); j.addProperty("phase", "verified"); persist(j)
        screenshot("started-character-chat")
        output.resolve("verified.txt").writeText("newPid=${Process.myPid()}\nexactPersonaRecovered=true\nsaveAndLeaveRealUi=true\nsameCharacterAndWorld=true\nexactSingleMirror=true\ndraftCleared=true\nstartedOneChat=true\nnoMessages=true\n")
    }
    @Test fun rollbackOnlyOwnedCharacterWorldAndSessions() {
        guard(); val j = journal(); rule.waitForIdle()
        val cid = j.get("character")?.asLong; val wid = j.get("world")?.asLong
        val sessions = if (cid == null) emptyList() else runBlocking(Dispatchers.IO) { db.sessionDao().getRecentForCharacter(cid) }
        runBlocking(Dispatchers.IO) { db.withTransaction {
            if (wid != null) check(db.encyclopediaDao().getById(wid)?.description.let { it == null || it == marker })
            if (cid != null) {
                val character = db.characterDao().getById(cid)
                check(character == null || (character.name == title && character.boundEncyclopediaId == wid && character.personaPrompt in listOf(marker, persona)))
                sessions.forEach { session ->
                    check(session.title == "$title · 新故事")
                    check(db.participantDao().getBySession(session.id).map { it.characterId } == listOf(cid))
                    check(db.sessionWorldDao().getBySession(session.id)?.encyclopediaId == wid)
                    db.sessionDao().delete(session.id)
                }
                db.characterDao().delete(cid)
            }
            if (wid != null) db.encyclopediaDao().delete(wid)
        } }
        if (cid != null) check(characterPrefs.edit().remove("character_$cid").commit())
        sessions.forEach { check(chatPrefs.edit().remove("session_${it.id}").commit()) }
        assertUnchanged(characterPrefs, j.getAsJsonObject("originalCharacterDrafts")); assertUnchanged(chatPrefs, j.getAsJsonObject("originalChatDrafts"))
        if (cid != null) check(runBlocking(Dispatchers.IO) { db.characterDao().getById(cid) } == null)
        if (wid != null) check(runBlocking(Dispatchers.IO) { db.encyclopediaDao().getById(wid) } == null)
        if (wid != null) check(runBlocking(Dispatchers.IO) { db.encyclopediaEntryDao().countEntries(wid) } == 0)
        sessions.forEach { check(runBlocking(Dispatchers.IO) { db.sessionDao().getById(it.id) } == null) }
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("ownedFixtureRemoved=true\notherCharacterAndChatDraftsExact=true\nprivateJournalRetained=true\n")
    }
}

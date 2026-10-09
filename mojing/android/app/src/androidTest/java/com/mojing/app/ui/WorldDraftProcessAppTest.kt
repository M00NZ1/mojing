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
import com.mojing.app.data.WorldEditDraft
import com.mojing.app.data.WorldEditDraftStore
import com.mojing.app.data.StoryOpeningInputDraftStore
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
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

/** Five-field world draft, real process recovery, real UI save and new story snapshot. */
class WorldDraftProcessAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("worldRun").orEmpty().also { UUID.fromString(it) }
    private val title get() = "潮界${run.take(8)}"
    private val characterTitle get() = "渡潮${run.take(8)}"
    private val marker get() = "WORLD_DRAFT_$run"
    private val original get() = WorldEditDraft(title, marker, "$marker 原背景", "原玩法", "$marker 原规则")
    private val changed get() = WorldEditDraft("$title · 新", "$marker 新简介", "$marker\n" + "潮界由航路和灯塔相连，角色只知道亲眼所见的约定。\n".repeat(32) + "WORLD_END_${run.take(8)}", "新玩法", "$marker 新规则：不得凭空获得秘密")
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val store get() = WorldEditDraftStore(context)
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val journalFile get() = File(context.filesDir, "world-process-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "world-process-$run").apply { mkdirs() }
    private fun guard() {
        check(args.getString("worldCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "world-process-$run.pending")
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
    private fun openSettings() {
        rule.waitForIdle()
        rule.waitUntil(10000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasText("创作") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        waitText("世界"); rule.onNodeWithText("世界").performClick()
        rule.waitUntil(10000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(title)
        hideKeyboard(); waitText(title)
        rule.onAllNodesWithContentDescription("更多操作").onFirst().performClick()
        rule.onNodeWithText("世界设置").performClick(); waitText("世界名称")
    }
    private fun replace(old: String, value: String) {
        rule.onNode(hasSetTextAction() and hasText(old), useUnmergedTree = true).performScrollTo().performTextReplacement(value)
    }
    private fun saved(w: EncyclopediaEntity) = WorldEditDraft(w.name, w.description, w.worldPrompt, w.gameplayMode, w.antiCheatPrompt)
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
    @Test fun durableFiveFieldWorldDraftUntilHostTerminatesProcess() {
        guard(); check(!journalFile.exists())
        val j = JsonObject().apply {
            addProperty("run", run); addProperty("seedPid", Process.myPid()); addProperty("phase", "snapshot")
            add("originalWorldDrafts", snapshot(worldPrefs)); add("originalCharacterDrafts", snapshot(characterPrefs)); add("originalChatDrafts", snapshot(chatPrefs))
        }
        persist(j)
        val wid = runBlocking(Dispatchers.IO) { db.withTransaction {
            val id = db.encyclopediaDao().upsert(EncyclopediaEntity(name = original.name, description = original.description, worldPrompt = original.prompt, gameplayMode = original.gameplay, antiCheatPrompt = original.rules))
            j.addProperty("world", id); persist(j)
            val cid = SaveCharacterBindingUseCase(db)(CharacterEntity(name = characterTitle, personaPrompt = marker, boundEncyclopediaId = id))
            j.addProperty("character", cid); j.addProperty("phase", "fixture-created"); persist(j)
            check(store.load(id) == null); id
        } }
        openSettings()
        replace(original.name, changed.name); replace(original.description, changed.description); replace(original.gameplay, changed.gameplay)
        replace(original.prompt, changed.prompt); replace(original.rules, changed.rules)
        hideKeyboard()
        rule.onNodeWithContentDescription("返回").performClick(); waitText("离开世界编辑？")
        screenshot("retain-world-draft-confirmation")
        rule.onNodeWithText("保留草稿并离开").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("世界设置").fetchSemanticsNodes().isEmpty() }
        check(runBlocking(Dispatchers.IO) { store.load(wid) } == changed)
        check(runBlocking(Dispatchers.IO) { saved(checkNotNull(db.encyclopediaDao().getById(wid))) } == original)
        j.addProperty("phase", "ready-for-kill"); persist(j)
        output.resolve("ready.txt").writeText("pid=${Process.myPid()}\nrun=$run\ndurableWorld=true\n")
        SystemClock.sleep(55000)
        error("host did not terminate the owned process")
    }
    @Test fun newProcessRestoresFiveFieldsSavesAndStartsCurrentWorld() {
        guard(); val j = journal(); check(j.get("phase").asString == "ready-for-kill")
        check(Process.myPid() != j.get("seedPid").asInt)
        val wid = j.get("world").asLong; val cid = j.get("character").asLong
        check(runBlocking(Dispatchers.IO) { store.load(wid) } == changed)
        openSettings(); waitText("发现未保存的世界草稿")
        screenshot("recover-five-field-world")
        rule.onNodeWithText("恢复草稿").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("恢复草稿").fetchSemanticsNodes().isEmpty() }
        for (value in listOf(changed.name, changed.description, changed.gameplay, changed.prompt, changed.rules)) {
            rule.onNode(hasSetTextAction() and hasText(value), useUnmergedTree = true).assertTextContains(value)
        }
        rule.onNodeWithText("保存世界设置").performClick(); waitText("已保存")
        check(runBlocking(Dispatchers.IO) { saved(checkNotNull(db.encyclopediaDao().getById(wid))) } == changed)
        check(runBlocking(Dispatchers.IO) { store.load(wid) } == null)
        screenshot("saved-world-settings")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithContentDescription("返回创作中心").performClick(); waitText("角色")
        rule.onNodeWithText("角色").performClick()
        rule.waitUntil(10000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(characterTitle)
        hideKeyboard()
        rule.waitUntil(10000) { rule.onAllNodesWithText(characterTitle).fetchSemanticsNodes().size >= 2 && rule.onAllNodesWithText(characterTitle).onLast().isDisplayed() }
        rule.onAllNodesWithText(characterTitle).onLast().performClick(); waitText("角色详情")
        rule.onNodeWithText("所属世界 · ${changed.name}").assertExists()
        screenshot("character-with-restored-world")
        rule.onNodeWithText("开始对话").performScrollTo().performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }
        runBlocking(Dispatchers.IO) {
            val sessions = db.sessionDao().getRecentForCharacter(cid); check(sessions.size == 1)
            val sid = sessions.single().id
            check(sessions.single().title == "$characterTitle · 新故事")
            check(db.participantDao().getBySession(sid).map { it.characterId } == listOf(cid))
            val world = checkNotNull(db.sessionWorldDao().getBySession(sid))
            check(world.encyclopediaId == wid && world.worldPrompt == changed.prompt && world.gameplayMode == changed.gameplay)
            check(world.antiCheatEnabled == rule.activity.secureStorage.defaultAntiCheatEnabled)
            check(world.antiCheatPrompt == if (world.antiCheatEnabled) changed.rules else "")
            check(db.messageDao().getNextStoryContextBatch(sid, "main", 0, 10).isEmpty())
            j.addProperty("session", sid); persist(j)
        }
        screenshot("started-restored-world-chat")
        j.addProperty("phase", "verified"); persist(j)
        output.resolve("verified.txt").writeText("newPid=${Process.myPid()}\nfiveFieldsExact=true\nrealUiSave=true\ndraftCleared=true\nnewStoryCurrentWorldExact=true\nnoMessages=true\n")
    }
    @Test fun rollbackOnlyOwnedWorldCharacterAndSessions() {
        guard(); val j = journal(); rule.waitForIdle()
        val wid = j.get("world")?.asLong; val cid = j.get("character")?.asLong
        val sessions = if (cid == null) emptyList() else runBlocking(Dispatchers.IO) { db.sessionDao().getRecentForCharacter(cid) }
        runBlocking(Dispatchers.IO) { db.withTransaction {
            if (wid != null) {
                val world = db.encyclopediaDao().getById(wid)
                check(world == null || saved(world) in listOf(original, changed))
            }
            if (cid != null) {
                val character = db.characterDao().getById(cid)
                check(character == null || (character.name == characterTitle && character.boundEncyclopediaId == wid && character.personaPrompt == marker))
                sessions.forEach { session ->
                    check(session.title == "$characterTitle · 新故事")
                    check(db.participantDao().getBySession(session.id).map { it.characterId } == listOf(cid))
                    check(db.sessionWorldDao().getBySession(session.id)?.encyclopediaId == wid)
                    db.sessionDao().delete(session.id)
                }
                db.characterDao().delete(cid)
            }
            if (wid != null) db.encyclopediaDao().delete(wid)
        } }
        if (wid != null) runBlocking(Dispatchers.IO) { store.clear(wid) }
        if (cid != null) check(characterPrefs.edit().remove("character_$cid").commit())
        sessions.forEach { check(chatPrefs.edit().remove("session_${it.id}").commit()) }
        assertUnchanged(worldPrefs, j.getAsJsonObject("originalWorldDrafts")); assertUnchanged(characterPrefs, j.getAsJsonObject("originalCharacterDrafts")); assertUnchanged(chatPrefs, j.getAsJsonObject("originalChatDrafts"))
        runBlocking(Dispatchers.IO) {
            if (wid != null) { check(db.encyclopediaDao().getById(wid) == null); check(db.encyclopediaEntryDao().countEntries(wid) == 0); check(store.load(wid) == null) }
            if (cid != null) check(db.characterDao().getById(cid) == null)
            sessions.forEach { check(db.sessionDao().getById(it.id) == null) }
        }
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("ownedFixturesRemoved=true\notherWorldCharacterChatDraftsExact=true\nprivateJournalRetained=true\n")
    }
}

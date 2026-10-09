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
import com.mojing.app.data.SessionSetupDraftStore
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

/** Retained session setup, foreground process recovery and one creation request. */
class SessionSetupProcessAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("setupRun").orEmpty().also { UUID.fromString(it) }
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
    private val setupStore get() = SessionSetupDraftStore(context)
    private val setupPrefs get() = context.getSharedPreferences("session_setup_draft_v1", 0)
    private val journalFile get() = File(context.filesDir, "setup-process-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "setup-process-$run").apply { mkdirs() }
    private fun guard() {
        check(args.getString("setupCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "setup-process-$run.pending")
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
    private val sessionTitle get() = "续航${run.take(8)}"
    private fun storyLibrary() {
        rule.waitUntil(10000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasText("对话") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        waitText("故事库")
    }
    @Test fun suspendSetupUntilHostTerminatesProcess() {
        try { seedSetup() } catch (failure: Throwable) {
            output.resolve("failure.txt").writeText(failure.stackTraceToString()); throw failure
        }
    }
    private fun seedSetup() {
        guard(); check(!journalFile.exists()); check(runBlocking(Dispatchers.IO) { setupStore.load() } == null)
        val j = JsonObject().apply {
            addProperty("run", run); addProperty("seedPid", Process.myPid()); addProperty("phase", "snapshot")
            add("originalSetupDrafts", snapshot(setupPrefs)); add("originalWorldDrafts", snapshot(worldPrefs)); add("originalCharacterDrafts", snapshot(characterPrefs)); add("originalChatDrafts", snapshot(chatPrefs))
        }
        persist(j)
        runBlocking(Dispatchers.IO) { db.withTransaction {
            val wid = db.encyclopediaDao().upsert(EncyclopediaEntity(name = original.name, description = original.description, worldPrompt = original.prompt, gameplayMode = original.gameplay, antiCheatPrompt = original.rules))
            j.addProperty("world", wid); persist(j)
        } }
        storyLibrary(); rule.onNodeWithText("新建对话").performClick(); waitText("选择世界（可选）")
        rule.onNodeWithText("选择世界（可选）").performClick(); rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("搜索世界").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("搜索世界").performClick()
        rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("model-picker-search")), useUnmergedTree = true).onFirst().performTextInput(title)
        rule.waitUntil(10000) { rule.onAllNodesWithText(title).filter(hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(title).filter(hasClickAction() and !hasSetTextAction()).onFirst().performScrollTo().performClick()
        waitText("去创建角色")
        rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasAnyChild(hasText("标题"))), useUnmergedTree = true).onFirst().performTextReplacement(sessionTitle)
        hideKeyboard()
        rule.onNodeWithText("对话设置").performScrollTo().performClick()
        for (label in listOf("启用旁白", "生成剧情选项", "保持世界规则")) {
            rule.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
        }
        screenshot("configured-opening")
        rule.onNodeWithText("去创建角色").performScrollTo().performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("新建对话").fetchSemanticsNodes().isEmpty() && runBlocking(Dispatchers.IO) { setupStore.load() } != null }
        waitText("角色")
        // Production binding owner seeds the one newly compatible character; this is not touch creation evidence.
        runBlocking(Dispatchers.IO) { db.withTransaction {
            val cid = SaveCharacterBindingUseCase(db)(CharacterEntity(name = characterTitle, personaPrompt = marker, boundEncyclopediaId = j.get("world").asLong))
            j.addProperty("character", cid); persist(j)
        } }
        val draft = checkNotNull(runBlocking(Dispatchers.IO) { setupStore.load() })
        check(draft.title == sessionTitle && draft.encyclopediaId == j.get("world").asLong && draft.characterIds.isEmpty() && draft.selectNewCharacter)
        j.addProperty("requestId", draft.requestId)
        j.addProperty("narrator", draft.narratorEnabled); j.addProperty("choices", draft.choiceEnabled); j.addProperty("rules", draft.antiCheatEnabled)
        if (args.getString("setupCommitted") == "true") {
            // Inject the narrow transaction-committed / draft-not-yet-cleared window through the original transaction.
            runBlocking(Dispatchers.IO) { db.withTransaction {
                val sid = com.mojing.app.domain.usecase.SessionCreationTransaction(db)(
                    com.mojing.app.data.local.entity.SessionEntity(title = sessionTitle, creationRequestId = draft.requestId),
                    com.mojing.app.data.local.entity.SessionWorldEntity(sessionId = 0, encyclopediaId = j.get("world").asLong, worldPrompt = original.prompt, gameplayMode = original.gameplay,
                        narratorEnabled = draft.narratorEnabled, choiceGenerationEnabled = draft.choiceEnabled, antiCheatEnabled = draft.antiCheatEnabled),
                    listOf(com.mojing.app.data.local.entity.SessionParticipantEntity(sessionId = 0, characterId = j.get("character").asLong)),
                )
                j.addProperty("session", sid); persist(j)
            } }
        }
        j.addProperty("phase", "ready-for-kill"); persist(j)
        output.resolve("ready.txt").writeText("pid=${Process.myPid()}\nrun=$run\nsuspendedSetup=true\n")
        SystemClock.sleep(55000); error("host did not terminate owned process")
    }
    @Test fun newProcessContinuesSameSetupAndCreatesOnce() {
        guard(); val j = journal(); check(Process.myPid() != j.get("seedPid").asInt)
        storyLibrary(); screenshot("library-after-process")
        waitText("新对话设定已保留")
        rule.onNodeWithText("继续设定").performClick(); waitText("选择世界（可选）")
        rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasAnyChild(hasText("标题"))), useUnmergedTree = true).onFirst().assertTextContains(sessionTitle)
        rule.onNodeWithText(title).assertExists(); waitText("1 人参与")
        screenshot("restored-opening")
        val create = if (rule.onAllNodesWithText("创建并开始").fetchSemanticsNodes().isNotEmpty()) "创建并开始" else "开始对话"
        rule.onNodeWithText(create).performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }
        runBlocking(Dispatchers.IO) {
            val rows = db.sessionDao().getRecentForCharacter(j.get("character").asLong); check(rows.size == 1)
            val row = rows.single(); check(row.title == sessionTitle && row.creationRequestId == j.get("requestId").asString)
            check(db.sessionDao().getByCreationRequestId(row.creationRequestId!!)!!.id == row.id)
            val world = checkNotNull(db.sessionWorldDao().getBySession(row.id))
            check(world.encyclopediaId == j.get("world").asLong && world.worldPrompt == original.prompt && world.gameplayMode == original.gameplay)
            check(world.narratorEnabled == j.get("narrator").asBoolean && world.choiceGenerationEnabled == j.get("choices").asBoolean && world.antiCheatEnabled == j.get("rules").asBoolean)
            check(world.antiCheatPrompt == if (world.antiCheatEnabled) original.rules else "")
            check(db.participantDao().getBySession(row.id).map { it.characterId } == listOf(j.get("character").asLong))
            check(db.messageDao().getNextStoryContextBatch(row.id, "main", 0, 10).isEmpty())
            j.addProperty("session", row.id); persist(j)
        }
        rule.waitUntil(10000) { runBlocking(Dispatchers.IO) { setupStore.load() } == null }
        screenshot("continued-opening-chat")
        j.addProperty("phase", "verified"); persist(j)
        output.resolve("verified.txt").writeText("newPid=${Process.myPid()}\ntitleWorldCharacterExact=true\ncreationRequestPresent=true\nnoMessages=true\n")
    }
    @Test fun newProcessOpensAlreadyCommittedSetupOnce() {
        guard(); val j = journal(); check(Process.myPid() != j.get("seedPid").asInt)
        val sid = j.get("session").asLong
        storyLibrary(); waitText("打开对话"); screenshot("committed-setup-recovery")
        rule.onNodeWithText("打开对话").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10000) { runBlocking(Dispatchers.IO) { setupStore.load() } == null }
        runBlocking(Dispatchers.IO) {
            val sessions = db.sessionDao().getRecentForCharacter(j.get("character").asLong)
            check(sessions.size == 1 && sessions.single().id == sid)
            check(db.sessionDao().getByCreationRequestId(j.get("requestId").asString)?.id == sid)
            check(db.messageDao().getNextStoryContextBatch(sid, "main", 0, 10).isEmpty())
        }
        screenshot("opened-original-committed-chat")
        j.addProperty("phase", "verified"); persist(j)
        output.resolve("verified.txt").writeText("newPid=${Process.myPid()}\nopenedSameSession=true\nnoDuplicateCreation=true\ndraftCleared=true\n")
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
                    check(session.title == sessionTitle)
                    check(db.participantDao().getBySession(session.id).map { it.characterId } == listOf(cid))
                    check(db.sessionWorldDao().getBySession(session.id)?.encyclopediaId == wid)
                    db.sessionDao().delete(session.id)
                }
                db.characterDao().delete(cid)
            }
            if (wid != null) db.encyclopediaDao().delete(wid)
        } }
        if (j.has("requestId")) runBlocking(Dispatchers.IO) { setupStore.clear(j.get("requestId").asString) }
        // If navigation failed after saving, verify this run's title and world before clearing its checkpoint.
        val pending = runBlocking(Dispatchers.IO) { setupStore.load() }
        if (pending != null && pending.title == sessionTitle && pending.encyclopediaId == wid) runBlocking(Dispatchers.IO) { setupStore.clear(pending.requestId) }
        assertUnchanged(setupPrefs, j.getAsJsonObject("originalSetupDrafts"))
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

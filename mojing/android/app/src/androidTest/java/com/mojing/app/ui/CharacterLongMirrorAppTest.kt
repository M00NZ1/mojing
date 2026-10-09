package com.mojing.app.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.StoryOpeningInputDraftStore
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.domain.encyclopedia.CharacterEncyclopediaSync
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import com.mojing.app.ui.chat.RetainedChatSessions
import com.mojing.app.data.prefs.UiPreferencesRepository
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

/** Real App flow for preserving a character persona when its legacy mirror is exactly 8000 chars. */
class CharacterLongMirrorAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("bindingRun").orEmpty().also { UUID.fromString(it) }
    private val worldTitle get() = "长人设百科${run.take(8)}"
    private val characterName get() = "镜像角色${run.take(8)}"
    private val marker get() = "LONG_MIRROR_$run"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val entryPrefs get() = context.getSharedPreferences("entry_edit_drafts_v1", 0)
    private val journalFile get() = File(context.filesDir, "character-long-mirror-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "character-long-mirror-$run").apply { mkdirs() }

    private val fullPersona: String get() = buildString {
        while (length < 16_000) append("长人设段落：$marker；角色保留全部历史、边界和行为规则。\n")
        setLength(16_000)
    }
    private val oldMirror get() = fullPersona.take(8_000)
    private val tailMarker get() = fullPersona.takeLast(96)
    private val initialSummary get() = fullPersona.lineSequence().map { it.trim() }.first { it.isNotBlank() }.take(400)

    private fun guard() {
        check(args.getString("bindingCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "character-long-mirror-$run.pending")
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
    private fun branchSnapshot() = runBlocking(Dispatchers.IO) { JsonObject().apply {
        UiPreferencesRepository(context).lastChatBranches.first().forEach { (id, branch) -> addProperty(id.toString(), branch) }
    } }
    private fun otherCharactersDigest(excluded: Long? = null): String = runBlocking(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        var cursor: CharacterEntity? = null
        do {
            val page = db.characterDao().getExportPage(cursor?.let { if (it.pinnedAt > 0) 0 else 1 }, cursor?.pinnedAt,
                cursor?.favorite, cursor?.createdAt, cursor?.id, 128)
            page.filter { it.id != excluded }.forEach { digest.update(Gson().toJson(it).toByteArray()); digest.update(0.toByte()) }
            cursor = page.lastOrNull()
        } while (page.size == 128)
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun row(j: JsonObject) = runBlocking(Dispatchers.IO) { checkNotNull(db.encyclopediaEntryDao().getById(j.get("entry").asLong)) }
    private fun character(j: JsonObject) = runBlocking(Dispatchers.IO) { checkNotNull(db.characterDao().getById(j.get("character").asLong)) }
    private fun versions(j: JsonObject) = runBlocking(Dispatchers.IO) { db.entryVersionDao().getByEntry(j.get("entry").asLong) }
    private fun waitText(text: String) { rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun hideKeyboard() = rule.runOnUiThread {
        rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0)
    }
    private fun screenshot(name: String) {
        rule.waitForIdle(); inst.waitForIdleSync()
        repeat(2) {
            val committed = CountDownLatch(1)
            rule.runOnUiThread { rule.activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }; rule.activity.window.decorView.invalidate() }
            check(committed.await(3, TimeUnit.SECONDS))
        }
        inst.uiAutomation.waitForIdle(300, 5_000)
        val bitmap = checkNotNull(inst.uiAutomation.takeScreenshot())
        try { FileOutputStream(output.resolve("$name.png")).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun field(value: String) = rule.onNode(hasSetTextAction() and hasText(value), useUnmergedTree = true)
    private fun replace(old: String, next: String) { field(old).performScrollTo().performTextReplacement(next); hideKeyboard(); rule.waitForIdle() }
    private fun openEntry(initialTitle: String, initialSummary: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasText("创作") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        waitText("世界"); rule.onNodeWithText("世界").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(worldTitle); hideKeyboard()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(worldTitle).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(worldTitle).filter(!hasSetTextAction()).onLast().performClick(); waitText(initialTitle); rule.onNodeWithText(initialTitle).performScrollTo().performClick(); waitText("编辑条目")
        field(initialTitle).assertExists(); field(initialSummary).assertExists()
    }
    private fun leaveEntry() { inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); rule.waitForIdle(); waitText("关系图"); inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); rule.waitForIdle(); waitText("搜索世界名称"); inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); rule.waitForIdle(); waitText("世界") }
    private fun openCharacter(name: String = characterName) {
        rule.onNodeWithText("角色").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(name); hideKeyboard()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(name).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(name).filter(!hasSetTextAction()).onLast().performClick(); waitText("角色详情")
        rule.onNode(hasText(tailMarker, substring = true), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("所属世界 · $worldTitle").assertExists()
    }
    private fun assertDrafts(j: JsonObject) {
        assertUnchanged(worldPrefs, j.getAsJsonObject("worldDrafts")); assertUnchanged(characterPrefs, j.getAsJsonObject("characterDrafts"))
        assertUnchanged(chatPrefs, j.getAsJsonObject("chatDrafts")); assertUnchanged(entryPrefs, j.getAsJsonObject("entryDrafts"))
        check(branchSnapshot() == j.getAsJsonObject("branches"))
    }

    @Test fun old8000MirrorEditMetadataPreservesFullCharacterAndStartsChat() {
        guard(); check(!journalFile.exists())
        try {
            val j = JsonObject().apply {
                addProperty("run", run); addProperty("otherDigest", otherCharactersDigest()); add("branches", branchSnapshot())
                add("worldDrafts", snapshot(worldPrefs)); add("characterDrafts", snapshot(characterPrefs)); add("chatDrafts", snapshot(chatPrefs)); add("entryDrafts", snapshot(entryPrefs))
            }; persist(j)
            runBlocking(Dispatchers.IO) { db.withTransaction {
                val wid = db.encyclopediaDao().upsert(EncyclopediaEntity(name = worldTitle, description = marker, worldPrompt = "$marker 世界", gameplayMode = "自由剧情", antiCheatPrompt = "$marker 规则"))
                j.addProperty("world", wid); persist(j)
                val cid = SaveCharacterBindingUseCase(db)(CharacterEntity(name = characterName, personaPrompt = fullPersona, boundEncyclopediaId = wid, temperature = 0.72f, maxTokens = 1700, favorite = true))
                j.addProperty("character", cid); persist(j)
                val eid = CharacterEncyclopediaSync.findCharacterMirrorIds(db.encyclopediaEntryDao(), wid, cid).single()
                val mirror = checkNotNull(db.encyclopediaEntryDao().getById(eid)); check(mirror.content == fullPersona)
                db.encyclopediaEntryDao().upsert(mirror.copy(content = oldMirror))
                j.addProperty("entry", eid); j.addProperty("initialTitle", mirror.title); j.addProperty("initialSummary", mirror.summary); persist(j)
                check(checkNotNull(db.characterDao().getById(cid)).personaPrompt == fullPersona)
                check(checkNotNull(db.encyclopediaEntryDao().getById(eid)).content.length == 8_000)
            } }
            val initialCharacter = character(j)
            openEntry(j.get("initialTitle").asString, j.get("initialSummary").asString); screenshot("legacy-8000-mirror-before-save")
            replace(j.get("initialTitle").asString, "百科改名${run.take(8)}"); replace(j.get("initialSummary").asString, "摘要改动_$run")
            rule.onAllNodesWithText("保存修改").onFirst().assertIsEnabled().performClick()
            rule.waitUntil(10_000) { row(j).title == "百科改名${run.take(8)}" && row(j).summary == "摘要改动_$run" && versions(j).size == 1 && rule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
            val saved = row(j); val savedCharacter = character(j); val old = versions(j).single()
            check(saved.content == fullPersona && saved.content.length > 8_000)
            check(savedCharacter.personaPrompt == fullPersona && savedCharacter.name == saved.title)
            check(initialCharacter.copy(name = savedCharacter.name, personaPrompt = savedCharacter.personaPrompt, updatedAt = savedCharacter.updatedAt) == savedCharacter)
            check(old.content == oldMirror && old.content.length == 8_000)
            check(runBlocking(Dispatchers.IO) { CharacterEncyclopediaSync.findCharacterMirrorIds(db.encyclopediaEntryDao(), j.get("world").asLong, j.get("character").asLong) } == listOf(j.get("entry").asLong))
            screenshot("metadata-save-keeps-full-mirror")
            leaveEntry(); openCharacter(saved.title); rule.onNodeWithText(fullPersona).assertExists(); screenshot("character-detail-full-persona")
            rule.onNodeWithText("开始对话").performScrollTo().performClick(); rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }
            runBlocking(Dispatchers.IO) { db.sessionDao().getRecentForCharacter(j.get("character").asLong).single().also { session ->
                j.addProperty("session", session.id); persist(j); check(db.participantDao().getBySession(session.id).map { it.characterId } == listOf(j.get("character").asLong)); check(db.messageDao().messageCount(session.id) == 0); check(db.sessionWorldDao().getBySession(session.id)?.encyclopediaId == j.get("world").asLong)
            } }
            screenshot("quick-start-same-character-zero-messages")
            rule.onNodeWithContentDescription("返回会话主页").performClick(); waitText("故事库"); openEntry("百科改名${run.take(8)}", "摘要改动_$run"); field("百科改名${run.take(8)}").assertExists(); field(fullPersona).assertExists(); screenshot("reopened-full-mirror")
            check(row(j) == saved && character(j) == savedCharacter && versions(j).single().content == oldMirror); assertDrafts(j); check(otherCharactersDigest(j.get("character").asLong) == j.get("otherDigest").asString)
            j.addProperty("phase", "verified"); persist(j); output.resolve("verified.txt").writeText("oldMirrorExact8000=true\nfullCharacterAndMirrorPreserved=true\nhistoryKeepsLegacy8000=true\nmetadataOnlyAppSave=true\ndetailReopenAndQuickStartMessagesZero=true\notherDigestFourDraftGroupsStoryBranchesExact=true\n")
        } catch (failure: Throwable) {
            output.resolve("failure.txt").writeText(failure.stackTraceToString()); runCatching { screenshot("failure-window") }; throw failure
        }
    }

    @Test fun rollbackOnlyOwnedUuidData() {
        guard(); val j = journal(); val cid = j.get("character")?.asLong ?: 0L; val wid = j.get("world")?.asLong ?: 0L; val eid = j.get("entry")?.asLong ?: 0L
        runBlocking(Dispatchers.IO) { db.withTransaction {
            if (cid > 0L) db.sessionDao().getRecentForCharacter(cid, limit = 128).forEach { session ->
                check(db.participantDao().getBySession(session.id).map { it.characterId } == listOf(cid))
                if (wid > 0L) check(db.sessionWorldDao().getBySession(session.id)?.encyclopediaId == wid)
                check(db.messageDao().messageCount(session.id) == 0)
                db.sessionDao().delete(session.id)
            }
            if (eid > 0L) db.encyclopediaEntryDao().getById(eid)?.let { entry -> check(entry.encyclopediaId == wid && (cid <= 0L || CharacterEncyclopediaSync.readLinkedCharacterId(entry.metaJson) == cid)); db.encyclopediaEntryDao().delete(eid) }
            if (wid > 0L) db.encyclopediaDao().getById(wid)?.let { world -> check(world.name == worldTitle); check(db.encyclopediaEntryDao().getByEncyclopedia(wid).isEmpty()); db.encyclopediaDao().delete(wid) }
            if (cid > 0L) db.characterDao().getById(cid)?.let { character -> check(character.boundEncyclopediaId == wid && (character.name == characterName || character.name == "百科改名${run.take(8)}")); db.characterDao().delete(cid) }
            if (cid > 0L) check(db.characterDao().getById(cid) == null)
            if (wid > 0L) check(db.encyclopediaDao().getById(wid) == null)
            if (eid > 0L) check(db.encyclopediaEntryDao().getById(eid) == null)
        } }
        j.get("session")?.asLong?.let { sid ->
            check(!j.getAsJsonObject("chatDrafts").has("session_$sid"))
            check(chatPrefs.edit().remove("session_$sid").commit())
            runBlocking(Dispatchers.IO) { UiPreferencesRepository(context).clearLastChatBranch(sid) }
        }
        if (cid > 0L) { check(!j.getAsJsonObject("characterDrafts").has("character_$cid")); check(characterPrefs.edit().remove("character_$cid").commit()) }
        if (wid > 0L && eid > 0L) {
            check(!j.getAsJsonObject("entryDrafts").has("encyclopedia_${wid}_entry_$eid"))
            check(entryPrefs.edit().remove("encyclopedia_${wid}_entry_$eid").commit())
        }
        assertDrafts(j); check(otherCharactersDigest() == j.get("otherDigest").asString); j.addProperty("phase", "rolled-back"); persist(j); output.resolve("rollback.txt").writeText("onlyOwnedUuidRowsAndDraftsRemoved=true\notherDigestFourDraftGroupsStoryBranchesExact=true\n")
    }
}

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
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import com.mojing.app.domain.encyclopedia.CharacterEncyclopediaSync
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.ui.chat.RetainedChatSessions
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import com.mojing.app.data.prefs.UiPreferencesRepository
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EntryCharacterFlowAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("bindingRun").orEmpty().also { UUID.fromString(it) }
    private val title get() = "角色航图${run.take(8)}"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val journalFile get() = File(context.filesDir, "entry-character-flow-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "entry-character-flow-$run").apply { mkdirs() }
    private fun guard() {
        check(args.getString("bindingCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "entry-character-flow-$run.pending")
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

    private val entryPrefs get() = context.getSharedPreferences("entry_edit_drafts_v1", 0)
    private val entryTitle get() = "归潮${run.take(8)}"
    private fun row(j: JsonObject) = runBlocking(Dispatchers.IO) { checkNotNull(db.encyclopediaEntryDao().getById(j.get("entry").asLong)) }
    private fun versions(j: JsonObject) = runBlocking(Dispatchers.IO) { db.entryVersionDao().getByEntry(j.get("entry").asLong) }
    private fun back() { inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); rule.waitForIdle() }
    private fun openWorldAndEntry(name: String) {
        rule.waitUntil(10000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasText("创作") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        waitText("世界"); rule.onNodeWithText("世界").performClick()
        rule.waitUntil(10000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(title)
        hideKeyboard()
        rule.waitUntil(10000) { rule.onAllNodesWithText(title).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(title).filter(!hasSetTextAction()).onLast().performClick()
        waitText(name); rule.onNodeWithText(name).performScrollTo().performClick(); waitText("编辑条目")
    }
    private fun field(value: String) = rule.onNode(hasSetTextAction() and hasText(value), useUnmergedTree = true)
    private fun replace(old: String, next: String) { field(old).performScrollTo().performTextReplacement(next); hideKeyboard(); rule.waitForIdle() }
    private fun advanced() {
        rule.onNodeWithText("高级设置").performScrollTo().performClick(); rule.waitForIdle()
    }
    private fun save(j: JsonObject, expectedTitle: String, count: Int) {
        hideKeyboard()
        rule.onAllNodesWithText("保存修改").onFirst().assertIsEnabled().performClick()
        rule.waitUntil(10000) { row(j).title == expectedTitle && versions(j).size == count && rule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun history() { rule.onNodeWithText("版本").performClick(); waitText("载入文本资料") }
    private fun selectOld() { rule.onAllNodesWithText("载入文本资料").onLast().performScrollTo().performClick(); waitText("载入 v1 文本资料？") }
    private fun assertText(titleValue: String, summary: String, content: String, tags: String, meta: String) {
        listOf(titleValue, summary, content, tags, meta).forEach { field(it).assertExists() }
    }

    private val marker get() = "ENTRY_CHARACTER_$run"
    private val uiPreferences get() = UiPreferencesRepository(context)
    private fun branchSnapshot() = runBlocking(Dispatchers.IO) { JsonObject().apply { uiPreferences.lastChatBranches.first().forEach { (id, branch) -> addProperty(id.toString(), branch) } } }
    private val originalPersona get() = (1..24).joinToString("\n") { "航海记录$it：归潮守护灯塔，记得旧港的约定。$marker" }
    private val changedPersona get() = (1..28).joinToString("\n") { "新记录$it：行舟探索群岛，保留同一世界中的经历。$marker" }
    private fun character(j: JsonObject) = runBlocking(Dispatchers.IO) { checkNotNull(db.characterDao().getById(j.get("character").asLong)) }
    private fun otherCharactersDigest(excluded: Long? = null): String = runBlocking(Dispatchers.IO) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var cursor: CharacterEntity? = null
        do {
            val page = db.characterDao().getExportPage(cursor?.let { if (it.pinnedAt > 0) 0 else 1 }, cursor?.pinnedAt, cursor?.favorite, cursor?.createdAt, cursor?.id, 128)
            page.filter { it.id != excluded }.forEach { digest.update(Gson().toJson(it).toByteArray()); digest.update(0.toByte()) }
            cursor = page.lastOrNull()
        } while (page.size == 128)
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun assertMirrors(j: JsonObject) = runBlocking(Dispatchers.IO) {
        check(CharacterEncyclopediaSync.findCharacterMirrorIds(db.encyclopediaEntryDao(), j.get("world").asLong, j.get("character").asLong) == listOf(j.get("entry").asLong))
        check(db.encyclopediaEntryDao().countEntries(j.get("world").asLong) == 1)
    }
    private fun leaveEntry() { back(); waitText("关系图"); back(); waitText("搜索世界名称"); back(); waitText("世界") }
    private fun openCharacter(name: String, persona: String) {
        rule.onNodeWithText("角色").performClick()
        rule.waitUntil(10000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(name); hideKeyboard()
        rule.waitUntil(10000) { rule.onAllNodesWithText(name).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(name).filter(!hasSetTextAction()).onLast().performClick(); waitText("角色详情")
        waitText(persona); rule.onNodeWithText(persona).assertExists()
        rule.onNodeWithText("所属世界 · $title").assertExists()
    }
    private fun assertDrafts(j: JsonObject) {
        assertUnchanged(worldPrefs, j.getAsJsonObject("originalWorldDrafts")); assertUnchanged(characterPrefs, j.getAsJsonObject("originalCharacterDrafts"))
        assertUnchanged(chatPrefs, j.getAsJsonObject("originalChatDrafts")); assertUnchanged(entryPrefs, j.getAsJsonObject("originalEntryDrafts"))
        check(branchSnapshot() == j.getAsJsonObject("originalBranches"))
    }
    @Test fun encyclopediaEditHistoryDetailAndStartChat() {
        guard(); check(!journalFile.exists())
        try {
            val j = JsonObject().apply {
                addProperty("run", run); addProperty("otherCharactersDigest", otherCharactersDigest())
                add("originalBranches", branchSnapshot())
                add("originalWorldDrafts", snapshot(worldPrefs)); add("originalCharacterDrafts", snapshot(characterPrefs))
                add("originalChatDrafts", snapshot(chatPrefs)); add("originalEntryDrafts", snapshot(entryPrefs))
            }; persist(j)
            runBlocking(Dispatchers.IO) { db.withTransaction {
                val wid = db.encyclopediaDao().upsert(EncyclopediaEntity(name = title, description = marker,
                    worldPrompt = "$marker 当前群岛世界", gameplayMode = "自由剧情", antiCheatPrompt = "$marker 世界规则"))
                j.addProperty("world", wid); persist(j)
                val cid = SaveCharacterBindingUseCase(db)(CharacterEntity(name = entryTitle, personaPrompt = originalPersona, boundEncyclopediaId = wid,
                    temperature = 0.72f, maxTokens = 1700, favorite = true))
                j.addProperty("character", cid)
                val eid = CharacterEncyclopediaSync.findCharacterMirrorIds(db.encyclopediaEntryDao(), wid, cid).single()
                j.addProperty("entry", eid); persist(j)
                check(!characterPrefs.contains("character_$cid")); check(!entryPrefs.contains("encyclopedia_${wid}_entry_$eid"))
            } }
            check(otherCharactersDigest(j.get("character").asLong) == j.get("otherCharactersDigest").asString)
            val initial = row(j); val initialCharacter = character(j); assertMirrors(j)
            openWorldAndEntry(initial.title)
            replace(initial.title, "行舟${run.take(8)}"); replace(initial.summary, "修订摘要_$run"); replace(initial.content, changedPersona)
            save(j, "行舟${run.take(8)}", 1)
            val changed = row(j); val changedCharacter = character(j); val oldVersions = versions(j)
            check(changedCharacter.copy(name = initialCharacter.name, personaPrompt = initialCharacter.personaPrompt, updatedAt = initialCharacter.updatedAt) == initialCharacter)
            check(changedCharacter.name == changed.title && changedCharacter.personaPrompt == changed.content)
            check(oldVersions.single().title == initial.title && oldVersions.single().content == initial.content && oldVersions.single().metaSnapshotJson == initial.metaJson)
            assertMirrors(j); screenshot("encyclopedia-edited-character")
            leaveEntry(); openCharacter(changed.title, changed.content); screenshot("detail-after-encyclopedia-save")
            rule.onNodeWithContentDescription("返回角色").performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("返回创作中心").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("返回创作中心").performClick(); waitText("世界")
            openWorldAndEntry(changed.title)
            replace(changed.title, "未存${run.take(8)}")
            history(); selectOld(); screenshot("history-character-confirm")
            rule.onNodeWithText("保留当前修改").performClick(); rule.onNodeWithText("编辑").performClick()
            field("未存${run.take(8)}").assertExists(); check(row(j) == changed && character(j) == changedCharacter && versions(j) == oldVersions)
            field(changed.summary).assertExists(); field(changed.content).assertExists()
            history(); selectOld(); rule.onNodeWithText("替换并载入").performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("编辑目录").fetchSemanticsNodes().isNotEmpty() }
            field(initial.title).assertExists(); field(initial.content).assertExists()
            check(row(j) == changed && character(j) == changedCharacter && versions(j) == oldVersions)
            save(j, initial.title, 2)
            val restored = row(j); val restoredCharacter = character(j); val finalVersions = versions(j)
            check(restoredCharacter.copy(updatedAt = initialCharacter.updatedAt) == initialCharacter)
            check(restored.copy(updatedAt = initial.updatedAt) == initial)
            check(finalVersions.drop(1) == oldVersions && finalVersions.first().version == 2 && finalVersions.first().title == changed.title && finalVersions.first().content == changed.content)
            assertMirrors(j); screenshot("restored-character-saved")
            leaveEntry(); openCharacter(initial.title, initial.content); screenshot("detail-restored-character")
            rule.onNodeWithText("开始对话").performScrollTo().performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }
            runBlocking(Dispatchers.IO) {
                val sessions = db.sessionDao().getRecentForCharacter(j.get("character").asLong); check(sessions.size == 1)
                val sid = sessions.single().id; j.addProperty("session", sid); persist(j)
                check(sessions.single().title == "${initial.title} · 新故事")
                check(db.participantDao().getBySession(sid).map { it.characterId } == listOf(j.get("character").asLong))
                val world = checkNotNull(db.sessionWorldDao().getBySession(sid)); val current = checkNotNull(db.encyclopediaDao().getById(j.get("world").asLong))
                check(world.encyclopediaId == current.id && world.worldPrompt == current.worldPrompt && world.gameplayMode == current.gameplayMode)
                check(world.templateId == "custom")
                check(world.antiCheatPrompt == if (world.antiCheatEnabled) current.antiCheatPrompt else "")
                check(db.messageDao().messageCount(sid) == 0)
            }
            screenshot("started-same-character-current-world")
            rule.onNodeWithContentDescription("返回会话主页").performClick(); waitText("故事库")
            openWorldAndEntry(initial.title); field(initial.title).assertExists(); field(initial.content).assertExists()
            check(row(j) == restored && character(j) == restoredCharacter && versions(j) == finalVersions); assertMirrors(j)
            history(); screenshot("reopened-character-two-versions")
            leaveEntry(); openCharacter(initial.title, initial.content)
            rule.onNodeWithText("${initial.title} · 新故事").assertExists(); check(row(j) == restored && character(j) == restoredCharacter)
            screenshot("reopened-detail-related-story")
            rule.onNodeWithContentDescription("返回角色").performClick(); back()
            // Chat owner may have created only the UUID session's own draft/preference.
            j.get("session")?.asLong?.let { check(chatPrefs.edit().remove("session_$it").commit()) }
            j.get("session")?.asLong?.let { runBlocking(Dispatchers.IO) { uiPreferences.clearLastChatBranch(it) } }
            assertDrafts(j); check(otherCharactersDigest(j.get("character").asLong) == j.get("otherCharactersDigest").asString)
            j.addProperty("phase", "verified"); persist(j)
            output.resolve("verified.txt").writeText("encyclopediaUiSaveUpdatesSameCharacter=true\nproductionHistoryV1V2=true\ncancelAndBeforeSaveRoomExact=true\nrestoredCharacterAndUniqueMirror=true\ndetailBeforeAndAfterRestoreExact=true\nstartSingleSessionSameParticipantCurrentWorldMessagesZero=true\nreopenRowsVersionsExact=true\notherCharactersDigestAndFourDraftGroupsExact=true\n")
        } catch (failure: Throwable) {
            output.resolve("failure.txt").writeText(failure.stackTraceToString())
            runCatching { output.resolve("failure-tree.txt").writeText(rule.onAllNodes(isRoot(), useUnmergedTree = true).onFirst().printToString(maxDepth = 100)) }
            runCatching { screenshot("failure-window") }; throw failure
        }
    }
    @Test fun rollbackOnlyOwnedCharacterWorldSessionAndDrafts() {
        guard(); val j = journal(); val sessions = mutableListOf<Long>()
        runBlocking(Dispatchers.IO) { db.withTransaction {
            j.get("character")?.asLong?.let { cid ->
                val c = db.characterDao().getById(cid)
                check(c == null || c.boundEncyclopediaId == j.get("world").asLong && c.personaPrompt.contains(marker) && c.name.endsWith(run.take(8)))
                db.sessionDao().getRecentForCharacter(cid, limit = 128).forEach { s ->
                    check(s.title.endsWith(" · 新故事") && s.title.contains(run.take(8)))
                    check(db.participantDao().getBySession(s.id).map { it.characterId } == listOf(cid))
                    check(db.sessionWorldDao().getBySession(s.id)?.encyclopediaId == j.get("world").asLong)
                    check(db.messageDao().messageCount(s.id) == 0)
                    sessions += s.id
                }
                sessions.forEach { db.sessionDao().delete(it) }
                if (c != null) db.characterDao().delete(cid)
            }
            j.get("world")?.asLong?.let { wid ->
                val world = db.encyclopediaDao().getById(wid); check(world == null || world.name == title && world.description == marker)
                db.encyclopediaEntryDao().getByEncyclopedia(wid).forEach { check(it.id == j.get("entry").asLong && it.content.contains(marker)) }
                check(db.entryRelationDao().getByEncyclopedia(wid).isEmpty())
                if (world != null) db.encyclopediaDao().delete(wid)
                check(db.encyclopediaEntryDao().countEntries(wid) == 0)
            }
            j.get("entry")?.asLong?.let { check(db.entryVersionDao().getByEntry(it).isEmpty()) }
        } }
        sessions.forEach { check(chatPrefs.edit().remove("session_$it").commit()) }
        runBlocking(Dispatchers.IO) { sessions.forEach { uiPreferences.clearLastChatBranch(it) } }
        j.get("character")?.asLong?.let { cid -> val key = "character_$cid"; check(!j.getAsJsonObject("originalCharacterDrafts").has(key)); check(characterPrefs.edit().remove(key).commit()) }
        j.get("entry")?.asLong?.let { eid -> val key = "encyclopedia_${j.get("world").asLong}_entry_$eid"; check(!j.getAsJsonObject("originalEntryDrafts").has(key)); check(entryPrefs.edit().remove(key).commit()) }
        assertDrafts(j); check(otherCharactersDigest() == j.get("otherCharactersDigest").asString)
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("onlyUuidWorldCharacterMirrorVersionsSessionDraftsRemoved=true\notherCharactersDigestFourDraftGroupsExact=true\n")
    }
}

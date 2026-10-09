package com.mojing.app.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.StoryOpeningInputDraftStore
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.LegacyWorldMappingEntity
import com.mojing.app.data.local.entity.WorldTemplateEntity
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

/** Real detail start-chat with conflicting mapped default; no message/provider request. */
class CharacterWorldSnapshotAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("worldRun").orEmpty().also { UUID.fromString(it) }
    private val marker get() = "CHARACTER_WORLD_$run"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val preferences get() = rule.activity.secureStorage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(rule.activity.secureStorage) as SharedPreferences
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val journalFile get() = File(context.filesDir, "character-world-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "character-world-$run").apply { mkdirs() }
    private val templateKey get() = "world-default-$run"
    private val defaultKeys = listOf("default_world_template_id", "default_anti_cheat_enabled")
    private fun title(bound: Boolean) = (if (bound) "归潮" else "旅人") + run.take(8)
    private fun guard() {
        check(args.getString("worldCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "character-world-$run.pending")
        FileOutputStream(pending).use { it.write(j.toString().toByteArray()); it.fd.sync() }
        check(pending.renameTo(journalFile))
    }
    private fun journal() = JsonParser.parseString(journalFile.readText()).asJsonObject.also { check(it.get("run").asString == run) }
    private fun drafts(p: SharedPreferences) = JsonObject().apply {
        p.all.forEach { (key, value) -> check(value is String); addProperty(key, value) }
    }
    private fun assertDrafts(p: SharedPreferences, original: JsonObject) {
        check(p.all.keys == original.keySet())
        original.entrySet().forEach { (key, value) -> check(p.getString(key, null) == value.asString) { "unrelated draft changed" } }
    }
    private fun waitText(text: String) {
        rule.waitUntil(10000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun openDetail(bound: Boolean) {
        rule.waitForIdle()
        rule.waitUntil(10000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasText("创作") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        waitText("角色"); rule.onNodeWithText("角色").performClick()
        rule.waitUntil(10000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(title(bound))
        rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0) }
        rule.waitUntil(10000) { rule.onAllNodesWithText(title(bound)).fetchSemanticsNodes().size >= 2 && rule.onAllNodesWithText(title(bound)).onLast().isDisplayed() }
        rule.onAllNodesWithText(title(bound)).onLast().performClick(); waitText("角色详情")
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
    @Test fun boundAndUnboundCharactersStartWithTheirExpectedWorld() {
        guard(); check(!journalFile.exists())
        val originals = JsonObject().apply { defaultKeys.forEach { key ->
            add(key, JsonObject().apply {
                addProperty("present", preferences.contains(key))
                if (preferences.contains(key)) {
                    if (key == "default_world_template_id") addProperty("value", preferences.getString(key, null))
                    else addProperty("value", preferences.getBoolean(key, false))
                }
            })
        } }
        val j = JsonObject().apply {
            addProperty("run", run); addProperty("phase", "snapshot")
            add("originalDefaults", originals); add("originalChatDrafts", drafts(chatPrefs)); add("originalCharacterDrafts", drafts(characterPrefs))
        }
        persist(j)
        runBlocking(Dispatchers.IO) { db.withTransaction {
            for (suffix in listOf("A", "B")) {
                val id = db.encyclopediaDao().upsert(EncyclopediaEntity(
                    name = "世界$suffix${run.take(8)}", description = marker, worldPrompt = "$marker ${suffix}当前背景",
                    gameplayMode = "${suffix}当前玩法", antiCheatPrompt = "$marker ${suffix}当前规则",
                ))
                j.addProperty("world$suffix", id); persist(j)
            }
            val tid = db.worldTemplateDao().upsert(WorldTemplateEntity(
                templateId = templateKey, label = "默认A${run.take(8)}", summary = marker,
                worldPrompt = "$marker A旧背景", gameplayMode = "A旧玩法", antiCheatPrompt = "$marker A旧规则",
                suggestedChoicesJson = "[\"A旧选项\"]",
            ))
            j.addProperty("template", tid); persist(j)
            db.legacyWorldMappingDao().insert(LegacyWorldMappingEntity(tid, j.get("worldA").asLong, marker))
            for (bound in listOf(true, false)) {
                val id = SaveCharacterBindingUseCase(db)(CharacterEntity(
                    name = title(bound), personaPrompt = marker, boundEncyclopediaId = if (bound) j.get("worldB").asLong else 0L,
                ))
                j.addProperty(if (bound) "boundCharacter" else "unboundCharacter", id); persist(j)
                check(!characterPrefs.contains("character_$id"))
            }
            j.addProperty("phase", "fixture-created"); persist(j)
        } }
        check(preferences.edit().putString("default_world_template_id", templateKey).putBoolean("default_anti_cheat_enabled", true).commit())
        for (bound in listOf(true, false)) {
            if (!bound) {
                rule.onNodeWithContentDescription("返回会话主页").performClick()
                waitText("故事库")
            }
            openDetail(bound)
            screenshot(if (bound) "bound-character-detail" else "unbound-character-detail")
            rule.onNodeWithText("开始对话").performScrollTo().performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }
            val cid = j.get(if (bound) "boundCharacter" else "unboundCharacter").asLong
            val suffix = if (bound) "B" else "A"
            runBlocking(Dispatchers.IO) {
                val sessions = db.sessionDao().getRecentForCharacter(cid); check(sessions.size == 1)
                val sid = sessions.single().id
                check(sessions.single().title == "${title(bound)} · 新故事")
                check(db.participantDao().getBySession(sid).map { it.characterId } == listOf(cid))
                val world = checkNotNull(db.sessionWorldDao().getBySession(sid))
                check(world.encyclopediaId == j.get("world$suffix").asLong)
                check(world.templateId == "custom" && world.worldPrompt == "$marker ${suffix}当前背景")
                check(world.gameplayMode == "${suffix}当前玩法" && world.antiCheatEnabled && world.antiCheatPrompt == "$marker ${suffix}当前规则")
                check(world.suggestedChoicesJson == if (bound) "[]" else "[\"A旧选项\"]")
                check(db.messageDao().getNextStoryContextBatch(sid, "main", 0, 10).isEmpty())
                j.addProperty(if (bound) "boundSession" else "unboundSession", sid); persist(j)
            }
            screenshot(if (bound) "bound-started-chat" else "unbound-started-chat")
        }
        j.addProperty("phase", "verified"); persist(j)
        output.resolve("verified.txt").writeText("boundWorldBExact=true\nunboundDefaultAExact=true\nrealDetailStartBoth=true\nnoMessages=true\n")
    }
    @Test fun rollbackOnlyOwnedFixturesAndRestoreExactDefaults() {
        guard(); val j = journal(); rule.waitForIdle()
        val sessions = mutableListOf<Long>()
        runBlocking(Dispatchers.IO) { db.withTransaction {
            for (bound in listOf(true, false)) {
                val cid = j.get(if (bound) "boundCharacter" else "unboundCharacter")?.asLong ?: continue
                val character = db.characterDao().getById(cid)
                val wid = if (bound) j.get("worldB").asLong else 0L
                check(character == null || (character.name == title(bound) && character.personaPrompt == marker && character.boundEncyclopediaId == wid))
                db.sessionDao().getRecentForCharacter(cid).forEach { session ->
                    check(session.title == "${title(bound)} · 新故事")
                    check(db.participantDao().getBySession(session.id).map { it.characterId } == listOf(cid))
                    check(db.sessionWorldDao().getBySession(session.id)?.encyclopediaId == j.get(if (bound) "worldB" else "worldA").asLong)
                    sessions.add(session.id); db.sessionDao().delete(session.id)
                }
                db.characterDao().delete(cid)
            }
            j.get("template")?.asLong?.let { tid ->
                val template = db.worldTemplateDao().getById(tid)
                check(template == null || (template.templateId == templateKey && template.summary == marker))
                val mapping = db.legacyWorldMappingDao().getByTemplateId(tid)
                check(mapping == null || (mapping.sourceHash == marker && mapping.encyclopediaId == j.get("worldA").asLong))
                db.openHelper.writableDatabase.execSQL("DELETE FROM legacy_world_mappings WHERE worldTemplateId = ? AND sourceHash = ?", arrayOf<Any>(tid, marker))
                db.worldTemplateDao().delete(tid)
            }
            for (suffix in listOf("A", "B")) j.get("world$suffix")?.asLong?.let { wid ->
                check(db.encyclopediaDao().getById(wid)?.description.let { it == null || it == marker })
                db.encyclopediaDao().delete(wid)
            }
        } }
        val editor = preferences.edit()
        defaultKeys.forEach { key ->
            val original = j.getAsJsonObject("originalDefaults").getAsJsonObject(key)
            if (!original.get("present").asBoolean) editor.remove(key)
            else if (key == "default_world_template_id") editor.putString(key, original.get("value").asString)
            else editor.putBoolean(key, original.get("value").asBoolean)
        }
        check(editor.commit())
        defaultKeys.forEach { key ->
            val original = j.getAsJsonObject("originalDefaults").getAsJsonObject(key)
            check(preferences.contains(key) == original.get("present").asBoolean)
            if (original.get("present").asBoolean) {
                if (key == "default_world_template_id") check(preferences.getString(key, null) == original.get("value").asString)
                else check(preferences.getBoolean(key, false) == original.get("value").asBoolean)
            }
        }
        sessions.forEach { check(chatPrefs.edit().remove("session_$it").commit()) }
        for (key in listOf("boundCharacter", "unboundCharacter")) j.get(key)?.asLong?.let { check(characterPrefs.edit().remove("character_$it").commit()) }
        assertDrafts(chatPrefs, j.getAsJsonObject("originalChatDrafts")); assertDrafts(characterPrefs, j.getAsJsonObject("originalCharacterDrafts"))
        runBlocking(Dispatchers.IO) {
            sessions.forEach { check(db.sessionDao().getById(it) == null) }
            for (key in listOf("boundCharacter", "unboundCharacter")) j.get(key)?.asLong?.let { check(db.characterDao().getById(it) == null) }
            j.get("template")?.asLong?.let { check(db.worldTemplateDao().getById(it) == null); check(db.legacyWorldMappingDao().getByTemplateId(it) == null) }
            for (key in listOf("worldA", "worldB")) j.get(key)?.asLong?.let { check(db.encyclopediaDao().getById(it) == null); check(db.encyclopediaEntryDao().countEntries(it) == 0) }
        }
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("ownedFixturesRemoved=true\ndefaultKeyPresenceAndValuesExact=true\notherCharacterAndChatDraftsExact=true\nprivateJournalRetained=true\n")
    }
}

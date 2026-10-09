package com.mojing.app.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.StoryOpeningInputDraftStore
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.CharacterProfileEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import com.mojing.app.domain.util.CharacterCardPngCodec
import com.mojing.app.ui.chat.RetainedChatSessions
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Compose + SAF flow for exporting an unsaved 16000-char persona and importing it as a new UUID. */
class CharacterPngPersonaAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val run get() = args.getString("pngRun").orEmpty().also { UUID.fromString(it) }
    private val sourceName get() = "PNG长人设${run.take(8)}"
    private val marker get() = "PNG_PERSONA_$run"
    private val pngName get() = "mojing-png-$run.png"
    private val journalFile get() = File(context.filesDir, "character-png-persona-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "character-png-persona-$run").apply { mkdirs() }
    private val authority = "com.mojing.app.test.library.documents"
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val entryPrefs get() = context.getSharedPreferences("entry_edit_drafts_v1", 0)
    private val fullPersona get() = buildString { while (length < 16_000) append("编辑后长人设：$marker；必须完整往返的正文规则。\n"); setLength(16_000) }
    private val oldPersona get() = "旧 profile 正文；$marker"
    private val notes get() = "独立 creator notes：$marker；不得注入 persona。"
    private val oldProfileJson get() = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"$sourceName","description":"$oldPersona","personality":"旧性格","scenario":"旧场景","first_mes":"旧开场","mes_example":"旧示例","system_prompt":"旧系统","post_history_instructions":"旧后置","creator_notes":"$notes","alternate_greetings":["旧问候"],"tags":["$marker"],"character_book":{"name":"$marker"},"creator":"旧作者","character_version":"旧版本","extensions":{"unknown":{"keep":"$marker"}}}}"""

    private fun guard() {
        assumeTrue(args.getString("pngCapture") == "true")
        assumeTrue(Build.FINGERPRINT.contains("generic", true) || Build.MODEL.startsWith("sdk_gphone", true))
        check(RetainedChatSessions.running.value.isEmpty()); check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun snapshot(p: SharedPreferences) = JsonObject().apply { p.all.forEach { (k, v) -> add(k, JsonObject().apply { when (v) { is String -> { addProperty("type", "string"); addProperty("value", v) }; is Boolean -> { addProperty("type", "boolean"); addProperty("value", v) }; else -> error("unexpected draft type") } }) } }
    private fun assertSnapshot(p: SharedPreferences, j: JsonObject) { check(p.all.keys == j.keySet()); j.entrySet().forEach { (k, v) -> val x = v.asJsonObject; if (x.get("type").asString == "string") check(p.getString(k, null) == x.get("value").asString) else check(p.getBoolean(k, false) == x.get("value").asBoolean) } }
    private fun branches() = runBlocking(Dispatchers.IO) { JsonObject().apply { UiPreferencesRepository(context).lastChatBranches.first().forEach { (id, b) -> addProperty(id.toString(), b) } } }
    private fun persist(j: JsonObject) { val p = File(context.filesDir, "character-png-persona-$run.pending"); FileOutputStream(p).use { it.write(j.toString().toByteArray()); it.fd.sync() }; check(p.renameTo(journalFile)) }
    private fun journal() = JsonParser.parseString(journalFile.readText()).asJsonObject.also { check(it.get("run").asString == run) }
    private fun digest(exclude: Long? = null) = runBlocking(Dispatchers.IO) { val d = MessageDigest.getInstance("SHA-256"); var c: CharacterEntity? = null; do { val page = db.characterDao().getExportPage(c?.let { if (it.pinnedAt > 0) 0 else 1 }, c?.pinnedAt, c?.favorite, c?.createdAt, c?.id, 128); page.filter { it.id != exclude }.forEach { d.update(Gson().toJson(it).toByteArray()); d.update(0.toByte()) }; c = page.lastOrNull() } while (page.size == 128); d.digest().joinToString("") { "%02x".format(it) } }
    private fun recordImport(j: JsonObject, required: Boolean = true): Long? = runBlocking(Dispatchers.IO) {
        val page = db.characterDao().getLibraryNamePage(null, null, null, null, 20, sourceName)
        check(page.size < 20)
        val owned = page.filter { it.id != j.get("sourceId")?.asLong && it.name.startsWith(sourceName) }.filter {
            val profile = db.characterProfileDao().getByCharacter(it.id)
            profile?.sourceFilename == pngName && profile.characterCardJson.contains(marker)
        }
        check(owned.size <= 1)
        val id = owned.singleOrNull()?.id
        if (required) checkNotNull(id)
        if (id != null) { j.addProperty("importedId", id); persist(j) }
        id
    }
    private fun screenshot(name: String) { rule.waitForIdle(); inst.waitForIdleSync(); if (inst.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName) repeat(2) { val l = CountDownLatch(1); rule.runOnUiThread { rule.activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { l.countDown() }; rule.activity.window.decorView.invalidate() }; check(l.await(3, TimeUnit.SECONDS)) }; inst.uiAutomation.waitForIdle(300, 5000); val b = checkNotNull(inst.uiAutomation.takeScreenshot()); try { FileOutputStream(output.resolve("$name.png")).use { check(b.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { b.recycle() } }
    private fun hideKeyboard() = rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0) }
    private fun back() { inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); rule.waitForIdle() }
    private fun waitText(t: String) { rule.waitUntil(10_000) { rule.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitAppWindow() {
        val deadline = android.os.SystemClock.uptimeMillis() + 12_000
        while (inst.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName) {
            check(android.os.SystemClock.uptimeMillis() < deadline) { "SAF did not return to app" }
            android.os.SystemClock.sleep(50)
        }
        inst.waitForIdleSync()
    }
    private fun field(t: String) = rule.onNode(hasSetTextAction() and hasText(t), useUnmergedTree = true)
    private fun digestAndDrafts(j: JsonObject) { check(digest(j.get("sourceId")?.asLong) == j.get("digest").asString); assertSnapshot(worldPrefs, j.getAsJsonObject("world")); assertSnapshot(characterPrefs, j.getAsJsonObject("character")); assertSnapshot(chatPrefs, j.getAsJsonObject("chat")); assertSnapshot(entryPrefs, j.getAsJsonObject("entry")); check(branches() == j.getAsJsonObject("branches")) }

    private fun callProvider(method: String, name: String = "", extra: Bundle? = null): Bundle? {
        val uri = android.net.Uri.parse("content://$authority"); val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) != android.content.pm.PackageManager.PERMISSION_GRANTED) { context.startActivity(android.content.Intent().setClassName("com.mojing.app.test", "com.mojing.app.test.LibraryExportFixtureActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); val end = android.os.SystemClock.uptimeMillis() + 5_000; while (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) != android.content.pm.PackageManager.PERMISSION_GRANTED && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(20); check(context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
        return context.contentResolver.call(uri, method, name, extra)
    }
    private fun node(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? { if (root == null) return null; if (root.isVisibleToUser && (root.text?.toString()?.equals(text, ignoreCase = true) == true || root.contentDescription?.toString()?.equals(text, ignoreCase = true) == true)) return root; for (i in 0 until root.childCount) node(root.getChild(i), text)?.let { return it }; return null }
    private fun clickDocument(text: String): Boolean { val end = android.os.SystemClock.uptimeMillis() + 10_000; while (android.os.SystemClock.uptimeMillis() < end) { val n = node(inst.uiAutomation.rootInActiveWindow, text) ?: if (text == "保存") node(inst.uiAutomation.rootInActiveWindow, "Save") else null; if (n != null) { var p: AccessibilityNodeInfo = n; while (!p.isClickable) p = p.parent ?: break; if (p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; if (text == "资料导出验收" && n.isVisibleToUser && !p.isClickable) return true }; if (text == "资料导出验收") (node(inst.uiAutomation.rootInActiveWindow, "Show roots") ?: node(inst.uiAutomation.rootInActiveWindow, "显示根目录"))?.performAction(AccessibilityNodeInfo.ACTION_CLICK); android.os.SystemClock.sleep(100) }; return false }
    private fun setDocumentText(text: String): Boolean { val end = android.os.SystemClock.uptimeMillis() + 10_000; while (android.os.SystemClock.uptimeMillis() < end) { fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? { if (n == null) return null; if (n.className?.toString() == "android.widget.EditText" && n.isVisibleToUser) return n; for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }; return null }; val f = findEditable(inst.uiAutomation.rootInActiveWindow); if (f != null && f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })) return true; android.os.SystemClock.sleep(100) }; return false }
    private fun openSourceDetail() { rule.onNode(hasText("创作") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, Role.Tab)).performClick(); waitText("角色"); rule.onNodeWithText("角色").performClick(); rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }; rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(sourceName); hideKeyboard(); rule.waitUntil(10_000) { rule.onAllNodesWithText(sourceName).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }; rule.onAllNodesWithText(sourceName).filter(!hasSetTextAction()).onLast().performClick(); waitText("角色详情") }

    @Test fun editedLongPersonaPngExportImportDetailAndEmptyStart() {
        guard(); check(!journalFile.exists()); val j = JsonObject().apply { addProperty("run", run); addProperty("sourceName", sourceName); add("world", snapshot(worldPrefs)); add("character", snapshot(characterPrefs)); add("chat", snapshot(chatPrefs)); add("entry", snapshot(entryPrefs)); add("branches", branches()); addProperty("digest", digest()) }; persist(j)
        try {
            val sourceId = runBlocking(Dispatchers.IO) { db.withTransaction { val id = SaveCharacterBindingUseCase(db)(CharacterEntity(name = sourceName, personaPrompt = oldPersona, avatarImagePath = "", cardImagePath = "")); j.addProperty("sourceId", id); persist(j); db.characterProfileDao().upsert(CharacterProfileEntity(characterId = id, sourceFilename = "old-profile.json", rawPersonaText = oldPersona, characterCardJson = oldProfileJson)); id } }
            callProvider("prepareMode", pngName, Bundle().apply { putString("mode", "normal") })
            rule.waitUntil(10_000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
            openSourceDetail(); rule.onNodeWithContentDescription("编辑角色").performClick(); waitText("编辑角色"); field(oldPersona).performScrollTo().performTextReplacement(fullPersona); hideKeyboard(); rule.onNodeWithText("导出与高级设置").performScrollTo().performClick(); rule.onNodeWithText("导出 PNG 形象卡").performScrollTo(); screenshot("01-editor-long-persona"); rule.onNodeWithText("导出 PNG 形象卡").performClick()
            check(clickDocument("资料导出验收")); check(setDocumentText(pngName)); screenshot("02-saf-before-save"); check(clickDocument("保存")); waitAppWindow(); rule.waitUntil(12_000) { rule.onAllNodes(hasText("已保存", substring = true)).fetchSemanticsNodes().isNotEmpty() }; j.addProperty("pngCreated", true); persist(j)
            val png = context.contentResolver.openInputStream(android.provider.DocumentsContract.buildDocumentUri(authority, pngName))!!.use { it.readBytes() }; check(CharacterCardPngCodec.isPng(png)); val data = checkNotNull(CharacterCardPngCodec.readCharaCardJsonRoot(png)).getAsJsonObject("data"); check(data.get("description").asString == fullPersona); check(data.get("creator_notes").asString == notes); check(data.getAsJsonObject("extensions").getAsJsonObject("unknown").get("keep").asString == marker); check(data.get("scenario").asString.isEmpty() && data.get("personality").asString.isEmpty()); FileOutputStream(output.resolve(pngName)).use { it.write(png); it.fd.sync() }; check(runBlocking(Dispatchers.IO) { db.characterDao().getById(sourceId)?.personaPrompt == oldPersona && db.characterProfileDao().getByCharacter(sourceId)?.characterCardJson == oldProfileJson })
            back(); waitText("保存角色修改？"); rule.onNodeWithText("放弃修改").performClick(); waitText("角色详情"); back(); waitText("角色"); rule.onNodeWithContentDescription("更多").performClick(); rule.onNodeWithText("导入（便携包 / JSON / TXT / Word / PNG）").performClick(); check(clickDocument("资料导出验收")); check(clickDocument(pngName)); waitAppWindow(); rule.waitUntil(15_000) { recordImport(j, required = false) != null }
            val importedId = checkNotNull(recordImport(j)); val imported = runBlocking(Dispatchers.IO) { checkNotNull(db.characterDao().getById(importedId)) }; check(imported.personaPrompt == fullPersona && imported.boundEncyclopediaId == 0L); rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(imported.name); hideKeyboard(); rule.waitUntil(10_000) { rule.onAllNodesWithText(imported.name).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }; rule.onAllNodesWithText(imported.name).filter(!hasSetTextAction()).onLast().performClick(); waitText("角色详情"); rule.onNodeWithText(fullPersona).assertExists(); check(runBlocking(Dispatchers.IO) { val p = checkNotNull(db.characterProfileDao().getByCharacter(importedId)); p.sourceFilename == pngName && JsonParser.parseString(p.characterCardJson).asJsonObject.getAsJsonObject("data").get("creator_notes").asString == notes }); screenshot("03-imported-detail-full-persona")
            rule.onNodeWithText("开始对话").performScrollTo().performClick(); rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }; val sid = runBlocking(Dispatchers.IO) { db.sessionDao().getRecentForCharacter(importedId, 128).single().id }; j.addProperty("sessionId", sid); persist(j); runBlocking(Dispatchers.IO) { check(db.participantDao().getBySession(sid).map { it.characterId } == listOf(importedId)); check(db.messageDao().messageCount(sid) == 0) }; screenshot("04-empty-start"); j.addProperty("phase", "verified"); persist(j); output.resolve("verified.txt").writeText("unsaved16000PersonaPngExport=true\ncreatorNotesPreservedOutsidePersona=true\npngSafImportNewUuidFullDetail=true\nemptyStartMessagesZero=true\n")
        } catch (t: Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString()); runCatching { screenshot("failure-window") }; throw t }
    }

    @Test fun rollbackOnlyOwnedPngPersonaRows() {
        guard(); val j = journal(); recordImport(j, required = false); val sourceId = j.get("sourceId")?.asLong ?: 0L; val importedId = j.get("importedId")?.asLong ?: 0L
        if (importedId > 0L && !j.has("sessionsToRollback")) {
            val ids = runBlocking(Dispatchers.IO) { db.sessionDao().getRecentForCharacter(importedId, 128).map { it.id } }
            j.add("sessionsToRollback", JsonArray().apply { ids.forEach { add(it) } }); persist(j)
        }
        runBlocking(Dispatchers.IO) { db.withTransaction {
            if (importedId > 0L) db.sessionDao().getRecentForCharacter(importedId, 128).forEach { s -> check(db.participantDao().getBySession(s.id).map { it.characterId } == listOf(importedId)); check(db.messageDao().messageCount(s.id) == 0); db.sessionDao().delete(s.id) }
            if (importedId > 0L) db.characterDao().getById(importedId)?.let { c -> check(c.name.startsWith(sourceName)); check(db.characterProfileDao().getByCharacter(importedId)?.sourceFilename == pngName); db.characterDao().delete(importedId) }
            if (sourceId > 0L) db.characterDao().getById(sourceId)?.let { c -> check(c.id == sourceId && c.name == sourceName && c.personaPrompt == oldPersona); check(db.characterProfileDao().getByCharacter(sourceId)?.characterCardJson == oldProfileJson); db.characterDao().delete(sourceId) }
            if (importedId > 0L) check(db.characterDao().getById(importedId) == null); if (sourceId > 0L) check(db.characterDao().getById(sourceId) == null)
        } }
        j.getAsJsonArray("sessionsToRollback")?.forEach { value -> val sid = value.asLong; check(!j.getAsJsonObject("chat").has("session_$sid")); check(chatPrefs.edit().remove("session_$sid").commit()); runBlocking(Dispatchers.IO) { UiPreferencesRepository(context).clearLastChatBranch(sid) } }
        listOf(sourceId, importedId).filter { it > 0L }.forEach { id -> check(!j.getAsJsonObject("character").has("character_$id")); check(characterPrefs.edit().remove("character_$id").commit()) }
        if (j.get("pngCreated")?.asBoolean == true) { check(callProvider("release", pngName) != null); check(callProvider("deleteOwned", pngName)?.getBoolean("deleted") == true) }
        digestAndDrafts(j); j.addProperty("phase", "rolled-back"); persist(j); output.resolve("rollback.txt").writeText("onlyOwnedSourceImportedSessionDraftsAndPngRemoved=true\notherDigestFourDraftGroupsBranchesExact=true\n")
    }
}

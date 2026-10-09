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
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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

/** MainActivity/SAF evidence for an unsaved portable JSON card with draft-only extensions. */
class CharacterExportDraftAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val run get() = args.getString("characterExportDraftRun").orEmpty().also { UUID.fromString(it) }
    private val sourceName get() = "JSON草稿角色${run.take(8)}"
    private val marker get() = "JSON_DRAFT_$run"
    private val jsonName get() = "mojing-character-draft-$run.json"
    private val pngName get() = "mojing-png-$run.png"
    private val journalFile get() = File(context.filesDir, "character-export-draft-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "character-export-draft-$run").apply { mkdirs() }
    private val authority = "com.mojing.app.test.library.documents"
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val entryPrefs get() = context.getSharedPreferences("entry_edit_drafts_v1", 0)
    private val oldPersona get() = "源角色 persona；$marker"
    private val draftPersona get() = "草稿 persona；$marker；导出后必须完整保留。"
    private val sourceProfileJson get() = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"$sourceName","description":"$oldPersona","personality":"源性格","scenario":"源场景","first_mes":"源开场","mes_example":"源示例","creator_notes":"源 notes $marker","alternate_greetings":["源问候"],"tags":["source-$marker"],"character_book":{"name":"source-$marker"},"creator":"源作者","character_version":"源版本","extensions":{"source":{"keep":"source-$marker"}}}}"""
    private val draftCardJson get() = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"$sourceName","description":"$draftPersona","personality":"草稿性格","scenario":"草稿场景","first_mes":"草稿开场","mes_example":"草稿示例","creator_notes":"draft-notes-$marker","alternate_greetings":["草稿问候"],"tags":["draft-$marker"],"character_book":{"name":"draft-$marker"},"creator":"草稿作者","character_version":"草稿版本","extensions":{"draft":{"keep":"draft-$marker","optional":null}}}}"""

    private fun guard() {
        assumeTrue(args.getString("characterExportDraftCapture") == "true")
        assumeTrue(Build.FINGERPRINT.contains("generic", true) || Build.MODEL.startsWith("sdk_gphone", true))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun snapshot(p: SharedPreferences) = JsonObject().apply { p.all.forEach { (k, v) -> add(k, JsonObject().apply { when (v) { is String -> { addProperty("type", "string"); addProperty("value", v) }; is Boolean -> { addProperty("type", "boolean"); addProperty("value", v) }; else -> error("unexpected draft type") } }) } }
    private fun assertSnapshot(p: SharedPreferences, j: JsonObject) { check(p.all.keys == j.keySet()); j.entrySet().forEach { (k, v) -> val x = v.asJsonObject; if (x.get("type").asString == "string") check(p.getString(k, null) == x.get("value").asString) else check(p.getBoolean(k, false) == x.get("value").asBoolean) } }
    private fun branches() = runBlocking(Dispatchers.IO) { JsonObject().apply { UiPreferencesRepository(context).lastChatBranches.first().forEach { (id, b) -> addProperty(id.toString(), b) } } }
    private fun persist(j: JsonObject) { val pending = File(context.filesDir, "character-export-draft-$run.pending"); FileOutputStream(pending).use { it.write(j.toString().toByteArray()); it.fd.sync() }; check(pending.renameTo(journalFile)) }
    private fun journal() = JsonParser.parseString(journalFile.readText()).asJsonObject.also { check(it.get("run").asString == run) }
    private fun digest(exclude: Long? = null) = runBlocking(Dispatchers.IO) { val d = MessageDigest.getInstance("SHA-256"); var c: CharacterEntity? = null; do { val page = db.characterDao().getExportPage(c?.let { if (it.pinnedAt > 0) 0 else 1 }, c?.pinnedAt, c?.favorite, c?.createdAt, c?.id, 128); page.filter { it.id != exclude }.forEach { d.update(Gson().toJson(it).toByteArray()); d.update(0.toByte()) }; c = page.lastOrNull() } while (page.size == 128); d.digest().joinToString("") { "%02x".format(it) } }
    private fun screenshot(name: String) { rule.waitForIdle(); inst.waitForIdleSync(); if (inst.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName) repeat(2) { val l = CountDownLatch(1); rule.runOnUiThread { rule.activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { l.countDown() }; rule.activity.window.decorView.invalidate() }; check(l.await(3, TimeUnit.SECONDS)) }; inst.uiAutomation.waitForIdle(300, 5000); val b = checkNotNull(inst.uiAutomation.takeScreenshot()); try { FileOutputStream(output.resolve("$name.png")).use { check(b.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { b.recycle() } }
    private fun hideKeyboard() = rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0) }
    private fun back() { inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); rule.waitForIdle() }
    private fun waitText(t: String) { rule.waitUntil(10_000) { rule.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitAppWindow() { val deadline = android.os.SystemClock.uptimeMillis() + 12_000; while (inst.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName) { check(android.os.SystemClock.uptimeMillis() < deadline) { "SAF did not return to app" }; android.os.SystemClock.sleep(50) }; inst.waitForIdleSync() }
    private fun node(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? { if (root == null) return null; if (root.isVisibleToUser && (root.text?.toString()?.equals(text, true) == true || root.contentDescription?.toString()?.equals(text, true) == true)) return root; for (i in 0 until root.childCount) node(root.getChild(i), text)?.let { return it }; return null }
    private fun clickDocument(text: String): Boolean { val end = android.os.SystemClock.uptimeMillis() + 10_000; while (android.os.SystemClock.uptimeMillis() < end) { val n = node(inst.uiAutomation.rootInActiveWindow, text) ?: if (text == "保存") node(inst.uiAutomation.rootInActiveWindow, "Save") else null; if (n != null) { var p: AccessibilityNodeInfo = n; while (!p.isClickable) p = p.parent ?: break; if (p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; if (text == "资料导出验收" && n.isVisibleToUser && !p.isClickable) return true }; if (text == "资料导出验收") (node(inst.uiAutomation.rootInActiveWindow, "Show roots") ?: node(inst.uiAutomation.rootInActiveWindow, "显示根目录"))?.performAction(AccessibilityNodeInfo.ACTION_CLICK); android.os.SystemClock.sleep(100) }; return false }
    private fun setDocumentText(text: String): Boolean { val end = android.os.SystemClock.uptimeMillis() + 10_000; while (android.os.SystemClock.uptimeMillis() < end) { fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? { if (n == null) return null; if (n.className?.toString() == "android.widget.EditText" && n.isVisibleToUser) return n; for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }; return null }; find(inst.uiAutomation.rootInActiveWindow)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })) return true }; android.os.SystemClock.sleep(100) }; return false }
    private fun callProvider(method: String, name: String = "", extra: Bundle? = null): Bundle? { val uri = android.net.Uri.parse("content://$authority"); val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION; if (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) != android.content.pm.PackageManager.PERMISSION_GRANTED) { context.startActivity(android.content.Intent().setClassName("com.mojing.app.test", "com.mojing.app.test.LibraryExportFixtureActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); val end = android.os.SystemClock.uptimeMillis() + 5_000; while (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) != android.content.pm.PackageManager.PERMISSION_GRANTED && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(20); check(context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) == android.content.pm.PackageManager.PERMISSION_GRANTED) }; return context.contentResolver.call(uri, method, name, extra) }
    private fun openSource() { rule.onNode(hasText("创作") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, Role.Tab)).performClick(); waitText("角色"); rule.onNodeWithText("角色").performClick(); rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }; rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(sourceName); hideKeyboard(); rule.waitUntil(10_000) { rule.onAllNodesWithText(sourceName).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }; rule.onAllNodesWithText(sourceName).filter(!hasSetTextAction()).onLast().performClick(); waitText("角色详情") }
    private fun recordImport(j: JsonObject, required: Boolean): Long? = runBlocking(Dispatchers.IO) { val page = db.characterDao().getLibraryNamePage(null, null, null, null, 20, sourceName); val owned = page.filter { it.id != j.get("sourceId")?.asLong && it.name.startsWith(sourceName) }.filter { db.characterProfileDao().getByCharacter(it.id)?.sourceFilename == "source.json" && db.characterProfileDao().getByCharacter(it.id)?.characterCardJson?.contains("draft-$marker") == true }; check(owned.size <= 1); val id = owned.singleOrNull()?.id; if (required) checkNotNull(id); if (id != null) { j.addProperty("importedId", id); persist(j) }; id }
    private fun assertDrafts(j: JsonObject) { check(digest(j.get("sourceId")?.asLong) == j.get("digest").asString); assertSnapshot(worldPrefs, j.getAsJsonObject("world")); assertSnapshot(characterPrefs, j.getAsJsonObject("character")); assertSnapshot(chatPrefs, j.getAsJsonObject("chat")); assertSnapshot(entryPrefs, j.getAsJsonObject("entry")); check(branches() == j.getAsJsonObject("branches")) }

    @Test fun invalidJsonRetryCancelSafExportReadAndImportDetail() {
        guard(); check(!journalFile.exists()); val j = JsonObject().apply { addProperty("run", run); addProperty("sourceName", sourceName); add("world", snapshot(worldPrefs)); add("character", snapshot(characterPrefs)); add("chat", snapshot(chatPrefs)); add("entry", snapshot(entryPrefs)); add("branches", branches()); addProperty("digest", digest()) }; persist(j)
        try {
            val sourceId = runBlocking(Dispatchers.IO) { db.withTransaction { val id = SaveCharacterBindingUseCase(db)(CharacterEntity(name = sourceName, personaPrompt = oldPersona, avatarImagePath = "", cardImagePath = "")); j.addProperty("sourceId", id); db.characterProfileDao().upsert(CharacterProfileEntity(characterId = id, sourceFilename = "source.json", rawPersonaText = oldPersona, characterCardJson = sourceProfileJson)); j.addProperty("sourceEntityRow", Gson().toJson(checkNotNull(db.characterDao().getById(id)))); j.addProperty("sourceProfileRow", Gson().toJson(checkNotNull(db.characterProfileDao().getByCharacter(id)))); persist(j); id } }
            j.addProperty("jsonAttempted", true); persist(j); callProvider("prepareMode", jsonName, Bundle().apply { putString("mode", "normal") }); rule.waitUntil(10_000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
            openSource(); rule.onNodeWithContentDescription("编辑角色").performClick(); waitText("编辑角色"); rule.onNodeWithText("导出与高级设置").performScrollTo().performClick(); rule.onNodeWithText("扩展设定").performScrollTo().performClick();
            var extension = rule.onNode(hasSetTextAction() and hasText(sourceProfileJson), useUnmergedTree = true); extension.performScrollTo().performTextReplacement("[]"); hideKeyboard(); rule.onNodeWithText("便携包").performScrollTo(); rule.onNodeWithText("JSON").performScrollTo().performClick(); rule.waitUntil(10_000) { rule.onAllNodes(hasText("扩展设定 JSON 须为对象", substring = true)).fetchSemanticsNodes().isNotEmpty() }; screenshot("01-invalid-json-error")
            extension = rule.onNode(hasSetTextAction() and hasText("[]"), useUnmergedTree = true); extension.performScrollTo().performTextReplacement(draftCardJson); hideKeyboard(); screenshot("02-draft-json-editor"); rule.onNodeWithText("JSON").performScrollTo().performClick(); check(clickDocument("资料导出验收")); check(setDocumentText(jsonName)); screenshot("03-saf-before-save"); back(); waitText("编辑角色"); rule.onNodeWithText("JSON").performScrollTo().performClick(); check(clickDocument("资料导出验收")); check(setDocumentText(jsonName)); check(clickDocument("保存")); waitAppWindow(); rule.waitUntil(12_000) { rule.onAllNodes(hasText("已保存", substring = true)).fetchSemanticsNodes().isNotEmpty() }; j.addProperty("jsonCreated", true); persist(j)
            val bytes = context.contentResolver.openInputStream(android.provider.DocumentsContract.buildDocumentUri(authority, jsonName))!!.use { it.readBytes() }; val exported = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject; check(exported.get("persona_prompt").asString == oldPersona); val profile = exported.getAsJsonObject("profile"); check(profile.get("raw_persona_text").asString == oldPersona); val card = profile.getAsJsonObject("character_card_json"); check(card.getAsJsonObject("data").get("description").asString == draftPersona); check(card.getAsJsonObject("data").get("creator_notes").asString == "draft-notes-$marker"); val draftExtensions = card.getAsJsonObject("data").getAsJsonObject("extensions").getAsJsonObject("draft"); check(draftExtensions.get("keep").asString == "draft-$marker"); check(draftExtensions.get("optional").isJsonNull); check(runBlocking(Dispatchers.IO) { Gson().toJson(checkNotNull(db.characterDao().getById(sourceId))) == j.get("sourceEntityRow").asString && Gson().toJson(checkNotNull(db.characterProfileDao().getByCharacter(sourceId))) == j.get("sourceProfileRow").asString }); FileOutputStream(output.resolve(jsonName)).use { it.write(bytes); it.fd.sync() }
            callProvider("prepareMode", pngName, Bundle().apply { putString("mode", "normal") }); j.addProperty("pngAttempted", true); persist(j); rule.onNodeWithText("导出 PNG 形象卡").performScrollTo().performClick(); check(clickDocument("资料导出验收")); check(setDocumentText(pngName)); screenshot("04-png-saf-before-save"); check(clickDocument("保存")); waitAppWindow(); rule.waitUntil(12_000) { rule.onAllNodes(hasText("已保存", substring = true)).fetchSemanticsNodes().isNotEmpty() }; j.addProperty("pngCreated", true); persist(j); val pngBytes = context.contentResolver.openInputStream(android.provider.DocumentsContract.buildDocumentUri(authority, pngName))!!.use { it.readBytes() }; check(CharacterCardPngCodec.isPng(pngBytes)); val pngData = checkNotNull(CharacterCardPngCodec.readCharaCardJsonRoot(pngBytes)).getAsJsonObject("data"); check(pngData.get("description").asString == oldPersona); check(pngData.get("scenario").asString.isEmpty() && pngData.get("personality").asString.isEmpty()); check(pngData.get("creator_notes").asString == "draft-notes-$marker"); val pngExtensions = pngData.getAsJsonObject("extensions").getAsJsonObject("draft"); check(pngExtensions.get("keep").asString == "draft-$marker"); check(pngExtensions.get("optional").isJsonNull); FileOutputStream(output.resolve(pngName)).use { it.write(pngBytes); it.fd.sync() }
            back(); waitText("保存角色修改？"); rule.onNodeWithText("放弃修改").performClick(); waitText("角色详情"); back(); waitText("角色"); rule.onNodeWithContentDescription("更多").performClick(); rule.onNodeWithText("导入（便携包 / JSON / TXT / Word / PNG）").performClick(); check(clickDocument("资料导出验收")); check(clickDocument(jsonName)); waitAppWindow(); rule.waitUntil(15_000) { recordImport(j, false) != null }; val importedId = checkNotNull(recordImport(j, true)); j.addProperty("importedId", importedId); persist(j); val imported = runBlocking(Dispatchers.IO) { checkNotNull(db.characterDao().getById(importedId)) }; val importedProfile = runBlocking(Dispatchers.IO) { checkNotNull(db.characterProfileDao().getByCharacter(importedId)) }; check(imported.personaPrompt == oldPersona); check(importedProfile.rawPersonaText == oldPersona); val importedCard = JsonParser.parseString(importedProfile.characterCardJson).asJsonObject.getAsJsonObject("data"); check(importedCard.get("description").asString == draftPersona); check(importedCard.get("creator_notes").asString == "draft-notes-$marker"); val importedExtensions = importedCard.getAsJsonObject("extensions").getAsJsonObject("draft"); check(importedExtensions.get("keep").asString == "draft-$marker"); check(importedExtensions.get("optional").isJsonNull); rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(imported.name); hideKeyboard(); rule.waitUntil(10_000) { rule.onAllNodesWithText(imported.name).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }; rule.onAllNodesWithText(imported.name).filter(!hasSetTextAction()).onLast().performClick(); waitText("角色详情"); rule.onNodeWithText(oldPersona).assertExists(); screenshot("05-imported-detail"); j.addProperty("phase", "verified"); persist(j); output.resolve("verified.txt").writeText("invalidObjectRejected=true\njsonRetrySafSaved=true\nsourceEntityAndProfileUnchanged=true\ndraftNotesAndExtensionsRoundTrip=true\nportableJsonImportedNewUuid=true\npngDraftNullRoundTrip=true\n")
        } catch (t: Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString()); runCatching { screenshot("failure-window") }; throw t }
    }

    @Test fun rollbackOnlyOwnedCharacterExportDraftRows() {
        guard(); val j = journal(); recordImport(j, false); val sourceId = j.get("sourceId")?.asLong ?: 0L; val importedId = j.get("importedId")?.asLong ?: 0L
        runBlocking(Dispatchers.IO) { db.withTransaction { if (importedId > 0) db.characterDao().getById(importedId)?.let { c -> check(c.name.startsWith(sourceName)); check(db.characterProfileDao().getByCharacter(importedId)?.sourceFilename == "source.json"); db.characterDao().delete(importedId) }; if (sourceId > 0) db.characterDao().getById(sourceId)?.let { c -> check(c.name == sourceName && Gson().toJson(c) == j.get("sourceEntityRow").asString); val p = checkNotNull(db.characterProfileDao().getByCharacter(sourceId)); check(Gson().toJson(p) == j.get("sourceProfileRow").asString); check(p.characterCardJson == sourceProfileJson); db.characterDao().delete(sourceId) }; check(importedId <= 0 || db.characterDao().getById(importedId) == null); check(sourceId <= 0 || db.characterDao().getById(sourceId) == null) } }
        listOf(sourceId, importedId).filter { it > 0 }.forEach { id -> check(!j.getAsJsonObject("character").has("character_$id")); check(characterPrefs.edit().remove("character_$id").commit()) }
        if (j.get("jsonAttempted")?.asBoolean == true) { callProvider("deleteOwned", jsonName) }; if (j.get("pngAttempted")?.asBoolean == true) { callProvider("deleteOwned", pngName) }; assertDrafts(j); j.addProperty("phase", "rolled-back"); persist(j); output.resolve("rollback.txt").writeText("onlyOwnedSourceImportedJsonPngDraftsRemoved=true\notherDigestFourDraftGroupsBranchesExact=true\n")
    }
}

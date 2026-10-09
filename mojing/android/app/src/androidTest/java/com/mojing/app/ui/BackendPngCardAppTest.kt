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
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.StoryOpeningInputDraftStore
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
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

/** Backend route-produced PNG through real MainActivity SAF/profile/detail/export. UUID-only rollback. */
class BackendPngCardAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val run get() = args.getString("backendPngCardRun").orEmpty().also { UUID.fromString(it) }
    private val name get() = "卡JSON角色${run.take(8)}"
    private val marker get() = "JSON_CARD_$run"
    private val sourceName get() = "mojing-png-$run.png"
    private val pngName get() = "mojing-png-${UUID.nameUUIDFromBytes("export-$run".toByteArray())}.png"
    private val journalFile get() = File(context.filesDir, "backend-png-card-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "backend-png-card-$run").apply { mkdirs() }
    private val authority = "com.mojing.app.test.library.documents"
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val entryPrefs get() = context.getSharedPreferences("entry_edit_drafts_v1", 0)
    private val sourceBytes get() = File(checkNotNull(args.getString("backendPngFixture"))).readBytes()
    private val rootJson get() = checkNotNull(CharacterCardPngCodec.readCharaCardJsonRoot(sourceBytes)).toString()


    private fun guard() { assumeTrue(args.getString("backendPngCardCapture") == "true"); assumeTrue(Build.FINGERPRINT.contains("generic", true) || Build.MODEL.startsWith("sdk_gphone", true)); check(RetainedChatSessions.running.value.isEmpty()); check(StoryOpeningInputDraftStore(context).loadGeneration() == null) }
    private fun snapshot(p: SharedPreferences) = JsonObject().apply { p.all.forEach { (k, v) -> add(k, JsonObject().apply { when (v) { is String -> { addProperty("type", "string"); addProperty("value", v) }; is Boolean -> { addProperty("type", "boolean"); addProperty("value", v) }; else -> error("unexpected draft type") } }) } }
    private fun assertSnapshot(p: SharedPreferences, j: JsonObject) { check(p.all.keys == j.keySet()); j.entrySet().forEach { (k, v) -> val x = v.asJsonObject; if (x.get("type").asString == "string") check(p.getString(k, null) == x.get("value").asString) else check(p.getBoolean(k, false) == x.get("value").asBoolean) } }
    private fun branches() = runBlocking(Dispatchers.IO) { JsonObject().apply { UiPreferencesRepository(context).lastChatBranches.first().forEach { (id, value) -> addProperty(id.toString(), value) } } }
    private fun persist(j: JsonObject) { val pending = File(context.filesDir, "backend-png-card-$run.pending"); FileOutputStream(pending).use { it.write(j.toString().toByteArray()); it.fd.sync() }; check(pending.renameTo(journalFile)) }
    private fun journal() = JsonParser.parseString(journalFile.readText()).asJsonObject.also { check(it.get("run").asString == run) }
    private fun digest(exclude: Long? = null) = runBlocking(Dispatchers.IO) { val d = MessageDigest.getInstance("SHA-256"); var cursor: CharacterEntity? = null; do { val page = db.characterDao().getExportPage(cursor?.let { if (it.pinnedAt > 0) 0 else 1 }, cursor?.pinnedAt, cursor?.favorite, cursor?.createdAt, cursor?.id, 128); page.filter { it.id != exclude }.forEach { d.update(Gson().toJson(it).toByteArray()); d.update(0.toByte()) }; cursor = page.lastOrNull() } while (page.size == 128); d.digest().joinToString("") { "%02x".format(it) } }
    private fun screenshot(label: String) { rule.waitForIdle(); inst.waitForIdleSync(); if (inst.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName) repeat(2) { val latch = CountDownLatch(1); rule.runOnUiThread { rule.activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { latch.countDown() }; rule.activity.window.decorView.invalidate() }; check(latch.await(3, TimeUnit.SECONDS)) }; inst.uiAutomation.waitForIdle(300, 5000); val bitmap = checkNotNull(inst.uiAutomation.takeScreenshot()); try { FileOutputStream(output.resolve("$label.png")).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() } }
    private fun hideKeyboard() = rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0) }
    private fun back() { inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); rule.waitForIdle() }
    private fun cancelPickerAndWaitForApp() {
        val end = android.os.SystemClock.uptimeMillis() + 10_000
        while (inst.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") != true) {
            check(android.os.SystemClock.uptimeMillis() < end); android.os.SystemClock.sleep(50)
        }
        repeat(4) {
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            inst.uiAutomation.waitForIdle(300, 3000)
            if (inst.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName) { inst.waitForIdleSync(); return }
        }
        error("SAF cancel did not return to app")
    }
    private fun waitText(value: String) { rule.waitUntil(10_000) { rule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() } }
    private fun node(root: AccessibilityNodeInfo?, value: String): AccessibilityNodeInfo? { if (root == null) return null; if (root.isVisibleToUser && (root.text?.toString()?.equals(value, true) == true || root.contentDescription?.toString()?.equals(value, true) == true)) return root; for (i in 0 until root.childCount) node(root.getChild(i), value)?.let { return it }; return null }
    private fun clickDocument(value: String): Boolean { val end = android.os.SystemClock.uptimeMillis() + 10_000; while (android.os.SystemClock.uptimeMillis() < end) { val n = node(inst.uiAutomation.rootInActiveWindow, value) ?: if (value == "保存") node(inst.uiAutomation.rootInActiveWindow, "Save") else null; if (n != null) { var parent: AccessibilityNodeInfo = n; while (!parent.isClickable) parent = parent.parent ?: break; if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; if (value == "资料导出验收" && n.isVisibleToUser && !parent.isClickable) return true }; if (value == "资料导出验收") (node(inst.uiAutomation.rootInActiveWindow, "Show roots") ?: node(inst.uiAutomation.rootInActiveWindow, "显示根目录"))?.performAction(AccessibilityNodeInfo.ACTION_CLICK); android.os.SystemClock.sleep(100) }; return false }
    private fun setDocumentText(value: String): Boolean { val end = android.os.SystemClock.uptimeMillis() + 10_000; while (android.os.SystemClock.uptimeMillis() < end) { fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? { if (n == null) return null; if (n.className?.toString() == "android.widget.EditText" && n.isVisibleToUser) return n; for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }; return null }; find(inst.uiAutomation.rootInActiveWindow)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })) return true }; android.os.SystemClock.sleep(100) }; return false }
    private fun grantAndCall(method: String, name: String = "", extras: Bundle? = null): Bundle? { val uri = android.net.Uri.parse("content://$authority"); val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION; if (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) != android.content.pm.PackageManager.PERMISSION_GRANTED) { context.startActivity(android.content.Intent().setClassName("com.mojing.app.test", "com.mojing.app.test.LibraryExportFixtureActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); val end = android.os.SystemClock.uptimeMillis() + 5_000; while (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) != android.content.pm.PackageManager.PERMISSION_GRANTED && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(20); check(context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) == android.content.pm.PackageManager.PERMISSION_GRANTED) }; return context.contentResolver.call(uri, method, name, extras) }
    private fun seedSource() { grantAndCall("prepareRead", sourceName, Bundle().apply { putString("mode", "normal"); putByteArray("bytes", sourceBytes) }) }
    private fun openLibrary() { rule.onNode(hasText("创作") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, Role.Tab)).performClick(); waitText("角色"); rule.onNodeWithText("角色").performClick(); waitText("角色") }
    private fun importMenu() { rule.onNodeWithContentDescription("更多").performClick(); rule.onNodeWithText("导入（便携包 / JSON / TXT / Word / PNG）").performClick() }
    private fun findOwned(j: JsonObject, required: Boolean): Long? = runBlocking(Dispatchers.IO) { val rows = db.characterDao().getLibraryNamePage(null, null, null, null, 40, name); val owned = rows.filter { it.name == name }.filter { val profile = db.characterProfileDao().getByCharacter(it.id); profile?.sourceFilename == sourceName && profile.characterCardJson.contains(marker) && profile.characterCardJson.contains("unknown-$marker") }; check(owned.size <= 1); val id = owned.singleOrNull()?.id; if (required) checkNotNull(id); if (id != null) { j.addProperty("importedId", id); persist(j) }; id }
    private fun assertDrafts(j: JsonObject) { check(digest(j.get("importedId")?.asLong) == j.get("digest").asString); assertSnapshot(worldPrefs, j.getAsJsonObject("world")); assertSnapshot(characterPrefs, j.getAsJsonObject("character")); assertSnapshot(chatPrefs, j.getAsJsonObject("chat")); assertSnapshot(entryPrefs, j.getAsJsonObject("entry")); check(branches() == j.getAsJsonObject("branches")) }
    private fun deleteOwnedFile(name: String) { grantAndCall("deleteOwned", name) }

    @Test fun importBackendPngCancelReopenDetailAndPngRoundTrip() {
        guard(); check(!journalFile.exists()); val j = JsonObject().apply { addProperty("run", run); addProperty("sourceName", sourceName); add("world", snapshot(worldPrefs)); add("character", snapshot(characterPrefs)); add("chat", snapshot(chatPrefs)); add("entry", snapshot(entryPrefs)); add("branches", branches()); addProperty("digest", digest()) }; persist(j)
        try {
            grantAndCall("prepareMode", sourceName, Bundle().apply { putString("mode", "normal") }); j.addProperty("jsonAttempted", true); persist(j); seedSource(); j.addProperty("sourceSeeded", true); persist(j); rule.waitUntil(10_000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }; openLibrary(); importMenu(); cancelPickerAndWaitForApp(); waitText("角色"); importMenu(); check(clickDocument("资料导出验收")); check(clickDocument(sourceName));
            rule.waitUntil(15_000) { findOwned(j, false) != null }; val importedId = checkNotNull(findOwned(j, true)); val imported = runBlocking(Dispatchers.IO) { checkNotNull(db.characterDao().getById(importedId)) }; val profile = runBlocking(Dispatchers.IO) { checkNotNull(db.characterProfileDao().getByCharacter(importedId)) }; check(profile.sourceFilename == sourceName); check(profile.rawPersonaText.contains("描述-$marker")); check(imported.personaPrompt.contains("场景-$marker") && imported.personaPrompt.contains("开场-$marker")); val savedRoot = JsonParser.parseString(profile.characterCardJson).asJsonObject; check(savedRoot == CharacterCardPngCodec.readCharaCardJsonRoot(sourceBytes)); val card = savedRoot.getAsJsonObject("data"); check(card.get("creator_notes").asString == "notes-$marker"); check(card.getAsJsonObject("character_book").get("name").asString == "book-$marker"); check(card.getAsJsonObject("extensions").getAsJsonObject("unknown").get("keep").asString == "unknown-$marker"); check(card.getAsJsonObject("extensions").get("nullable").isJsonNull); rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(name); hideKeyboard(); rule.waitUntil(10_000) { rule.onAllNodesWithText(name).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }; rule.onAllNodesWithText(name).filter(!hasSetTextAction()).onLast().performClick(); waitText("角色详情"); rule.onNodeWithText(imported.personaPrompt).assertExists(); check(!imported.personaPrompt.contains("notes-$marker")); screenshot("01-imported-detail")
            rule.onNodeWithContentDescription("编辑角色").performClick(); waitText("编辑角色"); rule.onNodeWithText("导出与高级设置").performScrollTo().performClick(); rule.onNodeWithText("导出 PNG 形象卡").performScrollTo(); screenshot("02-editor-export"); j.addProperty("pngAttempted", true); persist(j); rule.onNodeWithText("导出 PNG 形象卡").performClick(); check(clickDocument("资料导出验收")); check(setDocumentText(pngName)); screenshot("03-png-saf"); check(clickDocument("保存")); rule.waitUntil(12_000) { rule.onAllNodes(hasText("已保存", substring = true)).fetchSemanticsNodes().isNotEmpty() }; j.addProperty("pngCreated", true); persist(j); val png = context.contentResolver.openInputStream(android.provider.DocumentsContract.buildDocumentUri(authority, pngName))!!.use { it.readBytes() }; FileOutputStream(output.resolve(pngName)).use { it.write(png); it.fd.sync() }; output.resolve(sourceName).writeBytes(sourceBytes); val exportedRoot = checkNotNull(CharacterCardPngCodec.readCharaCardJsonRoot(png)); check(exportedRoot == CharacterCardPngCodec.readCharaCardJsonRoot(sourceBytes)); val pngData = exportedRoot.getAsJsonObject("data"); check(pngData.get("first_mes").asString == "开场-$marker" && pngData.get("scenario").asString == "场景-$marker"); check(pngData.get("creator_notes").asString == "notes-$marker"); check(pngData.getAsJsonObject("character_book").get("name").asString == "book-$marker"); check(pngData.getAsJsonObject("extensions").getAsJsonObject("unknown").get("keep").asString == "unknown-$marker"); check(pngData.getAsJsonObject("extensions").get("nullable").isJsonNull); back(); waitText("角色详情"); screenshot("04-png-detail"); j.addProperty("phase", "verified"); persist(j); output.resolve("verified.txt").writeText("backendRoutePngSafCancelReopen=true\nsourceFilenameBasenamePreserved=true\nfullProfileNotesBookUnknownNullPreserved=true\npngFirstMesScenarioRoundTrip=true\nnoModelRequestInTestFlow=true\n")
        } catch (t: Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString()); runCatching { screenshot("failure-window") }; throw t }
    }

    @Test fun rollbackOnlyOwnedBackendPngRows() {
        guard(); val j = journal(); val importedId = findOwned(j, false) ?: j.get("importedId")?.asLong ?: 0L; if (importedId > 0) runBlocking(Dispatchers.IO) { check(db.sessionDao().getRecentForCharacter(importedId, 128).isEmpty()) }; if (importedId > 0) runBlocking(Dispatchers.IO) { db.characterDao().getById(importedId)?.let { c -> val p = checkNotNull(db.characterProfileDao().getByCharacter(importedId)); check(c.name == name && p.sourceFilename == sourceName && p.characterCardJson.contains(marker)); db.characterDao().delete(importedId) } }; if (j.get("pngAttempted")?.asBoolean == true) deleteOwnedFile(pngName); if (j.get("jsonAttempted")?.asBoolean == true) deleteOwnedFile(j.get("sourceName").asString); check(runBlocking(Dispatchers.IO) { db.characterDao().getById(importedId) == null }); assertDrafts(j); j.addProperty("phase", "rolled-back"); persist(j); output.resolve("rollback.txt").writeText("onlyOwnedJsonCardAndPngRowsRemoved=true\notherDigestFourDraftGroupsBranchesExact=true\n")
    }
}

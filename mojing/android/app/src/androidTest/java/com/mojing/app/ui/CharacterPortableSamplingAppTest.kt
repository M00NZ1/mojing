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
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.StoryOpeningInputDraftStore
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.CharacterProfileEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import com.mojing.app.domain.util.CharacterPortableCodec
import com.mojing.app.domain.util.DocxTextExtractor
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

/** MainActivity SAF evidence for all five portable sampling values. */
class CharacterPortableSamplingAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val run get() = args.getString("characterPortableSamplingRun").orEmpty().also { UUID.fromString(it) }
    private val name get() = "采样往返角色-${run.take(8)}"
    private val marker get() = "PORTABLE_SAMPLING_$run"
    private val sourceName get() = "sampling-source-$run.json"
    private val jsonName get() = "mojing-sampling-$run.json"
    private val txtName get() = "mojing-sampling-$run.txt"
    private val docxName get() = "mojing-sampling-$run.docx"
    private val journalFile get() = File(context.filesDir, "character-portable-sampling-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "character-portable-sampling-$run").apply { mkdirs() }
    private val authority = "com.mojing.app.test.library.documents"
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val entryPrefs get() = context.getSharedPreferences("entry_edit_drafts_v1", 0)

    private fun guard() {
        assumeTrue(args.getString("characterPortableSamplingCapture") == "true")
        assumeTrue(Build.FINGERPRINT.contains("generic", true) || Build.MODEL.startsWith("sdk_gphone", true))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }

    private fun snapshot(p: SharedPreferences) = JsonObject().apply {
        p.all.forEach { (key, value) -> add(key, JsonObject().apply {
            when (value) {
                is String -> { addProperty("type", "string"); addProperty("value", value) }
                is Boolean -> { addProperty("type", "boolean"); addProperty("value", value) }
                else -> error("unexpected draft type")
            }
        }) }
    }

    private fun assertSnapshot(p: SharedPreferences, expected: JsonObject) {
        check(p.all.keys == expected.keySet())
        expected.entrySet().forEach { (key, value) ->
            val item = value.asJsonObject
            if (item.get("type").asString == "string") check(p.getString(key, null) == item.get("value").asString)
            else check(p.getBoolean(key, false) == item.get("value").asBoolean)
        }
    }

    private fun branches() = runBlocking(Dispatchers.IO) {
        JsonObject().apply { UiPreferencesRepository(context).lastChatBranches.first().forEach { (id, value) -> addProperty(id.toString(), value) } }
    }

    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "character-portable-sampling-$run.pending")
        FileOutputStream(pending).use { it.write(j.toString().toByteArray()); it.fd.sync() }
        check(pending.renameTo(journalFile))
    }

    private fun journal() = JsonParser.parseString(journalFile.readText()).asJsonObject.also { check(it.get("run").asString == run) }

    private fun digest(exclude: Set<Long> = emptySet()) = runBlocking(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        var cursor: CharacterEntity? = null
        do {
            val page = db.characterDao().getExportPage(cursor?.let { if (it.pinnedAt > 0) 0 else 1 }, cursor?.pinnedAt, cursor?.favorite, cursor?.createdAt, cursor?.id, 128)
            page.filter { it.id !in exclude }.forEach { digest.update(Gson().toJson(it).toByteArray()); digest.update(0.toByte()) }
            cursor = page.lastOrNull()
        } while (page.size == 128)
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun screenshot(label: String) {
        rule.waitForIdle(); inst.waitForIdleSync()
        if (inst.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName) repeat(2) {
            val latch = CountDownLatch(1)
            rule.runOnUiThread { rule.activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { latch.countDown() }; rule.activity.window.decorView.invalidate() }
            check(latch.await(3, TimeUnit.SECONDS))
        }
        inst.uiAutomation.waitForIdle(300, 5000)
        val bitmap = checkNotNull(inst.uiAutomation.takeScreenshot())
        try { FileOutputStream(output.resolve("$label.png")).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
    }

    private fun hideKeyboard() = rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0) }
    private fun back() { inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); rule.waitForIdle() }
    private fun waitText(value: String) { rule.waitUntil(10_000) { rule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() } }

    private fun waitAppWindow() {
        val end = android.os.SystemClock.uptimeMillis() + 12_000
        while (inst.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName) { check(android.os.SystemClock.uptimeMillis() < end); android.os.SystemClock.sleep(50) }
        inst.waitForIdleSync()
    }

    private fun cancelPickerAndWaitForApp() {
        val end = android.os.SystemClock.uptimeMillis() + 10_000
        while (inst.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") != true) { check(android.os.SystemClock.uptimeMillis() < end); android.os.SystemClock.sleep(50) }
        repeat(4) { inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); inst.uiAutomation.waitForIdle(300, 3000); if (inst.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName) { inst.waitForIdleSync(); return } }
        error("SAF cancel did not return to app")
    }

    private fun node(root: AccessibilityNodeInfo?, value: String): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isVisibleToUser && (root.text?.toString()?.equals(value, true) == true || root.contentDescription?.toString()?.equals(value, true) == true)) return root
        for (i in 0 until root.childCount) node(root.getChild(i), value)?.let { return it }
        return null
    }

    private fun clickDocument(value: String): Boolean {
        val end = android.os.SystemClock.uptimeMillis() + 10_000
        while (android.os.SystemClock.uptimeMillis() < end) {
            val found = node(inst.uiAutomation.rootInActiveWindow, value) ?: if (value == "保存") node(inst.uiAutomation.rootInActiveWindow, "Save") else null
            if (found != null) {
                var parent: AccessibilityNodeInfo = found
                while (!parent.isClickable) parent = parent.parent ?: break
                if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                if (value == "资料导出验收" && found.isVisibleToUser && !parent.isClickable) return true
            }
            if (value == "资料导出验收") (node(inst.uiAutomation.rootInActiveWindow, "Show roots") ?: node(inst.uiAutomation.rootInActiveWindow, "显示根目录"))?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    private fun setDocumentText(value: String): Boolean {
        val end = android.os.SystemClock.uptimeMillis() + 10_000
        while (android.os.SystemClock.uptimeMillis() < end) {
            fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (root == null) return null
                if (root.className?.toString() == "android.widget.EditText" && root.isVisibleToUser) return root
                for (i in 0 until root.childCount) find(root.getChild(i))?.let { return it }
                return null
            }
            find(inst.uiAutomation.rootInActiveWindow)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })) return true }
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    private fun callProvider(method: String, fileName: String = "", extras: Bundle? = null): Bundle? {
        val uri = android.net.Uri.parse("content://$authority")
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            context.startActivity(android.content.Intent().setClassName("com.mojing.app.test", "com.mojing.app.test.LibraryExportFixtureActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val end = android.os.SystemClock.uptimeMillis() + 5_000
            while (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) != android.content.pm.PackageManager.PERMISSION_GRANTED && android.os.SystemClock.uptimeMillis() < end) android.os.SystemClock.sleep(20)
            check(context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        return context.contentResolver.call(uri, method, fileName, extras)
    }

    private fun openSource() {
        rule.onNode(hasText("创作") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, Role.Tab)).performClick()
        waitText("角色"); rule.onNodeWithText("角色").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(name); hideKeyboard()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(name).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(name).filter(!hasSetTextAction()).onLast().performClick(); waitText("角色详情")
    }

    private fun openImport() {
        rule.onNodeWithContentDescription("更多").performClick()
        rule.onNodeWithText("导入（便携包 / JSON / TXT / Word / PNG）").performClick()
    }

    private fun sourceRow(): Pair<Long, CharacterProfileEntity> = runBlocking(Dispatchers.IO) {
        db.characterDao().getLibraryNamePage(null, null, null, null, 20, name).single { row ->
            db.characterProfileDao().getByCharacter(row.id)?.characterCardJson?.contains(marker) == true
        }.let { row -> row.id to checkNotNull(db.characterProfileDao().getByCharacter(row.id)) }
    }

    private fun importedRows() = runBlocking(Dispatchers.IO) {
        db.characterDao().getLibraryNamePage(null, null, null, null, 80, name).filter { row ->
            db.characterProfileDao().getByCharacter(row.id)?.let { profile ->
                row.name != name && profile.sourceFilename == sourceName && profile.characterCardJson.contains(marker)
            } == true
        }
    }

    private fun ownedRowsSnapshot() = importedRows().associateBy { it.id }

    private fun findOwnedSourceId() = runBlocking(Dispatchers.IO) {
        db.characterDao().getLibraryNamePage(null, null, null, null, 40, name).singleOrNull { row ->
            db.characterProfileDao().getByCharacter(row.id)?.let { profile ->
                row.name == name && profile.sourceFilename == sourceName && profile.characterCardJson.contains(marker)
            } == true
        }?.id
    }

    private fun assertSampling(root: JsonObject) {
        check(root.get("temperature").asFloat == 0.0f)
        check(root.get("top_p").asFloat == 0.37f)
        check(root.get("frequency_penalty").asFloat == -1.5f)
        check(root.get("presence_penalty").asFloat == 1.25f)
        check(root.get("max_tokens").asInt == 4097)
    }

    private fun readPortable(fileName: String): JsonObject {
        val bytes = context.contentResolver.openInputStream(android.provider.DocumentsContract.buildDocumentUri(authority, fileName))!!.use { it.readBytes() }
        FileOutputStream(output.resolve(fileName)).use { it.write(bytes); it.fd.sync() }
        val text = when {
            fileName.endsWith(".json") -> String(bytes, Charsets.UTF_8)
            fileName.endsWith(".txt") -> String(bytes, Charsets.UTF_8)
            else -> checkNotNull(DocxTextExtractor.tryExtractPlainText(bytes))
        }
        val root = if (fileName.endsWith(".json")) JsonParser.parseString(text).asJsonObject else CharacterPortableCodec.parseTxt(text)
        assertSampling(root); check(root.get("name").asString == name); check(root.get("persona_prompt").asString.contains(marker)); return root
    }

    private fun exportFormat(label: String, fileName: String, j: JsonObject) {
        j.addProperty("attempted_$label", true); persist(j)
        rule.onNodeWithText(label).performScrollTo().performClick(); cancelPickerAndWaitForApp()
        rule.onNodeWithText(label).performScrollTo().performClick(); check(clickDocument("资料导出验收")); check(setDocumentText(fileName)); if (label == "JSON") screenshot("json-saf"); check(clickDocument("保存")); waitAppWindow(); waitText("编辑角色")
        j.addProperty("created_$label", true); persist(j); readPortable(fileName)
    }

    private fun importFormat(fileName: String, j: JsonObject) {
        val before = ownedRowsSnapshot().keys; j.addProperty("import_attempted_$fileName", true); persist(j); openImport(); cancelPickerAndWaitForApp(); openImport(); check(clickDocument("资料导出验收")); check(clickDocument(fileName)); waitAppWindow()
        rule.waitUntil(15_000) { ownedRowsSnapshot().keys.any { it !in before } }
        val imported = checkNotNull(ownedRowsSnapshot().keys.firstOrNull { it !in before }); j.addProperty("imported_$fileName", imported); persist(j)
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(ownedRowsSnapshot().getValue(imported).name); hideKeyboard(); rule.waitUntil(10_000) { rule.onAllNodesWithText(ownedRowsSnapshot().getValue(imported).name).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }; rule.onAllNodesWithText(ownedRowsSnapshot().getValue(imported).name).filter(!hasSetTextAction()).onLast().performClick(); waitText("角色详情"); screenshot("imported-${fileName.substringAfterLast('.')}"); back(); waitText("角色")
    }

    @Test
    fun portableSamplingSafAllFormatsAndCancelRetry() {
        guard(); check(!journalFile.exists())
        val j = JsonObject().apply { addProperty("run", run); addProperty("sourceName", sourceName); add("world", snapshot(worldPrefs)); add("character", snapshot(characterPrefs)); add("chat", snapshot(chatPrefs)); add("entry", snapshot(entryPrefs)); add("branches", branches()); addProperty("digest", digest()) }
        persist(j)
        try {
            val sourceId = runBlocking(Dispatchers.IO) { db.withTransaction {
                val id = SaveCharacterBindingUseCase(db)(CharacterEntity(name = name, personaPrompt = "源人设-$marker", temperature = 0.0f, topP = 0.37f, frequencyPenalty = -1.5f, presencePenalty = 1.25f, maxTokens = 4097))
                db.characterProfileDao().upsert(CharacterProfileEntity(characterId = id, sourceFilename = sourceName, rawPersonaText = "源人设-$marker", characterCardJson = "{\"marker\":\"$marker\"}")); id
            } }
            j.addProperty("sourceId", sourceId); j.addProperty("sourceEntity", Gson().toJson(runBlocking(Dispatchers.IO) { db.characterDao().getById(sourceId) })); j.addProperty("sourceProfile", Gson().toJson(runBlocking(Dispatchers.IO) { db.characterProfileDao().getByCharacter(sourceId) })); persist(j)
            rule.waitUntil(10_000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }; openSource(); rule.onNodeWithContentDescription("编辑角色").performClick(); waitText("编辑角色"); rule.onNodeWithText("导出与高级设置").performScrollTo().performClick()
            listOf("0.0", "0.37", "-1.5", "1.25", "4097").forEach { rule.onNodeWithText(it, substring = false).performScrollTo().assertExists() }; screenshot("editor-sampling")
            exportFormat("JSON", jsonName, j); exportFormat("TXT", txtName, j); exportFormat("DOCX", docxName, j)
            val sourceBeforeLeave = runBlocking(Dispatchers.IO) { Gson().toJson(db.characterDao().getById(sourceId)) to Gson().toJson(db.characterProfileDao().getByCharacter(sourceId)) }; check(sourceBeforeLeave.first == j.get("sourceEntity").asString && sourceBeforeLeave.second == j.get("sourceProfile").asString)
            back(); waitText("角色详情"); back(); waitText("角色")
            importFormat(jsonName, j); importFormat(txtName, j); importFormat(docxName, j)
            val rows = importedRows(); check(rows.size == 3); val fullRows = runBlocking(Dispatchers.IO) { rows.map { checkNotNull(db.characterDao().getById(it.id)) } }; fullRows.forEach { row -> check(row.temperature == 0.0f && row.topP == 0.37f && row.frequencyPenalty == -1.5f && row.presencePenalty == 1.25f && row.maxTokens == 4097) }; check(fullRows.map { it.id }.toSet().size == 3)
            j.addProperty("phase", "verified"); persist(j)
            assertSnapshot(worldPrefs, j.getAsJsonObject("world")); assertSnapshot(characterPrefs, j.getAsJsonObject("character")); assertSnapshot(chatPrefs, j.getAsJsonObject("chat")); assertSnapshot(entryPrefs, j.getAsJsonObject("entry")); check(branches() == j.getAsJsonObject("branches")); check(digest(setOf(sourceId) + rows.map { it.id }.toSet()) == j.get("digest").asString)
            output.resolve("verified.txt").writeText("allFiveSamplingValuesJsonTxtDocx=true\nallThreeImportsUnique=true\ncancelRetryEachSaf=true\nsourceAndDraftsPreserved=true\nnoModelRequestInFlow=true\n")
        } catch (t: Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString()); runCatching { screenshot("failure-window") }; throw t }
    }

    @Test
    fun rollbackOnlyOwnedPortableSamplingRows() {
        guard(); val j = journal(); val sourceId = j.get("sourceId")?.asLong?.takeIf { it > 0 } ?: findOwnedSourceId() ?: 0L; val owned = importedRows().map { it.id }.toSet()
        runBlocking(Dispatchers.IO) { owned.forEach { id -> check(db.sessionDao().getRecentForCharacter(id, 128).isEmpty()); db.characterDao().getById(id)?.let { row -> val profile = checkNotNull(db.characterProfileDao().getByCharacter(id)); check(row.name != name && profile.sourceFilename == sourceName && profile.characterCardJson.contains(marker)); db.characterDao().delete(id) } }; if (sourceId > 0) { check(db.sessionDao().getRecentForCharacter(sourceId, 128).isEmpty()); db.characterDao().getById(sourceId)?.let { row -> val profile = checkNotNull(db.characterProfileDao().getByCharacter(sourceId)); check(row.name == name && profile.sourceFilename == sourceName && profile.characterCardJson.contains(marker)); j.get("sourceEntity")?.let { check(Gson().toJson(row) == it.asString) }; j.get("sourceProfile")?.let { check(Gson().toJson(profile) == it.asString) }; db.characterDao().delete(sourceId) } } }
        listOf("JSON" to jsonName, "TXT" to txtName, "DOCX" to docxName).forEach { (label, fileName) ->
            if (j.get("attempted_$label")?.asBoolean == true || j.get("created_$label")?.asBoolean == true) callProvider("deleteOwned", fileName)
        }
        if (sourceId > 0 && !j.getAsJsonObject("character").has("character_$sourceId")) characterPrefs.edit().remove("character_$sourceId").commit()
        check(importedRows().isEmpty()); check(sourceId <= 0 || runBlocking(Dispatchers.IO) { db.characterDao().getById(sourceId) == null }); check(digest() == j.get("digest").asString); assertSnapshot(worldPrefs, j.getAsJsonObject("world")); assertSnapshot(characterPrefs, j.getAsJsonObject("character")); assertSnapshot(chatPrefs, j.getAsJsonObject("chat")); assertSnapshot(entryPrefs, j.getAsJsonObject("entry")); check(branches() == j.getAsJsonObject("branches")); j.addProperty("phase", "rolled-back"); persist(j); output.resolve("rollback.txt").writeText("onlyOwnedSamplingRowsRemoved=true\notherDigestFourDraftGroupsBranchesExact=true\n")
    }
}

package com.mojing.app.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.StoryOpeningInputDraftStore
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
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

class EntryListPreviewAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("previewRun").orEmpty().also { UUID.fromString(it) }
    private val title get() = "预览航图${run.take(8)}"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val journalFile get() = File(context.filesDir, "entry-list-preview-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "entry-list-preview-$run").apply { mkdirs() }
    private fun guard() {
        check(args.getString("previewCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "entry-list-preview-$run.pending")
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
    private val entryTitle get() = "港湾${run.take(8)}"
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

    private val marker get() = "ENTRY_PREVIEW_$run"
    private val fullText get() = (1..150).joinToString("\n") { "航图$it：完整正文应只在预览选中和编辑时读取，远方的灯塔照亮归途。$marker" }
    private fun openWorldOnly() {
        rule.waitUntil(10000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasText("创作") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        waitText("世界"); rule.onNodeWithText("世界").performClick()
        rule.waitUntil(10000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(title); hideKeyboard()
        rule.waitUntil(10000) { rule.onAllNodesWithText(title).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(title).filter(!hasSetTextAction()).onLast().performClick(); waitText("关系图")
    }
    private fun rotate(orientation: Int) {
        rule.runOnUiThread { rule.activity.requestedOrientation = orientation }
        val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        rule.waitUntil(15000) { rule.activity.resources.configuration.orientation == expected }
        rule.waitForIdle()
    }
    private fun assertDrafts(j: JsonObject) {
        assertUnchanged(worldPrefs, j.getAsJsonObject("originalWorldDrafts")); assertUnchanged(characterPrefs, j.getAsJsonObject("originalCharacterDrafts"))
        assertUnchanged(chatPrefs, j.getAsJsonObject("originalChatDrafts")); assertUnchanged(entryPrefs, j.getAsJsonObject("originalEntryDrafts"))
    }
    @Test fun widePreviewMissingTargetRetryFullEditorAndNarrowReopen() {
        guard(); check(!journalFile.exists())
        val originalOrientation = rule.activity.requestedOrientation
        check(rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
        try {
            val j = JsonObject().apply {
                addProperty("run", run)
                addProperty("orientation", originalOrientation)
                add("originalWorldDrafts", snapshot(worldPrefs)); add("originalCharacterDrafts", snapshot(characterPrefs))
                add("originalChatDrafts", snapshot(chatPrefs)); add("originalEntryDrafts", snapshot(entryPrefs))
            }; persist(j)
            runBlocking(Dispatchers.IO) { db.withTransaction {
                val wid = db.encyclopediaDao().upsert(EncyclopediaEntity(name = title, description = marker)); j.addProperty("world", wid); persist(j)
                val eid = db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId = wid, title = entryTitle, entryType = "location", summary = "港湾摘要_$run", content = fullText, metaJson = "{\"note\":\"${"扩展".repeat(6000)}\"}"))
                j.addProperty("entry", eid); persist(j)
            } }
            val original = row(j)
            openWorldOnly(); screenshot("narrow-lightweight-list")
            rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); waitText("选择条目预览")
            rule.onAllNodesWithText(entryTitle).onFirst().performScrollTo().performClick()
            waitText(fullText.take(4000) + "…")
            rule.onNodeWithText(fullText.take(4000) + "…").performScrollTo().assertIsDisplayed()
            screenshot("wide-selected-long-preview")
            rule.onNodeWithText("编辑此条目").performScrollTo().performClick(); waitText("编辑条目")
            field(fullText).performScrollTo().assertIsDisplayed(); check(row(j) == original)
            screenshot("wide-editor-full-text")
            back(); waitText("关系图"); waitText(fullText.take(4000) + "…")
            runBlocking(Dispatchers.IO) { db.withTransaction { check(db.encyclopediaEntryDao().getById(original.id) == original); db.encyclopediaEntryDao().delete(original.id) } }
            rule.onAllNodesWithText(entryTitle).onFirst().performScrollTo().performClick(); waitText("条目已不可用，请刷新列表")
            rule.onNodeWithText("重试预览").performScrollTo().assertIsDisplayed(); screenshot("wide-unavailable-preview-actions")
            runBlocking(Dispatchers.IO) { db.withTransaction { check(db.encyclopediaEntryDao().getById(original.id) == null); db.encyclopediaEntryDao().upsert(original) } }
            rule.onNodeWithText("重试预览").performClick(); waitText(fullText.take(4000) + "…"); check(row(j) == original)
            rule.onNodeWithText(fullText.take(4000) + "…").performScrollTo().assertIsDisplayed()
            screenshot("wide-preview-retry-restored-target")
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); waitText(entryTitle)
            rule.onAllNodesWithText(entryTitle).onFirst().performScrollTo().performClick(); waitText("编辑条目"); field(fullText).assertExists(); check(row(j) == original)
            screenshot("narrow-editor-full-text-after-preview")
            back(); waitText("关系图"); back(); waitText("搜索世界名称"); back()
            assertDrafts(j); j.addProperty("phase", "verified"); persist(j)
            output.resolve("verified.txt").writeText("narrowList=true\nwidePreviewFirst4000=true\nwideEditorFullTextExact=true\nmissingTargetVisibleRetryAndRefresh=true\nretryAfterFixtureRestore=true\nportraitReopenFullTextRoomExact=true\nfourOtherDraftGroupsExact=true\n")
        } catch (failure: Throwable) {
            output.resolve("failure.txt").writeText(failure.stackTraceToString())
            runCatching { output.resolve("failure-tree.txt").writeText(rule.onAllNodes(isRoot(), useUnmergedTree = true).onFirst().printToString(maxDepth = 100)) }
            runCatching { screenshot("failure-window") }; throw failure
        } finally {
            runCatching { rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) }
            rule.runOnUiThread { rule.activity.requestedOrientation = originalOrientation }
        }
    }
    @Test fun rollbackOnlyOwnedPreviewWorldEntryAndDraft() {
        guard(); val j = journal()
        rule.runOnUiThread { rule.activity.requestedOrientation = j.get("orientation").asInt }
        runBlocking(Dispatchers.IO) { db.withTransaction {
            j.get("world")?.asLong?.let { wid ->
                val world = db.encyclopediaDao().getById(wid); check(world == null || world.name == title && world.description == marker)
                db.encyclopediaEntryDao().getByEncyclopedia(wid).forEach { check(it.id == j.get("entry").asLong && it.content == fullText) }
                check(db.entryRelationDao().getByEncyclopedia(wid).isEmpty())
                if (world != null) db.encyclopediaDao().delete(wid)
                check(db.encyclopediaEntryDao().countEntries(wid) == 0)
            }
            j.get("entry")?.asLong?.let { check(db.entryVersionDao().getByEntry(it).isEmpty()) }
        } }
        j.get("entry")?.asLong?.let { eid -> val key = "encyclopedia_${j.get("world").asLong}_entry_$eid"; check(!j.getAsJsonObject("originalEntryDrafts").has(key)); check(entryPrefs.edit().remove(key).commit()) }
        assertDrafts(j); j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("onlyUuidWorldEntryDraftRemoved=true\nfourOtherDraftGroupsExact=true\n")
    }
}

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

class EntryVersionFlowAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("versionRun").orEmpty().also { UUID.fromString(it) }
    private val title get() = "版本航图${run.take(8)}"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val journalFile get() = File(context.filesDir, "entry-version-flow-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "entry-version-flow-$run").apply { mkdirs() }
    private fun guard() {
        check(args.getString("versionCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "entry-version-flow-$run.pending")
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
    private val entryTitle get() = "旧港${run.take(8)}"
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
    @Test fun loadTextCancelConfirmSaveAndReopen() {
        guard(); check(!journalFile.exists())
        try {
        val j = JsonObject().apply {
            addProperty("run", run)
            add("originalWorldDrafts", snapshot(worldPrefs)); add("originalCharacterDrafts", snapshot(characterPrefs)); add("originalChatDrafts", snapshot(chatPrefs)); add("originalEntryDrafts", snapshot(entryPrefs))
            addProperty("cover", "version-fixture-$run.png")
        }; persist(j)
        val cover = File(context.filesDir, j.get("cover").asString)
        check(!cover.exists())
        Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.rgb(45, 72, 84))
            FileOutputStream(cover).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() }; bitmap.recycle()
        }
        runBlocking(Dispatchers.IO) { db.withTransaction {
            val wid = db.encyclopediaDao().upsert(EncyclopediaEntity(name = title, description = "VERSION_WORLD_$run"))
            j.addProperty("world", wid); persist(j)
            val id = db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId = wid,
                title = entryTitle, entryType = "world", summary = "初始摘要_$run", content = "初始正文_$run", tags = "初始标签_$run",
                metaJson = "{\"note\":\"初始扩展_$run\"}", coverImagePath = cover.absolutePath))
            j.addProperty("entry", id); persist(j)
        } }
        val initial = row(j)
        openWorldAndEntry(entryTitle)
        advanced()
        replace(initial.title, "一稿${run.take(8)}"); replace(initial.summary, "一稿摘要_$run")
        replace(initial.content, "一稿正文_$run"); replace(initial.tags, "一稿标签_$run"); replace(initial.metaJson, "{\"note\":\"一稿扩展_$run\"}")
        save(j, "一稿${run.take(8)}", 1)
        val first = row(j); val v1 = versions(j).single()
        check(v1.title == initial.title && v1.summary == initial.summary && v1.content == initial.content && v1.tags == initial.tags && v1.metaSnapshotJson == initial.metaJson)
        replace(first.title, "二稿${run.take(8)}"); replace(first.summary, "二稿摘要_$run")
        replace(first.content, "二稿正文_$run"); replace(first.tags, "二稿标签_$run"); replace(first.metaJson, "{\"note\":\"二稿扩展_$run\"}")
        save(j, "二稿${run.take(8)}", 2)
        val second = row(j); val oldVersions = versions(j)
        val v2 = oldVersions.first()
        check(v2.title == first.title && v2.summary == first.summary && v2.content == first.content && v2.tags == first.tags && v2.metaSnapshotJson == first.metaJson)
        replace(second.title, "未存${run.take(8)}"); replace(second.summary, "未存摘要_$run")
        replace(second.content, "未存正文_$run"); replace(second.tags, "未存标签_$run"); replace(second.metaJson, "{\"note\":\"未存扩展_$run\"}")
        rule.onNodeWithText("世界").performScrollTo().performClick(); rule.waitUntil(10000) { rule.onAllNodesWithText("地点", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("地点", substring = true) and hasClickAction()).performClick()
        rule.onNodeWithText("已确认").performScrollTo().performClick(); waitText("待核对")
        rule.onNode(hasText("待核对") and hasClickAction()).performClick()
        rule.onNode(isToggleable()).performScrollTo().performClick()
        history(); screenshot("history-scope-two-production-snapshots")
        selectOld(); screenshot("replacement-scope-confirmation")
        rule.onNodeWithText("保留当前修改").performClick()
        rule.onNodeWithText("编辑").performClick()
        assertText("未存${run.take(8)}", "未存摘要_$run", "未存正文_$run", "未存标签_$run", "{\"note\":\"未存扩展_$run\"}")
        rule.onNodeWithText("地点").assertExists(); rule.onNodeWithText("待核对").assertExists(); rule.onNode(isToggleable()).assertIsOn()
        check(row(j) == second && versions(j) == oldVersions)
        screenshot("cancel-keeps-unsaved-text")
        history(); selectOld(); rule.onNodeWithText("替换并载入").performClick(); rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("编辑目录").fetchSemanticsNodes().isNotEmpty() }
        assertText(initial.title, initial.summary, initial.content, initial.tags, initial.metaJson)
        rule.onNodeWithText("地点").assertExists(); rule.onNodeWithText("待核对").assertExists(); rule.onNode(isToggleable()).assertIsOn()
        check(row(j) == second && versions(j) == oldVersions)
        field(initial.title).performScrollTo(); screenshot("loaded-text-current-type")
        rule.onNodeWithContentDescription("条目封面预览").performScrollTo().assertIsDisplayed()
        screenshot("loaded-text-cover-retained")
        save(j, initial.title, 3)
        val saved = row(j)
        check(saved.title == initial.title && saved.summary == initial.summary && saved.content == initial.content && saved.tags == initial.tags && saved.metaJson == initial.metaJson)
        check(saved.entryType == "location" && saved.confidence == "pending" && saved.isFeatured && saved.coverImagePath == initial.coverImagePath)
        check(saved.sourceSessionId == initial.sourceSessionId && saved.sourceMessageId == initial.sourceMessageId && saved.createdAt == initial.createdAt)
        val finalVersions = versions(j)
        check(finalVersions.drop(1) == oldVersions)
        check(finalVersions.first().version == 3 && finalVersions.first().title == second.title && finalVersions.first().summary == second.summary && finalVersions.first().content == second.content && finalVersions.first().tags == second.tags && finalVersions.first().metaSnapshotJson == second.metaJson)
        back(); waitText("关系图"); back(); waitText("搜索世界名称"); back(); waitText("世界")
        openWorldAndEntry(initial.title); advanced(); assertText(initial.title, initial.summary, initial.content, initial.tags, initial.metaJson)
        rule.onNodeWithText("地点").assertExists(); rule.onNodeWithText("待核对").assertExists(); rule.onNode(isToggleable()).assertIsOn()
        check(row(j) == saved && versions(j) == finalVersions)
        history(); screenshot("reopened-three-versions")
        back(); waitText("关系图"); back(); waitText("搜索世界名称"); back()
        assertUnchanged(worldPrefs, j.getAsJsonObject("originalWorldDrafts")); assertUnchanged(characterPrefs, j.getAsJsonObject("originalCharacterDrafts")); assertUnchanged(chatPrefs, j.getAsJsonObject("originalChatDrafts")); assertUnchanged(entryPrefs, j.getAsJsonObject("originalEntryDrafts"))
        j.addProperty("phase", "verified"); persist(j)
        output.resolve("verified.txt").writeText("productionUiCreatedTwoSnapshots=true\ncancelKeepsAllDraftFields=true\nloadFiveTextFieldsOnly=true\nroomUnchangedBeforeSave=true\ncurrentTypeConfidenceFeaturedCoverRetained=true\nnewSnapshotCountThreeOldRowsExact=true\nreopenRowAndVersionsExact=true\notherDraftsExact=true\n")
        } catch (failure: Throwable) {
            output.resolve("failure.txt").writeText(failure.stackTraceToString())
            runCatching { output.resolve("failure-tree.txt").writeText(rule.onAllNodes(isRoot(), useUnmergedTree = true).onFirst().printToString(maxDepth = 100)) }
            runCatching { screenshot("failure-window") }; throw failure
        }
    }
    @Test fun rollbackOnlyOwnedWorldEntryDraftAndCover() {
        guard(); val j = journal()
        j.get("world")?.asLong?.let { wid -> runBlocking(Dispatchers.IO) { db.withTransaction {
            val world = db.encyclopediaDao().getById(wid)
            check(world == null || world.name == title && world.description == "VERSION_WORLD_$run")
            if (world != null) {
                val id = j.get("entry")?.asLong
                db.encyclopediaEntryDao().getByEncyclopedia(wid).forEach { check(it.id == id && it.coverImagePath == File(context.filesDir, j.get("cover").asString).absolutePath && it.content.endsWith(run)) }
                check(db.entryRelationDao().getByEncyclopedia(wid).isEmpty())
                db.encyclopediaDao().delete(wid)
            }
            check(db.encyclopediaEntryDao().getByEncyclopedia(wid).isEmpty())
            j.get("entry")?.asLong?.let { check(db.entryVersionDao().getByEntry(it).isEmpty()) }
        } }
            j.get("entry")?.asLong?.let { id ->
                val key = "encyclopedia_${wid}_entry_$id"
                check(!j.getAsJsonObject("originalEntryDrafts").has(key))
                check(entryPrefs.edit().remove(key).commit())
            }
        }
        val cover = File(context.filesDir, j.get("cover").asString)
        check(cover.name == "version-fixture-$run.png" && cover.canonicalFile.parentFile == context.filesDir.canonicalFile)
        if (cover.exists()) check(cover.delete())
        assertUnchanged(worldPrefs, j.getAsJsonObject("originalWorldDrafts")); assertUnchanged(characterPrefs, j.getAsJsonObject("originalCharacterDrafts")); assertUnchanged(chatPrefs, j.getAsJsonObject("originalChatDrafts")); assertUnchanged(entryPrefs, j.getAsJsonObject("originalEntryDrafts"))
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("uuidWorldEntryVersionsDraftCoverRemoved=true\notherDraftsExact=true\n")
    }
}

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

class EntryDraftFlowAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("entryDraftRun").orEmpty().also { UUID.fromString(it) }
    private val title get() = "草稿航图${run.take(8)}"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val journalFile get() = File(context.filesDir, "entry-draft-flow-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "entry-draft-flow-$run").apply { mkdirs() }
    private fun guard() {
        check(args.getString("entryDraftCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "entry-draft-flow-$run.pending")
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
    private fun fullScreenBody(value: String) {
        rule.onNodeWithText("全屏编辑").performScrollTo().performClick(); waitText("完成")
        rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()), useUnmergedTree = true).performTextReplacement(value)
        rule.onNode(hasSetTextAction() and hasText(value) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("完成").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("完成").fetchSemanticsNodes().isEmpty() }
    }
    private fun longText(mark: String) = "$mark-$run\n" + (1..250).joinToString("\n") {
        "第${it}段：潮声穿过旧港的石阶，守灯人记下归航的船名与星位。每一段设定都属于这份百科草稿，未保存时仍可离开后继续，不能只保留可见片段。"
    } + "\nEND_$mark-$run"
    @Test fun failedSaveRetryRetainRecoverSaveAndDiscardLongDraft() {
        guard(); check(!journalFile.exists())
        try {
        val j = JsonObject().apply {
            addProperty("run", run)
            add("originalWorldDrafts", snapshot(worldPrefs)); add("originalCharacterDrafts", snapshot(characterPrefs)); add("originalChatDrafts", snapshot(chatPrefs)); add("originalEntryDrafts", snapshot(entryPrefs))
            addProperty("cover", "entry-draft-fixture-$run.png")
        }; persist(j)
        val cover = File(context.filesDir, j.get("cover").asString); check(!cover.exists())
        Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.rgb(45, 72, 84))
            FileOutputStream(cover).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() }; bitmap.recycle()
        }
        runBlocking(Dispatchers.IO) { db.withTransaction {
            val wid = db.encyclopediaDao().upsert(EncyclopediaEntity(name = title, description = "DRAFT_WORLD_$run"))
            j.addProperty("world", wid); persist(j)
            val id = db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId = wid,
                title = entryTitle, entryType = "location", summary = "初始摘要_$run", content = "初始正文_$run", tags = "初始标签_$run",
                metaJson = "{\"note\":\"初始扩展_$run\"}", confidence = "pending", isFeatured = true, coverImagePath = cover.absolutePath))
            j.addProperty("entry", id); j.add("initialRow", Gson().toJsonTree(db.encyclopediaEntryDao().getById(id))); persist(j)
        } }
        val initial = row(j); val firstBody = longText("FIRST"); val firstSummary = "长摘要_$run：" + "潮声与归航记忆，".repeat(90)
        openWorldAndEntry(entryTitle)
        fullScreenBody(firstBody); replace(initial.summary, firstSummary)
        runBlocking(Dispatchers.IO) { db.withTransaction {
            check(db.encyclopediaEntryDao().getById(initial.id) == initial)
            check(db.entryVersionDao().getByEntry(initial.id).isEmpty())
            j.addProperty("phase", "before-owned-target-delete"); persist(j)
            db.encyclopediaEntryDao().delete(initial.id)
        } }
        rule.onAllNodesWithText("保存修改").onFirst().performClick(); waitText("重试保存")
        field(firstBody).assertExists(); field(firstSummary).assertExists()
        check(runBlocking(Dispatchers.IO) { db.encyclopediaEntryDao().getById(initial.id) == null && db.entryVersionDao().getByEntry(initial.id).isEmpty() })
        screenshot("owned-target-deleted-save-error-draft-retained")
        runBlocking(Dispatchers.IO) { db.withTransaction {
            check(db.encyclopediaEntryDao().getById(initial.id) == null)
            check(db.encyclopediaDao().getById(initial.encyclopediaId)?.description == "DRAFT_WORLD_$run")
            check(db.encyclopediaEntryDao().upsert(initial) == initial.id)
        } }
        rule.onAllNodesWithText("重试保存").onFirst().assertIsEnabled().performClick()
        rule.waitUntil(10000) { row(j).content == firstBody && versions(j).size == 1 && rule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
        val first = row(j); check(first.summary == firstSummary)
        check(first.entryType == initial.entryType && first.confidence == initial.confidence && first.isFeatured == initial.isFeatured && first.coverImagePath == initial.coverImagePath && first.metaJson == initial.metaJson)
        check(versions(j).single().content == initial.content)
        val retainedBody = longText("RETAINED"); val retainedSummary = "重进摘要_$run：" + "继续旧港的新篇，".repeat(80)
        fullScreenBody(retainedBody); replace(firstSummary, retainedSummary)
        rule.onNodeWithContentDescription("返回").performClick(); waitText("尚未保存的修改"); screenshot("long-draft-leave-confirmation")
        rule.onNodeWithText("继续编辑").performClick(); field(retainedBody).assertExists(); field(retainedSummary).assertExists(); check(row(j) == first)
        rule.onNodeWithContentDescription("返回").performClick(); waitText("尚未保存的修改")
        rule.onNodeWithText("保留草稿并离开").performClick(); waitText("关系图")
        check(row(j) == first && versions(j).size == 1)
        rule.onNodeWithText(entryTitle).performScrollTo().performClick(); waitText("发现未保存的词条草稿"); screenshot("reopen-recovery-choice")
        rule.onNodeWithText("恢复草稿").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("发现未保存的词条草稿").fetchSemanticsNodes().isEmpty() }
        field(retainedBody).assertExists(); field(retainedSummary).assertExists(); check(row(j) == first)
        rule.onNodeWithText("全屏编辑").performScrollTo().performClick(); waitText("完成")
        rule.onNode(hasSetTextAction() and hasText(retainedBody) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists(); screenshot("recovered-full-long-body")
        rule.onNodeWithText("完成").performClick(); save(j, entryTitle, 2)
        val saved = row(j); val savedVersions = versions(j)
        check(saved.content == retainedBody && saved.summary == retainedSummary)
        check(saved.entryType == initial.entryType && saved.confidence == initial.confidence && saved.isFeatured == initial.isFeatured && saved.coverImagePath == initial.coverImagePath && saved.metaJson == initial.metaJson)
        check(savedVersions.first().content == firstBody && savedVersions.last().content == initial.content)
        fullScreenBody(longText("DISCARD"))
        rule.onNodeWithContentDescription("返回").performClick(); waitText("尚未保存的修改")
        rule.onNodeWithText("保留草稿并离开").performClick(); waitText("关系图")
        rule.onNodeWithText(entryTitle).performScrollTo().performClick(); waitText("发现未保存的词条草稿")
        rule.onNodeWithText("丢弃草稿").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("发现未保存的词条草稿").fetchSemanticsNodes().isEmpty() }
        field(retainedBody).assertExists(); field(retainedSummary).assertExists()
        check(row(j) == saved && versions(j) == savedVersions)
        rule.onNodeWithContentDescription("返回").performClick(); waitText("关系图")
        rule.onNodeWithText(entryTitle).performScrollTo().performClick(); waitText("已保存")
        check(rule.onAllNodesWithText("发现未保存的词条草稿").fetchSemanticsNodes().isEmpty())
        field(retainedBody).assertExists(); field(retainedSummary).assertExists(); check(row(j) == saved && versions(j) == savedVersions)
        field(retainedSummary).performScrollTo(); screenshot("discarded-draft-reopen-saved-summary")
        rule.onNodeWithContentDescription("返回").performClick(); waitText("关系图"); back(); waitText("搜索世界名称"); back()
        assertUnchanged(worldPrefs, j.getAsJsonObject("originalWorldDrafts")); assertUnchanged(characterPrefs, j.getAsJsonObject("originalCharacterDrafts")); assertUnchanged(chatPrefs, j.getAsJsonObject("originalChatDrafts")); assertUnchanged(entryPrefs, j.getAsJsonObject("originalEntryDrafts"))
        j.addProperty("phase", "verified"); persist(j)
        output.resolve("verified.txt").writeText("longBodyChars=${retainedBody.length}\ncontrolledOwnedTargetDeletionSaveFailure=true\nfailedSaveKeepsFullDraft=true\nrestoredOwnedTargetUiRetrySavesOnce=true\ncontinueEditingKeepsDraft=true\nretainLeaveRecoverFullBodyAndSummary=true\nrecoverSaveCreatesExactlySecondSnapshot=true\ndiscardReopenSavedRowVersionsExact=true\notherDraftsExact=true\n")
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
            check(world == null || world.name == title && world.description == "DRAFT_WORLD_$run")
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
        check(cover.name == "entry-draft-fixture-$run.png" && cover.canonicalFile.parentFile == context.filesDir.canonicalFile)
        if (cover.exists()) check(cover.delete())
        assertUnchanged(worldPrefs, j.getAsJsonObject("originalWorldDrafts")); assertUnchanged(characterPrefs, j.getAsJsonObject("originalCharacterDrafts")); assertUnchanged(chatPrefs, j.getAsJsonObject("originalChatDrafts")); assertUnchanged(entryPrefs, j.getAsJsonObject("originalEntryDrafts"))
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("uuidWorldEntryVersionsDraftCoverRemoved=true\notherDraftsExact=true\n")
    }
}

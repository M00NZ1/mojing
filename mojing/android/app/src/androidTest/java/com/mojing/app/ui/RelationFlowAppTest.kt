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

class RelationFlowAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("relationRun").orEmpty().also { UUID.fromString(it) }
    private val title get() = "关系航图${run.take(8)}"
    private val fromTitle get() = "守灯人${run.take(8)}"
    private val toTitle get() = "港湾${run.take(8)}"
    private val type get() = "守护"
    private val label get() = "RELATION_$run：守灯人守护港湾的航路。"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val journalFile get() = File(context.filesDir, "relation-flow-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "relation-flow-$run").apply { mkdirs() }
    private fun guard() {
        check(args.getString("relationCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "relation-flow-$run.pending")
        FileOutputStream(pending).use { it.write(j.toString().toByteArray()); it.fd.sync() }
        check(pending.renameTo(journalFile))
    }
    private fun journal() = JsonParser.parseString(journalFile.readText()).asJsonObject.also { check(it.get("run").asString == run) }
    private fun relationSnapshot(excludedWorld: Long = -1L) = JsonObject().apply {
        // Fixture safety check only; production relation loading remains paged.
        db.openHelper.readableDatabase.query("SELECT * FROM entry_relations WHERE encyclopediaId != ? ORDER BY id", arrayOf<Any>(excludedWorld)).use { cursor ->
            while (cursor.moveToNext()) add(cursor.getLong(cursor.getColumnIndexOrThrow("id")).toString(), JsonObject().apply {
                cursor.columnNames.forEachIndexed { index, name -> addProperty(name, cursor.getString(index)) }
            })
        }
    }
    private fun assertEntries(j: JsonObject) = runBlocking(Dispatchers.IO) {
        listOf("from", "to").forEach { key ->
            check(db.encyclopediaEntryDao().getById(j.get(key).asLong) == Gson().fromJson(j.get(key + "Row"), EncyclopediaEntryEntity::class.java))
        }
    }
    private fun openWorld() {
        rule.waitUntil(10000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasText("创作") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        waitText("世界"); rule.onNodeWithText("世界").performClick()
        rule.waitUntil(10000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(title)
        hideKeyboard()
        rule.waitUntil(10000) { rule.onAllNodesWithText(title).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(title).filter(!hasSetTextAction()).onLast().performClick()
        waitText("关系图")
        rule.onNode(hasText("关系图") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
    }
    private fun back() { inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); rule.waitForIdle() }
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

    @Test fun addInspectReopenCancelAndDeleteRelation() {
        guard(); check(!journalFile.exists())
        try {
        val j = JsonObject().apply {
            addProperty("run", run)
            add("originalWorldDrafts", snapshot(worldPrefs)); add("originalCharacterDrafts", snapshot(characterPrefs)); add("originalChatDrafts", snapshot(chatPrefs))
            add("originalRelations", runBlocking(Dispatchers.IO) { relationSnapshot() })
        }; persist(j)
        runBlocking(Dispatchers.IO) { db.withTransaction {
            val wid = db.encyclopediaDao().upsert(EncyclopediaEntity(name = title, description = "RELATION_WORLD_$run"))
            j.addProperty("world", wid); persist(j)
            listOf("from" to fromTitle, "to" to toTitle).forEach { (key, name) ->
                val id = db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId = wid, title = name, content = "RELATION_${key.uppercase()}_$run", entryType = if (key == "from") "character" else "location"))
                j.addProperty(key, id); j.add(key + "Row", Gson().toJsonTree(db.encyclopediaEntryDao().getById(id))); persist(j)
            }
        } }
        openWorld(); waitText("还没有条目关系")
        rule.onNodeWithContentDescription("添加关系").performClick(); waitText("添加条目关系")
        rule.onNodeWithText("从条目：请选择").performClick(); waitText("选择条目")
        rule.onNodeWithTag("entry-option:${j.get("from").asLong}").performClick()
        waitText("从条目：$fromTitle")
        rule.onNodeWithText("到条目：请选择").performClick(); waitText("选择条目")
        rule.onNodeWithTag("entry-option:${j.get("to").asLong}").performClick()
        waitText("到条目：$toTitle")
        output.resolve("endpoint-form-tree.txt").writeText(rule.onAllNodes(isRoot(), useUnmergedTree = true).printToString())
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(type)
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTextReplacement(label)
        back(); screenshot("selected-endpoints-type-note")
        rule.onNode(hasText("添加关系") and hasAnyAncestor(isDialog())).assertIsEnabled().performClick()
        waitText("当前页关系图")
        val relation = runBlocking(Dispatchers.IO) { db.entryRelationDao().getByEncyclopedia(j.get("world").asLong).single() }
        check(relation.fromEntryId == j.get("from").asLong && relation.toEntryId == j.get("to").asLong && relation.relationType == type && relation.label == label)
        j.addProperty("relation", relation.id); j.add("relationRow", Gson().toJsonTree(relation)); persist(j)
        rule.onNodeWithText("$fromTitle —[$type]→ $toTitle").performScrollTo().assertIsDisplayed()
        screenshot("saved-graph-and-list")
        listOf("from" to fromTitle, "to" to toTitle).forEach { (key, name) ->
            rule.onNode(hasText(name.take(1)) and hasClickAction()).performScrollTo().performClick()
            waitText("打开条目"); rule.onNodeWithText("打开条目").performScrollTo().performClick()
            rule.waitUntil(10000) { rule.onAllNodes(hasSetTextAction() and hasText(name), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasSetTextAction() and hasText("RELATION_${key.uppercase()}_$run"), useUnmergedTree = true).assertExists()
            screenshot("node-$key-correct-entry")
            back(); waitText("关系图")
        }
        back(); waitText("搜索世界名称"); back(); waitText("世界")
        openWorld(); waitText("当前页关系图")
        check(runBlocking(Dispatchers.IO) { db.entryRelationDao().getByEncyclopedia(j.get("world").asLong).single() } == relation)
        rule.onNodeWithContentDescription("删除关系").performScrollTo().performClick(); waitText("确认删除关系")
        screenshot("delete-confirmation")
        rule.onNodeWithText("取消").performClick()
        check(runBlocking(Dispatchers.IO) { db.entryRelationDao().getByEncyclopedia(j.get("world").asLong).single() } == relation)
        rule.onNodeWithContentDescription("删除关系").performScrollTo().performClick(); waitText("确认删除关系")
        rule.onNodeWithText("删除").performClick(); waitText("还没有条目关系")
        check(runBlocking(Dispatchers.IO) { db.entryRelationDao().getByEncyclopedia(j.get("world").asLong).isEmpty() })
        assertEntries(j); screenshot("relation-deleted-entries-retained")
        back(); waitText("搜索世界名称"); back(); waitText("世界")
        openWorld(); waitText("还没有条目关系"); assertEntries(j)
        check(runBlocking(Dispatchers.IO) { relationSnapshot(j.get("world").asLong) } == j.getAsJsonObject("originalRelations"))
        back(); waitText("搜索世界名称"); back()
        j.addProperty("phase", "verified"); persist(j)
        output.resolve("verified.txt").writeText("relationFieldsExact=true\nsameIdAfterReopen=true\nbothNodeTargetsExact=true\ncancelKeepsRow=true\ndeleteOnlyRelation=true\nentriesExact=true\notherRelationsExact=true\n")
        } catch (failure: Throwable) {
            output.resolve("failure.txt").writeText(failure.stackTraceToString())
            runCatching { output.resolve("failure-tree.txt").writeText(rule.onAllNodes(isRoot(), useUnmergedTree = true).printToString()) }
            runCatching { screenshot("failure-window") }
            throw failure
        }
    }
    @Test fun rollbackOnlyOwnedWorldAndEntries() {
        guard(); val j = journal()
        j.get("world")?.asLong?.let { wid -> runBlocking(Dispatchers.IO) { db.withTransaction {
            val world = db.encyclopediaDao().getById(wid)
            check(world == null || world.name == title && world.description == "RELATION_WORLD_$run")
            if (world != null) {
                val ids = listOfNotNull(j.get("from")?.asLong, j.get("to")?.asLong)
                db.encyclopediaEntryDao().getByEncyclopedia(wid).forEach { check(it.id in ids && it.content == "RELATION_${if(it.id == j.get("from").asLong) "FROM" else "TO"}_$run") }
                db.entryRelationDao().getByEncyclopedia(wid).forEach { check(it.fromEntryId in ids && it.toEntryId in ids && it.label == label) }
                db.encyclopediaDao().delete(wid)
            }
            check(db.encyclopediaEntryDao().getByEncyclopedia(wid).isEmpty()); check(db.entryRelationDao().getByEncyclopedia(wid).isEmpty())
        } } }
        assertUnchanged(worldPrefs, j.getAsJsonObject("originalWorldDrafts")); assertUnchanged(characterPrefs, j.getAsJsonObject("originalCharacterDrafts")); assertUnchanged(chatPrefs, j.getAsJsonObject("originalChatDrafts"))
        check(runBlocking(Dispatchers.IO) { relationSnapshot() } == j.getAsJsonObject("originalRelations"))
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("uuidWorldEntriesRelationsRemoved=true\notherDraftsAndRelationsExact=true\n")
    }
}

package com.mojing.app.ui

import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowInsets
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.*
import com.mojing.app.data.local.entity.ConfigEntity
import com.mojing.app.domain.story.*
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

/** Host SIGKILL phases. Original data stays in a durable private journal, never exported. */
class NovelRecoveryProcessAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("novelRun").orEmpty().also { UUID.fromString(it) }
    private val multiBatch get() = args.getString("novelMultiBatch") == "true"
    private fun completeBody(number: Int) = "完整第${number}章灯塔约定。".repeat(100) + "COMPLETE_${number}_$run"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val store get() = StoryOpeningInputDraftStore(context)
    private val drafts get() = context.getSharedPreferences("story_opening_input_draft_v1", 0)
    private val prefs get() = rule.activity.secureStorage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(rule.activity.secureStorage) as SharedPreferences
    private val keys get() = listOf("public_api_key", "public_base_url", "public_model")
    private val journalFile get() = File(context.filesDir, "novel-process-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "novel-process-$run").apply { mkdirs() }
    private fun guard() { check(args.getString("novelProcessCapture") == "true"); check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic")) }
    private fun persist(j: JsonObject) {
        val f = File(context.filesDir, "novel-process-$run.pending")
        FileOutputStream(f).use { it.write(j.toString().toByteArray()); it.fd.sync() }
        check(f.renameTo(journalFile))
    }
    private fun journal() = JsonParser.parseString(journalFile.readText()).asJsonObject.also { check(it.get("run").asString == run) }
    private fun snapshot(p: SharedPreferences, keys: List<String>) = JsonObject().apply { keys.forEach { k -> add(k, JsonObject().apply {
        addProperty("present", p.contains(k)); if (p.contains(k)) addProperty("value", p.getString(k, null))
    }) } }
    private fun restore(p: SharedPreferences, original: JsonObject) {
        val editor = p.edit()
        original.entrySet().forEach { (k, v) -> if (v.asJsonObject.get("present").asBoolean) editor.putString(k, v.asJsonObject.get("value").asString) else editor.remove(k) }
        check(editor.commit())
        original.entrySet().forEach { (k, v) -> check(p.contains(k) == v.asJsonObject.get("present").asBoolean); if (p.contains(k)) check(p.getString(k, null) == v.asJsonObject.get("value").asString) }
    }
    private fun openNovel() {
        rule.waitForIdle()
        rule.waitUntil(10000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasText("创作") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        rule.waitForIdle()
        rule.waitUntil(10000) { rule.onAllNodesWithText("小说创作").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("小说创作").onFirst().performClick()
    }
    @Test fun streamUntilHostTerminatesProcess() {
        guard(); check(!journalFile.exists()); check(RetainedChatSessions.running.value.isEmpty()); check(store.loadGeneration() == null)
        val receipt = runBlocking(Dispatchers.IO) { db.configDao().get(StoryOpeningDraftCodec.KEY) }
        check(receipt == null || StoryOpeningDraftCodec.decode(receipt.valueJson) is StoryOpeningRecord.Saved)
        val base = args.getString("localBase").orEmpty(); check(base.matches(Regex("http://127[.]0[.]0[.]1:[0-9]+/v1")))
        val j = JsonObject().apply {
            addProperty("run", run); addProperty("seedPid", Process.myPid()); addProperty("phase", "snapshot")
            add("original", snapshot(prefs, keys)); add("draftOriginal", snapshot(drafts, listOf("input", "generation")))
            addProperty("receiptPresent", receipt != null); if (receipt != null) addProperty("receipt", receipt.valueJson)
        }
        persist(j)
        check(prefs.edit().putString("public_api_key", "local-novel-only").putString("public_base_url", base).putString("public_model", "novel-$run").commit())
        runBlocking(Dispatchers.IO) {
            if (receipt != null) db.configDao().delete(StoryOpeningDraftCodec.KEY)
            store.commit(StoryOpeningInputDraft("NOVEL_$run", "保护灯塔秘密", "悬疑", if (multiBatch) 5 else 2, null, null, emptySet()))
        }
        openNovel()
        rule.waitUntil(10000) { rule.onAllNodesWithText("开始创作").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("开始创作").onFirst().performClick()
        rule.waitUntil(20000) { val g = store.loadGeneration(); g != null && runBlocking(Dispatchers.IO) {
            store.loadGenerationBatches(g).lastOrNull()?.contains("TAIL_$run") == true &&
                (!multiBatch || store.loadCompletedChapters(g).size == 3)
        } }
        val g = checkNotNull(store.loadGeneration()); val raw = runBlocking(Dispatchers.IO) { checkNotNull(store.loadGenerationContent(g)) }
        check(g.input.premise == "NOVEL_$run" && g.model == "novel-$run")
        if (multiBatch) runBlocking(Dispatchers.IO) {
            val chapters = store.loadCompletedChapters(g)
            check(g.input.chapterCount == 5 && chapters.map { it.number } == listOf(1, 2, 3))
            chapters.forEach { check(it.content == completeBody(it.number)) }
            val batches = store.loadGenerationBatches(g)
            check(batches.size == 2 && batches.last().contains("TAIL_$run"))
            j.addProperty("secondRaw", batches.last())
        }
        j.addProperty("generationId", g.requestId); j.addProperty("raw", raw); j.addProperty("phase", "ready-for-kill"); persist(j)
        output.resolve("ready.txt").writeText("pid=${Process.myPid()}\nrun=$run\ndurableBody=true\n")
        SystemClock.sleep(55000)
        error("host did not terminate the owned process")
    }
    @Test fun nextProcessRestoresSavesAndReopensOnce() {
        guard(); val j = journal(); check(j.get("phase").asString == "ready-for-kill"); check(Process.myPid() != j.get("seedPid").asInt)
        val g = checkNotNull(store.loadGeneration()); check(g.requestId == j.get("generationId").asString)
        check(runBlocking(Dispatchers.IO) { store.loadGenerationContent(g) } == j.get("raw").asString)
        if (multiBatch) runBlocking(Dispatchers.IO) {
            val chapters = store.loadCompletedChapters(g)
            check(chapters.map { it.number } == listOf(1, 2, 3))
            chapters.forEach { check(it.content == completeBody(it.number)) }
            check(store.loadGenerationBatches(g).last() == j.get("secondRaw").asString)
        }
        openNovel()
        rule.waitUntil(10000) { rule.onAllNodesWithText("上次生成中断").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("复制已收到正文").onFirst().performScrollTo().performClick()
        rule.onAllNodesWithText(if (multiBatch) "保存已收到草稿" else "保存中断片段草稿").onFirst().performScrollTo().performClick()
        rule.waitUntil(20000) { runBlocking(Dispatchers.IO) { db.configDao().get(StoryOpeningDraftCodec.KEY)?.let { StoryOpeningDraftCodec.decode(it.valueJson) is StoryOpeningRecord.Saved } == true } }
        val saved = runBlocking(Dispatchers.IO) { StoryOpeningDraftCodec.decode(db.configDao().get(StoryOpeningDraftCodec.KEY)!!.valueJson) } as StoryOpeningRecord.Saved
        j.addProperty("session", saved.sessionId); j.addProperty("savedDraftId", saved.id); persist(j)
        val rows = runBlocking(Dispatchers.IO) { db.messageDao().getNextStoryContextBatch(saved.sessionId, "main", 0, 20) }
        val chapters = rows.filter { it.speakerType == "narrator" }.sortedBy { it.id }
        check(chapters.size == if (multiBatch) 4 else 1)
        check(chapters.last().content.contains("TAIL_$run"))
        if (multiBatch) chapters.forEachIndexed { index, row ->
            val metadata = JsonParser.parseString(row.structuredContentJson).asJsonObject
            check(metadata.get("chapter_number").asInt == index + 1)
            check(metadata.get("chapter_incomplete").asBoolean == (index == 3))
            if (index < 3) check(row.content.contains(completeBody(index + 1)))
        }
        check(store.loadGeneration() == null)
        rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); rule.waitForIdle(); openNovel()
        rule.waitUntil(10000) { rule.onAllNodesWithText("上次创作已保存").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("打开已保存的会话").onFirst().performScrollTo().performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }
        check(runBlocking(Dispatchers.IO) { (StoryOpeningDraftCodec.decode(db.configDao().get(StoryOpeningDraftCodec.KEY)!!.valueJson) as StoryOpeningRecord.Saved).sessionId } == saved.sessionId)
        check(runBlocking(Dispatchers.IO) { db.messageDao().getNextStoryContextBatch(saved.sessionId, "main", 0, 20).count { it.speakerType == "narrator" } } == chapters.size)
        j.addProperty("phase", "verified"); persist(j)
        output.resolve("verified.txt").writeText("newPid=${Process.myPid()}\nrawRecovered=true\nsavedOnce=true\nreopenedSameSession=true\n")
    }
    @Test fun rollbackOnlyOwnedFixtureAndExactConfiguration() {
        guard(); val j = journal()
        rule.waitForIdle()
        (j.get("generationId")?.asString ?: store.loadGeneration()?.takeIf { it.input.premise == "NOVEL_$run" }?.requestId)?.let { runBlocking(Dispatchers.IO) { store.clearGeneration(it) } }
        runBlocking(Dispatchers.IO) { db.withTransaction {
            val record = db.configDao().get(StoryOpeningDraftCodec.KEY)
            val saved = record?.let { StoryOpeningDraftCodec.decode(it.valueJson) as? StoryOpeningRecord.Saved }
            val sid = j.get("session")?.asLong ?: saved?.sessionId
            if (sid != null) { val session = db.sessionDao().getById(sid); check(session == null || session.summary == "NOVEL_$run"); db.sessionDao().delete(sid) }
            db.openHelper.writableDatabase.execSQL("DELETE FROM llm_cost_records WHERE modelName = ?", arrayOf("novel-$run"))
            if (j.get("receiptPresent").asBoolean) db.configDao().set(ConfigEntity(StoryOpeningDraftCodec.KEY, j.get("receipt").asString)) else db.configDao().delete(StoryOpeningDraftCodec.KEY)
        } }
        restore(prefs, j.getAsJsonObject("original")); restore(drafts, j.getAsJsonObject("draftOriginal"))
        check(runBlocking(Dispatchers.IO) { db.configDao().get(StoryOpeningDraftCodec.KEY)?.valueJson } == if (j.get("receiptPresent").asBoolean) j.get("receipt").asString else null)
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("exactConfigAndDraftRestored=true\nprivateJournalRetained=true\n")
    }

    /** Real window orientation/IME changes, distinct from ActivityScenario.recreate. */
    @Test fun novelInputSurvivesRealRotationKeyboardBackAndReentry() {
        guard(); check(!journalFile.exists()); check(RetainedChatSessions.running.value.isEmpty()); check(store.loadGeneration() == null)
        val receipt = runBlocking(Dispatchers.IO) { db.configDao().get(StoryOpeningDraftCodec.KEY) }
        check(receipt == null || StoryOpeningDraftCodec.decode(receipt.valueJson) is StoryOpeningRecord.Saved)
        val originalOrientation = rule.activity.requestedOrientation
        val j = JsonObject().apply {
            addProperty("run", run); addProperty("phase", "rotation-snapshot")
            add("original", snapshot(prefs, keys)); add("draftOriginal", snapshot(drafts, listOf("input", "generation")))
            addProperty("receiptPresent", receipt != null); if (receipt != null) addProperty("receipt", receipt.valueJson)
        }
        persist(j)
        runBlocking(Dispatchers.IO) {
            if (receipt != null) db.configDao().delete(StoryOpeningDraftCodec.KEY)
            store.commit(StoryOpeningInputDraft.EMPTY)
        }
        var text = "ROTATE_$run\n" + "灯塔守望者将未寄出的信放进抽屉。\n".repeat(25)
        fun input() = rule.onNode(hasSetTextAction(), useUnmergedTree = true)
        fun rotate(requested: Int, expected: Int) {
            rule.runOnUiThread { rule.activity.requestedOrientation = requested }
            rule.waitUntil(10000) { rule.activity.resources.configuration.orientation == expected }
            rule.waitForIdle()
            input().assertTextContains(text)
        }
        fun screenshot(name: String) {
            rule.waitForIdle(); inst.waitForIdleSync()
            repeat(2) {
                val committed = CountDownLatch(1)
                rule.runOnUiThread {
                    val decor = rule.activity.window.decorView
                    decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                    decor.invalidate()
                }
                check(committed.await(3, TimeUnit.SECONDS)) { "application frame was not committed" }
            }
            inst.uiAutomation.waitForIdle(300, 5000)
            val bitmap = checkNotNull(inst.uiAutomation.takeScreenshot())
            try { FileOutputStream(output.resolve("$name.png")).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
        try {
            openNovel()
            rule.waitUntil(10000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            input().performTextReplacement(text)
            rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
            input().performScrollTo().performClick()
            rule.waitUntil(10000) { rule.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            input().performTextInputSelection(TextRange(text.length))
            input().performTextInput("VISIBLE_END_$run")
            text += "VISIBLE_END_$run"
            input().assertIsDisplayed(); screenshot("landscape-keyboard")
            val imeTop = rule.activity.window.decorView.height - rule.activity.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
            check(input().fetchSemanticsNode().boundsInWindow.bottom <= imeTop + 1) { "multiline input extends behind the IME" }
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitUntil(10000) { rule.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == false }
            input().assertTextContains(text)
            rule.onAllNodesWithText("开始创作").onFirst().assertIsDisplayed()
            screenshot("landscape-keyboard-dismissed")
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
            screenshot("portrait-draft")
            rule.onNodeWithContentDescription("返回创作中心").performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithText("小说创作").fetchSemanticsNodes().isNotEmpty() }
            check(runBlocking(Dispatchers.IO) { store.load()?.premise } == text)
            openNovel()
            rule.waitUntil(10000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            input().assertTextContains(text)
            screenshot("reentered-draft")
            check(store.loadGeneration() == null)
            check(runBlocking(Dispatchers.IO) { db.configDao().get(StoryOpeningDraftCodec.KEY) } == null)
            j.addProperty("phase", "rotation-verified"); persist(j)
            output.resolve("verified.txt").writeText("realLandscape=true\nimeBackKeepsScreen=true\nportraitRetains=true\nreentryExactInput=true\nnoGeneration=true\n")
        } finally {
            rule.runOnUiThread { rule.activity.requestedOrientation = originalOrientation }
        }
    }
}

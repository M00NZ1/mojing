package com.mojing.app.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
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
import com.mojing.app.data.ChatDraftStore
import com.mojing.app.data.local.entity.SessionEntity
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

/** Real MainActivity draft input; never sends a message or changes provider settings. */
class CoreInputRotationAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("inputRun").orEmpty().also { UUID.fromString(it) }
    private val title get() = "输入${run.take(8)}"
    private val marker get() = "INPUT_ROTATION_$run"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val prefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val store get() = ChatDraftStore(context)
    private val journalFile get() = File(context.filesDir, "core-input-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "core-input-$run").apply { mkdirs() }
    private fun guard() {
        check(args.getString("inputCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "core-input-$run.pending")
        FileOutputStream(pending).use { it.write(j.toString().toByteArray()); it.fd.sync() }
        check(pending.renameTo(journalFile))
    }
    private fun journal() = JsonParser.parseString(journalFile.readText()).asJsonObject.also { check(it.get("run").asString == run) }
    private fun input() = rule.onNodeWithContentDescription("消息输入", useUnmergedTree = true)
    private fun imeVisible() = rule.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
    private fun informationVisible() = rule.onAllNodesWithText("对话信息").fetchSemanticsNodes().isNotEmpty() &&
        rule.onAllNodesWithText("对话信息").onFirst().isDisplayed()
    private fun waitForBrand() {
        rule.waitForIdle()
        rule.waitUntil(10000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
    }
    private fun openFixture() {
        rule.onNode(hasText("对话") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        rule.waitUntil(10000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(title)
        rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0) }
        rule.waitUntil(10000) { !imeVisible() }
        rule.waitUntil(10000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().size >= 2 && rule.onAllNodesWithText(title).onLast().isDisplayed() }
        rule.onAllNodesWithText(title).onLast().performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun screenshot(name: String) {
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
    private fun assertInputAndSendAboveIme() {
        val decor = rule.activity.window.decorView
        val imeTop = decor.height - decor.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
        input().assertIsDisplayed()
        val send = rule.onNodeWithContentDescription("发送", useUnmergedTree = true)
        send.assertIsDisplayed()
        for (node in listOf(input(), send)) {
            val bounds = node.fetchSemanticsNode().boundsInWindow
            check(bounds.top >= 0 && bounds.bottom <= imeTop + 1 && bounds.height > 0) { "input/send crosses real IME boundary: $bounds, imeTop=$imeTop" }
        }
    }
    @Test fun inputSurvivesRotationImeBackAndReentry() {
        guard(); check(!journalFile.exists())
        val originalOrientation = rule.activity.requestedOrientation
        val j = JsonObject().apply {
            addProperty("run", run); addProperty("phase", "snapshot"); addProperty("orientation", originalOrientation)
            // Private only: compare all other draft keys after rollback, never export their values.
            add("otherDrafts", JsonObject().apply { prefs.all.forEach { (key, value) -> check(value is String); addProperty(key, value) } })
        }
        persist(j)
        val sid = runBlocking(Dispatchers.IO) { db.withTransaction {
            val id = db.sessionDao().insert(SessionEntity(title = title, summary = marker, creationRequestId = run))
            check(!prefs.contains("session_$id"))
            j.addProperty("session", id); j.addProperty("phase", "fixture-created"); persist(j)
            id
        } }
        var text = "$marker\n" + "守望者保留未寄出的信，等待明日的约定。\n".repeat(12)
        fun rotate(requested: Int, expected: Int) {
            rule.runOnUiThread { rule.activity.requestedOrientation = requested }
            rule.waitUntil(10000) { rule.activity.resources.configuration.orientation == expected }
            rule.waitForIdle(); input().assertTextContains(text)
            check(!informationVisible()) { "closed drawer opened during rotation" }
        }
        try {
            waitForBrand(); openFixture()
            input().performTextReplacement(text)
            screenshot("portrait-before-rotation")
            rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
            screenshot("landscape-before-click")
            output.resolve("layout-before-click.txt").writeText("input=" + input().fetchSemanticsNode().boundsInWindow + "\nsend=" + rule.onNodeWithContentDescription("发送", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow + "\nime=" + imeVisible() + "\ninfo=" + rule.onAllNodesWithText("对话信息").fetchSemanticsNodes().size)
            input().performClick()
            rule.waitUntil(10000) { imeVisible() }
            input().performTextInputSelection(TextRange(text.length))
            input().performTextInput("VISIBLE_END_${run.take(8)}")
            text += "VISIBLE_END_${run.take(8)}"
            screenshot("landscape-keyboard"); assertInputAndSendAboveIme()
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitUntil(10000) { !imeVisible() }
            input().assertTextContains(text); rule.onNodeWithContentDescription("发送").assertIsDisplayed()
            screenshot("landscape-keyboard-dismissed")
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
            input().performClick(); rule.waitUntil(10000) { imeVisible() }
            input().performTextInputSelection(TextRange(text.length))
            screenshot("portrait-keyboard"); assertInputAndSendAboveIme()
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitUntil(10000) { !imeVisible() }
            input().assertTextContains(text)
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isEmpty() }
            rule.waitUntil(10000) { !RetainedChatSessions.stores.contains(sid) }
            check(store.load(sid).inputText == text)
            openFixture(); input().assertTextContains(text)
            screenshot("reentered-draft")
            // The same resize must also retain an intentionally open information panel.
            rule.onNodeWithContentDescription("会话设置与资料").performClick()
            rule.waitUntil(10000) { informationVisible() }
            rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            rule.waitUntil(10000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            rule.onNodeWithText("对话信息").assertIsDisplayed()
            screenshot("landscape-open-information")
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitUntil(10000) { !informationVisible() }
            input().assertTextContains(text)
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
            check(runBlocking(Dispatchers.IO) { db.messageDao().getNextStoryContextBatch(sid, "main", 0, 10).isEmpty() })
            check(RetainedChatSessions.running.value.isEmpty())
            j.addProperty("phase", "verified"); persist(j)
            output.resolve("verified.txt").writeText("realLandscape=true\nrealPortrait=true\nimeBackKeepsChat=true\ninputAndSendAboveIme=true\nreentryExactDraft=true\nidleOwnerReleased=true\nclosedAndOpenDrawerRetainIntent=true\nnoMessages=true\n")
        } finally { rule.runOnUiThread { rule.activity.requestedOrientation = originalOrientation } }
    }
    @Test fun rollbackOnlyOwnedSessionDraft() {
        guard(); val j = journal(); waitForBrand()
        val sid = j.get("session")?.asLong
        if (sid != null) {
            runBlocking(Dispatchers.IO) { db.withTransaction {
                val session = db.sessionDao().getById(sid)
                check(session == null || (session.summary == marker && session.creationRequestId == run))
                if (session != null) db.sessionDao().delete(sid)
            } }
            check(prefs.edit().remove("session_$sid").commit())
            check(!prefs.contains("session_$sid"))
            check(runBlocking(Dispatchers.IO) { db.sessionDao().getById(sid) } == null)
        }
        val original = j.getAsJsonObject("otherDrafts")
        check(prefs.all.keys == original.keySet()) { "unrelated draft key set changed" }
        original.entrySet().forEach { (key, value) -> check(prefs.getString(key, null) == value.asString) { "unrelated draft changed" } }
        rule.runOnUiThread { rule.activity.requestedOrientation = j.get("orientation").asInt }
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("ownedSessionAndDraftRemoved=true\notherDraftsExact=true\norientationRestored=true\nprivateJournalRetained=true\n")
    }
}

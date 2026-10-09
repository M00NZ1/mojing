package com.mojing.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.room.withTransaction
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.MainActivity
import com.mojing.app.data.ChatDraftSnapshot
import com.mojing.app.data.ChatDraftStore
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.media.NativeSpeechRecognizer
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real implicit recognizer Activity/result and complete chat UI; no voice/network inference. */
@RunWith(AndroidJUnit4::class)
class SpeechInputAppCaptureTest {
    val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val database get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private lateinit var fixture: Fixture
    private var launcherIntent: Intent? = null
    private var storage: SecureStorage? = null
    private var originalTheme: String? = null

    private val setup = object : org.junit.rules.ExternalResource() {
        override fun before() {
            assumeTrue("speechInputAppCapture=true required", args.getString("speechInputAppCapture") == "true")
            assumeTrue("generic emulator only", Build.FINGERPRINT.contains("generic", true) || Build.MODEL.startsWith("sdk_gphone", true) || Build.DEVICE.contains("emulator", true))
            assumeTrue("root must prepare and restore microphone grant", context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            args.getString("captureTheme")?.let { theme ->
                storage = SecureStorage().also { it.init(context) }
                originalTheme = storage?.themeMode
                storage?.themeMode = theme
            }
        }
        override fun after() {
            originalTheme?.let { storage?.themeMode = it }
            if (::fixture.isInitialized) {
                ChatDraftStore(context).save(fixture.sessionId, ChatDraftSnapshot())
                runBlocking(Dispatchers.IO) {
                    database.withTransaction {
                        database.sessionDao().delete(fixture.sessionId)
                        database.characterDao().delete(fixture.characterId)
                    }
                }
            }
        }
    }

    @get:Rule val orderedRules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(setup).around(rule)

    @Before fun prepareFixture() {
        launcherIntent = Intent(rule.activity.intent)
        fixture = runBlocking(Dispatchers.IO) {
            database.withTransaction {
                val suffix = UUID.randomUUID().toString().take(8)
                val title = "语音输入故事 · $suffix"
                val character = database.characterDao().upsert(CharacterEntity(name = "语音角色 · $suffix", personaPrompt = "本地语音验收"))
                val session = database.sessionDao().insert(SessionEntity(title = title))
                database.participantDao().upsert(SessionParticipantEntity(sessionId = session, characterId = character))
                database.messageDao().insert(MessageEntity(sessionId = session, speakerType = "character", characterId = character, content = "潮声还在窗外。"))
                Fixture(session, character, title)
            }
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
        assertTrue("real recognition handler must be discoverable", NativeSpeechRecognizer.isSpeechRecognitionResolvable(context))
        val handlers = context.packageManager.queryIntentActivities(NativeSpeechRecognizer.createIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue("test-only recognizer handler is installed", handlers.any { it.activityInfo.name == "com.mojing.app.test.AcceptanceRecognitionActivity" })
        val resolved = context.packageManager.resolveActivity(NativeSpeechRecognizer.createIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue("must resolve to test recognizer or chooser, never a configured real recognizer",
            resolved?.activityInfo?.name in setOf("com.mojing.app.test.AcceptanceRecognitionActivity", "com.android.internal.app.ResolverActivity"))
        openChat()
    }

    @After fun restoreHarnessIntent() {
        launcherIntent?.let { intent -> val activity = rule.activity; rule.runOnUiThread { activity.setIntent(Intent(intent)) } }
    }

    @Test fun realSpeechResultsRespectCursorSelectionRecreateCancelAndDraftReentry() {
        data class Case(val name: String, val source: String, val selection: TextRange, val expected: String, val cursor: Int)
        val cases = listOf(
            Case("start", "甲乙", TextRange(0), "丙甲乙", 1),
            Case("middle", "甲乙", TextRange(1), "甲丙乙", 2),
            Case("end", "甲乙", TextRange(2), "甲乙丙", 3),
            Case("selection", "甲乙丁", TextRange(1, 3), "甲丙", 2),
            Case("reverse", "甲乙丁", TextRange(3, 1), "甲丙", 2),
            Case("empty-draft", "", TextRange(0), "丙", 1),
        )
        cases.forEach { case ->
            println("Speech input case: ${case.name}")
            setInput(case.source, case.selection)
            launchSpeech()
            clickRecognizer("acceptance-recognition-return")
            assertInput(case.expected, TextRange(case.cursor))
            capture("speech-input-${case.name}")
        }

        setInput("甲乙丁", TextRange(3, 1))
        val oldActivity = rule.activity
        launchSpeech()
        instrumentation.runOnMainSync { oldActivity.recreate() }
        val recreateDeadline = SystemClock.uptimeMillis() + 10_000
        while (!oldActivity.isDestroyed && SystemClock.uptimeMillis() < recreateDeadline) SystemClock.sleep(20)
        assertTrue("platform Activity was actually destroyed", oldActivity.isDestroyed)
        clickRecognizer("acceptance-recognition-return")
        assertInput("甲丙", TextRange(2))
        capture("speech-input-recreate-selected-range")

        setInput("保留输入", TextRange(1, 3))
        launchSpeech()
        clickRecognizer("acceptance-recognition-cancel")
        assertInput("保留输入", TextRange(1, 3))
        capture("speech-input-canceled")
        launchSpeech()
        clickRecognizer("acceptance-recognition-empty")
        assertInput("保留输入", TextRange(1, 3))
        capture("speech-input-empty-result")
        launchSpeech()
        clickRecognizer("acceptance-recognition-return")
        assertInput("保丙入", TextRange(2))
        assertEquals("real persisted draft follows input", "保丙入", ChatDraftStore(context).load(fixture.sessionId).inputText)
        leaveChat()
        openChat()
        input().assertTextContains("保丙入")
        capture("speech-input-draft-reentry")
        leaveChat()
    }

    @Test fun sharedInputToolsReplaceSelectedTextWithMacroAndEmoji() {
        setInput("甲乙丁", TextRange(1, 3))
        openTools()
        rule.onNodeWithText("快捷词").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        rule.onNodeWithText("你的名字").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        assertInput("甲{{user}}", TextRange("甲{{user}}".length))
        hideKeyboardIfVisible()
        capture("speech-input-macro-selection")

        setInput("甲乙丁", TextRange(3, 1))
        openTools()
        rule.onNodeWithText("表情").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        rule.onNodeWithText("😀").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        assertInput("甲😀", TextRange("甲😀".length))
        assertEquals("shared tools persist only the replacement", "甲😀", ChatDraftStore(context).load(fixture.sessionId).inputText)
        capture("speech-input-emoji-selection")
        leaveChat()
    }

    private fun input() = rule.onAllNodes(hasSetTextAction() and (hasContentDescription("消息输入") or hasAnyAncestor(hasContentDescription("消息输入"))), useUnmergedTree = true).onFirst()

    private fun setInput(text: String, range: TextRange) {
        input().performTextReplacement(text)
        input().performSemanticsAction(SemanticsActions.SetSelection) { it(range.start, range.end, false) }
        assertInput(text, range)
        hideKeyboardIfVisible()
        assertInput(text, range)
    }

    private fun hideKeyboardIfVisible() {
        val activity = rule.activity
        var visible = false
        rule.runOnUiThread {
            activity.window.decorView.rootWindowInsets?.let {
                visible = WindowInsetsCompat.toWindowInsetsCompat(it).isVisible(WindowInsetsCompat.Type.ime())
            }
        }
        if (visible) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private fun assertInput(text: String, range: TextRange) {
        try {
            rule.waitUntil(10_000) {
                runCatching {
                    input().assertTextContains(text)
                    assertEquals(text, input().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
                    assertEquals(range, input().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
                    true
                }.getOrDefault(false)
            }
        } catch (failure: Throwable) {
            capture("speech-input-unexpected-value")
            val observed = runCatching { input().fetchSemanticsNode().config.let { "${it[SemanticsProperties.EditableText].text}/${it[SemanticsProperties.TextSelectionRange]}" } }.getOrNull()
            throw AssertionError("expected $text/$range, actual $observed; fixture draft ${ChatDraftStore(context).load(fixture.sessionId).inputText}", failure)
        }
    }

    private fun openTools() {
        val selection = input().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        println("Speech input before tools: $selection")
        rule.onNodeWithContentDescription("更多输入工具").performClick()
        rule.onNodeWithText("输入工具").assertIsDisplayed()
        val after = input().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        println("Speech input after tools: $after")
        assertEquals("opening input tools preserves the insertion target", selection, after)
    }

    private fun launchSpeech() {
        openTools()
        rule.onNodeWithText("语音输入").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        awaitRecognizer("acceptance-recognition-return")
    }

    private fun awaitRecognizer(description: String): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
            return null
        }
        fun click(node: AccessibilityNodeInfo): Boolean {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        val deadline = SystemClock.uptimeMillis() + 10_000
        var selected = false
        var onceClicked = false
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            find(root) { it.contentDescription?.toString() == description }?.let { return it }
            if (!selected) {
                find(root) { it.text?.toString()?.let { text -> text.contains("com.mojing.app.test") && text.startsWith("Complete action using") } == true }
                    ?.let { selected = true }
            }
            if (!selected) {
                find(root) { it.text?.toString() == "本地识别验收" }?.let { selected = click(it) }
                    ?: find(root) { it.text?.toString() == "com.mojing.app.test" }?.let { selected = click(it) }
            }
            if (selected && !onceClicked) find(root) {
                it.viewIdResourceName?.endsWith("button_once") == true ||
                    it.text?.toString()?.lowercase() in setOf("just once", "仅此一次", "仅限一次")
            }?.let { onceClicked = click(it) }
            SystemClock.sleep(20)
        }
        capture("speech-input-unexpected-recognizer")
        error("recognizer action not displayed: $description")
    }

    private fun clickRecognizer(description: String) {
        assertTrue(awaitRecognizer(description).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        rule.waitForIdle()
    }

    private fun openChat() {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mojing://chat")).apply {
            setClass(context, MainActivity::class.java)
            putExtra("navigate_to", "chat"); putExtra("session_id", fixture.sessionId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithText(fixture.title).assertIsDisplayed(); input().assertIsDisplayed(); true }.getOrDefault(false) }
        val activity = rule.activity
        assertTrue("navigation intent consumed", activity.intent.data == null && !activity.intent.hasExtra("session_id"))
        launcherIntent?.let { intent -> rule.runOnUiThread { activity.setIntent(Intent(intent)) } }
    }

    private fun leaveChat() {
        rule.onNodeWithContentDescription("返回会话主页").performClick()
        rule.waitUntil(10_000) { runCatching { rule.onAllNodesWithText("故事库").onFirst().assertIsDisplayed(); true }.getOrDefault(false) }
    }

    private fun capture(name: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText("输入工具").fetchSemanticsNodes().isEmpty() }
        val settled = java.util.concurrent.CountDownLatch(1)
        val activity = rule.activity
        rule.runOnUiThread {
            activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { settled.countDown() }
            activity.window.decorView.invalidate()
        }
        assertTrue("capture follows a committed application frame", settled.await(5, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        val runName = args.getString("captureRun") ?: "speech-input-app-20261006"
        require(runName.matches(Regex("[A-Za-z0-9._-]+")))
        val directory = File(context.getExternalFilesDir(null), runName).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { out -> assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, out)) }
    }

    private data class Fixture(val sessionId: Long, val characterId: Long, val title: String)
}

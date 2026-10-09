package com.mojing.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.MainActivity
import com.mojing.app.data.VoiceChoice
import com.mojing.app.data.VoicePreferences
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in complete-app proof for the test-only local Android TTS engine. This
 * covers app controls and input retention; it does not prove vendor speech.
 */
@RunWith(AndroidJUnit4::class)
class SpeechPlaybackAppCaptureTest {
    val rule = createAndroidComposeRule<MainActivity>()

    private val themeRule = object : org.junit.rules.ExternalResource() {
        override fun before() {
            assumeTrue("speechPlaybackAppCapture=true is required", args.getString("speechPlaybackAppCapture") == "true")
            assumeTrue("speech playback app capture is restricted to a generic emulator", isGenericEmulator())
            args.getString("captureTheme")?.let { theme ->
                val storage = com.mojing.app.data.SecureStorage().also { it.init(targetContext) }
                capturedStorage = storage
                originalTheme = storage.themeMode
                storage.themeMode = theme
            }
        }

        override fun after() {
            originalTheme?.let { capturedStorage?.themeMode = it }
        }
    }

    @get:Rule
    val orderedRules: org.junit.rules.TestRule = org.junit.rules.RuleChain
        .outerRule(themeRule)
        .around(rule)

    private val args get() = InstrumentationRegistry.getArguments()
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val database get() = EntryPointAccessors.fromApplication(
        targetContext.applicationContext,
        PrototypeDatabaseEntryPoint::class.java,
    ).database()
    private lateinit var fixture: Fixture
    private var originalLaunchIntent: Intent? = null
    private var originalTheme: String? = null
    private var capturedStorage: com.mojing.app.data.SecureStorage? = null
    private val inputText = "本地测试引擎暂停继续并保留输入。".repeat(500)

    @Before
    fun requireExplicitGenericCaptureAndSeed() {
        assumeTrue("speechPlaybackAppCapture=true is required", args.getString("speechPlaybackAppCapture") == "true")
        assumeTrue("speech playback app capture is restricted to a generic emulator", isGenericEmulator())
        originalLaunchIntent = Intent(rule.activity.intent)
        fixture = seedFixture()
        VoicePreferences(targetContext).saveSession(
            fixture.sessionId,
            VoiceChoice("android:com.mojing.app.test", ""),
        )
        dismissSplash()
        openChat()
    }

    @After
    fun removeOnlyOwnedState() {
        restoreHarnessLaunchIntent()
        if (::fixture.isInitialized) {
            VoicePreferences(targetContext).saveSession(fixture.sessionId, VoiceChoice("inherit"))
            runBlocking(Dispatchers.IO) {
                database.withTransaction {
                    database.sessionDao().delete(fixture.sessionId)
                    database.characterDao().delete(fixture.characterId)
                }
            }
        }
    }

    @Test
    fun realLocalEnginePausesResumesStopsAndDoesNotReplayOnReentry() {
        val input = inputNode()
        input.performTextReplacement(inputText)
        input.assertTextContains(inputText)

        openInputToolsAndPreview()
        awaitSpeechState("正在朗读")
        awaitSegmentPosition()
        capture("speech-playback-playing")

        rule.onNodeWithContentDescription("暂停朗读").performClick()
        awaitSpeechState("已暂停")
        awaitSegmentPosition()
        input.assertTextContains(inputText)
        capture("speech-playback-paused")

        rule.onNodeWithContentDescription("继续朗读").performClick()
        awaitSpeechState("正在朗读")
        awaitSegmentPosition()
        capture("speech-playback-resumed")

        rule.onNodeWithContentDescription("停止朗读").performClick()
        awaitNoSpeechBar()
        input.assertTextContains(inputText)
        capture("speech-playback-stopped-input-retained")

        rule.onNodeWithContentDescription("返回会话主页").performClick()
        awaitText("故事库")
        openChat()
        awaitNoSpeechBar()
        inputNode().assertTextContains(inputText)
        capture("speech-playback-reenter-no-autoplay")
    }

    private fun openInputToolsAndPreview() {
        rule.onNodeWithContentDescription("更多输入工具").performClick()
        awaitText("输入工具")
        rule.onNodeWithText("试听朗读").performClick()
    }

    private fun inputNode() = rule.onAllNodes(
        hasSetTextAction() and (
            hasContentDescription("消息输入") or hasAnyAncestor(hasContentDescription("消息输入"))
        ),
        useUnmergedTree = true,
    ).onFirst()

    private fun openChat() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("mojing://chat")).apply {
            setClass(targetContext, MainActivity::class.java)
            putExtra("navigate_to", "chat")
            putExtra("session_id", fixture.sessionId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        targetContext.startActivity(intent)
        rule.waitForIdle()
        awaitText(fixture.sessionTitle)
        awaitDisplayedContentDescription("消息输入")
        assertExternalIntentConsumed()
        restoreHarnessLaunchIntent()
    }

    private fun dismissSplash() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private fun awaitSpeechState(text: String) {
        rule.waitUntil(15_000) {
            runCatching { rule.onNodeWithText(text, substring = false).assertIsDisplayed(); true }
                .getOrDefault(false)
        }
    }

    private fun awaitSegmentPosition() {
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("第1/", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitNoSpeechBar() {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("正在朗读", substring = false).fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodesWithText("已暂停", substring = false).fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodesWithText("准备朗读", substring = false).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun awaitText(text: String) {
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithText(text, substring = false).assertIsDisplayed(); true }
                .getOrDefault(false)
        }
    }

    private fun awaitDisplayedContentDescription(description: String) {
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithContentDescription(description).assertIsDisplayed(); true }
                .getOrDefault(false)
        }
    }

    private fun assertExternalIntentConsumed() {
        val intent = rule.activity.intent
        check(intent.data == null) { "external navigation data was not consumed: ${intent.data}" }
        check(!intent.hasExtra("navigate_to")) { "navigate_to extra was not consumed" }
        check(!intent.hasExtra("session_id")) { "session_id extra was not consumed" }
    }

    private fun restoreHarnessLaunchIntent() {
        val launchIntent = originalLaunchIntent ?: return
        val activity = rule.activity
        rule.runOnUiThread { activity.setIntent(Intent(launchIntent)) }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000)
        val directory = File(
            targetContext.getExternalFilesDir(null),
            args.getString("captureRun") ?: "speech-playback-app-20261006",
        ).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(
                Bitmap.CompressFormat.PNG, 100, output,
            )) { "screenshot encoding failed for $name" }
        }
    }

    private fun isGenericEmulator(): Boolean = Build.FINGERPRINT.contains("generic", true) ||
        Build.MODEL.startsWith("sdk_gphone", true) || Build.DEVICE.contains("emulator", true)

    private fun seedFixture(): Fixture = runBlocking(Dispatchers.IO) {
        val suffix = UUID.randomUUID().toString().take(8)
        database.withTransaction {
            val characterId = database.characterDao().upsert(
                CharacterEntity(name = "本地引擎角色 · $suffix", personaPrompt = "本地引擎试听角色"),
            )
            val sessionId = database.sessionDao().insert(SessionEntity(title = "本地引擎故事 · $suffix"))
            database.participantDao().upsert(SessionParticipantEntity(sessionId = sessionId, characterId = characterId))
            database.messageDao().insert(MessageEntity(
                sessionId = sessionId,
                speakerType = "narrator",
                content = "本地引擎试听历史消息",
            ))
            Fixture(sessionId, characterId, "本地引擎故事 · $suffix")
        }
    }

    private data class Fixture(val sessionId: Long, val characterId: Long, val sessionTitle: String)
}

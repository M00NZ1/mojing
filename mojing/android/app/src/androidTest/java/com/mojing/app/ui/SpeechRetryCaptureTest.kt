package com.mojing.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
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
 * Opt-in complete-app speech failure capture. It exercises the real input
 * preview path with an intentionally missing Android TTS package. The test
 * proves the error and repeatable retry path; it does not claim audio output.
 */
@RunWith(AndroidJUnit4::class)
class SpeechRetryCaptureTest {
    val rule = createAndroidComposeRule<MainActivity>()

    private val themeRule = object : org.junit.rules.ExternalResource() {
        override fun before() {
            assumeTrue("speechRetryCapture=true is required", args.getString("speechRetryCapture") == "true")
            assumeTrue("speech retry capture is restricted to a generic emulator", isGenericEmulator())
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
    private val database get() = dagger.hilt.android.EntryPointAccessors
        .fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java)
        .database()
    private lateinit var fixture: Fixture
    private val inputText = "试听失败后仍保留的输入 · ${UUID.randomUUID().toString().take(6)}"
    private var originalLaunchIntent: Intent? = null
    private var originalTheme: String? = null
    private var capturedStorage: com.mojing.app.data.SecureStorage? = null

    @Before
    fun requireExplicitGenericCaptureAndSeed() {
        assumeTrue("speechRetryCapture=true is required", args.getString("speechRetryCapture") == "true")
        assumeTrue("speech retry capture is restricted to a generic emulator", isGenericEmulator())
        originalLaunchIntent = Intent(rule.activity.intent)
        fixture = seedFixture()
        VoicePreferences(targetContext).saveSession(
            fixture.sessionId,
            VoiceChoice("android:com.mojing.acceptance.missing.tts"),
        )
        dismissSplash()
        openChat()
    }

    @After
    fun removeOnlyOwnedState() {
        restoreHarnessLaunchIntent()
        // The fixture ID is unique, so inherit removes only the two keys this
        // test wrote and leaves all other voice preference records untouched.
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
    fun missingAndroidEngineRetriesSameFailureAndReentryDoesNotReplay() {
        val input = inputNode()
        input.performTextReplacement(inputText)
        input.assertTextContains(inputText)

        openInputToolsAndPreview()
        awaitText(ERROR)
        awaitRetryAction()
        capture("speech-retry-first-failure")

        rule.onNodeWithText("重试").performClick()
        awaitText(ERROR)
        awaitRetryAction()
        capture("speech-retry-second-failure")
        input.assertTextContains(inputText)

        rule.onNodeWithText("重试").performClick()
        awaitText(ERROR)
        awaitRetryAction()
        capture("speech-retry-third-failure-small-layout")
        input.assertTextContains(inputText)

        rule.onNodeWithContentDescription("返回会话主页").performClick()
        awaitText("故事库")
        openChat()
        awaitNoText(ERROR)
        awaitNoText("重试")
        inputNode().assertTextContains(inputText)
        capture("speech-retry-reenter-no-autoplay")
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

    private fun awaitText(text: String) {
        try {
            rule.waitUntil(10_000) {
                runCatching { rule.onNodeWithText(text, substring = false).assertIsDisplayed(); true }
                    .getOrDefault(false)
            }
        } catch (failure: Throwable) {
            capture("speech-retry-unexpected-state")
            println(rule.onRoot(useUnmergedTree = true).printToString())
            throw failure
        }
    }

    private fun awaitRetryAction() {
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithText("重试", substring = false).assertIsDisplayed(); true }
                .getOrDefault(false)
        }
    }

    private fun awaitNoText(text: String) {
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun assertExternalIntentConsumed() {
        val intent = rule.activity.intent
        check(intent.data == null) { "external navigation data was not consumed: ${intent.data}" }
        check(!intent.hasExtra("navigate_to")) { "navigate_to extra was not consumed" }
        check(!intent.hasExtra("session_id")) { "session_id extra was not consumed" }
    }

    /** Restore the ActivityScenario harness intent after real external delivery. */
    private fun restoreHarnessLaunchIntent() {
        val launchIntent = originalLaunchIntent ?: return
        val activity = rule.activity
        rule.runOnUiThread { activity.setIntent(Intent(launchIntent)) }
    }

    private fun awaitDisplayedContentDescription(description: String) {
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithContentDescription(description).assertIsDisplayed(); true }
                .getOrDefault(false)
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000)
        val directory = File(
            targetContext.getExternalFilesDir(null),
            args.getString("captureRun") ?: "speech-retry-20261006",
        ).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(
                android.graphics.Bitmap.CompressFormat.PNG, 100, output,
            )) { "screenshot encoding failed for $name" }
        }
    }

    private fun isGenericEmulator(): Boolean = Build.FINGERPRINT.contains("generic", true) ||
        Build.MODEL.startsWith("sdk_gphone", true) || Build.DEVICE.contains("emulator", true)

    private fun seedFixture(): Fixture = runBlocking(Dispatchers.IO) {
        val suffix = UUID.randomUUID().toString().take(8)
        database.withTransaction {
            val characterId = database.characterDao().upsert(
                CharacterEntity(name = "试听角色 · $suffix", personaPrompt = "试听失败验收角色"),
            )
            val sessionId = database.sessionDao().insert(SessionEntity(title = "试听失败故事 · $suffix"))
            database.participantDao().upsert(SessionParticipantEntity(sessionId = sessionId, characterId = characterId))
            database.messageDao().insert(MessageEntity(
                sessionId = sessionId,
                speakerType = "narrator",
                content = "试听失败验收历史消息",
            ))
            Fixture(sessionId, characterId, "试听失败故事 · $suffix")
        }
    }

    private data class Fixture(val sessionId: Long, val characterId: Long, val sessionTitle: String)

    private companion object {
        const val ERROR = "指定的系统朗读引擎未安装或不可用，请重新选择"
    }
}

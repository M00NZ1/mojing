package com.mojing.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.MainActivity
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in complete-app evidence for persisted reader preferences. This proves
 * local UI preferences survive Activity recreation and are consumed by the
 * real reader; it does not exercise a large-data or accessibility run.
 */
@RunWith(AndroidJUnit4::class)
class ReadingPreferencesAppCaptureTest {
    val rule = createAndroidComposeRule<MainActivity>()

    private val args get() = InstrumentationRegistry.getArguments()
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val database get() = EntryPointAccessors
        .fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java)
        .database()
    private val preferences by lazy { UiPreferencesRepository(targetContext) }
    private lateinit var fixture: Fixture
    private var originalLaunchIntent: Intent? = null
    private var oldDensity: String? = null
    private var oldFont: String? = null
    private var oldNarratorItalic: Boolean? = null
    private var originalTheme: String? = null
    private var capturedStorage: SecureStorage? = null

    private val themeRule = object : org.junit.rules.ExternalResource() {
        override fun before() {
            assumeCaptureEnabled()
            args.getString("captureTheme")?.let { theme ->
                capturedStorage = SecureStorage().also { it.init(targetContext) }
                originalTheme = capturedStorage?.themeMode
                capturedStorage?.themeMode = theme
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

    @Before
    fun seedFixtureAndBackUpPreferences() {
        assumeCaptureEnabled()
        originalLaunchIntent = Intent(rule.activity.intent)
        runBlocking(Dispatchers.IO) {
            oldDensity = preferences.chatDensity.first()
            oldFont = preferences.chatFont.first()
            oldNarratorItalic = preferences.narratorItalic.first()
            preferences.setChatFont("system")
            preferences.setNarratorItalic(false)
            preferences.setChatDensity("comfortable")
        }
        fixture = seedFixture()
        dismissSplash()
        openChat()
        rule.onNodeWithContentDescription("返回会话主页").performClick()
        awaitDisplayed("故事库")
    }

    @After
    fun restorePreferencesAndRemoveFixture() {
        restoreHarnessLaunchIntent()
        runBlocking(Dispatchers.IO) {
            oldDensity?.let { preferences.setChatDensity(it) }
            oldFont?.let { preferences.setChatFont(it) }
            oldNarratorItalic?.let { preferences.setNarratorItalic(it) }
            if (::fixture.isInitialized) {
                database.withTransaction {
                    database.sessionDao().delete(fixture.sessionId)
                    database.characterDao().delete(fixture.characterId)
                }
            }
        }
    }

    @Test
    fun readerPreferencesPersistAcrossRecreateAndChangeAgain() {
        openAppearance()
        rule.onNodeWithText("阅读", substring = false).performScrollTo().assertIsEnabled().performClick()
        awaitPreference { preferences.chatDensity.first() == "reader" }
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithText("阅读", substring = false).assertIsSelected(); true }.getOrDefault(false) }
        rule.onNodeWithText("等宽", substring = false).performScrollTo().assertIsEnabled().performClick()
        awaitPreference { preferences.chatFont.first() == "mono" }
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithText("等宽", substring = false).assertIsSelected(); true }.getOrDefault(false) }
        val narratorSwitch = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        narratorSwitch.performScrollTo().assertIsEnabled().performClick()
        awaitPreference { preferences.narratorItalic.first() }
        rule.waitUntil(10_000) { runCatching { narratorSwitch.assertIsOn(); true }.getOrDefault(false) }
        capture("reading-preferences-mono-italic-settings")

        returnToFixtureChat()
        openReader()
        assertReaderStyles(FontFamily.Monospace, narratorItalic = true)
        capture("reading-preferences-mono-italic-reader")

        val activity = rule.activity
        rule.runOnUiThread { activity.recreate() }
        rule.waitUntil(10_000) { activity.isDestroyed }
        rule.waitForIdle()
        awaitDisplayed(fixture.title)
        awaitDescription("退出阅读模式")
        runBlocking(Dispatchers.IO) {
            org.junit.Assert.assertEquals("reader", preferences.chatDensity.first())
            org.junit.Assert.assertEquals("mono", preferences.chatFont.first())
            org.junit.Assert.assertTrue(preferences.narratorItalic.first())
        }
        assertReaderStyles(FontFamily.Monospace, narratorItalic = true)
        capture("reading-preferences-after-recreate")

        leaveReaderAndOpenAppearance()
        rule.onNodeWithText("无衬线", substring = false).performScrollTo().assertIsEnabled().performClick()
        awaitPreference { preferences.chatFont.first() == "sans" }
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithText("无衬线", substring = false).assertIsSelected(); true }.getOrDefault(false) }
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performScrollTo().assertIsEnabled().performClick()
        awaitPreference { !preferences.narratorItalic.first() }
        rule.waitUntil(10_000) { runCatching { rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOff(); true }.getOrDefault(false) }
        capture("reading-preferences-sans-normal-settings")

        returnToFixtureChat()
        openReader()
        assertReaderStyles(FontFamily.SansSerif, narratorItalic = false)
        capture("reading-preferences-sans-normal-reader")
    }

    private fun openAppearance() {
        clickBottom("设置")
        awaitDisplayed("设置")
        rule.onNodeWithText("个性化", substring = false).performClick()
        awaitDisplayed("我的资料")
        rule.onNodeWithText("外观", substring = false).performClick()
        rule.onNodeWithText("阅读显示", substring = false).performScrollTo().assertIsDisplayed()
    }

    private fun leaveReaderAndOpenAppearance() {
        rule.onNodeWithContentDescription("退出阅读模式").performClick()
        rule.onNodeWithContentDescription("返回会话主页").performClick()
        awaitDisplayed("故事库")
        openAppearance()
    }

    private fun returnToFixtureChat() {
        rule.onNodeWithContentDescription("返回设置").performClick()
        awaitDisplayed("个性化")
        clickBottom("对话")
        openChat()
    }

    private fun openReader() {
        awaitDescription("阅读模式")
        rule.onNodeWithContentDescription("阅读模式").performClick()
        awaitDescription("退出阅读模式")
        awaitDisplayed(fixture.chapterTitle)
    }

    private fun assertReaderStyles(font: FontFamily, narratorItalic: Boolean) {
        assertTextStyle(fixture.chapterTitle, font, FontStyle.Normal)
        assertTextStyle(fixture.narratorParagraph, font, if (narratorItalic) FontStyle.Italic else FontStyle.Normal)
        assertTextStyle(fixture.characterParagraph, font, FontStyle.Normal)
    }

    private fun assertTextStyle(text: String, family: FontFamily, style: FontStyle) {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        org.junit.Assert.assertEquals("font family for $text", family, layouts.single().layoutInput.style.fontFamily)
        org.junit.Assert.assertEquals("font style for $text", style, layouts.single().layoutInput.style.fontStyle ?: FontStyle.Normal)
    }

    private fun awaitPreference(predicate: suspend () -> Boolean) {
        rule.waitUntil(10_000) { runBlocking(Dispatchers.IO) { predicate() } }
    }

    private fun clickBottom(label: String) {
        awaitDisplayed(label)
        rule.onAllNodesWithText(label).filter(hasClickAction()).onLast().performClick()
        rule.waitForIdle()
    }

    private fun awaitDisplayed(text: String) {
        rule.waitUntil(10_000) {
            runCatching { rule.onAllNodesWithText(text, substring = false).onFirst().assertIsDisplayed(); true }
                .getOrDefault(false)
        }
    }

    private fun awaitDescription(description: String) {
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithContentDescription(description).assertIsDisplayed(); true }
                .getOrDefault(false)
        }
    }

    private fun openChat() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("mojing://chat")).apply {
            setClass(targetContext, MainActivity::class.java)
            putExtra("navigate_to", "chat")
            putExtra("session_id", fixture.sessionId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        targetContext.startActivity(intent)
        rule.waitForIdle()
        awaitDisplayed(fixture.title)
        awaitDescription("消息输入")
        check(rule.activity.intent.data == null) { "external navigation data was not consumed" }
        check(!rule.activity.intent.hasExtra("navigate_to")) { "navigate_to extra was not consumed" }
        check(!rule.activity.intent.hasExtra("session_id")) { "session_id extra was not consumed" }
        restoreHarnessLaunchIntent()
    }

    private fun dismissSplash() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private fun restoreHarnessLaunchIntent() {
        val launchIntent = originalLaunchIntent ?: return
        val activity = rule.activity
        rule.runOnUiThread { activity.setIntent(Intent(launchIntent)) }
    }

    private fun seedFixture(): Fixture = runBlocking(Dispatchers.IO) {
        database.withTransaction {
            val suffix = UUID.randomUUID().toString().take(8)
            val title = "阅读设置故事 · $suffix"
            val chapter = "第一章"
            val narrator = "潮声在窗外记录这一页。"
            val character = "你终于回来了。"
            val characterId = database.characterDao().upsert(
                CharacterEntity(name = "阅读角色 · $suffix", personaPrompt = "阅读设置验收角色"),
            )
            val sessionId = database.sessionDao().insert(SessionEntity(title = title))
            database.participantDao().upsert(SessionParticipantEntity(sessionId = sessionId, characterId = characterId))
            database.messageDao().insert(MessageEntity(
                sessionId = sessionId,
                speakerType = "narrator",
                content = "$chapter\n\n$narrator",
            ))
            database.messageDao().insert(MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                characterId = characterId,
                content = character,
            ))
            Fixture(sessionId, characterId, title, chapter, narrator, character)
        }
    }

    private fun capture(name: String) {
        val runName = args.getString("captureRun") ?: "reading-preferences-app-20261006"
        require(runName.matches(Regex("[A-Za-z0-9._-]+"))) { "unsafe capture run name" }
        val directory = File(targetContext.getExternalFilesDir(null), runName).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, output)) { "screenshot encoding failed for $name" }
        }
    }

    private fun assumeCaptureEnabled() {
        assumeTrue("readingPreferencesAppCapture=true is required", args.getString("readingPreferencesAppCapture") == "true")
        val generic = Build.FINGERPRINT.contains("generic", true) ||
            Build.MODEL.startsWith("sdk_gphone", true) || Build.DEVICE.contains("emulator", true)
        assumeTrue("reading preferences capture is restricted to a generic emulator", generic)
    }

    private data class Fixture(
        val sessionId: Long,
        val characterId: Long,
        val title: String,
        val chapterTitle: String,
        val narratorParagraph: String,
        val characterParagraph: String,
    )
}

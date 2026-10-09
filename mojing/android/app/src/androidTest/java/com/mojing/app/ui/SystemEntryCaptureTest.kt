package com.mojing.app.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.MainActivity
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.service.NotificationHelper
import dagger.hilt.android.EntryPointAccessors
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
 * Opt-in complete-app system-entry acceptance. This test sends the app's own
 * PendingIntent and shortcut URI intents; it does not claim a physical
 * notification shade or launcher click was performed.
 */
@RunWith(AndroidJUnit4::class)
class SystemEntryCaptureTest {
    val rule = createAndroidComposeRule<MainActivity>()

    private val themeRule = object : org.junit.rules.ExternalResource() {
        override fun before() {
            assumeTrue("systemEntryCapture=true is required", args.getString("systemEntryCapture") == "true")
            assumeTrue("system entry capture is restricted to a generic emulator", isGenericEmulator())
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
    private val database get() = EntryPointAccessors
        .fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java)
        .database()
    private lateinit var fixture: Fixture
    private val ownedNotificationIds = linkedSetOf<Int>()
    private var originalLaunchIntent: Intent? = null
    private var originalTheme: String? = null
    private var capturedStorage: com.mojing.app.data.SecureStorage? = null

    @Before
    fun requireExplicitGenericCapture() {
        assumeTrue("systemEntryCapture=true is required", args.getString("systemEntryCapture") == "true")
        assumeTrue("system entry capture is restricted to a generic emulator", isGenericEmulator())
        originalLaunchIntent = Intent(rule.activity.intent)
        fixture = seedFixture()
        dismissSplash()
    }

    @After
    fun removeOnlyOwnedState() {
        restoreHarnessLaunchIntent()
        runCatching {
            val manager = targetContext.getSystemService(NotificationManager::class.java)
            ownedNotificationIds.forEach(manager!!::cancel)
        }
        if (::fixture.isInitialized) runBlocking(Dispatchers.IO) {
            database.withTransaction {
                database.sessionDao().delete(fixture.sessionId)
                database.characterDao().delete(fixture.characterId)
            }
        }
    }

    @Test
    fun notificationPendingIntentIsIdempotentAndRecreateDoesNotDuplicateSession() {
        requireNotificationPermission()
        val helper = NotificationHelper(targetContext.applicationContext)
        val notificationId = fixture.sessionId.toInt()
        ownedNotificationIds += notificationId
        helper.showGenerationComplete(fixture.sessionId, fixture.characterName, "系统入口验收")
        val notification = awaitNotification(notificationId)
        notification.contentIntent.send()
        awaitText(fixture.sessionTitle)
        rule.onNodeWithText(fixture.sessionTitle).assertIsDisplayed()
        assertExternalIntentConsumed()
        capture("system-notification-chat")

        // A second PendingIntent.send models duplicate delivery through the
        // same notification; it must not create another session or stack chat.
        notification.contentIntent.send()
        awaitText(fixture.sessionTitle)
        assertExternalIntentConsumed()
        restoreHarnessLaunchIntent()
        recreateActualActivity()
        awaitText(fixture.sessionTitle)
        assertExternalIntentConsumed()

        rule.onNodeWithContentDescription("返回会话主页").performClick()
        awaitText("故事库")
        awaitNoContentDescription("返回会话主页")
        capture("system-notification-back-to-library")
    }

    @Test
    fun notificationForDeletedSessionFallsBackToStoryLibrary() {
        requireNotificationPermission()
        val helper = NotificationHelper(targetContext.applicationContext)
        val notificationId = (fixture.sessionId + 100_000L).toInt()
        ownedNotificationIds += notificationId
        val notification = helper.createGenerateNotification("系统入口旧会话", 0, fixture.sessionId)
        targetContext.getSystemService(NotificationManager::class.java)!!.notify(notificationId, notification)
        val pendingIntent = awaitNotification(notificationId).contentIntent
        deliverUri("mojing://characters")
        awaitText(fixture.characterName)
        assertExternalIntentConsumed()
        runBlocking(Dispatchers.IO) { database.sessionDao().delete(fixture.sessionId) }
        pendingIntent.send()
        awaitText("故事库")
        awaitNoContentDescription("返回创作中心")
        assertExternalIntentConsumed()
        restoreHarnessLaunchIntent()
        capture("system-notification-deleted-session-library")
        check(runBlocking(Dispatchers.IO) { database.sessionDao().getById(fixture.sessionId) } == null)
    }

    @Test
    fun newChatShortcutUriOpensOnceAndCancellationSurvivesRecreate() {
        deliverUri("mojing://new_chat")
        awaitDisplayedContentDescription("关闭新建对话")
        capture("system-shortcut-new-chat-dialog")
        rule.onNodeWithContentDescription("关闭新建对话").performClick()
        awaitNoContentDescription("关闭新建对话")
        awaitText("故事库")
        assertExternalIntentConsumed()
        restoreHarnessLaunchIntent()

        recreateActualActivity()
        awaitNoContentDescription("关闭新建对话")
        awaitText("故事库")
        assertExternalIntentConsumed()
    }

    @Test
    fun charactersShortcutUriOpensCharactersAndBackReturnsToCreationRoot() {
        deliverUri("mojing://characters")
        awaitText("角色")
        assertExternalIntentConsumed()
        restoreHarnessLaunchIntent()
        capture("system-shortcut-characters")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitText("最近项目")
        capture("system-shortcut-characters-back-to-creation")
        rule.onNodeWithText("对话", substring = false).performClick()
        awaitText("故事库")
        capture("system-shortcut-characters-back")
    }

    private fun deliverUri(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .setClass(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        targetContext.startActivity(intent)
        rule.waitForIdle()
    }

    private fun awaitNotification(id: Int): android.app.Notification {
        lateinit var result: android.app.Notification
        rule.waitUntil(5_000) {
            val found = targetContext.getSystemService(NotificationManager::class.java)!!
                .activeNotifications.firstOrNull { it.id == id }
            if (found == null) false else { result = found.notification; true }
        }
        return result
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

    private fun awaitNoContentDescription(description: String) {
        rule.waitUntil(5_000) {
            rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun dismissSplash() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private fun isGenericEmulator(): Boolean = Build.FINGERPRINT.contains("generic", true) ||
        Build.MODEL.startsWith("sdk_gphone", true) || Build.DEVICE.contains("emulator", true)

    private fun requireNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            check(targetContext.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                "POST_NOTIFICATIONS is required; grant it once for the complete instrument run before invoking this test"
            }
        }
    }

    private fun assertExternalIntentConsumed() {
        val intent = rule.activity.intent
        check(intent.data == null) { "external navigation data was not consumed: ${intent.data}" }
        check(!intent.hasExtra("navigate_to")) { "navigate_to extra was not consumed" }
        check(!intent.hasExtra("session_id")) { "session_id extra was not consumed" }
    }

    /** Restore only the harness ACTION_MAIN/LAUNCHER intent after real app delivery. */
    private fun recreateActualActivity() {
        // Scenario missed the real onNewIntent RESUMED event when its Intent
        // filter changed. Recreate the actual Activity rather than waiting on
        // that stale Scenario stage. The restored harness Intent lets it track
        // destruction and the replacement Activity normally.
        val activity = rule.activity
        rule.runOnUiThread { activity.recreate() }
        rule.waitUntil(10_000) { activity.isDestroyed }
        rule.waitForIdle()
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
        val runName = args.getString("captureRun") ?: "system-entry-20261006"
        val directory = java.io.File(targetContext.getExternalFilesDir(null), runName).apply { mkdirs() }
        val file = java.io.File(directory, "$name.png")
        file.outputStream().use { output ->
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(
                android.graphics.Bitmap.CompressFormat.PNG, 100, output,
            )) { "screenshot encoding failed for $name" }
        }
    }

    private fun seedFixture(): Fixture = runBlocking(Dispatchers.IO) {
        val suffix = UUID.randomUUID().toString().take(8)
        val title = "系统入口故事 · $suffix"
        val characterName = "系统入口角色 · $suffix"
        database.withTransaction {
            val characterId = database.characterDao().upsert(CharacterEntity(name = characterName, personaPrompt = "入口验收角色"))
            val sessionId = database.sessionDao().insert(SessionEntity(title = title))
            database.participantDao().upsert(SessionParticipantEntity(sessionId = sessionId, characterId = characterId))
            database.messageDao().insert(MessageEntity(sessionId = sessionId, speakerType = "narrator", content = "入口验收消息"))
            Fixture(sessionId, characterId, title, characterName)
        }
    }

    private data class Fixture(
        val sessionId: Long,
        val characterId: Long,
        val sessionTitle: String,
        val characterName: String,
    )
}

package com.mojing.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.semantics.SemanticsActions
import androidx.core.view.WindowInsetsCompat
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.MainActivity
import com.mojing.app.data.ChatDraftSnapshot
import com.mojing.app.data.ChatDraftStore
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.AutoVoiceMetadata
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.google.gson.JsonParser
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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


/** Opt-in complete App acceptance using only the local test APK TTS engine. */
@RunWith(AndroidJUnit4::class)
class VoiceAttachmentAppCaptureTest {
    val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val database get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private var launcherIntent: Intent? = null
    private lateinit var storage: SecureStorage
    private var originalTheme: String? = null
    private var fixture: Fixture? = null

    private val setup = object : org.junit.rules.ExternalResource() {
        override fun before() {
            assumeTrue("autoVoiceCapture=true required", args.getString("autoVoiceCapture") == "true")
            assumeTrue("generic emulator only", Build.FINGERPRINT.contains("generic", true) || Build.MODEL.startsWith("sdk_gphone", true) || Build.DEVICE.contains("emulator", true))
            storage = SecureStorage().also { it.init(context) }
            originalTheme = storage.themeMode
            args.getString("captureTheme")?.let { storage.themeMode = it }
        }
        override fun after() {
            var failure: Throwable? = null
            var settled = fixture == null
            fun attempt(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
            }
            try {
                attempt {
                    fixture?.let { own ->
                        instrumentation.runOnMainSync { com.mojing.app.ui.chat.RetainedChatSessions.stores.stop(own.sessionId) }
                        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
                        while (own.sessionId in com.mojing.app.ui.chat.RetainedChatSessions.running.value && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(20)
                        settled = own.sessionId !in com.mojing.app.ui.chat.RetainedChatSessions.running.value
                        check(settled) { "owned generation did not settle; fixture data retained" }
                    }
                }
            } finally {
                attempt { originalTheme?.let { storage.themeMode = it } }
                if (settled) attempt {
                    fixture?.let { own ->
                        ChatDraftStore(context).save(own.sessionId, ChatDraftSnapshot())
                        runBlocking(Dispatchers.IO) {
                            val directory = File(context.filesDir, "attachments/${own.sessionId}").canonicalFile
                            check(directory.parentFile == File(context.filesDir, "attachments").canonicalFile && directory.name == own.sessionId.toString())
                            val files = directory.listFiles().orEmpty().toList()
                            files.forEach { file ->
                                check(!java.nio.file.Files.isSymbolicLink(file.toPath()))
                                check(file.canonicalFile.parentFile == directory && file.name.matches(Regex("gen_voice_[A-Za-z0-9_-]+_[0-9]+\\.(wav|mp3)")) && file.isFile)
                            }
                            database.withTransaction {
                                database.openHelper.writableDatabase.execSQL("DELETE FROM llm_cost_records WHERE sessionId = ?", arrayOf(own.sessionId))
                                database.sessionDao().delete(own.sessionId)
                                database.characterDao().delete(own.characterId)
                            }
                            files.forEach { check(it.delete()) }
                            if (directory.isDirectory && directory.listFiles()?.isEmpty() == true) check(directory.delete())
                        }
                    }
                }
            }
            failure?.let { throw it }
        }
    }
    @get:Rule val orderedRules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(setup).around(rule)
    @Before fun prepareLauncher() {
        launcherIntent = Intent(rule.activity.intent)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }
    @After fun restoreLauncher() {
        launcherIntent?.let { intent -> val activity = rule.activity; rule.runOnUiThread { activity.setIntent(Intent(intent)) } }
    }

    @Test fun originalTextBecomesOrderedFilesAndPlaysInChatAndReadingWithoutLosingDraft() {
        val own = seed(AutoVoiceMetadata.STATE_FAILED)
        openChat(own)
        input().performTextReplacement("配音期间保留的独立输入")
        hideKeyboardIfVisible()
        try { awaitRetry() } catch (failure: Throwable) {
            capture("voice-retry-unavailable")
            android.util.Log.i("VoiceAcceptance", rule.onRoot(useUnmergedTree = true).printToString())
            throw failure
        }
        capture("voice-original-failure")
        rule.onNodeWithContentDescription("重试配音").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        rule.waitUntil(30_000) { metadata(own).state == AutoVoiceMetadata.STATE_COMPLETE }
        val parts = runBlocking(Dispatchers.IO) { database.attachmentDao().getByMessage(own.voiceMessageId) }
        assertTrue("long text generated multiple chunks", parts.size >= 3)
        parts.forEach { part ->
            assertEquals("voice", part.assetType)
            assertTrue(part.mimeType.startsWith("audio/"))
            assertEquals(own.text, part.generationPrompt)
            assertTrue(File(part.storagePath).isFile && File(part.storagePath).length() > 44)
            val bytes = File(part.storagePath).readBytes()
            assertEquals("local engine emitted eight seconds of PCM", 128_044, bytes.size)
            assertTrue("local engine emitted silence", bytes.drop(44).all { it == 0.toByte() })
        }
        awaitPlay()
        input().assertTextContains("配音期间保留的独立输入")
        capture("voice-saved-chunks")
        rule.onNodeWithContentDescription("播放语音附件").performClick()
        awaitText("正在朗读")
        capture("voice-playing")
        rule.onNodeWithContentDescription("暂停朗读").performClick()
        awaitText("已暂停")
        capture("voice-paused")
        rule.onNodeWithContentDescription("继续朗读").performClick()
        awaitText("正在朗读")
        capture("voice-resumed")
        rule.onNodeWithContentDescription("停止朗读").performClick()
        awaitStopped()
        parts.forEach { assertTrue(File(it.storagePath).isFile) }
        input().assertTextContains("配音期间保留的独立输入")
        capture("voice-stopped-files-retained")
        rule.onNodeWithContentDescription("阅读模式").performClick()
        awaitPlay()
        rule.onNodeWithContentDescription("播放语音附件").performClick()
        awaitText("正在朗读")
        capture("voice-reading-playback")
        rule.onNodeWithContentDescription("停止朗读").performClick()
        awaitStopped()
        leaveChat()
        openChat(own)
        input().assertTextContains("配音期间保留的独立输入")
        awaitPlay()
        awaitStopped()
        capture("voice-reentry-no-autoplay")
        recreate()
        input().assertTextContains("配音期间保留的独立输入")
        awaitPlay()
        awaitStopped()
        assertEquals(parts.map { it.storagePath }, runBlocking(Dispatchers.IO) { database.attachmentDao().getByMessage(own.voiceMessageId).map { it.storagePath } })
        capture("voice-activity-recreate")
        leaveChat()
    }

    @Test fun coldRunningAndLegacyBecomeInterruptedAndOnlyOwnOrphanIsCleaned() {
        val own = seed(AutoVoiceMetadata.STATE_RUNNING, legacy = true)
        val directory = File(context.filesDir, "attachments/${own.sessionId}").apply { mkdirs() }
        val token = metadata(own).attemptToken!!
        val orphan = File(directory, "gen_voice_${token}_0.wav").apply { writeText("owned orphan") }
        val other = File(directory, "gen_voice_${UUID.randomUUID()}_0.wav").apply { writeText("other attempt") }
        openChat(own)
        rule.waitUntil(10_000) { metadata(own).state == AutoVoiceMetadata.STATE_INTERRUPTED }
        awaitRetry()
        assertTrue(!orphan.exists())
        assertTrue(other.isFile)
        assertEquals(own.text, metadata(own).text)
        assertEquals("配音生成中断，可重试", runBlocking(Dispatchers.IO) { database.messageDao().getById(own.voiceMessageId)!!.content })
        rule.onNodeWithText("配音生成中断，可重试").assertIsDisplayed()
        val legacy = runBlocking(Dispatchers.IO) { requireNotNull(database.messageDao().getById(requireNotNull(own.legacyId))) }
        assertEquals("配音已中断", legacy.content)
        assertEquals(AutoVoiceMetadata.STATE_INTERRUPTED, AutoVoiceMetadata.parse(legacy.structuredContentJson)?.state)
        assertEquals(1, rule.onAllNodesWithContentDescription("重试配音").fetchSemanticsNodes().size)
        assertTrue(runBlocking(Dispatchers.IO) { database.attachmentDao().getByMessage(own.voiceMessageId).isEmpty() })
        awaitStopped()
        capture("voice-interrupted-and-legacy")
        recreate()
        awaitRetry()
        assertEquals(AutoVoiceMetadata.STATE_INTERRUPTED, metadata(own).state)
        rule.onNodeWithText("配音生成中断，可重试").assertIsDisplayed()
        assertEquals("配音已中断", runBlocking(Dispatchers.IO) { database.messageDao().getById(own.legacyId!!)!!.content })
        assertTrue(other.isFile)
        awaitStopped()
        capture("voice-interrupted-recreate")
        leaveChat()
    }

    private fun seed(state: String, legacy: Boolean = false): Fixture = runBlocking(Dispatchers.IO) {
        database.withTransaction {
            val suffix = UUID.randomUUID().toString().take(8)
            val title = "配音附件故事 · $suffix"
            val character = database.characterDao().upsert(CharacterEntity(name = "配音角色 · $suffix", personaPrompt = "仅本地验收", voiceProvider = "android:com.mojing.app.test", voiceModel = ""))
            val session = database.sessionDao().insert(SessionEntity(title = title))
            database.sessionWorldDao().upsert(SessionWorldEntity(sessionId = session, autoCharacterSpeech = true))
            database.participantDao().upsert(SessionParticipantEntity(sessionId = session, characterId = character))
            val source = database.messageDao().insert(MessageEntity(sessionId = session, speakerType = "character", characterId = character, content = "潮声还在窗外。"))
            val text = "雪港的潮声仍在窗外。".repeat(900)
            val voice = database.messageDao().insert(MessageEntity(sessionId = session, speakerType = "character", characterId = character, parentMessageId = source, branchId = "main", includeInContext = false,
                content = if (state == AutoVoiceMetadata.STATE_RUNNING) "配音生成中…" else "配音生成失败，可重试", structuredContentJson = AutoVoiceMetadata.create(text, state, UUID.randomUUID().toString())))
            val legacyId = if (legacy) database.messageDao().insert(MessageEntity(sessionId = session, speakerType = "character", characterId = character, parentMessageId = source, branchId = "main", includeInContext = false,
                content = "配音生成中…", structuredContentJson = """{"derived_media_version":1,"derived_media_kind":"voice"}""")) else null
            Fixture(session, character, voice, title, text, legacyId).also { fixture = it }
        }
    }
    private fun metadata(own: Fixture) = runBlocking(Dispatchers.IO) { requireNotNull(AutoVoiceMetadata.parse(requireNotNull(database.messageDao().getById(own.voiceMessageId)).structuredContentJson)) }
    private fun awaitRetry() = rule.waitUntil(10_000) { runCatching { rule.onNodeWithContentDescription("重试配音").assertIsDisplayed().assertIsEnabled(); true }.getOrDefault(false) }
    private fun awaitPlay() = rule.waitUntil(10_000) { runCatching { rule.onNodeWithContentDescription("播放语音附件").assertIsDisplayed().assertIsEnabled(); true }.getOrDefault(false) }
    private fun awaitText(value: String) = rule.waitUntil(10_000) { runCatching { rule.onNodeWithText(value).assertIsDisplayed(); true }.getOrDefault(false) }
    private fun awaitStopped() = rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("停止朗读").fetchSemanticsNodes().isEmpty() }
    private fun recreate() {
        val previous = rule.activity
        instrumentation.runOnMainSync { previous.recreate() }
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (!previous.isDestroyed && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(20)
        assertTrue(previous.isDestroyed)
        rule.waitForIdle()
    }
    private fun input() = rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst()

    private fun hideKeyboardIfVisible() {
        val activity = rule.activity
        rule.runOnUiThread {
            activity.currentFocus?.clearFocus()
            androidx.core.view.WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        rule.waitForIdle()
    }

    private fun openChat(own: Fixture) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mojing://chat")).apply {
            setClass(context, MainActivity::class.java)
            putExtra("navigate_to", "chat"); putExtra("session_id", own.sessionId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithText(own.title).assertIsDisplayed(); input().assertIsDisplayed(); true }.getOrDefault(false) }
        val activity = rule.activity
        assertTrue("external intent consumed", activity.intent.data == null && !activity.intent.hasExtra("session_id"))
        launcherIntent?.let { intent -> rule.runOnUiThread { activity.setIntent(Intent(intent)) } }
    }

    private fun leaveChat() {
        rule.onNodeWithContentDescription("返回会话主页").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("故事库").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture(name: String) {
        val settled = CountDownLatch(1)
        val activity = rule.activity
        if (Build.VERSION.SDK_INT >= 29 && activity.window.decorView.isHardwareAccelerated) {
            rule.runOnUiThread {
                activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { settled.countDown() }
                activity.window.decorView.invalidate()
            }
            assertTrue("application frame committed", settled.await(5, TimeUnit.SECONDS))
        }
        instrumentation.waitForIdleSync()
        val runName = args.getString("captureRun") ?: "auto-voice-20261006"
        require(runName.matches(Regex("[A-Za-z0-9._-]+")))
        val directory = File(context.getExternalFilesDir(null), runName).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { out -> assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, out)) }
    }


    private data class Fixture(val sessionId: Long, val characterId: Long, val voiceMessageId: Long, val title: String, val text: String, val legacyId: Long?)
}

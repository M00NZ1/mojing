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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.core.view.WindowInsetsCompat
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.MainActivity
import com.mojing.app.data.ChatDraftSnapshot
import com.mojing.app.data.ChatDraftStore
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.AutoImageMetadata
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

/** Opt-in complete App acceptance. Only a local test HTTP endpoint is used. */
@RunWith(AndroidJUnit4::class)
class ImageRecoveryAppCaptureTest {
    val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val database get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private var launcherIntent: Intent? = null
    private lateinit var storage: SecureStorage
    private lateinit var server: LocalImageServer
    private var originalTheme: String? = null
    private var originalPublicKey: String? = null
    private var fixture: Fixture? = null

    private val setup = object : org.junit.rules.ExternalResource() {
        override fun before() {
            assumeTrue("autoImageRecoveryCapture=true required", args.getString("autoImageRecoveryCapture") == "true")
            assumeTrue("generic emulator only", Build.FINGERPRINT.contains("generic", true) || Build.MODEL.startsWith("sdk_gphone", true) || Build.DEVICE.contains("emulator", true))
            storage = SecureStorage().also { it.init(context) }
            originalTheme = storage.themeMode
            originalPublicKey = storage.publicApiKey
            // The UUID character owns the primary localhost route. Disable the
            // optional public fallback for this fixture and restore it in memory.
            try {
                server = LocalImageServer()
                storage.publicApiKey = ""
                args.getString("captureTheme")?.let { storage.themeMode = it }
            } catch (failure: Throwable) {
                runCatching { if (::server.isInitialized) server.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { originalPublicKey?.let { storage.publicApiKey = it } }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { originalTheme?.let { storage.themeMode = it } }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
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
                // The real local-HTTP repository propagates cancellation. Always
                // restore user preferences even if another teardown step fails.
                attempt { if (::server.isInitialized) server.close() }
                attempt { originalPublicKey?.let { storage.publicApiKey = it } }
                attempt { originalTheme?.let { storage.themeMode = it } }
                if (settled) attempt {
                    fixture?.let { own ->
                        ChatDraftStore(context).save(own.sessionId, ChatDraftSnapshot())
                        runBlocking(Dispatchers.IO) {
                            val ownedDirectory = File(context.filesDir, "attachments/${own.sessionId}").canonicalFile
                            val parent = File(context.filesDir, "attachments").canonicalFile
                            check(ownedDirectory.parentFile == parent && ownedDirectory.name == own.sessionId.toString())
                            val paths = database.attachmentDao().getByMessage(own.imageMessageId).map { it.storagePath }
                            // Include generated-but-uncommitted files in this
                            // fixture's exact directory. Validate all before deleting.
                            val ownedFiles = (paths.map(::File) + ownedDirectory.listFiles().orEmpty().toList()).distinctBy { it.absolutePath }
                            ownedFiles.forEach { file ->
                                check(!java.nio.file.Files.isSymbolicLink(file.toPath()))
                                val resolved = file.canonicalFile
                                check(resolved.parentFile == ownedDirectory && resolved.name.startsWith("gen_") && resolved.extension == "png")
                                check(!resolved.exists() || resolved.isFile)
                            }
                            database.withTransaction {
                                database.openHelper.writableDatabase.execSQL("DELETE FROM llm_cost_records WHERE sessionId = ?", arrayOf(own.sessionId))
                                database.sessionDao().delete(own.sessionId)
                                database.characterDao().delete(own.characterId)
                            }
                            ownedFiles.forEach { file -> if (file.exists()) check(file.delete()) }
                            if (ownedDirectory.isDirectory && ownedDirectory.listFiles()?.isEmpty() == true) check(ownedDirectory.delete())
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

    @Test fun failedImageRetriesInPlaceAndPreservesDraftAndOriginalPrompt() {
        val own = seed(AutoImageMetadata.STATE_FAILED)
        server.failures.set(1)
        openChat(own)
        input().performTextReplacement("配图期间独立保存的输入")
        hideKeyboardIfVisible()
        awaitRetry()
        capture("auto-image-original-failure")
        val firstToken = metadata(own).attemptToken
        retry()
        rule.waitUntil(10_000) {
            metadata(own).let { it.state == AutoImageMetadata.STATE_FAILED && it.attemptToken != firstToken } && server.requests.size == 1
        }
        awaitRetry()
        input().assertTextContains("配图期间独立保存的输入")
        capture("auto-image-retry-failure")
        retry()
        rule.waitUntil(10_000) { metadata(own).state == AutoImageMetadata.STATE_COMPLETE }
        val attachment = runBlocking(Dispatchers.IO) { database.attachmentDao().getByMessage(own.imageMessageId).single() }
        assertEquals(own.prompt, attachment.generationPrompt)
        assertTrue(File(attachment.storagePath).isFile)
        assertEquals(2, server.requests.size)
        server.requests.forEach { request ->
            assertEquals(own.prompt, JsonParser.parseString(request).asJsonObject["prompt"].asString)
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("停止").fetchSemanticsNodes().isEmpty() }
        awaitImage(attachment.fileName)
        capture("auto-image-success-original-message")
        leaveChat()
        openChat(own)
        input().assertTextContains("配图期间独立保存的输入")
        assertEquals("配图期间独立保存的输入", ChatDraftStore(context).load(own.sessionId).inputText)
        awaitImage(attachment.fileName)
        assertEquals(1, runBlocking(Dispatchers.IO) { database.attachmentDao().getByMessage(own.imageMessageId).size })
        capture("auto-image-success-reentry")
        leaveChat()
    }

    @Test fun initialTailRecoversRunningAndLegacyWithoutAutomaticNetworkRequest() {
        val own = seed(AutoImageMetadata.STATE_RUNNING, legacy = true)
        openChat(own)
        rule.waitUntil(10_000) { metadata(own).state == AutoImageMetadata.STATE_INTERRUPTED }
        awaitRetry()
        assertEquals(0, server.requests.size)
        assertEquals(own.prompt, metadata(own).prompt)
        assertEquals(1, rule.onAllNodesWithContentDescription("重试配图").fetchSemanticsNodes().size)
        capture("auto-image-interrupted-tail-and-legacy")
        val previous = rule.activity
        instrumentation.runOnMainSync { previous.recreate() }
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (!previous.isDestroyed && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(20)
        assertTrue("Activity was destroyed", previous.isDestroyed)
        awaitRetry()
        assertEquals(0, server.requests.size)
        assertEquals(AutoImageMetadata.STATE_INTERRUPTED, metadata(own).state)
        capture("auto-image-interrupted-activity-recreate")
        leaveChat()
    }

    private fun seed(state: String, legacy: Boolean = false): Fixture = runBlocking(Dispatchers.IO) {
        database.withTransaction {
            val suffix = UUID.randomUUID().toString().take(8)
            val title = "配图恢复故事 · $suffix"
            val character = database.characterDao().upsert(CharacterEntity(
                name = "配图角色 · $suffix", personaPrompt = "仅本地配图验收",
                imageGenEnabled = true, imageGenApiKey = "acceptance-local-only",
                imageGenBaseUrl = server.baseUrl, imageGenModel = "dall-e-3",
            ))
            val session = database.sessionDao().insert(SessionEntity(title = title))
            database.sessionWorldDao().upsert(SessionWorldEntity(sessionId = session, autoCharacterImageGen = true))
            database.participantDao().upsert(SessionParticipantEntity(sessionId = session, characterId = character))
            val source = database.messageDao().insert(MessageEntity(sessionId = session, speakerType = "character", characterId = character, content = "潮声还在窗外。"))
            val prompt = "本地验收画面 · $suffix"
            val image = database.messageDao().insert(MessageEntity(
                sessionId = session, speakerType = "character", characterId = character,
                parentMessageId = source, branchId = "main", includeInContext = false,
                content = if (state == AutoImageMetadata.STATE_RUNNING) "🖼 配图生成中…" else "🖼 配图生成失败，可重试",
                structuredContentJson = AutoImageMetadata.create(prompt, state, UUID.randomUUID().toString()),
            ))
            if (legacy) database.messageDao().insert(MessageEntity(
                sessionId = session, speakerType = "character", characterId = character,
                parentMessageId = source, branchId = "main", includeInContext = false,
                content = "🖼 配图生成中…", structuredContentJson = """{"derived_media_version":1,"derived_media_kind":"image"}""",
            ))
            Fixture(session, character, image, title, prompt).also { fixture = it }
        }
    }

    private fun metadata(own: Fixture) = runBlocking(Dispatchers.IO) {
        requireNotNull(AutoImageMetadata.parse(requireNotNull(database.messageDao().getById(own.imageMessageId)).structuredContentJson))
    }

    private fun awaitRetry() = rule.waitUntil(10_000) {
        runCatching { rule.onNodeWithContentDescription("重试配图").assertIsDisplayed().assertIsEnabled(); true }.getOrDefault(false)
    }

    private fun awaitImage(name: String) = rule.waitUntil(10_000) {
        runCatching { rule.onNodeWithContentDescription(name).assertIsDisplayed(); true }.getOrDefault(false)
    }

    private fun retry() {
        rule.onNodeWithContentDescription("重试配图").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
    }

    private fun input() = rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst()

    private fun hideKeyboardIfVisible() {
        val activity = rule.activity
        var visible = false
        rule.runOnUiThread { activity.window.decorView.rootWindowInsets?.let { visible = WindowInsetsCompat.toWindowInsetsCompat(it).isVisible(WindowInsetsCompat.Type.ime()) } }
        if (visible) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
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
        val runName = args.getString("captureRun") ?: "auto-image-recovery-20261006"
        require(runName.matches(Regex("[A-Za-z0-9._-]+")))
        val directory = File(context.getExternalFilesDir(null), runName).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { out -> assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, out)) }
    }

    private data class Fixture(val sessionId: Long, val characterId: Long, val imageMessageId: Long, val title: String, val prompt: String)

    private class LocalImageServer : AutoCloseable {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:${socket.localPort}/v1"
        val failures = AtomicInteger()
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val activeClient = AtomicReference<java.net.Socket?>()
        private val encoded = ByteArrayOutputStream().let { out ->
            val image = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            image.eraseColor(android.graphics.Color.rgb(97, 151, 170))
            image.compress(Bitmap.CompressFormat.PNG, 100, out)
            image.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
        private val worker = Thread {
            while (!socket.isClosed && requests.size < 8) {
                runCatching {
                    val accepted = socket.accept()
                    activeClient.set(accepted)
                    try { accepted.use { client ->
                        client.soTimeout = 5_000
                        val input = client.getInputStream().buffered()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val byte = input.read(); check(byte >= 0 && header.length < 16_384)
                            header.append(byte.toChar())
                        }
                        val length = header.lines().firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                        check(length in 1..65_536)
                        val body = ByteArray(length)
                        var read = 0
                        while (read < length) { val count = input.read(body, read, length - read); check(count > 0); read += count }
                        requests.add(body.toString(Charsets.UTF_8))
                        val failed = failures.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0
                        val response = if (failed) """{"error":{"message":"local fixture failure"}}""" else """{"data":[{"b64_json":"$encoded"}]}"""
                        val bytes = response.toByteArray(Charsets.UTF_8)
                        client.getOutputStream().use { output ->
                            output.write("HTTP/1.1 ${if (failed) "500 Internal Server Error" else "200 OK"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            output.write(bytes); output.flush()
                        }
                    } } finally { activeClient.compareAndSet(accepted, null) }
                }
            }
        }.apply { isDaemon = true; name = "local-image-acceptance"; start() }

        override fun close() {
            socket.close()
            activeClient.getAndSet(null)?.close()
            worker.join(1_000)
            check(!worker.isAlive) { "owned local HTTP server did not close" }
        }
    }
}

package com.mojing.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.mojing.app.MainActivity
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.domain.usecase.MainBranchMediaBundleUseCase
import com.mojing.app.test.MediaBundleDocumentsProvider
import dagger.hilt.android.EntryPointAccessors
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
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Opt-in full MainActivity SAF acceptance. It uses the registered DocumentsProvider and picker UI. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class MainBranchMediaBundleAppCaptureTest {
    val rule = createAndroidComposeRule<MainActivity>()
    private var originalTheme: String? = null
    private lateinit var storage: com.mojing.app.data.SecureStorage
    private val themeSetup = object : org.junit.rules.ExternalResource() {
        override fun before() {
            assumeTrue("mediaBundleCapture=true required", args.getString("mediaBundleCapture") == "true")
            assumeTrue("generic emulator only", Build.FINGERPRINT.contains("generic", true) || Build.MODEL.startsWith("sdk_gphone", true) || Build.DEVICE.contains("emulator", true))
            storage = com.mojing.app.data.SecureStorage().also { it.init(context) }
            originalTheme = storage.themeMode
            args.getString("captureTheme")?.let { storage.themeMode = it }
        }
        override fun after() { originalTheme?.let { storage.themeMode = it } }
    }
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(themeSetup).around(rule)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val database get() = EntryPointAccessors.fromApplication(
        context.applicationContext, PrototypeDatabaseEntryPoint::class.java,
    ).database()
    private lateinit var session: SessionEntity
    private lateinit var targetSession: SessionEntity
    private var launcherIntent: Intent? = null
    private var characterId = 0L
    private lateinit var ownedFile: java.io.File
    private val ownAudioFiles = mutableListOf<java.io.File>()
    private val ownDocuments = mutableListOf<String>()

    @Before
    fun setUp() = runBlocking(Dispatchers.IO) {
        assumeTrue("mediaBundleCapture=true required", args.getString("mediaBundleCapture") == "true")
        assumeTrue("generic emulator only", Build.FINGERPRINT.contains("generic", true) || Build.MODEL.startsWith("sdk_gphone", true) || Build.DEVICE.contains("emulator", true))
        launcherIntent = Intent(rule.activity.intent)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
        val suffix = UUID.randomUUID().toString().take(8)
        characterId = database.characterDao().upsert(CharacterEntity(name = "媒体包角色-$suffix", personaPrompt = "本地媒体包验收"))
        val id = database.sessionDao().insert(SessionEntity(title = "媒体包 App 来源-$suffix"))
        session = SessionEntity(id = id, title = "媒体包 App 来源-$suffix")
        database.participantDao().upsert(SessionParticipantEntity(sessionId = id, characterId = characterId))
        val source = database.messageDao().insert(MessageEntity(sessionId = id, speakerType = "character", characterId = characterId, content = "父消息"))
        val structured = com.mojing.app.data.local.AutoImageMetadata.create("媒体包 App 验收画面", "complete", UUID.randomUUID().toString())
        val media = database.messageDao().insert(MessageEntity(sessionId = id, speakerType = "character", characterId = characterId,
            parentMessageId = source, content = "", structuredContentJson = structured, includeInContext = false))
        ownedFile = java.io.File(context.cacheDir, "media-app-$suffix.png").apply {
            val bitmap = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(30, 120, 180))
            outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        database.messageDao().insertMessageAttachmentRaw(MessageAttachmentEntity(messageId = media, assetType = "image",
            fileName = "acceptance.png", mimeType = "image/png", storagePath = ownedFile.absolutePath,
            generationPrompt = "媒体包 App 验收画面", generationModel = "local"))
        database.messageDao().insertMessageAttachmentRaw(MessageAttachmentEntity(messageId = source, assetType = "image",
            fileName = "manual-acceptance.png", mimeType = "image/png", storagePath = ownedFile.absolutePath))
        val voiceId = database.messageDao().insert(MessageEntity(sessionId = id, speakerType = "character",
            characterId = characterId, parentMessageId = source, content = "", includeInContext = false,
            structuredContentJson = com.mojing.app.data.local.AutoVoiceMetadata.create("本地语音附件验收", "complete", UUID.randomUUID().toString())))
        repeat(2) { part ->
            val file = java.io.File(context.cacheDir, "media-app-$suffix-$part.wav").apply { writeBytes(silenceWav()) }
            ownAudioFiles += file
            database.messageDao().insertMessageAttachmentRaw(MessageAttachmentEntity(messageId = voiceId,
                assetType = "voice", fileName = "voice-$part.wav", mimeType = "audio/wav", storagePath = file.absolutePath))
        }
        database.messageDao().insert(MessageEntity(sessionId = id, speakerType = "narrator", content = "媒体包旁白验收"))
        val targetId = database.sessionDao().insert(SessionEntity(title = "媒体包 App 目标-$suffix"))
        targetSession = SessionEntity(id = targetId, title = "媒体包 App 目标-$suffix")
        database.participantDao().upsert(SessionParticipantEntity(sessionId = targetId, characterId = characterId))
        instrumentation.runOnMainSync {
            context.startActivity(Intent("android.intent.action.VIEW", Uri.parse("mojing://chat")).apply {
                setClass(context, MainActivity::class.java)
                putExtra("navigate_to", "chat")
                putExtra("session_id", id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText(session.title).fetchSemanticsNodes().isNotEmpty() }
        restoreLauncherIntent()
    }

    @After
    fun tearDown() = runBlocking(Dispatchers.IO) {
        val importedPaths = if (::targetSession.isInitialized) database.attachmentDao().getByMessages(
            database.messageDao().getMainBranchMessages(targetSession.id).map { it.id }).map { it.storagePath } else emptyList()
        if (::session.isInitialized) database.sessionDao().delete(session.id)
        if (::targetSession.isInitialized) database.sessionDao().delete(targetSession.id)
        if (characterId > 0) database.characterDao().delete(characterId)
        if (::ownedFile.isInitialized) ownedFile.delete()
        ownAudioFiles.forEach { it.delete() }
        ownDocuments.forEach { name -> runCatching {
            providerCall("deleteOwned", name, null)
        } }
        runCatching { providerCall("revokeControl", "", null) }
        if (::targetSession.isInitialized) importedPaths.forEach { path ->
            if (database.attachmentDao().countByStoragePath(path) == 0)
                com.mojing.app.util.ChatAttachmentFiles.deleteOwnedPersistedMediaFile(context, targetSession.id, path)
        }
    }

    @Test
    fun realSafProviderRoundTripsImportAndExportControls() {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(session.title).fetchSemanticsNodes().isNotEmpty() }
        hideKeyboard()
        rule.waitForIdle()
        val output = ByteArrayOutputStream()
        runBlocking(Dispatchers.IO) {
            MainBranchMediaBundleUseCase(context, database.messageDao(), database.attachmentDao(), database.characterDao(),
                database.participantDao(), database.sessionDao(), database.sessionBranchDao(), database = database).exportBundle(output, session.id, {}, { _, _ -> })
        }
        val fixtureName = "media-app-fixture-${UUID.randomUUID()}.zip"
        val providerUri = Uri.parse("content://${MediaBundleDocumentsProvider.AUTHORITY}")
        ownDocuments += fixtureName
        val documentUri = providerCall("prepare", fixtureName,
            Bundle().apply { putByteArray("bytes", output.toByteArray()) })!!.getParcelable<Uri>("uri")!!

        rule.onNodeWithContentDescription("会话菜单").performClick()
        capture("01-source-menu")
        rule.onNodeWithText("导出主线记录与媒体…").performScrollTo().performClick()
        assertTrue(clickDocumentText("媒体包验收"))
        capture("02-saf-export")
        val exportName = "ui-export-${UUID.randomUUID()}.zip"
        ownDocuments += exportName
        assertTrue(setDocumentText(exportName))
        assertTrue(clickDocumentText("保存"))
        rule.waitUntil(10_000) { rule.onAllNodesWithText("已导出", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val exportedUri = android.provider.DocumentsContract.buildDocumentUri(
            MediaBundleDocumentsProvider.AUTHORITY, exportName,
        )
        val verificationRoot = java.io.File(context.cacheDir, "media-app-export-check-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val archive = java.io.File(verificationRoot, "export.zip")
            context.contentResolver.openInputStream(exportedUri).use { input -> archive.outputStream().use { output -> input!!.copyTo(output) } }
            runBlocking(Dispatchers.IO) {
                com.mojing.app.domain.chat.MainBranchMediaBundleCodec.open(archive, java.io.File(verificationRoot, "request")).use { handle ->
                    var rows = 0
                    var media = 0
                    com.mojing.app.domain.chat.MainBranchMediaBundleCodec.readMessages(handle) { message ->
                        rows++
                        com.mojing.app.domain.chat.MainBranchMediaBundleCodec.readMediaForMessage(handle, message.id) { media++ }
                    }
                    assertEquals(4, rows)
                    assertEquals(4, media)
                }
            }
        } finally { verificationRoot.deleteRecursively() }
        capture("03-export-complete")
        rule.waitForIdle()

        openSession(targetSession)

        rule.onNodeWithContentDescription("会话菜单").performClick()
        rule.onNodeWithText("导入主线记录与媒体…").performScrollTo().performClick()
        capture("04-import-confirm")
        rule.onNodeWithText("选择媒体包").performClick()
        assertTrue(clickDocumentText("媒体包验收"))
        assertTrue(clickDocumentText(fixtureName))
        rule.waitUntil(10_000) { rule.onAllNodesWithText("已导入", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(4, runBlocking(Dispatchers.IO) { database.messageDao().getMainBranchMessages(targetSession.id).size })
        val importedRows = runBlocking(Dispatchers.IO) { database.messageDao().getMainBranchMessages(targetSession.id) }
        assertEquals(importedRows[0].id, importedRows[1].parentMessageId)
        assertEquals(importedRows[0].id, importedRows[2].parentMessageId)
        assertEquals("narrator", importedRows[3].speakerType)
        assertEquals(null, importedRows[3].characterId)
        assertEquals(4, runBlocking(Dispatchers.IO) { database.attachmentDao().getByMessages(importedRows.map { it.id }).size })
        capture("05-imported-media")
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(targetSession.title).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(4, runBlocking(Dispatchers.IO) { database.messageDao().getMainBranchMessages(targetSession.id).size })
        capture("05b-recreated-import")
        rule.onNodeWithContentDescription("acceptance.png", useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("image_preview").fetchSemanticsNodes().isNotEmpty() }
        capture("06-image-viewer")
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("image_preview").fetchSemanticsNodes().isEmpty() &&
            rule.onAllNodesWithText(targetSession.title).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("播放语音附件").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithContentDescription("播放语音附件").onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("暂停朗读").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("暂停朗读").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("已暂停").fetchSemanticsNodes().isNotEmpty() }
        capture("07-voice-paused")
        rule.onNodeWithContentDescription("继续朗读").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("正在朗读").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("停止朗读").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("停止朗读").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithContentDescription("会话菜单").performClick()
        rule.onNodeWithText("导入主线记录与媒体…").performScrollTo().performClick()
        rule.onNodeWithText("选择媒体包").performClick()
        assertTrue(clickDocumentText("媒体包验收"))
        assertTrue(clickDocumentText(fixtureName))
        rule.waitUntil(10_000) { rule.onAllNodesWithText("已导入", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(4, runBlocking(Dispatchers.IO) { database.messageDao().getMainBranchMessages(targetSession.id).size })
        capture("08-duplicate")
    }

    @Test
    fun realSafSlowImportStopsAndLeavingDoesNotCommit() {
        openSession(targetSession)
        val fixtureName = "slow-${UUID.randomUUID()}"
        ownDocuments += fixtureName
        providerCall("prepare", fixtureName, Bundle().apply { putByteArray("bytes", ByteArray(64 * 1024)) })
        fun beginImport() {
            rule.onNodeWithContentDescription("会话菜单").performClick()
            capture("00-slow-menu")
            rule.onNodeWithText("导入主线记录与媒体…").performScrollTo().performClick()
            rule.onNodeWithText("选择媒体包").performClick()
            assertTrue(clickDocumentText("媒体包验收"))
            assertTrue(clickDocumentText(fixtureName))
            rule.waitUntil(10_000) { rule.onAllNodesWithText("停止").fetchSemanticsNodes().isNotEmpty() }
        }
        beginImport()
        capture("09-slow-import")
        rule.onNodeWithText("停止").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("媒体包导入已停止", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, runBlocking(Dispatchers.IO) { database.messageDao().getMainBranchMessages(targetSession.id).size })
        capture("10-stopped-import")
        beginImport()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("会话菜单").fetchSemanticsNodes().isEmpty() }
        openSession(targetSession)
        assertEquals(0, runBlocking(Dispatchers.IO) { database.messageDao().getMainBranchMessages(targetSession.id).size })
        capture("11-leave-reopen")
    }

    private fun clickDocumentText(text: String): Boolean {
        val automation = instrumentation.uiAutomation
        val deadline = android.os.SystemClock.uptimeMillis() + 8_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            val node = root?.let { findNode(it, text) ?: if (text == "保存")
                (findNode(it, "Save") ?: findNode(it, "SAVE")) else null }
            if (node == null && text == "媒体包验收" && root != null) {
                val rootsButton = findNode(root, "Show roots") ?: findNode(root, "显示根目录")
                rootsButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (node != null) {
                val clickable = clickableAncestor(node)
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                if (text == "媒体包验收" && node.isVisibleToUser && !clickable.isClickable) return true
            }
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    private fun setDocumentText(text: String): Boolean {
        val automation = instrumentation.uiAutomation
        val deadline = android.os.SystemClock.uptimeMillis() + 8_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            val field = root?.let { findEditable(it) }
            if (field != null) {
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
            }
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString() == "android.widget.EditText" && node.isVisibleToUser) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let { findEditable(it)?.let { found -> return found } }
        return null
    }

    private fun findNode(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let { findNode(it, text)?.let { found -> return found } }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) current = current.parent
        return current
    }

    private fun openSession(target: SessionEntity) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mojing://chat")).apply {
            setClass(context, MainActivity::class.java)
            putExtra("navigate_to", "chat")
            putExtra("session_id", target.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        rule.waitUntil(10_000) { rule.onAllNodesWithText(target.title).fetchSemanticsNodes().isNotEmpty() }
        restoreLauncherIntent()
        hideKeyboard()
    }

    private fun restoreLauncherIntent() {
        val activity = rule.activity
        launcherIntent?.let { original -> rule.runOnUiThread { activity.intent = Intent(original) } }
    }

    private fun providerCall(method: String, name: String, extras: Bundle?): Bundle? {
        val control = Uri.parse("content://${MediaBundleDocumentsProvider.AUTHORITY}")
        if (context.checkUriPermission(control, android.os.Process.myPid(), android.os.Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            context.startActivity(Intent().setClassName("com.mojing.app.test", "com.mojing.app.test.MediaBundleFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            while (context.checkUriPermission(control, android.os.Process.myPid(), android.os.Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(20)
            check(context.checkUriPermission(control, android.os.Process.myPid(), android.os.Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            instrumentation.waitForIdleSync()
            val activity = rule.activity
            rule.waitUntil(5_000) {
                var focused = false
                instrumentation.runOnMainSync { focused = activity.hasWindowFocus() }
                focused
            }
        }
        return context.contentResolver.call(control, method, name, extras)
    }

    private fun hideKeyboard() {
        rule.activityRule.scenario.onActivity { activity ->
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            activity.currentFocus?.clearFocus()
        }
        instrumentation.waitForIdleSync()
    }

    private object DocumentsRoot {
        fun uri(): Uri = android.provider.DocumentsContract.buildDocumentUri(
            MediaBundleDocumentsProvider.AUTHORITY, MediaBundleDocumentsProvider.ROOT_ID,
        )
    }

    private fun silenceWav(): ByteArray {
        val payload = 96_000
        val out = ByteArrayOutputStream(44 + payload)
        fun text(value: String) { out.write(value.toByteArray(Charsets.US_ASCII)) }
        fun int(value: Int) { repeat(4) { out.write((value ushr (it * 8)) and 0xff) } }
        fun short(value: Int) { repeat(2) { out.write((value ushr (it * 8)) and 0xff) } }
        text("RIFF"); int(36 + payload); text("WAVEfmt "); int(16); short(1); short(1)
        int(16_000); int(32_000); short(2); short(16); text("data"); int(payload)
        out.write(ByteArray(payload)); return out.toByteArray()
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        instrumentation.waitForIdleSync()
        val run = args.getString("captureRun") ?: "media-bundle-20261006"
        require(run.matches(Regex("[A-Za-z0-9._-]+")))
        val directory = java.io.File(context.getExternalFilesDir(null), run).apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { output ->
            assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}

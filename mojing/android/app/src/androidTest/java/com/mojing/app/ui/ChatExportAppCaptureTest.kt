package com.mojing.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.test.LibraryExportDocumentsProvider
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
import java.util.UUID

/** Opt-in real SAF acceptance for the two chat export writers. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class ChatExportAppCaptureTest {
    private val rule = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val database get() = EntryPointAccessors.fromApplication(
        context.applicationContext, PrototypeDatabaseEntryPoint::class.java,
    ).database()
    private val documents = mutableListOf<String>()
    private val payload = "AEX03-${UUID.randomUUID()} " + "正文阻塞验证。".repeat(24_000)
    private lateinit var kind: String
    private lateinit var session: SessionEntity
    private lateinit var tailContent: String
    private lateinit var selectedSwipeContent: String
    private lateinit var unselectedSwipeContent: String
    private var unselectedSwipeId = 0L
    private var selectedSwipeId = 0L
    private var launcherIntent: Intent? = null
    private var themePrefs: android.content.SharedPreferences? = null
    private var themeExisted = false
    private var originalTheme: String? = null
    private var characterId = 0L
    private var firstMessageId = 0L
    private val omittedUser = "AEX03-user-${UUID.randomUUID()}"

    private val setup = object : org.junit.rules.ExternalResource() {
        override fun before() {
            assumeTrue("chatExportCapture=true required", args.getString("chatExportCapture") == "true")
            assumeTrue("generic emulator only", Build.FINGERPRINT.contains("generic", true) || Build.MODEL.startsWith("sdk_gphone", true))
            kind = args.getString("chatExportKind") ?: "chat"
            assumeTrue("chatExportKind must be novel or chat", kind in setOf("novel", "chat"))
            val storage = com.mojing.app.data.SecureStorage().also { it.init(context) }
            themePrefs = com.mojing.app.data.SecureStorage::class.java.getDeclaredField("prefs").also {
                it.isAccessible = true
            }.get(storage) as android.content.SharedPreferences
            themeExisted = themePrefs!!.contains("theme_mode")
            originalTheme = themePrefs!!.getString("theme_mode", null)
            args.getString("captureTheme")?.let { check(themePrefs!!.edit().putString("theme_mode", it).commit()) }
        }
        override fun after() {
            themePrefs?.let { prefs ->
                val editor = prefs.edit()
                if (themeExisted) editor.putString("theme_mode", originalTheme) else editor.remove("theme_mode")
                check(editor.commit())
                assertEquals(themeExisted, prefs.contains("theme_mode"))
                assertEquals(originalTheme, prefs.getString("theme_mode", null))
            }
        }
    }
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(setup).around(rule)

    @Before
    fun seedAndOpen() = runBlocking(Dispatchers.IO) {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.scenario.onActivity { launcherIntent = Intent(it.intent) }
        val suffix = UUID.randomUUID().toString()
        characterId = database.characterDao().upsert(CharacterEntity(name = "AEX03-$suffix", personaPrompt = "AEX03 fixture"))
        val sessionId = database.sessionDao().insert(SessionEntity(title = "AEX03-$kind-$suffix"))
        session = SessionEntity(id = sessionId, title = "AEX03-$kind-$suffix")
        database.participantDao().upsert(SessionParticipantEntity(sessionId = sessionId, characterId = characterId))
        database.sessionWorldDao().upsert(SessionWorldEntity(sessionId = sessionId, gameplayMode = if (kind == "novel") "小说创作" else "自由剧情"))
        firstMessageId = database.messageDao().insert(
            MessageEntity(sessionId = sessionId, speakerType = if (kind == "novel") "narrator" else "user",
                characterId = if (kind == "novel") null else characterId,
                content = if (kind == "novel") "<NARRATION>$payload</NARRATION>" else payload,
                structuredContentJson = if (kind == "novel")
                    "{\"chapter_number\":1,\"chapter_title\":\"AEX03章节\"}" else "{}"),
        )
        if (kind == "chat") {
            val swipe = "aex03-swipe-${UUID.randomUUID()}"
            unselectedSwipeContent = "AEX03-未采用版本-${UUID.randomUUID()}"
            selectedSwipeContent = "AEX03-采用版本-${UUID.randomUUID()}"
            unselectedSwipeId = database.messageDao().insert(MessageEntity(
                sessionId = sessionId, speakerType = "character", characterId = characterId,
                parentMessageId = firstMessageId, swipeGroupId = swipe, includeInContext = true,
                content = unselectedSwipeContent,
            ))
            selectedSwipeId = database.messageDao().insert(MessageEntity(
                sessionId = sessionId, speakerType = "character", characterId = characterId,
                parentMessageId = firstMessageId, swipeGroupId = swipe, includeInContext = false,
                content = selectedSwipeContent,
            ))
            database.messageDao().upsertBranchSwipeSelectionRaw(
                com.mojing.app.data.local.entity.BranchSwipeSelectionEntity(sessionId, "main", swipe, selectedSwipeId),
            )
        } else {
            tailContent = "AEX03-尾声-${UUID.randomUUID()}"
            database.messageDao().insert(MessageEntity(
                sessionId = sessionId, speakerType = "character", characterId = characterId,
                parentMessageId = firstMessageId, content = tailContent,
                structuredContentJson = "{\"chapter_title\":\"尾声\"}",
            ))
            database.messageDao().insert(MessageEntity(sessionId = sessionId, speakerType = "user", content = omittedUser))
        }
        instrumentation.runOnMainSync {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mojing://chat")).apply {
                setClass(context, MainActivity::class.java)
                putExtra("navigate_to", "chat")
                putExtra("session_id", sessionId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        await(10_000) { uiExists(session.title) && uiExists("返回会话主页") }
        restoreLauncherIntent()
    }

    @After
    fun removeOnlyFixtures() = runBlocking(Dispatchers.IO) {
        documents.forEach { name -> runCatching { providerCall("release", name) } }
        documents.forEach { name -> runCatching { providerCall("deleteOwned", name) } }
        if (::session.isInitialized) database.sessionDao().delete(session.id)
        if (characterId != 0L) database.characterDao().delete(characterId)
        runCatching { providerCall("revokeControl", "") }
        Unit
    }

    @Test
    fun realPickerCancelAndRecreateAllowExportAgain() {
        beginPicker()
        capture("01-picker-before-cancel")
        back()
        assertMenuAvailable()
        val old = activity()
        beginPicker()
        instrumentation.runOnMainSync { old.recreate() }
        assertTrue(clickDocument("资料导出验收"))
        val name = prepare("normal")
        saveAs(name)
        awaitNotice(successNotice())
        assertTrue(old !== activity())
        verify(name)
        capture("02-picker-recreated-success")
    }

    @Test
    fun realProviderFailureAllowsNewTargetRetry() {
        val failed = prepare("fail")
        beginPicker(); saveAs(failed)
        awaitNotice("写入文件失败")
        assertEquals(0L, providerCall("status", failed)!!.getLong("bytes"))
        assertMenuAvailable()
        capture("03-write-error")
        val retry = prepare("normal")
        beginPicker(); saveAs(retry)
        awaitNotice(successNotice())
        verify(retry)
        capture("04-error-retry-success")
    }

    @Test
    fun realWriteRecreateAndStopLeaveCanRetryAfterReopen() {
        val interrupted = prepare("hold")
        beginPicker(); saveAs(interrupted)
        await(10_000) { providerCall("status", interrupted)!!.getBoolean("opened") }
        if (kind == "novel" && uiExists("关闭小说目录")) clickUi("关闭小说目录")
        capture("05-writing")
        val writeOwner = activity()
        instrumentation.runOnMainSync { writeOwner.recreate() }
        awaitNotice("上次导出已中断")
        assertTrue(writeOwner !== activity())
        capture("06-recreated-interruption")
        providerCall("release", interrupted)
        await(15_000) { providerCall("status", interrupted)!!.getBoolean("closed") }
        val retry = prepare("normal")
        beginPicker(); saveAs(retry)
        awaitNotice(successNotice()); verify(retry)
        capture("07-recreated-retry-success")
        val stopped = prepare("hold")
        beginPicker(); saveAs(stopped)
        await(10_000) { providerCall("status", stopped)!!.getBoolean("opened") }
        if (kind == "novel" && uiExists("关闭小说目录")) clickUi("关闭小说目录")
        back()
        awaitVisible("继续导出")
        capture("08-leave-guard")
        clickUi("继续导出")
        await(10_000) { !uiExists("继续导出") }
        back()
        awaitVisible("停止并离开")
        clickUi("停止并离开")
        awaitVisible("正在停止…")
        providerCall("release", stopped)
        await(15_000) { providerCall("status", stopped)!!.getBoolean("closed") }
        await(10_000) { !uiExists("返回会话主页") }
        openChatAgain()
        assertMenuAvailable()
        capture("09-stop-reopen")
        val final = prepare("normal")
        beginPicker(); saveAs(final)
        awaitNotice(successNotice()); verify(final)
        capture("10-stop-retry-success")
    }

    private fun activity(): MainActivity {
        var value: MainActivity? = null
        rule.scenario.onActivity { value = it }
        return checkNotNull(value)
    }

    private fun successNotice() = if (kind == "novel") "小说已导出" else "聊天记录已导出"

    private fun beginPicker() {
        if (kind == "novel") {
            if (!uiExists("导出小说 TXT")) openContents()
            clickUi("导出小说 TXT")
        } else {
            clickUi("会话菜单")
            clickUi("导出主线聊天记录…")
        }
        assertTrue(clickDocument("资料导出验收"))
    }

    private fun saveAs(name: String) {
        assertTrue(setDocumentText(name))
        assertTrue(clickDocument("保存"))
        await(8_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.mojing.app" }
        closeContents()
    }

    private fun prepare(mode: String): String {
        val name = "aex03-${UUID.randomUUID()}.${if (kind == "novel") "txt" else "json"}"
        documents += name
        providerCall("prepareMode", name, Bundle().apply { putString("mode", mode) })
        return name
    }

    private fun verify(name: String) {
        val uri = android.provider.DocumentsContract.buildDocumentUri(LibraryExportDocumentsProvider.AUTHORITY, name)
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        if (kind == "novel") {
            val text = bytes.toString(Charsets.UTF_8)
            assertEquals("${session.title}\n\n第 1 章 AEX03章节\n\n$payload\n\n尾声\n\n$tailContent\n\n", text)
            assertTrue(!text.contains("<NARRATION>"))
            assertTrue(!text.contains("</NARRATION>"))
            assertTrue(!text.contains(omittedUser))
        } else {
            val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            assertEquals("mojing_chat_export", root.get("format").asString)
            assertEquals(1, root.get("version").asInt)
            assertEquals(session.id, root.get("sessionId").asLong)
            val rows = root.getAsJsonArray("messages")
            assertEquals(3, rows.size())
            val contents = rows.map { it.asJsonObject.get("content").asString }
            assertEquals(setOf(payload, selectedSwipeContent, unselectedSwipeContent), contents.toSet())
            rows.forEach { row ->
                assertEquals(session.id, row.asJsonObject.get("sessionId").asLong)
                assertEquals("main", row.asJsonObject.get("branchId").asString)
                assertTrue(row.asJsonObject.get("id").asLong > 0L)
            }
            val selected = rows.first { it.asJsonObject.get("content").asString == selectedSwipeContent }.asJsonObject
            val unselected = rows.first { it.asJsonObject.get("content").asString == unselectedSwipeContent }.asJsonObject
            assertEquals(selectedSwipeId, selected.get("id").asLong)
            assertEquals(unselectedSwipeId, unselected.get("id").asLong)
            assertTrue(selected.get("includeInContext").asBoolean)
            assertTrue(!unselected.get("includeInContext").asBoolean)
            val raw = runBlocking(Dispatchers.IO) { database.messageDao().getMainBranchMessages(session.id).associateBy { it.id } }
            assertEquals(true, raw.getValue(unselectedSwipeId).includeInContext)
            assertEquals(false, raw.getValue(selectedSwipeId).includeInContext)
        }
        assertTrue(runBlocking(Dispatchers.IO) { database.sessionDao().getById(session.id) != null })
        assertEquals(if (kind == "novel") "<NARRATION>$payload</NARRATION>" else payload,
            runBlocking(Dispatchers.IO) { database.messageDao().getById(firstMessageId)!!.content })
    }

    private fun openChatAgain() {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mojing://chat")).apply {
            setClass(context, MainActivity::class.java)
            putExtra("navigate_to", "chat")
            putExtra("session_id", session.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        await(10_000) { uiExists(session.title) && uiExists("返回会话主页") }
        restoreLauncherIntent()
    }

    private fun restoreLauncherIntent() {
        launcherIntent?.let { original ->
            rule.scenario.onActivity { it.intent = Intent(original) }
        }
    }

    private fun assertMenuAvailable() {
        if (kind == "novel") {
            closeContents()
            openContents()
            assertUiEnabled("导出小说 TXT")
            closeContents()
            return
        }
        clickUi("会话菜单")
        assertUiEnabled("导出主线聊天记录…")
        back()
    }

    private fun openContents() {
        if (activity().resources.configuration.screenWidthDp >= 400) clickUi("目录")
        else {
            clickUi("会话菜单")
            clickUi("小说目录")
        }
    }

    private fun closeContents() {
        await(8_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.mojing.app" }
        if (kind == "novel" && uiExists("关闭小说目录")) {
            clickUi("关闭小说目录")
            await(8_000) { !uiExists("关闭小说目录") }
        }
    }

    private fun providerCall(method: String, name: String, extra: Bundle? = null): Bundle? {
        val uri = Uri.parse("content://${LibraryExportDocumentsProvider.AUTHORITY}")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            context.startActivity(Intent().setClassName("com.mojing.app.test", "com.mojing.app.test.LibraryExportFixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await(5_000) { context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        }
        return context.contentResolver.call(uri, method, name, extra)
    }

    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); instrumentation.waitForIdleSync() }

    private fun clickUi(text: String) {
        val clicked = clickDocument(text)
        if (!clicked) captureTimeout()
        assertTrue("UI action missing: $text", clicked)
        instrumentation.waitForIdleSync()
    }

    private fun clickDocument(text: String): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + 8_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val node = root?.let { findNode(it, text) ?: if (text == "保存") (findNode(it, "Save") ?: findNode(it, "SAVE")) else null }
            if (node != null) {
                var clickable: AccessibilityNodeInfo = node
                while (!clickable.isClickable) clickable = clickable.parent ?: break
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                if (text == "资料导出验收" && node.isVisibleToUser) return true
            } else if (text == "资料导出验收") {
                (root?.let { findNode(it, "Show roots") ?: findNode(it, "显示根目录") })?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    private fun setDocumentText(text: String): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + 8_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val field = instrumentation.uiAutomation.rootInActiveWindow?.let(::findEditable)
            if (field != null && field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })) return true
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString() == "android.widget.EditText" && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { findEditable(it)?.let { found -> return found } }
        return null
    }

    private fun findNode(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { findNode(it, text)?.let { found -> return found } }
        return null
    }

    private fun findNodeContaining(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString()?.contains(text) == true)) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { findNodeContaining(it, text)?.let { found -> return found } }
        return null
    }

    private fun uiExists(text: String): Boolean = instrumentation.uiAutomation.rootInActiveWindow?.let { findNode(it, text) } != null
    private fun uiContains(text: String): Boolean = instrumentation.uiAutomation.rootInActiveWindow?.let { findNodeContaining(it, text) } != null
    private fun awaitVisible(text: String) = await(12_000) { uiExists(text) }
    private fun awaitNotice(text: String) = await(12_000) { uiContains(text) }

    private fun assertUi(text: String) { awaitVisible(text) }
    private fun assertUiEnabled(text: String) {
        awaitVisible(text)
        var node = findNode(instrumentation.uiAutomation.rootInActiveWindow!!, text)!!
        while (!node.isClickable) node = node.parent ?: break
        assertTrue(node.isEnabled)
    }

    private fun await(timeout: Long, predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + timeout
        while (!predicate()) {
            if (android.os.SystemClock.uptimeMillis() >= deadline) {
                captureTimeout()
                error("condition timed out")
            }
            android.os.SystemClock.sleep(50)
        }
    }

    private fun capture(name: String) {
        await(8_000) {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() in
                setOf("com.mojing.app", "com.google.android.documentsui")
        }
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(args.getString("captureDelayMillis")?.toLong() ?: 250L)
        val run = args.getString("captureRun") ?: "aex03-chat-export"
        require(run.matches(Regex("[A-Za-z0-9._-]+")))
        val directory = java.io.File(context.getExternalFilesDir(null), run).apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { output ->
            assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun captureTimeout() {
        val run = args.getString("captureRun") ?: "aex03-chat-export"
        val directory = java.io.File(context.getExternalFilesDir(null), run).apply { mkdirs() }
        java.io.File(directory, "timeout-${android.os.SystemClock.uptimeMillis()}.png").outputStream().use { output ->
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}

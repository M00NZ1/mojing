package com.mojing.app.ui

import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.local.entity.CharacterEntity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in SAF acceptance; only UUID fixtures are removed, existing application data stays intact. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class LibraryExportAppCaptureTest {
    val rule = ActivityScenarioRule(MainActivity::class.java)
    private val activity: MainActivity get() {
        var value: MainActivity? = null
        rule.scenario.onActivity { value = it }
        return checkNotNull(value)
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val authority = "com.mojing.app.test.library.documents"
    private val database get() = EntryPointAccessors.fromApplication(context.applicationContext,
        PrototypeDatabaseEntryPoint::class.java).database()
    private val documents = mutableListOf<String>()
    private val notices = CopyOnWriteArrayList<String>()
    private var characterId = 0L
    private lateinit var fixtureName: String
    private val page get() = args.getString("libraryPage") ?: "characters"
    private val menuDescription = "更多"
    private val exportLabel get() = when (page) { "encyclopedias" -> "导出百科"; "templates" -> "导出模板"; else -> "导出角色" }
    private val payload = "本批导出恢复验证" + "内容".repeat(96_000)
    private var storage: com.mojing.app.data.SecureStorage? = null
    private var originalTheme: String? = null
    private var themePrefs: android.content.SharedPreferences? = null
    private var themeExisted = false
    private val setup = object : org.junit.rules.ExternalResource() {
        override fun before() {
            Assume.assumeTrue("libraryExportCapture=true required", args.getString("libraryExportCapture") == "true")
            Assume.assumeTrue("generic emulator only", Build.FINGERPRINT.contains("generic", true) || Build.MODEL.startsWith("sdk_gphone", true))
            storage = com.mojing.app.data.SecureStorage().also { it.init(context) }
            themePrefs = com.mojing.app.data.SecureStorage::class.java.getDeclaredField("prefs").also {
                it.isAccessible = true
            }.get(storage) as android.content.SharedPreferences
            themeExisted = themePrefs!!.contains("theme_mode")
            originalTheme = themePrefs!!.getString("theme_mode", null)
            args.getString("captureTheme")?.let { check(themePrefs!!.edit().putString("theme_mode", it).commit()) }
            instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
                if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
                    notices.add(event.text.joinToString(" "))
            }
        }
        override fun after() {
            instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
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

    @Before fun seedAndOpen() {
        // The launch overlay owns input even while underlying navigation has semantics.
        back()
        fixtureName = "AEX02-${UUID.randomUUID()}"
        require(page in setOf("characters", "encyclopedias", "templates"))
        characterId = runBlocking(Dispatchers.IO) {
            when (page) {
                "encyclopedias" -> database.encyclopediaDao().upsert(com.mojing.app.data.local.entity.EncyclopediaEntity(name = fixtureName, worldPrompt = payload))
                "templates" -> database.worldTemplateDao().upsert(com.mojing.app.data.local.entity.WorldTemplateEntity(label = fixtureName, worldPrompt = payload))
                else -> database.characterDao().upsert(CharacterEntity(name = fixtureName, personaPrompt = payload))
            }
        }
        clickUi("创作")
        openLibrary()
    }

    @After fun removeOnlyFixtures() {
        documents.forEach { name -> runCatching { call("release", name) } }
        documents.forEach { name -> runCatching { call("deleteOwned", name) } }
        if (characterId != 0L) runBlocking(Dispatchers.IO) { when (page) {
            "encyclopedias" -> database.encyclopediaDao().delete(characterId)
            "templates" -> database.worldTemplateDao().delete(characterId)
            else -> database.characterDao().delete(characterId)
        } }
        runCatching { call("revokeControl", "") }
    }

    @Test fun realPickerCancelAndPickerRecreateAllowExportAgain() {
        beginPicker()
        capture("01-picker-before-cancel")
        back(); backIfPickerStillVisible()
        assertMenuAvailable()
        val pickerOwner = activity
        beginPicker()
        // ActivityScenario.recreate() first demands RESUMED, while SAF correctly stops its owner.
        instrumentation.runOnMainSync { pickerOwner.recreate() }
        assertTrue(clickDocument("资料导出验收"))
        val name = prepare("normal")
        saveAs(name)
        awaitNotice("导出成功")
        assertNotSame(pickerOwner, activity)
        verifyExport(name)
        assertMenuAvailable()
        capture("02-picker-recreated-success")
    }

    @Test fun realProviderOpenFailureAllowsNewTargetRetry() {
        val failed = prepare("fail")
        beginPicker(); saveAs(failed)
        awaitNotice("写入导出文件失败")
        assertMenuAvailable()
        capture("03-write-error")
        assertEquals(0, call("status", failed)!!.getLong("bytes"))
        val retry = prepare("normal")
        beginPicker(); saveAs(retry)
        awaitNotice("导出成功")
        verifyExport(retry)
        assertMenuAvailable()
        capture("04-error-retry-success")
    }

    @Test fun realProviderWriteRecreateAndStopLeavingAllowNewExport() {
        val interrupted = prepare("hold")
        beginPicker(); saveAs(interrupted)
        awaitOpen(interrupted)
        capture("05-writing")
        rule.scenario.recreate()
        awaitNotice("上次导出已中断")
        call("release", interrupted)
        awaitClosed(interrupted)
        assertMenuAvailable()
        capture("06-recreated-interruption")
        val retry = prepare("normal")
        beginPicker(); saveAs(retry)
        awaitNotice("导出成功")
        verifyExport(retry)
        capture("07-recreated-retry-success")
        val stopped = prepare("hold")
        beginPicker(); saveAs(stopped)
        awaitOpen(stopped)
        back()
        assertUi("继续导出")
        capture("08-leave-guard")
        clickUi("继续导出")
        await(10_000) { !uiExists("继续导出") }
        back()
        assertUi("停止并离开")
        clickUi("停止并离开")
        assertUi("正在停止…")
        call("release", stopped)
        awaitClosed(stopped)
        await(10_000) { !uiExists("返回创作中心") }
        openLibrary()
        assertMenuAvailable()
        capture("09-stop-reopen")
        val final = prepare("normal")
        beginPicker(); saveAs(final)
        awaitNotice("导出成功")
        verifyExport(final)
        capture("10-stop-retry-success")
    }

    private fun prepare(mode: String): String {
        val name = "aex02-${UUID.randomUUID()}.json"
        documents += name
        call("prepareMode", name, Bundle().apply { putString("mode", mode) })
        return name
    }
    private fun call(method: String, name: String, extra: Bundle? = null): Bundle? {
        val uri = Uri.parse("content://$authority")
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            context.startActivity(android.content.Intent().setClassName("com.mojing.app.test", "com.mojing.app.test.LibraryExportFixtureActivity")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            while (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) != android.content.pm.PackageManager.PERMISSION_GRANTED && android.os.SystemClock.uptimeMillis() < deadline)
                android.os.SystemClock.sleep(20)
            check(context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        return context.contentResolver.call(uri, method, name, extra)
    }
    private fun beginPicker() {
        notices.clear()
        clickUi(menuDescription)
        clickUi(exportLabel)
        assertTrue(clickDocument("资料导出验收"))
    }
    private fun saveAs(name: String) {
        assertTrue(setDocumentText(name))
        assertTrue(clickDocument("保存"))
    }
    private fun awaitOpen(name: String) { await(10_000) { call("status", name)!!.getBoolean("opened") } }
    private fun awaitClosed(name: String) { await(15_000) { call("status", name)!!.getBoolean("closed") } }
    private fun awaitNotice(text: String) { await(12_000) { notices.any { it.contains(text) } } }
    private fun assertMenuAvailable() {
        assertUiEnabled(menuDescription); clickUi(menuDescription)
        assertUiEnabled(exportLabel)
        back()
    }
    private fun verifyExport(name: String) {
        val uri = android.provider.DocumentsContract.buildDocumentUri(authority, name)
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        assertEquals(if (page == "templates") 1 else 2, root.get("version").asInt)
        assertEquals(page, root.get("type").asString)
        val row = root.getAsJsonArray("data").map { it.asJsonObject }.single { it.get(if (page == "templates") "label" else "name").asString == fixtureName }
        assertEquals(payload, row.get(if (page == "characters") "personaPrompt" else "worldPrompt").asString)
        // Never serialize existing user records into frozen acceptance evidence.
        assertNotNull(runBlocking(Dispatchers.IO) { when (page) {
            "encyclopedias" -> database.encyclopediaDao().getById(characterId)
            "templates" -> database.worldTemplateDao().getById(characterId)
            else -> database.characterDao().getById(characterId)
        } })
    }
    private fun openLibrary() {
        val label = if (page == "characters") "角色" else "世界"
        await(10_000) { uiExists(label) }
        clickUi(label)
        await(10_000) { uiExists("返回创作中心") }
        if (page == "templates") {
            clickUi("更多")
            clickUi("世界工坊")
            await(10_000) { uiExists("设定工坊") }
        }
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); instrumentation.waitForIdleSync() }
    private fun backIfPickerStillVisible() {
        if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true) back()
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
                if (text == "资料导出验收" && node.isVisibleToUser && !clickable.isClickable) return true
            } else if (root != null && text == "资料导出验收") {
                (findNode(root, "Show roots") ?: findNode(root, "显示根目录"))?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            android.os.SystemClock.sleep(100)
        }
        return false
    }
    private fun setDocumentText(text: String): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + 8_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val field = root?.let { findEditable(it) }
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
    private fun await(timeout: Long, predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + timeout
        while (!predicate()) {
            if (android.os.SystemClock.uptimeMillis() >= deadline) {
                capture("timeout-${android.os.SystemClock.uptimeMillis()}")
                error("condition timed out")
            }
            android.os.SystemClock.sleep(50)
        }
    }
    private fun uiExists(text: String): Boolean = instrumentation.uiAutomation.rootInActiveWindow?.let { findNode(it, text) } != null
    private fun clickUi(text: String) { assertTrue("UI action missing: $text", clickDocument(text)); instrumentation.waitForIdleSync() }
    private fun assertUi(text: String) { await(10_000) { uiExists(text) } }
    private fun assertUiEnabled(text: String) {
        assertUi(text)
        var node = findNode(instrumentation.uiAutomation.rootInActiveWindow!!, text)!!
        while (!node.isClickable) node = node.parent ?: break
        assertTrue("disabled: $text", node.isEnabled)
    }
    private fun capture(name: String) {
        if (!name.startsWith("01-") && !name.startsWith("timeout-")) await(8_000) {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.mojing.app"
        }
        instrumentation.waitForIdleSync()
        // Accessibility can precede the first drawn dialog frame. This affects only capture,
        // never the writer/cancellation assertions or a business operation.
        val captureDelay = args.getString("captureDelayMillis")?.toLong() ?: 250L
        require(captureDelay in 0L..3_000L)
        android.os.SystemClock.sleep(captureDelay)
        val run = args.getString("captureRun") ?: "aex02"
        require(run.matches(Regex("[A-Za-z0-9._-]+")))
        val directory = java.io.File(context.getExternalFilesDir(null), run).apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { output ->
            assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}

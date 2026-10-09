package com.mojing.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
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

/** Opt-in complete App acceptance of touch/accessibility return-to-latest after real SAF import. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class ChatLatestAppCaptureTest {
    private val rule = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val database get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val documents = mutableListOf<String>()
    private lateinit var format: String
    private lateinit var session: SessionEntity
    private var characterId = 0L
    private var originalId = 0L
    private val originalContent = "AEX04-original-${UUID.randomUUID()}"
    private var launcherIntent: Intent? = null
    private var themePrefs: android.content.SharedPreferences? = null
    private var themeExisted = false
    private var originalTheme: String? = null
    private val setup = object : org.junit.rules.ExternalResource() {
        override fun before() {
            assumeTrue("chatImportCapture=true required", args.getString("chatImportCapture") == "true")
            assumeTrue("generic emulator only", Build.FINGERPRINT.contains("generic", true) || Build.MODEL.startsWith("sdk_gphone", true))
            format = args.getString("chatImportFormat") ?: "messages"
            require(format in setOf("mes", "messages", "jsonl"))
            val storage = com.mojing.app.data.SecureStorage().also { it.init(context) }
            themePrefs = com.mojing.app.data.SecureStorage::class.java.getDeclaredField("prefs").also { it.isAccessible = true }.get(storage) as android.content.SharedPreferences
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

    @Before fun seedAndOpen() = runBlocking(Dispatchers.IO) {
        back()
        rule.scenario.onActivity { launcherIntent = Intent(it.intent) }
        val suffix = UUID.randomUUID().toString()
        characterId = database.characterDao().upsert(CharacterEntity(name="AEX04-$suffix", personaPrompt="AEX04 fixture"))
        val id = database.sessionDao().insert(SessionEntity(title="AEX04-$format-$suffix"))
        session = SessionEntity(id=id, title="AEX04-$format-$suffix")
        database.participantDao().upsert(SessionParticipantEntity(sessionId=id, characterId=characterId))
        database.sessionWorldDao().upsert(SessionWorldEntity(sessionId=id, gameplayMode="自由剧情"))
        originalId = database.messageDao().insert(MessageEntity(sessionId=id, speakerType="user", content=originalContent))
        openChatAgain()
        Unit
    }

    @After fun removeOnlyFixtures() = runBlocking(Dispatchers.IO) {
        documents.forEach { name -> runCatching { providerCall("release", name) } }
        documents.forEach { name -> runCatching { providerCall("deleteOwned", name) } }
        if (::session.isInitialized) database.sessionDao().delete(session.id)
        if (characterId != 0L) database.characterDao().delete(characterId)
        runCatching { providerCall("revokeControl", "") }
        Unit
    }

    @Test fun realImportedLatestTouchAndAccessibility() {
        val fixture = fixture(300)
        prepare(fixture, "normal")
        beginPicker(); select(fixture); awaitNotice("已导入聊天记录 300 条")
        verifyImported(fixture)
        await(10_000) { !uiContains("已导入聊天记录 300 条") }
        capture("01-imported")
        diagnose("01-before-touch")
        val rect = latestBounds()
        assertTrue("latest button must have real layout bounds", rect.width() > 0 && rect.height() > 0)
        val x=rect.centerX(); val y=rect.centerY()
        evidence("touch.txt", "button=$rect DOWN/UP=($x,$y) source=TOUCHSCREEN")
        val time=android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event=android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true)); event.recycle()
        }
        instrumentation.waitForIdleSync()
        diagnose("02-after-touch"); capture("02-after-touch")
        awaitNotice(fixture.contents.last().substringBefore(' '))
        awaitNotice(fixture.contents.last().substringAfterLast(' '))
        assertTrue("must not open message operations", !uiExists("复制全文"))
        verifyImported(fixture)
        // Return to an earlier viewport using the actual list's accessibility scroll action.
        repeat(3) {
            val list=freshRoot()?.let { findNode(it,"对话正文") }
            assertTrue("history backward scroll", list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)==true)
            instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(300)
        }
        diagnose("03-before-accessibility"); capture("03-before-accessibility")
        clickUi("回到最新")
        awaitNotice(fixture.contents.last().substringBefore(' '))
        awaitNotice(fixture.contents.last().substringAfterLast(' '))
        diagnose("04-after-accessibility"); capture("04-after-accessibility")
        verifyImported(fixture)
        runBlocking(Dispatchers.IO) {
            val rows=database.messageDao().getMainBranchMessages(session.id).sortedBy { it.id }
            evidence("verified.json", JsonObject().apply {
                addProperty("sessionId", session.id); addProperty("originalId", originalId)
                addProperty("rowCount", rows.size); addProperty("lastId", rows.last().id)
                addProperty("lastMarker", fixture.contents.last().substringAfterLast(' '))
            }.toString())
        }
    }

    private fun freshRoot(): AccessibilityNodeInfo? {
        if (args.getString("refreshAccessibility") != "false" && Build.VERSION.SDK_INT >= 33) {
            check(instrumentation.uiAutomation.clearCache())
        }
        return instrumentation.uiAutomation.rootInActiveWindow
    }
    private fun evidence(name: String, text: String) {
        val run=args.getString("captureRun") ?: "aex05-latest"
        require(run.matches(Regex("[A-Za-z0-9._-]+")))
        java.io.File(context.getExternalFilesDir(null), run).apply { mkdirs() }.resolve(name).writeText(text)
    }
    private fun semantics(block: (SemanticsNode, android.view.View) -> Unit) {
        rule.scenario.onActivity { activity ->
            fun views(view: android.view.View) {
                val method=view.javaClass.methods.firstOrNull { it.name=="getSemanticsOwner" && it.parameterCount==0 }
                if(method!=null) {
                    val owner=method.invoke(view) as SemanticsOwner
                    fun nodes(node: SemanticsNode) { block(node,view); node.children.forEach(::nodes) }
                    nodes(owner.unmergedRootSemanticsNode)
                }
                if(view is android.view.ViewGroup) for(i in 0 until view.childCount) views(view.getChildAt(i))
            }
            views(activity.window.decorView)
        }
    }
    private fun label(node: SemanticsNode): String =
        (node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()+" "+
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()).trim()
    private fun latestBounds(): android.graphics.RectF {
        var result=android.graphics.RectF()
        semantics { node,view ->
            if(label(node).contains("回到最新")) {
                val loc=IntArray(2); view.getLocationOnScreen(loc)
                var target=node
                while (target.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)==null) {
                    target=target.parent ?: break
                }
                val b=target.boundsInRoot
                result=android.graphics.RectF(b.left+loc[0],b.top+loc[1],b.right+loc[0],b.bottom+loc[1])
            }
        }
        return result
    }
    private fun diagnose(name: String) {
        val lines=mutableListOf<String>()
        semantics { node,view ->
            val value=label(node)
            val safe=value.takeIf { it.contains("AEX04") || it.contains("回到最新") || it=="对话正文" }.orEmpty()
            val loc=IntArray(2); view.getLocationOnScreen(loc)
            lines+="compose id=${node.id} parent=${node.parent?.id} placed=${node.layoutInfo.isPlaced} clear=${node.config.isClearingSemantics} boundsRoot=${node.boundsInRoot} boundsWindow=${node.boundsInWindow} viewScreen=${loc.toList()} merge=${node.config.isMergingSemanticsOfDescendants} keys=${node.config.map { it.key.name }} label=$safe"
        }
        fun inspect(node: AccessibilityNodeInfo, depth:Int) {
            val text=node.text?.toString().orEmpty(); val desc=node.contentDescription?.toString().orEmpty()
            val safe=(text+" "+desc).takeIf { it.contains("AEX04") || it.contains("回到最新") || it.trim()=="对话正文" }.orEmpty()
            val rect=android.graphics.Rect(); node.getBoundsInScreen(rect)
            lines+="android depth=$depth window=${node.windowId} bounds=$rect visible=${node.isVisibleToUser} clickable=${node.isClickable} class=${node.className} label=$safe"
            for(i in 0 until node.childCount) node.getChild(i)?.let { inspect(it,depth+1) }
        }
        freshRoot()?.let { lines+="active package=${it.packageName} window=${it.windowId}"; inspect(it,0) }
        evidence("$name.txt",lines.joinToString("\n"))
    }

    private data class Fixture(val name: String, val text: String, val contents: List<String>, val prefix: Int)
    private fun fixture(count: Int): Fixture {
        val uuid = UUID.randomUUID().toString()
        val name = "aex04-$uuid.${if(format == "jsonl") "txt" else "json"}"
        documents += name
        val contents = (0 until count).map { "AEX04-$uuid-$it " + "导入正文。".repeat(if(count > 3) 45 else 1) +
            if (it == count - 1) " AEX05-END-$uuid-$it" else "" }
        val rows = contents.mapIndexed { index, content -> JsonObject().apply {
            if (format == "mes") { addProperty("is_user", index % 2 == 0); addProperty("mes", content) }
            else { addProperty("speakerType", if(index % 2 == 0) "user" else "character"); addProperty("content", content) }
        }.toString() }
        val prefixText = if(format == "jsonl") rows.take(160).joinToString("\n", postfix="\n")
            else "{\"$format\":[" + rows.take(160).joinToString(",", postfix=",")
        val text = if(format == "jsonl") rows.joinToString("\n", postfix="\n") else "{\"$format\":[" + rows.joinToString(",") + "]}"
        return Fixture(name, text, contents, if(count > 3) prefixText.toByteArray(Charsets.UTF_8).size else 0)
    }
    private fun prepare(fixture: Fixture, mode: String) {
        providerCall("prepareRead", fixture.name, Bundle().apply {
            putString("mode", mode); putByteArray("bytes", fixture.text.toByteArray(Charsets.UTF_8)); putInt("prefix", fixture.prefix)
        })
        awaitApp()
    }
    private fun beginPicker() {
        awaitApp(); clickUi("会话菜单"); clickUi("导入聊天记录…")
        assertTrue(clickDocument("资料导出验收"))
    }
    private fun select(fixture: Fixture) { clickUi(fixture.name); awaitApp() }
    private fun awaitApp() = await(10_000) { freshRoot()?.packageName?.toString() == "com.mojing.app" }
    private fun awaitHeld(fixture: Fixture, mode: String) {
        await(12_000) { providerCall("status", fixture.name)!!.getInt("readOpens") >= if(mode == "hold-first") 1 else 2 }
        awaitNotice(if(mode == "hold-first") "正在检查文件 · 128 条" else "正在写入记录 · 128 条")
    }
    private fun awaitClosed(fixture: Fixture) = await(12_000) { providerCall("status", fixture.name)!!.getBoolean("closed") }
    private fun verifyOriginalOnly() = runBlocking(Dispatchers.IO) {
        val rows = database.messageDao().getMainBranchMessages(session.id)
        assertEquals(1, rows.size); assertEquals(originalId, rows.single().id); assertEquals(originalContent, rows.single().content)
    }
    private fun verifyImported(fixture: Fixture) = runBlocking(Dispatchers.IO) {
        val rows = database.messageDao().getMainBranchMessages(session.id).sortedBy { it.id }
        assertEquals(fixture.contents.size + 1, rows.size)
        assertEquals(originalId, rows.first().id); assertEquals(originalContent, rows.first().content)
        val batches = mutableSetOf<String>()
        rows.drop(1).forEachIndexed { index, row ->
            assertEquals(session.id, row.sessionId); assertEquals("main", row.branchId)
            assertEquals(fixture.contents[index], row.content)
            assertEquals(if(index % 2 == 0) "user" else "character", row.speakerType)
            assertEquals(if(index % 2 == 0) null else characterId, row.characterId)
            val metadata = JsonParser.parseString(row.structuredContentJson).asJsonObject
            assertEquals(index, metadata["st_import_index"].asInt); batches += metadata["st_import_batch"].asString
        }
        assertEquals(1, batches.size)
        // The import contract refreshes a bounded tail window and preserves the viewport.
        // Verify that this batch is displayed; exact content/order/last row are checked in Room.
        awaitNotice(fixture.contents.last().substringBefore(' ').substringBeforeLast('-'))
    }
    private fun activity(): MainActivity {
        var result: MainActivity? = null
        rule.scenario.onActivity { result = it }
        return checkNotNull(result)
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
            val root = freshRoot()
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
            val field = freshRoot()?.let(::findEditable)
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

    private fun uiExists(text: String): Boolean = freshRoot()?.let { findNode(it, text) } != null
    private fun uiContains(text: String): Boolean = freshRoot()?.let { findNodeContaining(it, text) } != null
    private fun awaitVisible(text: String) = await(12_000) { uiExists(text) }
    private fun awaitNotice(text: String) = await(12_000) { uiContains(text) }

    private fun assertUi(text: String) { awaitVisible(text) }
    private fun assertUiEnabled(text: String) {
        awaitVisible(text)
        var node = findNode(freshRoot()!!, text)!!
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
            freshRoot()?.packageName?.toString() in
                setOf("com.mojing.app", "com.google.android.documentsui")
        }
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(args.getString("captureDelayMillis")?.toLong() ?: 250L)
        val run = args.getString("captureRun") ?: "aex04-chat-import"
        require(run.matches(Regex("[A-Za-z0-9._-]+")))
        val directory = java.io.File(context.getExternalFilesDir(null), run).apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { output ->
            assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun captureTimeout() {
        val run = args.getString("captureRun") ?: "aex04-chat-import"
        val directory = java.io.File(context.getExternalFilesDir(null), run).apply { mkdirs() }
        // Freeze only fixture markers and navigation semantics for failed-driver diagnosis.
        val nodes = mutableListOf<String>()
        fun inspect(node: AccessibilityNodeInfo) {
            val text = node.text?.toString().orEmpty()
            if (text.contains("AEX04") || text.contains("回到") || node.contentDescription?.toString()?.contains("回到") == true) {
                nodes += "visible=${node.isVisibleToUser} clickable=${node.isClickable} class=${node.className} text=$text description=${node.contentDescription}"
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::inspect)
        }
        freshRoot()?.let(::inspect)
        java.io.File(directory, "timeout-${android.os.SystemClock.uptimeMillis()}.txt").writeText(nodes.joinToString("\n"))
        java.io.File(directory, "timeout-${android.os.SystemClock.uptimeMillis()}.png").outputStream().use { output ->
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}

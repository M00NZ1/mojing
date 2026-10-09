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
import com.mojing.app.data.local.entity.*
import com.mojing.app.domain.usecase.MainBranchMediaBundleUseCase
import com.mojing.app.test.MediaBundleDocumentsProvider
import com.mojing.app.util.ChatAttachmentFiles
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Opt-in actual MainActivity -> SAF -> provider failures -> reselect -> atomic import. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class MediaBundleInterruptionAppCaptureTest {
    private val rule = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val database get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val documents = mutableListOf<String>()
    private val files = mutableListOf<File>()
    private lateinit var sourceSession: SessionEntity
    private lateinit var session: SessionEntity
    private var characterId = 0L
    private var originalId = 0L
    private lateinit var original: MessageEntity
    private lateinit var originalAttachment: MessageAttachmentEntity
    private lateinit var sourceRows: List<MessageEntity>
    private lateinit var sourceAttachments: List<MessageAttachmentEntity>
    private lateinit var validZip: ByteArray
    private lateinit var originalFile: File
    private lateinit var originalHash: String
    private var launcherIntent: Intent? = null
    private var themePrefs: android.content.SharedPreferences? = null
    private var themeExisted = false
    private var originalTheme: String? = null
    private var cacheBefore = emptySet<String>()
    private val setup = object : org.junit.rules.ExternalResource() {
        override fun before() {
            assumeTrue("mediaInterruptionCapture=true required", args.getString("mediaInterruptionCapture") == "true")
            assumeTrue("generic emulator only", Build.FINGERPRINT.contains("generic", true) || Build.MODEL.startsWith("sdk_gphone", true))
            val storage = com.mojing.app.data.SecureStorage().also { it.init(context) }
            themePrefs = com.mojing.app.data.SecureStorage::class.java.getDeclaredField("prefs").also { it.isAccessible = true }.get(storage) as android.content.SharedPreferences
            themeExisted = themePrefs!!.contains("theme_mode")
            originalTheme = themePrefs!!.getString("theme_mode", null)
            args.getString("captureTheme")?.let { check(themePrefs!!.edit().putString("theme_mode", it).commit()) }
        }
        override fun after() {
            themePrefs?.let { prefs ->
                val editor = prefs.edit()
                if(themeExisted) editor.putString("theme_mode",originalTheme) else editor.remove("theme_mode")
                check(editor.commit()); assertEquals(themeExisted,prefs.contains("theme_mode")); assertEquals(originalTheme,prefs.getString("theme_mode",null))
            }
        }
    }
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(setup).around(rule)

    @Before fun seedAndOpen() = runBlocking(Dispatchers.IO) {
        back(); rule.scenario.onActivity { launcherIntent=Intent(it.intent) }
        val uuid=UUID.randomUUID().toString()
        characterId=database.characterDao().upsert(CharacterEntity(name="AEX06-$uuid",personaPrompt="AEX06 fixture"))
        sourceSession=SessionEntity(id=database.sessionDao().insert(SessionEntity(title="AEX06-source-$uuid")),title="AEX06-source-$uuid")
        session=SessionEntity(id=database.sessionDao().insert(SessionEntity(title="AEX06-target-${uuid.take(8)}")),title="AEX06-target-${uuid.take(8)}")
        for(s in listOf(sourceSession,session)) {
            database.participantDao().upsert(SessionParticipantEntity(sessionId=s.id,characterId=characterId))
            database.sessionWorldDao().upsert(SessionWorldEntity(sessionId=s.id,gameplayMode="自由剧情"))
        }
        val png=File(context.cacheDir,"aex06-$uuid.png").also { files+=it }
        val bitmap=android.graphics.Bitmap.createBitmap(48,48,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(30,120,180))
        png.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        val parent=database.messageDao().insert(MessageEntity(sessionId=sourceSession.id,speakerType="character",characterId=characterId,content="AEX06 父消息-$uuid"))
        val image=database.messageDao().insert(MessageEntity(sessionId=sourceSession.id,speakerType="character",characterId=characterId,parentMessageId=parent,content="",includeInContext=false,structuredContentJson=com.mojing.app.data.local.AutoImageMetadata.create("AEX06 画面","complete",UUID.randomUUID().toString())))
        for(id in listOf(parent,image)) database.messageDao().insertMessageAttachmentRaw(MessageAttachmentEntity(messageId=id,assetType="image",fileName="aex06-$id.png",mimeType="image/png",storagePath=png.absolutePath))
        val voice=database.messageDao().insert(MessageEntity(sessionId=sourceSession.id,speakerType="character",characterId=characterId,parentMessageId=parent,content="",includeInContext=false,structuredContentJson=com.mojing.app.data.local.AutoVoiceMetadata.create("AEX06 语音","complete",UUID.randomUUID().toString())))
        repeat(2) { part ->
            val wav=File(context.cacheDir,"aex06-$uuid-$part.wav").also { files+=it; it.writeBytes(wav()) }
            database.messageDao().insertMessageAttachmentRaw(MessageAttachmentEntity(messageId=voice,assetType="voice",fileName="voice-$part.wav",mimeType="audio/wav",storagePath=wav.absolutePath))
        }
        database.messageDao().insert(MessageEntity(sessionId=sourceSession.id,speakerType="narrator",content="AEX06 旁白-$uuid"))
        originalId=database.messageDao().insert(MessageEntity(sessionId=session.id,speakerType="user",content="AEX06 原有消息-$uuid"))
        originalFile=File(context.filesDir,"attachments/${session.id}/aex06-original-$uuid.png").apply { check(parentFile!!.mkdirs() || parentFile!!.isDirectory); png.copyTo(this) }
        files+=originalFile; originalHash=sha(originalFile)
        database.messageDao().insertMessageAttachmentRaw(MessageAttachmentEntity(messageId=originalId,assetType="image",fileName="original.png",mimeType="image/png",storagePath=originalFile.absolutePath))
        original=database.messageDao().getMainBranchMessages(session.id).single()
        originalAttachment=database.attachmentDao().getByMessages(listOf(originalId)).single()
        sourceRows=database.messageDao().getMainBranchMessages(sourceSession.id).sortedBy { it.id }
        sourceAttachments=database.attachmentDao().getByMessages(sourceRows.map { it.id })
        val output=ByteArrayOutputStream()
        MainBranchMediaBundleUseCase(context,database.messageDao(),database.attachmentDao(),database.characterDao(),database.participantDao(),database.sessionDao(),database.sessionBranchDao(),database=database).exportBundle(output,sourceSession.id,{}, {_,_->})
        validZip=output.toByteArray()
        cacheBefore=cacheNames()
        openChatAgain(); Unit
    }
    @After fun removeOnlyFixtures() = runBlocking(Dispatchers.IO) {
        documents.forEach { runCatching { providerCall("releaseRead",it) } }
        documents.forEach { assertTrue(providerCall("deleteOwned",it)!!.getBoolean("deleted")) }
        val imported=if(::session.isInitialized) database.attachmentDao().getByMessages(database.messageDao().getMainBranchMessages(session.id).map { it.id }).map { it.storagePath } else emptyList()
        if(::session.isInitialized) database.sessionDao().delete(session.id)
        if(::sourceSession.isInitialized) database.sessionDao().delete(sourceSession.id)
        if(characterId>0L) database.characterDao().delete(characterId)
        imported.filter { it!=originalFile.absolutePath }.forEach { path ->
            assertEquals(0,database.attachmentDao().countByStoragePath(path))
            assertTrue(ChatAttachmentFiles.deleteOwnedPersistedMediaFile(context,session.id,path))
        }
        files.forEach { assertTrue(!it.exists() || it.delete()) }
        runCatching { providerCall("revokeControl","") }
        Unit
    }
    @Test fun realReadRecreateShowsRecoveryAndRetryDoesNotDuplicate() {
        val failed=prepare("fail-read"); beginPicker(); select(failed)
        await(12_000) { providerCall("readStatus",failed)!!.getInt("bytes")>0 }
        awaitNotice("正在检查文件"); capture("01-reading-before-recreate")
        val owner=activity(); instrumentation.runOnMainSync { owner.recreate() }
        await(12_000) { owner!==activity() && uiExists("返回会话主页") }
        providerCall("releaseRead",failed)
        await(12_000) { !uiExists("停止") && cacheNames()==cacheBefore }
        verifyOriginalOnly(); recordState("recreated")
        awaitNotice("上次媒体包导入已中断"); capture("02-recovery-notice")
        retryAndDuplicate("recovery")
        val importedRows=runBlocking(Dispatchers.IO) { database.messageDao().getMainBranchMessages(session.id) }
        val fresh=activity(); instrumentation.runOnMainSync { fresh.recreate() }
        await(12_000) { fresh!==activity() && uiExists("返回会话主页") }
        // A completed/duplicate result consumes the recovery marker; recreation must not report interruption.
        await(12_000) { !uiExists("停止") }; assertFalse(uiContains("上次媒体包导入已中断"))
        runBlocking(Dispatchers.IO) { assertEquals(importedRows,database.messageDao().getMainBranchMessages(session.id)) }
        verifyImported(); capture("06-success-recreated-no-false-notice")
    }
    @Test fun realPickerCancelRecreateDoesNotReportInterruptedImport() {
        prepare("normal"); beginPicker(); back(); awaitApp()
        verifyOriginalOnly(); val owner=activity(); instrumentation.runOnMainSync { owner.recreate() }
        await(12_000) { owner!==activity() && uiExists("返回会话主页") }
        assertFalse(uiContains("上次媒体包导入已中断")); verifyOriginalOnly(); capture("01-canceled-picker-recreated")
        retryAndDuplicate("picker-cancel")
    }
    @Test fun realReportedOpenFailureRecreateDoesNotReportStaleInterruption() {
        val failed=prepare("fail-open"); beginPicker(); select(failed); awaitNotice("媒体包操作失败")
        verifyOriginalOnly(); capture("01-reported-open-failure")
        val owner=activity(); instrumentation.runOnMainSync { owner.recreate() }
        await(12_000) { owner!==activity() && uiExists("返回会话主页") }
        assertFalse(uiContains("上次媒体包导入已中断")); verifyOriginalOnly(); assertNoRequestResidue(); capture("02-reported-failure-recreated")
        retryAndDuplicate("reported-failure")
    }
    private fun retryAndDuplicate(label:String) {
        val valid=prepare("normal"); beginPicker(); select(valid)
        awaitNotice("已导入 4 条记录和 4 个媒体"); verifyImported(); capture("04-$label-success")
        verifyDuplicate(valid,label)
    }
    private fun verifyDuplicate(valid:String,label:String) {
        val rows=runBlocking(Dispatchers.IO) { database.messageDao().getMainBranchMessages(session.id) }
        val media=runBlocking(Dispatchers.IO) { database.attachmentDao().getByMessages(rows.map { it.id }) }
        beginPicker(); select(valid); awaitNotice("未重复写入"); verifyImported()
        runBlocking(Dispatchers.IO) { assertEquals(rows,database.messageDao().getMainBranchMessages(session.id)); assertEquals(media,database.attachmentDao().getByMessages(rows.map { it.id })) }
        assertEquals(2,providerCall("readStatus",valid)!!.getInt("opens")); capture("05-$label-duplicate")
        evidence("verified-$label.json",com.google.gson.Gson().toJson(mapOf("messages" to rows.map { it.id },"attachments" to media.map { mapOf("id" to it.id,"sha256" to sha(File(it.storagePath))) },"duplicateUnchanged" to true,"singleSafOpenPerImport" to true)))
    }
    private fun prepare(mode:String):String {
        val name="aex06-${UUID.randomUUID()}.zip"; documents+=name
        providerCall("prepareRead",name,Bundle().apply { putString("mode",if(mode=="corrupt") "normal" else mode); putByteArray("bytes",if(mode=="corrupt") validZip.copyOf(validZip.size/2) else validZip) })
        awaitApp(); return name
    }
    private fun beginPicker() { awaitApp(); clickUi("会话菜单"); clickUi("导入主线记录与媒体…"); clickUi("选择媒体包"); assertTrue(clickDocument("媒体包验收")) }
    private fun select(name:String) { clickUi(name); awaitApp() }
    private fun verifyOriginalOnly()=runBlocking(Dispatchers.IO) { assertEquals(listOf(original),database.messageDao().getMainBranchMessages(session.id)); assertEquals(listOf(originalAttachment),database.attachmentDao().getByMessages(listOf(originalId))); assertEquals(originalHash,sha(originalFile)); verifySource() }
    private suspend fun verifySource() { assertEquals(sourceRows,database.messageDao().getMainBranchMessages(sourceSession.id)); assertEquals(sourceAttachments,database.attachmentDao().getByMessages(sourceRows.map { it.id })) }
    private fun verifyImported()=runBlocking(Dispatchers.IO) {
        val rows=database.messageDao().getMainBranchMessages(session.id).sortedBy { it.id }
        assertEquals(5,rows.size); assertEquals(original,rows.first()); assertEquals(originalHash,sha(originalFile)); verifySource()
        val imported=rows.drop(1); val batch=mutableSetOf<String>()
        imported.forEachIndexed { i,row ->
            val src=sourceRows[i]; assertEquals(session.id,row.sessionId); assertEquals("main",row.branchId); assertEquals(src.content,row.content); assertEquals(src.speakerType,row.speakerType); assertEquals(src.characterId,row.characterId); assertEquals(src.includeInContext,row.includeInContext); assertEquals(src.createdAt,row.createdAt)
            assertEquals(src.parentMessageId?.let { p-> imported[sourceRows.indexOfFirst { it.id==p }].id },row.parentMessageId)
            val meta=JsonParser.parseString(row.structuredContentJson).asJsonObject; assertEquals(src.id,meta["mojing_media_bundle_source"].asLong); batch+=meta["mojing_media_bundle_batch"].asString
            val media=database.attachmentDao().getByMessages(listOf(row.id)); val expected=sourceAttachments.filter { it.messageId==src.id }
            assertEquals(expected.size,media.size)
            media.zip(expected).forEach { (a,b) -> assertEquals(b.fileName,a.fileName); assertEquals(b.assetType,a.assetType); assertEquals(b.mimeType,a.mimeType); assertEquals(b.generationPrompt,a.generationPrompt); assertEquals(b.generationModel,a.generationModel); assertEquals(b.createdAt,a.createdAt); assertEquals(sha(File(b.storagePath)),sha(File(a.storagePath))) }
        }
        assertEquals(1,batch.size); assertNoRequestResidue(imported=true)
        Unit
    }
    private fun cacheNames()=File(context.cacheDir,"chat_media_bundle").listFiles()?.map { it.name }?.toSet().orEmpty()
    private fun assertNoRequestResidue(imported:Boolean=false) {
        assertEquals(cacheBefore,cacheNames())
        val paths=File(context.filesDir,"attachments/${session.id}").listFiles()?.filter { it.isFile }?.map { it.canonicalPath }?.toSet().orEmpty()
        val referenced=runBlocking(Dispatchers.IO) { database.attachmentDao().getByMessages(database.messageDao().getMainBranchMessages(session.id).map { it.id }).map { File(it.storagePath).canonicalPath }.toSet() }
        assertEquals(referenced,paths); if(!imported) assertEquals(setOf(originalFile.canonicalPath),paths)
    }
    private fun sha(file:File):String { val digest=MessageDigest.getInstance("SHA-256"); file.inputStream().use { input-> val buffer=ByteArray(8192); while(true) { val read=input.read(buffer); if(read<0) break; digest.update(buffer,0,read) } }; return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } }
    private fun wav():ByteArray { val n=3200; return java.nio.ByteBuffer.allocate(44+n).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { put("RIFF".toByteArray()); putInt(36+n); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16); put("data".toByteArray()); putInt(n); put(ByteArray(n)) }.array() }
    private fun activity():MainActivity { var result:MainActivity?=null; rule.scenario.onActivity { result=it }; return checkNotNull(result) }
    private fun openChatAgain() {
        context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("mojing://chat")).apply { setClass(context,MainActivity::class.java); putExtra("navigate_to","chat"); putExtra("session_id",session.id); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP) })
        await(10_000) { uiExists(session.title) && uiExists("返回会话主页") }; launcherIntent?.let { original-> rule.scenario.onActivity { it.intent=Intent(original) } }
    }
    private fun awaitApp()=await(10_000) { freshRoot()?.packageName?.toString()=="com.mojing.app" }
    private fun freshRoot():AccessibilityNodeInfo? { if(Build.VERSION.SDK_INT>=33) instrumentation.uiAutomation.clearCache(); return instrumentation.uiAutomation.rootInActiveWindow }
    private fun evidence(name:String,text:String) { val run=args.getString("captureRun") ?: "aex07-media-interruption"; require(run.matches(Regex("[A-Za-z0-9._-]+"))); File(File(context.getExternalFilesDir(null),run).apply { mkdirs() },name).writeText(text) }
    private fun recordState(name:String) {
        instrumentation.runOnMainSync {
            val stores=com.mojing.app.ui.chat.RetainedChatSessions.stores
            val entries=stores.javaClass.getDeclaredField("entries").also { it.isAccessible=true }.get(stores) as Map<*,*>
            val entry=entries[session.id]
            val vm=entry?.javaClass?.getDeclaredField("model")?.also { it.isAccessible=true }?.get(entry) as? com.mojing.app.ui.chat.ChatViewModel
            evidence("$name.json",com.google.gson.Gson().toJson(mapOf("ready" to vm?.state?.value?.isReady,"error" to vm?.state?.value?.error,"notice" to vm?.mediaBundleNotice?.value,"progress" to vm?.mediaBundleProgress?.value)))
        }
    }
    private fun providerCall(method: String, name: String, extra: Bundle? = null): Bundle? {
        val uri = Uri.parse("content://${MediaBundleDocumentsProvider.AUTHORITY}")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), flags) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            context.startActivity(Intent().setClassName("com.mojing.app.test", "com.mojing.app.test.MediaBundleFixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
                if (text == "媒体包验收" && node.isVisibleToUser) return true
            } else if (text == "媒体包验收") {
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
        val run = args.getString("captureRun") ?: "aex07-media-interruption"
        require(run.matches(Regex("[A-Za-z0-9._-]+")))
        val directory = java.io.File(context.getExternalFilesDir(null), run).apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { output ->
            assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun captureTimeout() {
        val run = args.getString("captureRun") ?: "aex07-media-interruption"
        val directory = java.io.File(context.getExternalFilesDir(null), run).apply { mkdirs() }
        // Freeze only fixture markers and navigation semantics for failed-driver diagnosis.
        val nodes = mutableListOf<String>()
        fun inspect(node: AccessibilityNodeInfo) {
            val text = node.text?.toString().orEmpty()
            if (text.contains("AEX06") || text.contains("回到") || node.contentDescription?.toString()?.contains("回到") == true) {
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

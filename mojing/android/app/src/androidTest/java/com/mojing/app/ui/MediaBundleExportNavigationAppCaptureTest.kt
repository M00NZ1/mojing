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
import com.mojing.app.test.LibraryExportDocumentsProvider
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

/** Opt-in real ZIP export cancellation and navigation; fresh local UUID fixtures only. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class MediaBundleExportNavigationAppCaptureTest {
    private val rule = ActivityScenarioRule(MainActivity::class.java)
    private val testName = org.junit.rules.TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val database get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val documents = mutableListOf<String>()
    private val files = mutableListOf<File>()
    private lateinit var sourceSession: SessionEntity
    private lateinit var session: SessionEntity
    private var encyclopediaId = 0L
    private var duplicateId = 0L
    private lateinit var roleName: String
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
            assumeTrue("mediaExportNavigationCapture=true required", args.getString("mediaExportNavigationCapture") == "true")
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
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(testName).around(setup).around(rule)

    @Before fun seedAndOpen() = runBlocking(Dispatchers.IO) {
        back(); rule.scenario.onActivity { launcherIntent=Intent(it.intent) }
        val uuid=UUID.randomUUID().toString()
        encyclopediaId=database.encyclopediaDao().upsert(EncyclopediaEntity(name="AEX11世界-$uuid"))
        roleName="AEX11-${uuid.take(8)}"
        characterId=database.characterDao().upsert(CharacterEntity(name=roleName,personaPrompt="AEX11来源角色",boundEncyclopediaId=encyclopediaId))
        sourceSession=SessionEntity(id=database.sessionDao().insert(SessionEntity(title="AEX11-source-$uuid")),title="AEX11-source-$uuid")
        session=SessionEntity(id=database.sessionDao().insert(SessionEntity(title="AEX11-target-${uuid.take(8)}")),title="AEX11-target-${uuid.take(8)}")
        for(s in listOf(sourceSession,session)) {
            if(s.id==sourceSession.id || duplicateId>0L) database.participantDao().upsert(SessionParticipantEntity(sessionId=s.id,characterId=characterId))
            if(s.id==session.id && duplicateId>0L) database.participantDao().upsert(SessionParticipantEntity(sessionId=s.id,characterId=duplicateId,sortOrder=1))
            database.sessionWorldDao().upsert(SessionWorldEntity(sessionId=s.id,encyclopediaId=encyclopediaId,gameplayMode="自由剧情"))
        }
        val png=File(context.cacheDir,"aex11-$uuid.png").also { files+=it }
        val bitmap=android.graphics.Bitmap.createBitmap(192,192,android.graphics.Bitmap.Config.ARGB_8888)
        val random=java.util.Random(9L)
        for(y in 0 until 192) for(x in 0 until 192) bitmap.setPixel(x,y,android.graphics.Color.rgb(random.nextInt(256),random.nextInt(256),random.nextInt(256)))
        png.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        val parent=database.messageDao().insert(MessageEntity(sessionId=sourceSession.id,speakerType="character",characterId=characterId,content="AEX11 父消息-$uuid"))
        val image=database.messageDao().insert(MessageEntity(sessionId=sourceSession.id,speakerType="character",characterId=characterId,parentMessageId=parent,content="",includeInContext=false,structuredContentJson=com.mojing.app.data.local.AutoImageMetadata.create("AEX11 画面","complete",UUID.randomUUID().toString())))
        for(id in listOf(parent,image)) database.messageDao().insertMessageAttachmentRaw(MessageAttachmentEntity(messageId=id,assetType="image",fileName="aex11-$id.png",mimeType="image/png",storagePath=png.absolutePath))
        val voice=database.messageDao().insert(MessageEntity(sessionId=sourceSession.id,speakerType="character",characterId=characterId,parentMessageId=parent,content="",includeInContext=false,structuredContentJson=com.mojing.app.data.local.AutoVoiceMetadata.create("AEX11 语音","complete",UUID.randomUUID().toString())))
        repeat(2) { part ->
            val wav=File(context.cacheDir,"aex11-$uuid-$part.wav").also { files+=it; it.writeBytes(wav()) }
            database.messageDao().insertMessageAttachmentRaw(MessageAttachmentEntity(messageId=voice,assetType="voice",fileName="voice-$part.wav",mimeType="audio/wav",storagePath=wav.absolutePath))
        }
        database.messageDao().insert(MessageEntity(sessionId=sourceSession.id,speakerType="narrator",content="AEX11 旁白-$uuid"))
        originalId=database.messageDao().insert(MessageEntity(sessionId=session.id,speakerType="user",content="AEX11 原有消息-$uuid"))
        originalFile=File(context.filesDir,"attachments/${session.id}/aex11-original-$uuid.png").apply { check(parentFile!!.mkdirs() || parentFile!!.isDirectory); png.copyTo(this) }
        files+=originalFile; originalHash=sha(originalFile)
        database.messageDao().insertMessageAttachmentRaw(MessageAttachmentEntity(messageId=originalId,assetType="image",fileName="original.png",mimeType="image/png",storagePath=originalFile.absolutePath))
        original=database.messageDao().getMainBranchMessages(session.id).single()
        originalAttachment=database.attachmentDao().getByMessages(listOf(originalId)).single()
        sourceRows=database.messageDao().getMainBranchMessages(sourceSession.id).sortedBy { it.id }
        sourceAttachments=database.attachmentDao().getByMessages(sourceRows.map { it.id })
        val output=ByteArrayOutputStream()
        MainBranchMediaBundleUseCase(context,database.messageDao(),database.attachmentDao(),database.characterDao(),database.participantDao(),database.sessionDao(),database.sessionBranchDao(),database=database).exportBundle(output,sourceSession.id,{}, {_,_->})
        validZip=output.toByteArray()
        evidence("seed-${testName.methodName}.json",com.google.gson.Gson().toJson(mapOf("sourceSession" to sourceSession.id,"targetSession" to session.id,"character" to characterId,"duplicateCharacter" to duplicateId,"world" to encyclopediaId,"uuid" to uuid)))
        cacheBefore=cacheNames()
        openChatAgain(); Unit
    }
    @After fun removeOnlyFixtures() = runBlocking(Dispatchers.IO) {
        documents.forEach { runCatching { providerCall("release",it) } }
        documents.forEach { providerCall("deleteOwned",it) }
        val imported=if(::session.isInitialized) database.attachmentDao().getByMessages(database.messageDao().getMainBranchMessages(session.id).map { it.id }).map { it.storagePath } else emptyList()
        if(::session.isInitialized) database.sessionDao().delete(session.id)
        if(::sourceSession.isInitialized) database.sessionDao().delete(sourceSession.id)
        if(characterId>0L) database.characterDao().delete(characterId)
        if(duplicateId>0L) database.characterDao().delete(duplicateId)
        if(encyclopediaId>0L) database.encyclopediaDao().delete(encyclopediaId)
        imported.filter { it!=originalFile.absolutePath }.forEach { path ->
            assertEquals(0,database.attachmentDao().countByStoragePath(path))
            assertTrue(ChatAttachmentFiles.deleteOwnedPersistedMediaFile(context,session.id,path))
        }
        files.forEach { assertTrue(!it.exists() || it.delete()) }
        val draftStore=com.mojing.app.data.CharacterEditDraftStore(context)
        if(duplicateId>0L) assertNull(draftStore.load(duplicateId))
        assertNull(database.sessionDao().getById(session.id))
        assertNull(database.sessionDao().getById(sourceSession.id))
        assertNull(database.characterDao().getById(characterId))
        assertNull(database.encyclopediaDao().getById(encyclopediaId))
        evidence("cleanup-${testName.methodName}.json",com.google.gson.Gson().toJson(mapOf("fixtureRowsRemoved" to true,"fixtureFilesRemoved" to true,"editorDraftAbsent" to true)))
        runCatching { providerCall("revokeControl","") }
        Unit
    }
    @Test fun realNavigationContinueCompletesWithoutLeaving() {
        val held=startHeld(); clickUi("返回会话主页"); awaitVisible("继续导出")
        assertUi("停止并离开"); capture("02-navigation-confirm")
        clickUi("继续导出"); await(8_000) { !uiExists("继续导出") }
        assertUi("返回会话主页"); assertUi("停止"); capture("03-continue-writing")
        providerCall("release",held); awaitNotice("已导出主线记录与媒体")
        verifyExport(held); verifyOriginalOnly(); assertNoRequestResidue(); assertUi("返回会话主页"); capture("04-completed-still-in-chat")
    }
    @Test fun realStopShowsPartialTargetWarningAndNewTargetExport() {
        val held=startHeld(); clickUi("停止"); awaitNotice("正在停止"); capture("02-stopping")
        providerCall("release",held); awaitNotice("媒体包导出已停止，目标文件可能不完整，请重新导出")
        await(12_000) { providerCall("status",held)!!.getBoolean("closed") && cacheNames()==cacheBefore }
        verifyOriginalOnly(); assertNoRequestResidue(); capture("03-stopped-partial-target-warning"); retry("stop")
    }
    @Test fun realNavigationStopLeavesAfterCleanupThenReenterAndExport() {
        val held=startHeld(); clickUi("返回会话主页"); awaitVisible("停止并离开"); capture("02-navigation-confirm")
        clickUi("停止并离开"); awaitNotice("正在停止导出"); capture("03-waiting-before-leave")
        providerCall("release",held)
        await(15_000) { providerCall("status",held)!!.getBoolean("closed") && cacheNames()==cacheBefore && !uiExists("返回会话主页") }
        verifyOriginalOnly(); assertNoRequestResidue(); capture("04-left-after-cleanup")
        openChatAgain(); assertFalse(uiContains("上次媒体包导出已中断")); capture("05-reentered"); retry("leave")
    }
    private fun startHeld():String {
        val held=prepare("hold"); beginPicker(); saveAs(held)
        await(12_000) { providerCall("status",held)!!.getBoolean("opened") }
        awaitNotice("正在写出媒体包"); capture("01-writing"); return held
    }
    private fun retry(label:String) {
        val valid=prepare("normal"); beginPicker(); saveAs(valid); awaitNotice("已导出主线记录与媒体")
        verifyExport(valid); verifyOriginalOnly(); assertNoRequestResidue(); capture("06-"+label+"-success")
    }
    private fun verifyExport(name:String)=runBlocking(Dispatchers.IO) {
        val bytes=providerCall("read",name)!!.getByteArray("bytes")!!
        assertTrue(bytes.size>65536)
        val root=File(context.cacheDir,"aex11-check-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val archive=File(root,"export.zip").apply { writeBytes(bytes) }
            com.mojing.app.domain.chat.MainBranchMediaBundleCodec.open(archive,File(root,"validation")).use { handle ->
                var rowCount=0; var mediaCount=0
                com.mojing.app.domain.chat.MainBranchMediaBundleCodec.readMessages(handle) { row ->
                    val src=sourceRows[rowCount++]; assertEquals(src.id,row.id); assertEquals(src.content,row.content); assertEquals(src.parentMessageId,row.parentMessageId); assertEquals(src.characterId,row.characterId); assertEquals(src.speakerType,row.speakerType); assertEquals(src.includeInContext,row.includeInContext); assertEquals(src.createdAt,row.createdAt)
                    assertEquals(JsonParser.parseString(src.structuredContentJson),JsonParser.parseString(row.structuredContentJson))
                    com.mojing.app.domain.chat.MainBranchMediaBundleCodec.readMediaForMessage(handle,row.id) { media ->
                        val expected=sourceAttachments.filter { it.messageId==row.id }[if(row.id==sourceRows[2].id) mediaCount-2 else 0]
                        assertEquals(sha(File(expected.storagePath)),media.sha256.lowercase()); assertEquals(expected.fileName,media.fileName); assertEquals(expected.assetType,media.assetType); assertEquals(expected.mimeType,media.mimeType); assertEquals(expected.createdAt,media.createdAt); mediaCount++
                    }
                }
                assertEquals(4,rowCount); assertEquals(4,mediaCount)
            }
            evidence("verified-${testName.methodName}.json",com.google.gson.Gson().toJson(mapOf("zipBytes" to bytes.size,"zipSha256" to sha(archive),"messages" to 4,"media" to 4,"sourceUnchanged" to true)))
        } finally {
            assertEquals(context.cacheDir.canonicalFile,root.canonicalFile.parentFile)
            root.walkBottomUp().forEach { assertTrue(it.delete()) }
        }
    }
    private fun prepare(mode:String):String {
        val name="aex09-${UUID.randomUUID()}.zip"
        providerCall("prepareMode",name,Bundle().apply { putString("mode",mode) }); documents+=name; awaitApp(); return name
    }
    private fun beginPicker() { awaitApp(); clickUi("会话菜单"); clickUi("导出主线记录与媒体…"); assertTrue(clickDocument("资料导出验收")) }
    private fun saveAs(name:String) { assertTrue(setDocumentText(name)); clickUi("保存"); awaitApp() }
    private fun verifyOriginalOnly()=runBlocking(Dispatchers.IO) { assertEquals(listOf(original),database.messageDao().getMainBranchMessages(session.id)); assertEquals(listOf(originalAttachment),database.attachmentDao().getByMessages(listOf(originalId))); assertEquals(originalHash,sha(originalFile)); verifySource() }
    private suspend fun verifySource() { assertEquals(sourceRows,database.messageDao().getMainBranchMessages(sourceSession.id)); assertEquals(sourceAttachments,database.attachmentDao().getByMessages(sourceRows.map { it.id })) }
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
        context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("mojing://chat")).apply { setClass(context,MainActivity::class.java); putExtra("navigate_to","chat"); putExtra("session_id",sourceSession.id); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP) })
        await(10_000) { uiExists(sourceSession.title) && uiExists("返回会话主页") }; launcherIntent?.let { original-> rule.scenario.onActivity { it.intent=Intent(original) } }
    }
    private fun awaitApp()=await(10_000) { freshRoot()?.packageName?.toString()=="com.mojing.app" }
    private fun freshRoot():AccessibilityNodeInfo? { if(Build.VERSION.SDK_INT>=33) instrumentation.uiAutomation.clearCache(); return instrumentation.uiAutomation.rootInActiveWindow }
    private fun evidence(name:String,text:String) { val run=args.getString("captureRun") ?: "aex11-media-export"; require(run.matches(Regex("[A-Za-z0-9._-]+"))); File(File(context.getExternalFilesDir(null),run).apply { mkdirs() },name).writeText(text) }
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
        // Search text can equal the candidate label; clicks must choose the result, not EditText.
        if (node.isVisibleToUser && !node.isEditable && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
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
        val run = args.getString("captureRun") ?: "aex11-media-export"
        require(run.matches(Regex("[A-Za-z0-9._-]+")))
        val directory = java.io.File(context.getExternalFilesDir(null), run).apply { mkdirs() }
        java.io.File(directory, "${testName.methodName}-$name.png").outputStream().use { output ->
            assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun captureTimeout() {
        val run = args.getString("captureRun") ?: "aex11-media-export"
        val directory = java.io.File(context.getExternalFilesDir(null), run).apply { mkdirs() }
        // Freeze only fixture markers and navigation semantics for failed-driver diagnosis.
        val nodes = mutableListOf<String>()
        fun inspect(node: AccessibilityNodeInfo) {
            val text = node.text?.toString().orEmpty()
            if (text.contains("AEX11") || text.contains("回到") || node.contentDescription?.toString()?.contains("回到") == true) {
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

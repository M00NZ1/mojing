package com.mojing.app.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import coil.imageLoader
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.ModelPlatform
import com.mojing.app.data.ModelPlatformCodec
import com.mojing.app.data.StoryOpeningInputDraftStore
import com.mojing.app.data.local.entity.*
import com.mojing.app.ui.chat.ChatViewModel
import com.mojing.app.ui.chat.RetainedChatSessions
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** MainActivity first-turn requests; host runs independent rollback even after a failed flow. */
class MediaFailureAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("mediaFailureRun").orEmpty().also { UUID.fromString(it) }
    private val scenario get() = args.getString("scenario").orEmpty()
    private val title get() = "续章入口${run.take(8)}"
    private val marker get() = "PARENT_MEMORY_$run"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "media-failure-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "media-failure-$run").apply { mkdirs() }
    private val preferences get() = rule.activity.secureStorage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(rule.activity.secureStorage) as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun guard(idle: Boolean = true) {
        assumeTrue(args.getString("mediaFailureCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        if (idle) check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
        check(scenario in listOf("large"))
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "media-failure-$run.pending")
        FileOutputStream(pending).use { it.write(j.toString().toByteArray()); it.fd.sync() }
        check(pending.renameTo(journalFile))
    }
    private fun snapshot(p: SharedPreferences) = JsonObject().apply { p.all.forEach { (k, v) -> add(k, JsonObject().apply {
        when (v) { is String -> { addProperty("type", "string"); addProperty("value", v) }
            is Boolean -> { addProperty("type", "boolean"); addProperty("value", v) }; else -> error("unexpected draft type") }
    }) } }
    private fun assertSnapshot(p: SharedPreferences, expected: JsonObject) {
        check(p.all.keys == expected.keySet())
        expected.entrySet().forEach { (k, v) -> val row = v.asJsonObject
            if (row["type"].asString == "string") check(p.all[k] is String && p.getString(k, null) == row["value"].asString)
            else check(p.all[k] is Boolean && p.getBoolean(k, false) == row["value"].asBoolean)
        }
    }
    private fun branches() = io { JsonObject().apply {
        com.mojing.app.data.prefs.UiPreferencesRepository(context).lastChatBranches.first().forEach { (id, b) -> addProperty(id.toString(), b) }
    } }
    private fun digest() = io {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        listOf("sessions", "messages", "session_branches", "session_context_memories", "world_encyclopedias",
            "encyclopedia_entries", "characters", "character_profiles", "world_templates", "timeline_events", "message_attachments").forEach { table ->
            hash.update(table.toByteArray())
            db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { c ->
                while (c.moveToNext()) for (index in 0 until c.columnCount) { hash.update((c.getString(index) ?: "<null>").toByteArray()); hash.update(0.toByte()) }
            }
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun mediaFilesDigest(): String = io {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        db.openHelper.readableDatabase.query("SELECT DISTINCT storagePath FROM message_attachments ORDER BY storagePath").use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0)
                hash.update(path.toByteArray())
                if (!path.contains("://")) {
                    val file = File(path)
                    hash.update(if (file.isFile) file.inputStream().use { input ->
                        val fileHash = java.security.MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(32768)
                        while (true) { val n=input.read(buffer); if(n<0) break; fileHash.update(buffer,0,n) }
                        fileHash.digest()
                    } else "missing".toByteArray())
                }
            }
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
    private class LocalImages : java.io.Closeable {
        @Volatile var recovered = false
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = java.util.concurrent.CountDownLatch(1)
        val requested = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(1)
        private val socket = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        private val pool = java.util.concurrent.Executors.newCachedThreadPool()
        val base = "http://127.0.0.1:${socket.localPort}"
        init { pool.submit {
            while (!socket.isClosed) try {
                val client=socket.accept()
                pool.submit { client.use { s ->
                    s.soTimeout=10000
                    val reader=s.getInputStream().bufferedReader()
                    val path=reader.readLine().split(" ")[1]
                    while (!reader.readLine().isNullOrEmpty()) { }
                    if (!recovered && (path.contains("missing") || path.contains("corrupt"))) {
                        failures.incrementAndGet()
                        val bytes="invalid image bytes".toByteArray()
                        val status=if(path.contains("missing")) "404 Not Found" else "200 OK"
                        s.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: image/png\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                        return@submit
                    }
                    val late=path.contains("late")
                    if(late) { requested.countDown(); check(gate.await(30,java.util.concurrent.TimeUnit.SECONDS)) }
                    val kind=path.substringAfterLast('/').substringBefore('?')
                    val (w,h)=when(kind) { "wide" -> 1200 to 240; "tall" -> 240 to 1200; else -> 600 to 600 }
                    val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                    val canvas=android.graphics.Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.rgb(45,85,105))
                    val paint=android.graphics.Paint().apply { color=android.graphics.Color.rgb(235,210,150) }
                    canvas.drawRect(w*.1f,h*.15f,w*.9f,h*.85f,paint)
                    val bytes=java.io.ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG,100,out); out.toByteArray() }
                    bitmap.recycle()
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                    if(late) completed.countDown()
                } }
            } catch (_:java.net.SocketException) { }
        } }
        override fun close() { gate.countDown(); socket.close(); pool.shutdownNow(); pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS) }
    }
    private fun model(id: Long): ChatViewModel = checkNotNull(modelOrNull(id))
    private fun modelOrNull(id: Long): ChatViewModel? {
        val stores = RetainedChatSessions.stores
        val entries = stores.javaClass.getDeclaredField("entries").apply { isAccessible = true }.get(stores) as Map<*, *>
        val entry = entries[id] ?: return null
        return entry.javaClass.getDeclaredField("model").apply { isAccessible = true }.get(entry) as ChatViewModel
    }
    private fun waitText(text: String) = rule.waitUntil(10000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun screenshot(label: String) {
        rule.waitForIdle(); inst.waitForIdleSync()
        repeat(2) { val frame = java.util.concurrent.CountDownLatch(1)
            rule.runOnUiThread { rule.activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }; rule.activity.window.decorView.invalidate() }
            check(frame.await(3, java.util.concurrent.TimeUnit.SECONDS))
        }
        val b = checkNotNull(inst.uiAutomation.takeScreenshot())
        try { FileOutputStream(output.resolve("$label.png")).use { check(b.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { b.recycle() }
    }
    private fun assertNarratorHeader() {
        if (args.getString("headerCapture") != "true") return
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onAllNodesWithText("子线旁白-$run", useUnmergedTree = true).onLast().assertIsDisplayed()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        check(layouts.single().lineCount == 1 && layouts.single().isLineEllipsized(0))
        val nameBounds = rule.onAllNodesWithText("子线旁白-$run", useUnmergedTree = true).onLast().fetchSemanticsNode().boundsInRoot
        val timeNodes = rule.onAllNodes(SemanticsMatcher("timestamp") { node ->
            node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                .singleOrNull()?.text?.matches(Regex("[0-9]{1,2}:[0-9]{2}")) == true
        }, useUnmergedTree = true).fetchSemanticsNodes().filter { kotlin.math.abs(it.boundsInRoot.center.y - nameBounds.center.y) < 1f }
        check(timeNodes.isNotEmpty())
        timeNodes.forEach { node ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val layout = results.single()
            check(layout.lineCount == 1 && layout.getLineEnd(0) == layout.layoutInput.text.length &&
                layout.getLineLeft(0) >= 0f && layout.getLineRight(0) <= layout.size.width && !layout.didOverflowHeight) {
                "header timestamp: size=${layout.size} lineRight=${layout.getLineRight(0)} left=${layout.getLineLeft(0)} end=${layout.getLineEnd(0)} length=${layout.layoutInput.text.length} heightOverflow=${layout.didOverflowHeight}"
            }
        }
    }
    private fun openStory(id: Long) {
        rule.waitUntil(10000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasText("对话") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, Role.Tab)).performClick()
        waitText("故事库")
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(title)
        rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0) }
        rule.waitUntil(10000) { rule.onAllNodesWithText(title).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(title).filter(!hasSetTextAction()).onFirst().performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() && model(id).state.value.isReady }
    }

    @Test fun mediaFailureRequestAndPersistence() {
        guard(); check(!journalFile.exists())
        val base = args.getString("localBase").orEmpty(); check(base.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/v1")))
        val j = JsonObject().apply {
            addProperty("run", run); addProperty("scenario", scenario); addProperty("phase", "snapshot")
            add("originalConfig", JsonObject().apply { configKeys.forEach { k -> addProperty(k, if (preferences.contains(k)) preferences.getString(k, null) else null) } })
            add("drafts", JsonObject().apply { draftNames.forEach { n -> add(n, snapshot(context.getSharedPreferences(n, 0))) } })
            add("branches", branches()); addProperty("digest", digest()); addProperty("mediaFilesDigest",mediaFilesDigest())
        }; persist(j)
        val images = LocalImages()
        try {
            val cid = io { db.characterDao().upsert(CharacterEntity(name = "子线角色-$run", personaPrompt = "SYNTHETIC_$run")) }
            j.addProperty("character", cid); persist(j)
            val sid = io { db.sessionDao().insert(SessionEntity(title = title, creationRequestId = run)) }
            j.addProperty("session", sid); persist(j)
            val ids=mutableListOf<Long>()
            val local=args.getString("localFiles")=="true"
            val owned=listOf(File(context.filesDir,"media-failure-$run-missing.png"),File(context.filesDir,"media-failure-$run-corrupt.png"))
            if(local) {
                check(owned.none { it.exists() })
                j.add("ownedFiles",com.google.gson.JsonArray().apply { owned.forEach { add(it.absolutePath) } }); persist(j)
                owned[1].writeBytes("invalid local png".toByteArray())
            }
            fun path(index:Int)=if(local && index<2) owned[index].absolutePath else "${images.base}/${listOf("missing","corrupt","wide")[index]}?run=$run"
            fun cacheKey(index:Int)=if(local && index<2) "${owned[index].absolutePath}#${owned[index].lastModified()}" else path(index)
            fun recover() {
                images.recovered=true
                if(local) owned.forEach { file ->
                    val b=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888)
                    b.eraseColor(android.graphics.Color.rgb(45,85,105))
                    try { FileOutputStream(file).use { check(b.compress(Bitmap.CompressFormat.PNG,100,it)); it.fd.sync() } } finally { b.recycle() }
                }
            }
            val names=listOf("缺失的用户图片", "无法解码的角色配图", "正常角色配图")
            io { db.withTransaction {
                db.participantDao().upsert(SessionParticipantEntity(sessionId=sid,characterId=cid))
                db.sessionWorldDao().upsert(SessionWorldEntity(sessionId=sid,narratorEnabled=false,gameplayMode="角色扮演",autoSedimentEnabled=false))
                names.forEachIndexed { index,name ->
                    val id=db.messageDao().insert(MessageEntity(sessionId=sid,speakerType=if(index==0) "user" else "character",characterId=if(index==0) null else cid,content="正文保留${index+1}"))
                    ids.add(id)
                    db.attachmentDao().insert(MessageAttachmentEntity(messageId=id,fileName=name,mimeType="image/png",storagePath=path(index)))
                }
            } }
            openStory(sid)
            rule.waitUntil(15000) { model(sid).state.value.messages.size==3 }
            val list=rule.onNodeWithContentDescription("对话正文")
            val draft="未发送图片失败草稿-$run"
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).performTextReplacement(draft)
            rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken,0); rule.activity.currentFocus?.clearFocus() }
            rule.waitUntil(10000) { rule.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }
            val metrics=JsonObject()
            val repaired=args.getString("repaired")=="true"
            names.take(2).forEachIndexed { index,name ->
                list.performScrollToNode(hasContentDescription(name))
                rule.onNodeWithText("正文保留${index+1}").assertIsDisplayed()
                if(repaired) {
                    waitText("图片无法读取")
                    rule.onNodeWithContentDescription("重新加载$name").assertIsDisplayed()
                    if(index==0) {
                        val before=images.failures.get()
                        rule.onNodeWithContentDescription("重新加载$name").performClick()
                        if(!local) rule.waitUntil(10000) { images.failures.get()>before }
                        rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("重新加载$name").fetchSemanticsNodes().isNotEmpty() }
                        rule.onNodeWithText("正文保留1").assertIsDisplayed()
                        metrics.addProperty("failedRetryPreserved",true)
                    }
                } else {
                    // Wait for an independent Coil request to confirm this URL fails, then observe the actual UI.
                    val result=io { context.imageLoader.execute(coil.request.ImageRequest.Builder(context).data("${images.base}/${if(index==0) "missing" else "corrupt"}?run=$run").build()) }
                    check(result is coil.request.ErrorResult)
                    rule.waitForIdle()
                    val bounds=rule.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot
                    metrics.addProperty("height$index",bounds.height)
                    metrics.addProperty("errorText$index",rule.onAllNodesWithText("图片无法读取").fetchSemanticsNodes().isNotEmpty())
                }
                screenshot("0${index+1}-failed-${index}")
                // The image center now contains the retry button. Tap its image area to open the preview.
                rule.onNodeWithContentDescription(name).performTouchInput { click(androidx.compose.ui.geometry.Offset(20f,20f)) }
                rule.waitUntil(10000) { rule.onAllNodesWithTag("image_preview").fetchSemanticsNodes().isNotEmpty() }
                rule.waitUntil(10000) { rule.onAllNodes(hasText("图片无法读取") and hasAnyAncestor(hasTestTag("image_preview"))).fetchSemanticsNodes().isNotEmpty() }
                screenshot("0${index+3}-preview-failed-${index}")
                if(repaired) {
                    val previewRetry=rule.onNode(hasText("重新加载") and hasAnyAncestor(hasTestTag("image_preview")))
                    previewRetry.assertIsDisplayed()
                    if(index==1) {
                        recover()
                        previewRetry.performClick()
                        rule.waitUntil(10000) { rule.onAllNodes(hasText("图片无法读取") and hasAnyAncestor(hasTestTag("image_preview"))).fetchSemanticsNodes().isEmpty() }
                        screenshot("05-preview-recovered")
                        rule.waitUntil(10000) { context.imageLoader.memoryCache?.get(coil.memory.MemoryCache.Key(cacheKey(1)))!=null }
                    }
                }
                inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                rule.waitUntil(10000) { rule.onAllNodesWithTag("image_preview").fetchSemanticsNodes().isEmpty() }
                if(repaired && index==1) {
                    rule.onNodeWithContentDescription("重新加载$name").performClick()
                    rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("重新加载$name").fetchSemanticsNodes().isEmpty() }
                }
                rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft)
            }
            if(repaired) {
                list.performScrollToNode(hasContentDescription(names[0]))
                rule.onNodeWithContentDescription("重新加载${names[0]}").performClick()
                rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("重新加载${names[0]}").fetchSemanticsNodes().isEmpty() }
                screenshot("06-user-recovered")
                rule.waitUntil(10000) { context.imageLoader.memoryCache?.get(coil.memory.MemoryCache.Key(cacheKey(0)))!=null }
            }
            list.performScrollToNode(hasContentDescription(names[2]))
            rule.onNodeWithContentDescription(names[2]).performClick()
            rule.onNodeWithTag("image_preview").assertIsDisplayed()
            rule.waitUntil(10000) { context.imageLoader.memoryCache?.get(coil.memory.MemoryCache.Key("${images.base}/wide?run=$run"))!=null }
            screenshot("07-normal-preview")
            rule.onNodeWithContentDescription("关闭图片预览").performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithTag("image_preview").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithContentDescription("返回会话主页").performClick(); waitText("故事库")
            rule.onAllNodesWithText(title).filter(!hasSetTextAction()).onFirst().performClick()
            rule.waitUntil(10000) { modelOrNull(sid)?.state?.value?.let { it.isReady && it.messages.size==3 }==true }
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft)
            io { ids.forEachIndexed { index,id -> check(db.messageDao().getById(id)?.content=="正文保留${index+1}") } }
            screenshot("08-reentry")
            metrics.addProperty("draftReentry",true); metrics.addProperty("rawMessagesPreserved",true); metrics.addProperty("repaired",repaired); metrics.addProperty("localFiles",local)
            output.resolve("metrics.json").writeText(metrics.toString())
            j.addProperty("phase","verified"); persist(j)
        } catch (t: Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString()); runCatching { screenshot("failure") }; throw t } finally { images.close() }
    }

    @Test fun rollbackOnlyOwnedBranchMemory() {
        guard(idle = false)
        val j = JsonParser.parseString(journalFile.readText()).asJsonObject; check(j["run"].asString == run)
        val sid = j["session"]?.asLong ?: io { db.sessionDao().getByCreationRequestId(run)?.id } ?: 0L
        val cid = j["character"]?.asLong ?: 0L
        check(RetainedChatSessions.running.value.all { it == sid })
        if (sid > 0L) {
            inst.runOnMainSync { RetainedChatSessions.stores.stop(sid) }
            rule.waitUntil(10000) { sid !in RetainedChatSessions.running.value }
            io { db.withTransaction {
                val owned = db.sessionDao().getById(sid); check(owned == null || owned.creationRequestId == run)
                db.openHelper.writableDatabase.execSQL("DELETE FROM llm_cost_records WHERE sessionId = ?", arrayOf(sid))
                db.sessionDao().delete(sid)
            } }
        }
        if (cid > 0L) io { db.characterDao().getById(cid)?.let { check(it.name == "子线角色-$run" && it.personaPrompt == "SYNTHETIC_$run"); db.characterDao().delete(cid) } }
        val editor = preferences.edit(); j.getAsJsonObject("originalConfig").entrySet().forEach { (k, v) -> if (v.isJsonNull) editor.remove(k) else editor.putString(k, v.asString) }; check(editor.commit())
        j.getAsJsonObject("originalConfig").entrySet().forEach { (k, v) -> check(preferences.contains(k) == !v.isJsonNull); if (!v.isJsonNull) check(preferences.getString(k, null) == v.asString) }
        if (sid > 0L) {
            val drafts = context.getSharedPreferences("chat_drafts_v1", 0)
            val original = j.getAsJsonObject("drafts").getAsJsonObject("chat_drafts_v1")
            val edit = drafts.edit()
            drafts.all.keys.filter { k -> k in listOf("session_$sid", "reply_recovery_v1_session_$sid") || k.startsWith("chapter_input_v1_${sid}_") }.forEach { k -> check(!original.has(k)); edit.remove(k) }
            check(edit.commit()); io { com.mojing.app.data.prefs.UiPreferencesRepository(context).clearLastChatBranch(sid) }
        }
        draftNames.forEach { n -> assertSnapshot(context.getSharedPreferences(n, 0), j.getAsJsonObject("drafts").getAsJsonObject(n)) }
        j.getAsJsonArray("ownedFiles")?.forEach { element ->
            val file=File(element.asString)
            check(file.parentFile==context.filesDir && file.name in listOf("media-failure-$run-missing.png","media-failure-$run-corrupt.png"))
            if(file.exists()) check(file.isFile && file.delete())
            check(!file.exists())
        }
        check(branches() == j.getAsJsonObject("branches")); check(digest() == j["digest"].asString); check(mediaFilesDigest() == j["mediaFilesDigest"].asString)
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("uuidOnlyRemoved=true\nconfigPresenceValuesExact=true\nfourDraftsExact=true\nbranchPrefsExact=true\nelevenTablesDigestExact=true\noriginalMediaFilesDigestExact=true\n")
    }
}

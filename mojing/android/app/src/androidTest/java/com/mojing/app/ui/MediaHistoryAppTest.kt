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
class MediaHistoryAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("mediaHistoryRun").orEmpty().also { UUID.fromString(it) }
    private val scenario get() = args.getString("scenario").orEmpty()
    private val title get() = "续章入口${run.take(8)}"
    private val marker get() = "PARENT_MEMORY_$run"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "media-history-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "media-history-$run").apply { mkdirs() }
    private val preferences get() = rule.activity.secureStorage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(rule.activity.secureStorage) as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun guard(idle: Boolean = true) {
        assumeTrue(args.getString("mediaHistoryCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        if (idle) check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
        check(scenario in listOf("large"))
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "media-history-$run.pending")
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

    @Test fun mediaHistoryRequestAndPersistence() {
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
            val count = if (scenario == "large") 4000 else 80
            var oldId = 0L
            var lastId = 0L
            var chars = 0L
            var estimatedTokens = 0L
            val metrics = JsonObject().apply { addProperty("count", count); addProperty("model", Build.MODEL); addProperty("sdk", Build.VERSION.SDK_INT) }
            val insertStart = android.os.SystemClock.elapsedRealtime()
            io { db.withTransaction {
                db.participantDao().upsert(SessionParticipantEntity(sessionId = sid, characterId = cid))
                db.sessionWorldDao().upsert(SessionWorldEntity(sessionId = sid, narratorEnabled = false, narratorName = "历史旁白",
                    gameplayMode = "小说创作", choiceGenerationEnabled = false, autoSedimentEnabled = false))
                repeat(count) { index ->
                    val n = index + 1
                    val heading = "第${n}章 雨城${n}"
                    val body = if(n >= 3998) "灯影下的阅读锚点$n。" else when (n % 4) {
                        0 -> "雨落在青石街巷。她收起信纸，穿过灯影，仍记得城外旧桥与昨日的约定。\n\n".repeat(32)
                        1 -> "她在旧桥等他。"
                        2 -> "> 灯塔的约定\n\n- 信纸仍在\n- 船尚未归\n\n**雨城纪事**\n\n" + "守灯人记录昨日的风向与远行的约定。".repeat(18)
                        else -> "第一段：雨声。\n\n第二段：他在船舷写下日期。\n\n第三段：她仍然相信旧桥的约定。"
                    }
                    val content = heading + "\n\n" + body + "\nINDEX_${n}_$run"
                    chars += content.length
                    estimatedTokens += com.mojing.app.domain.engine.TokenCounter.estimate(content)
                    val id = db.messageDao().insert(MessageEntity(sessionId = sid, speakerType = if(n == 3998 || n == 4000 || n == 20 || n % 40 == 0) "character" else "narrator", characterId = cid, content = content,
                        structuredContentJson = com.mojing.app.domain.story.NovelChapter.metadata("{\"synthetic_extra\":{\"locked\":true,\"run\":\"$run\"}}", n, heading)))
                    if(n == 3998 || n == 4000 || n == 20 || n % 40 == 0) {
                        val kind=if(n==3998) "late" else listOf("wide","tall","square")[n%3]
                        db.attachmentDao().insert(MessageAttachmentEntity(messageId=id,fileName="合成图片$n-$kind",mimeType="image/png",storagePath="${images.base}/$kind?run=$run&n=$n"))
                    }
                    if (n == 20) oldId = id
                    lastId = id
                }
            } }
            metrics.addProperty("insertMs", android.os.SystemClock.elapsedRealtime()-insertStart)
            metrics.addProperty("characters", chars); metrics.addProperty("estimatedTokens", estimatedTokens)
            if (scenario == "large") check(estimatedTokens >= 1_000_000)
            val platform = ModelPlatform("branch-$run", "本机子线验证", base, "local-branch-only", listOf("branch-$run"), "branch-$run", mapOf("branch-$run" to 100000))
            check(preferences.edit().putString("public_api_key", platform.apiKey).putString("public_base_url", base).putString("public_model", platform.selectedModel)
                .putString("model_platforms_v1", ModelPlatformCodec.encode(listOf(platform))).putString("active_model_platform", platform.id).putString("speaker_turn_mode", "auto").commit())
            val openStart = android.os.SystemClock.elapsedRealtime()
            openStory(sid)
            rule.waitUntil(20000) { model(sid).state.value.messages.lastOrNull()?.id == lastId }
            rule.waitForIdle()
            metrics.addProperty("openStoryUiMs", android.os.SystemClock.elapsedRealtime()-openStart)
            metrics.addProperty("initialWindowMessages", model(sid).state.value.messages.size)
            check(model(sid).state.value.messages.size <= 160)
            val anchor = rule.onAllNodesWithText("灯影下的阅读锚点3999。",substring=true).onFirst()
            val bodyList = rule.onNodeWithContentDescription("对话正文")
            bodyList.performScrollToNode(hasContentDescription("合成图片3998-late"))
            check(images.requested.await(10,java.util.concurrent.TimeUnit.SECONDS))
            bodyList.performScrollToNode(hasText("灯影下的阅读锚点3999。",substring=true))
            anchor.assertIsDisplayed()
            rule.waitForIdle()
            val before = anchor.fetchSemanticsNode().boundsInRoot.top
            screenshot("01-before-image")
            images.gate.countDown()
            check(images.completed.await(10,java.util.concurrent.TimeUnit.SECONDS))
            rule.waitForIdle()
            // Memory cache admission confirms Coil decoded the gated image; it may be above the anchor viewport.
            rule.waitUntil(10000) { context.imageLoader.memoryCache?.get(coil.memory.MemoryCache.Key("${images.base}/late?run=$run&n=3998")) != null }
            rule.waitForIdle()
            val after = anchor.fetchSemanticsNode().boundsInRoot.top
            metrics.addProperty("anchorBeforePx",before); metrics.addProperty("anchorAfterPx",after)
            metrics.addProperty("anchorDeltaPx",after-before)
            anchor.assertIsDisplayed()
            check(kotlin.math.abs(after-before) <= 2f) { "reading anchor moved: $before -> $after" }
            screenshot("02-after-image")
            val list = rule.onNodeWithContentDescription("对话正文")
            val pages = com.google.gson.JsonArray()
            repeat(5) { page ->
                // Dispatch actual touch drags, then use LazyColumn's UI scroll action to its page control.
                repeat(3) { list.performTouchInput { swipeDown() }; rule.waitForIdle() }
                list.performScrollToNode(hasText("加载更早消息"))
                rule.onNodeWithText("加载更早消息").assertIsDisplayed()
                val before = model(sid).state.value.messages.first().id
                rule.onNodeWithText("加载更早消息").performClick()
                rule.waitUntil(20000) { !model(sid).state.value.isLoadingHistory && model(sid).state.value.messages.first().id < before }
                rule.waitForIdle()
                val state = model(sid).state.value
                check(state.messages.size <= 200)
                check(state.messages.zipWithNext().all { (a,b) -> a.id < b.id })
                pages.add(JsonObject().apply { addProperty("page",page); addProperty("firstId",state.messages.first().id); addProperty("lastId",state.messages.last().id); addProperty("window",state.messages.size) })
            }
            screenshot("03-media-older")
            repeat(1) { page ->
                list.performScrollToNode(hasText("加载较新消息"))
                rule.onNodeWithText("加载较新消息").assertIsDisplayed()
                val before = model(sid).state.value.messages.last().id
                rule.onNodeWithText("加载较新消息").performClick()
                rule.waitUntil(20000) { !model(sid).state.value.isLoadingHistory && model(sid).state.value.messages.last().id > before }
                rule.waitForIdle()
                check(model(sid).state.value.messages.size <= 200)
            }
            metrics.add("olderPages",pages)
            screenshot("04-media-newer")
            rule.onAllNodesWithContentDescription("小说目录").onFirst().performClick()
            waitText("第${count}章 雨城${count}")
            rule.onNodeWithContentDescription("搜索小说目录", useUnmergedTree = true).performTextReplacement("第二十章")
            waitText("第20章 雨城20")
            rule.onNodeWithContentDescription("搜索小说目录", useUnmergedTree = true).performImeAction()
            rule.waitUntil(10000) { rule.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }
            rule.onAllNodesWithText("第20章 雨城20").onFirst().performClick()
            rule.waitUntil(20000) { model(sid).state.value.messages.any { it.id == oldId } && model(sid).state.value.hasNewerMessages && rule.onAllNodesWithText("小说目录").fetchSemanticsNodes().isEmpty() }
            rule.onAllNodesWithText("第20章 雨城20", substring = true).onFirst().assertIsDisplayed()
            screenshot("05-old-media-chapter")
            val latestStart = android.os.SystemClock.elapsedRealtime()
            rule.onAllNodesWithContentDescription("回到最新").onFirst().performClick()
            rule.waitUntil(20000) { model(sid).state.value.messages.lastOrNull()?.id == lastId && !model(sid).state.value.hasNewerMessages }
            rule.waitForIdle()
            metrics.addProperty("latestUiMs", android.os.SystemClock.elapsedRealtime()-latestStart)
            check(model(sid).state.value.messages.size <= 160)
            screenshot("05-return-latest-$scenario")
            io { db.openHelper.readableDatabase.query("SELECT COUNT(*), SUM(LENGTH(content)) FROM messages WHERE sessionId = ?", arrayOf(sid)).use { c ->
                c.moveToFirst(); check(c.getInt(0) == count && c.getLong(1) == chars)
            } }
            val draft="未发送媒体阅读草稿-$run"
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).performTextReplacement(draft)
            rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken,0); rule.activity.currentFocus?.clearFocus() }
            rule.waitUntil(10000) { rule.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }
            rule.onNodeWithContentDescription("返回会话主页").performClick(); waitText("故事库")
            rule.onAllNodesWithText(title).filter(!hasSetTextAction()).onFirst().performClick()
            rule.waitUntil(20000) { modelOrNull(sid)?.state?.value?.let { it.isReady && it.messages.lastOrNull()?.id == lastId } == true }
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft)
            bodyList.performScrollToNode(hasContentDescription("合成图片4000-tall"))
            rule.onNodeWithContentDescription("合成图片4000-tall").assertIsDisplayed()
            screenshot("07-media-reentry")
            metrics.addProperty("mediaAttachments",io { db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM message_attachments WHERE messageId IN (SELECT id FROM messages WHERE sessionId=?)",arrayOf(sid)).use { c -> c.moveToFirst(); c.getInt(0) } })
            metrics.addProperty("draftReentry",true)
            output.resolve("metrics.json").writeText(metrics.toString())
            j.addProperty("phase", "verified"); persist(j)
            output.resolve("verified.txt").writeText("scenario=$scenario\ncount=$count\nrawHistoryPreserved=true\nwindowBounded=true\noldChapterSearchAndJump=true\nreturnedLatest=true\n")
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
        check(branches() == j.getAsJsonObject("branches")); check(digest() == j["digest"].asString); check(mediaFilesDigest() == j["mediaFilesDigest"].asString)
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("uuidOnlyRemoved=true\nconfigPresenceValuesExact=true\nfourDraftsExact=true\nbranchPrefsExact=true\nelevenTablesDigestExact=true\noriginalMediaFilesDigestExact=true\n")
    }
}

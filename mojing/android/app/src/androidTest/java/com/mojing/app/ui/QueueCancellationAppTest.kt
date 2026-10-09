package com.mojing.app.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.StoryOpeningInputDraftStore
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.ui.chat.RetainedChatSessions
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import com.mojing.app.data.ModelPlatform
import com.mojing.app.data.ModelPlatformCodec

class QueueCancellationAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("queueCancelRun").orEmpty().also { UUID.fromString(it) }
    private val title get() = "草稿航图${run.take(8)}"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val journalFile get() = File(context.filesDir, "queue-cancel-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "queue-cancel-$run").apply { mkdirs() }
    private fun guard() {
        check(args.getString("queueCancelCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "queue-cancel-$run.pending")
        FileOutputStream(pending).use { it.write(j.toString().toByteArray()); it.fd.sync() }
        check(pending.renameTo(journalFile))
    }
    private fun journal() = JsonParser.parseString(journalFile.readText()).asJsonObject.also { check(it.get("run").asString == run) }
    private fun snapshot(p: SharedPreferences) = JsonObject().apply {
        p.all.forEach { (key, value) -> add(key, JsonObject().apply {
            when (value) {
                is String -> { addProperty("type", "string"); addProperty("value", value) }
                is Boolean -> { addProperty("type", "boolean"); addProperty("value", value) }
                else -> error("unexpected draft type")
            }
        }) }
    }
    private fun assertUnchanged(p: SharedPreferences, original: JsonObject) {
        check(p.all.keys == original.keySet())
        original.entrySet().forEach { (key, value) ->
            val row = value.asJsonObject
            if (row.get("type").asString == "string") check(p.all[key] is String && p.getString(key, null) == row.get("value").asString)
            else check(p.all[key] is Boolean && p.getBoolean(key, false) == row.get("value").asBoolean)
        }
    }
    private fun waitText(text: String) {
        rule.waitUntil(10000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun hideKeyboard() {
        rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0) }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle(); inst.waitForIdleSync()
        repeat(2) {
            val committed = CountDownLatch(1)
            rule.runOnUiThread {
                val decor = rule.activity.window.decorView
                decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }; decor.invalidate()
            }
            check(committed.await(3, TimeUnit.SECONDS))
        }
        inst.uiAutomation.waitForIdle(300, 5000)
        val bitmap = checkNotNull(inst.uiAutomation.takeScreenshot())
        try { FileOutputStream(output.resolve("$name.png")).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private val dep get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java)
    private val preferences get() = rule.activity.secureStorage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(rule.activity.secureStorage) as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform")
    private val model get() = "queue-cancel-$run"
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun configure(base: String, capacity: Int) {
        val p = ModelPlatform("queue-cancel-$run", "本机取消夹具", base, "local-queue-cancel-only", listOf(model), model, mapOf(model to capacity))
        check(preferences.edit().putString("public_api_key", p.apiKey).putString("public_base_url", base).putString("public_model", model)
            .putString("model_platforms_v1", ModelPlatformCodec.encode(listOf(p))).putString("active_model_platform", p.id).commit())
    }
    private fun branches() = io { JsonObject().apply {
        com.mojing.app.data.prefs.UiPreferencesRepository(context).lastChatBranches.first().forEach { (id, branch) -> addProperty(id.toString(), branch) }
    } }
    private fun digest(): String = io {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        listOf("world_encyclopedias", "encyclopedia_entries", "characters", "character_profiles", "world_templates", "timeline_events").forEach { table ->
            db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { c ->
                while (c.moveToNext()) for (index in 0 until c.columnCount) { hash.update((c.getString(index) ?: "<null>").toByteArray()); hash.update(0.toByte()) }
            }
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun task(id: Long) = io { checkNotNull(db.generationTaskDao().getById(id)) }
    private fun waitStatus(id: Long, status: String) = rule.waitUntil(20000) { task(id).status == status }
    private fun openTasks() {
        rule.waitUntil(10000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithContentDescription("生成记录").performClick()
        waitText("生成记录")
    }
    private fun back() { inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); rule.waitForIdle() }
    private fun recordTask(j: JsonObject, id: Long): Long {
        j.getAsJsonArray("tasks").add(id); persist(j); return id
    }
    private fun metaTask(j: JsonObject, ids: List<Long>, label: String) = recordTask(j, io {
        dep.processor().enqueueEncyclopediaMetaFill(j["world"].asLong, label, ids)
    })

    @Test fun fullTaskPageCancellationAndRetry() {
        guard(); check(!journalFile.exists()); check(io { dep.processor().countActiveTasks() } == 0); check(!dep.processor().isQueuePaused())
        val j = JsonObject().apply {
            addProperty("run", run); addProperty("digest", digest()); add("branches", branches()); add("tasks", com.google.gson.JsonArray())
            add("originalConfig", JsonObject().apply { configKeys.forEach { key -> preferences.getString(key, null)?.let { addProperty(key, it) } ?: add(key, com.google.gson.JsonNull.INSTANCE) } })
            add("drafts", JsonObject().apply { listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1").forEach { add(it, snapshot(context.getSharedPreferences(it, 0))) } })
        }
        persist(j)
        val wid = io { db.encyclopediaDao().upsert(EncyclopediaEntity(name = title, description = "QUEUE_CANCEL_WORLD_$run")) }; j.addProperty("world", wid); persist(j)
        val entries = (1..4).map { index -> io { db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId = wid, title = "QUEUE_ENTRY_${index}_$run", entryType = "location", content = "原文_$run", metaJson = "{\"unknown\":\"$run\"}")) } }
        j.add("entries", com.google.gson.JsonArray().apply { entries.forEach { add(it) } }); persist(j)
        val cid = io { db.characterDao().upsert(com.mojing.app.data.local.entity.CharacterEntity(name = "QUEUE_CHARACTER_$run", personaPrompt = "原人设_$run")) }; j.addProperty("character", cid); persist(j)
        val template = io { db.worldTemplateDao().upsert(com.mojing.app.data.local.entity.WorldTemplateEntity(templateId = "queue-$run", label = "QUEUE_TEMPLATE_$run", summary = "原摘要_$run", worldPrompt = "原世界书_$run")) }; j.addProperty("template", template); persist(j)
        val originalCharacter = io { db.characterDao().getById(cid)!! }
        val originalTemplate = io { db.worldTemplateDao().getById(template)!! }
        val calls = AtomicInteger(0); val closed = AtomicInteger(0); val fallback = AtomicInteger(0)
        val mode = AtomicReference("meta-partial"); val failure = AtomicReference<Throwable?>(null)
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")); server.soTimeout = 1000
        val handlers = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue } catch (_: java.io.IOException) { break }
                handlers += thread(isDaemon = true) {
                    try { socket.use {
                        socket.soTimeout = 20000
                        val input = socket.getInputStream(); val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) { val b = input.read(); check(b >= 0 && header.length < 32768); header.append(b.toChar()) }
                        val length = header.lines().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt(); check(length in 1..300000)
                        val request = JsonParser.parseString(input.readNBytes(length).toString(Charsets.UTF_8)).asJsonObject
                        check(request["model"].asString == model)
                        val number = calls.incrementAndGet(); val currentMode = mode.get()
                        val isFallback = header.toString().startsWith("POST /fallback/"); if (isFallback) fallback.incrementAndGet()
                        val prompt = request["messages"].asJsonArray.last().asJsonObject["content"].asString
                        output.resolve("http-$number.txt").writeText("mode=$currentMode\nfallback=$isFallback\n")
                        val hang = currentMode == "hang" || currentMode == "meta-partial" && prompt.contains("QUEUE_ENTRY_2_$run")
                        if (hang) { check(input.read() == -1) { "request socket not closed" }; closed.incrementAndGet(); return@use }
                        val fail = currentMode == "retry-failure" && prompt.contains("QUEUE_ENTRY_4_$run")
                        val content = Gson().toJson(mapOf("region" to "生成区域_$run", "persona_prompt" to "新生成_$run", "worldPrompt" to "新世界书_$run"))
                        val response = if (fail) "{\"error\":{\"message\":\"fixture failure\"}}".toByteArray() else Gson().toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to content), "finish_reason" to "stop")), "usage" to mapOf("prompt_tokens" to 10, "completion_tokens" to 5))).toByteArray()
                        socket.getOutputStream().apply { write("HTTP/1.1 ${if (fail) "400 Bad Request" else "200 OK"}\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush() }
                    } } catch (t: Throwable) { failure.compareAndSet(null, t); output.resolve("server-failure.txt").writeText(t.stackTraceToString()) }
                }
            }
        }
        try {
            val base = "http://127.0.0.1:${server.localPort}/v1"
            configure("$base\nhttp://127.0.0.1:${server.localPort}/fallback/v1", 100000)
            val partial = metaTask(j, entries.take(2), "取消_${run.take(8)}")
            rule.waitUntil(20000) { calls.get() == 2 && task(partial).progressDone == 1 }
            val firstSaved = io { db.encyclopediaEntryDao().getById(entries[0])!! }
            val secondOriginal = io { db.encyclopediaEntryDao().getById(entries[1])!! }
            check(firstSaved.metaJson.contains("生成区域_$run"))
            val next = metaTask(j, listOf(entries[1]), "下一项_${run.take(8)}")
            openTasks(); rule.onNodeWithText(task(partial).title).performScrollTo().performClick(); waitText("取消生成")
            screenshot("01-running-detail")
            rule.onNodeWithText("取消生成").performClick(); waitText("取消这次生成？")
            mode.set("success")
            rule.onNodeWithText("取消生成").performClick()
            waitStatus(partial, "CANCELLED"); waitStatus(next, "COMPLETED")
            rule.waitUntil(10000) { closed.get() == 1 }
            check(calls.get() == 3 && fallback.get() == 0); check(task(partial).progressDone == 1)
            check(task(partial).resultJson.isEmpty()); check(io { db.encyclopediaEntryDao().getById(entries[0])!! } == firstSaved)
            check(io { db.encyclopediaEntryDao().getById(entries[1])!! }.content == secondOriginal.content)
            check(!io { dep.processor().cancelTask(partial) })
            screenshot("02-cancelled-next-complete")
            back(); openTasks(); rule.onNodeWithText(task(partial).title).performScrollTo().performClick(); waitText("已取消")
            check(task(partial).progressDone == 1); screenshot("03-cancelled-reopened"); back()

            // The same owner must stop each producer, including both encyclopedia output modes.
            fun stopProducer(enqueue: suspend () -> Long) {
                mode.set("hang"); val before = calls.get(); val closedBefore = closed.get()
                val id = recordTask(j, io { enqueue() }); rule.waitUntil(10000) { calls.get() == before + 1 }
                check(io { dep.processor().cancelTask(id) }); waitStatus(id, "CANCELLED")
                rule.waitUntil(10000) { closed.get() == closedBefore + 1 }
                check(calls.get() == before + 1); check(task(id).resultJson.isEmpty())
            }
            stopProducer { dep.processor().enqueueCharacterPersonaAi(com.mojing.app.domain.generation.CharacterPersonaAiPayload(cid, originalCharacter.name, originalCharacter.personaPrompt, "", "", model)) }
            stopProducer { dep.processor().enqueueWorldTemplatePromptAi(com.mojing.app.domain.generation.WorldTemplatePromptAiPayload(template, originalTemplate.label, "", originalTemplate.summary, originalTemplate.worldPrompt, "自由剧情", "")) }
            stopProducer { dep.processor().enqueueEncyclopediaBatch(wid, title, "原背景_$run", "location", 2, 80, 100, "") }
            stopProducer { dep.processor().enqueueEncyclopediaBatch(wid, title, "原背景_$run", "event", 2, 80, 100, "", "timeline") }
            check(io { db.characterDao().getById(cid)!! } == originalCharacter); check(io { db.worldTemplateDao().getById(template)!! } == originalTemplate)

            mode.set("retry-failure"); configure(base, 100000)
            val retry = metaTask(j, entries.drop(2), "续跑_${run.take(8)}")
            waitStatus(retry, "FAILED"); check(task(retry).progressDone == 1)
            val thirdSaved = io { db.encyclopediaEntryDao().getById(entries[2])!! }; val beforeRetry = calls.get()
            back(); openTasks(); rule.onNodeWithText(task(retry).title).performScrollTo().performClick(); waitText("继续尝试")
            mode.set("success"); rule.onAllNodesWithText("继续尝试").onLast().performClick(); waitStatus(retry, "COMPLETED")
            check(task(retry).progressDone == 2 && calls.get() == beforeRetry + 1)
            check(io { db.encyclopediaEntryDao().getById(entries[2])!! } == thirdSaved)
            screenshot("04-retry-completed"); check(closed.get() == 5 && fallback.get() == 0)
            handlers.forEach { it.join(3000) }; failure.get()?.let { throw it }
            j.addProperty("phase", "verified"); persist(j)
            output.resolve("verified.txt").writeText("uiCancelSocketClosed=true\npartialProgressPreserved=true\nnextTaskCompleted=true\nallFiveProducersCancelled=true\nnoCancelRetryFallback=true\nsourceAndSnapshotsPreserved=true\nfailedRetryOnlyRemaining=true\nreopenCancelledState=true\nhttpCount=${calls.get()}\nsocketCancelCount=${closed.get()}\n")
        } catch (t: Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString()); runCatching { output.resolve("failure-tree.txt").writeText(rule.onAllNodes(isRoot(), useUnmergedTree = true).onFirst().printToString(100)) }; runCatching { screenshot("failure") }; throw t }
        finally { server.close(); worker.join(3000); handlers.forEach { it.join(3000) } }
    }

    @Test fun rollbackOnlyOwnedQueueFixtures() {
        guard(); val j = journal(); val wid = j["world"]?.asLong
        j.getAsJsonArray("tasks").forEach { row -> val id = row.asLong; io { dep.processor().cancelTask(id) }; rule.waitUntil(10000) { task(id).status !in listOf("RUNNING", "QUEUED", "PAUSED") } }
        io { db.withTransaction {
            j.getAsJsonArray("tasks").forEach { row -> val id = row.asLong; val t = db.generationTaskDao().getById(id); check(t == null || t.targetEncyclopediaId == wid || t.targetCharacterId == j["character"]?.asLong || t.targetWorldTemplateId == j["template"]?.asLong); db.openHelper.writableDatabase.execSQL("DELETE FROM generation_tasks WHERE id = ?", arrayOf(id)) }
            if (wid != null) { val world = db.encyclopediaDao().getById(wid); check(world == null || world.name == title && world.description == "QUEUE_CANCEL_WORLD_$run"); check(db.encyclopediaEntryDao().getByEncyclopedia(wid).all { it.title.startsWith("QUEUE_ENTRY_") && it.title.endsWith(run) }); db.encyclopediaDao().delete(wid) }
            j["character"]?.asLong?.let { id -> val c = db.characterDao().getById(id); check(c == null || c.name == "QUEUE_CHARACTER_$run"); db.characterDao().delete(id) }
            j["template"]?.asLong?.let { id -> val t = db.worldTemplateDao().getById(id); check(t == null || t.templateId == "queue-$run"); db.worldTemplateDao().delete(id) }
            db.openHelper.writableDatabase.execSQL("DELETE FROM llm_cost_records WHERE modelName = ? AND sessionId IS NULL AND characterId IS NULL", arrayOf(model))
        } }
        val editor = preferences.edit(); j.getAsJsonObject("originalConfig").entrySet().forEach { (key, value) -> if (value.isJsonNull) editor.remove(key) else editor.putString(key, value.asString) }; check(editor.commit())
        j.getAsJsonObject("originalConfig").entrySet().forEach { (key, value) -> check(preferences.contains(key) == !value.isJsonNull); if (!value.isJsonNull) check(preferences.getString(key, null) == value.asString) }
        j.getAsJsonObject("drafts").entrySet().forEach { (name, value) -> assertUnchanged(context.getSharedPreferences(name, 0), value.asJsonObject) }
        check(digest() == j["digest"].asString); check(branches() == j.getAsJsonObject("branches")); j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("uuidFixturesOnlyRemoved=true\nconfigPresenceValuesRestored=true\nallFourDraftsExact=true\notherWorldEntryCharacterProfileTemplateTimelineDigestExact=true\n")
    }
}

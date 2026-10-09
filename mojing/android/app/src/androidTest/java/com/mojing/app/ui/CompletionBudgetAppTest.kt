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

class CompletionBudgetAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("completionBudgetRun").orEmpty().also { UUID.fromString(it) }
    private val title get() = "草稿航图${run.take(8)}"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val journalFile get() = File(context.filesDir, "completion-budget-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "completion-budget-$run").apply { mkdirs() }
    private fun guard() {
        check(args.getString("completionBudgetCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "completion-budget-$run.pending")
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

    private val entryPrefs get() = context.getSharedPreferences("entry_edit_drafts_v1", 0)
    private val entryTitle get() = "旧港${run.take(8)}"
    private fun row(j: JsonObject) = runBlocking(Dispatchers.IO) { checkNotNull(db.encyclopediaEntryDao().getById(j.get("entry").asLong)) }
    private fun versions(j: JsonObject) = runBlocking(Dispatchers.IO) { db.entryVersionDao().getByEntry(j.get("entry").asLong) }
    private fun back() { inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); rule.waitForIdle() }
    private fun openWorldAndEntry(name: String) {
        rule.waitUntil(10000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasText("创作") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        waitText("世界"); rule.onNodeWithText("世界").performClick()
        rule.waitUntil(10000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(title)
        hideKeyboard()
        rule.waitUntil(10000) { rule.onAllNodesWithText(title).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(title).filter(!hasSetTextAction()).onLast().performClick()
        waitText(name); rule.onNodeWithText(name).performScrollTo().performClick(); waitText("编辑条目")
    }
    private fun field(value: String) = rule.onNode(hasSetTextAction() and hasText(value), useUnmergedTree = true)
    private fun replace(old: String, next: String) { field(old).performScrollTo().performTextReplacement(next); hideKeyboard(); rule.waitForIdle() }
    private fun advanced() {
        rule.onNodeWithText("高级设置").performScrollTo().performClick(); rule.waitForIdle()
    }
    private fun save(j: JsonObject, expectedTitle: String, count: Int) {
        hideKeyboard()
        rule.onAllNodesWithText("保存修改").onFirst().assertIsEnabled().performClick()
        rule.waitUntil(10000) { row(j).title == expectedTitle && versions(j).size == count && rule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun history() { rule.onNodeWithText("版本").performClick(); waitText("载入文本资料") }
    private fun selectOld() { rule.onAllNodesWithText("载入文本资料").onLast().performScrollTo().performClick(); waitText("载入 v1 文本资料？") }
    private fun assertText(titleValue: String, summary: String, content: String, tags: String, meta: String) {
        listOf(titleValue, summary, content, tags, meta).forEach { field(it).assertExists() }
    }
    private fun fullScreenBody(value: String) {
        rule.onNodeWithText("全屏编辑").performScrollTo().performClick(); waitText("完成")
        rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()), useUnmergedTree = true).performTextReplacement(value)
        rule.onNode(hasSetTextAction() and hasText(value) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("完成").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("完成").fetchSemanticsNodes().isEmpty() }
    }
    private fun longText(mark: String) = "$mark-$run\n" + (1..250).joinToString("\n") {
        "第${it}段：潮声穿过旧港的石阶，守灯人记下归航的船名与星位。每一段设定都属于这份百科草稿，未保存时仍可离开后继续，不能只保留可见片段。"
    } + "\nEND_$mark-$run"

    private val dep get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java)
    private val preferences get() = rule.activity.secureStorage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(rule.activity.secureStorage) as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform")
    private val model get() = "completion-budget-$run"
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun editorModel(): com.mojing.app.ui.encyclopedia.EntryEditViewModel {
        val root = rule.activity.viewModelStore
        for (key in root.keys()) {
            val nav = root[key] ?: continue
            if (nav.javaClass.simpleName != "NavControllerViewModel") continue
            val field = nav.javaClass.getDeclaredField("viewModelStores").apply { isAccessible = true }
            val stores = field.get(nav) as Map<*, *>
            for (store in stores.values.filterIsInstance<androidx.lifecycle.ViewModelStore>())
                for (vmKey in store.keys()) (store[vmKey] as? com.mojing.app.ui.encyclopedia.EntryEditViewModel)?.let { return it }
        }
        error("editor-model-not-found")
    }
    private fun configure(base: String, capacity: Int) {
        val p = ModelPlatform("completion-$run", "本机容量夹具", base, "local-completion-only", listOf(model), model, mapOf(model to capacity))
        check(preferences.edit().putString("public_api_key", p.apiKey).putString("public_base_url", base).putString("public_model", model)
            .putString("model_platforms_v1", ModelPlatformCodec.encode(listOf(p))).putString("active_model_platform", p.id).commit())
    }
    private fun branches() = io { JsonObject().apply {
        com.mojing.app.data.prefs.UiPreferencesRepository(context).lastChatBranches.first().forEach { (id, branch) -> addProperty(id.toString(), branch) }
    } }
    private fun digest(): String = io {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        listOf("world_encyclopedias", "encyclopedia_entries", "characters", "character_profiles").forEach { table ->
            db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { c ->
                while (c.moveToNext()) for (index in 0 until c.columnCount) { hash.update((c.getString(index) ?: "<null>").toByteArray()); hash.update(0.toByte()) }
            }
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
    @Test fun fullEditorAndQueueCapacityRecovery() {
        guard(); check(!journalFile.exists()); check(io { dep.processor().countActiveTasks() } == 0); check(!dep.processor().isQueuePaused())
        val j = JsonObject().apply { addProperty("run", run); addProperty("digest", digest()); add("branches", branches())
            add("originalConfig", JsonObject().apply { configKeys.forEach { key -> preferences.getString(key, null)?.let { addProperty(key, it) } ?: add(key, com.google.gson.JsonNull.INSTANCE) } })
            add("drafts", JsonObject().apply { listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1").forEach { add(it, snapshot(context.getSharedPreferences(it, 0))) } }) }
        persist(j)
        val wid = io { db.encyclopediaDao().upsert(EncyclopediaEntity(name = title, description = "COMPLETION_WORLD_$run")) }; j.addProperty("world", wid); persist(j)
        val eid = io { db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId = wid, title = entryTitle, entryType = "location", content = "原文_$run")) }; j.addProperty("entry", eid); persist(j)
        val initial = row(j)
        val calls = AtomicInteger(0); val failure = AtomicReference<Throwable?>(null); val cancelled = CountDownLatch(1); val release = CountDownLatch(1)
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")); server.soTimeout = 60000
        val worker = thread(isDaemon = true) {
            try { repeat(4) { index -> server.accept().use { socket ->
                socket.soTimeout = 20000
                val input = socket.getInputStream(); val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) { val b = input.read(); check(b >= 0 && header.length < 32768); header.append(b.toChar()) }
                val length = header.lines().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt(); check(length in 1..300000)
                val request = JsonParser.parseString(input.readNBytes(length).toString(Charsets.UTF_8)).asJsonObject
                check(request["model"].asString == model); check(request["max_tokens"].asInt == if (index < 2) 3000 else 8000)
                val prompt = request["messages"].asJsonArray.last().asJsonObject["content"].asString
                if (index < 2) check(prompt.contains("END_FULL_$run")) { "full-body-marker-missing" }
                if (index == 2) check(prompt.contains("SMALL_$run"))
                if (index == 3) check(prompt.contains("END_META_$run"))
                calls.incrementAndGet()
                if (index == 1) { cancelled.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
                val content = Gson().toJson(mapOf("region" to "生成区域_$run"))
                val response = Gson().toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to content), "finish_reason" to "stop")), "usage" to mapOf("prompt_tokens" to 10, "completion_tokens" to 5))).toByteArray()
                try { socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush() } }
                catch (e: java.io.IOException) { if (index != 1) throw e }
            } } } catch (t: Throwable) { if (!server.isClosed) { failure.set(t); output.resolve("server-failure.txt").writeText(t.stackTraceToString()) } }
        }
        try {
            val base = "http://127.0.0.1:${server.localPort}/v1"
            configure(base, 4000)
            openWorldAndEntry(entryTitle)
            val body = "完整正文_$run\n" + "潮声与原资料。".repeat(1800) + "END_FULL_$run"
            fullScreenBody(body)
            rule.onNodeWithText("补全空白内容").performScrollTo().performClick()
            waitText("补全空白内容")
            rule.waitUntil(10000) { rule.onAllNodesWithText("未发送请求", substring = true).fetchSemanticsNodes().isNotEmpty() }
            check(calls.get() == 0); check(row(j) == initial); field(body).assertExists(); screenshot("capacity-failure-original-retained")
            rule.waitUntil(10000) { editorModel().state.value.snackbar == null }
            configure(base, 100000)
            check(rule.activity.secureStorage.modelPlatforms().single().modelContextWindows[model] == 100000)
            rule.onNodeWithText("补全空白内容").performScrollTo().performClick()
            val trace = mutableSetOf<String>()
            rule.waitUntil(10000) {
                val state = editorModel().state.value
                trace.add("busy=${state.isAiCompleting};snackbar=${state.snackbar};recoverable=${state.recoverableDraft != null};loaded=${state.isLoaded}")
                output.resolve("retry-state-trace.txt").writeText(trace.joinToString("\n"))
                failure.get()?.let { throw it }; calls.get() == 1 && rule.onAllNodesWithText("补全空白内容").fetchSemanticsNodes().isNotEmpty() }
            check(row(j) == initial); field(body).assertExists()
            rule.waitUntil(10000) { editorModel().state.value.snackbar == null }
            rule.onNodeWithText("补全空白内容").performScrollTo().performClick()
            check(cancelled.await(15, TimeUnit.SECONDS)); waitText("停止补全"); screenshot("cancel-action-visible")
            rule.onNodeWithText("停止补全").performClick(); waitText("补全空白内容"); release.countDown()
            fullScreenBody(body + "\n取消后的编辑_$run")
            rule.onAllNodesWithText("保存修改").onFirst().performClick()
            rule.waitUntil(10000) { row(j).content == body + "\n取消后的编辑_$run" && rule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
            val saved = row(j)
            rule.onNodeWithContentDescription("返回").performClick(); waitText("关系图")
            rule.onNodeWithText(entryTitle).performScrollTo().performClick(); waitText("已保存")
            field(saved.content).assertExists(); check(row(j) == saved); screenshot("cancel-save-reopen-full-body")
            val first = io { db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId = wid, title = "SMALL_$run", entryType = "location")) }
            val oldMeta = "{\"unknown\":\"" + "完整meta原文".repeat(1800) + "END_META_$run\"}"
            val second = io { db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId = wid, title = "LARGE_$run", entryType = "location", metaJson = oldMeta)) }
            j.addProperty("first", first); j.addProperty("second", second); persist(j)
            configure(base + "\nhttp://127.0.0.1:9/v1", 10000)
            val task = io { dep.processor().enqueueEncyclopediaMetaFill(wid, title, listOf(first, second)) }; j.addProperty("task", task); persist(j)
            rule.waitUntil(20000) { io { db.generationTaskDao().getById(task)?.status == "FAILED" } }
            val failed = io { checkNotNull(db.generationTaskDao().getById(task)) }
            check(failed.progressDone == 1); check(failed.errorMessage.contains("未发送请求")); check(calls.get() == 3)
            check(io { db.encyclopediaEntryDao().getById(second)!!.metaJson } == oldMeta)
            val firstSaved = io { db.encyclopediaEntryDao().getById(first)!! }
            check(JsonParser.parseString(firstSaved.metaJson).asJsonObject["region"].asString == "生成区域_$run")
            configure(base, 100000)
            check(io { dep.processor().requeueFailedTask(failed) })
            rule.waitUntil(20000) { io { db.generationTaskDao().getById(task)?.status == "COMPLETED" } }
            check(calls.get() == 4); check(io { db.generationTaskDao().getById(task)!!.progressDone } == 2)
            check(io { db.encyclopediaEntryDao().getById(first)!! } == firstSaved)
            val meta = JsonParser.parseString(io { db.encyclopediaEntryDao().getById(second)!!.metaJson }).asJsonObject
            check(meta["unknown"].asString.endsWith("END_META_$run")); check(meta["region"].asString == "生成区域_$run")
            worker.join(3000); failure.get()?.let { throw it }
            j.addProperty("phase", "verified"); persist(j)
            output.resolve("verified.txt").writeText("knownEditorRejectHttpZero=true\nfullOriginalSentOnRetry=true\ncancelSaveReopen=true\nqueueProgressOnePreserved=true\ncapacityFailureStopsFallback=true\nqueueRetryStartsSecond=true\nunknownMetaPreserved=true\nhttpCount=4\nloopbackOnly=true\n")
        } catch (t: Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString()); runCatching { output.resolve("failure-tree.txt").writeText(rule.onAllNodes(isRoot(), useUnmergedTree = true).onFirst().printToString(100)) }; runCatching { screenshot("failure") }; throw t }
        finally { release.countDown(); server.close(); worker.join(3000) }
    }

    @Test fun rollbackOnlyOwnedCompletionFixtures() {
        guard(); val j = journal()
        j["task"]?.asLong?.let { task -> io { dep.processor().cancelTask(task) }; rule.waitUntil(10000) { io { db.generationTaskDao().getById(task)?.status !in listOf("RUNNING", "QUEUED") } } }
        val wid = j["world"]?.asLong
        io { db.withTransaction {
            j["task"]?.asLong?.let { id -> val t = db.generationTaskDao().getById(id); check(t == null || t.targetEncyclopediaId == wid); db.openHelper.writableDatabase.execSQL("DELETE FROM generation_tasks WHERE id = ?", arrayOf(id)) }
            if (wid != null) { val world = db.encyclopediaDao().getById(wid); check(world == null || world.name == title && world.description == "COMPLETION_WORLD_$run"); check(db.encyclopediaEntryDao().getByEncyclopedia(wid).all { it.title == entryTitle || it.title == "SMALL_$run" || it.title == "LARGE_$run" }); db.encyclopediaDao().delete(wid) }
            db.openHelper.writableDatabase.execSQL("DELETE FROM llm_cost_records WHERE modelName = ? AND sessionId IS NULL AND characterId IS NULL", arrayOf(model))
        } }
        val editor = preferences.edit(); j.getAsJsonObject("originalConfig").entrySet().forEach { (key, value) -> if (value.isJsonNull) editor.remove(key) else editor.putString(key, value.asString) }; check(editor.commit())
        j.getAsJsonObject("originalConfig").entrySet().forEach { (key, value) -> check(preferences.contains(key) == !value.isJsonNull); if (!value.isJsonNull) check(preferences.getString(key, null) == value.asString) }
        if (wid != null) j["entry"]?.asLong?.let { eid -> val key = "encyclopedia_${wid}_entry_$eid"; check(!j.getAsJsonObject("drafts").getAsJsonObject("entry_edit_drafts_v1").has(key)); check(entryPrefs.edit().remove(key).commit()) }
        j.getAsJsonObject("drafts").entrySet().forEach { (name, value) -> assertUnchanged(context.getSharedPreferences(name, 0), value.asJsonObject) }
        check(digest() == j["digest"].asString); check(branches() == j.getAsJsonObject("branches")); j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("uuidFixturesOnlyRemoved=true\nconfigPresenceValuesRestored=true\nallFourDraftsExact=true\notherWorldEntryCharacterProfileDigestExact=true\n")
    }
}

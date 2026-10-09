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

class GenerationFeedbackAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("generationFeedbackRun").orEmpty().also { UUID.fromString(it) }
    private val title get() = "草稿航图${run.take(8)}"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val worldPrefs get() = context.getSharedPreferences("world_edit_drafts_v1", 0)
    private val characterPrefs get() = context.getSharedPreferences("character_edit_drafts_v1", 0)
    private val chatPrefs get() = context.getSharedPreferences("chat_drafts_v1", 0)
    private val journalFile get() = File(context.filesDir, "generation-feedback-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "generation-feedback-$run").apply { mkdirs() }
    private fun guard() {
        check(args.getString("generationFeedbackCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "generation-feedback-$run.pending")
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
    private val model get() = "generation-feedback-$run"
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun configure(base: String, capacity: Int) {
        val p = ModelPlatform("generation-feedback-$run", "本机取消夹具", base, "local-generation-feedback-only", listOf(model), model, mapOf(model to capacity))
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


    @Test fun fullFeedbackAndSnapshotApplication() {
        guard(); check(!journalFile.exists()); check(io { dep.processor().countActiveTasks() } == 0); check(!dep.processor().isQueuePaused())
        val j = JsonObject().apply {
            addProperty("run", run); addProperty("digest", digest()); add("branches", branches()); add("tasks", com.google.gson.JsonArray())
            add("originalConfig", JsonObject().apply { configKeys.forEach { key -> preferences.getString(key, null)?.let { addProperty(key, it) } ?: add(key, com.google.gson.JsonNull.INSTANCE) } })
            add("drafts", JsonObject().apply { listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1").forEach { add(it, snapshot(context.getSharedPreferences(it, 0))) } })
        }; persist(j)
        val wid = io { db.encyclopediaDao().upsert(EncyclopediaEntity(name = title, description = "QUEUE_CANCEL_WORLD_$run")) }; j.addProperty("world", wid); persist(j)
        val eid = io { db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId = wid, title = "QUEUE_ENTRY_$run", content = "完整正文_$run")) }
        val cid = io { db.characterDao().upsert(com.mojing.app.data.local.entity.CharacterEntity(name = "QUEUE_CHARACTER_$run", personaPrompt = "原人设_$run")) }; j.addProperty("character", cid); persist(j)
        val template = io { db.worldTemplateDao().upsert(com.mojing.app.data.local.entity.WorldTemplateEntity(templateId = "queue-$run", label = "QUEUE_TEMPLATE_$run", summary = "原摘要_$run", worldPrompt = "原世界书_$run")) }; j.addProperty("template", template); persist(j)
        fun character() = io { db.characterDao().getById(cid)!! }
        fun world() = io { db.worldTemplateDao().getById(template)!! }
        fun persona() = recordTask(j, io { val c = character(); dep.processor().enqueueCharacterPersonaAi(com.mojing.app.domain.generation.CharacterPersonaAiPayload(cid, "${c.name}_${j.getAsJsonArray("tasks").size()}", c.personaPrompt, "", "", model)) })
        fun worldTask() = recordTask(j, io { val t = world(); dep.processor().enqueueWorldTemplatePromptAi(com.mojing.app.domain.generation.WorldTemplatePromptAiPayload(template, "${t.label}_${j.getAsJsonArray("tasks").size()}", "", t.summary, t.worldPrompt, "自由剧情", "")) })
        val calls = AtomicInteger(0); val failure = AtomicReference<Throwable?>(null)
        val releases = listOf(CountDownLatch(1), CountDownLatch(1))
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")); server.soTimeout = 60000
        val worker = thread(isDaemon = true) {
            try { repeat(7) { index -> server.accept().use { socket ->
                socket.soTimeout = 20000; val input = socket.getInputStream(); val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) { val b = input.read(); check(b >= 0 && header.length < 32768); header.append(b.toChar()) }
                val length = header.lines().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                val request = JsonParser.parseString(input.readNBytes(length).toString(Charsets.UTF_8)).asJsonObject; check(request["model"].asString == model)
                calls.incrementAndGet()
                if (index in 4..5) check(releases[index - 4].await(20, TimeUnit.SECONDS))
                val fields: Map<String, String> = when (index) {
                    0 -> emptyMap()
                    1 -> mapOf("persona_prompt" to "成功人设_$run")
                    2 -> mapOf("category" to "无可用世界字段")
                    3 -> mapOf("summary" to "成功摘要_$run", "worldPrompt" to "成功世界书_$run")
                    4 -> mapOf("persona_prompt" to "待应用人设_$run")
                    5 -> mapOf("worldPrompt" to "待应用世界书_$run")
                    else -> mapOf("unsupported_fixture_field" to "不写入现有资料")
                }
                val response = Gson().toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to Gson().toJson(fields)), "finish_reason" to "stop")), "usage" to mapOf("prompt_tokens" to 10, "completion_tokens" to 5))).toByteArray()
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush() }
            } } } catch (t: Throwable) { if (!server.isClosed) { failure.set(t); output.resolve("server-failure.txt").writeText(t.stackTraceToString()) } }
        }
        try {
            configure("http://127.0.0.1:${server.localPort}/v1", 100000)
            val original = character(); val originalWorld = world()
            val p = persona(); rule.waitUntil(10000) { task(p).status !in listOf("QUEUED", "RUNNING") }
            output.resolve("first-result.txt").writeText("status=${task(p).status}\ndone=${task(p).progressDone}\ntotal=${task(p).progressTotal}\n")
            check(task(p).status == "FAILED" && task(p).progressDone == 0 && task(p).resultJson.isEmpty()) { "empty-persona-incorrectly-terminal:${task(p).status}" }
            check(character() == original); openTasks(); rule.onNodeWithText(task(p).title).performScrollTo().performClick(); waitText("继续尝试")
            rule.onNodeWithText("生成反馈").assertExists(); screenshot("00-failed-feedback")
            rule.onAllNodesWithText("继续尝试").onLast().performClick(); waitStatus(p, "COMPLETED")
            check(task(p).progressDone == 1 && task(p).progressTotal == 1 && task(p).resultAppliedAt != null); check(character().personaPrompt == "成功人设_$run")
            waitText("已完成 1 / 1"); screenshot("01-persona-retry-complete"); back()
            val w = worldTask(); waitStatus(w, "FAILED"); check(task(w).progressDone == 0 && task(w).resultJson.isEmpty()); check(world() == originalWorld)
            rule.onNodeWithText(task(w).title).performScrollTo().performClick(); waitText("继续尝试"); rule.onAllNodesWithText("继续尝试").onLast().performClick(); waitStatus(w, "COMPLETED")
            check(task(w).progressDone == 1 && task(w).resultAppliedAt != null); check(world().worldPrompt == "成功世界书_$run")
            waitText("已完成 1 / 1"); screenshot("02-template-retry-complete"); back()
            val staleP = persona(); rule.waitUntil(10000) { calls.get() == 5 }
            val manuallyChanged = character().copy(personaPrompt = "生成期间用户改稿_$run", updatedAt = System.currentTimeMillis())
            io { db.characterDao().upsert(manuallyChanged) }; releases[0].countDown(); waitStatus(staleP, "COMPLETED")
            check(task(staleP).progressDone == 1 && task(staleP).resultAppliedAt == null && task(staleP).resultJson.isNotEmpty()); check(character() == manuallyChanged)
            rule.onNodeWithText(task(staleP).title).performScrollTo().performClick(); waitText("已完成 1 / 1")
            rule.onNodeWithText("生成时反馈").assertExists()
            rule.onAllNodesWithText("生成已完成 · 待应用").onLast().performScrollTo().assertIsDisplayed()
            screenshot("03-persona-pending-apply")
            rule.onNodeWithText("对比并应用").performScrollTo().performClick(); waitText("应用生成结果")
            rule.onNodeWithText(manuallyChanged.personaPrompt).assertExists()
            val secondEdit = manuallyChanged.copy(personaPrompt = "预览之后用户再改稿_$run", updatedAt = System.currentTimeMillis())
            io { db.characterDao().upsert(secondEdit) }
            rule.onNodeWithText("确认应用").performClick(); waitText("内容又有变化，请刷新后重新确认")
            check(character() == secondEdit && task(staleP).resultAppliedAt == null)
            screenshot("04-persona-stale-preview")
            rule.onNodeWithText("刷新当前内容").performClick(); waitText("确认应用")
            rule.onNodeWithText(secondEdit.personaPrompt).assertExists(); rule.onNodeWithText("确认应用").performClick()
            rule.waitUntil(10000) { task(staleP).resultAppliedAt != null }
            check(character().personaPrompt == "待应用人设_$run")
            rule.onAllNodesWithText("生成已完成 · 已应用").onLast().performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("对比并应用").assertDoesNotExist(); screenshot("05-persona-applied")
            back(); back(); openTasks(); rule.onNodeWithText(task(staleP).title).performScrollTo().performClick()
            rule.onAllNodesWithText("生成已完成 · 已应用").onLast().performScrollTo().assertIsDisplayed()
            check(character().personaPrompt == "待应用人设_$run"); back()
            val staleW = worldTask(); rule.waitUntil(10000) { calls.get() == 6 }
            val worldChanged = world().copy(worldPrompt = "用户新世界书_$run", updatedAt = System.currentTimeMillis())
            io { db.worldTemplateDao().upsert(worldChanged) }; releases[1].countDown(); waitStatus(staleW, "COMPLETED")
            check(task(staleW).progressDone == 1 && task(staleW).resultAppliedAt == null && task(staleW).resultJson.isNotEmpty()); check(world() == worldChanged)
            back(); openTasks(); rule.onNodeWithText(task(staleW).title).performScrollTo().performClick(); waitText("已完成 1 / 1")
            rule.onAllNodesWithText("生成已完成 · 待应用").onLast().performScrollTo().assertIsDisplayed(); screenshot("06-template-pending-reopened")
            rule.onNodeWithText("对比并应用").performScrollTo().performClick(); waitText("应用生成结果")
            rule.onNodeWithText(worldChanged.summary + "\n\n" + worldChanged.worldPrompt).assertExists()
            rule.onNodeWithText("确认应用").performClick(); rule.waitUntil(10000) { task(staleW).resultAppliedAt != null }
            check(world().worldPrompt == "待应用世界书_$run" && world().summary == worldChanged.summary)
            rule.onAllNodesWithText("生成已完成 · 已应用").onLast().performScrollTo().assertIsDisplayed(); screenshot("07-template-applied")
            back(); back(); openTasks(); rule.onNodeWithText(task(staleW).title).performScrollTo().performClick()
            rule.onAllNodesWithText("生成已完成 · 已应用").onLast().performScrollTo().assertIsDisplayed()
            check(world().worldPrompt == "待应用世界书_$run"); rule.onNodeWithText("对比并应用").assertDoesNotExist()
            back()
            val originalEntry = io { db.encyclopediaEntryDao().getById(eid)!! }
            val hintTask = metaTask(j, listOf(eid), title); waitStatus(hintTask, "COMPLETED")
            check(task(hintTask).errorMessage.startsWith("提示：") && task(hintTask).resultJson.isEmpty())
            check(io { db.encyclopediaEntryDao().getById(eid) } == originalEntry)
            rule.onNodeWithText(task(hintTask).title).performScrollTo().performClick()
            rule.onNodeWithText("生成时反馈").assertExists(); rule.onNodeWithText("生成反馈").assertDoesNotExist()
            rule.onNodeWithText("生成已完成 · 待应用").assertDoesNotExist(); screenshot("08-completed-information")
            worker.join(3000); failure.get()?.let { throw it }; check(calls.get() == 7)
            j.addProperty("phase", "verified"); persist(j)
            output.resolve("verified.txt").writeText("failedFeedbackRetained=true\ncompletedInformationPreservesTarget=true\ncompletedFeedbackHistorical=true\npendingSnapshotLabel=true\nuserRetrySavedProgressOne=true\nunchangedTargetAutoApplied=true\nstalePreviewRejected=true\nrefreshConfirmApplied=true\ncharacterTemplateApplied=true\nappliedReopened=true\nhttpCount=7\n")
        } catch (t: Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString()); runCatching { output.resolve("failure-tree.txt").writeText(rule.onAllNodes(isRoot(), useUnmergedTree = true).onFirst().printToString(100)) }; runCatching { screenshot("failure") }; throw t }
        finally { releases.forEach { it.countDown() }; server.close(); worker.join(3000) }
    }

    @Test fun rollbackOnlyOwnedFeedbackFixtures() {
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

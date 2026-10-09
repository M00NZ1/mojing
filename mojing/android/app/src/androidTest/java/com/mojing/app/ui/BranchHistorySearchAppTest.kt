package com.mojing.app.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
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
class BranchHistorySearchAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("branchHistorySearchRun").orEmpty().also { UUID.fromString(it) }
    private val scenario get() = args.getString("scenario").orEmpty()
    private val title get() = "续章入口${run.take(8)}"
    private val parentPhrase = "父线未来航期秘密"
    private val childPhrase = "子线修改后的航期"
    private fun searchPrefsDigest(): String {
        val values = context.getSharedPreferences("message_search",0).all.toSortedMap().map { (k,v) ->
            k + ":" + when(v) { is Set<*> -> v.map { it.toString() }.sorted().joinToString("|"); else -> v.toString() }
        }.joinToString("\n")
        return java.security.MessageDigest.getInstance("SHA-256").digest(values.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "branch-history-search-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "branch-history-search-$run").apply { mkdirs() }
    private val preferences get() = rule.activity.secureStorage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(rule.activity.secureStorage) as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun guard(idle: Boolean = true) {
        assumeTrue(args.getString("branchHistorySearchCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        if (idle) check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
        check(scenario in listOf("large"))
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "branch-history-search-$run.pending")
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
            "encyclopedia_entries", "characters", "character_profiles", "world_templates", "timeline_events").forEach { table ->
            hash.update(table.toByteArray())
            db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { c ->
                while (c.moveToNext()) for (index in 0 until c.columnCount) { hash.update((c.getString(index) ?: "<null>").toByteArray()); hash.update(0.toByte()) }
            }
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun model(id: Long): ChatViewModel {
        val stores = RetainedChatSessions.stores
        val entries = stores.javaClass.getDeclaredField("entries").apply { isAccessible = true }.get(stores) as Map<*, *>
        val entry = checkNotNull(entries[id])
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

    @Test fun branchHistorySearchRequestAndPersistence() {
        guard(); check(!journalFile.exists())
        val base = args.getString("localBase").orEmpty(); check(base.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/v1")))
        val j = JsonObject().apply {
            addProperty("run", run); addProperty("scenario", scenario); addProperty("phase", "snapshot")
            add("originalConfig", JsonObject().apply { configKeys.forEach { k -> addProperty(k, if (preferences.contains(k)) preferences.getString(k, null) else null) } })
            add("drafts", JsonObject().apply { draftNames.forEach { n -> add(n, snapshot(context.getSharedPreferences(n, 0))) } })
            add("branches", branches()); addProperty("digest", digest()); addProperty("searchPrefsDigest",searchPrefsDigest())
        }; persist(j)
        try {
            val cid = io { db.characterDao().upsert(CharacterEntity(name = "子线角色-$run", personaPrompt = "SYNTHETIC_$run")) }
            j.addProperty("character", cid); persist(j)
            val sid = io { db.sessionDao().insert(SessionEntity(title = title, creationRequestId = run)) }
            j.addProperty("session", sid); persist(j)
            val count = 4000
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
                    val content = heading + "\n\n" + "雨落在青石街巷。她收起信纸，穿过灯影，仍记得城外旧桥与昨日的约定。".repeat(10) + "\nINDEX_${n}_$run" + if (n == count) "\n$parentPhrase" else ""
                    chars += content.length
                    estimatedTokens += com.mojing.app.domain.engine.TokenCounter.estimate(content)
                    val id = db.messageDao().insert(MessageEntity(sessionId = sid, speakerType = "narrator", content = content,
                        structuredContentJson = com.mojing.app.domain.story.NovelChapter.metadata("{}", n, heading)))
                    if (n == 20) oldId = id
                    lastId = id
                }
            } }
            metrics.addProperty("insertMs", android.os.SystemClock.elapsedRealtime()-insertStart)
            metrics.addProperty("characters", chars); metrics.addProperty("estimatedTokens", estimatedTokens)
            if (scenario == "large") check(estimatedTokens >= 1_000_000)
            val source = io { checkNotNull(db.messageDao().getById(oldId)) }
            val child = "edit-search-$run"
            val replacementId = io { db.sessionBranchDao().insertEditedBranch(
                SessionBranchEntity(sessionId=sid,branchId=child,label="航期修改子线",sourceMessageId=oldId,parentBranchId="main"),
                source.copy(id=0,branchId=child,regeneratedFromMessageId=oldId,content="第20章 雨城20\n\n$childPhrase：她改为明日出发。"),emptyList()) }
            check(context.getSharedPreferences("message_search",0).all.keys.none { it == "history_$sid" || it.startsWith("history_${sid}_") })
            val platform = ModelPlatform("branch-$run", "本机子线验证", base, "local-branch-only", listOf("branch-$run"), "branch-$run", mapOf("branch-$run" to 100000))
            check(preferences.edit().putString("public_api_key", platform.apiKey).putString("public_base_url", base).putString("public_model", platform.selectedModel)
                .putString("model_platforms_v1", ModelPlatformCodec.encode(listOf(platform))).putString("active_model_platform", platform.id).putString("speaker_turn_mode", "auto").commit())
            openStory(sid)
            rule.waitUntil(20000) { model(sid).state.value.messages.lastOrNull()?.id == lastId }
            val draft = "搜索时保留的草稿-$run"
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).performTextReplacement(draft)
            rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken,0); rule.activity.currentFocus?.clearFocus() }
            rule.waitUntil(10000) { rule.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }
            rule.onNodeWithContentDescription("会话菜单").performClick(); waitText("故事线")
            rule.onNodeWithText("故事线").performClick(); waitText("选择故事线")
            rule.onNodeWithText("航期修改子线").performClick()
            rule.waitUntil(20000) { model(sid).state.value.currentBranchId == child && rule.onAllNodesWithText("选择故事线").fetchSemanticsNodes().isEmpty() }
            screenshot("01-child")
            fun search(phrase: String) {
                rule.onNodeWithContentDescription("会话菜单").performClick(); waitText("搜索消息")
                rule.onNodeWithText("搜索消息").performClick(); waitText("全部故事线")
                rule.onAllNodes(hasSetTextAction(),useUnmergedTree=true).onFirst().performTextReplacement(phrase)
                rule.onAllNodes(hasSetTextAction(),useUnmergedTree=true).onFirst().performImeAction()
                rule.waitUntil(20000) { rule.onAllNodesWithText("没有找到匹配消息").fetchSemanticsNodes().isNotEmpty() }
                check(rule.onAllNodesWithText("共 0 条匹配消息").fetchSemanticsNodes().isNotEmpty())
            }
            search(parentPhrase)
            check(model(sid).state.value.currentBranchId == child)
            screenshot("02-future-excluded-current")
            rule.onNodeWithText("全部故事线").performClick(); waitText("共 1 条匹配消息")
            screenshot("03-parent-hit-all")
            rule.onAllNodesWithText(parentPhrase,substring=true).filter(!hasSetTextAction()).onFirst().performClick()
            waitText("打开对话")
            check(model(sid).state.value.currentBranchId == child)
            screenshot("04-parent-context-no-switch")
            rule.onNodeWithText("打开对话").performClick()
            rule.waitUntil(20000) { model(sid).state.value.currentBranchId == "main" && model(sid).state.value.messages.any { it.id == lastId } && rule.onAllNodesWithText("全部故事线").fetchSemanticsNodes().isEmpty() }
            rule.onAllNodesWithText(parentPhrase,substring=true).onFirst().assertIsDisplayed()
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft)
            screenshot("05-parent-chat")
            search(childPhrase)
            check(model(sid).state.value.currentBranchId == "main")
            screenshot("06-child-excluded-parent")
            rule.onNodeWithText("全部故事线").performClick(); waitText("共 1 条匹配消息")
            screenshot("07-child-hit-all")
            rule.onAllNodesWithText(childPhrase,substring=true).filter(!hasSetTextAction()).onFirst().performClick(); waitText("打开对话")
            check(model(sid).state.value.currentBranchId == "main")
            screenshot("08-child-context-no-switch")
            rule.onNodeWithText("打开对话").performClick()
            rule.waitUntil(20000) { model(sid).state.value.currentBranchId == child && model(sid).state.value.messages.any { it.id == replacementId } && rule.onAllNodesWithText("全部故事线").fetchSemanticsNodes().isEmpty() }
            rule.onAllNodesWithText(childPhrase,substring=true).onFirst().assertIsDisplayed()
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft)
            check(model(sid).state.value.messages.size <= 200 && !model(sid).state.value.hasNewerMessages)
            screenshot("09-child-chat")
            check(io { db.messageDao().getById(oldId) } == source)
            metrics.addProperty("parentCurrentZeroAllOne",true); metrics.addProperty("childCurrentZeroAllOne",true)
            metrics.addProperty("contextDoesNotSwitch",true); metrics.addProperty("explicitOpenSwitches",true)
            metrics.addProperty("draftPreserved",true)
            output.resolve("metrics.json").writeText(metrics.toString())
            j.addProperty("phase", "verified"); persist(j)
            output.resolve("verified.txt").writeText("scenario=$scenario\ncount=$count\nsourcePreserved=true\nwindowBounded=true\ncurrentZeroAllOne=true\ncontextNoSwitch=true\nexplicitOpenSwitch=true\ndraftPreserved=true\n")
        } catch (t: Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString()); runCatching { screenshot("failure") }; throw t }
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
        if (sid > 0L) {
            val sp = context.getSharedPreferences("message_search",0)
            val edit = sp.edit()
            sp.all.keys.filter { it == "history_$sid" || it.startsWith("history_${sid}_") }.forEach { edit.remove(it) }
            check(edit.commit())
        }
        check(searchPrefsDigest() == j["searchPrefsDigest"].asString)
        draftNames.forEach { n -> assertSnapshot(context.getSharedPreferences(n, 0), j.getAsJsonObject("drafts").getAsJsonObject(n)) }
        check(branches() == j.getAsJsonObject("branches")); check(digest() == j["digest"].asString)
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("uuidOnlyRemoved=true\nconfigPresenceValuesExact=true\nfourDraftsExact=true\nbranchPrefsExact=true\ntenTablesDigestExact=true\nsearchPrefsDigestExact=true\n")
    }
}

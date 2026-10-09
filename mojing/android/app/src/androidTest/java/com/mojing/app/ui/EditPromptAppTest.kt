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
class EditPromptAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("editPromptRun").orEmpty().also { UUID.fromString(it) }
    private val scenario get() = args.getString("scenario").orEmpty()
    private val title get() = "续章入口${run.take(8)}"
    private val marker get() = "PARENT_MEMORY_$run"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "edit-prompt-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "edit-prompt-$run").apply { mkdirs() }
    private val preferences get() = rule.activity.secureStorage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(rule.activity.secureStorage) as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun guard(idle: Boolean = true) {
        assumeTrue(args.getString("editPromptCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        if (idle) check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
        check(scenario in listOf("large"))
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "edit-prompt-$run.pending")
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
            "encyclopedia_entries", "characters", "character_profiles", "world_templates", "timeline_events", "session_memory_segments").forEach { table ->
            hash.update(table.toByteArray())
            db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { c ->
                while (c.moveToNext()) for (index in 0 until c.columnCount) { hash.update((c.getString(index) ?: "<null>").toByteArray()); hash.update(0.toByte()) }
            }
        }
        hash.digest().joinToString("") { "%02x".format(it) }
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

    @Test fun editPromptRequestAndPersistence() {
        guard(); check(!journalFile.exists())
        val base = args.getString("localBase").orEmpty(); check(base.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/v1")))
        val j = JsonObject().apply {
            addProperty("run", run); addProperty("scenario", scenario); addProperty("phase", "snapshot")
            add("originalConfig", JsonObject().apply { configKeys.forEach { k -> addProperty(k, if (preferences.contains(k)) preferences.getString(k, null) else null) } })
            add("drafts", JsonObject().apply { draftNames.forEach { n -> add(n, snapshot(context.getSharedPreferences(n, 0))) } })
            add("branches", branches()); addProperty("digest", digest())
        }; persist(j)
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
                    val body = when (n % 4) {
                        0 -> "雨落在青石街巷。她收起信纸，穿过灯影，仍记得城外旧桥与昨日的约定。\n\n".repeat(32)
                        1 -> "她在旧桥等他。"
                        2 -> "> 灯塔的约定\n\n- 信纸仍在\n- 船尚未归\n\n**雨城纪事**\n\n" + "守灯人记录昨日的风向与远行的约定。".repeat(18)
                        else -> "第一段：雨声。\n\n第二段：他在船舷写下日期。\n\n第三段：她仍然相信旧桥的约定。"
                    }
                    val content = heading + "\n\n" + body + "\nINDEX_${n}_$run" + if(n>3500) " FUTURE_$run" else if(n==3500) " OLD_SOURCE_$run" else ""
                    chars += content.length
                    estimatedTokens += com.mojing.app.domain.engine.TokenCounter.estimate(content)
                    val id = db.messageDao().insert(MessageEntity(sessionId = sid, speakerType = "narrator", content = content,
                        structuredContentJson = com.mojing.app.domain.story.NovelChapter.metadata("{\"synthetic_extra\":{\"locked\":true,\"run\":\"$run\"}}", n, heading)))
                    if (n == 3500) oldId = id
                    lastId = id
                }
            } }
            metrics.addProperty("insertMs", android.os.SystemClock.elapsedRealtime()-insertStart)
            metrics.addProperty("characters", chars); metrics.addProperty("estimatedTokens", estimatedTokens)
            if (scenario == "large") check(estimatedTokens >= 1_000_000)
            val platform = ModelPlatform("branch-$run", "本机子线验证", base, "local-branch-only", listOf("branch-$run"), "branch-$run", mapOf("branch-$run" to 16384))
            check(preferences.edit().putString("public_api_key", platform.apiKey).putString("public_base_url", base).putString("public_model", platform.selectedModel)
                .putString("model_platforms_v1", ModelPlatformCodec.encode(listOf(platform))).putString("active_model_platform", platform.id).putString("speaker_turn_mode", "auto").commit())
            val openStart = android.os.SystemClock.elapsedRealtime()
            openStory(sid)
            rule.waitUntil(20000) { model(sid).state.value.messages.lastOrNull()?.id == lastId }
            rule.waitForIdle()
            metrics.addProperty("openStoryUiMs", android.os.SystemClock.elapsedRealtime()-openStart)
            metrics.addProperty("initialWindowMessages", model(sid).state.value.messages.size)
            check(model(sid).state.value.messages.size <= 160)
            screenshot("01-latest-$scenario")
            val original = io { checkNotNull(db.messageDao().getById(oldId)) }
            val parentRows = io { db.messageDao().getBranchMessages(sid,"main") }
            io {
                db.sessionContextMemoryDao().upsert(SessionContextMemoryEntity(sessionId=sid,branchId="main",globalSummary="FUTURE_UCM_$run",sourceStartMessageId=parentRows.first().id,sourceEndMessageId=lastId))
                db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(sessionId=sid,branchId="main",startMessageId=parentRows.first().id,endMessageId=lastId,summary="FUTURE_SEGMENT_$run"))
            }
            val parentMemory=io { db.sessionContextMemoryDao().getBySessionAndBranch(sid,"main") }
            val draft = "未发送的旧桥约定-$run"
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).performTextReplacement(draft)
            rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0); rule.activity.currentFocus?.clearFocus() }
            rule.waitUntil(10000) { rule.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }
            rule.onAllNodesWithContentDescription("小说目录").onFirst().performClick()
            waitText("第${count}章 雨城${count}")
            rule.onNodeWithContentDescription("搜索小说目录",useUnmergedTree=true).performTextReplacement("第三千五百章")
            waitText("第3500章 雨城3500")
            rule.onNodeWithContentDescription("搜索小说目录",useUnmergedTree=true).performImeAction()
            rule.waitUntil(10000) { rule.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }
            rule.onAllNodesWithText("第3500章 雨城3500").onFirst().performClick()
            rule.waitUntil(20000) { model(sid).state.value.messages.any { it.id == oldId } && rule.onAllNodesWithText("小说目录").fetchSemanticsNodes().isEmpty() }
            rule.onAllNodesWithText("第3500章 雨城3500",substring=true).onFirst().performClick()
            waitText("编辑"); rule.onNodeWithText("编辑").performClick(); waitText("编辑消息")
            val edited = "第3500章 雨城3500\n\nEDITED_$run：她把航期改为明日，仍在旧桥等他。\n\n" + "> 只改变当前故事线。\n\n".repeat(12)
            rule.onAllNodes(hasSetTextAction(),useUnmergedTree=true).onLast().performTextReplacement(edited)
            screenshot("06-edit-old")
            rule.onNodeWithText("创建编辑分支").performClick()
            rule.waitUntil(20000) { model(sid).state.value.currentBranchId != "main" && rule.onAllNodesWithText("编辑消息").fetchSemanticsNodes().isEmpty() }
            rule.waitForIdle()
            val child = model(sid).state.value.currentBranchId
            val replacement = model(sid).state.value.messages.last()
            check(replacement.content == edited.trim() && replacement.regeneratedFromMessageId == oldId)
            check(!model(sid).state.value.hasNewerMessages && model(sid).state.value.messages.none { it.id > oldId && it.id != replacement.id })
            check(io { db.messageDao().getById(oldId) } == original)
            check(JsonParser.parseString(replacement.structuredContentJson).asJsonObject["synthetic_extra"] == JsonParser.parseString(original.structuredContentJson).asJsonObject["synthetic_extra"])
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft)
            screenshot("07-edited-child")
            rule.onAllNodesWithContentDescription("小说目录").onFirst().performClick()
            waitText("生成下一章")
            rule.onNodeWithText("生成下一章").performClick(); waitText("开始生成")
            rule.onAllNodes(hasSetTextAction(),useUnmergedTree=true).onLast().performTextReplacement("DIRECTION_$run：承接已改航期，不回原线未来。")
            screenshot("08-next-chapter")
            rule.onNodeWithText("开始生成").performClick()
            rule.waitUntil(30000) { io { db.messageDao().getVisibleMessagesTail(sid,child,10).any { it.content.contains("REPLY_$run") } } }
            rule.waitUntil(20000) { !model(sid).state.value.isGenerating && sid !in RetainedChatSessions.running.value }
            val after=io { db.messageDao().getVisibleMessagesTail(sid,child,30) }
            val reply=after.single { it.content.contains("REPLY_$run") }
            check(reply.branchId==child && com.mojing.app.domain.story.NovelChapter.number(reply.structuredContentJson)==3501)
            check(after.any { it.id==replacement.id && it.content==edited.trim() })
            check(after.none { it.content.contains("FUTURE_$run") || it.id==oldId })
            check(io { db.messageDao().getBranchMessages(sid,"main") }==parentRows)
            check(io { db.sessionContextMemoryDao().getBySessionAndBranch(sid,"main") }==parentMemory)
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft)
            screenshot("09-generated-child")
            rule.onNodeWithContentDescription("返回会话主页").performClick(); waitText("故事库")
            rule.onAllNodesWithText(title).filter(!hasSetTextAction()).onFirst().performClick()
            rule.waitUntil(20000) { modelOrNull(sid)?.state?.value?.let { it.isReady && it.currentBranchId==child && it.messages.lastOrNull()?.id==reply.id }==true }
            check(io { db.messageDao().getVisibleMessagesTail(sid,child,30) }==after)
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft)
            screenshot("10-generated-reentry")
            metrics.addProperty("editedChapter",3500); metrics.addProperty("generatedChapter",3501)
            metrics.addProperty("parentPreserved",true); metrics.addProperty("draftReentry",true)
            output.resolve("metrics.json").writeText(metrics.toString())
            j.addProperty("phase", "verified"); persist(j)
            output.resolve("verified.txt").writeText("scenario=$scenario\ncount=$count\nrawHistoryPreserved=true\nwindowBounded=true\noldChapterSearchAndJump=true\nreturnedLatest=true\n")
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
        draftNames.forEach { n -> assertSnapshot(context.getSharedPreferences(n, 0), j.getAsJsonObject("drafts").getAsJsonObject(n)) }
        check(branches() == j.getAsJsonObject("branches")); check(digest() == j["digest"].asString)
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("uuidOnlyRemoved=true\nconfigPresenceValuesExact=true\nfourDraftsExact=true\nbranchPrefsExact=true\nelevenTablesDigestExact=true\n")
    }
}

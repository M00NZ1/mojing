package com.mojing.app.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.ChatDraftStore
import com.mojing.app.data.local.entity.*
import com.mojing.app.data.ReplyRecoveryLoadResult
import com.mojing.app.ui.chat.ChatViewModel
import com.mojing.app.ui.chat.RetainedChatSessions
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Opt-in host-controlled phases. The HTTP server and kill driver live outside this process.
 * Configuration rollback is durable in private files before any configuration is changed.
 * A phase failure retains its UUID fixture and journal for explicit rollback, never broad cleanup.
 */
class ReplyRecoveryProcessAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext,
        PrototypeDatabaseEntryPoint::class.java).database()
    private val runId get() = args.getString("processRun").orEmpty().also { UUID.fromString(it) }
    private val journalFile get() = File(context.filesDir, "reply-process-$runId.json")
    private val output get() = File(context.getExternalFilesDir(null), "reply-process-$runId").apply { mkdirs() }
    private val preferences: SharedPreferences get() {
        val storage = rule.activity.secureStorage
        return storage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .get(storage) as SharedPreferences
    }
    private val keys = listOf("public_api_key", "public_base_url", "public_model", "speaker_turn_mode")
    private fun guard() {
        assumeTrue(args.getString("replyProcessCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
    }
    private fun persist(journal: JsonObject) {
        val pending = File(context.filesDir, "reply-process-$runId.pending")
        FileOutputStream(pending).use { it.write(journal.toString().toByteArray()); it.fd.sync() }
        check(pending.renameTo(journalFile))
    }
    private fun journal() = JsonParser.parseString(journalFile.readText()).asJsonObject.also {
        check(it.get("run").asString == runId)
    }
    private fun session(j: JsonObject) = j.get("session").asLong
    private fun open(j: JsonObject) {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(10_000) { rule.onAllNodesWithText("故事库").fetchSemanticsNodes().isNotEmpty() }
        val title = j.get("title").asString
        rule.waitUntil(10_000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(title).onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { model(session(j)).state.value.isReady }
    }
    private fun model(id: Long): ChatViewModel {
        val stores = RetainedChatSessions.stores
        val entries = stores.javaClass.getDeclaredField("entries").apply { isAccessible = true }.get(stores) as Map<*, *>
        val entry = checkNotNull(entries[id])
        return entry.javaClass.getDeclaredField("model").apply { isAccessible = true }.get(entry) as ChatViewModel
    }
    private fun awaitCommittedFrame() {
        rule.waitForIdle()
        instrumentation.waitForIdleSync()
        repeat(2) {
            val frame = java.util.concurrent.CountDownLatch(1)
            rule.runOnUiThread {
                val decor = rule.activity.window.decorView
                decor.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
                decor.invalidate()
            }
            check(frame.await(3, java.util.concurrent.TimeUnit.SECONDS)) { "recovery frame was not committed" }
        }
    }
    @Test fun streamUntilHostTerminatesProcess() {
        guard()
        check(!journalFile.exists()) { "existing journal must be rolled back first" }
        check(RetainedChatSessions.running.value.isEmpty()) { "another generation is active" }
        val mode = args.getString("speaker").orEmpty()
        check(mode in setOf("character", "narrator"))
        val base = args.getString("localBase").orEmpty()
        check(base.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/v1")))
        val original = JsonObject()
        keys.forEach { key -> original.add(key, JsonObject().apply {
            addProperty("present", preferences.contains(key))
            if (preferences.contains(key)) addProperty("value", preferences.getString(key, null))
        }) }
        val j = JsonObject().apply {
            addProperty("run", runId); addProperty("speaker", mode); addProperty("seedPid", Process.myPid())
            addProperty("title", "进程恢复-$runId"); add("original", original); addProperty("phase", "snapshot")
        }
        persist(j) // Never depend on an in-memory finally after process death.
        runBlocking(Dispatchers.IO) {
            val character = db.characterDao().upsert(CharacterEntity(name = "恢复角色-$runId", personaPrompt = "SYNTHETIC_$runId"))
            j.addProperty("character", character); persist(j)
            val id = db.sessionDao().insert(SessionEntity(title = j.get("title").asString, creationRequestId = runId))
            j.addProperty("session", id); persist(j)
            db.withTransaction {
                db.participantDao().upsert(SessionParticipantEntity(sessionId = id, characterId = character, forceNext = true))
                db.sessionWorldDao().upsert(SessionWorldEntity(sessionId = id, narratorEnabled = false,
                    choiceGenerationEnabled = false, autoSedimentEnabled = false,
                    sessionLlmApiKey = "local-process-only", sessionLlmBaseUrl = base))
                db.messageDao().insert(MessageEntity(sessionId = id, speakerType = "user", content = "SEED_$runId"))
            }
        }
        check(preferences.edit().putString("public_api_key", "local-process-only")
            .putString("public_base_url", base).putString("public_model", "local-process-model")
            .putString("speaker_turn_mode", "auto").commit())
        j.addProperty("phase", "configured"); persist(j)
        open(j)
        val id = session(j)
        if (mode == "character") {
            rule.onAllNodesWithContentDescription("消息输入").onFirst().performTextInput("SEND_$runId")
            rule.waitUntil(5_000) { model(id).state.value.inputText == "SEND_$runId" }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitForIdle()
            rule.onAllNodesWithContentDescription("发送").onFirst().performClick()
        } else {
            rule.onAllNodesWithContentDescription("更多输入工具").onFirst().performClick()
            rule.onAllNodesWithText("生成旁白").onFirst().performClick()
            rule.onAllNodesWithText("自动生成旁白").onFirst().performClick()
        }
        val prefix = "CHECKPOINT_$runId"
        val tail = "_SHORT_TAIL"
        val store = ChatDraftStore(context)
        try {
            rule.waitUntil(20_000) {
                val record = store.loadReplyRecovery(id)
                record is ReplyRecoveryLoadResult.Valid && record.snapshot.rawText.contains(prefix)
            }
        } catch (failure: Throwable) {
            val state = model(id).state.value
            output.resolve("diagnostic.txt").writeText("ready=${state.isReady}\ngenerating=${state.isGenerating}\ninputLength=${state.inputText.length}\nstreamLength=${state.streamingText.length}\nerror=${state.error}\n")
            throw failure
        }
        rule.waitUntil(10_000) { model(id).state.value.streamingText.contains(tail) }
        val record = store.loadReplyRecovery(id) as ReplyRecoveryLoadResult.Valid
        check(!record.snapshot.rawText.contains(tail)) { "short tail crossed a checkpoint; cannot prove requested window" }
        check(record.snapshot.speakerType == mode)
        check(RetainedChatSessions.running.value == setOf(id)) { "kill would affect another generation" }
        j.addProperty("checkpoint", record.snapshot.rawText); j.addProperty("token", record.snapshot.token)
        j.addProperty("phase", "ready-for-kill"); persist(j)
        output.resolve("ready.txt").writeText("pid=${Process.myPid()}\nsession=$id\nspeaker=$mode\ncheckpoint=true\nshortTailInUi=true\nshortTailPersisted=false\n")
        // Host checks ready, exact PID and journal phase before sending SIGKILL via run-as.
        repeat(600) { SystemClock.sleep(100) }
        error("host did not terminate the owned process")
    }
    @Test fun nextProcessShowsAndKeepsCheckpointOnce() {
        guard()
        val j = journal()
        check(j.get("phase").asString == "ready-for-kill")
        check(Process.myPid() != j.get("seedPid").asInt)
        val id = session(j)
        val store = ChatDraftStore(context)
        val expected = j.get("checkpoint").asString
        check((store.loadReplyRecovery(id) as ReplyRecoveryLoadResult.Valid).snapshot.rawText == expected)
        open(j)
        rule.waitUntil(10_000) { rule.onAllNodesWithText("上次生成中断").fetchSemanticsNodes().isNotEmpty() }
        check(model(id).state.value.replyRecovery?.text == expected)
        rule.onAllNodesWithText("上次生成中断").onFirst().assertIsDisplayed()
        awaitCommittedFrame()
        output.resolve("restored.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        if (args.getString("probeRecoveryGate") == "true") {
            val unsent = "UNSENT_$runId"
            rule.onAllNodesWithContentDescription("消息输入").onFirst().performTextInput(unsent)
            rule.waitUntil(5_000) { model(id).state.value.inputText == unsent }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitForIdle()
            rule.onAllNodesWithContentDescription("发送").onFirst().performClick()
            rule.waitForIdle()
            check(model(id).state.value.inputText == unsent && !model(id).state.value.isGenerating)
            runBlocking(Dispatchers.IO) {
                check(db.messageDao().getNextStoryContextBatch(id, "main", 0L, 20).none { it.content == unsent })
            }
            var launched = false
            rule.runOnIdle { launched = model(id).submitNarratorGuidance("BLOCKED_$runId") }
            output.resolve("gate.txt").writeText("sendBlockedWithoutCommit=true\ninputRetained=true\ngenerationAcceptedWithPendingRecovery=$launched\n")
            check(!launched) { "new generation accepted before pending recovery was handled" }
        }
        rule.onAllNodesWithText("保留为消息").onFirst().performClick()
        rule.waitUntil(10_000) { model(id).state.value.replyRecovery == null }
        rule.runOnIdle { model(id).keepRecoveredReply() } // stale double action must not insert again.
        runBlocking(Dispatchers.IO) {
            val messages = db.messageDao().getNextStoryContextBatch(id, "main", 0L, 20)
            val replies = messages.filter { it.speakerType == j.get("speaker").asString }
            check(replies.size == 1 && replies.single().content == expected)
            check(db.messageDao().findReplyRecoveryMessageId(id, "main", j.get("token").asString) == replies.single().id)
        }
        check(store.loadReplyRecovery(id) == ReplyRecoveryLoadResult.Missing)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(10_000) { rule.onAllNodesWithText("故事库").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(j.get("title").asString).onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }
        check(model(id).state.value.replyRecovery == null)
        j.addProperty("phase", "verified"); persist(j)
        output.resolve("verified.txt").writeText("newPid=${Process.myPid()}\noriginalPid=${j.get("seedPid").asInt}\ncheckpointRestored=true\nshortTailAbsent=true\nkeptOnce=true\nreopenedWithoutRecovery=true\n")
    }
    @Test fun rollbackOnlyOwnedFixtureAndExactConfiguration() {
        guard()
        val j = journal()
        val id = j.get("session")?.asLong ?: 0L
        if (id > 0L) {
            instrumentation.runOnMainSync { RetainedChatSessions.stores.stop(id) }
            rule.waitUntil(10_000) { id !in RetainedChatSessions.running.value }
            // Leave the fixture screen before restoring; no pending draft owner may overwrite rollback.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitForIdle()
        }
        val original = j.getAsJsonObject("original")
        val editor = preferences.edit()
        keys.forEach { key -> val value = original.getAsJsonObject(key)
            if (value.get("present").asBoolean) editor.putString(key, value.get("value").asString) else editor.remove(key)
        }
        check(editor.commit())
        keys.forEach { key -> val value = original.getAsJsonObject(key)
            check(preferences.contains(key) == value.get("present").asBoolean)
            if (value.get("present").asBoolean) check(preferences.getString(key, null) == value.get("value").asString)
        }
        runBlocking(Dispatchers.IO) {
            db.withTransaction {
                if (id > 0L) {
                    val owned = db.sessionDao().getById(id)
                    check(owned == null || owned.creationRequestId == runId)
                    db.openHelper.writableDatabase.execSQL("DELETE FROM llm_cost_records WHERE sessionId = ?", arrayOf(id))
                    db.sessionDao().delete(id)
                }
                j.get("character")?.asLong?.let { character ->
                    val owned = db.characterDao().getById(character)
                    check(owned == null || owned.name == "恢复角色-$runId")
                    db.characterDao().delete(character)
                }
            }
        }
        if (id > 0L) {
            val drafts = context.getSharedPreferences("chat_drafts_v1", 0)
            check(drafts.edit().remove("session_$id").remove("reply_recovery_v1_session_$id").commit())
        }
        j.addProperty("phase", "rolled-back"); persist(j) // Private original snapshot retained, never exported.
        output.resolve("rollback.txt").writeText("exactConfigRestored=true\nownedFixtureRemoved=true\nprivateJournalRetained=true\n")
    }
}

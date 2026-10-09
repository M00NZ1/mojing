package com.mojing.app.dao

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.mojing.app.data.StoryOpeningInputDraft
import com.mojing.app.data.StoryOpeningInputDraftStore
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.search.MessageSearchIndexManager
import com.mojing.app.domain.engine.TokenCounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit, isolated on-device measurements. No production database or provider is used. */
@RunWith(AndroidJUnit4::class)
class AndroidCompletionScaleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private fun requireOptIn() {
        assumeTrue(args.getString("completionScale") == "true")
        assumeTrue(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
    }

    private fun report(name: String, values: Map<String, Any>) {
        val run = args.getString("scaleRun", "android-complete-scale")!!
        require(run.matches(Regex("[a-zA-Z0-9_-]+")))
        val directory = File(context.getExternalFilesDir(null), run).apply { mkdirs() }
        File(directory, "$name.json").writeText(Gson().toJson(values + mapOf(
            "device" to Build.MODEL, "sdk" to Build.VERSION.SDK_INT,
            "evidence" to "emulator; local synthetic data; elapsed includes instrumentation overhead",
        )))
    }

    @Test fun largeHistoryPagingSearchEditAndOldMessageLookup() = runBlocking(Dispatchers.IO) {
        requireOptIn()
        val name = "completion_scale_${UUID.randomUUID()}.db"
        check(!context.getDatabasePath(name).exists())
        var database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val metrics = linkedMapOf<String, Any>()
        suspend fun <T> timed(label: String, block: suspend () -> T): T {
            val start = SystemClock.elapsedRealtimeNanos()
            return block().also { metrics[label] = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0 }
        }
        try {
            val session = database.sessionDao().insert(SessionEntity(title = "大型历史独立验收"))
            val paragraph = "海港灯塔的守夜人沿石阶走向码头，确认远行者留下的约定。".repeat(10)
            var chars = 0L
            var estimatedTokens = 0L
            var oldMessageId = 0L
            timed("insert_5000_ms") {
                for (batch in 0 until 5000 step 128) {
                    val rows = (batch until minOf(batch + 128, 5000)).map { index ->
                        val text = "第${index}段：" + paragraph + if (index == 37) "琥珀旧航图唯一记号" else ""
                        chars += text.length
                        estimatedTokens += TokenCounter.estimate(text)
                        MessageEntity(sessionId = session, speakerType = if (index % 2 == 0) "user" else "narrator", content = text)
                    }
                    val ids = database.messageDao().insertAll(rows)
                    if (batch == 0) oldMessageId = ids[37]
                }
            }
            metrics["messages"] = 5000
            metrics["characters"] = chars
            metrics["estimated_tokens"] = estimatedTokens
            metrics["token_estimator"] = "TokenCounter.estimate; CJK 1.6 and ASCII 0.8, not provider tokenizer"
            timed("index_ready_ms") { MessageSearchIndexManager(database.messageDao()).ensureSessionReady(session) }
            database.close()
            database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val before = Debug.getPss()
            val dao = database.messageDao()
            val tail = timed("reopen_and_tail80_ms") { dao.getMainMessagesTail(session, 80) }
            assertEquals(80, tail.size)
            var cursor = tail.last().id
            var visited = tail.size
            var window = tail
            timed("walk_5000_keyset_ms") {
                while (true) {
                    val page = dao.getMainMessagesBefore(session, cursor, 40)
                    if (page.isEmpty()) break
                    assertTrue(page.all { it.id < cursor })
                    assertEquals(page.size, page.map { it.id }.distinct().size)
                    visited += page.size
                    cursor = page.last().id
                    window = (window + page).takeLast(200)
                    assertTrue(window.size <= 200)
                }
            }
            assertEquals(5000, visited)
            val found = timed("chinese_phrase_search_ms") { dao.searchMainMessages(session, "琥珀旧航图", 0, 40) }
            assertEquals(listOf(oldMessageId), found.map { it.id })
            assertTrue(found.single().content.length <= 2048)
            val old = timed("old_message_and_window_ms") {
                val source = dao.getMainMessageById(session, oldMessageId)!!
                val beforePage = dao.getMainMessagesBefore(session, source.id, 40)
                val afterPage = dao.getMainMessagesAfter(session, source.id, 40)
                assertTrue(beforePage.all { it.id < source.id })
                assertTrue(afterPage.all { it.id > source.id })
                source
            }
            timed("edit_and_incremental_search_ms") {
                dao.updateContent(old.id, old.content.replace("琥珀旧航图唯一记号", "银色罗盘校验新记号"))
                assertTrue(dao.searchMainMessages(session, "琥珀旧航图", 0, 40).isEmpty())
                assertEquals(old.id, dao.searchMainMessages(session, "银色罗盘", 0, 40).single().id)
            }
            val first = dao.searchMainMessages(session, "灯塔", 0, 40)
            val second = dao.searchMainMessages(session, "灯塔", 0, 40, first.last().id)
            assertEquals(40, first.size)
            assertEquals(40, second.size)
            assertTrue(first.map { it.id }.intersect(second.map { it.id }.toSet()).isEmpty())
            metrics["search_total"] = dao.countMainMessages(session, "灯塔", 0)
            assertEquals(5000, metrics["search_total"])
            metrics["pss_before_window_kib"] = before
            metrics["pss_after_walk_search_edit_kib"] = Debug.getPss()
            metrics["max_retained_message_window"] = 200
            report("large-history", metrics)
        } finally {
            database.close()
            check(context.deleteDatabase(name)) // This test's unique database only.
        }
    }

    @Test fun measureLargeOpeningInputSerialization() = runBlocking {
        requireOptIn()
        val prefix = "completion_input_${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${prefix}_$name", mode)
        }
        val store = StoryOpeningInputDraftStore(isolated)
        val measurements = linkedMapOf<String, Any>()
        try {
            for (length in listOf(1_000, 50_000, 200_000)) {
                val draft = StoryOpeningInputDraft.EMPTY.copy(premise = "海".repeat(length))
                val samples = ArrayList<Double>()
                repeat(30) { index ->
                    withContext(Dispatchers.Main) {
                        val start = SystemClock.elapsedRealtimeNanos()
                        store.save(draft.copy(direction = "输入$index"))
                        samples += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    }
                }
                store.commit(draft)
                assertEquals(draft, StoryOpeningInputDraftStore(isolated).load())
                measurements["${length}_chars"] = mapOf("samples" to 30, "median_ms" to samples.sorted()[15],
                    "p95_ms" to samples.sorted()[28], "max_ms" to samples.max())
            }
            report("input-serialization", measurements)
        } finally { context.deleteSharedPreferences("${prefix}_story_opening_input_draft_v1") }
    }
}

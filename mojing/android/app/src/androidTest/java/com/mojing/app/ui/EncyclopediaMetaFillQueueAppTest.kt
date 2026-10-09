package com.mojing.app.ui

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.GenerationTaskEntity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Opt-in production queue/HTTP/Room check. MainActivity is real; concurrent edits use its production save owner. */
class EncyclopediaMetaFillQueueAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val run get() = args.getString("metaFillRun").orEmpty().also { UUID.fromString(it) }
    private val dependencies get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java)
    private val preferences get() = rule.activity.secureStorage.javaClass.getDeclaredField("prefs")
        .apply { isAccessible = true }.get(rule.activity.secureStorage) as SharedPreferences
    private val keys = listOf("public_api_key", "public_base_url", "public_model")
    private val journal get() = File(context.filesDir, "meta-fill-$run.json")
    private fun guard() {
        assumeTrue(args.getString("metaFillQueueCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
    }
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun persist(value: JsonObject) {
        val pending = File(context.filesDir, "meta-fill-$run.pending")
        FileOutputStream(pending).use { it.write(value.toString().toByteArray()); it.fd.sync() }
        check(pending.renameTo(journal))
    }

    @Test fun delayedQueueResultPreservesEditsAndDoesNotRecreateDeletedEntry() {
        guard()
        val dep = dependencies
        val db = dep.database()
        check(io { db.generationTaskDao().countActive() } == 0)
        check(!dep.processor().isQueuePaused())
        check(!journal.exists())
        val j = JsonObject().apply { addProperty("run", run) }
        val original = JsonObject()
        keys.forEach { key -> original.add(key, JsonObject().apply {
            addProperty("present", preferences.contains(key)); addProperty("value", preferences.getString(key, ""))
        }) }
        j.add("original", original); persist(j)
        val world = io { db.encyclopediaDao().upsert(EncyclopediaEntity(name = "META_WORLD_$run")) }
        j.addProperty("world", world); persist(j)
        val first = io { dep.saveEntry()(EncyclopediaEntryEntity(encyclopediaId = world, entryType = "location", title = "FIRST_$run", content = "SEED_$run")) }
        val second = io { dep.saveEntry()(EncyclopediaEntryEntity(encyclopediaId = world, entryType = "location", title = "SECOND_$run")) }
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 25_000
        val received = List(2) { CountDownLatch(1) }
        val release = List(2) { CountDownLatch(1) }
        val failure = AtomicReference<Throwable?>(null)
        val responder = thread(isDaemon = true) {
            try {
                repeat(2) { index -> server.accept().use { socket ->
                    socket.soTimeout = 20_000
                    val input = socket.getInputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val byte = input.read(); check(byte >= 0 && header.length < 32_768); header.append(byte.toChar())
                    }
                    val length = header.lines().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    check(length in 1..100_000)
                    val body = input.readNBytes(length).toString(Charsets.UTF_8)
                    val request = JsonParser.parseString(body).asJsonObject
                    check(request["model"].asString == "meta-model-$run")
                    check(request["messages"].toString().contains(if (index == 0) "FIRST_$run" else "SECOND_$run"))
                    received[index].countDown()
                    check(release[index].await(20, TimeUnit.SECONDS))
                    val content = Gson().toJson(mapOf("title" to "MODEL_TITLE", "content" to "MODEL_CONTENT", "region" to "FILLED_REGION_$run"))
                    val response = Gson().toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to content), "finish_reason" to "stop")),
                        "usage" to mapOf("prompt_tokens" to 10, "completion_tokens" to 5))).toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(response); flush()
                    }
                } }
            } catch (error: Throwable) { failure.set(error); received.forEach { it.countDown() } }
        }
        try {
            check(preferences.edit().putString("public_api_key", "local-meta-only").putString("public_model", "meta-model-$run")
                .putString("public_base_url", "http://127.0.0.1:${server.localPort}/v1").commit())
            val task = io { dep.processor().enqueueEncyclopediaMetaFill(world, "META_WORLD_$run", listOf(first.id, second.id)) }
            j.addProperty("task", task); persist(j)
            check(received[0].await(20, TimeUnit.SECONDS)); failure.get()?.let { throw it }
            io { dep.saveEntry().saveEdited(first.copy(title = "EDITED_$run", content = "USER_CONTENT_$run")) }
            release[0].countDown()
            check(received[1].await(20, TimeUnit.SECONDS)); failure.get()?.let { throw it }
            io { db.encyclopediaEntryDao().delete(second.id) }
            release[1].countDown()
            rule.waitUntil(20_000) { io { db.generationTaskDao().getById(task)?.status == "COMPLETED" } }
            responder.join(2_000); failure.get()?.let { throw it }
            val saved = io { db.encyclopediaEntryDao().getById(first.id)!! }
            assertEquals("EDITED_$run", saved.title)
            assertEquals("USER_CONTENT_$run", saved.content)
            assertEquals("FILLED_REGION_$run", JsonParser.parseString(saved.metaJson).asJsonObject["region"].asString)
            assertNull(io { db.encyclopediaEntryDao().getById(second.id) })
            assertEquals(2, io { db.generationTaskDao().getById(task)!!.progressDone })
            j.addProperty("phase", "verified"); persist(j)
        } finally {
            release.forEach { it.countDown() }; server.close(); responder.join(3_000)
        }
    }

    @Test fun rollbackExactFixtureAndConfiguration() {
        guard()
        val dep = dependencies
        val db = dep.database()
        val j = JsonParser.parseString(journal.readText()).asJsonObject
        check(j["run"].asString == run)
        val task = j["task"]?.asLong
        if (task != null) {
            io { dep.processor().cancelTask(task) }
            // The serial worker reaching this no-network marker proves the prior request unwound.
            val marker = io { db.generationTaskDao().insert(GenerationTaskEntity(
                taskKind = "meta_fixture_barrier", title = "META_BARRIER_$run", status = "QUEUED", payloadJson = "{}")) }
            j.addProperty("marker", marker); persist(j)
            val deadline = System.currentTimeMillis() + 35_000
            while (io { db.generationTaskDao().getById(marker)?.status != "FAILED" }) {
                check(System.currentTimeMillis() < deadline); Thread.sleep(100)
            }
        }
        val original = j.getAsJsonObject("original")
        val editor = preferences.edit()
        keys.forEach { key -> val value = original.getAsJsonObject(key)
            if (value["present"].asBoolean) editor.putString(key, value["value"].asString) else editor.remove(key)
        }
        check(editor.commit())
        keys.forEach { key -> val value = original.getAsJsonObject(key)
            check(preferences.contains(key) == value["present"].asBoolean)
            if (value["present"].asBoolean) check(preferences.getString(key, null) == value["value"].asString)
        }
        io { db.withTransaction {
            val world = j["world"]?.asLong
            if (world != null) {
                check(db.encyclopediaDao().getById(world)?.name == "META_WORLD_$run")
                if (task != null) {
                    check(db.generationTaskDao().getById(task)?.targetEncyclopediaId == world)
                    db.openHelper.writableDatabase.execSQL("DELETE FROM generation_tasks WHERE id = ?", arrayOf(task))
                }
                db.encyclopediaDao().delete(world)
                assertNull(db.encyclopediaDao().getById(world))
            }
            db.openHelper.writableDatabase.execSQL("DELETE FROM llm_cost_records WHERE modelName = ?", arrayOf("meta-model-$run"))
            j["marker"]?.asLong?.let { marker ->
                check(db.generationTaskDao().getById(marker)?.title == "META_BARRIER_$run")
                db.openHelper.writableDatabase.execSQL("DELETE FROM generation_tasks WHERE id = ?", arrayOf(marker))
            }
        } }
        j.addProperty("phase", "rolled-back"); persist(j)
    }
}

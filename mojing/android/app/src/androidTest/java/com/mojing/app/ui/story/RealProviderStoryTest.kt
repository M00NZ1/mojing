package com.mojing.app.ui.story

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.engine.UniversalContextMemoryManager
import com.mojing.app.domain.story.StoryWritingUseCase
import com.mojing.app.domain.usecase.CreateSessionUseCase
import com.mojing.app.domain.usecase.SessionCreationTransaction
import com.mojing.app.ui.theme.MoJingTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import okio.buffer

/** Explicit opt-in acceptance against temporary provider credentials, real UI and isolated Room storage. */
class RealProviderStoryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var db: AppDatabase? = null
    private var databaseName = ""
    private var preferenceName = ""
    private val store = ViewModelStore()
    private lateinit var vm: StorySimulationViewModel
    private lateinit var storage: SecureStorage
    private lateinit var retry: LlmRetry
    @Volatile private var openedSession = 0L

    private fun prepare(provider: String, invalidModel: Boolean = false) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realProviders") == "true")
        val secretFile = File(context.filesDir, "real-provider-keys.json")
        assumeTrue(secretFile.isFile)
        val key = JSONObject(secretFile.readText()).getString(provider)
        databaseName = "real-provider-${UUID.randomUUID()}.db"
        preferenceName = "real-provider-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(preferenceName, mode)
        }
        storage = SecureStorage().also { it.init(isolated) }
        storage.publicApiKey = key
        storage.publicBaseUrl = if (provider == "siliconflow") "https://api.siliconflow.cn/v1" else "https://api.deepseek.com"
        storage.publicModel = if (invalidModel) "mojing-nonexistent-model-acceptance" else model(provider)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        db = database
        val api = LlmApiService()
        if (InstrumentationRegistry.getArguments().getString("captureProviderResponses") == "true") {
            // Explicit diagnostics for this neutral, isolated fixture only. Never record headers or credentials.
            val field = LlmApiService::class.java.getDeclaredField("client").apply { isAccessible = true }
            val original = field.get(api) as okhttp3.OkHttpClient
            val folder = File(context.getExternalFilesDir(null), "provider-review").apply { mkdirs() }
            val serial = java.util.concurrent.atomic.AtomicInteger()
            field.set(api, original.newBuilder().addInterceptor { chain ->
                val stem = "$provider-${System.currentTimeMillis()}-${serial.incrementAndGet()}"
                val requestBuffer = okio.Buffer()
                chain.request().body?.writeTo(requestBuffer)
                File(folder, "$stem-request.json").writeText(requestBuffer.readUtf8())
                val response = chain.proceed(chain.request())
                val body = response.body ?: return@addInterceptor response
                val received = okio.Buffer()
                val source = object : okio.ForwardingSource(body.source()) {
                    override fun close() {
                        try { super.close() } finally {
                            File(folder, "$stem-response.txt").writeText(received.clone().readUtf8())
                        }
                    }
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        val size = super.read(sink, byteCount)
                        if (size > 0 && received.size + size <= 256_000L) sink.copyTo(received, sink.size - size, size)
                        if (size == -1L) File(folder, "$stem-response.txt").writeText(received.clone().readUtf8())
                        return size
                    }
                }.buffer()
                response.newBuilder().body(object : okhttp3.ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = source
                }).build()
            }.build())
        }
        retry = LlmRetry(api, CostRecorder(database.costRecordDao(), com.mojing.app.domain.billing.BillingPriceRepository(database.costRecordDao(), com.mojing.app.data.repository.BillingPreferences(isolated)), storage))
        rule.runOnUiThread {
            rule.activity.actionBar?.hide()
            vm = StorySimulationViewModel(StoryWritingUseCase(retry), storage,
                database.worldTemplateDao(), database.encyclopediaDao(), database.characterDao(),
                CreateSessionUseCase(SessionCreationTransaction(database), database.characterDao(), database.worldTemplateDao(), storage, database.legacyWorldMappingDao(), database.encyclopediaDao()),
                com.mojing.app.data.StoryOpeningDraftStore(database))
            store.put("provider-story", vm)
        }
        rule.setContent { MoJingTheme { StorySimulationScreen(rememberNavController(), { openedSession = it }, vm) } }
        rule.waitUntil(10_000) { !vm.state.value.isRestoring }
    }

    private fun model(provider: String) = if (provider == "siliconflow") "Pro/deepseek-ai/DeepSeek-V3.2" else "deepseek-flash"

    private fun start(chapters: Int) {
        rule.onNodeWithText("故事背景与大致设定 *").performScrollTo().performTextInput(
            "雾港灯塔在暴雨前熄灭。修复师沈照与信使林汐发现值班日志缺了一页。两人决定先核实记录，再寻找失踪的守塔人。写克制的悬疑冒险，线索逐步揭晓。",
        )
        rule.onNodeWithText("$chapters 章").performScrollTo().performClick()
        rule.onNodeWithText("生成小说并开始创作").performScrollTo().performClick()
    }

    private fun awaitResult() {
        rule.waitUntil(310_000) { openedSession > 0 || vm.state.value.error != null }
        val error = vm.state.value.error
        if (error != null) Log.i("ProviderAcceptance", "generation_failed message=$error")
        assertNull("Real provider generation must finish successfully", error)
        assertTrue(openedSession > 0)
    }

    private fun capture(name: String) {
        val folder = File(context.getExternalFilesDir(null), "provider-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun success(provider: String, chapters: Int) {
        prepare(provider)
        start(chapters)
        rule.waitUntil(310_000) { vm.state.value.preview.isNotBlank() || vm.state.value.error != null || openedSession > 0 }
        if (vm.state.value.preview.isNotBlank()) {
            rule.onNodeWithText("复制已接收预览").performScrollTo().assertIsDisplayed()
            capture("$provider-$chapters-stream")
        }
        awaitResult()
        val database = checkNotNull(db)
        val rows = runBlocking { database.messageDao().getNextStoryContextBatch(openedSession, "main", 0L, 10) }
        assertEquals(chapters, rows.count { it.speakerType == "narrator" })
        assertTrue(rows.filter { it.speakerType == "narrator" }.all { it.content.length > 100 })
        val result = JSONObject().put("provider", provider).put("chapters", chapters)
            .put("first_content_ms", vm.state.value.firstContentDelayMs).put("elapsed_ms", vm.state.value.generationElapsedMs)
            .put("characters", vm.state.value.receivedChars).put("saved_messages", rows.size)
        Log.i("ProviderAcceptance", result.toString())
        File(context.getExternalFilesDir(null), "provider-review/$provider-$chapters-result.json").writeText(result.toString())
        if (chapters == 1) {
            runBlocking {
                database.messageDao().insert(MessageEntity(sessionId = openedSession, speakerType = "user", content = "补充确定事实：缺失的日志已在码头仓库找到，沈照保管原件，林汐只知道日志已找到。"))
                val manager = UniversalContextMemoryManager(database.sessionContextMemoryDao(), database.messageDao(), retry)
                val revision = manager.reserveUpdateRevision(openedSession, "main")
                val began = System.nanoTime()
                assertTrue("Real memory JSON should parse and persist", manager.rebuild(openedSession, "main", revision,
                    storage.publicApiKey, storage.publicBaseUrl, storage.publicModel, "雾港灯塔调查", listOf("沈照", "林汐")))
                val memory = manager.getFormattedMemory(openedSession, "main")
                assertTrue(memory.isNotBlank())
                Log.i("ProviderAcceptance", "memory provider=$provider elapsed_ms=${(System.nanoTime()-began)/1_000_000} chars=${memory.length}")
                manager.clear(openedSession, "main")
                assertEquals("", manager.getFormattedMemory(openedSession, "main"))
            }
        }
    }

    @Test fun siliconflowSingleChapterAndMemory() = success("siliconflow", 1)
    @Test fun deepseekSingleChapterAndMemory() = success("deepseek", 1)
    @Test fun siliconflowTwoChapters() = success("siliconflow", 2)
    @Test fun deepseekTwoChapters() = success("deepseek", 2)

    @Test fun siliconflowMemoryFromRealStory() = memoryFromRealStory("siliconflow")
    @Test fun deepseekMemoryFromRealStory() = memoryFromRealStory("deepseek")

    private fun memoryFromRealStory(provider: String) {
        prepare(provider)
        runBlocking {
            val database = checkNotNull(db)
            val creator = CreateSessionUseCase(SessionCreationTransaction(database), database.characterDao(), database.worldTemplateDao(), storage, database.legacyWorldMappingDao(), database.encyclopediaDao())
            val id = (creator.createBlank(title = "记忆回归 · 雾港") as CreateSessionUseCase.Result.Created).sessionId
            val text = InstrumentationRegistry.getInstrumentation().context.assets.open("provider-story-context.json").bufferedReader().use { it.readText() }
            val rows = org.json.JSONArray(text)
            for (index in 0 until rows.length()) {
                val item = rows.getJSONObject(index)
                database.messageDao().insert(MessageEntity(sessionId = id, speakerType = item.getString("speakerType"), content = item.getString("content")))
            }
            val manager = UniversalContextMemoryManager(database.sessionContextMemoryDao(), database.messageDao(), retry)
            val revision = manager.reserveUpdateRevision(id, "main")
            val began = System.nanoTime()
            assertTrue(manager.rebuild(id, "main", revision, storage.publicApiKey, storage.publicBaseUrl,
                storage.publicModel, "雾港灯塔调查", listOf("沈照", "林汐")))
            val memory = manager.getFormattedMemory(id, "main")
            assertTrue(memory.contains("沈照") && memory.contains("林汐") && memory.contains("日志"))
            Log.i("ProviderAcceptance", "memory_replay provider=$provider elapsed_ms=${(System.nanoTime()-began)/1_000_000} chars=${memory.length}")
            manager.clear(id, "main")
            assertEquals("", manager.getFormattedMemory(id, "main"))
            assertEquals(rows.length(), database.messageDao().getNextStoryContextBatch(id, "main", 0L, 10).size)
        }
    }

    @Test fun realCancellationPreservesDraftAndDoesNotCreateSession() {
        cancelRealRequest(afterContent = false)
    }

    @Test fun realCancellationAfterContentPreservesPreview() {
        cancelRealRequest(afterContent = true)
    }

    private fun cancelRealRequest(afterContent: Boolean) {
        prepare("siliconflow")
        start(2)
        rule.waitUntil(90_000) {
            if (afterContent) vm.state.value.preview.length > 60
            else vm.state.value.generationElapsedMs >= 1_000 || vm.state.value.preview.isNotBlank()
        }
        val started = System.nanoTime()
        rule.onNodeWithText("停止").performScrollTo().performClick()
        rule.waitUntil(5_000) { !vm.state.value.isGenerating }
        assertEquals(0L, openedSession)
        assertTrue(vm.state.value.premise.isNotBlank())
        if (afterContent) assertTrue(vm.state.value.preview.length > 60)
        assertEquals("已停止", vm.state.value.generationStage)
        Log.i("ProviderAcceptance", "cancel elapsed_ms=${(System.nanoTime()-started)/1_000_000} preview_chars=${vm.state.value.preview.length}")
        capture(if (afterContent) "cancelled-after-content" else "cancelled")
    }

    @Test fun realInvalidModelCanRetryWithCorrectModel() {
        prepare("deepseek", invalidModel = true)
        start(1)
        rule.waitUntil(45_000) { vm.state.value.error != null }
        assertEquals(0L, openedSession)
        assertTrue(vm.state.value.premise.isNotBlank())
        rule.onNodeWithText("重试").performScrollTo().assertIsDisplayed()
        capture("invalid-model")
        storage.publicModel = model("deepseek")
        rule.onNodeWithText("重试").performClick()
        awaitResult()
        Log.i("ProviderAcceptance", "invalid_model_retry successful=true")
    }

    @After fun cleanOwnFixture() {
        rule.runOnUiThread { store.clear() }
        db?.close()
        if (databaseName.startsWith("real-provider-")) context.deleteDatabase(databaseName)
        if (preferenceName.startsWith("real-provider-")) context.deleteSharedPreferences(preferenceName)
    }
}

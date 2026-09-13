package com.mojing.app.ui.story

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.data.SecureStorage
import com.mojing.app.data.StoryOpeningDraftStore
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.remote.LlmApiService
import com.mojing.app.domain.billing.CostRecorder
import com.mojing.app.domain.engine.LlmRetry
import com.mojing.app.domain.story.StoryChapter
import com.mojing.app.domain.story.StoryOpeningDraft
import com.mojing.app.domain.story.StoryOpeningRecord
import com.mojing.app.domain.story.StoryWritingResult
import com.mojing.app.domain.story.StoryWritingUseCase
import com.mojing.app.domain.usecase.CreateSessionUseCase
import com.mojing.app.domain.usecase.SessionCreationTransaction
import com.mojing.app.ui.theme.MoJingTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Run each phase in a separate instrumentation process with the same opt-in database name. */
class StoryRecoveryProcessTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun name(): String {
        val value = InstrumentationRegistry.getArguments().getString("recoveryDb").orEmpty()
        assumeTrue(value.matches(Regex("story_process_[a-z0-9_]+\\.db")))
        return value
    }
    private fun open(name: String) = Room.databaseBuilder(context, AppDatabase::class.java, name).build()

    @Test fun seedPendingForNextProcess() = runBlocking {
        val name = name()
        check(!context.getDatabasePath(name).exists())
        val database = open(name)
        try {
            StoryOpeningDraftStore(database).persist(StoryOpeningDraft(
                premise = "雾港来信", direction = "寻找灯塔", tone = "悬疑", template = null,
                encyclopediaId = null, characterIds = emptyList(), worldPrompt = "只有主角知道信件内容",
                result = StoryWritingResult("灯塔归途", listOf(StoryChapter(1, "第一封信", "长篇正文".repeat(4000) + "完整结尾")), listOf("前往灯塔")), model = "原模型"))
        } finally { database.close() }
    }

    @Test fun nextProcessRestoresAndSavesThroughScreen() = runBlocking {
        val name = name()
        check(context.getDatabasePath(name).exists())
        val database = open(name)
        val models = ViewModelStore()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int) = super.getSharedPreferences(name, mode)
        }
        val storage = SecureStorage().also { it.init(isolated) }
        try {
            assertTrue(storage.publicApiKey.isBlank())
            lateinit var vm: StorySimulationViewModel
            var opened = 0L
            rule.runOnUiThread {
                vm = StorySimulationViewModel(StoryWritingUseCase(LlmRetry(LlmApiService(), CostRecorder(database.costRecordDao(), com.mojing.app.domain.billing.BillingPriceRepository(database.costRecordDao(), com.mojing.app.data.repository.BillingPreferences(isolated)), storage))),
                    storage, database.worldTemplateDao(), database.encyclopediaDao(), database.characterDao(),
                    CreateSessionUseCase(SessionCreationTransaction(database), database.characterDao(), database.worldTemplateDao(), storage, database.legacyWorldMappingDao(), database.encyclopediaDao()),
                    StoryOpeningDraftStore(database))
                models.put("recovery", vm)
            }
            rule.setContent { MoJingTheme { StorySimulationScreen(rememberNavController(), { opened = it }, vm) } }
            rule.waitUntil(10_000) { vm.state.value.hasPendingStory }
            rule.onNodeWithText("继续上次创作").assertIsDisplayed()
            java.io.File(context.getExternalFilesDir(null), "$name-pending.png").outputStream().use {
                rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            rule.onNodeWithText("复制完整正文").assertIsDisplayed().performClick()
            assertTrue(vm.pendingStoryText().endsWith("前往灯塔"))
            assertTrue(vm.pendingStoryText().contains("完整结尾"))
            rule.onNodeWithText("保存并进入会话").assertIsDisplayed().performClick()
            rule.waitUntil(10_000) { opened > 0L }
            val saved = StoryOpeningDraftStore(database).load() as StoryOpeningRecord.Saved
            assertEquals(opened, saved.sessionId)
            val messages = database.messageDao().getNextStoryContextBatch(opened, "main", 0L, 10)
            assertEquals(2, messages.size)
            assertTrue(messages.last().content.contains("完整结尾"))
            rule.onNodeWithText("打开已保存的会话").assertIsDisplayed().performClick()
            java.io.File(context.getExternalFilesDir(null), "$name-saved.png").outputStream().use {
                rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            assertEquals(saved.sessionId, opened)
        } finally {
            rule.runOnUiThread { models.clear() }
            database.close()
            context.deleteDatabase(name)
            context.deleteSharedPreferences(name)
        }
    }
}

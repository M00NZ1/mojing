package com.mojing.app.ui

import android.graphics.Bitmap
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.room.withTransaction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.MainActivity
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.ui.chat.ChatViewModel
import com.mojing.app.ui.chat.RetainedChatSessions
import kotlinx.coroutines.Job
import dagger.hilt.android.EntryPointAccessors
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in complete App acceptance for the configured session loop:
 * choose world and character in the real picker, create a session, leave and
 * reopen it, then send a message through a local SSE endpoint.
 *
 * This test deliberately does not use a real provider or a direct session
 * creation call as a substitute for the UI selection and creation path.
 */
@RunWith(AndroidJUnit4::class)
class ConfiguredSessionLoopAppCaptureTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val database get() = EntryPointAccessors
        .fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java)
        .database()

    private val suffix = UUID.randomUUID().toString()
    private val worldName = "验收世界-$suffix"
    private val worldPrompt = "WORLD_PROMPT_$suffix"
    private val characterName = "验收角色-$suffix"
    private val persona = "PERSONA_$suffix"
    private val sessionTitle = "配置会话-$suffix"
    private val userMessage = "USER_MESSAGE_$suffix"
    private val replyMessage = "REPLY_MESSAGE_$suffix"
    private val narration = "NARRATION_$suffix"
    private val narratorMode get() = args.getString("narratorMode") ?: "off"
    private var pendingObservedAt = 0L
    private var leaveDispatchedAt = 0L

    private var worldId = 0L
    private var characterId = 0L
    private var sessionId = 0L
    private var server: LocalSseServer? = null
    private var cleanupRequired = false
    private var securePreferences: SharedPreferences? = null
    private var securePreferenceSnapshot = emptyMap<String, PreferenceState>()
    private var draftPreferences: SharedPreferences? = null
    private var draftPreferenceSnapshot = emptyMap<String, PreferenceState>()
    private var originalIntentFingerprint = ""

    private data class PreferenceState(val present: Boolean, val value: Any?)

    @Before
    fun setUp() {
        assumeTrue("configuredSessionLoopCapture=true is required", args.getString("configuredSessionLoopCapture") == "true")
        val generic = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.startsWith("sdk_gphone", ignoreCase = true) ||
            Build.DEVICE.contains("emulator", ignoreCase = true)
        assumeTrue("configured session capture is restricted to a generic emulator", generic)

        val storage = rule.activity.secureStorage
        securePreferences = storagePreferences(storage)
        securePreferenceSnapshot = SECURE_KEYS.associateWith { key -> preferenceState(securePreferences!!, key) }
        originalIntentFingerprint = intentFingerprint(rule.activity.intent)

        cleanupRequired = true
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                worldId = database.encyclopediaDao().upsert(EncyclopediaEntity(
                    name = worldName,
                    description = "本次完整流程专用世界",
                    worldPrompt = worldPrompt,
                    gameplayMode = "自由剧情",
                ))
                characterId = database.characterDao().upsert(CharacterEntity(
                    name = characterName,
                    personaPrompt = persona,
                    boundEncyclopediaId = worldId,
                ))
            }
        }
        require(narratorMode in setOf("off", "success", "leave"))
        server = LocalSseServer(replyMessage, narration, narratorMode != "off").also { it.start() }

        storage.publicApiKey = "configured-session-local-only"
        storage.publicBaseUrl = checkNotNull(server).baseUrl
        storage.publicModel = "configured-session-local-model"
        storage.defaultNarratorEnabled = false
        storage.defaultChoiceGenerationEnabled = false

        // MainActivity starts behind the product splash overlay.
        pressBack()
        waitForText("故事库")
    }

    @Test
    fun configuredWorldAndCharacterCreateReopenAndSendCapture() {
        try { configuredLoop() }
        catch (failure: Throwable) {
            val run = args.getString("captureRun") ?: "configured-session-loop-20261006"
            require(run.matches(Regex("[A-Za-z0-9._-]+")))
            java.io.File(targetContext.getExternalFilesDir(null), run).apply { mkdirs() }
                .resolve("failure.txt").writeText(failure.stackTraceToString())
            java.io.File(targetContext.getExternalFilesDir(null), run).resolve("request-shapes.txt").writeText(
                server?.requests?.mapIndexed { index, body ->
                    val root = com.google.gson.JsonParser.parseString(body).asJsonObject
                    "$index: " + root.getAsJsonArray("messages").joinToString { item ->
                        val message = item.asJsonObject
                        val text = message.get("content").asString
                        "role=${message.get("role").asString},len=${text.length},narrator=${text.contains("负责场景描述和剧情推进")}," +
                            "author=${text.contains("长篇小说作者")},persona=${text.contains(persona)},world=${text.contains(worldPrompt)}"
                    }
                }?.joinToString("\n").orEmpty(),
            )
            freezeFailure()
            throw failure
        }
    }

    private fun configuredLoop() {
        onNodeIfPresent("对话")?.performClick()
        waitForText("故事库")
        onNodeIfPresent("新建对话")?.performClick()
        waitForText("选择世界（可选）")

        rule.onAllNodesWithText("选择世界（可选）").onFirst().performClick()
        waitForContentDescription("搜索世界")
        rule.onAllNodesWithContentDescription("搜索世界").onFirst().performClick()
        rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("model-picker-search")), useUnmergedTree = true)
            .onFirst().performTextInput(worldName)
        waitForText(worldName)
        waitForPickerOption(worldName)
        rule.onAllNodesWithText(worldName).filter(hasClickAction() and !hasSetTextAction())
            .onFirst().performScrollTo().assertIsDisplayed().performClick()

        waitForText("参与角色（可选）")
        rule.onAllNodesWithText("参与角色（可选）").onFirst().performClick()
        waitForText(characterName)
        waitForPickerOption(characterName)
        rule.onAllNodesWithText(characterName).filter(hasClickAction() and !hasSetTextAction())
            .onFirst().performScrollTo().assertIsDisplayed().performClick()
        rule.onAllNodesWithText("完成").onFirst().performClick()

        // Scope to the labelled field; the story-library search still exists behind the sheet.
        rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasAnyChild(hasText("标题"))), useUnmergedTree = true).onFirst()
            .performTextReplacement(sessionTitle)
        rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasAnyChild(hasText("标题"))), useUnmergedTree = true).onFirst()
            .assertTextContains(sessionTitle)
        hideKeyboardIfVisible()
        val createLabel = if (textExists("创建并开始")) "创建并开始" else "开始对话"
        rule.onAllNodesWithText(createLabel).onFirst().performClick()

        rule.waitUntil(15_000) {
            sessionId = runBlocking(Dispatchers.IO) {
                database.sessionDao().search(sessionTitle).firstOrNull()?.id ?: 0L
            }
            sessionId > 0L
        }
        val persisted = runBlocking(Dispatchers.IO) {
            val world = database.sessionWorldDao().getBySession(sessionId)
            val participants = database.participantDao().getBySession(sessionId)
            Triple(world, participants, database.sessionDao().getById(sessionId))
        }
        assertEquals(worldId, persisted.first?.encyclopediaId)
        assertEquals(worldPrompt, persisted.first?.worldPrompt)
        assertEquals(listOf(characterId), persisted.second.map { it.characterId })
        assertEquals(sessionTitle, persisted.third?.title)
        waitForContentDescription("消息输入")
        draftPreferences = targetContext.getSharedPreferences("chat_drafts_v1", 0)
        draftPreferenceSnapshot = chatDraftKeys(sessionId).associateWith { key -> preferenceState(draftPreferences!!, key) }

        // Leave through the normal navigation path, then reopen the same card.
        pressBack()
        waitForText(sessionTitle)
        if (narratorMode != "off") {
            // Only this UUID-owned session is prepared to reach the production threshold.
            runBlocking(Dispatchers.IO) {
                database.withTransaction {
                    val world = checkNotNull(database.sessionWorldDao().getBySession(sessionId))
                    database.sessionWorldDao().upsert(world.copy(narratorEnabled = true, autoSedimentEnabled = false))
                    repeat(2) { index ->
                        database.messageDao().insert(MessageEntity(sessionId = sessionId, content = "SEED_${index}_$suffix"))
                    }
                }
            }
        }
        rule.onAllNodesWithText(sessionTitle).onFirst().performClick()
        waitForContentDescription("消息输入")
        rule.onAllNodesWithContentDescription("消息输入").onFirst().assertIsDisplayed()

        rule.onAllNodesWithContentDescription("消息输入").onFirst().performTextInput(userMessage)
        hideKeyboardIfVisible()
        rule.onAllNodesWithContentDescription("发送").onFirst().performClick()
        rule.waitUntil(15_000) {
            runBlocking(Dispatchers.IO) {
                database.messageDao().getNextStoryContextBatch(sessionId, "main", 0L, 80)
                    .any { it.content == userMessage }
            }
        }
        rule.waitUntil(15_000) { checkNotNull(server).requestBody.get() != null }
        val request = checkNotNull(server).requestBody.get() ?: error("local SSE server did not receive a request")
        assertTrue("request must carry the selected world prompt", request.contains(worldPrompt))
        assertTrue("request must carry the selected character persona", request.contains(persona))
        if (narratorMode != "off") {
            checkNotNull(server).releaseReply.countDown()
            val deadline = SystemClock.uptimeMillis() + 15_000
            var pending: Job? = null
            while (pending == null && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    pending = pendingNarratorJob()
                    if (pending?.isActive == true) pendingObservedAt = SystemClock.uptimeMillis()
                }
                if (pending?.isActive != true) { pending = null; SystemClock.sleep(5) }
            }
            check(pending != null) { "450ms production pending narrator window was not observed" }
            if (narratorMode == "leave") {
                leaveDispatchedAt = SystemClock.uptimeMillis()
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                waitForText("故事库")
                check(pending!!.isCancelled) { "dispose did not cancel the observed pending owner" }
                assertTrue("leave must be dispatched within the pending window", leaveDispatchedAt - pendingObservedAt < 450)
                SystemClock.sleep(900)
                assertEquals("leaving must not send narration", 0, checkNotNull(server).narratorRequests.size)
                rule.onAllNodesWithText(sessionTitle).onFirst().performClick()
                waitForContentDescription("消息输入")
                SystemClock.sleep(600)
                assertEquals("reopening must not revive the old pending request", 0, checkNotNull(server).narratorRequests.size)
            } else {
                rule.waitUntil(15_000) { checkNotNull(server).narratorRequests.size == 1 }
                val narratorRequest = com.google.gson.JsonParser.parseString(checkNotNull(server).narratorRequests.single()).asJsonObject
                assertTrue("second request must contain the narrator system prompt",
                    narratorRequest.getAsJsonArray("messages").any {
                        val message = it.asJsonObject
                        message.get("role").asString == "system" &&
                            message.get("content").asString.contains("负责场景描述和剧情推进")
                    })
                rule.waitUntil(15_000) {
                    runBlocking(Dispatchers.IO) {
                        database.messageDao().getNextStoryContextBatch(sessionId, "main", 0L, 80)
                            .any { it.speakerType == "narrator" && it.content == "<NARRATION>$narration</NARRATION>" }
                    }
                }
            }
        }
        rule.waitUntil(15_000) {
            runBlocking(Dispatchers.IO) {
                database.messageDao().getNextStoryContextBatch(sessionId, "main", 0L, 80)
                    .any { it.content == replyMessage }
            }
        }
        waitForText(replyMessage)
        rule.onAllNodesWithText(replyMessage).onFirst().performScrollTo().assertIsDisplayed()
        hideKeyboardIfVisible()

        val captured = args.getString("captureRun") ?: "configured-session-loop-20261006"
        require(captured.matches(Regex("[A-Za-z0-9._-]+")))
        val output = java.io.File(targetContext.getExternalFilesDir(null), captured).apply { mkdirs() }
        java.io.File(output, "configured-session-loop.txt").writeText(
            "sessionId=$sessionId\nworldId=$worldId\ncharacterId=$characterId\nrequestContainsWorld=true\nrequestContainsPersona=true\n" +
                "narratorMode=$narratorMode\nrequestCount=${checkNotNull(server).requests.size}\nnarratorRequestCount=${checkNotNull(server).narratorRequests.size}\npendingObservedAt=$pendingObservedAt\nleaveDispatchedAt=$leaveDispatchedAt\n",
        )
        java.io.File(output, "configured-session-loop.png").outputStream().use { stream ->
            awaitCommittedFrame()
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        // Restore the route used after splash so the next serial capture starts cleanly.
        pressBack()
        waitForText("故事库")
        rule.onAllNodesWithText("故事库").onFirst().assertIsDisplayed()
    }

    @Test
    fun fastCompletedRetainedJobsStopForegroundService() {
        val ownerId = -SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            RetainedChatSessions.stores.acquire(ownerId) { store ->
                object : androidx.lifecycle.ViewModel() {}.also { store.put("fast-completion-probe", it) }
            }
            // All three requests finish before the main loop can deliver service onStartCommand.
            repeat(3) {
                val job = Job()
                RetainedChatSessions.retainGeneration(ownerId, job, targetContext)
                job.complete()
            }
            RetainedChatSessions.stores.release(ownerId)
            check(ownerId !in RetainedChatSessions.running.value)
        }
        instrumentation.waitForIdleSync()
        rule.waitUntil(5_000) {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys activity services com.mojing.app")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
                !it.readText().contains("com.mojing.app/.service.GenerateService")
            }
        }
        waitForText("故事库")
        check(!rule.activity.isDestroyed) { "App must survive immediate service completion" }
    }

    @Test
    fun novelSelectionsReopenAndRemoveOnlyUnavailableCharacter() {
        val preferences = targetContext.getSharedPreferences("story_opening_input_draft_v1", 0)
        val originalInput = preferenceState(preferences, "input")
        val inputSnapshotFile = java.io.File(targetContext.cacheDir, "novel-input-before-$suffix.json")
        inputSnapshotFile.writeText(com.google.gson.Gson().toJson(originalInput))
        val store = com.mojing.app.data.StoryOpeningInputDraftStore(targetContext)
        val receipt = runBlocking(Dispatchers.IO) {
            database.configDao().get(com.mojing.app.domain.story.StoryOpeningDraftCodec.KEY)
        }
        assumeTrue("Active generation is protected", store.loadGeneration() == null)
        assumeTrue("Pending or saved story is protected", receipt == null)
        try {
            runBlocking(Dispatchers.IO) { store.commit(com.mojing.app.data.StoryOpeningInputDraft.EMPTY) }
            rule.onAllNodesWithText("创作").onFirst().performClick()
            waitForText("小说创作")
            rule.onAllNodesWithText("小说创作").onFirst().performClick()
            waitForText("故事背景 *")
            rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasAnyChild(hasText("故事背景 *"))), useUnmergedTree = true)
                .onFirst().performTextReplacement(userMessage)
            hideKeyboardIfVisible()
            rule.onAllNodesWithText("世界").onFirst().performScrollTo().performClick()
            waitForContentDescription("搜索世界")
            rule.onAllNodesWithContentDescription("搜索世界").onFirst().performClick()
            rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("model-picker-search")), useUnmergedTree = true)
                .onFirst().performTextInput(worldName)
            waitForPickerOption(worldName)
            rule.onAllNodesWithText(worldName).filter(hasClickAction() and !hasSetTextAction()).onFirst()
                .performScrollTo().performClick()
            rule.onAllNodesWithText("参与角色").onFirst().performScrollTo().performClick()
            waitForPickerOption(characterName)
            rule.onAllNodesWithText(characterName).filter(hasClickAction() and !hasSetTextAction()).onFirst()
                .performScrollTo().performClick()
            rule.onAllNodesWithText("完成").onFirst().performClick()
            rule.waitUntil(5_000) {
                runBlocking(Dispatchers.IO) { store.load() }?.let {
                    it.premise == userMessage && it.encyclopediaId == worldId && it.characterIds == setOf(characterId)
                } == true
            }
            hideKeyboardIfVisible()
            rule.onAllNodesWithContentDescription("返回创作中心").onFirst().performClick()
            waitForText("小说创作")
            rule.onAllNodesWithText("小说创作").onFirst().performClick()
            waitForText(characterName)
            waitForText(worldName)
            rule.activityRule.scenario.recreate()
            waitForText(characterName)
            waitForText(worldName)
            rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasAnyChild(hasText("故事背景 *"))), useUnmergedTree = true)
                .onFirst().assertTextContains(userMessage)
            rule.onAllNodesWithContentDescription("返回创作中心").onFirst().performClick()
            waitForText("小说创作")
            // Delete only the UUID fixture row to exercise stale restored IDs.
            runBlocking(Dispatchers.IO) { database.characterDao().delete(characterId) }
            rule.onAllNodesWithText("小说创作").onFirst().performClick()
            waitForText("移除失效选择")
            check(runBlocking(Dispatchers.IO) { store.load() }!!.characterIds == setOf(characterId))
            rule.onAllNodesWithText("移除失效选择").onFirst().performScrollTo().performClick()
            rule.waitUntil(5_000) {
                runBlocking(Dispatchers.IO) { store.load() }?.let {
                    it.premise == userMessage && it.encyclopediaId == worldId && it.characterIds.isEmpty()
                } == true
            }
            waitForText(worldName)
            assertEquals("editing/restoring must not generate", 0, checkNotNull(server).requests.size)
            val run = args.getString("captureRun") ?: "novel-selection-recovery"
            require(run.matches(Regex("[A-Za-z0-9._-]+")))
            val output = java.io.File(targetContext.getExternalFilesDir(null), run).apply { mkdirs() }
            output.resolve("novel-selection-recovery.txt").writeText(
                "uiSelected=true\nreopenPreserved=true\nactivityRecreated=true\nstaleIdPreservedBeforeRemoval=true\n" +
                    "removedOnlyUnavailableCharacter=true\npremiseAndWorldPreserved=true\nhttpRequests=0\n",
            )
            output.resolve("novel-selection-recovery.png").outputStream().use {
                awaitCommittedFrame()
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            rule.onAllNodesWithContentDescription("返回创作中心").onFirst().performClick()
            rule.waitUntil(5_000) {
                rule.onAllNodesWithContentDescription("返回创作中心").fetchSemanticsNodes().isEmpty()
            }
        } catch (failure: Throwable) {
            freezeFailure()
            throw failure
        } finally {
            // Dispose the last screen and its ON_STOP draft writer before restoring the snapshot.
            if (rule.onAllNodesWithContentDescription("返回创作中心").fetchSemanticsNodes().isNotEmpty()) {
                rule.onAllNodesWithContentDescription("返回创作中心").onFirst().performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithContentDescription("返回创作中心").fetchSemanticsNodes().isEmpty()
                }
            }
            rule.waitForIdle()
            restorePreference(preferences, "input", originalInput)
            check(preferenceState(preferences, "input") == originalInput) { "novel input restore mismatch" }
            check(runBlocking(Dispatchers.IO) {
                database.configDao().get(com.mojing.app.domain.story.StoryOpeningDraftCodec.KEY)
            } == receipt) { "story receipt changed during selection recovery" }
        }
    }

    // Read-only instrumentation probe of the actual screen-owned ViewModel, never a replacement owner.
    private fun pendingNarratorJob(): Job? {
        val stores = RetainedChatSessions.stores
        val entries = stores.javaClass.getDeclaredField("entries").apply { isAccessible = true }.get(stores) as Map<*, *>
        val entry = entries[sessionId] ?: return null
        val model = entry.javaClass.getDeclaredField("model").apply { isAccessible = true }.get(entry) as ChatViewModel
        return model.javaClass.getDeclaredField("autoNarratorJob").apply { isAccessible = true }.get(model) as? Job
    }

    @After
    fun tearDown() {
        if (!cleanupRequired) return
        // A failed title assertion can still follow a successful UI creation.
        // Resolve only sessions linked to this newly allocated world and character.
        if (sessionId <= 0L && worldId > 0L && characterId > 0L) {
            runBlocking(Dispatchers.IO) {
                database.openHelper.readableDatabase.query(
                    "SELECT DISTINCT sw.sessionId FROM session_worlds sw JOIN session_participants sp ON sp.sessionId = sw.sessionId WHERE sw.encyclopediaId = ? AND sp.characterId = ? LIMIT 2",
                    arrayOf(worldId, characterId),
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        sessionId = cursor.getLong(0)
                        check(!cursor.moveToNext()) { "multiple owned sessions found; fixture retained" }
                    }
                }
            }
        }
        var failure: Throwable? = null
        var ownerSettled = sessionId <= 0L
        if (!ownerSettled) {
            try {
                instrumentation.runOnMainSync {
                    com.mojing.app.ui.chat.RetainedChatSessions.stores.stop(sessionId)
                }
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (sessionId in com.mojing.app.ui.chat.RetainedChatSessions.running.value &&
                    SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(20)
                }
                ownerSettled = sessionId !in com.mojing.app.ui.chat.RetainedChatSessions.running.value
                check(ownerSettled) { "owned generation did not settle; fixture data retained" }
            } catch (error: Throwable) {
                failure = error
            }
        }
        try {
            server?.close()
        } catch (error: Throwable) {
            failure = failure ?: error
        }
        try {
            restorePreferences(securePreferences ?: error("secure preferences were not captured"), securePreferenceSnapshot)
            check(securePreferenceSnapshot.all { (key, state) -> preferenceState(checkNotNull(securePreferences), key) == state }) {
                "secure preference presence/value restore mismatch"
            }
            check(intentFingerprint(rule.activity.intent) == originalIntentFingerprint) { "MainActivity intent changed during capture" }
        } catch (error: Throwable) {
            failure = failure ?: error
        }
        if (ownerSettled && failure == null) {
            try {
                runBlocking(Dispatchers.IO) {
                    database.withTransaction {
                        if (sessionId > 0L) {
                            database.openHelper.writableDatabase.execSQL(
                                "DELETE FROM llm_cost_records WHERE sessionId = ?",
                                arrayOf(sessionId),
                            )
                            database.sessionDao().delete(sessionId)
                        }
                        if (characterId > 0L) database.characterDao().delete(characterId)
                        if (worldId > 0L) database.encyclopediaDao().delete(worldId)
                    }
                }
                draftPreferences?.let { preferences ->
                    draftPreferenceSnapshot.forEach { (key, state) -> restorePreference(preferences, key, state) }
                }
                if (sessionId > 0L && draftPreferences == null) {
                    val preferences = targetContext.getSharedPreferences("chat_drafts_v1", 0)
                    val editor = preferences.edit()
                    chatDraftKeys(sessionId).forEach(editor::remove)
                    check(editor.commit()) { "failed to clear owned draft keys" }
                }
            } catch (error: Throwable) {
                failure = failure ?: error
            }
        }
        if (failure != null) throw AssertionError("configured capture cleanup failed; fixture retained when unsettled", failure)
    }

    private fun onNodeIfPresent(text: String) =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().firstOrNull()?.let { rule.onAllNodesWithText(text).onFirst() }

    private fun textExists(text: String): Boolean = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String) {
        rule.waitUntil(10_000) { textExists(text) }
        rule.waitForIdle()
    }

    private fun waitForPickerOption(text: String) {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(text).filter(hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun freezeFailure() {
        val captured = args.getString("captureRun") ?: "configured-session-loop-20261006"
        require(captured.matches(Regex("[A-Za-z0-9._-]+")))
        val output = java.io.File(targetContext.getExternalFilesDir(null), captured).apply { mkdirs() }
        java.io.File(output, "core-failure-semantics.txt").writeText(rule.onRoot(useUnmergedTree = true).printToString())
        java.io.File(output, "core-failure.png").outputStream().use { stream ->
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    private fun waitForContentDescription(value: String) {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithContentDescription(value).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun awaitCommittedFrame() {
        rule.waitForIdle()
        instrumentation.waitForIdleSync()
        repeat(2) {
            val settled = CountDownLatch(1)
            rule.runOnUiThread {
                val decor = rule.activity.window.decorView
                if (Build.VERSION.SDK_INT >= 29 && decor.isHardwareAccelerated) {
                    decor.viewTreeObserver.registerFrameCommitCallback { settled.countDown() }
                    decor.invalidate()
                } else decor.postOnAnimation { settled.countDown() }
            }
            check(settled.await(3, TimeUnit.SECONDS)) { "application frame was not committed" }
        }
    }

    private fun hideKeyboardIfVisible() {
        fun shown(): Boolean {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys input_method")
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
                it.readText().contains("mInputShown=true")
            }
        }
        if (shown()) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(5_000) { !shown() }
        rule.waitForIdle()
    }

    private fun pressBack() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private fun storagePreferences(storage: Any): SharedPreferences {
        val field = storage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
        return checkNotNull(field.get(storage) as? SharedPreferences) { "secure storage is not initialized" }
    }

    private fun preferenceState(preferences: SharedPreferences, key: String): PreferenceState =
        PreferenceState(preferences.contains(key), preferences.all[key])

    private fun restorePreferences(preferences: SharedPreferences, snapshot: Map<String, PreferenceState>) {
        snapshot.forEach { (key, state) -> restorePreference(preferences, key, state) }
    }

    private fun restorePreference(preferences: SharedPreferences, key: String, state: PreferenceState) {
        val editor = preferences.edit()
        if (!state.present) {
            check(editor.remove(key).commit()) { "failed to restore preference presence for $key" }
            return
        }
        when (val value = state.value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> editor.remove(key)
            else -> error("unsupported preference type for $key: ${value::class.java.name}")
        }
        check(editor.commit()) { "failed to restore preference $key" }
    }

    private fun intentFingerprint(intent: Intent?): String = intent?.toUri(Intent.URI_INTENT_SCHEME).orEmpty()

    private companion object {
        val SECURE_KEYS = setOf("public_api_key", "public_base_url", "public_model", "theme_mode", "ui_font_scale", "default_narrator_enabled", "default_choice_generation_enabled")
        fun chatDraftKeys(sessionId: Long): Set<String> = setOf(
            "session_$sessionId",
            "reply_recovery_v1_session_$sessionId",
            "chapter_input_v1_${sessionId}_main",
        )
    }

    private class LocalSseServer(private val reply: String, private val narration: String, private val gated: Boolean) : AutoCloseable {
        private companion object {
            const val MAX_HEADER_BYTES = 64 * 1024
            const val MAX_BODY_BYTES = 16 * 1024 * 1024
        }
        private val socket = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:${socket.localPort}/v1"
        val requestBody = AtomicReference<String?>()
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val narratorRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val releaseReply = CountDownLatch(if (gated) 1 else 0)
        private val stopping = AtomicBoolean(false)
        private val workerError = AtomicReference<Throwable?>()
        private val stopped = CountDownLatch(1)
        private var worker: Thread? = null

        fun start() {
            worker = Thread {
                try {
                    while (!stopping.get()) { socket.accept().use { client ->
                        client.soTimeout = 15_000
                        val input = DataInputStream(BufferedInputStream(client.getInputStream()))
                        val header = readHeaders(input)
                        val length = header.lineSequence().firstOrNull { it.startsWith("Content-Length:", true) }
                            ?.substringAfter(':')?.trim()?.toIntOrNull()
                            ?: error("request is missing a valid Content-Length header")
                        require(length in 0..MAX_BODY_BYTES) { "request body exceeds 16 MiB bound: $length" }
                        val body = ByteArray(length)
                        readExactly(input, body)
                        val request = body.toString(Charsets.UTF_8)
                        check(requests.size < 16) { "fixture request bound exceeded" }
                        requests.add(request)
                        val root = com.google.gson.JsonParser.parseString(request).asJsonObject
                        val systemPrompt = root.getAsJsonArray("messages").filter { it.asJsonObject.get("role").asString == "system" }
                            .joinToString("\n") { it.asJsonObject.get("content").asString }
                        val isNarrator = systemPrompt.contains("输出格式：<NARRATION>旁白内容</NARRATION>")
                        val streaming = root.get("stream")?.asBoolean == true
                        if (isNarrator) narratorRequests.add(request)
                        if (streaming && !isNarrator) {
                            requestBody.compareAndSet(null, request)
                            check(releaseReply.await(15, TimeUnit.SECONDS)) { "fixture response gate timed out" }
                        }
                        val gson = com.google.gson.Gson()
                        val content = when {
                            isNarrator -> "<NARRATION>$narration</NARRATION>"
                            streaming -> reply
                            systemPrompt.contains("剧情事件提取") -> "[]"
                            else -> gson.toJson(com.mojing.app.domain.engine.UniversalContextMemory())
                        }
                        val writer = client.getOutputStream().bufferedWriter()
                        if (streaming) {
                            val payload = "{\"choices\":[{\"delta\":{\"content\":${gson.toJson(content)}}}]}"
                            writer.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\ndata: $payload\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                        } else {
                            val payload = "{\"choices\":[{\"message\":{\"content\":${gson.toJson(content)}},\"finish_reason\":\"stop\"}]}"
                            writer.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n$payload")
                        }
                        writer.flush()
                    } }
                } catch (error: Throwable) {
                    if (!stopping.get()) workerError.set(error)
                } finally {
                    stopped.countDown()
                }
            }.apply { isDaemon = true; name = "configured-session-loop-sse"; start() }
        }

        private fun readHeaders(input: InputStream): String {
            val result = StringBuilder()
            while (result.length < MAX_HEADER_BYTES) {
                val value = input.read()
                check(value >= 0) { "local SSE client closed before headers" }
                result.append(value.toChar())
                if (result.endsWith("\r\n\r\n")) return result.toString()
            }
            error("request headers exceed 64 KiB bound")
        }

        private fun readExactly(input: InputStream, body: ByteArray) {
            var offset = 0
            while (offset < body.size) {
                val count = input.read(body, offset, body.size - offset)
                check(count > 0) { "local SSE client closed before request body completed" }
                offset += count
            }
        }

        override fun close() {
            stopping.set(true)
            socket.close()
            check(stopped.await(2, TimeUnit.SECONDS)) { "local SSE worker did not settle after close" }
            worker?.join(2_000)
            check(!worker?.isAlive.orFalse()) { "local SSE worker is still alive" }
            workerError.get()?.let { throw AssertionError("local SSE worker failed", it) }
        }

        private fun Boolean?.orFalse(): Boolean = this ?: false
    }
}

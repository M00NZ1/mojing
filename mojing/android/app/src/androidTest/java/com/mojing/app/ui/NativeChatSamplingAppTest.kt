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
import java.security.MessageDigest
import java.util.UUID

/** MainActivity loopback chat evidence for persisted sampling changes between turns. */
class NativeChatSamplingAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val run get() = args.getString("nativeChatSamplingRun").orEmpty().also { UUID.fromString(it) }
    private val base get() = args.getString("localBase").orEmpty().also { check(it.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/anthropic\\.com/v1"))) }
    private val title get() = "聊天采样-$run"
    private val characterName get() = "聊天采样角色-$run"
    private val marker get() = "NATIVE_SAMPLING_$run"
    private val journalFile get() = File(context.filesDir, "native-chat-sampling-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "native-chat-sampling-$run").apply { mkdirs() }
    private val preferences: SharedPreferences get() = rule.activity.secureStorage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(rule.activity.secureStorage) as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "speaker_turn_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")

    private fun guard(requireIdle: Boolean = true) { assumeTrue(args.getString("nativeChatSamplingCapture") == "true"); assumeTrue(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic")); if (requireIdle) check(RetainedChatSessions.running.value.isEmpty()); check(StoryOpeningInputDraftStore(context).loadGeneration() == null) }
    private fun snapshot(p: SharedPreferences) = JsonObject().apply { p.all.forEach { (k, v) -> add(k, JsonObject().apply { when (v) { is String -> { addProperty("type", "string"); addProperty("value", v) }; is Boolean -> { addProperty("type", "boolean"); addProperty("value", v) }; else -> error("unexpected preference type") } }) } }
    private fun assertSnapshot(p: SharedPreferences, expected: JsonObject) { check(p.all.keys == expected.keySet()); expected.entrySet().forEach { (k, v) -> val item = v.asJsonObject; if (item.get("type").asString == "string") check(p.getString(k, null) == item.get("value").asString) else check(p.getBoolean(k, false) == item.get("value").asBoolean) } }
    private fun branches() = runBlocking(Dispatchers.IO) { JsonObject().apply { com.mojing.app.data.prefs.UiPreferencesRepository(context).lastChatBranches.first().forEach { (id, branch) -> addProperty(id.toString(), branch) } } }
    private fun persist(j: JsonObject) { val pending = File(context.filesDir, "native-chat-sampling-$run.pending"); FileOutputStream(pending).use { it.write(j.toString().toByteArray()); it.fd.sync() }; check(pending.renameTo(journalFile)) }
    private fun journal() = JsonParser.parseString(journalFile.readText()).asJsonObject.also { check(it.get("run").asString == run) }
    private fun digest(exclude: Set<Long> = emptySet()) = runBlocking(Dispatchers.IO) { val d = MessageDigest.getInstance("SHA-256"); var c: CharacterEntity? = null; do { val page = db.characterDao().getExportPage(c?.let { if (it.pinnedAt > 0) 0 else 1 }, c?.pinnedAt, c?.favorite, c?.createdAt, c?.id, 128); page.filter { it.id !in exclude }.forEach { d.update(it.toString().toByteArray()); d.update(0.toByte()) }; c = page.lastOrNull() } while (page.size == 128); d.digest().joinToString("") { "%02x".format(it) } }
    private fun model(id: Long): ChatViewModel { val stores = RetainedChatSessions.stores; val entries = stores.javaClass.getDeclaredField("entries").apply { isAccessible = true }.get(stores) as Map<*, *>; val entry = checkNotNull(entries[id]); return entry.javaClass.getDeclaredField("model").apply { isAccessible = true }.get(entry) as ChatViewModel }
    private fun waitText(value: String) { rule.waitUntil(10_000) { rule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() } }
    private fun hideKeyboard() = rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0) }
    private fun openStory(j: JsonObject) { rule.waitUntil(10_000) { rule.onAllNodesWithText("M O J I N G").fetchSemanticsNodes().isEmpty() }; rule.onNode(hasText("对话") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, Role.Tab)).performClick(); waitText("故事库"); rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(j.get("title").asString); hideKeyboard(); rule.waitUntil(10_000) { rule.onAllNodesWithText(j.get("title").asString).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }; rule.onAllNodesWithText(j.get("title").asString).filter(!hasSetTextAction()).onFirst().performClick(); rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }; rule.waitUntil(10_000) { model(j.get("session").asLong).state.value.isReady } }
    private fun send(j: JsonObject, text: String, replyMarker: String) { val id = j.get("session").asLong; rule.onAllNodesWithContentDescription("消息输入").onFirst().performTextInput(text); rule.waitUntil(5_000) { model(id).state.value.inputText == text }; inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); rule.onAllNodesWithContentDescription("发送").onFirst().performClick(); rule.waitUntil(25_000) { runBlocking(Dispatchers.IO) { db.messageDao().getBranchMessages(id, "main").any { it.speakerType == "character" && it.content.contains(replyMarker) } } }; rule.waitUntil(10_000) { !model(id).state.value.isGenerating && id !in RetainedChatSessions.running.value } }
    private fun openEditor(j: JsonObject) { inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); waitText("故事库"); rule.onNode(hasText("创作") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, Role.Tab)).performClick(); waitText("角色"); rule.onNodeWithText("角色").performClick(); rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }; rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(characterName); hideKeyboard(); rule.waitUntil(10_000) { rule.onAllNodesWithText(characterName).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }; rule.onAllNodesWithText(characterName).filter(!hasSetTextAction()).onLast().performClick(); waitText("角色详情"); rule.onNodeWithContentDescription("编辑角色").performClick(); waitText("编辑角色") }
    private fun setField(current: String, value: String) { rule.onNodeWithText(current, substring = false).performScrollTo().performTextReplacement(value); hideKeyboard() }
    private fun screenshot(label: String) { rule.waitForIdle(); inst.waitForIdleSync(); repeat(2) { val frame = java.util.concurrent.CountDownLatch(1); rule.runOnUiThread { rule.activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }; rule.activity.window.decorView.invalidate() }; check(frame.await(3, java.util.concurrent.TimeUnit.SECONDS)) }; val b = checkNotNull(inst.uiAutomation.takeScreenshot()); try { FileOutputStream(output.resolve("$label.png")).use { check(b.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { b.recycle() } }
    private fun findOwnedSessionId() = runBlocking(Dispatchers.IO) { db.sessionDao().getByCreationRequestId(run)?.id }
    private fun findOwnedCharacterId() = runBlocking(Dispatchers.IO) { db.characterDao().getLibraryNamePage(null, null, null, null, 40, characterName).singleOrNull { it.name == characterName && db.characterDao().getById(it.id)?.personaPrompt?.contains(marker) == true }?.id }

    @Test fun nativeSamplingSendEditAndSendAgain() {
        guard(); check(!journalFile.exists()); val drafts = draftNames.associateWith { snapshot(context.getSharedPreferences(it, 0)) }; val original = JsonObject().apply { configKeys.forEach { k -> preferences.getString(k, null)?.let { addProperty(k, it) } ?: add(k, com.google.gson.JsonNull.INSTANCE) } }; val j = JsonObject().apply { addProperty("run", run); add("originalConfig", original); add("drafts", JsonObject().apply { drafts.forEach { (k, v) -> add(k, v) } }); add("branches", branches()); addProperty("digest", digest()); addProperty("title", title); addProperty("phase", "snapshot") }; persist(j)
        try {
            val ids = runBlocking(Dispatchers.IO) { db.withTransaction { val cid = db.characterDao().upsert(CharacterEntity(name = characterName, modelName = "claude-opus-4-7", personaPrompt = "角色-$marker", temperature = 0.0f, topP = 0.37f, frequencyPenalty = -1.5f, presencePenalty = 1.25f, maxTokens = 4097)); db.characterProfileDao().upsert(CharacterProfileEntity(characterId = cid, sourceFilename = "native-chat-sampling-$run", rawPersonaText = "角色-$marker", characterCardJson = "{\"marker\":\"$marker\"}")); val sid = db.sessionDao().insert(SessionEntity(title = title, creationRequestId = run)); db.participantDao().upsert(SessionParticipantEntity(sessionId = sid, characterId = cid, forceNext = true)); db.sessionWorldDao().upsert(SessionWorldEntity(sessionId = sid, narratorEnabled = false, choiceGenerationEnabled = false, autoSedimentEnabled = false, gameplayMode = "自由剧情", sessionLlmApiKey = "local-chat-only", sessionLlmBaseUrl = base)); db.messageDao().insert(MessageEntity(sessionId = sid, speakerType = "user", content = "SEED_$marker")); cid to sid } }; j.addProperty("character", ids.first); j.addProperty("session", ids.second); persist(j); check(preferences.edit().putString("public_api_key", "local-chat-only").putString("public_base_url", base).putString("public_model", "claude-opus-4-7").putString("speaker_turn_mode", "auto").commit()); j.addProperty("phase", "configured"); persist(j)
            openStory(j); send(j, "SEND_1_$marker", "SAMPLING_REPLY_1_$marker"); j.addProperty("phase", "first-reply"); persist(j); screenshot("first-reply")
            openEditor(j); rule.onNodeWithText("导出与高级设置").performScrollTo().performClick(); rule.onNodeWithText("Top P 与两项惩罚用于 OpenAI 兼容线路；原生 Anthropic 线路暂不发送这三项。温度是否生效取决于模型，Claude 4.7 及后续模型和 Mythos Preview 使用模型默认采样。").performScrollTo().assertExists(); screenshot("sampling-protocol-note"); setField("0.0", "0.6"); setField("0.37", "0.8"); setField("-1.5", "0.0"); setField("1.25", "-0.75"); setField("4097", "4098"); screenshot("editor-second-sampling"); rule.onNodeWithContentDescription("返回").performClick(); waitText("保存角色修改？"); rule.onNodeWithText("保存并离开").performClick(); waitText("角色详情"); backToLibrary(); j.addProperty("phase", "sampling-edited"); persist(j)
            openStory(j); send(j, "SEND_2_$marker", "SAMPLING_REPLY_2_$marker"); val entity = runBlocking(Dispatchers.IO) { checkNotNull(db.characterDao().getById(j.get("character").asLong)) }; check(entity.temperature == 0.6f && entity.topP == 0.8f && entity.frequencyPenalty == 0.0f && entity.presencePenalty == -0.75f && entity.maxTokens == 4098); j.addProperty("phase", "verified"); persist(j); screenshot("second-reply"); output.resolve("verified.txt").writeText("twoRepliesPersisted=true\nsecondSamplingSaved=true\ntwoNativeStreamRequestsExpected=true\nloopbackOnly=true\n")
        } catch (t: Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString()); runCatching { screenshot("failure") }; throw t }
    }

    private fun backToLibrary() { rule.onNodeWithContentDescription("返回角色").performClick(); waitText("角色"); rule.onNode(hasText("对话") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, Role.Tab)).performClick(); waitText("故事库") }

    @Test fun rollbackOnlyOwnedNativeSampling() { guard(requireIdle = false); val j = journal(); val sid = j.get("session")?.asLong?.takeIf { it > 0 } ?: findOwnedSessionId() ?: 0L; val cid = j.get("character")?.asLong?.takeIf { it > 0 } ?: findOwnedCharacterId() ?: 0L; check(RetainedChatSessions.running.value.all { it == sid }); if (sid > 0) { if (sid in RetainedChatSessions.running.value) inst.runOnMainSync { RetainedChatSessions.stores.stop(sid) }; rule.waitUntil(10_000) { sid !in RetainedChatSessions.running.value }; runBlocking(Dispatchers.IO) { db.withTransaction { val owned = db.sessionDao().getById(sid); check(owned == null || owned.creationRequestId == run); db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM session_participants WHERE characterId = ? AND sessionId != ?", arrayOf(cid.toString(), sid.toString())).use { check(it.moveToFirst() && it.getInt(0) == 0) }; db.openHelper.writableDatabase.execSQL("DELETE FROM llm_cost_records WHERE sessionId = ?", arrayOf(sid)); db.sessionDao().delete(sid) } } }; if (cid > 0) runBlocking(Dispatchers.IO) { db.characterDao().getById(cid)?.let { check(it.name == characterName && db.characterDao().getById(it.id)?.personaPrompt?.contains(marker) == true); db.characterDao().delete(cid) } }; val editor = preferences.edit(); j.getAsJsonObject("originalConfig").entrySet().forEach { (k, v) -> if (v.isJsonNull) editor.remove(k) else editor.putString(k, v.asString) }; check(editor.commit()); j.getAsJsonObject("originalConfig").entrySet().forEach { (key, value) -> check(preferences.contains(key) == !value.isJsonNull); if (!value.isJsonNull) check(preferences.getString(key, null) == value.asString) }; if (cid > 0) { val p = context.getSharedPreferences("character_edit_drafts_v1", 0); if (!j.getAsJsonObject("drafts").getAsJsonObject("character_edit_drafts_v1").has("character_$cid")) p.edit().remove("character_$cid").commit() }; if (sid > 0) { val p = context.getSharedPreferences("chat_drafts_v1", 0); p.edit().remove("session_$sid").remove("reply_recovery_v1_session_$sid").remove("chapter_input_v1_${sid}_main").commit(); runBlocking { com.mojing.app.data.prefs.UiPreferencesRepository(context).clearLastChatBranch(sid) } }; draftNames.forEach { n -> assertSnapshot(context.getSharedPreferences(n, 0), j.getAsJsonObject("drafts").getAsJsonObject(n)) }; check(branches() == j.getAsJsonObject("branches")); check(digest() == j.get("digest").asString); j.addProperty("phase", "rolled-back"); persist(j); output.resolve("rollback.txt").writeText("ownedSessionCharacterCostsRemoved=true\nconfigRestored=true\notherDigestPreserved=true\n") }
}

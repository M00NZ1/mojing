package com.mojing.app.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import com.mojing.app.ui.chat.search.SearchViewModel
import com.mojing.app.ui.chat.search.SearchScope
import com.mojing.app.ui.chat.search.SearchState
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
class SearchTailAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("searchTailRun").orEmpty().also { UUID.fromString(it) }
    private val scenario get() = args.getString("scenario").orEmpty()
    private val title get() = "续章入口${run.take(8)}"
    private val marker get() = "PARENT_MEMORY_$run"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "search-tail-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "search-tail-$run").apply { mkdirs() }
    private val preferences get() = rule.activity.secureStorage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(rule.activity.secureStorage) as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun guard(idle: Boolean = true) {
        assumeTrue(args.getString("searchTailCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        if (idle) check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
        check(scenario in listOf("current", "all"))
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "search-tail-$run.pending")
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
            "encyclopedia_entries", "characters", "character_profiles", "world_templates", "timeline_events", "message_attachments").forEach { table ->
            hash.update(table.toByteArray())
            db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { c ->
                while (c.moveToNext()) for (index in 0 until c.columnCount) { hash.update((c.getString(index) ?: "<null>").toByteArray()); hash.update(0.toByte()) }
            }
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun mediaFilesDigest(): String = io {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        db.openHelper.readableDatabase.query("SELECT DISTINCT storagePath FROM message_attachments ORDER BY storagePath").use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0)
                hash.update(path.toByteArray())
                if (!path.contains("://")) {
                    val file = File(path)
                    hash.update(if (file.isFile) file.inputStream().use { input ->
                        val fileHash = java.security.MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(32768)
                        while (true) { val n=input.read(buffer); if(n<0) break; fileHash.update(buffer,0,n) }
                        fileHash.digest()
                    } else "missing".toByteArray())
                }
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


    private fun searchPrefsDigest(): String {
        val values=context.getSharedPreferences("message_search",0).all.toSortedMap().map { (k,v) ->
            k+":"+when(v) { is Set<*> -> v.map { it.toString() }.sorted().joinToString("|"); else -> v.toString() }
        }.joinToString("\n")
        return java.security.MessageDigest.getInstance("SHA-256").digest(values.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    // Read the existing NavBackStackEntry store inherited by SearchScreen's hiltViewModel.
    // No ViewModelProvider is invoked and no test-only search model is created.
    private fun searchModel(): SearchViewModel {
        fun values(store: androidx.lifecycle.ViewModelStore): Collection<*> =
            (store.javaClass.getDeclaredField("map").apply { isAccessible=true }.get(store) as Map<*,*>).values
        val nav=values(rule.activity.viewModelStore).single { it?.javaClass?.name=="androidx.navigation.NavControllerViewModel" }!!
        val stores=nav.javaClass.getDeclaredField("viewModelStores").apply { isAccessible=true }.get(nav) as Map<*,*>
        return stores.values.flatMap { values(it as androidx.lifecycle.ViewModelStore) }.filterIsInstance<SearchViewModel>().single()
    }
    private fun searchState(): SearchState = rule.runOnIdle { searchModel().state.value }
    private fun waitSearch(offset: Int, size: Int, total: Int) {
        rule.waitUntil(20000) {
            val s=searchModel().state.value
            !s.searching && !s.indexing && !s.visibilityIndexing && !s.counting && s.totalMatches==total &&
                s.firstHitOffset==offset && s.hits.size==size
        }
        check(searchState().error==null)
    }
    private fun resultsList()=rule.onNode(hasScrollToNodeAction() and !hasContentDescription("对话正文"))
    private fun page(label: String) {
        resultsList().performScrollToNode(hasText(label))
        rule.onNodeWithText(label).assertIsDisplayed().performClick()
    }
    private fun hideIme() {
        rule.runOnUiThread { rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(rule.activity.window.decorView.windowToken,0); rule.activity.currentFocus?.clearFocus() }
        rule.waitUntil(10000) { rule.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())!=true }
    }

    @Test fun searchTailRequestAndPersistence() {
        guard(); check(!journalFile.exists())
        val base = args.getString("localBase").orEmpty(); check(base.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/v1")))
        val j = JsonObject().apply {
            addProperty("run", run); addProperty("scenario", scenario); addProperty("phase", "snapshot")
            add("originalConfig", JsonObject().apply { configKeys.forEach { k -> addProperty(k, if (preferences.contains(k)) preferences.getString(k, null) else null) } })
            add("drafts", JsonObject().apply { draftNames.forEach { n -> add(n, snapshot(context.getSharedPreferences(n, 0))) } })
            add("branches", branches()); addProperty("digest", digest()); addProperty("mediaFilesDigest",mediaFilesDigest()); addProperty("searchPrefsDigest",searchPrefsDigest())
        }; persist(j)
        try {
            val cid = io { db.characterDao().upsert(CharacterEntity(name = "子线角色-$run", personaPrompt = "SYNTHETIC_$run")) }
            j.addProperty("character", cid); persist(j)
            val sid = io { db.sessionDao().insert(SessionEntity(title = title, creationRequestId = run)) }
            j.addProperty("session", sid); persist(j)

            val phrase="雨城连续线索"
            fun body(n: Int, child: Boolean=false)="${if(child) "子" else "主"}序${n.toString().padStart(3,'0')} $phrase：她收起信纸，走过旧桥。\n完整原文结尾${n}_$run"
            val parentIds=mutableListOf<Long>()
            val child="cache-$run"
            var replacement=0L
            val childIds=mutableListOf<Long>()
            io { db.withTransaction {
                db.participantDao().upsert(SessionParticipantEntity(sessionId=sid,characterId=cid))
                db.sessionWorldDao().upsert(SessionWorldEntity(sessionId=sid,narratorEnabled=false,gameplayMode="角色扮演",autoSedimentEnabled=false))
                repeat(203) { n -> parentIds+=db.messageDao().insert(MessageEntity(sessionId=sid,speakerType="character",characterId=cid,content=body(n+1))) }
                if(scenario=="all") {
                    val source=checkNotNull(db.messageDao().getById(parentIds[89]))
                    replacement=db.sessionBranchDao().insertEditedBranch(SessionBranchEntity(sessionId=sid,branchId=child,label="连续线索子线",sourceMessageId=source.id,parentBranchId="main"),
                        source.copy(id=0,branchId=child,regeneratedFromMessageId=source.id,content=body(90,true)),emptyList())
                    repeat(75) { n -> childIds+=db.messageDao().insert(MessageEntity(sessionId=sid,branchId=child,speakerType="character",characterId=cid,content=body(n+91,true))) }
                }
            } }
            val expected=parentIds.asReversed().map { "main" to it } + if(scenario=="all")
                (childIds.asReversed()+replacement+parentIds.take(89).asReversed()).map { child to it } else emptyList()
            val total=expected.size
            val windows=com.google.gson.JsonArray()
            fun assertWindow(label: String, offset: Int, size: Int) {
                waitSearch(offset,size,total)
                val s=searchState()
                check(s.hits.map { it.branchId to it.message.id }==expected.subList(offset,offset+size)) { "window mismatch $label" }
                check(s.hits.size<=120 && s.hits.distinctBy { it.branchId to it.message.id }.size==s.hits.size)
                check(s.hits.all { it.message.content.isEmpty() })
                check(s.hasNewer==(offset>0)); check(s.hasOlder==(offset+size<total))
                check(model(sid).state.value.currentBranchId=="main")
                windows.add(JsonObject().apply { addProperty("label",label); addProperty("offset",offset); addProperty("size",size); addProperty("exactPairs",true) })
            }
            openStory(sid)

            val draft="搜索尾页未发送草稿-$run"
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).performTextReplacement(draft); hideIme()
            rule.onNodeWithContentDescription("会话菜单").performClick(); waitText("搜索消息")
            rule.onNodeWithText("搜索消息").performClick(); waitText("全部故事线")
            if(scenario=="all") rule.onNodeWithText("全部故事线").performClick()
            rule.onNode(hasSetTextAction()).performTextReplacement(phrase)
            rule.onNode(hasSetTextAction()).performImeAction(); hideIme()
            assertWindow("initial",0,40); screenshot("01-initial")
            val seen=mutableSetOf<Pair<String,Long>>()
            seen+=searchState().hits.map { it.branchId to it.message.id }
            var older=0
            var loaded=40
            while(searchState().hasOlder) {
                page("加载更早结果"); older++
                loaded=minOf(loaded+40,total)
                assertWindow("older-$older",(loaded-120).coerceAtLeast(0),minOf(loaded,120))
                seen+=searchState().hits.map { it.branchId to it.message.id }
            }
            check(loaded==total && seen==expected.toSet())
            check(searchState().hits.last().message.id==parentIds.first())
            check(!searchState().hasOlder)
            resultsList().performScrollToNode(hasText("主序001",substring=true))
            check(rule.onAllNodesWithText("加载更早结果").fetchSemanticsNodes().isEmpty())
            screenshot("02-partial-last-page")
            // Oldest result is also the last item of the inherited child group in ALL scope.
            val target=expected.last()
            val full=io { checkNotNull(db.messageDao().getById(target.second)).content }
            rule.onAllNodesWithText(full.substringBefore('\n'),substring=true).filter(!hasSetTextAction()).onFirst().performClick()
            rule.waitUntil(10000) { searchModel().state.value.let { !it.searching && it.selectedMessageId==target.second && it.selectedBranchId==target.first && it.contextMessages.any { row -> row.id==target.second } } }
            rule.onNodeWithContentDescription("下一个").assertIsNotEnabled()
            rule.onNodeWithText("$total / $total").assertIsDisplayed()
            check(searchState().contextMessages.single { it.id==target.second }.content==full)
            screenshot("03-terminal-context")
            rule.onNodeWithText("结果列表").performClick()
            var newer=0
            while(searchState().hasNewer) {
                val before=searchState().firstHitOffset
                page("加载较新结果"); newer++
                assertWindow("newer-$newer",(before-40).coerceAtLeast(0),120)
            }
            check(searchState().firstHitOffset==0)
            resultsList().performScrollToNode(hasText("主序203",substring=true))
            check(rule.onAllNodesWithText("加载较新结果").fetchSemanticsNodes().isEmpty())
            screenshot("04-newest-edge")
            val first=expected.first()
            val firstText=io { checkNotNull(db.messageDao().getById(first.second)).content }
            rule.onAllNodesWithText(firstText.substringBefore('\n'),substring=true).filter(!hasSetTextAction()).onFirst().performClick()
            rule.waitUntil(10000) { searchModel().state.value.let { !it.searching && it.selectedMessageId==first.second && it.contextMessages.any { row -> row.id==first.second } } }
            rule.onNodeWithContentDescription("上一个").assertIsNotEnabled()
            rule.onNodeWithText("1 / $total").assertIsDisplayed()
            screenshot("05-first-context")
            inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            rule.waitUntil(10000) { searchModel().state.value.selectedMessageId==null }
            inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("消息输入").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft)
            check(model(sid).state.value.currentBranchId=="main")
            screenshot("06-back-draft")
            rule.onNodeWithContentDescription("返回会话主页").performClick(); waitText("故事库")
            rule.onAllNodesWithText(title).filter(!hasSetTextAction()).onFirst().performClick()
            rule.waitUntil(10000) { modelOrNull(sid)?.state?.value?.isReady==true }
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft)
            screenshot("07-reentry-draft")
            output.resolve("metrics.json").writeText(JsonObject().apply { addProperty("scenario",scenario); addProperty("total",total); addProperty("olderUiReads",older); addProperty("newerUiReads",newer); add("windows",windows); addProperty("exhaustivePairs",seen.size); addProperty("partialLastPage",total%40); addProperty("terminalControls",true); addProperty("draftReentry",true); addProperty("realOwner",true) }.toString())
            j.addProperty("phase","verified"); persist(j)
            output.resolve("verified.txt").writeText("realUi=true\nrealSearchOwner=true\nexactOrderedBranchMessagePairs=true\ncacheMax120=true\nexhaustivePairs=true\npartialLastPage=true\nterminalControls=true\ndraftReentry=true\n")
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
        if(sid>0L) {
            val sp=context.getSharedPreferences("message_search",0)
            val edit=sp.edit()
            sp.all.keys.filter { it=="history_$sid" || it.startsWith("history_${sid}_") }.forEach { edit.remove(it) }
            check(edit.commit())
        }
        check(searchPrefsDigest()==j["searchPrefsDigest"].asString)
        draftNames.forEach { n -> assertSnapshot(context.getSharedPreferences(n, 0), j.getAsJsonObject("drafts").getAsJsonObject(n)) }
        check(branches() == j.getAsJsonObject("branches")); check(digest() == j["digest"].asString); check(mediaFilesDigest() == j["mediaFilesDigest"].asString)
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("uuidOnlyRemoved=true\nconfigPresenceValuesExact=true\nfourDraftsExact=true\nbranchPrefsExact=true\nelevenTablesDigestExact=true\noriginalMediaFilesDigestExact=true\nsearchPrefsDigestExact=true\n")
    }
}

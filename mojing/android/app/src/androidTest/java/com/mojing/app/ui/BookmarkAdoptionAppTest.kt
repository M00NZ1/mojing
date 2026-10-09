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
class BookmarkAdoptionAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("bookmarkAdoptionRun").orEmpty().also { UUID.fromString(it) }
    private val scenario get() = args.getString("scenario").orEmpty()
    private val title get() = "回复收藏${run.take(8)}"
    private val marker get() = "PARENT_MEMORY_$run"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "bookmark-adoption-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "bookmark-adoption-$run").apply { mkdirs() }
    private val preferences get() = rule.activity.secureStorage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(rule.activity.secureStorage) as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun guard(idle: Boolean = true) {
        assumeTrue(args.getString("bookmarkAdoptionCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        if (idle) check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
        check(scenario in listOf("current", "all", "child", "childAll"))
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "bookmark-adoption-$run.pending")
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
            "encyclopedia_entries", "characters", "character_profiles", "world_templates", "timeline_events", "message_attachments", "message_bookmarks", "branch_swipe_selections").forEach { table ->
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
    private fun waitText(text: String) = rule.waitUntil(10000) { rule.onAllNodesWithText(text,substring=true).fetchSemanticsNodes().isNotEmpty() }
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

    @Test fun bookmarkAdoptionRequestAndPersistence() {
        guard(); check(!journalFile.exists())
        val j = JsonObject().apply {
            addProperty("run",run); addProperty("scenario",scenario); addProperty("phase","snapshot")
            add("originalConfig",JsonObject().apply { configKeys.forEach { k -> addProperty(k,if(preferences.contains(k)) preferences.getString(k,null) else null) } })
            add("drafts",JsonObject().apply { draftNames.forEach { n -> add(n,snapshot(context.getSharedPreferences(n,0))) } })
            add("branches",branches()); addProperty("digest",digest()); addProperty("mediaFilesDigest",mediaFilesDigest()); addProperty("searchPrefsDigest",searchPrefsDigest())
        }; persist(j)
        try {
            val cid=io { db.characterDao().upsert(CharacterEntity(name="子线角色-$run",personaPrompt="SYNTHETIC_$run")) }
            j.addProperty("character",cid); persist(j)
            val sid=io { db.sessionDao().insert(SessionEntity(title=title,creationRequestId=run)) }
            j.addProperty("session",sid); persist(j)
            val sp=context.getSharedPreferences("message_search",0)
            check(sp.all.keys.none { it=="history_$sid" || it.startsWith("history_${sid}_") })
            j.addProperty("ownedSearchSession",sid); persist(j)
            val oldPhrase="旧约青灯归途"
            val newPhrase="新约银舟黎明"
            val oldBody="$oldPhrase：她把旧信留在桥边。\n旧版完整结尾${run.take(8)}"
            val newBody="$newPhrase：她决定乘舟去往新的城。\n新版完整结尾${run.take(8)}"
            val gid="reply-$run"
            val old=MessageEntity(sessionId=sid,speakerType="character",characterId=cid,swipeGroupId=gid,content=oldBody,structuredContentJson="{\"source\":\"old-$run\"}",createdAt=1000)
            val newer=old.copy(content=newBody,structuredContentJson="{\"source\":\"new-$run\"}",includeInContext=false,createdAt=2000)
            var oldId=0L; var newId=0L
            io { db.withTransaction {
                db.participantDao().upsert(SessionParticipantEntity(sessionId=sid,characterId=cid))
                db.sessionWorldDao().upsert(SessionWorldEntity(sessionId=sid,narratorEnabled=false,gameplayMode="角色扮演",autoSedimentEnabled=false))
                oldId=db.messageDao().insert(old); newId=db.messageDao().insert(newer)
            } }
            val originalOld=io { db.messageDao().getById(oldId) }!!
            val originalNew=io { db.messageDao().getById(newId) }!!
            val child="adoption-$run"
            val branch=if(scenario.startsWith("child")) child else "main"
            if(branch==child) io { db.sessionBranchDao().insert(SessionBranchEntity(sessionId=sid,branchId=child,label="另一种约定",sourceMessageId=newId,parentBranchId="main")) }
            fun assertAdopted() {
                check(model(sid).state.value.currentBranchId==branch)
                check(io { db.messageDao().getById(oldId) }==originalOld)
                check(io { db.messageDao().getById(newId) }==originalNew)
                io {
                    db.openHelper.readableDatabase.query("SELECT selectedMessageId FROM branch_swipe_selections WHERE sessionId=? AND branchId=? AND swipeGroupId=?",arrayOf(sid,branch,gid)).use { c -> check(c.moveToFirst() && c.getLong(0)==newId && !c.moveToNext()) }
                    if(branch==child) {
                        check(db.messageDao().getMainAdoptedSearchMessageById(sid,oldId)?.id==oldId)
                        check(db.messageDao().getMainAdoptedSearchMessageById(sid,newId)==null)
                    }
                }
            }
            openStory(sid); waitText(oldPhrase)
            if(scenario=="current") {
                rule.onNodeWithContentDescription("会话设置与资料").performClick(); waitText("对话信息")
                rule.onNodeWithText("书签").performClick()
                waitText("还没有收藏")
                rule.onNodeWithText("点击消息选择「收藏」，以后可在这里回到原文。").assertIsDisplayed()
                screenshot("00-empty-guidance")
                rule.onNodeWithContentDescription("关闭").performClick()
                rule.waitUntil(10000) { runCatching { rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertIsDisplayed() }.isSuccess }
            }
            if(branch==child) {
                rule.onNodeWithContentDescription("会话菜单").performClick(); waitText("故事线")
                rule.onNodeWithText("故事线").performClick(); waitText("选择故事线")
                rule.onNodeWithText("另一种约定").performClick()
                rule.waitUntil(10000) { model(sid).state.value.currentBranchId==child && rule.onAllNodesWithText("选择故事线").fetchSemanticsNodes().isEmpty() }
                waitText("1 / 2")
            }
            val draft="版本收藏未发送草稿-$run"
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).performTextReplacement(draft); hideIme()
            val oldBubble = hasText(oldPhrase,substring=true) and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
            rule.onNode(oldBubble).performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithText("收藏").fetchSemanticsNodes().isNotEmpty() }; rule.onNodeWithText("收藏").performClick()
            rule.waitUntil(10000) { oldId in model(sid).state.value.bookmarkedMessageIds && model(sid).state.value.bookmarkBusyIds.isEmpty() }
            screenshot("01-old-bookmarked")
            rule.onNode(oldBubble).performTouchInput { swipeLeft() }
            waitText("2 / 2")
            rule.waitUntil(10000) { model(sid).state.value.displayLines.any { it.swipeGroupId==gid && it.selectedMessage().id==newId } }
            assertAdopted(); screenshot("02-new-adopted"); rule.onNodeWithText(newPhrase,substring=true).assertIsDisplayed()
            rule.onNodeWithContentDescription("会话菜单").performClick(); waitText("搜索消息")
            rule.onNodeWithText("搜索消息").performClick(); waitText("全部故事线")
            if(scenario=="all" || scenario=="childAll") rule.onNodeWithText("全部故事线").performClick()
            fun search(phrase:String,total:Int) {
                rule.onNode(hasSetTextAction()).performTextReplacement(phrase)
                rule.onNode(hasSetTextAction()).performImeAction(); hideIme(); waitSearch(0,total,total)
                assertAdopted()
            }
            search(oldPhrase,if(scenario=="childAll") 1 else 0)
            if(scenario=="childAll") check(searchState().hits.single().let { it.message.id==oldId && it.branchId=="main" })
            screenshot("03-old-search-scope")
            search(newPhrase,1)
            check(searchState().hits.single().message.id==newId && searchState().hits.single().branchId==branch)
            screenshot("04-new-search-hit")
            rule.onAllNodesWithText(newPhrase,substring=true).filter(!hasSetTextAction()).onFirst().performClick()
            rule.waitUntil(10000) { searchModel().state.value.let { !it.searching && !it.contextLoadingBefore && !it.contextLoadingAfter && it.selectedMessageId==newId && it.contextMessages.any { row -> row.id==newId } } }
            check(searchState().contextMessages.single { it.id==newId }==originalNew)
            screenshot("05-new-search-context")
            check(searchState().contextMessages.none { it.id==oldId }) { "Inactive reply rendered as preceding story context" }
            val oldNodes=rule.onAllNodesWithText(oldPhrase,substring=true)
            oldNodes.fetchSemanticsNodes().indices.forEach { oldNodes[it].assertIsNotDisplayed() }
            assertAdopted()
            inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            rule.waitUntil(10000) { searchModel().state.value.selectedMessageId==null }
            inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitText("2 / 2")
            rule.onNodeWithContentDescription("会话设置与资料").performClick(); waitText("对话信息")
            rule.onNodeWithText("书签").performClick()
            rule.waitUntil(10000) { model(sid).state.value.bookmarks.any { it.messageId==oldId } }
            waitText(oldPhrase); screenshot("06-old-bookmark-list")
            rule.onNode(hasText(oldPhrase,substring=true) and hasClickAction() and !SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)).performClick(); waitText("收藏原文 · 只读")
            rule.waitUntil(10000) { model(sid).state.value.bookmarkLocatingId==null }
            check(model(sid).state.value.bookmarkReadOnlyMessage==originalOld)
            rule.onNodeWithText(oldBody).assertIsDisplayed(); screenshot("07-old-read-only"); assertAdopted()
            rule.onNodeWithText("复制全文").performClick()
            rule.runOnUiThread { val clip=rule.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip; check(clip?.getItemAt(0)?.text?.toString()==oldBody) }
            rule.onNodeWithText("关闭").performClick()
            rule.waitUntil(10000) { model(sid).state.value.bookmarkReadOnlyMessage==null }
            assertAdopted(); rule.onNodeWithText(newPhrase,substring=true).assertIsDisplayed()
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft)
            screenshot("08-closed-draft")
            rule.onNodeWithContentDescription("返回会话主页").performClick(); waitText("故事库")
            rule.onAllNodesWithText(title).filter(!hasSetTextAction()).onFirst().performClick()
            rule.waitUntil(10000) { modelOrNull(sid)?.state?.value?.isReady==true }
            waitText("2 / 2"); assertAdopted()
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft)
            rule.onNodeWithText(newPhrase,substring=true).assertIsDisplayed(); screenshot("09-reentry")
            j.addProperty("phase","verified"); persist(j)
            output.resolve("verified.txt").writeText("realUi=true\nrealSearchOwner=true\noldBookmark=true\nnewAdoption=true\noldSearchScopeExact=true\nnewSearchExact=true\ncontextAdoptedOnly=true\noldReadOnlyExact=true\ncopyExact=true\noriginalRowsExact=true\nbranchSelectionExact=true\nparentAdoptionPreserved=${branch==child}\ndraftReentry=true\n")
        } catch(t:Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString()); runCatching { screenshot("failure") }; throw t }
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
        if(j.has("ownedSearchSession")) {
            check(j["ownedSearchSession"].asLong==sid)
            val sp=context.getSharedPreferences("message_search",0)
            val edit=sp.edit()
            sp.all.keys.filter { it=="history_$sid" || it.startsWith("history_${sid}_") }.forEach { edit.remove(it) }
            check(edit.commit())
        }
        check(searchPrefsDigest()==j["searchPrefsDigest"].asString)
        draftNames.forEach { n -> assertSnapshot(context.getSharedPreferences(n, 0), j.getAsJsonObject("drafts").getAsJsonObject(n)) }
        check(branches() == j.getAsJsonObject("branches")); check(digest() == j["digest"].asString); check(mediaFilesDigest() == j["mediaFilesDigest"].asString)
        j.addProperty("phase", "rolled-back"); persist(j)
        output.resolve("rollback.txt").writeText("uuidOnlyRemoved=true\nconfigPresenceValuesExact=true\nfourDraftsExact=true\nbranchPrefsExact=true\nthirteenTablesDigestExact=true\noriginalMediaFilesDigestExact=true\nsearchPrefsDigestExact=true\n")
    }
}

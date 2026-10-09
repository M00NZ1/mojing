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
class RecallAdoptionAppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("recallAdoptionRun").orEmpty().also { UUID.fromString(it) }
    private val scenario get() = args.getString("scenario").orEmpty()
    private val title get() = "撤回回复${run.take(8)}"
    private val marker get() = "PARENT_MEMORY_$run"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "recall-adoption-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "recall-adoption-$run").apply { mkdirs() }
    private val preferences get() = rule.activity.secureStorage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(rule.activity.secureStorage) as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun guard(idle: Boolean = true) {
        assumeTrue(args.getString("recallAdoptionCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        if (idle) check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
        check(scenario in listOf("recall", "protected"))
    }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "recall-adoption-$run.pending")
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
        listOf("sessions", "characters", "messages", "app_config", "session_participants", "session_worlds", "character_profiles", "character_expressions", "voice_profiles", "personas", "world_encyclopedias", "encyclopedia_entries", "entry_relations", "entry_versions", "timeline_events", "world_templates", "world_lore_entries", "prompt_templates", "session_character_states", "session_memory_segments", "session_event_nodes", "session_branches", "session_context_memories", "message_attachments", "message_bookmarks", "llm_cost_records", "generation_tasks", "branch_visibility_segments", "session_memory_corrections", "branch_swipe_selections", "branch_context_exclusions", "legacy_world_mappings", "legacy_lore_mappings", "branch_event_status").forEach { table ->
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

    @Test fun recallAdoptionRequestAndPersistence() {
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
            val draft="撤回未发送草稿-$run"
            fun selected(id:Long) {
                check(model(sid).state.value.currentBranchId=="main")
                check(model(sid).state.value.displayLines.any { it.swipeGroupId==gid && it.selectedMessage().id==id })
                io { db.openHelper.readableDatabase.query("SELECT selectedMessageId FROM branch_swipe_selections WHERE sessionId=? AND branchId='main' AND swipeGroupId=?",arrayOf(sid,gid)).use { c -> check(c.moveToFirst() && c.getLong(0)==id && !c.moveToNext()) } }
            }
            fun bubble(phrase:String)=hasText(phrase,substring=true) and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
            openStory(sid); waitText(oldPhrase)
            rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).performTextReplacement(draft); hideIme()
            rule.onNode(bubble(oldPhrase)).performClick(); waitText("收藏"); rule.onNodeWithText("收藏").performClick()
            rule.waitUntil(10000) { oldId in model(sid).state.value.bookmarkedMessageIds && model(sid).state.value.bookmarkBusyIds.isEmpty() }
            screenshot("01-old-bookmarked")
            rule.onNode(bubble(oldPhrase)).performTouchInput { swipeLeft() }; waitText("2 / 2")
            rule.waitUntil(10000) { model(sid).state.value.displayLines.any { it.swipeGroupId==gid && it.selectedMessage().id==newId } }
            selected(newId); screenshot("02-new-adopted")
            var segmentId=0L; var eventId=0L; var stateId=0L; var correctionId=0L
            io { db.withTransaction {
                segmentId=db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(sessionId=sid,startMessageId=oldId,endMessageId=newId,summary="合成摘要-$run"))
                eventId=db.sessionEventNodeDao().insert(SessionEventNodeEntity(sessionId=sid,messageId=newId,title="合成事件-$run"))
                stateId=db.characterStateDao().upsert(SessionCharacterStateEntity(sessionId=sid,characterId=cid,lastSnapshotAttemptUserMessageId=newId,dynamicStateJson="{\"synthetic\":\"$run\"}"))
                correctionId=db.sessionMemoryCorrectionDao().insert(SessionMemoryCorrectionEntity(sessionId=sid,sourceMessageId=newId,content="保留手动纠正-$run"))
                db.sessionContextMemoryDao().upsert(SessionContextMemoryEntity(sessionId=sid,sourceStartMessageId=oldId,sourceEndMessageId=newId,globalSummary="合成当前记忆-$run",isValid=true,revision=30))
                if(scenario=="protected") db.sessionBranchDao().insert(SessionBranchEntity(sessionId=sid,branchId="protected-$run",label="保留约定",sourceMessageId=newId))
            } }
            val beforeDialog=digest()
            // Room transaction startup under the Compose test dispatcher stalls without this
            // read-only fixture check. Separate native-clock tests cover both cold UI previews.
            val preview=io { db.messageDao().previewRecallInSession(sid,newId) }
            check(preview.canRecall==(scenario=="recall"))
            output.resolve("harness-boundary.txt").writeText("readOnlyDaoPreviewBeforeUi=true\nnativeClockColdPreviewCoveredSeparately=true\n")
            fun recallDialog() {
                rule.onNode(bubble(newPhrase)).performClick(); waitText("撤回"); rule.onNodeWithText("撤回").performClick()
                waitText("撤回这条消息？")
                try {
                    rule.waitUntil(10000) { rule.onAllNodesWithText("正在检查故事线引用…").fetchSemanticsNodes().isEmpty() }
                } catch(t:Throwable) {
                    output.resolve("thread-stacks.txt").writeText(Thread.getAllStackTraces().entries.sortedBy { it.key.name }.joinToString("\n\n") { (thread,stack) -> thread.name+" "+thread.state+"\n"+stack.joinToString("\n") })
                    val started=System.nanoTime()
                    val observation=io { kotlinx.coroutines.withTimeout(5000) { model(sid).previewMessageRecall(newId) } }
                    output.resolve("stalled-preview.txt").writeText("vmReadMs=${(System.nanoTime()-started)/1000000}\ncanRecall=${observation.canRecall}\n")
                    output.resolve("dialog-semantics.txt").writeText(rule.onAllNodes(isRoot(),useUnmergedTree=true).fetchSemanticsNodes().indices.joinToString("\n") { rule.onAllNodes(isRoot(),useUnmergedTree=true)[it].printToString() })
                    throw t
                }
            }
            recallDialog()
            if(scenario=="protected") {
                rule.onNodeWithText("需要保留这条消息").assertIsDisplayed()
                rule.onNodeWithText("这条消息或其附属内容是故事线、检查点或编辑版本的来源。请保留原文，使用编辑创建新的故事线。").assertIsDisplayed()
                rule.onNodeWithText("故事线 · 保留约定").assertIsDisplayed()
                rule.onNodeWithText("确认撤回").assertIsNotEnabled(); screenshot("03-protected-reason")
                rule.onNodeWithText("保留并返回").performClick()
                rule.waitUntil(10000) { rule.onAllNodesWithText("撤回这条消息？").fetchSemanticsNodes().isEmpty() }
                check(digest()==beforeDialog); selected(newId)
                rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft)
                screenshot("04-protected-return")
            } else {
                rule.onNodeWithText("将重新整理 1 段自动摘要，后续对话会逐批补齐；手动纠正会保留。").assertIsDisplayed()
                rule.onNodeWithText("同组的其他回复会保留；撤回当前版本后会显示剩余版本。").assertIsDisplayed()
                rule.onNodeWithText("确认撤回").assertIsEnabled(); screenshot("03-recall-impact")
                rule.onNodeWithText("取消").performClick()
                rule.waitUntil(10000) { rule.onAllNodesWithText("撤回这条消息？").fetchSemanticsNodes().isEmpty() }
                check(digest()==beforeDialog); selected(newId)
                rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft); screenshot("04-cancel-preserved")
                recallDialog(); rule.onNodeWithText("确认撤回").performClick()
                rule.waitUntil(10000) { rule.onAllNodesWithText("撤回这条消息？").fetchSemanticsNodes().isEmpty() && model(sid).state.value.displayLines.any { it.selectedMessage().id==oldId } }
                selected(oldId)
                io {
                    check(db.messageDao().getById(newId)==null); check(db.messageDao().getById(oldId)==originalOld)
                    check(db.messageDao().getMainAdoptedSearchMessageById(sid,oldId)?.id==oldId)
                    check(db.messageDao().getMainAdoptedSearchMessageById(sid,newId)==null)
                    fun rowCount(table:String,id:Long):Int=db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table WHERE id=?",arrayOf(id)).use { c -> check(c.moveToFirst()); c.getInt(0) }
                    check(rowCount("session_memory_segments",segmentId)==0); check(rowCount("session_event_nodes",eventId)==0)
                    val cs=db.characterStateDao().getBySessionAndCharacter(sid,cid)!!
                    check(cs.id==stateId && !cs.snapshotIsValid && cs.lastSnapshotAttemptUserMessageId==0L && cs.dynamicStateJson.contains(run))
                    check(db.sessionMemoryCorrectionDao().getById(sid,correctionId)?.content=="保留手动纠正-$run")
                    val memory=db.sessionContextMemoryDao().getBySessionAndBranch(sid,"main")!!
                    check(!memory.isValid && memory.revision==31L && memory.globalSummary=="合成当前记忆-$run")
                }
                check(oldId in model(sid).state.value.bookmarkedMessageIds)
                rule.onNode(bubble(oldPhrase)).assertIsDisplayed(); screenshot("05-fallback-old")
                rule.onNodeWithContentDescription("会话设置与资料").performClick(); waitText("对话信息"); rule.onNodeWithText("书签").performClick()
                waitText(oldPhrase); screenshot("06-bookmark-list")
                rule.onNode(hasText(oldPhrase,substring=true) and hasClickAction() and !SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)).performClick()
                rule.waitUntil(10000) { model(sid).state.value.bookmarkLocatingId==null && !model(sid).state.value.isDrawerOpen && model(sid).state.value.focusedMessageId==oldId }
                check(model(sid).state.value.bookmarkReadOnlyMessage==null); selected(oldId)
                rule.onNode(bubble(oldPhrase)).assertIsDisplayed(); screenshot("07-bookmark-normal-location")
                rule.onNodeWithContentDescription("会话菜单").performClick(); waitText("搜索消息"); rule.onNodeWithText("搜索消息").performClick(); waitText("全部故事线")
                fun search(phrase:String,total:Int) {
                    rule.onNode(hasSetTextAction()).performTextReplacement(phrase); rule.onNode(hasSetTextAction()).performImeAction(); hideIme(); waitSearch(0,total,total); selected(oldId)
                }
                search(newPhrase,0); screenshot("08-recalled-search-empty")
                search(oldPhrase,1); check(searchState().hits.single().message.id==oldId); screenshot("09-old-search-hit")
                rule.onAllNodesWithText(oldPhrase,substring=true).filter(!hasSetTextAction()).onFirst().performClick()
                rule.waitUntil(10000) { searchState().let { !it.contextLoadingBefore && !it.contextLoadingAfter && it.selectedMessageId==oldId && it.contextMessages.any { row -> row.id==oldId } } }
                check(searchState().contextMessages.single { it.id==oldId }==originalOld); check(searchState().contextMessages.none { it.id==newId })
                screenshot("10-old-search-context")
                inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                rule.waitUntil(10000) { searchModel().state.value.selectedMessageId==null }
                inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                rule.waitUntil(10000) { runCatching { rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertIsDisplayed() }.isSuccess }
            }
            val expected=if(scenario=="protected") newId else oldId
            selected(expected); rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft)
            rule.onNodeWithContentDescription("返回会话主页").performClick(); waitText("故事库")
            rule.onAllNodesWithText(title).filter(!hasSetTextAction()).onFirst().performClick()
            rule.waitUntil(10000) { modelOrNull(sid)?.state?.value?.isReady==true }
            selected(expected); rule.onNodeWithContentDescription("消息输入",useUnmergedTree=true).assertTextEquals(draft)
            rule.onNode(bubble(if(scenario=="protected") newPhrase else oldPhrase)).assertIsDisplayed(); screenshot("11-reentry-draft")
            if(scenario=="protected") check(digest()==beforeDialog)
            j.addProperty("phase","verified"); persist(j)
            output.resolve("verified.txt").writeText("realUi=true\nscenario=$scenario\ncancelOrRetainExact=true\nremainingAdoptionOrProtectionExact=true\nmanualCorrectionPreserved=true\ndraftReentry=true\n")
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
        output.resolve("rollback.txt").writeText("uuidOnlyRemoved=true\nconfigPresenceValuesExact=true\nfourDraftsExact=true\nbranchPrefsExact=true\nthirtyFourBusinessTablesDigestExact=true\noriginalMediaFilesDigestExact=true\nsearchPrefsDigestExact=true\n")
    }
}

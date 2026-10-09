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
import kotlinx.coroutines.launch
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
class SearchScrollNativeAppTest {
    private lateinit var activity: MainActivity
    private lateinit var activityScenario: androidx.test.core.app.ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("recallAdoptionRun").orEmpty().also { UUID.fromString(it) }
    private val scenario get() = args.getString("scenario").orEmpty()
    private val title get() = "深结果搜索${run.take(8)}"
    private val marker get() = "PARENT_MEMORY_$run"
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "recall-adoption-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "recall-adoption-$run").apply { mkdirs() }
    private val preferences get() = com.mojing.app.data.SecureStorage().apply { init(context) }.let { storage -> storage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(storage) } as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun guard(idle: Boolean = true) {
        assumeTrue(args.getString("recallAdoptionCapture") == "true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        if (idle) check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration() == null)
        check(scenario in listOf("own", "inherited"))
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
    private fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
        if(Build.VERSION.SDK_INT>=33) inst.uiAutomation.clearCache()
        val result=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        fun visit(node:android.view.accessibility.AccessibilityNodeInfo?) { if(node==null) return; result.add(node); for(i in 0 until node.childCount) visit(node.getChild(i)) }
        val windows=inst.uiAutomation.windows
        if(windows.isEmpty()) visit(inst.uiAutomation.rootInActiveWindow) else windows.forEach { visit(it.root) }
        return result
    }
    private fun waitNode(text:String, exact:Boolean=false):android.view.accessibility.AccessibilityNodeInfo {
        val until=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<until) {
            val current=nodes().filter { it.isVisibleToUser }
            (current.firstOrNull { it.text?.toString()==text || it.contentDescription?.toString()==text }
                ?: current.firstOrNull { !exact && (it.text?.toString()?.contains(text)==true || it.contentDescription?.toString()?.contains(text)==true) })?.let { return it }
            Thread.sleep(50)
        }; error("native node missing: $text")
    }
    private fun click(text:String) {
        var n:android.view.accessibility.AccessibilityNodeInfo?=waitNode(text,true)
        while(n!=null && !n.isClickable) n=n.parent
        check(n?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true)
    }
    private fun clickContaining(text:String) {
        var selected:android.view.accessibility.AccessibilityNodeInfo?=null
        val until=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<until && selected==null) {
            selected=nodes().firstOrNull { it.isVisibleToUser && !it.isEditable && it.text?.toString()?.contains(text)==true }
            if(selected==null) Thread.sleep(40)
        }
        var n=checkNotNull(selected) { "native content missing: $text" }
        while(!n.isClickable) { n=checkNotNull(n.parent); check(!n.isEditable) }
        check(n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun waitState(condition:()->Boolean) {
        val until=System.currentTimeMillis()+20000
        while(System.currentTimeMillis()<until) { if(condition()) return; Thread.sleep(30) }; error("native state timeout")
    }
    private fun model(sid:Long):ChatViewModel {
        val stores=RetainedChatSessions.stores
        val entries=stores.javaClass.getDeclaredField("entries").apply { isAccessible=true }.get(stores) as Map<*,*>
        val entry=checkNotNull(entries[sid])
        return entry.javaClass.getDeclaredField("model").apply { isAccessible=true }.get(entry) as ChatViewModel
    }
    private fun searchModel():SearchViewModel {
        fun values(store:androidx.lifecycle.ViewModelStore):Collection<*> = (store.javaClass.getDeclaredField("map").apply { isAccessible=true }.get(store) as Map<*,*>).values
        val nav=values(activity.viewModelStore).single { it?.javaClass?.name=="androidx.navigation.NavControllerViewModel" }!!
        val stores=nav.javaClass.getDeclaredField("viewModelStores").apply { isAccessible=true }.get(nav) as Map<*,*>
        return stores.values.flatMap { values(it as androidx.lifecycle.ViewModelStore) }.filterIsInstance<SearchViewModel>().single()
    }
    private fun searchPrefsDigest():String {
        val values=context.getSharedPreferences("message_search",0).all.toSortedMap().map { (k,v) -> k+":"+when(v) { is Set<*> -> v.map { it.toString() }.sorted().joinToString("|"); else -> v.toString() } }.joinToString("\n")
        return java.security.MessageDigest.getInstance("SHA-256").digest(values.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun capture(label:String) {
        inst.waitForIdleSync()
        repeat(2) {
            val frame=java.util.concurrent.CountDownLatch(1)
            inst.runOnMainSync { activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }; activity.window.decorView.invalidate() }
            check(frame.await(3,java.util.concurrent.TimeUnit.SECONDS))
        }
        val b=checkNotNull(inst.uiAutomation.takeScreenshot())
        try { FileOutputStream(output.resolve("$label.png")).use { check(b.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { b.recycle() }
    }
    private fun tap(x:Float,y:Float) {
        val now=android.os.SystemClock.uptimeMillis()
        val down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0)
        val up=android.view.MotionEvent.obtain(now,now+80,android.view.MotionEvent.ACTION_UP,x,y,0)
        try { check(inst.uiAutomation.injectInputEvent(down,true)); check(inst.uiAutomation.injectInputEvent(up,true)) } finally { down.recycle();up.recycle() }
    }
    private fun replaceInput(text:String) {
        var edit:android.view.accessibility.AccessibilityNodeInfo?=null
        waitState { edit=nodes().firstOrNull { it.isVisibleToUser && it.isEditable }; edit!=null }
        check(edit!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text) }))
    }



    private fun longContent(text:String) {
        var n=waitNode(text)
        while(!n.isLongClickable) n=checkNotNull(n.parent)
        check(n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK))
    }
    private fun hold(x:Float,y:Float) {
        val now=android.os.SystemClock.uptimeMillis()
        val down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0)
        try { check(inst.uiAutomation.injectInputEvent(down,true)); Thread.sleep(900)
            val up=android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,x,y,0)
            try { check(inst.uiAutomation.injectInputEvent(up,true)) } finally { up.recycle() }
        } finally { down.recycle() }
    }
    private fun back() {
        inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
    }
    @Test fun searchScrollRecreation() {
        guard(); check(!journalFile.exists())
        val overview=args.getString("overview")=="true"
        val before=args.getString("before")=="true"
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val j=JsonObject().apply {
            addProperty("run",run); addProperty("scenario",scenario); addProperty("phase","snapshot")
            add("originalConfig",JsonObject().apply { configKeys.forEach { k -> addProperty(k,if(preferences.contains(k)) preferences.getString(k,null) else null) } })
            add("drafts",JsonObject().apply { draftNames.forEach { n -> add(n,snapshot(context.getSharedPreferences(n,0))) } })
            add("branches",branches()); addProperty("digest",digest()); addProperty("mediaFilesDigest",mediaFilesDigest()); addProperty("searchPrefsDigest",searchPrefsDigest())
        }; persist(j)
        try {
            val cid=io { db.characterDao().upsert(CharacterEntity(name="来信者-$run",personaPrompt="SYNTHETIC_$run")) }; j.addProperty("character",cid); persist(j)
            val sid=io { db.sessionDao().insert(SessionEntity(title=title,creationRequestId=run)) }; j.addProperty("session",sid); persist(j)
            val sourceText="分叉原文-${run.take(8)}"
            val mid=io { db.withTransaction {
                db.participantDao().upsert(SessionParticipantEntity(sessionId=sid,characterId=cid))
                db.sessionWorldDao().upsert(SessionWorldEntity(sessionId=sid,narratorEnabled=false,gameplayMode="对话",autoSedimentEnabled=false))
                db.messageDao().insert(MessageEntity(sessionId=sid,speakerType="user",content=sourceText))
            } }
            val target="branch-search-$run"
            val targetLabel="雨港搜索线-${run.take(8)}"
            io { db.sessionBranchDao().insert(SessionBranchEntity(sessionId=sid,branchId=target,label=targetLabel,sourceMessageId=mid,parentBranchId="main",createdAt=1001L)) }
            val query="雨港检索${run.take(8)}"
            val targetText="$query 深层目标原文"
            val tid=io { db.messageDao().insert(MessageEntity(sessionId=sid,branchId=target,speakerType="character",characterId=cid,content=targetText)) }
            val extraIds=io { db.withTransaction { (2..40).map { n -> db.messageDao().insert(MessageEntity(sessionId=sid,branchId=target,speakerType="character",characterId=cid,content="$query 第${n}条检索原文")) } } }
            val originalExtra=io { extraIds.map { db.messageDao().getById(it) } }
            val mainOriginal=io { db.messageDao().getById(mid) }!!
            val targetOriginal=io { db.messageDao().getById(tid) }!!
            val branchOriginal=io { db.sessionBranchDao().getBySession(sid) }.sortedBy { it.id }
            activityScenario=androidx.test.core.app.ActivityScenario.launch(android.content.Intent(context,MainActivity::class.java).putExtra("navigate_to","chat").putExtra("session_id",sid)); activityScenario.onActivity { activity=it }
            waitState { runCatching { model(sid).state.value.isReady }.getOrDefault(false) }; waitNode("会话菜单")
            val draft="故事线未发送草稿-$run"; replaceInput(draft); waitState { model(sid).state.value.inputText==draft }
            fun hideIme() {
                inst.runOnMainSync { activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken,0); activity.currentFocus?.clearFocus() }
                waitState { activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())!=true }
            }
            val origin=if(scenario=="inherited") target else "main"
            if(origin!= "main") {
                hideIme(); click("会话菜单"); click("故事线"); waitNode("选择故事线",true); clickContaining(targetLabel)
                waitState { model(sid).state.value.currentBranchId==target && model(sid).state.value.branchNavigationLabel==null }
                waitNode(targetText,true)
            }
            fun open() { hideIme(); click("会话菜单"); click("搜索消息"); waitNode("全部故事线",true) }
            fun checkOriginal() {
                check(io { db.messageDao().getById(mid) }==mainOriginal)
                check(io { db.messageDao().getById(tid) }==targetOriginal)
                check(io { extraIds.map { db.messageDao().getById(it) } }==originalExtra)
                check(io { db.sessionBranchDao().getBySession(sid) }.sortedBy { it.id }==branchOriginal)
            }
            fun checkChat() { check(model(sid).state.value.currentBranchId==origin && model(sid).state.value.inputText==draft); checkOriginal() }
            open(); replaceInput(query); if(origin=="main") click("全部故事线")
            j.addProperty("ownedSearchSession",sid); persist(j)
            click("搜索")
            waitState { searchModel().state.value.let { !it.searching && !it.counting && it.totalMatches==40 && it.hits.size==40 && it.hits.last().message.id==tid } }
            val searchVm=searchModel(); val result=searchVm.state.value
            check(result.scope==(if(origin=="main") SearchScope.ALL else SearchScope.CURRENT) && !result.exactMatch && result.query==query)
            waitNode("共 40 条匹配消息",true)
            fun targetBounds():android.graphics.Rect? = nodes().firstOrNull { it.isVisibleToUser && !it.isEditable && it.text?.toString()?.contains("深层目标原文")==true }?.let { node -> android.graphics.Rect().also { node.getBoundsInScreen(it) } }
            fun settledBounds():android.graphics.Rect {
                var last:android.graphics.Rect?=null; var same=0
                repeat(100) {
                    val frame=java.util.concurrent.CountDownLatch(1)
                    inst.runOnMainSync { activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }; activity.window.decorView.invalidate() }
                    check(frame.await(3,java.util.concurrent.TimeUnit.SECONDS))
                    val next=targetBounds()
                    same=if(next!=null && next==last) same+1 else 0
                    last=next
                    if(same>=4) return checkNotNull(next)
                }; error("target bounds did not settle")
            }
            fun checkRestoredBounds(original:android.graphics.Rect) {
                val restored=settledBounds()
                val rounding= kotlin.math.ceil(context.resources.displayMetrics.density.toDouble()).toInt()
                check(restored.left==original.left && restored.right==original.right && restored.height()==original.height() && kotlin.math.abs(restored.top-original.top)<=rounding) { "original=$original restored=$restored rounding=$rounding" }
                output.resolve("bounds.txt").appendText("original=$original restored=$restored rounding=$rounding\n")
            }
            check(targetBounds()==null)
            fun scrollToTarget() {
                var steps=0
                while(targetBounds()==null) {
                    check(steps++<50); val list=nodes().first { it.isVisibleToUser && it.isScrollable }
                    val moved=list.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    inst.waitForIdleSync()
                    repeat(2) {
                        val frame=java.util.concurrent.CountDownLatch(1)
                        inst.runOnMainSync { activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }; activity.window.decorView.invalidate() }
                        check(frame.await(3,java.util.concurrent.TimeUnit.SECONDS))
                    }
                    check(moved || targetBounds()!=null)
                }
            }
            scrollToTarget()
            val originalBounds=settledBounds(); capture("01-deep-result-scrolled"); checkChat()
            activityScenario.recreate(); activityScenario.onActivity { activity=it }; waitState { model(sid).state.value.isReady }; waitNode("全部故事线",true)
            check(searchModel()===searchVm && searchModel().state.value==result)
            if(before) {
                check(targetBounds()==null); capture("02-before-deep-result-lost"); click("返回"); checkChat()
                output.resolve("verified.txt").writeText("beforeDeepResultPositionLost=true\nqueryScopeResultsOwnerRetained=true\noriginalRowsDraftNoSwitch=true\n")
                j.addProperty("phase","before-verified"); persist(j); return
            }
            waitNode("深层目标原文"); checkRestoredBounds(originalBounds); capture("02-deep-result-restored"); checkChat()
            clickContaining("深层目标原文")
            waitState { !searchModel().state.value.searching && searchModel().state.value.selectedMessageId==tid }
            waitNode("打开对话",true); capture("03-deep-target-reader"); checkChat()
            click("结果列表"); waitNode("全部故事线",true); waitNode("深层目标原文"); checkRestoredBounds(originalBounds); checkChat()
            capture("04-return-to-deep-result")
            replaceInput("$query 第40条检索原文"); click("搜索")
            waitState { searchModel().state.value.let { !it.searching && !it.counting && it.totalMatches==1 } }
            check(targetBounds()==null)
            replaceInput(query); click("搜索")
            waitState { searchModel().state.value.let { !it.searching && !it.counting && it.totalMatches==40 && it.hits.size==40 } }
            check(targetBounds()==null); check(searchModel().state.value.resultRevision!=result.resultRevision)
            capture("05-new-query-resets-to-top"); checkChat()

            click("返回"); waitNode("会话菜单",true); checkChat()
            open(); waitNode(query); scrollToTarget(); clickContaining("深层目标原文")
            waitState { searchModel().state.value.let { !it.searching && it.selectedMessageId==tid && it.selectedBranchId==target && it.contextMessages.any { m -> m.id==tid } } }
            waitNode("打开对话",true); capture("06-target-original-reader"); checkChat()
            val detail=searchModel().state.value
            activityScenario.recreate(); activityScenario.onActivity { activity=it }; waitState { model(sid).state.value.isReady }
            waitNode("打开对话",true); check(searchModel()===searchVm && searchModel().state.value==detail)
            waitNode(query); capture("07-reader-recreated"); checkChat()
            click("结果列表"); waitNode("全部故事线",true); checkChat(); clickContaining("深层目标原文")
            waitState { !searchModel().state.value.searching && searchModel().state.value.selectedMessageId==tid }
            click("打开对话")
            waitState { model(sid).state.value.currentBranchId==target && model(sid).state.value.branchNavigationLabel==null && model(sid).state.value.messages.any { it.id==tid } }
            waitNode("会话菜单",true); waitNode(query); check(model(sid).state.value.inputText==draft); checkOriginal()
            waitState { branches().get(sid.toString())?.asString==target }; capture("08-cross-branch-target-draft")
            click("返回会话主页"); waitNode("故事库"); replaceInput(title); hideIme(); waitNode(title,true); clickContaining(title); waitNode("会话菜单",true)
            waitState { model(sid).state.value.isReady && model(sid).state.value.currentBranchId==target && model(sid).state.value.inputText==draft }
            waitNode("$query 第40条检索原文",true); checkOriginal(); capture("09-reentry-target-draft")
            open(); replaceInput(targetText); j.addProperty("ownedSearchSession",sid); persist(j); click("搜索")
            waitState { searchModel().state.value.let { !it.searching && !it.counting && it.totalMatches==1 && it.hits.single().message.id==tid } }
            clickContaining("深层目标原文"); waitNode("打开对话",true); click("打开对话"); waitNode("会话菜单",true)
            waitNode(targetText,true); checkOriginal(); check(model(sid).state.value.inputText==draft && model(sid).state.value.currentBranchId==target)
            capture("10-reentry-formal-target-relocated")
            output.resolve("verified.txt").writeText("deepResultAnchorRecreatedWithinOneDp=true\nreturnFromReaderAnchorWithinOneDp=true\nnewQueryResetsToTop=true\nqueryScopeResultsRecreated=true\noriginalHiltOwnerRetained=true\ncancelNoSwitchOriginalDraftExact=true\nreaderTargetBranchRecreated=true\nreaderCloseNoWrite=true\nformalCrossBranchOpen=true\noriginalRowsExact=true\ndraftPreferenceReentryExact=true\n")
            j.addProperty("phase","verified"); persist(j)
        } catch(t:Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString()); output.resolve("nodes.txt").writeText(nodes().filter { it.isVisibleToUser }.joinToString("\n") { "text=${it.text} desc=${it.contentDescription} click=${it.isClickable}" }); runCatching { capture("failure") }; throw t }
        finally { if(::activityScenario.isInitialized) activityScenario.close() }
    }
    @Test fun rollbackOnlyOwnedBranchMemory() {
        guard(idle = false)
        val j = JsonParser.parseString(journalFile.readText()).asJsonObject; check(j["run"].asString == run)
        val sid = j["session"]?.asLong ?: io { db.sessionDao().getByCreationRequestId(run)?.id } ?: 0L
        val cid = j["character"]?.asLong ?: 0L
        check(RetainedChatSessions.running.value.all { it == sid })
        if (sid > 0L) {
            inst.runOnMainSync { RetainedChatSessions.stores.stop(sid) }
            waitState { sid !in RetainedChatSessions.running.value }
            io { db.withTransaction {
                val owned = db.sessionDao().getById(sid); check(owned == null || owned.creationRequestId == run)
                db.openHelper.writableDatabase.execSQL("DELETE FROM llm_cost_records WHERE sessionId = ?", arrayOf(sid))
                db.sessionDao().delete(sid)
            } }
        }
        if (cid > 0L) io { db.characterDao().getById(cid)?.let { check(it.name == "来信者-$run" && it.personaPrompt == "SYNTHETIC_$run"); db.characterDao().delete(cid) } }
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

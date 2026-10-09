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
class EventInheritanceNativeAppTest {
    private lateinit var activity: MainActivity
    private lateinit var activityScenario: androidx.test.core.app.ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("recallAdoptionRun").orEmpty().also { UUID.fromString(it) }
    private val scenario get() = args.getString("scenario").orEmpty()
    private val title get() = "继承事件${run.take(8)}"
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

    @Test fun inheritedEventFlow() {
        guard(); check(!journalFile.exists())
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val j=JsonObject().apply {
            addProperty("run",run); addProperty("scenario",scenario); addProperty("phase","snapshot")
            add("originalConfig",JsonObject().apply { configKeys.forEach { k -> addProperty(k,if(preferences.contains(k)) preferences.getString(k,null) else null) } })
            add("drafts",JsonObject().apply { draftNames.forEach { n -> add(n,snapshot(context.getSharedPreferences(n,0))) } })
            add("branches",branches()); addProperty("digest",digest()); addProperty("mediaFilesDigest",mediaFilesDigest()); addProperty("searchPrefsDigest",searchPrefsDigest())
        }; persist(j)
        try {
            val cid=io { db.characterDao().upsert(CharacterEntity(name="子线角色-$run",personaPrompt="SYNTHETIC_$run")) }; j.addProperty("character",cid); persist(j)
            val sid=io { db.sessionDao().insert(SessionEntity(title=title,creationRequestId=run)) }; j.addProperty("session",sid); persist(j)
            val child="event-$run"
            val sourceBody="青灯约定原文-$run"
            var sourceId=0L; var eventId=0L; var futureEventId=0L
            io { db.withTransaction {
                db.participantDao().upsert(SessionParticipantEntity(sessionId=sid,characterId=cid))
                db.sessionWorldDao().upsert(SessionWorldEntity(sessionId=sid,narratorEnabled=false,autoSedimentEnabled=false))
                sourceId=db.messageDao().insert(MessageEntity(sessionId=sid,speakerType="character",characterId=cid,content=sourceBody,createdAt=1000))
                val anchor=db.messageDao().insert(MessageEntity(sessionId=sid,speakerType="user",content="在桥边分叉-$run",createdAt=2000))
                val future=db.messageDao().insert(MessageEntity(sessionId=sid,speakerType="character",characterId=cid,content="父线未来银舟-$run",createdAt=3000))
                eventId=db.sessionEventNodeDao().insert(SessionEventNodeEntity(sessionId=sid,messageId=sourceId,title="青灯约定",description="等待桥边来信",createdAt=1000))
                futureEventId=db.sessionEventNodeDao().insert(SessionEventNodeEntity(sessionId=sid,messageId=future,title="银舟未来",createdAt=3000))
                db.sessionBranchDao().insert(SessionBranchEntity(sessionId=sid,branchId=child,label="舟行子线",sourceMessageId=anchor))
                db.messageDao().insert(MessageEntity(sessionId=sid,branchId=child,speakerType="character",characterId=cid,content="子线独立正文-$run",createdAt=4000))
            } }
            val parentEvent=io { db.sessionEventNodeDao().getBySessionAndBranch(sid,"main").single { it.id==eventId } }
            val originalMessages=io { db.messageDao().getMainMessagesForExport(sid,0,Long.MAX_VALUE,20) }
            val intent=android.content.Intent(context,MainActivity::class.java).putExtra("navigate_to","chat").putExtra("session_id",sid)
            activityScenario=androidx.test.core.app.ActivityScenario.launch(intent); activityScenario.onActivity { activity=it }
            waitState { runCatching { model(sid).state.value.isReady }.getOrDefault(false) }; waitNode("会话菜单")
            val draft="继承事件未发送草稿-$run"
            replaceInput(draft); waitState { model(sid).state.value.inputText==draft }
            fun hideIme() {
                inst.runOnMainSync { activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken,0); activity.currentFocus?.clearFocus() }
                waitState { activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())!=true }
            }
            hideIme()
            fun switch(label:String,branch:String) {
                click("会话菜单"); click("故事线"); waitNode("选择故事线"); click(label)
                waitState { model(sid).state.value.let { it.isReady && it.currentBranchId==branch && it.branchNavigationLabel==null } }
                waitState { nodes().none { it.isVisibleToUser && it.text?.toString()=="选择故事线" } }
            }
            fun openEvents() {
                click("会话设置与资料"); waitNode("对话信息")
                if (nodes().none { it.isVisibleToUser && it.contentDescription?.toString()=="搜索故事线事件" }) click("事件")
                waitNode("搜索故事线事件")
                waitState { model(sid).state.value.let { it.eventNodesLoaded && !it.eventNodesLoadingMore } }
            }
            fun query(text:String) {
                replaceInput(text); waitState { model(sid).state.value.let { it.eventQuery==text && it.eventNodesLoaded && !it.eventNodesLoadingMore } }; hideIme()
            }
            fun closeDrawer() { click("关闭"); waitState { nodes().none { it.isVisibleToUser && it.text?.toString()=="对话信息" } } }
            switch("舟行子线",child); openEvents(); query("青灯约定")
            check(model(sid).state.value.eventNodes.single().let { it.id==eventId && !it.resolved && it.branchId=="main" })
            waitNode("继承事件 · 本线可改状态，删除请回来源线"); capture("01-child-inherited-pending")
            var delete:android.view.accessibility.AccessibilityNodeInfo?=waitNode("删除事件")
            while(delete!=null && !delete.isClickable && delete.isEnabled) delete=delete.parent
            check(delete!=null && !delete.isEnabled)
            click("标为已解决")
            waitState { model(sid).state.value.let { it.eventBusyIds.isEmpty() && it.eventNodes.singleOrNull()?.resolved==true } }
            io { check(db.sessionEventNodeDao().getBySessionAndBranch(sid,"main").single { it.id==eventId }==parentEvent); check(db.sessionEventNodeDao().getAllStatusOverrides(sid,child).single()==BranchEventStatusEntity(sid,child,eventId,true)) }
            click("已解决"); waitState { model(sid).state.value.let { it.eventResolvedFilter==true && it.eventNodesLoaded && it.eventNodes.singleOrNull()?.id==eventId } }
            waitNode("标为未解决"); capture("02-child-resolved-filter")
            click("待跟进"); waitState { model(sid).state.value.let { it.eventResolvedFilter==false && it.eventNodesLoaded && it.eventNodes.isEmpty() } }
            waitNode("没有匹配的事件"); capture("03-child-pending-empty")
            click("全部"); waitState { model(sid).state.value.let { it.eventResolvedFilter==null && it.eventNodesLoaded && it.eventNodes.size==1 } }
            query("银舟未来"); check(model(sid).state.value.eventNodes.isEmpty()); waitNode("没有匹配的事件"); capture("04-child-future-excluded")
            query("青灯约定"); waitNode("标为未解决")
            val focused=java.util.concurrent.atomic.AtomicBoolean(false)
            val observer=kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { model(sid).state.collect { if(it.focusedMessageId==sourceId) focused.set(true) } }
            try { click("原文消息 #$sourceId"); waitState { focused.get() && nodes().none { it.isVisibleToUser && it.text?.toString()=="对话信息" } } } finally { observer.cancel() }
            check(model(sid).state.value.currentBranchId==child); waitNode(sourceBody); capture("05-child-source-location")
            switch("主线剧情","main"); openEvents(); query("青灯约定")
            check(model(sid).state.value.eventNodes.single().let { it.id==eventId && !it.resolved }); waitNode("标为已解决"); capture("06-parent-pending-preserved")
            query("银舟未来"); check(model(sid).state.value.eventNodes.single().id==futureEventId); waitNode("银舟未来"); capture("07-parent-future-present")
            closeDrawer(); switch("舟行子线",child); openEvents(); query("青灯约定"); check(model(sid).state.value.eventNodes.single().resolved); waitNode("标为未解决"); capture("08-child-state-restored")
            closeDrawer(); check(model(sid).state.value.inputText==draft)
            click("返回会话主页"); waitNode("故事库"); replaceInput(title); waitNode(title); clickContaining(title); waitNode("会话菜单")
            waitState { model(sid).state.value.isReady }; check(model(sid).state.value.currentBranchId==child); check(model(sid).state.value.inputText==draft)
            waitNode("子线独立正文"); capture("09-reentry-child-draft")
            openEvents(); query("青灯约定"); check(model(sid).state.value.eventNodes.single().resolved); waitNode("标为未解决"); capture("10-reentry-resolved")
            io { check(db.sessionEventNodeDao().getBySessionAndBranch(sid,"main").single { it.id==eventId }==parentEvent); check(db.messageDao().getMainMessagesForExport(sid,0,Long.MAX_VALUE,20)==originalMessages) }
            j.addProperty("phase","verified"); persist(j)
            output.resolve("event-inheritance.txt").writeText("allActionsUi=true\nchildOverrideExact=true\nparentOriginalRowExact=true\nfutureVisibilityExact=true\nsourceKeepsChild=true\nfocusObserved=true\nreentryDraftState=true\nparentMessagesExact=true\n")
        } catch(t:Throwable) {
            output.resolve("failure.txt").writeText(t.stackTraceToString()); output.resolve("nodes.txt").writeText(nodes().filter { it.isVisibleToUser }.joinToString("\n") { "text=${it.text} desc=${it.contentDescription} enabled=${it.isEnabled} click=${it.isClickable}" }); runCatching { capture("failure") }; throw t
        } finally { if(::activityScenario.isInitialized) activityScenario.close() }
    }
}

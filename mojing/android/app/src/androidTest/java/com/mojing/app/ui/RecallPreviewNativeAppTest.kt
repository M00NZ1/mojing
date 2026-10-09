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
class RecallPreviewNativeAppTest {
    private lateinit var activity: MainActivity
    private lateinit var activityScenario: androidx.test.core.app.ActivityScenario<MainActivity>
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
    @Test fun nativeRecallPreview() {
        guard(); check(!journalFile.exists())
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val j=JsonObject().apply {
            addProperty("run",run); addProperty("scenario",scenario); addProperty("phase","snapshot")
            add("originalConfig",JsonObject().apply { configKeys.forEach { k -> addProperty(k,if(preferences.contains(k)) preferences.getString(k,null) else null) } })
            add("drafts",JsonObject().apply { draftNames.forEach { n -> add(n,snapshot(context.getSharedPreferences(n,0))) } })
            add("branches",branches()); addProperty("digest",digest()); addProperty("mediaFilesDigest",mediaFilesDigest())
            val prefs=context.getSharedPreferences("message_search",0)
            val values=prefs.all.toSortedMap().map { (k,v) -> k+":"+when(v) { is Set<*> -> v.map { it.toString() }.sorted().joinToString("|"); else -> v.toString() } }.joinToString("\n")
            addProperty("searchPrefsDigest",java.security.MessageDigest.getInstance("SHA-256").digest(values.toByteArray()).joinToString("") { "%02x".format(it) })
        }; persist(j)
        try {
            val cid=io { db.characterDao().upsert(CharacterEntity(name="子线角色-$run",personaPrompt="SYNTHETIC_$run")) }; j.addProperty("character",cid); persist(j)
            val sid=io { db.sessionDao().insert(SessionEntity(title=title,creationRequestId=run)) }; j.addProperty("session",sid); persist(j)
            io { db.withTransaction {
                db.participantDao().upsert(SessionParticipantEntity(sessionId=sid,characterId=cid))
                db.sessionWorldDao().upsert(SessionWorldEntity(sessionId=sid,narratorEnabled=false,autoSedimentEnabled=false))
                val gid="native-$run"
                val old=db.messageDao().insert(MessageEntity(sessionId=sid,speakerType="character",characterId=cid,content="原生旧版-$run",swipeGroupId=gid,includeInContext=false,createdAt=1000))
                val mid=db.messageDao().insert(MessageEntity(sessionId=sid,speakerType="character",characterId=cid,content="原生时钟撤回预览-$run",swipeGroupId=gid,createdAt=2000))
                db.sessionMemorySegmentDao().insert(SessionMemorySegmentEntity(sessionId=sid,startMessageId=old,endMessageId=mid,summary="原生合成摘要-$run"))
                if(scenario=="protected") db.sessionBranchDao().insert(SessionBranchEntity(sessionId=sid,branchId="protected-$run",label="保留约定",sourceMessageId=mid))
            } }
            val intent=android.content.Intent(context,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("navigate_to","chat").putExtra("session_id",sid)
            activityScenario=androidx.test.core.app.ActivityScenario.launch(intent)
            activityScenario.onActivity { activity=it }
            val stores=RetainedChatSessions.stores
            val until=System.currentTimeMillis()+10000
            while(System.currentTimeMillis()<until) {
                val entries=stores.javaClass.getDeclaredField("entries").apply { isAccessible=true }.get(stores) as Map<*,*>
                val entry=entries[sid]
                val model=entry?.javaClass?.getDeclaredField("model")?.apply { isAccessible=true }?.get(entry) as? ChatViewModel
                if(model?.state?.value?.isReady==true && model.state.value.messages.any { it.content=="原生时钟撤回预览-$run" }) break
                Thread.sleep(50)
            }
            waitNode("会话菜单")
            val b0=checkNotNull(inst.uiAutomation.takeScreenshot())
            check(b0.width==1080 && b0.height==2400)
            try { FileOutputStream(output.resolve("native-before-tap.png")).use { b0.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { b0.recycle() }
            click("原生时钟撤回预览-$run")
            click("撤回"); waitNode("撤回这条消息？")
            val start=System.currentTimeMillis()
            waitNode(if(scenario=="protected") "需要保留这条消息" else "撤回后无法恢复")
            val confirm=waitNode("确认撤回")
            var button:android.view.accessibility.AccessibilityNodeInfo?=confirm
            while(button!=null && !button.isClickable && button.isEnabled) button=button.parent
            check(button!=null && button.isEnabled==(scenario=="recall"))
            val b=checkNotNull(inst.uiAutomation.takeScreenshot())
            try { FileOutputStream(output.resolve("native-preview.png")).use { check(b.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { b.recycle() }
            click(if(scenario=="protected") "保留并返回" else "取消")
            output.resolve("native-preview.txt").writeText("noComposeTestClock=true\nrealAccessibilityClick=true\nimpactMs=${System.currentTimeMillis()-start}\nconfirmationEnabled=${scenario=="recall"}\n")
        } catch(t:Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString())
            output.resolve("native-nodes.txt").writeText(nodes().filter { it.isVisibleToUser }.joinToString("\n") { "text=${it.text} desc=${it.contentDescription} click=${it.isClickable}" })
            val b=inst.uiAutomation.takeScreenshot(); if(b!=null) try { FileOutputStream(output.resolve("native-failure.png")).use { b.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { b.recycle() }
            throw t }
        finally { if(::activityScenario.isInitialized) activityScenario.close() }
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
    @Test fun nativeRecallFlow() {
        guard(); check(!journalFile.exists())
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
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

            val intent=android.content.Intent(context,MainActivity::class.java).putExtra("navigate_to","chat").putExtra("session_id",sid)
            activityScenario=androidx.test.core.app.ActivityScenario.launch(intent); activityScenario.onActivity { activity=it }
            waitState { runCatching { model(sid).state.value.isReady }.getOrDefault(false) }; waitNode("会话菜单")
            val draft="撤回未发送草稿-$run"
            replaceInput(draft); waitState { model(sid).state.value.inputText==draft }
            inst.runOnMainSync { activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken,0); activity.currentFocus?.clearFocus() }
            waitState { activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())!=true }
            capture("00-before-old-tap"); tap(450f,480f); click("收藏")
            waitState { oldId in model(sid).state.value.bookmarkedMessageIds && model(sid).state.value.bookmarkBusyIds.isEmpty() }; capture("01-old-bookmarked")
            val now=android.os.SystemClock.uptimeMillis()
            for(step in 0..15) {
                val action=if(step==0) android.view.MotionEvent.ACTION_DOWN else if(step==15) android.view.MotionEvent.ACTION_UP else android.view.MotionEvent.ACTION_MOVE
                val event=android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),action,950f-step*50,480f,0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { check(inst.uiAutomation.injectInputEvent(event,true)); if(step<15) Thread.sleep(20) } finally { event.recycle() }
            }
            waitState { model(sid).state.value.displayLines.any { it.swipeGroupId==gid && it.selectedMessage().id==newId } }; capture("02-new-adopted")
            fun selected(id:Long) {
                check(model(sid).state.value.currentBranchId=="main")
                check(model(sid).state.value.displayLines.any { it.swipeGroupId==gid && it.selectedMessage().id==id })
                io { check(db.messageDao().getBranchSwipeSelection(sid,"main",gid)?.selectedMessageId==id) }
            }
            selected(newId)
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
            fun recallDialog() {
                click(newBody); click("撤回"); waitNode("撤回这条消息？")
                waitNode(if(scenario=="protected") "需要保留这条消息" else "同组的其他回复会保留")
            }
            recallDialog()
            if(scenario=="protected") {
                waitNode("这条消息或其附属内容是故事线、检查点或编辑版本的来源")
                waitNode("故事线 · 保留约定")
                var confirm:android.view.accessibility.AccessibilityNodeInfo?=waitNode("确认撤回")
                while(confirm!=null && confirm.isEnabled && !confirm.isClickable) confirm=confirm.parent
                check(confirm!=null && !confirm.isEnabled); capture("03-protected-reason"); click("保留并返回")
                waitState { nodes().none { it.isVisibleToUser && it.text?.toString()=="撤回这条消息？" } }
                check(digest()==beforeDialog); selected(newId); capture("04-protected-return")
            } else {
                waitNode("将重新整理 1 段自动摘要"); capture("03-recall-impact"); click("取消")
                waitState { nodes().none { it.isVisibleToUser && it.text?.toString()=="撤回这条消息？" } }
                check(digest()==beforeDialog); selected(newId); check(model(sid).state.value.inputText==draft); capture("04-cancel-preserved")
                recallDialog(); click("确认撤回")
                waitState { model(sid).state.value.displayLines.any { it.selectedMessage().id==oldId } && nodes().none { it.isVisibleToUser && it.text?.toString()=="撤回这条消息？" } }
                selected(oldId)
                io {
                    check(db.messageDao().getById(newId)==null); check(db.messageDao().getById(oldId)==originalOld)
                    fun count(table:String,id:Long)=db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table WHERE id=?",arrayOf(id)).use { c -> check(c.moveToFirst()); c.getInt(0) }
                    check(count("session_memory_segments",segmentId)==0); check(count("session_event_nodes",eventId)==0)
                    val cs=db.characterStateDao().getBySessionAndCharacter(sid,cid)!!
                    check(cs.id==stateId && !cs.snapshotIsValid && cs.lastSnapshotAttemptUserMessageId==0L && cs.dynamicStateJson.contains(run))
                    check(db.sessionMemoryCorrectionDao().getById(sid,correctionId)?.content=="保留手动纠正-$run")
                    val memory=db.sessionContextMemoryDao().getBySessionAndBranch(sid,"main")!!
                    check(!memory.isValid && memory.revision==31L && memory.globalSummary=="合成当前记忆-$run")
                }
                capture("05-fallback-old"); click("会话设置与资料"); click("书签"); waitNode(oldPhrase); capture("06-bookmark-list")
                val located=java.util.concurrent.atomic.AtomicBoolean(false)
                val locatorObserver=kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    model(sid).state.collect { if(it.focusedMessageId==oldId) located.set(true) }
                }
                try {
                    clickContaining(oldPhrase)
                    waitState { model(sid).state.value.let { !it.isDrawerOpen && it.bookmarkLocatingId==null } }
                    check(located.get())
                } finally { locatorObserver.cancel() }
                check(model(sid).state.value.bookmarkReadOnlyMessage==null); selected(oldId); capture("07-bookmark-normal-location")
                click("会话菜单"); click("搜索消息"); waitNode("全部故事线")
                fun search(phrase:String,total:Int) {
                    replaceInput(phrase); click("搜索")
                    waitState { searchModel().state.value.let { it.completedQuery==phrase && !it.searching && !it.indexing && !it.visibilityIndexing && !it.counting && it.totalMatches==total && it.hits.size==total } }
                    check(searchModel().state.value.error==null); selected(oldId)
                }
                search(newPhrase,0); waitNode("没有找到匹配消息"); capture("08-recalled-search-empty"); search(oldPhrase,1)
                check(searchModel().state.value.hits.single().message.id==oldId); waitNode("她把旧信留在桥边"); capture("09-old-search-hit")
                clickContaining(oldPhrase)
                waitState { searchModel().state.value.let { !it.contextLoadingBefore && !it.contextLoadingAfter && it.selectedMessageId==oldId && it.contextMessages.any { row -> row.id==oldId } } }
                check(searchModel().state.value.contextMessages.single { it.id==oldId }==originalOld); check(searchModel().state.value.contextMessages.none { it.id==newId })
                waitNode("1 / 1"); waitState { nodes().none { it.isVisibleToUser && it.text?.toString()?.contains("正在加载")==true } }; capture("10-old-search-context")
                inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                waitState { searchModel().state.value.selectedMessageId==null }
                inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); waitNode("会话菜单")
            }
            val expected=if(scenario=="protected") newId else oldId
            selected(expected); check(model(sid).state.value.inputText==draft)
            click("返回会话主页"); waitNode("故事库"); replaceInput(title); waitNode(title)
            clickContaining(title); waitNode("会话菜单"); waitState { model(sid).state.value.isReady }; selected(expected)
            check(model(sid).state.value.inputText==draft)
            waitNode(if(scenario=="protected") newPhrase else oldPhrase)
            waitState { nodes().none { it.isVisibleToUser && it.text?.toString()?.contains("正在加载对话")==true } }
            capture("11-reentry-draft")
            if(scenario=="protected") check(digest()==beforeDialog)
            j.addProperty("phase","verified"); persist(j)
            output.resolve("native-full.txt").writeText("noComposeTestClock=true\nallActionsUi=true\nnoDaoPreviewPreRead=true\nscenario=$scenario\ncancelOrReturnExact=true\nremainingAdoptionOrProtectionExact=true\ndraftReentry=true\n")
        } catch(t:Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString()); runCatching { capture("native-full-failure") }; throw t }
        finally { if(::activityScenario.isInitialized) activityScenario.close() }
    }
}

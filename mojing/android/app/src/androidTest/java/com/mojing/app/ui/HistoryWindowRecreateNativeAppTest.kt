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
import androidx.lifecycle.viewModelScope
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

/** Formal bookmark reading through MainActivity; each UUID has independent rollback. */
class HistoryWindowRecreateNativeAppTest {
    private lateinit var activity: MainActivity
    private lateinit var activityScenario: androidx.test.core.app.ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("recallAdoptionRun").orEmpty().also { UUID.fromString(it) }
    private val scenario get() = args.getString("scenario").orEmpty()
    private val title get() = "参与角色${run.take(8)}"
    private val foundation get() = "SYNTHETIC_$run\n"+(1..24).joinToString("\n\n") { "第${it}段：旧港口的灯塔向西照亮海岸。居民遵守既定日常规则，来信者按世界原有设定行动；这些是用于阅读恢复的合成世界资料。" }
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
        val until=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<until) {
            var n:android.view.accessibility.AccessibilityNodeInfo?=waitNode(text,true)
            while(n!=null && !n.isClickable) n=n.parent
            if(n?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true) return
            Thread.sleep(50)
        };error("click failed: $text")
    }
    private fun worldSaving(vm:ChatViewModel):Boolean = runCatching {
        vm.state.value.javaClass.getMethod("getWorldSettingSaving").invoke(vm.state.value) as Boolean
    }.getOrDefault(false)
    private fun clickSwitch(label:String) {
        val text=waitNode(label,true)
        fun switches(node:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> {
            val found=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            if(node.isCheckable) found.add(node)
            for(i in 0 until node.childCount) node.getChild(i)?.let { found.addAll(switches(it)) }
            return found
        }
        var parent=text.parent
        repeat(3) {
            val found=parent?.let(::switches).orEmpty().filter { it.isVisibleToUser }
            if(found.size==1) { check(found.single().isEnabled);check(found.single().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));return }
            parent=parent?.parent
        }
        val labelBounds=android.graphics.Rect();text.getBoundsInScreen(labelBounds)
        val aligned=nodes().filter { node ->
            val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
            node.isVisibleToUser && node.isCheckable && node.isClickable &&
                bounds.left >= labelBounds.right && bounds.centerY() in labelBounds.top..labelBounds.bottom
        }
        check(aligned.size==1) { "label has no unique checkable switch: $label" }
        check(aligned.single().isEnabled)
        check(aligned.single().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
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
    private fun scrollTo(text:String) {
        repeat(24) {
            if(nodes().any { it.isVisibleToUser && it.text?.toString()==text }) return
            val scroll=nodes().firstOrNull { it.isVisibleToUser && it.isScrollable } ?: error("No scroll surface")
            check(scroll.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            inst.waitForIdleSync();Thread.sleep(80)
        }
        error("scroll target not visible: $text")
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
    @Test fun historyWindowRecreation() {
        guard(); check(!journalFile.exists())
        val before=args.getString("before")=="true"
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val j=JsonObject().apply {
            addProperty("run",run); addProperty("scenario",scenario); addProperty("phase","snapshot")
            add("originalConfig",JsonObject().apply { configKeys.forEach { k -> addProperty(k,if(preferences.contains(k)) preferences.getString(k,null) else null) } })
            add("drafts",JsonObject().apply { draftNames.forEach { n -> add(n,snapshot(context.getSharedPreferences(n,0))) } })
            addProperty("thinkPresent",preferences.contains("allow_session_think_max"));addProperty("thinkValue",preferences.getBoolean("allow_session_think_max",false));add("branches",branches()); addProperty("digest",digest()); addProperty("mediaFilesDigest",mediaFilesDigest()); addProperty("searchPrefsDigest",searchPrefsDigest())
        }; persist(j)
        try {
            val eid=io { db.encyclopediaDao().upsert(EncyclopediaEntity(name="百科-$run",worldPrompt=foundation)) };j.addProperty("encyclopedia",eid);persist(j)
            val cid=io { db.characterDao().upsert(CharacterEntity(name="来信者-$run",personaPrompt="SYNTHETIC_$run",boundEncyclopediaId=eid)) };j.addProperty("character",cid);persist(j)
            val candidates=com.google.gson.JsonArray();j.add("candidates",candidates);persist(j)
            val candidate=io { db.characterDao().upsert(CharacterEntity(name="候选-$run-01",personaPrompt="SYNTHETIC_$run",boundEncyclopediaId=eid)) };candidates.add(candidate);persist(j)
            val sid=io { db.sessionDao().insert(SessionEntity(title=title,creationRequestId=run)) };j.addProperty("session",sid);persist(j)
            val mid=io { db.withTransaction {
                db.participantDao().upsert(SessionParticipantEntity(sessionId=sid,characterId=cid))
                db.sessionWorldDao().upsert(SessionWorldEntity(sessionId=sid,encyclopediaId=eid,narratorEnabled=false,gameplayMode="对话",autoSedimentEnabled=false))
                db.messageDao().insert(MessageEntity(sessionId=sid,speakerType="user",content="原文-${run.take(8)}"))
            } }
            val target="participant-$run";val targetLabel="角色支线-${run.take(8)}"
            io { db.sessionBranchDao().insert(SessionBranchEntity(sessionId=sid,branchId=target,label=targetLabel,sourceMessageId=mid,parentBranchId="main",createdAt=1001L)) }
            val tid=io { db.messageDao().insert(MessageEntity(sessionId=sid,branchId=target,speakerType="character",characterId=cid,content="支线原文-${run.take(8)}")) }
            // Main summary is after the fork: it must not be inherited by the child.
            val mainSummarySource=io { db.messageDao().insert(MessageEntity(sessionId=sid,speakerType="user",content="主线后续原文-${run.take(8)}")) }
            val originalMessages=io { listOf(db.messageDao().getById(mid),db.messageDao().getById(tid),db.messageDao().getById(mainSummarySource)) }
            val originalWorld=io { db.sessionWorldDao().getBySession(sid) }
            val originalCharacters=io { listOf(db.characterDao().getById(cid),db.characterDao().getById(candidate)) }
            val originalEncyclopedia=io { db.encyclopediaDao().getById(eid) }
            val originalBranches=io { db.sessionBranchDao().getBySession(sid) }.sortedBy { it.id }
            val originalSession=io { db.sessionDao().getById(sid) }!!
            val originalParticipant=io { db.participantDao().getBySession(sid) }.single()
            activityScenario=androidx.test.core.app.ActivityScenario.launch(android.content.Intent(context,MainActivity::class.java).putExtra("navigate_to","chat").putExtra("session_id",sid));activityScenario.onActivity { activity=it }
            waitState { runCatching { model(sid).state.value.isReady }.getOrDefault(false) };waitNode("会话菜单")
            val draft="未发送-$run";replaceInput(draft);waitState { model(sid).state.value.inputText==draft }
            fun hideIme() { inst.runOnMainSync { activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken,0);activity.currentFocus?.clearFocus() };waitState { activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())!=true } }
            val origin=if(scenario=="inherited") target else "main"
            val sourceIds=(1..220).associateWith { ordinal -> io { db.messageDao().insert(MessageEntity(sessionId=sid,branchId=origin,speakerType="user",content="收藏来源${ordinal.toString().padStart(3,'0')}-${run.take(8)}")) } }
            val sourceRows=io { sourceIds.values.map { db.messageDao().getById(it) } }
            val bookmarkIds=(1..220).associateWith { ordinal -> io { db.bookmarkDao().insert(MessageBookmarkEntity(sessionId=sid,messageId=sourceIds.getValue(ordinal),note="来信${ordinal.toString().padStart(3,'0')}-${run.take(8)}",createdAt=1000L+ordinal)) } }
            if(origin!="main") { hideIme();click("会话菜单");click("故事线");waitNode("选择故事线",true);clickContaining(targetLabel);waitState { model(sid).state.value.currentBranchId==target && model(sid).state.value.branchNavigationLabel==null } }
            val originalBookmarks=io { db.bookmarkDao().getFirstPage(sid,221) }
            fun unchanged() {
                check(io { db.sessionDao().getById(sid) }==originalSession)
                check(io { db.participantDao().getBySession(sid) }.single()==originalParticipant)
                check(io { db.bookmarkDao().getFirstPage(sid,221) }==originalBookmarks)
                check(io { sourceIds.values.map { db.messageDao().getById(it) } }==sourceRows)
                check(model(sid).state.value.currentBranchId==origin && model(sid).state.value.inputText==draft)
                check(io { listOf(db.messageDao().getById(mid),db.messageDao().getById(tid),db.messageDao().getById(mainSummarySource)) }==originalMessages)
                check(io { db.sessionWorldDao().getBySession(sid) }==originalWorld)
                check(io { listOf(db.characterDao().getById(cid),db.characterDao().getById(candidate)) }==originalCharacters)
                check(io { db.encyclopediaDao().getById(eid) }==originalEncyclopedia)
                check(io { db.sessionBranchDao().getBySession(sid) }.sortedBy { it.id }==originalBranches)
            }
            hideIme();click("会话设置与资料");waitNode("对话信息",true);click("书签")
            waitState { model(sid).state.value.let { it.bookmarksLoaded && it.bookmarks.size==40 } }
            waitNode("来信220-${run.take(8)}",true);capture("01-first-page");unchanged()
            repeat(4) { page ->
                scrollTo("加载更早收藏");click("加载更早收藏")
                val last=bookmarkIds.getValue(220-(page+2)*40+1)
                waitState { model(sid).state.value.let { it.bookmarksLoaded && !it.bookmarksLoadingMore && it.bookmarks.lastOrNull()?.id==last } }
            }
            check(model(sid).state.value.bookmarksBeforeId==bookmarkIds.getValue(141))
            check(model(sid).state.value.bookmarksBeforeCreatedAt==1141L)
            val window=model(sid).state.value.bookmarks
            val anchor="来信${30.toString().padStart(3,'0')}-${run.take(8)}"
            repeat(24) {
                if(nodes().any { it.isVisibleToUser && it.text?.toString()==window.first().note }) return@repeat
                val scroll=nodes().first { it.isVisibleToUser && it.isScrollable }
                scroll.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);inst.waitForIdleSync();Thread.sleep(60)
            }
            scrollTo(anchor);waitNode(anchor,true)
            capture("01-bookmark-target");unchanged()
            clickContaining(anchor)
            val targetId=sourceIds.getValue(30)
            waitState { model(sid).state.value.let { it.bookmarkLocatingId==null && it.messages.any { row -> row.id==targetId } && it.focusedMessageId==null && it.hasNewerMessages } }
            val originalWindow=model(sid).state.value.messages
            val content="收藏来源030-${run.take(8)}"
            waitNode(content,true);capture("02-history-target");unchanged()
            val scroll=nodes().first { it.isVisibleToUser && it.isScrollable }
            check(scroll.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            inst.waitForIdleSync();Thread.sleep(150)
            val readingContent=nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.first { it.startsWith("收藏来源") }
            capture("02b-manual-reading")
            val beforeRect=android.graphics.Rect().also { waitNode(readingContent,true).getBoundsInScreen(it) }
            val previous=model(sid)
            activityScenario.recreate();activityScenario.onActivity { activity=it }
            waitState { runCatching { model(sid).state.value.let { it.isReady && it.currentBranchId==origin } }.getOrDefault(false) }
            check(model(sid)!==previous)
            if(before) {
                check(model(sid).state.value.messages.size==80 && model(sid).state.value.messages.none { it.id==targetId })
                capture("03-before-history-lost");unchanged()
                output.resolve("verified.txt").writeText("twoHundredTwentyRealSources=true\nformalBookmarkJump=true\nfocusConsumedBeforeRecreate=true\nhistoryWindowLostAfterRecreate=true\noriginalRowsDraftBranchExact=true\n")
                j.addProperty("phase","before-verified");persist(j);return
            }
            check(model(sid).state.value.messages==originalWindow)
            val afterRect=android.graphics.Rect().also { waitNode(readingContent,true).getBoundsInScreen(it) }
            check(kotlin.math.abs(beforeRect.top-afterRect.top)<=2) { "Anchor changed: $beforeRect -> $afterRect" }
            capture("03-history-restored");unchanged()
            // Explicit latest action clears the window before the next recreation.
            click("回到最新")
            waitState { model(sid).state.value.let { !it.hasNewerMessages && !it.isLoadingHistory && it.messages.last().id==sourceIds.getValue(220) } }
            waitNode("收藏来源220-${run.take(8)}",true);capture("04-latest-reset");unchanged()
            activityScenario.recreate();activityScenario.onActivity { activity=it }
            waitState { runCatching { model(sid).state.value.isReady }.getOrDefault(false) }
            check(!model(sid).state.value.hasNewerMessages && model(sid).state.value.messages.none { it.id==targetId })
            waitNode("收藏来源220-${run.take(8)}",true);capture("05-latest-reset-restored");unchanged()
            output.resolve("verified.txt").writeText("twoHundredTwentyRealSources=true\nformalBookmarkJump=true\nhistoryWindowRestored=true\nstableAnchorRestored=true\noriginalRowsDraftBranchExact=true\n")
            j.addProperty("phase","verified");persist(j)
        } catch(t:Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString());runCatching { capture("failure") };throw t }
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
        if (j.has("candidates")) io { j.getAsJsonArray("candidates").forEach { value ->
            db.characterDao().getById(value.asLong)?.let { check(it.name.startsWith("候选-$run-") && it.personaPrompt == "SYNTHETIC_$run" && it.boundEncyclopediaId == j["encyclopedia"].asLong); db.characterDao().delete(it.id) }
        } }
        if (j.has("encyclopedia")) io { db.encyclopediaDao().getById(j["encyclopedia"].asLong)?.let { check(it.name == "百科-$run" && it.worldPrompt == foundation); db.encyclopediaDao().delete(it.id) } }
        val thinkEdit=preferences.edit();if(j["thinkPresent"].asBoolean) thinkEdit.putBoolean("allow_session_think_max",j["thinkValue"].asBoolean) else thinkEdit.remove("allow_session_think_max");check(thinkEdit.commit())
        check(preferences.contains("allow_session_think_max")==j["thinkPresent"].asBoolean);check(preferences.getBoolean("allow_session_think_max",false)==j["thinkValue"].asBoolean)
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

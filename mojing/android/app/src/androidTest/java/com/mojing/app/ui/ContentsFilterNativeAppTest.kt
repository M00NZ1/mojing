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
class ContentsFilterNativeAppTest {
    private lateinit var activity: MainActivity
    private lateinit var activityScenario: androidx.test.core.app.ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("recallAdoptionRun").orEmpty().also { UUID.fromString(it) }
    private val scenario get() = args.getString("scenario").orEmpty()
    private val title get() = "目录筛选${run.take(8)}"
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
    @Test fun renameDraftRecreation() {
        guard(); check(!journalFile.exists())
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
            val oldTitle="第一章 北塔"
            val newTitle="第一章 雨港-${run.take(8)}"
            val body="$oldTitle\n\n原文保持-$run"
            val mid=io { db.withTransaction {
                db.participantDao().upsert(SessionParticipantEntity(sessionId=sid,characterId=cid))
                db.sessionWorldDao().upsert(SessionWorldEntity(sessionId=sid,narratorEnabled=false,gameplayMode="小说创作",autoSedimentEnabled=false))
                var target=0L
                for(number in 1..70) {
                    val chapterTitle=if(number==7) oldTitle else "第${number}章 海路"
                    val id=db.messageDao().insert(MessageEntity(sessionId=sid,speakerType="narrator",content=if(number==7) body else "$chapterTitle\n\n海路原文-$number-$run",structuredContentJson="{\"chapter_number\":$number,\"chapter_title\":\"$chapterTitle\",\"chapter_incomplete\":false,\"custom\":\"$run\"}"))
                    if(number==7) target=id
                }
                target
            } }
            val original=io { db.messageDao().getById(mid) }!!
            val child="inherited-$run"
            if(scenario=="inherited") io { db.sessionBranchDao().insert(SessionBranchEntity(sessionId=sid,branchId=child,label="继承雨港",sourceMessageId=io { db.messageDao().getVisibleStoryContentsBefore(sid,"main",Long.MAX_VALUE,1).single().id },parentBranchId="main")) }
            activityScenario=androidx.test.core.app.ActivityScenario.launch(android.content.Intent(context,MainActivity::class.java).putExtra("navigate_to","chat").putExtra("session_id",sid)); activityScenario.onActivity { activity=it }
            waitState { runCatching { model(sid).state.value.isReady }.getOrDefault(false) }; waitNode("会话菜单")
            if(scenario=="inherited") {
                click("会话菜单"); click("故事线"); waitNode("选择故事线"); click("继承雨港")
                waitState { model(sid).state.value.currentBranchId==child && model(sid).state.value.branchNavigationLabel==null }
            }
            val branch=if(scenario=="inherited") child else "main"
            val draft="目录未发送草稿-$run"; replaceInput(draft); waitState { model(sid).state.value.inputText==draft }
            fun hideIme() {
                inst.runOnMainSync { activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken,0); activity.currentFocus?.clearFocus() }
                waitState { activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())!=true }
            }
            fun contentsModel():com.mojing.app.ui.chat.contents.StoryContentsViewModel {
                fun values(store:androidx.lifecycle.ViewModelStore):Collection<*> = (store.javaClass.getDeclaredField("map").apply { isAccessible=true }.get(store) as Map<*,*>).values
                val nav=values(activity.viewModelStore).single { it?.javaClass?.name=="androidx.navigation.NavControllerViewModel" }!!
                val stores=nav.javaClass.getDeclaredField("viewModelStores").apply { isAccessible=true }.get(nav) as Map<*,*>
                return stores.values.flatMap { values(it as androidx.lifecycle.ViewModelStore) }.filterIsInstance<com.mojing.app.ui.chat.contents.StoryContentsViewModel>().single()
            }
            hideIme(); click("小说目录")
            waitState { contentsModel().state.value.catalogLoaded && !contentsModel().state.value.isLoading }
            check(contentsModel().state.value.entries.size==40 && contentsModel().state.value.entries.none { it.messageId==mid })
            replaceInput("北塔"); hideIme()
            waitNode("修改章节名称：$oldTitle",true)
            check(contentsModel().state.value.query=="北塔" && contentsModel().state.value.entries.single().messageId==mid)
            capture("01-old-chapter-search")
            click("修改章节名称：$oldTitle"); waitNode("章节名称",true); replaceInput(newTitle); hideIme()
            activityScenario.recreate(); activityScenario.onActivity { activity=it }
            waitNode("章节名称",true)
            check(nodes().filter { it.isVisibleToUser && it.isEditable }.single().text.toString()==newTitle)
            waitState { contentsModel().state.value.catalogLoaded && !contentsModel().state.value.isLoading }
            capture("02-restored-editor")
            check(io { db.messageDao().getById(mid) }==original)
            click("取消"); waitNode("搜索小说目录",true)
            if(args.getString("before")=="true") {
                check(contentsModel().state.value.query.isEmpty())
                check(contentsModel().state.value.entries.size==40 && contentsModel().state.value.entries.none { it.messageId==mid })
                capture("03-before-search-lost")
                output.resolve("before.txt").writeText("realActivityRecreate=true\neditorInputPreserved=true\nsearchIntentLost=true\ndeepTargetAbsent=true\ncancelNoWrite=true\n")
            } else {
                waitNode("修改章节名称：$oldTitle",true)
                check(contentsModel().state.value.query=="北塔" && contentsModel().state.value.entries.single().messageId==mid)
                capture("03-cancel-search-retained")
                click("修改章节名称：$oldTitle"); waitNode("章节名称",true); replaceInput(newTitle); hideIme()
                activityScenario.recreate(); activityScenario.onActivity { activity=it }; waitNode("章节名称",true)
                check(nodes().filter { it.isVisibleToUser && it.isEditable }.single().text.toString()==newTitle)
                click("修改原章名称")
                waitState { !model(sid).state.value.novelMetadataSaving && io { db.messageDao().getById(mid)?.content?.startsWith(newTitle)==true } }
                waitNode("没有匹配的目录，试试其他关键词。",true)
                check(contentsModel().state.value.query=="北塔" && contentsModel().state.value.entries.isEmpty())
                capture("04-old-filter-reapplied")
                replaceInput("雨港"); hideIme(); waitNode("修改章节名称：$newTitle",true)
                check(contentsModel().state.value.query=="雨港" && contentsModel().state.value.entries.single().messageId==mid)
                capture("05-new-filter-found")
                val saved=io { db.messageDao().getById(mid) }!!
                check(saved.content==body.replaceFirst(oldTitle,newTitle) && saved.branchId=="main")
                val json=JsonParser.parseString(saved.structuredContentJson).asJsonObject
                check(json["custom"].asString==run && json["chapter_number"].asInt==7 && !json["chapter_incomplete"].asBoolean)
                check(io { db.sessionBranchDao().getBySession(sid) }.size==if(scenario=="inherited") 1 else 0)
                clickContaining(newTitle); waitNode("消息输入",true)
                waitState { model(sid).state.value.messages.any { it.id==mid } }
                check(model(sid).state.value.currentBranchId==branch && model(sid).state.value.inputText==draft)
                waitNode("原文保持-$run"); capture("06-formal-old-chapter-location")
                click("返回会话主页"); waitNode("故事库"); replaceInput(title); clickContaining(title); waitNode("会话菜单")
                waitState { model(sid).state.value.isReady }
                check(model(sid).state.value.currentBranchId==branch && model(sid).state.value.inputText==draft)
                click("小说目录"); waitNode("搜索小说目录",true); replaceInput("雨港"); hideIme(); waitNode("修改章节名称：$newTitle",true)
                check(io { db.messageDao().getById(mid) }==saved); capture("07-reentry-draft-and-title")
                output.resolve("verified.txt").writeText("realActivityRecreate=true\nsearchIntentRetained=true\ncancelNoWrite=true\noldFilterZeroAfterSave=true\nnewFilterOneExactId=true\nformalDeepChapterLocation=true\nbranchBodyMetadataDraftExact=true\n")
            }
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

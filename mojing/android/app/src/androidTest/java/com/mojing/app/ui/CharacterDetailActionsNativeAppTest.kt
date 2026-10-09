package com.mojing.app.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojing.app.MainActivity
import com.mojing.app.data.StoryOpeningInputDraftStore
import com.mojing.app.data.local.entity.*
import com.mojing.app.ui.chat.RetainedChatSessions
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Fixed detail actions through the real app, with UUID-owned fixtures and independent rollback. */
class CharacterDetailActionsNativeAppTest {
    private lateinit var activity: MainActivity
    private lateinit var activityScenario: androidx.test.core.app.ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("detailActionsRun").orEmpty().also { UUID.fromString(it) }
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "character-detail-actions-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "character-detail-actions-$run").apply { mkdirs() }
    private val preferences get() = com.mojing.app.data.SecureStorage().apply { init(context) }.let { storage -> storage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(storage) } as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode", "theme_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "character-detail-actions-$run.pending")
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
    private fun waitState(condition:()->Boolean) {
        val until=System.currentTimeMillis()+20000
        while(System.currentTimeMillis()<until) { if(condition()) return; Thread.sleep(30) }; error("native state timeout")
    }
    private fun searchPrefsDigest():String {
        val values=context.getSharedPreferences("message_search",0).all.toSortedMap().map { (k,v) -> k+":"+when(v) { is Set<*> -> v.map { it.toString() }.sorted().joinToString("|"); else -> v.toString() } }.joinToString("\n")
        return java.security.MessageDigest.getInstance("SHA-256").digest(values.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun replaceInput(text:String) {
        var edit:android.view.accessibility.AccessibilityNodeInfo?=null
        waitState { edit=nodes().firstOrNull { it.isVisibleToUser && it.isEditable }; edit!=null }
        check(edit!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text) }))
    }



    private fun back() {
        inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
    }

    private fun guard() {
        check(args.getString("detailActionsCapture")=="true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration()==null)
    }
    private fun capture(label:String) {
        inst.waitForIdleSync()
        inst.uiAutomation.waitForIdle(300,5000)
        // Accessibility becomes current before RenderThread has committed the new frame.
        // Await actual draw commits rather than sleeping or changing the app state.
        repeat(2) {
            val committed=java.util.concurrent.CountDownLatch(1)
            inst.runOnMainSync {
                val view=activity.window.decorView
                view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                view.invalidate()
            }
            check(committed.await(5,java.util.concurrent.TimeUnit.SECONDS)) { "frame not committed" }
        }
        val bitmap=checkNotNull(inst.uiAutomation.takeScreenshot())
        try { FileOutputStream(output.resolve("$label.png")).use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { bitmap.recycle() }
        output.resolve("$label-shell.png").writeBytes(
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("screencap -p")).use { it.readBytes() },
        )
        val title=nodes().firstOrNull { it.text?.toString()=="角色详情" }
        output.resolve("$label-window.txt").writeText("detailTitleVisible=${title?.isVisibleToUser}\ntitleBounds="+
            title?.let { n -> android.graphics.Rect().also { n.getBoundsInScreen(it) }.toShortString() })
    }
    private fun characterIds():Set<Long> = io {
        buildSet { db.openHelper.readableDatabase.query("SELECT id FROM characters ORDER BY id").use { c -> while(c.moveToNext()) add(c.getLong(0)) } }
    }
    private fun footerNode(label:String):android.view.accessibility.AccessibilityNodeInfo {
        waitNode(label,true)
        return nodes().filter { it.isVisibleToUser && it.text?.toString()==label }.maxBy { n -> android.graphics.Rect().also { n.getBoundsInScreen(it) }.top }
    }
    private fun clickFooter(label:String) {
        var node=footerNode(label)
        while(!node.isClickable) node=checkNotNull(node.parent)
        check(node.isEnabled && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun sessionIds():Set<Long> = io { buildSet {
        db.openHelper.readableDatabase.query("SELECT id FROM sessions ORDER BY id").use { c -> while(c.moveToNext()) add(c.getLong(0)) }
    } }
    private fun assertFooter(label:String="收藏") {
        val rects=listOf("编辑","开始对话",label).map { text ->
            var n=footerNode(text);while(!n.isClickable) n=checkNotNull(n.parent)
            check(n.isEnabled)
            android.graphics.Rect().also { n.getBoundsInScreen(it) }
        }
        check(rects.all { it.top==rects.first().top && it.bottom==rects.first().bottom })
        check(rects.zipWithNext().all { (a,b) -> a.right<=b.left })
        check(rects.all { it.height()>=48*context.resources.displayMetrics.density })
    }
    private fun scrollToStories() {
        repeat(30) {
            if(nodes().any { it.isVisibleToUser && it.text?.toString()=="相关故事" }) return
            val n=nodes().firstOrNull { it.isVisibleToUser && it.isScrollable } ?: error("detail scroll missing")
            check(n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            inst.waitForIdleSync(); Thread.sleep(80)
        };error("stories not reached")
    }
    @Test fun longDetailFavoriteEditAndLocalChat() {
        guard();check(!journalFile.exists())
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val fixtureName="详情角色-${run.take(8)}"
        val persona="SYNTHETIC_$run\n"+(1..32).joinToString("\n") { "人物设定第${it}段：谨慎观察、记住约定，在长篇故事中保留自己的判断。" }
        val j=JsonObject().apply {
            addProperty("run",run);addProperty("digest",digest());addProperty("mediaFilesDigest",mediaFilesDigest());addProperty("searchPrefsDigest",searchPrefsDigest())
            add("branches",branches());add("drafts",JsonObject().apply { draftNames.forEach { add(it,snapshot(context.getSharedPreferences(it,0))) } })
            add("originalIds",com.google.gson.Gson().toJsonTree(characterIds()))
            add("originalSessions",com.google.gson.Gson().toJsonTree(sessionIds()))
            add("originalConfig",JsonObject().apply { configKeys.forEach { addProperty(it,preferences.getString(it,null)) } })
        };persist(j)
        io { db.withTransaction {
            val id=db.characterDao().upsert(CharacterEntity(name=fixtureName,personaPrompt=persona))
            j.addProperty("fixture",id);j.addProperty("persona",persona);persist(j)
            val stories=mutableListOf<Long>()
            repeat(6) { index ->
                val sid=db.sessionDao().insert(SessionEntity(title="合成相关故事-$index-${run.take(8)}",summary="只用于固定动作验收"))
                db.participantDao().upsert(SessionParticipantEntity(sessionId=sid,characterId=id))
                stories.add(sid);j.add("stories",com.google.gson.Gson().toJsonTree(stories));persist(j)
            }
        } }
        // Local-only entry; also block configured credentials without exposing their values.
        check(preferences.edit().putString("theme_mode",args.getString("captureTheme")?:"dark")
            .putString("public_api_key","").putString("model_platforms_v1","[]").putString("active_model_platform","").commit())
        try {
            activityScenario=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            activityScenario.onActivity { activity=it }
            click("创作");click("角色");waitNode("搜索角色名或人物设定",true)
            replaceInput(run.take(8));click(fixtureName);waitNode("角色详情",true)
            waitState { nodes().any { it.isVisibleToUser && it.text?.toString()=="开始对话" } }
            assertFooter();capture("01-long-detail-fixed-actions")
            val initial=android.graphics.Rect().also { footerNode("开始对话").getBoundsInScreen(it) }
            scrollToStories();assertFooter();capture("02-related-stories-fixed-actions")
            check(android.graphics.Rect().also { footerNode("开始对话").getBoundsInScreen(it) }==initial)
            clickFooter("收藏");waitNode("已收藏",true)
            waitState { io { db.characterDao().getById(j["fixture"].asLong)?.favorite==true } }
            assertFooter("已收藏");capture("03-favorite-persisted")
            clickFooter("编辑");waitNode("编辑角色",true)
            replaceInput("$fixtureName-已编辑");click("保存")
            waitState { io { db.characterDao().getById(j["fixture"].asLong)?.name=="$fixtureName-已编辑" } }
            waitNode("已保存",true);capture("04-real-editor-saved");click("返回")
            waitNode("角色详情",true);waitNode("已收藏",true);assertFooter("已收藏");capture("05-returned-detail-actions")
            val row=checkNotNull(io { db.characterDao().getById(j["fixture"].asLong) })
            check(row.name=="$fixtureName-已编辑" && row.personaPrompt==persona && row.favorite)
            j.addProperty("chatPending",true);persist(j)
            clickFooter("开始对话")
            waitState { (sessionIds()-j.getAsJsonArray("originalSessions").map { it.asLong }.toSet()-j.getAsJsonArray("stories").map { it.asLong }.toSet()).size==1 }
            val sid=(sessionIds()-j.getAsJsonArray("originalSessions").map { it.asLong }.toSet()-j.getAsJsonArray("stories").map { it.asLong }.toSet()).single()
            j.addProperty("created",sid);persist(j)
            waitNode("$fixtureName-已编辑 · 新故事",true);capture("06-local-chat-no-send")
            io {
                check(db.participantDao().getBySession(sid).map { it.characterId }==listOf(row.id))
                check(db.sessionWorldDao().getBySession(sid)!=null)
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages WHERE sessionId=?",arrayOf(sid)).use { c -> check(c.moveToFirst() && c.getInt(0)==0) }
            }
            check(RetainedChatSessions.running.value.isEmpty())
            back();waitNode("故事库",true);capture("07-chat-returns-to-story-root")
            click("创作");click("角色");waitNode("搜索角色名或人物设定",true)
            replaceInput(run.take(8));click("$fixtureName-已编辑")
            waitNode("角色详情",true);waitNode("已收藏",true);assertFooter("已收藏");capture("08-reentered-detail-persisted")
            clickFooter("已收藏");waitNode("收藏",true)
            waitState { io { db.characterDao().getById(row.id)?.favorite==false } }
            capture("09-unfavorite-persisted")
            back();waitNode("$fixtureName-已编辑",true);capture("10-returned-filtered-library")
            check(characterIds()==j.getAsJsonArray("originalIds").map { it.asLong }.toSet()+row.id)
            output.resolve("verified.txt").writeText("nativeMainActivity=true\nlongContentFooterFixed=true\nfavoriteRoomAndFeedback=true\neditSaveReturn=true\ncreatedExactlyOneLocalSession=true\nnoMessagesOrRunningGeneration=true\nchatReturnsToStoryRoot=true\nreenterDetailAndReturnToFilteredLibrary=true\n")
            j.addProperty("phase","verified");persist(j)
        } catch(t:Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString());runCatching { capture("failure") };throw t }
        finally { if(::activityScenario.isInitialized) activityScenario.close() }
    }
    @Test fun rollbackOnlyOwnedDetailRows() {
        guard();val j=JsonParser.parseString(journalFile.readText()).asJsonObject;check(j["run"].asString==run)
        val editor=preferences.edit()
        j.getAsJsonObject("originalConfig").entrySet().forEach { (k,v) -> if(v.isJsonNull) editor.remove(k) else editor.putString(k,v.asString) }
        check(editor.commit())
        j.getAsJsonObject("originalConfig").entrySet().forEach { (k,v) -> check(preferences.contains(k)==!v.isJsonNull);if(!v.isJsonNull) check(preferences.getString(k,null)==v.asString) }
        val original=j.getAsJsonArray("originalIds").map { it.asLong }.toSet()
        val originalSessions=j.getAsJsonArray("originalSessions").map { it.asLong }.toSet()
        val fixture=j["fixture"]?.asLong
        val stories=j.getAsJsonArray("stories")?.map { it.asLong }?.toSet().orEmpty()
        val created=sessionIds()-originalSessions-stories
        check(created.size<=1 && (created.isEmpty() || j.has("chatPending")))
        val owned=stories+created
        io { db.withTransaction {
            owned.forEach { id ->
                check(id !in originalSessions)
                val row=checkNotNull(db.sessionDao().getById(id))
                check(row.title.contains(run.take(8)))
                check(db.participantDao().getBySession(id).all { it.characterId==fixture })
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages WHERE sessionId=?",arrayOf(id)).use { c -> check(c.moveToFirst() && c.getInt(0)==0) }
                db.sessionDao().delete(id)
            }
            fixture?.let { id -> db.characterDao().getById(id)?.let { row ->
                check(id !in original && row.name.startsWith("详情角色-${run.take(8)}") && row.personaPrompt==j["persona"].asString)
                db.characterDao().delete(id)
            } }
        } }
        // Remove only new fixture keys, asserting every preexisting key/value before touching prefs.
        draftNames.forEach { name ->
            val p=context.getSharedPreferences(name,0);val expected=j.getAsJsonObject("drafts").getAsJsonObject(name)
            val extra=p.all.keys-expected.keySet()
            check(extra.all { key -> (owned+listOfNotNull(fixture)).any { id -> key==id.toString() || key.startsWith("$id:") || key.endsWith("_$id") } })
            val e=p.edit();extra.forEach(e::remove);check(e.commit());assertSnapshot(p,expected)
        }
        io { owned.forEach { com.mojing.app.data.prefs.UiPreferencesRepository(context).clearLastChatBranch(it) } }
        check(characterIds()==original);check(sessionIds()==originalSessions)
        check(branches()==j.getAsJsonObject("branches"));check(digest()==j["digest"].asString)
        check(mediaFilesDigest()==j["mediaFilesDigest"].asString);check(searchPrefsDigest()==j["searchPrefsDigest"].asString)
        j.addProperty("phase","rolled-back");persist(j)
        output.resolve("rollback.txt").writeText("onlyUUIDRowsRemoved=true\n34TablesExact=true\nconfigPresenceValueExact=true\nfourDraftsExact=true\nmediaSearchBranchPreferencesExact=true\n")
    }
}

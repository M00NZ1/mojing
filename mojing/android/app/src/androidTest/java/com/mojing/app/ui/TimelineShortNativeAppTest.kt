package com.mojing.app.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import androidx.room.withTransaction
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

/** Timeline detail, editing, and pair paging in MainActivity, with fresh UUID fixtures and independent exact rollback. */
class TimelineShortNativeAppTest {
    private lateinit var activity: MainActivity
    private lateinit var activityScenario: androidx.test.core.app.ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("timelineShortRun").orEmpty().also { UUID.fromString(it) }
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "timeline-short-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "timeline-short-$run").apply { mkdirs() }
    private val preferences get() = com.mojing.app.data.SecureStorage().apply { init(context) }.let { storage -> storage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(storage) } as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode", "theme_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "timeline-short-$run.pending")
        FileOutputStream(pending).use { it.write(j.toString().toByteArray()); it.fd.sync() }
        check(pending.renameTo(journalFile))
    }
    private fun snapshot(p: SharedPreferences) = JsonObject().apply { p.all.forEach { (k, v) -> add(k, JsonObject().apply {
        when (v) { is String -> { addProperty("type", "string"); addProperty("value", v) }
            is Boolean -> { addProperty("type", "boolean"); addProperty("value", v) }
            is Int -> { addProperty("type", "int"); addProperty("value", v) }
            is Long -> { addProperty("type", "long"); addProperty("value", v) }
            is Float -> { addProperty("type", "floatBits"); addProperty("value", v.toRawBits()) }
            is Set<*> -> { check(v.all { it is String });addProperty("type", "strings");add("value",com.google.gson.Gson().toJsonTree(v.map { it as String }.sorted())) }
            else -> error("unexpected preference type") }
    }) } }
    private fun assertSnapshot(p: SharedPreferences, expected: JsonObject) {
        check(p.all.keys == expected.keySet())
        expected.entrySet().forEach { (k, v) -> val row = v.asJsonObject
            when(row["type"].asString) {
                "string" -> check(p.all[k] is String && p.getString(k,null)==row["value"].asString)
                "boolean" -> check(p.all[k] is Boolean && p.getBoolean(k,false)==row["value"].asBoolean)
                "int" -> check(p.all[k] is Int && p.getInt(k,0)==row["value"].asInt)
                "long" -> check(p.all[k] is Long && p.getLong(k,0)==row["value"].asLong)
                "floatBits" -> check(p.all[k] is Float && p.getFloat(k,0f).toRawBits()==row["value"].asInt)
                "strings" -> check(p.all[k] is Set<*> && p.getStringSet(k,null)==row.getAsJsonArray("value").map { it.asString }.toSet())
                else -> error("unexpected preference type")
            }
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
        val until=System.currentTimeMillis()+60000
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

    private fun hideKeyboardIfOpen() {
        var open=false
        inst.runOnMainSync {
            open=androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true
        }
        if(open) back()
    }

    private fun guard() {
        check(args.getString("timelineShortCapture")=="true")
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
        val title=nodes().firstOrNull { it.text?.toString()=="世界" }
        output.resolve("$label-window.txt").writeText("detailTitleVisible=${title?.isVisibleToUser}\ntitleBounds="+
            title?.let { n -> android.graphics.Rect().also { n.getBoundsInScreen(it) }.toShortString() })
    }

    private fun worldIds():Set<Long> = io { buildSet {
        db.openHelper.readableDatabase.query("SELECT id FROM world_encyclopedias ORDER BY id").use { c -> while(c.moveToNext()) add(c.getLong(0)) }
    } }
    @Suppress("UNCHECKED_CAST")
    private fun uiStore(): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        val repo = com.mojing.app.data.prefs.UiPreferencesRepository(context)
        return repo.javaClass.getDeclaredMethod("getDataStore").apply { isAccessible = true }.invoke(repo)
            as androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    }
    private fun uiPrefsSnapshot() = io { com.google.gson.Gson().toJsonTree(uiStore().data.first().asMap().mapKeys { it.key.name }.toSortedMap()) }
    private fun visible(text: String) = nodes().any { it.isVisibleToUser && (it.text?.toString()==text || it.contentDescription?.toString()==text) }
    private fun scrollTo(text: String, forward: Boolean = true, actionable: Boolean = false) {
        var scrollForward=forward
        waitState {
            val currentNodes=nodes()
            val viewport=currentNodes.filter { it.isVisibleToUser && it.isScrollable }
                .maxByOrNull { candidate -> android.graphics.Rect().also { candidate.getBoundsInScreen(it) }.height() }
            val viewportBounds=android.graphics.Rect().also { viewport?.getBoundsInScreen(it) }
            var target=currentNodes.firstOrNull { it.isVisibleToUser && (it.text?.toString()==text || it.contentDescription?.toString()==text) }
            val aligning=target!=null
            if (target != null && listOf("已保存正文-", "未保存正文-", "组织正文-", "端点正文", "关联条目原文-").any(text::startsWith)) {
                val rect=android.graphics.Rect().also { target?.getBoundsInScreen(it) }
                val bodyHeading=currentNodes.firstOrNull { it.isVisibleToUser && it.text?.toString()=="详细内容" }
                val headingRect=android.graphics.Rect().also { bodyHeading?.getBoundsInScreen(it) }
                val margin=(16*context.resources.displayMetrics.density).toInt()
                if(bodyHeading==null || headingRect.top < viewportBounds.top+margin || rect.top < viewportBounds.top+margin) { scrollForward=false;target=null }
                else if(rect.bottom > viewportBounds.bottom-margin) { scrollForward=true;target=null }

            }
            if (target!=null && actionable) {
                val label=target
                val labelRect=android.graphics.Rect().also { label.getBoundsInScreen(it) }
                while(target!=null && !target.isClickable) target=target.parent
                val rect=android.graphics.Rect().also { target?.getBoundsInScreen(it) }
                val minimumLabelHeight=if(label.text.isNullOrEmpty()) 0f else
                    16*context.resources.displayMetrics.density*context.resources.configuration.fontScale
                val coveredByFab = (text=="打开条目" || text.startsWith("打开终点条目：") || text.startsWith("打开起点条目：")) && rect.bottom > context.resources.displayMetrics.heightPixels - 112*context.resources.displayMetrics.density
                output.resolve("scroll-trace.txt").appendText("candidate=$text label=${labelRect.toShortString()} action=${rect.toShortString()}\n")
                if(rect.top < viewportBounds.top) scrollForward=false
                else if(rect.bottom > viewportBounds.bottom || coveredByFab) scrollForward=true
                val fullActionDescription = listOf("查看来源：", "确认资料：", "无原文来源：", "打开起点条目：", "打开终点条目：").any { label.contentDescription?.toString()?.startsWith(it)==true }
                if(coveredByFab || rect.height()+1<48*context.resources.displayMetrics.density || labelRect.height()+1<minimumLabelHeight ||
                    (fullActionDescription && labelRect.height()+1<rect.height())) {
                    target=null
                }
            }
            if (target!=null) true else {
                // SHOW_ON_SCREEN reports success for a clipped label without exposing its action.
                viewport?.let {
                    val bounds=android.graphics.Rect().also { viewport.getBoundsInScreen(it) }
                    val actions=viewport.actionList.map { it.id }
                    if((text=="按关联条目类型筛选" || text=="当前页关系图") && !scrollForward &&
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD !in actions) {
                        inst.waitForIdleSync();inst.uiAutomation.waitForIdle(100,5000)
                        return@waitState false
                    }
                    if(scrollForward && android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD !in actions &&
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actions) scrollForward=false
                    else if(!scrollForward && android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD !in actions &&
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions) scrollForward=true
                    val x=bounds.left+bounds.width()/3
                    val distance=minOf(bounds.height()/2, ((if(aligning)80 else 240)*context.resources.displayMetrics.density).toInt())
                    val from=bounds.centerY()+(if(scrollForward) distance/2 else -distance/2)
                    val to=bounds.centerY()+(if(scrollForward) -distance/2 else distance/2)
                    output.resolve("scroll-trace.txt").appendText("target=$text viewport=${bounds.toShortString()} class=${viewport.className} actions=${viewport.actionList} gesture=$x,$from->$x,$to\n")
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
                        "input swipe $x $from $x $to 600")).use { it.readBytes() }
                }
                inst.waitForIdleSync();inst.uiAutomation.waitForIdle(100,5000)
                false
            }
        }
    }
    private fun assertAction(text:String) {
        var n=waitNode(text,true)
        while(!n.isClickable) n=checkNotNull(n.parent)
        val rect=android.graphics.Rect().also { n.getBoundsInScreen(it) }
        check(n.isEnabled && rect.height()+1>=48*context.resources.displayMetrics.density) { "$text action clipped: ${rect.toShortString()}" }
        check(rect.left>=0 && rect.right<=context.resources.displayMetrics.widthPixels)
    }
    private fun eventTitle(index:Int)="雾海事件${index}·北塔档案与远行者的漫长约定-${run.take(8)}"
    private fun setField(label:String,value:String) {
        scrollTo(label)
        fun editable(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
            if(node==null)return null
            if(node.isEditable && node.isVisibleToUser)return node
            for(i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
            return null
        }
        var root:android.view.accessibility.AccessibilityNodeInfo?=waitNode(label,true)
        var input:android.view.accessibility.AccessibilityNodeInfo?=null
        while(root!=null && input==null) { input=editable(root);root=root.parent }
        val n=checkNotNull(input) { "editable field missing: $label" }
        check(n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        }))
    }
    private fun recreate() { activityScenario.recreate();activityScenario.onActivity { activity=it };inst.waitForIdleSync() }
    private fun openMore(title:String) {
        scrollTo(title, actionable=true)
        fun findMore(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
            if(node==null)return null
            if(node.isVisibleToUser && node.contentDescription?.toString()=="更多操作")return node
            for(i in 0 until node.childCount)findMore(node.getChild(i))?.let { return it }
            return null
        }
        var root:android.view.accessibility.AccessibilityNodeInfo?=waitNode(title,true)
        var button:android.view.accessibility.AccessibilityNodeInfo?=null
        while(root!=null && button==null) { button=findMore(root);root=root.parent }
        var action=checkNotNull(button)
        while(!action.isClickable)action=checkNotNull(action.parent)
        val bounds=android.graphics.Rect().also { action.getBoundsInScreen(it) }
        val density=context.resources.displayMetrics.density
        check(bounds.width()+1>=48*density && bounds.height()+1>=48*density) { "more target below 48dp: $bounds" }
        check(action.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        waitNode("查看事件",true)
    }
    private fun menu(title:String,action:String) {
        openMore(title);click(action)
    }
    @Test fun timelineShortFlowAndRecreate() {
        guard();check(!journalFile.exists())
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val name="本地世界-${run.take(8)}"
        val j=JsonObject().apply {
            add("uiPrefs",uiPrefsSnapshot());add("securePrefs",snapshot(preferences));addProperty("run",run);addProperty("digest",digest());addProperty("mediaFilesDigest",mediaFilesDigest());addProperty("searchPrefsDigest",searchPrefsDigest())
            add("branches",branches());add("drafts",JsonObject().apply { draftNames.forEach { add(it,snapshot(context.getSharedPreferences(it,0))) } })
            add("originalIds",com.google.gson.Gson().toJsonTree(worldIds()));add("ownedFiles",com.google.gson.JsonArray())
            add("originalConfig",JsonObject().apply { configKeys.forEach { addProperty(it,preferences.getString(it,null)) } })
        };persist(j)
        io { db.withTransaction {
            val id=db.encyclopediaDao().upsert(EncyclopediaEntity(name=name,description="SYNTHETIC_$run",worldPrompt="TIMELINE_SHORT_$run"))
            j.addProperty("fixture",id);persist(j)
            val linked=db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId=id,title="关联事件条目-${run.take(8)}",entryType="event",content="关联条目原文-$run"))
            j.add("endpoints",com.google.gson.Gson().toJsonTree(listOf(linked)));persist(j)
            val ids=(0 until 30).map { index -> db.timelineEventDao().upsert(TimelineEventEntity(encyclopediaId=id,
                entryId=if(index==25)linked else null,title=eventTitle(index),description="事件原文$index-$run",
                eventTime="雾海历第一千零二十三年·冬至后的第七个长夜",sortOrder=index)) }
            j.add("events",com.google.gson.Gson().toJsonTree(ids));persist(j)
        } }
        check(preferences.edit().putString("theme_mode",args.getString("captureTheme")?:"dark").putString("public_api_key","").putString("model_platforms_v1","[]").putString("active_model_platform","").commit())
        io { com.mojing.app.data.prefs.UiPreferencesRepository(context).setEncyclopediaListLayout("list") }
        try {
            val ids=j.getAsJsonArray("events").map { it.asLong }
            activityScenario=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java);activityScenario.onActivity { activity=it }
            click("创作");click("世界");waitNode("搜索世界名称",true);replaceInput(run.take(8));scrollTo(name,actionable=true);click(name);click("时间线")
            capture("01-timeline-viewport")
            scrollTo(eventTitle(0),actionable=true);openMore(eventTitle(0));capture("02-more");back();waitState { !visible("查看事件") }
            menu(eventTitle(0),"查看事件");waitNode("编辑事件",true);capture("03-detail")
            recreate();waitNode("编辑事件",true);capture("04-detail-recreate");click("关闭");waitState { !visible("编辑事件") }
            scrollTo("下一页",actionable=true);assertAction("下一页");capture("05-page-control");click("下一页");waitNode("第 2 页",true)
            scrollTo(eventTitle(24),actionable=true);capture("06-page2-event");recreate();waitNode("第 2 页",true)
            scrollTo(eventTitle(24),actionable=true);capture("07-page2-recreate")
            menu(eventTitle(24),"删除");waitNode("确认删除时间线事件",true);capture("08-delete-confirm");click("取消");waitState { !visible("确认删除时间线事件") }
            check(io { db.timelineEventDao().getById(ids[24]) }!=null)
            scrollTo("添加事件",forward=false,actionable=true);assertAction("添加事件");capture("09-create-control");click("添加事件");waitNode("事件标题",true);capture("10-create-dialog")
            scrollTo("放弃草稿",actionable=true);click("放弃草稿");waitState { !visible("事件标题") }
            scrollTo("上一页",forward=false,actionable=true);click("上一页");waitNode("第 1 页",true);scrollTo(eventTitle(0),actionable=true);capture("11-previous-event")
            output.resolve("verified.txt").writeText("nativeMainActivity=true\nshortViewportEventsAndActionsReachable=true\nexplicitMore48dp=true\npairPage2Recreate=true\ndetailRecreate=true\ndeleteCancelOriginalKept=true\ncreateDiscard=true\n")
        } catch(failure:Throwable) {
            capture("failure");output.resolve("failure-nodes.txt").writeText(nodes().joinToString("\n") { "${it.className} text=${it.text} desc=${it.contentDescription} visible=${it.isVisibleToUser}" });throw failure
        } finally { activityScenario.close() }
    }
    @Test fun rollbackOnlyOwnedWorldRows() {
        guard();val j=JsonParser.parseString(journalFile.readText()).asJsonObject;check(j["run"].asString==run)
        val editor=preferences.edit()
        j.getAsJsonObject("originalConfig").entrySet().forEach { (k,v) -> if(v.isJsonNull) editor.remove(k) else editor.putString(k,v.asString) }
        check(editor.commit())
        j.getAsJsonObject("originalConfig").entrySet().forEach { (k,v) -> check(preferences.contains(k)==!v.isJsonNull);if(!v.isJsonNull) check(preferences.getString(k,null)==v.asString) }
        assertSnapshot(preferences,j.getAsJsonObject("securePrefs"))
        io { uiStore().edit { prefs ->
            val key=stringPreferencesKey("encyclopedia_list_layout")
            val old=j.getAsJsonObject("uiPrefs")["encyclopedia_list_layout"]
            if(old==null || old.isJsonNull) prefs.remove(key) else prefs[key]=old.asString
        } }
        check(uiPrefsSnapshot()==j["uiPrefs"])
        val original=j.getAsJsonArray("originalIds").map { it.asLong }.toSet()
        io { db.withTransaction {
            j["fixture"]?.asLong?.let { id -> db.encyclopediaDao().getById(id)?.let { row ->
                check(id !in original && row.worldPrompt=="TIMELINE_SHORT_$run")
                val draft=com.mojing.app.data.EntryEditDraftStore(context)
                (listOf(0L)+j.getAsJsonArray("endpoints").map { it.asLong }).forEach { entry ->
                    check(j.getAsJsonObject("drafts").getAsJsonObject("entry_edit_drafts_v1")["encyclopedia_${id}_entry_$entry"]==null)
                    draft.clear(id,entry)
                }
                com.mojing.app.data.WorldEditDraftStore(context).clear(id)
                db.encyclopediaDao().delete(id)
            } }
        } }
        draftNames.forEach { name -> assertSnapshot(context.getSharedPreferences(name,0),j.getAsJsonObject("drafts").getAsJsonObject(name)) }
        check(worldIds()==original);check(branches()==j.getAsJsonObject("branches"));check(digest()==j["digest"].asString)
        check(mediaFilesDigest()==j["mediaFilesDigest"].asString);check(searchPrefsDigest()==j["searchPrefsDigest"].asString)
        j.addProperty("phase","rolled-back");persist(j)
        j.getAsJsonArray("ownedFiles")?.forEach { value ->
            val file=File(value.asString);check(file.parentFile==context.filesDir && file.name.startsWith("timeline-short-$run-") && file.extension=="png")
            if(file.exists()) check(file.delete())
        }
        output.resolve("rollback.txt").writeText("onlyUUIDWorldRemoved=true\n34TablesExact=true\nconfigPresenceValueExact=true\ncompleteSecurePreferencesTypesPresenceValuesExact=true\nuiPreferencesPresenceValueExact=true\nfourDraftsExact=true\nmediaSearchBranchPreferencesExact=true\n")
    }
}

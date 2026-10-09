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

/** Existing world settings route through the real app, with UUID-owned fixtures and independent rollback. */
class RelationEndpointsNativeAppTest {
    private lateinit var activity: MainActivity
    private lateinit var activityScenario: androidx.test.core.app.ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("relationEndpointsRun").orEmpty().also { UUID.fromString(it) }
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "relation-endpoints-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "relation-endpoints-$run").apply { mkdirs() }
    private val preferences get() = com.mojing.app.data.SecureStorage().apply { init(context) }.let { storage -> storage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(storage) } as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode", "theme_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "relation-endpoints-$run.pending")
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
        check(args.getString("relationEndpointsCapture")=="true")
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
    private fun replaceValue(old:String, value:String) {
        var edit:android.view.accessibility.AccessibilityNodeInfo?=null
        waitState { edit=nodes().firstOrNull { it.isVisibleToUser && it.isEditable && it.text?.toString()==old };edit!=null }
        check(edit!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value) }))
        waitState { nodes().any { it.isEditable && it.text?.toString()==value } }
    }
    private fun assertSettingsAction() {
        var target:android.view.accessibility.AccessibilityNodeInfo?=null
        waitState {
            target=nodes().filter { it.isVisibleToUser && it.text?.toString()=="世界设置" }.mapNotNull { label ->
                var candidate:android.view.accessibility.AccessibilityNodeInfo?=label
                while(candidate!=null && !candidate.isClickable) candidate=candidate.parent
                candidate?.takeIf { it.isEnabled }
            }.firstOrNull()
            target!=null
        }
        val n=checkNotNull(target)
        val rect=android.graphics.Rect().also { n.getBoundsInScreen(it) }
        check(n.isEnabled && rect.height()+1>=48*context.resources.displayMetrics.density)
        check(rect.left>=0 && rect.right<=context.resources.displayMetrics.widthPixels)
    }
    private fun world(id:Long)=io { checkNotNull(db.encyclopediaDao().getById(id)) }
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
            var target=nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString()==text || it.contentDescription?.toString()==text) }
            if (target?.isEditable == true && listOf("已保存正文-", "未保存正文-", "组织正文-", "端点正文").any(text::startsWith)) {
                val rect=android.graphics.Rect().also { target?.getBoundsInScreen(it) }
                // BasicTextField uses minLines=4 / minHeight=112dp; partial bounds must not count as body evidence.
                val minimum=maxOf(112f, 80f*context.resources.configuration.fontScale+20f)-2f
                if(rect.height()+1<minimum*context.resources.displayMetrics.density) target=null
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
                if(rect.top < labelRect.top) scrollForward=false
                else if(rect.bottom > labelRect.bottom) scrollForward=true
                val fullActionDescription = listOf("查看来源：", "确认资料：", "无原文来源：", "打开起点条目：", "打开终点条目：").any { label.contentDescription?.toString()?.startsWith(it)==true }
                if(coveredByFab || rect.height()+1<48*context.resources.displayMetrics.density || labelRect.height()+1<minimumLabelHeight ||
                    (fullActionDescription && labelRect.height()+1<rect.height())) {
                    target=null
                }
            }
            if (target!=null) true else {
                val n=nodes().filter { it.isVisibleToUser && it.isScrollable }
                    .maxByOrNull { candidate -> android.graphics.Rect().also { candidate.getBoundsInScreen(it) }.height() }
                // SHOW_ON_SCREEN reports success for a clipped label without exposing its action.
                // Use a small real gesture so the screenshot and click both use the actual viewport.
                n?.let { viewport ->
                    val bounds=android.graphics.Rect().also { viewport.getBoundsInScreen(it) }
                    val actions=viewport.actionList.map { it.id }
                    if(scrollForward && android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD !in actions &&
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actions) scrollForward=false
                    else if(!scrollForward && android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD !in actions &&
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions) scrollForward=true
                    val x=bounds.left+(12*context.resources.displayMetrics.density).toInt()
                    val distance=minOf(bounds.height()/3, (120*context.resources.displayMetrics.density).toInt())
                    val from=bounds.centerY()+(if(scrollForward) distance/2 else -distance/2)
                    val to=bounds.centerY()+(if(scrollForward) -distance/2 else distance/2)
                    output.resolve("scroll-trace.txt").appendText("target=$text viewport=${bounds.toShortString()} class=${viewport.className} actions=${viewport.actionList} gesture=$x,$from->$x,$to\n")
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
                        "input swipe $x $from $x $to 160")).use { it.readBytes() }
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
    @Test fun relationEndpointActionsAndRecreate() {
        guard();check(!journalFile.exists())
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val name="本地世界-${run.take(8)}";val query=run.take(8)
        val j=JsonObject().apply {
            add("uiPrefs",uiPrefsSnapshot());add("securePrefs",snapshot(preferences));addProperty("run",run);addProperty("digest",digest());addProperty("mediaFilesDigest",mediaFilesDigest());addProperty("searchPrefsDigest",searchPrefsDigest())
            add("branches",branches());add("drafts",JsonObject().apply { draftNames.forEach { add(it,snapshot(context.getSharedPreferences(it,0))) } })
            add("originalIds",com.google.gson.Gson().toJsonTree(worldIds()))
            add("originalConfig",JsonObject().apply { configKeys.forEach { addProperty(it,preferences.getString(it,null)) } })
        };persist(j)
        io { db.withTransaction {
            val id=db.encyclopediaDao().upsert(EncyclopediaEntity(name=name,description="SYNTHETIC_$run",worldPrompt="RELATION_ENDPOINTS_$run"))
            j.addProperty("fixture",id);persist(j)
            repeat(100) { index -> db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId=id,
                title="筛选页人物$index",entryType="character",content="不应读取的正文".repeat(1000))) }
            val ids=mutableListOf<Long>()
            val files=com.google.gson.JsonArray();j.add("ownedFiles",files);persist(j)
            repeat(endpointCount) { index ->
                val file=File(context.filesDir,"relation-endpoints-$run-$index.png")
                check(!file.exists());files.add(file.absolutePath);persist(j)
                val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
                try { bitmap.eraseColor(coverColor(index));FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it));it.fd.sync() } }
                finally { bitmap.recycle() }
                ids.add(db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId=id,
                    title=endpointTitle(index),entryType="location",content="端点正文$index-$run",coverImagePath=file.absolutePath)))
            }
            j.add("endpoints",com.google.gson.Gson().toJsonTree(ids));j.addProperty("entry",ids.first());persist(j)
            (1 until endpointCount).forEach { index -> check(db.entryRelationDao().upsertIfEndpointsBelongToEncyclopedia(EntryRelationEntity(
                encyclopediaId=id,fromEntryId=ids.first(),toEntryId=ids[index],relationType="守护",label="本地关系$index"))) }
            check(j.getAsJsonObject("drafts").getAsJsonObject("world_edit_drafts_v1").keySet().none { it.startsWith("${id}_") })
        } }
        check(preferences.edit().putString("theme_mode",args.getString("captureTheme")?:"dark")
            .putString("public_api_key","").putString("model_platforms_v1","[]").putString("active_model_platform","").commit())
        io { com.mojing.app.data.prefs.UiPreferencesRepository(context).setEncyclopediaListLayout(args.getString("captureLayout")?:"list") }
        try {
            val id=j["fixture"].asLong
            activityScenario=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            activityScenario.onActivity { activity=it }
            click("创作");click("世界");waitNode("搜索世界名称",true);replaceInput(query)
            scrollTo(name,actionable=true);click(name);click("关系图")
            waitNode("当前页关系图",true);capture("01-dense-graph")
            val titles=(0 until endpointCount).map(::endpointTitle)
            val graphIndices=listOf(0)+(17 downTo 7).toList()
            val graphBounds=graphIndices.map { index ->
                val action="选择条目：${titles[index]}"
                scrollTo(action,actionable=true)
                val n=waitNode(action,true);check(n.isClickable && n.isEnabled)
                val rect=android.graphics.Rect().also { n.getBoundsInScreen(it) }
                val shot=checkNotNull(inst.uiAutomation.takeScreenshot())
                try { check(shot.getPixel(rect.centerX(),rect.top+(24*context.resources.displayMetrics.density).toInt())==coverColor(index)) }
                finally { shot.recycle() }
                rect
            }
            check(nodes().none { it.contentDescription?.toString()=="选择条目：${titles[1]}" })
            output.resolve("node-bounds.json").writeText(com.google.gson.Gson().toJson(graphBounds))
            scrollTo("选择条目：${titles[7]}",actionable=true)
            click("选择条目：${titles[7]}")
            waitState { waitNode("选择条目：${titles[7]}",true).isChecked }
            scrollTo("打开条目",actionable=true);capture("02-selected-node")
            val from="打开起点条目：${titles[0]}"
            val to="打开终点条目：${titles[1]}"
            // Descending relation IDs leave this endpoint outside the 12-node canvas.
            scrollTo(to,actionable=true);assertAction(to);capture("03-endpoints-outside-canvas")
            fun verifyCover(action:String,index:Int) {
                val rect=android.graphics.Rect().also { waitNode(action,true).getBoundsInScreen(it) }
                val bitmap=checkNotNull(inst.uiAutomation.takeScreenshot())
                try { check(bitmap.getPixel(rect.left+(28*context.resources.displayMetrics.density).toInt(),rect.centerY())==coverColor(index)) }
                finally { bitmap.recycle() }
            }
            verifyCover(to,1)
            click(to);waitNode("编辑条目",true);scrollTo("端点正文1-$run");capture("04-to-actual-body")
            back();waitNode("关系图",true);scrollTo(to,actionable=true);capture("05-to-back")
            scrollTo(from,forward=false,actionable=true);assertAction(from);verifyCover(from,0);capture("06-from-action")
            click(from);waitNode("编辑条目",true);scrollTo("端点正文0-$run");capture("07-from-actual-body")
            back();waitNode("关系图",true);scrollTo(to,actionable=true);capture("08-from-back")
            activityScenario.recreate();activityScenario.onActivity { activity=it }
            waitNode("关系图",true);scrollTo(to,actionable=true);verifyCover(to,1);capture("09-recreated-endpoints")
            click(to);waitNode("编辑条目",true);scrollTo("端点正文1-$run");capture("10-recreated-to-body")
            back();waitNode("关系图",true);scrollTo("当前页关系图",forward=false)
            scrollTo("选择条目：${titles[7]}",actionable=true)
            check(waitNode("选择条目：${titles[7]}",true).isChecked)
            scrollTo("打开条目",actionable=true);capture("11-selection-preserved")
            click("条目");waitNode("筛选页人物0",true);capture("12-initial-100-page")
            click("关系图");waitNode("当前页关系图",true);scrollTo(to,actionable=true);capture("13-return-endpoints")
            check(RetainedChatSessions.running.value.isEmpty())
            output.resolve("verified.txt").writeText("nativeMainActivity=true\n18ActualPNGEndpointsOutsideInitial100=true\ncanvasStill12=true\n48dpFullNameEndpointActions=true\nfromAndToActualBodyBack=true\nselectionAndActionsSurviveRecreate=true\nnoMessagesOrSupplierRequests=true\n")
            j.addProperty("phase","verified");persist(j)
        } catch(t:Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString());runCatching { capture("failure") };throw t }
        finally { if(::activityScenario.isInitialized) activityScenario.close() }
    }
    private val endpointCount get()=18
    private fun endpointTitle(index:Int)="雾海端点${index+1}·北塔档案守护者与远行者的漫长约定-${run.take(8)}"
    private fun coverColor(index:Int)=android.graphics.Color.rgb(40+index*10,90+index*4,190-index*6)
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
                check(id !in original && row.worldPrompt=="RELATION_ENDPOINTS_$run")
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
            val file=File(value.asString);check(file.parentFile==context.filesDir && file.name.startsWith("relation-endpoints-$run-") && file.extension=="png")
            if(file.exists()) check(file.delete())
        }
        output.resolve("rollback.txt").writeText("onlyUUIDWorldRemoved=true\n34TablesExact=true\nconfigPresenceValueExact=true\ncompleteSecurePreferencesTypesPresenceValuesExact=true\nuiPreferencesPresenceValueExact=true\nfourDraftsExact=true\nmediaSearchBranchPreferencesExact=true\n")
    }
}

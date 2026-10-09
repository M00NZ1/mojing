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
class RelationCoversNativeAppTest {
    private lateinit var activity: MainActivity
    private lateinit var activityScenario: androidx.test.core.app.ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("relationCoversRun").orEmpty().also { UUID.fromString(it) }
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "relation-covers-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "relation-covers-$run").apply { mkdirs() }
    private val preferences get() = com.mojing.app.data.SecureStorage().apply { init(context) }.let { storage -> storage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(storage) } as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode", "theme_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "relation-covers-$run.pending")
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

    private fun hideKeyboardIfOpen() {
        var open=false
        inst.runOnMainSync {
            open=androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true
        }
        if(open) back()
    }

    private fun guard() {
        check(args.getString("relationCoversCapture")=="true")
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
                val coveredByFab = text=="打开条目" && rect.bottom > context.resources.displayMetrics.heightPixels - 112*context.resources.displayMetrics.density
                if(coveredByFab || rect.height()+1<48*context.resources.displayMetrics.density || labelRect.height()+1<minimumLabelHeight) {
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
                    val x=bounds.centerX()
                    val distance=minOf(bounds.height()/6, (80*context.resources.displayMetrics.density).toInt())
                    val from=bounds.centerY()+(if(forward) distance/2 else -distance/2)
                    val to=bounds.centerY()+(if(forward) -distance/2 else distance/2)
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
    @Test fun relationCoversManagementAndDirtyReturn() {
        guard();check(!journalFile.exists())
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val name="本地世界-${run.take(8)}";val query=run.take(8)
        val j=JsonObject().apply {
            add("uiPrefs",uiPrefsSnapshot());addProperty("run",run);addProperty("digest",digest());addProperty("mediaFilesDigest",mediaFilesDigest());addProperty("searchPrefsDigest",searchPrefsDigest())
            add("branches",branches());add("drafts",JsonObject().apply { draftNames.forEach { add(it,snapshot(context.getSharedPreferences(it,0))) } })
            add("originalIds",com.google.gson.Gson().toJsonTree(worldIds()))
            add("originalConfig",JsonObject().apply { configKeys.forEach { addProperty(it,preferences.getString(it,null)) } })
        };persist(j)
        io { db.withTransaction {
            val id=db.encyclopediaDao().upsert(EncyclopediaEntity(name=name,description="SYNTHETIC_$run",worldPrompt="RELATION_COVERS_$run"))
            j.addProperty("fixture",id);persist(j)
            repeat(100) { index -> db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId=id,
                title="筛选页人物$index",entryType="character",content="不应读取的正文".repeat(1000))) }
            val ids=mutableListOf<Long>()
            val files=com.google.gson.JsonArray();j.add("ownedFiles",files);persist(j)
            repeat(endpointCount) { index ->
                val file=File(context.filesDir,"relation-covers-$run-$index.png")
                check(!file.exists());files.add(file.absolutePath);persist(j)
                val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
                try { bitmap.eraseColor(coverColor(index));FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it));it.fd.sync() } }
                finally { bitmap.recycle() }
                ids.add(db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId=id,
                    title="雾海端点${index+1}-${run.take(8)}",entryType="location",content="端点正文$index-$run",coverImagePath=file.absolutePath)))
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
            if(args.getString("reproduction")!="true") {
                val titles=(0 until endpointCount).map { "雾海端点${it+1}-${run.take(8)}" }
                // All endpoints are beyond the initial 100-character page.
                val bounds=titles.map { title ->
                    val n=waitNode("选择条目：$title",true);check(n.isClickable && n.isEnabled)
                    android.graphics.Rect().also { n.getBoundsInScreen(it);check(it.width()+1>=48*context.resources.displayMetrics.density && it.height()+1>=48*context.resources.displayMetrics.density) }
                }
                for(i in bounds.indices) for(k in 0 until i) check(!android.graphics.Rect.intersects(bounds[i],bounds[k])) { "overlapping nodes $i / $k" }
                output.resolve("node-bounds.json").writeText(com.google.gson.Gson().toJson(bounds.map { listOf(it.left,it.top,it.right,it.bottom) }))
                val shot=checkNotNull(inst.uiAutomation.takeScreenshot())
                try { bounds.forEachIndexed { index, rect -> check(shot.getPixel(rect.centerX(),rect.top+(24*context.resources.displayMetrics.density).toInt())==coverColor(index)) { "missing actual endpoint cover $index" } } }
                finally { shot.recycle() }
                check(!waitNode("选择条目：${titles.last()}",true).isChecked)
                click("选择条目：${titles.last()}")
                // Compose 1.9.4 maps Selected to native checked for non-Tab roles.
                waitState { waitNode("选择条目：${titles.last()}",true).isChecked }
                val selectedA11y=waitNode("选择条目：${titles.last()}",true)
                output.resolve("selection-a11y.txt").writeText("role=Button\nnativeCheckedBefore=false\nnativeCheckedAfter=${selectedA11y.isChecked}\nnativeSelected=${selectedA11y.isSelected}\nstateDescription=${selectedA11y.stateDescription}\n")
                scrollTo("打开条目",actionable=true);capture("02-selected-endpoint-card")
                val titleBounds=android.graphics.Rect().also { waitNode(titles.last(),true).getBoundsInScreen(it) }
                val typeBounds=android.graphics.Rect().also { waitNode("地点",true).getBoundsInScreen(it) }
                val selectedShot=checkNotNull(inst.uiAutomation.takeScreenshot())
                try { check(selectedShot.getPixel(titleBounds.left-(32*context.resources.displayMetrics.density).toInt(),(titleBounds.top+typeBounds.bottom)/2)==coverColor(endpointCount-1)) { "selected card cover differs from node" } }
                finally { selectedShot.recycle() }
                click("打开条目");waitNode("编辑条目",true);scrollTo("端点正文${endpointCount-1}-$run");capture("03-actual-endpoint-body")
                back();waitNode("关系图",true);scrollTo("当前页关系图",forward=false);capture("04-back-graph-top")
                output.resolve("back-state.txt").writeText("selectedCardVisible=${visible("打开条目")}\n")
                check(waitNode("选择条目：${titles.last()}",true).isChecked) { "selection lost after actual editor Back" }
                scrollTo("打开条目",actionable=true);capture("04b-back-to-selection")
                click("条目");waitNode("筛选页人物0",true);click("关系图");waitNode("当前页关系图",true)
                capture("05-filtered-page-independent-covers")
                activityScenario.recreate();activityScenario.onActivity { activity=it }
                waitNode("关系图",true);scrollTo("当前页关系图",forward=false)
                check(waitNode("选择条目：${titles.last()}",true).isChecked) { "selection lost on Activity recreation" }
                scrollTo("打开条目",actionable=true);capture("06-selection-after-activity-recreate")
                check(RetainedChatSessions.running.value.isEmpty())
                output.resolve("verified.txt").writeText("nativeMainActivity=true\n${endpointCount}ActualPNGEndpointsOutsideInitial100=true\n48dpFullNameSelectionNoOverlap=true\nselectedCardAndActualBodyBack=true\nnoMessagesOrSupplierRequests=true\n")
            }
            j.addProperty("phase","verified");persist(j)
        } catch(t:Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString());runCatching { capture("failure") };throw t }
        finally { if(::activityScenario.isInitialized) activityScenario.close() }
    }
    private val endpointCount get()=if(args.getString("sparse")=="true") 5 else 12
    private fun coverColor(index:Int)=android.graphics.Color.rgb(40+index*12,110+index*5,170-index*7)
    @Test fun rollbackOnlyOwnedWorldRows() {
        guard();val j=JsonParser.parseString(journalFile.readText()).asJsonObject;check(j["run"].asString==run)
        val editor=preferences.edit()
        j.getAsJsonObject("originalConfig").entrySet().forEach { (k,v) -> if(v.isJsonNull) editor.remove(k) else editor.putString(k,v.asString) }
        check(editor.commit())
        j.getAsJsonObject("originalConfig").entrySet().forEach { (k,v) -> check(preferences.contains(k)==!v.isJsonNull);if(!v.isJsonNull) check(preferences.getString(k,null)==v.asString) }
        io { uiStore().edit { prefs ->
            val key=stringPreferencesKey("encyclopedia_list_layout")
            val old=j.getAsJsonObject("uiPrefs")["encyclopedia_list_layout"]
            if(old==null || old.isJsonNull) prefs.remove(key) else prefs[key]=old.asString
        } }
        check(uiPrefsSnapshot()==j["uiPrefs"])
        val original=j.getAsJsonArray("originalIds").map { it.asLong }.toSet()
        io { db.withTransaction {
            j["fixture"]?.asLong?.let { id -> db.encyclopediaDao().getById(id)?.let { row ->
                check(id !in original && row.worldPrompt=="RELATION_COVERS_$run")
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
            val file=File(value.asString);check(file.parentFile==context.filesDir && file.name.startsWith("relation-covers-$run-") && file.extension=="png")
            if(file.exists()) check(file.delete())
        }
        output.resolve("rollback.txt").writeText("onlyUUIDWorldRemoved=true\n34TablesExact=true\nconfigPresenceValueExact=true\nuiPreferencesPresenceValueExact=true\nfourDraftsExact=true\nmediaSearchBranchPreferencesExact=true\n")
    }
}

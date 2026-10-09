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
class EntryRelationsNativeAppTest {
    private lateinit var activity: MainActivity
    private lateinit var activityScenario: androidx.test.core.app.ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("entryRelationsRun").orEmpty().also { UUID.fromString(it) }
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "entry-relations-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "entry-relations-$run").apply { mkdirs() }
    private val preferences get() = com.mojing.app.data.SecureStorage().apply { init(context) }.let { storage -> storage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(storage) } as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode", "theme_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "entry-relations-$run.pending")
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
        check(args.getString("entryRelationsCapture")=="true")
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
            if (target?.isEditable == true && listOf("已保存正文-", "未保存正文-", "组织正文-").any(text::startsWith)) {
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
                if(rect.height()+1<48*context.resources.displayMetrics.density || labelRect.height()+1<minimumLabelHeight) {
                    target=null
                }
            }
            if (target!=null) true else {
                val n=nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }
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
    @Test fun entryRelationsManagementAndDirtyReturn() {
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
            val id=db.encyclopediaDao().upsert(EncyclopediaEntity(name=name,description="SYNTHETIC_$run",worldPrompt="ENTRY_RELATIONS_$run"))
            j.addProperty("fixture",id);persist(j)
            val main=db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId=id, title="雾港-${run.take(8)}",
                entryType="location", content="已保存正文-$run", metaJson="""{"alias":["旧称"],"local_rule":"KEEP_$run"}"""))
            val other=db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId=id, title="港务会-${run.take(8)}", entryType="faction", content="组织正文-$run"))
            j.addProperty("entry",main);j.addProperty("other",other);persist(j)
            check(db.entryRelationDao().upsertIfEndpointsBelongToEncyclopedia(EntryRelationEntity(encyclopediaId=id,
                fromEntryId=other,toEntryId=main,relationType="管理",label="已保存关系-$run")))

            check(j.getAsJsonObject("drafts").getAsJsonObject("world_edit_drafts_v1").keySet().none { it.startsWith("${id}_") })
        } }
        check(preferences.edit().putString("theme_mode",args.getString("captureTheme")?:"dark")
            .putString("public_api_key","").putString("model_platforms_v1","[]").putString("active_model_platform","").commit())
        io { com.mojing.app.data.prefs.UiPreferencesRepository(context).setEncyclopediaListLayout(args.getString("captureLayout")?:"list") }
        try {
            val id=j["fixture"].asLong;val entry=j["entry"].asLong;val other=j["other"].asLong
            val title="雾港-${run.take(8)}";val otherTitle="港务会-${run.take(8)}"
            activityScenario=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            activityScenario.onActivity { activity=it }
            click("创作");click("世界");waitNode("搜索世界名称",true);replaceInput(query)
            scrollTo(name,actionable=true);click(name);click("全部");scrollTo(title,actionable=true);click(title)
            waitNode("编辑条目",true);waitNode("旧称",true);capture("01-editor-alias")
            replaceValue("旧称","潮港, 雾城")
            scrollTo("已保存正文-$run");replaceValue("已保存正文-$run","未保存正文-$run")
            hideKeyboardIfOpen()
            scrollTo("添加或管理关系",actionable=true);assertAction("添加或管理关系")
            scrollTo(otherTitle,actionable=true);waitNode("势力 / 组织",false);capture("02-real-related-entry")
            scrollTo("添加或管理关系",forward=false,actionable=true);click("添加或管理关系")
            waitNode("尚未保存的修改",true);capture("03-dirty-exit")
            click("继续编辑");scrollTo("未保存正文-$run",forward=false);capture("04-cancel-keeps-body")
            scrollTo("添加或管理关系",actionable=true);click("添加或管理关系");click("保留草稿并离开")
            waitNode("关系图",true);waitNode("添加关系",true);capture("05-existing-graph")
            click("添加关系");waitNode("从条目：$title",true);capture("06-preselected-endpoint")
            click("到条目：请选择");waitNode(otherTitle,true);click(otherTitle)
            replaceValue("关联","守护");capture("07-existing-owner-dialog")
            click("添加关系")
            waitState { io { db.entryRelationDao().getEntryPage(id,entry,Long.MAX_VALUE,25).size==2 } }
            capture("08-added-graph");back()
            waitNode("编辑条目",true);scrollTo("未保存正文-$run",forward=false);capture("09-dirty-body-return")
            scrollTo("添加或管理关系",actionable=true);scrollTo(otherTitle,actionable=true)
            check(io { db.entryRelationDao().getEntryPage(id,entry,Long.MAX_VALUE,25).map { it.relationType }.toSet() }==setOf("守护","管理"))
            capture("10-refreshed-relations")
            scrollTo("添加或管理关系",forward=false,actionable=true);click("添加或管理关系");click("放弃修改")
            waitNode("关系图",true);back();waitNode("编辑条目",true)
            scrollTo("已保存正文-$run",forward=false);capture("11-discard-restores-editor")
            scrollTo("旧称",forward=false);replaceValue("旧称","潮港, 雾城");hideKeyboardIfOpen()
            click("保存修改");waitState { io { db.encyclopediaEntryDao().getById(entry)?.metaJson?.contains("潮港")==true } }
            val meta=io { JsonParser.parseString(checkNotNull(db.encyclopediaEntryDao().getById(entry)).metaJson).asJsonObject }
            check(meta["local_rule"].asString=="KEEP_$run" && meta.getAsJsonArray("alias").map { it.asString }==listOf("潮港","雾城"))
            scrollTo("添加或管理关系",actionable=true);scrollTo(otherTitle,actionable=true);click(otherTitle)
            waitNode("编辑条目",true);scrollTo("组织正文-$run");capture("12-open-real-other-entry")
            back();waitNode("编辑条目",true);scrollTo("潮港, 雾城",forward=false);capture("13-back-original-entry")
            check(RetainedChatSessions.running.value.isEmpty())
            output.resolve("verified.txt").writeText("nativeMainActivity=true\nrealEntryRelationsBrowseAndOpen=true\ndirtyCancelKeepDiscardReturn=true\nexistingGraphOwnerPreselectAndAdd=true\naliasExistingMetaJsonPreservesOtherKeys=true\nnoMessagesOrSupplierRequests=true\n")
            j.addProperty("phase","verified");persist(j)
        } catch(t:Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString());runCatching { capture("failure") };throw t }
        finally { if(::activityScenario.isInitialized) activityScenario.close() }
    }
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
                check(id !in original && row.worldPrompt=="ENTRY_RELATIONS_$run")
                val draft=com.mojing.app.data.EntryEditDraftStore(context)
                listOfNotNull(0L,j["entry"]?.asLong,j["other"]?.asLong).forEach { entry ->
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
        output.resolve("rollback.txt").writeText("onlyUUIDWorldRemoved=true\n34TablesExact=true\nconfigPresenceValueExact=true\nuiPreferencesPresenceValueExact=true\nfourDraftsExact=true\nmediaSearchBranchPreferencesExact=true\n")
    }
}

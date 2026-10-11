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

/** Character library footer through native MainActivity, with an independent UUID rollback. */
class CharacterLibraryActionsNativeAppTest {
    private lateinit var activity: MainActivity
    private lateinit var activityScenario: androidx.test.core.app.ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("libraryActionsRun").orEmpty().also { UUID.fromString(it) }
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "character-library-actions-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "character-library-actions-$run").apply { mkdirs() }
    private val preferences get() = com.mojing.app.data.SecureStorage().apply { init(context) }.let { storage -> storage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(storage) } as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode", "theme_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "character-library-actions-$run.pending")
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
        check(args.getString("libraryActionsCapture")=="true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration()==null)
    }
    private fun capture(label:String) {
        inst.waitForIdleSync()
        inst.uiAutomation.waitForIdle(300,5000)
        val bitmap=checkNotNull(inst.uiAutomation.takeScreenshot())
        try { FileOutputStream(output.resolve("$label.png")).use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { bitmap.recycle() }
        // Keep the UIAutomation image and append the same bounded system capture used by the World fixtures.
        output.resolve("$label-shell.png").writeBytes(android.os.ParcelFileDescriptor.AutoCloseInputStream(
            inst.uiAutomation.executeShellCommand("screencap -p")).use { it.readBytes() })
    }
    private fun characterIds():Set<Long> = io {
        buildSet { db.openHelper.readableDatabase.query("SELECT id FROM characters ORDER BY id").use { c -> while(c.moveToNext()) add(c.getLong(0)) } }
    }
    private fun footerNode(label:String):android.view.accessibility.AccessibilityNodeInfo {
        waitNode(label,true)
        return nodes().filter { it.isVisibleToUser && it.text?.toString()==label }.maxBy { n -> android.graphics.Rect().also { n.getBoundsInScreen(it) }.top }
    }
    private data class FooterTarget(val node:android.view.accessibility.AccessibilityNodeInfo,val depth:Int,val viaAncestor:Boolean)
    private val minTouchPx:Int
        get() = kotlin.math.ceil(48f*context.resources.displayMetrics.density).toInt()
    private fun nodeEvidence(node:android.view.accessibility.AccessibilityNodeInfo):JsonObject = JsonObject().apply {
        val b=android.graphics.Rect().also { node.getBoundsInScreen(it) }
        addProperty("left",b.left);addProperty("top",b.top);addProperty("right",b.right);addProperty("bottom",b.bottom)
        addProperty("enabled",node.isEnabled);addProperty("clickable",node.isClickable)
        addProperty("selected",node.isSelected)
        addProperty("hasClickAction",node.actionList.any { it.id==android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK })
    }
    private fun recordFooterParentEvidence() {
        val fixed=listOf("新建角色","导入","对话","创作","设置")
        val evidence=JsonObject()
        fixed.forEach { label ->
            val leaf=footerNode(label)
            val chain=List(8) { JsonObject() }
            var node:android.view.accessibility.AccessibilityNodeInfo?=leaf
            var depth=0
            while(node!=null && depth<8) {
                chain[depth].addProperty("depth",depth)
                chain[depth].add("node",nodeEvidence(node))
                node=node.parent
                depth++
            }
            val rows=com.google.gson.JsonArray()
            chain.take(depth).forEach { rows.add(it) }
            evidence.add(label,JsonObject().apply { add("chain",rows);addProperty("depthsRecorded",depth) })
        }
        val j=JsonParser.parseString(journalFile.readText()).asJsonObject
        j.add("footerParentEvidence",evidence)
        persist(j)
    }
    private fun footerTarget(label:String, tab:Boolean=false):FooterTarget {
        val textNode=footerNode(label)
        val textBounds=android.graphics.Rect().also { textNode.getBoundsInScreen(it) }
        fun valid(n:android.view.accessibility.AccessibilityNodeInfo):Boolean {
            if(n===textNode || !n.isVisibleToUser || !n.isEnabled) return false
            val b=android.graphics.Rect().also { n.getBoundsInScreen(it) }
            if(b.isEmpty || !b.contains(textBounds)) return false
            val click=n.isClickable || n.actionList.any { it.id==android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK }
            return if(tab && label=="创作") n.isSelected else click
        }
        var parent=textNode.parent
        var depth=1
        while(parent!=null && depth<=8) {
            if(valid(parent)) return FooterTarget(parent,depth,true)
            parent=parent.parent
            depth++
        }
        // Some Compose semantics trees expose the text leaf without a usable parent link.
        // Recover only the smallest bounded clickable/selected container for this fixed label.
        val screenW=context.resources.displayMetrics.widthPixels
        val screenH=context.resources.displayMetrics.heightPixels
        val recovered=nodes().asSequence().filter(::valid).map { n ->
            val b=android.graphics.Rect().also { n.getBoundsInScreen(it) }
            n to b
        }.filter { (_,b) -> b.width()<=screenW && b.height()<=screenH && b.height()>=minTouchPx }
            .minByOrNull { (_,b) -> b.width().toLong()*b.height().toLong() }
        return recovered?.first?.let { FooterTarget(it,-1,false) }
            ?: error("footer container missing: $label")
    }
    private fun footerEvidence(target:FooterTarget):JsonObject = JsonObject().apply {
        val b=android.graphics.Rect().also { target.node.getBoundsInScreen(it) }
        addProperty("ancestorDepth",target.depth)
        addProperty("viaAncestor",target.viaAncestor)
        addProperty("left",b.left);addProperty("top",b.top);addProperty("right",b.right);addProperty("bottom",b.bottom)
        addProperty("enabled",target.node.isEnabled);addProperty("clickable",target.node.isClickable)
        addProperty("selected",target.node.isSelected)
    }
    private fun clickFooter(label:String) {
        val node=footerTarget(label).node
        check(node.isEnabled && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun assertFooter() {
        // Persist the bounded five-label parent evidence before any container or geometry check can fail.
        recordFooterParentEvidence()
        val targets=linkedMapOf(
            "新建角色" to footerTarget("新建角色"), "导入" to footerTarget("导入"),
            "对话" to footerTarget("对话",true), "创作" to footerTarget("创作",true), "设置" to footerTarget("设置",true)
        )
        val create=android.graphics.Rect().also { targets.getValue("新建角色").node.getBoundsInScreen(it) }
        val import=android.graphics.Rect().also { targets.getValue("导入").node.getBoundsInScreen(it) }
        val sessionTab=android.graphics.Rect().also { targets.getValue("对话").node.getBoundsInScreen(it) }
        val creationTab=android.graphics.Rect().also { targets.getValue("创作").node.getBoundsInScreen(it) }
        val settingsTab=android.graphics.Rect().also { targets.getValue("设置").node.getBoundsInScreen(it) }
        check(create.top==import.top && create.left<import.left && create.height()>=minTouchPx && import.height()>=minTouchPx)
        check(sessionTab.top==creationTab.top && creationTab.top==settingsTab.top)
        check(sessionTab.height()>=minTouchPx && creationTab.height()>=minTouchPx && settingsTab.height()>=minTouchPx)
        check(create.bottom<=sessionTab.top && import.bottom<=sessionTab.top)
        val evidence=JsonObject().apply { targets.forEach { (label,target) -> add(label,footerEvidence(target)) } }
        val j=JsonParser.parseString(journalFile.readText()).asJsonObject
        j.add("footerEvidence",evidence);persist(j)
    }
    @Test fun importCancelCreateAndReturn() {
        guard(); check(!journalFile.exists())
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val fixtureName="库角色-${run.take(8)}"
        val j=JsonObject().apply {
            addProperty("run",run);addProperty("digest",digest());addProperty("mediaFilesDigest",mediaFilesDigest());addProperty("searchPrefsDigest",searchPrefsDigest())
            add("branches",branches());add("drafts",JsonObject().apply { draftNames.forEach { add(it,snapshot(context.getSharedPreferences(it,0))) } })
            add("originalIds",com.google.gson.Gson().toJsonTree(characterIds()))
            add("originalConfig",JsonObject().apply { configKeys.forEach { addProperty(it,preferences.getString(it,null)) } })
        };persist(j)
        io { db.withTransaction {
            val id=db.characterDao().upsert(CharacterEntity(name=fixtureName,personaPrompt="SYNTHETIC_$run"))
            j.addProperty("fixture",id);persist(j)
        } }
        check(preferences.edit().putString("theme_mode",args.getString("captureTheme")?:"dark").commit())
        try {
            activityScenario=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            activityScenario.onActivity { activity=it }
            click("创作");click("角色");waitNode("搜索角色名或人物设定",true)
            replaceInput(run.take(8));waitNode(fixtureName,true)
            // Search does not bring up IME when replaced through accessibility.
            assertFooter();capture("01-filtered-library-actions")
            clickFooter("导入")
            waitState { nodes().any { it.isVisibleToUser && it.packageName?.toString()?.contains("documentsui")==true } }
            capture("02-real-file-picker");back()
            waitNode(fixtureName,true);assertFooter();capture("03-cancel-preserves-library")
            check(characterIds()==j.getAsJsonArray("originalIds").map { it.asLong }.toSet()+j["fixture"].asLong)
            // Focus actual search input and verify page actions yield space to IME.
            var search=nodes().first { it.isVisibleToUser && it.isEditable }
            check(search.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            waitState {
                var ime=false
                inst.runOnMainSync { ime=activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true }
                ime
            }
            waitState { nodes().none { it.isVisibleToUser && it.text?.toString()=="导入" } }
            capture("04-search-ime-no-footer");back();waitNode("导入",true);assertFooter()
            j.addProperty("createPending",true);persist(j)
            clickFooter("新建角色");waitNode("编辑角色",true)
            waitState { nodes().none { it.isVisibleToUser && it.text?.toString() in listOf("对话","创作","设置") } }
            val added=characterIds()-j.getAsJsonArray("originalIds").map { it.asLong }.toSet()-setOf(j["fixture"].asLong)
            check(added.size==1)
            val created=checkNotNull(io { db.characterDao().getById(added.single()) })
            check(created.copy(id=0,createdAt=0,updatedAt=0)==CharacterEntity(name="新角色",createdAt=0,updatedAt=0))
            j.addProperty("created",created.id);j.add("createdRow",com.google.gson.Gson().toJsonTree(created));persist(j)
            capture("05-new-character-editor")
            click("返回");waitNode(fixtureName,true);assertFooter();capture("06-returned-filter-preserved")
            check(characterIds()==j.getAsJsonArray("originalIds").map { it.asLong }.toSet()+setOf(j["fixture"].asLong,created.id))
            click("返回创作中心");waitNode("小说创作",true);capture("07-creation-root-navigation")
            output.resolve("verified.txt").writeText("nativeMainActivity=true\nfooterAligned=true\nrealOpenDocumentCancel=true\nsearchAndLibraryPreserved=true\nimeHidesActions=true\ncreatedExactlyOneUnboundCharacter=true\nreturnedToCreationRoot=true\n")
            j.addProperty("phase","verified");persist(j)
        } catch(t:Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString());runCatching { capture("failure") };throw t }
        finally { if(::activityScenario.isInitialized) activityScenario.close() }
    }
    @Test fun rollbackOnlyOwnedLibraryRows() {
        guard();val j=JsonParser.parseString(journalFile.readText()).asJsonObject;check(j["run"].asString==run)
        val original=j.getAsJsonArray("originalIds").map { it.asLong }.toSet()
        val fixture=j["fixture"]?.asLong
        val extra=characterIds()-original-listOfNotNull(fixture).toSet()
        check(extra.size<=1 && (extra.isEmpty() || j.has("createPending")))
        io { db.withTransaction {
            extra.forEach { id ->
                check(!original.contains(id))
                val row=checkNotNull(db.characterDao().getById(id))
                if(j.has("createdRow")) check(row==com.google.gson.Gson().fromJson(j["createdRow"],CharacterEntity::class.java))
                else check(row.copy(id=0,createdAt=0,updatedAt=0)==CharacterEntity(name="新角色",createdAt=0,updatedAt=0))
                db.characterDao().delete(id)
            }
            fixture?.let { id -> db.characterDao().getById(id)?.let { row ->
                check(!original.contains(id) && row.name=="库角色-${run.take(8)}" && row.personaPrompt=="SYNTHETIC_$run")
                db.characterDao().delete(id)
            } }
        } }
        val editor=preferences.edit()
        j.getAsJsonObject("originalConfig").entrySet().forEach { (k,v) -> if(v.isJsonNull) editor.remove(k) else editor.putString(k,v.asString) }
        check(editor.commit())
        j.getAsJsonObject("originalConfig").entrySet().forEach { (k,v) -> check(preferences.contains(k)==!v.isJsonNull);if(!v.isJsonNull) check(preferences.getString(k,null)==v.asString) }
        draftNames.forEach { assertSnapshot(context.getSharedPreferences(it,0),j.getAsJsonObject("drafts").getAsJsonObject(it)) }
        check(characterIds()==original);check(branches()==j.getAsJsonObject("branches"));check(digest()==j["digest"].asString)
        check(mediaFilesDigest()==j["mediaFilesDigest"].asString);check(searchPrefsDigest()==j["searchPrefsDigest"].asString)
        j.addProperty("phase","rolled-back");persist(j)
        output.resolve("rollback.txt").writeText("onlyNewRowsRemoved=true\n34TablesExact=true\nconfigPresenceValueExact=true\nfourDraftsExact=true\nmediaSearchBranchPreferencesExact=true\n")
    }
}

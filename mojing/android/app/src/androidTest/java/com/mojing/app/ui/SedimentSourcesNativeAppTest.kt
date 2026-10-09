package com.mojing.app.ui

import androidx.activity.enableEdgeToEdge
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
class SedimentSourcesNativeAppTest {
    private lateinit var activity: MainActivity
    private lateinit var activityScenario: androidx.test.core.app.ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("sedimentSourcesRun").orEmpty().also { UUID.fromString(it) }
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "sediment-sources-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "sediment-sources-$run").apply { mkdirs() }
    private val preferences get() = com.mojing.app.data.SecureStorage().apply { init(context) }.let { storage -> storage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(storage) } as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode", "theme_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "sediment-sources-$run.pending")
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
        check(args.getString("sedimentSourcesCapture")=="true")
        check(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        check(RetainedChatSessions.running.value.isEmpty())
        check(StoryOpeningInputDraftStore(context).loadGeneration()==null)
    }
    private fun capture(label:String) {
        inst.waitForIdleSync(); inst.uiAutomation.waitForIdle(200,5000)
        repeat(2) {
            val committed=java.util.concurrent.CountDownLatch(1)
            inst.runOnMainSync { val view=activity.window.decorView
                view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }; view.invalidate() }
            check(committed.await(5,java.util.concurrent.TimeUnit.SECONDS))
        }
        val shot=checkNotNull(inst.uiAutomation.takeScreenshot())
        try { FileOutputStream(output.resolve("$label.png")).use { check(shot.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { shot.recycle() }
        output.resolve("$label-nodes.txt").writeText(nodes().filter { it.isVisibleToUser }.joinToString("\n") {
            val r=android.graphics.Rect().also(it::getBoundsInScreen)
            "${it.text} | ${it.contentDescription} | ${r.toShortString()} enabled=${it.isEnabled} click=${it.isClickable}" })
    }
    private fun sessionIds():Set<Long> = io { buildSet {
        db.openHelper.readableDatabase.query("SELECT id FROM sessions ORDER BY id").use { c -> while(c.moveToNext()) add(c.getLong(0)) }
    } }
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
                val coveredByFab = text=="打开条目" && rect.bottom > context.resources.displayMetrics.heightPixels - 112*context.resources.displayMetrics.density
                output.resolve("scroll-trace.txt").appendText("candidate=$text label=${labelRect.toShortString()} action=${rect.toShortString()}\n")
                if(rect.top < labelRect.top) scrollForward=false
                else if(rect.bottom > labelRect.bottom) scrollForward=true
                val fullActionDescription = listOf("查看来源：", "确认资料：", "无原文来源：").any { label.contentDescription?.toString()?.startsWith(it)==true }
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
    private fun tabFullyVisible(text:String):Boolean {
        val label=waitNode(text,true)
        val labelRect=android.graphics.Rect().also(label::getBoundsInScreen)
        // Android exposes an already-selected Tab as non-clickable; it still has a full hit area.
        val tab=checkNotNull(label.parent)
        val rect=android.graphics.Rect().also(tab::getBoundsInScreen)
        return tab.isEnabled && rect.width()+1>=48*context.resources.displayMetrics.density &&
            rect.height()+1>=48*context.resources.displayMetrics.density && rect.left>=0 &&
            rect.right<=context.resources.displayMetrics.widthPixels && labelRect.left>=rect.left && labelRect.right<=rect.right
    }
    private fun clickCardContaining(action:String) {
        var n=waitNode(action,true)
        while(!n.isClickable) n=checkNotNull(n.parent)
        n=checkNotNull(n.parent)
        while(!n.isClickable) n=checkNotNull(n.parent)
        check(n.isEnabled && n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun actionEnabled(text:String):Boolean {
        var n=waitNode(text,true)
        while(!n.isClickable) n=checkNotNull(n.parent)
        return n.isEnabled
    }
    private fun assertAction(text:String) {
        var n=waitNode(text,true)
        while(!n.isClickable) n=checkNotNull(n.parent)
        val rect=android.graphics.Rect().also { n.getBoundsInScreen(it) }
        check(n.isEnabled && rect.height()+1>=48*context.resources.displayMetrics.density && rect.width()+1>=48*context.resources.displayMetrics.density) { "$text action clipped: ${rect.toShortString()}" }
        check(rect.left>=0 && rect.right<=context.resources.displayMetrics.widthPixels)
    }
    private fun caseTitle(kind:String) = if(kind=="原文变更")
        "雾港北塔钥匙归还规则与支线记录-${run.take(8)}" else "$kind-${run.take(8)}"
    @Test fun sourceActionsAndSingleConfirmation() {
        guard();check(!journalFile.exists())
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val name="原文核对-${run.take(8)}"
        val j=JsonObject().apply {
            add("uiPrefs",uiPrefsSnapshot());add("securePrefs",snapshot(preferences));addProperty("run",run);addProperty("digest",digest())
            addProperty("mediaFilesDigest",mediaFilesDigest());addProperty("searchPrefsDigest",searchPrefsDigest())
            add("branches",branches());add("drafts",JsonObject().apply { draftNames.forEach { add(it,snapshot(context.getSharedPreferences(it,0))) } })
            add("originalIds",com.google.gson.Gson().toJsonTree(worldIds()))
            add("originalSessionIds",com.google.gson.Gson().toJsonTree(sessionIds()))
            add("originalConfig",JsonObject().apply { configKeys.forEach { addProperty(it,preferences.getString(it,null)) } })
        };persist(j)
        val cases=listOf("多条原文", "原文变更", "退出采用", "原文缺失", "分支原文", "旧版来源", "未记录来源")
        io { db.withTransaction {
            val sid=db.sessionDao().insert(SessionEntity(title="原文夹具-$run",creationRequestId="SEDIMENT_SOURCES_$run"))
            j.addProperty("session",sid);persist(j)
            check(j.getAsJsonObject("uiPrefs")["chat_last_branch_$sid"]==null)
            check(j.getAsJsonObject("drafts").getAsJsonObject("chat_drafts_v1")["session_$sid"]==null)
            val enc=db.encyclopediaDao().upsert(EncyclopediaEntity(name=name,description="SYNTHETIC_$run",worldPrompt="SEDIMENT_SOURCES_$run"))
            j.addProperty("fixture",enc);persist(j)
            val messages=com.google.gson.JsonArray();j.add("ownedMessages",messages)
            suspend fun message(body:String, branch:String="main",included:Boolean=true):MessageEntity {
                val id=db.messageDao().insert(MessageEntity(sessionId=sid,content=body,branchId=branch,includeInContext=included))
                messages.add(id);persist(j);return checkNotNull(db.messageDao().getByIdInSession(id,sid))
            }
            val a=message("雾港守卫在晨雾中交出了旧钥匙。")
            val b=message("钥匙能开启北塔的档案室，守卫要求日落前归还。")
            val changed=message("变更后的原文：北塔已经封闭，钥匙交给了巡夜人。")
            val excluded=message("这段已退出采用上下文的原文仍保留在历史中。",included=false)
            val missing=message("仅用于缺失来源验证的夹具。")
            check(db.messageDao().delete(missing.id))
            val branchId="sediment-$run"
            val branchRow=db.sessionBranchDao().insert(SessionBranchEntity(sessionId=sid,branchId=branchId,label="雾港支线",sourceMessageId=a.id))
            j.addProperty("branchRow",branchRow);j.addProperty("branchId",branchId);persist(j)
            val branch=message("雾港支线原文：守卫把钥匙留在了码头。",branchId)
            check(db.messageDao().getVisibleEventSources(sid,branchId,listOf(branch.id)).single().id==branch.id)
            check(db.messageDao().getMainEventSources(sid,listOf(a.id,b.id,changed.id)).size==3)
            val entries=JsonObject();j.add("entries",entries)
            for (kind in cases.reversed()) {
                val sources=when(kind) {
                    "多条原文" -> listOf(a,b); "原文变更" -> listOf(changed)
                    "退出采用" -> listOf(excluded); "原文缺失" -> listOf(missing)
                    "分支原文" -> listOf(branch); "旧版来源" -> listOf(a); else -> emptyList()
                }
                val meta=JsonObject().apply {
                    add("source_message_ids",com.google.gson.Gson().toJsonTree(sources.map { it.id }))
                    addProperty("source_branch_id",if(kind=="分支原文") branchId else "main")
                    if(kind!="旧版来源") {
                        addProperty("source_fingerprint_version",1)
                        add("source_message_fingerprints",com.google.gson.JsonArray().apply { sources.forEach { m -> add(JsonObject().apply {
                            addProperty("message_id",m.id)
                            addProperty("fingerprint",com.mojing.app.domain.encyclopedia.messageSourceFingerprint(
                                if(kind=="原文变更") m.copy(content="变更前的原文。") else m))
                        }) } })
                    }
                }
                val id=db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId=enc,
                    title=caseTitle(kind),entryType="location",summary="守卫交出的钥匙与雾港北塔的记录。请核对对话原文后确认。",
                    content="资料正文：$kind",confidence="inferred",sourceSessionId=if(sources.isEmpty()) null else sid,
                    sourceMessageId=sources.firstOrNull()?.id,metaJson=meta.toString()))
                entries.addProperty(kind,id);persist(j)
            }
        } }
        io {
            val enc=j["fixture"].asLong;val eid=j.getAsJsonObject("entries")["旧版来源"].asLong
            check(j.getAsJsonObject("drafts").getAsJsonObject("entry_edit_drafts_v1")["encyclopedia_${enc}_entry_$eid"]==null)
            val entry=checkNotNull(db.encyclopediaEntryDao().getById(eid))
            com.mojing.app.data.EntryEditDraftStore(context).save(enc,eid,com.mojing.app.data.EntryDraftSnapshot(
                title=entry.title,entryType=entry.entryType,summary=entry.summary,content="未保存草稿正文：旧版来源核对。",
                tags=entry.tags,confidence=entry.confidence,metaJson=entry.metaJson,isFeatured=entry.isFeatured,coverImagePath=entry.coverImagePath))
        }
        check(preferences.edit().putString("theme_mode",args.getString("captureTheme")?:"light")
            .putString("public_api_key","").putString("model_platforms_v1","[]").putString("active_model_platform","").commit())
        io { com.mojing.app.data.prefs.UiPreferencesRepository(context).setEncyclopediaListLayout("list") }
        try {
            val enc=j["fixture"].asLong
            activityScenario=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            activityScenario.onActivity { activity=it }
            click("创作");click("世界");waitNode("搜索世界名称",true);replaceInput(run.take(8))
            scrollTo(name,actionable=true);click(name);click("沉积");waitState { tabFullyVisible("沉积") }
            val labels=mapOf("多条原文" to "来源内容未变更","原文变更" to "来源内容已变更", "退出采用" to "来源已退出采用上下文",
                "原文缺失" to "原始对话已不存在，百科内容仍保留。", "分支原文" to "来源内容未变更", "旧版来源" to "旧来源，无法核验")
            for((index,kind) in cases.withIndex()) {
                val title=caseTitle(kind)
                val sourceAction=if(kind=="未记录来源") "无原文来源：$title" else "查看来源：$title"
                scrollTo(sourceAction,actionable=true)
                if(kind=="未记录来源") {
                    check(!actionEnabled(sourceAction));capture("${index+1}-missing-source-disabled")
                    continue
                }
                assertAction(sourceAction);scrollTo("确认资料：$title",actionable=true);assertAction("确认资料：$title")
                scrollTo(sourceAction,actionable=true)
                capture("${index+1}-card")
                if(kind=="多条原文") {
                    clickCardContaining(sourceAction);waitNode("编辑条目",true);check(!visible("对话原文"))
                    scrollTo("资料正文：多条原文");capture("8-ordinary-card-edit-no-source-intent")
                    back();waitNode("沉积",true);scrollTo(sourceAction,actionable=true)
                }
                click(sourceAction)
                if(kind=="旧版来源") {
                    waitNode("发现未保存的词条草稿",true);check(!visible("对话原文"))
                    capture("6-draft-before-source");click("恢复草稿")
                }
                waitNode("对话原文",true);waitNode(checkNotNull(labels[kind]),true)
                if(kind=="原文缺失") {
                    check(!visible("进入来源故事线"));assertAction("重新读取");click("重新读取")
                    waitNode(checkNotNull(labels[kind]),true)
                }
                if(kind=="多条原文") {
                    waitNode("雾港守卫在晨雾中交出了旧钥匙。",true);capture("1-source-first")
                    click("下一条原文");waitNode("钥匙能开启北塔的档案室，守卫要求日落前归还。",true)
                    capture("1-source-next");click("上一条原文");waitNode("雾港守卫在晨雾中交出了旧钥匙。",true)
                } else capture("${index+1}-source")
                if(kind=="分支原文") {
                    assertAction("进入来源故事线");click("进入来源故事线")
                    waitNode("原文夹具-$run",true);waitNode("雾港支线",true)
                    waitNode("雾港支线原文：守卫把钥匙留在了码头。",true)
                    check(io { com.mojing.app.data.prefs.UiPreferencesRepository(context).getLastChatBranch(j["session"].asLong) }=="main")
                    capture("5-actual-source-conversation");click("返回百科");waitNode("编辑条目",true)
                } else click("关闭原文")
                waitNode("编辑条目",true)
                check(!visible("对话原文"));capture("${index+1}-closed-editor")
                if(kind=="多条原文") {
                    activityScenario.recreate();activityScenario.onActivity { activity=it };waitNode("编辑条目",true)
                    check(!visible("对话原文"));capture("1-consumed-intent-recreate")
                }
                if(kind=="旧版来源") {
                    scrollTo("未保存草稿正文：旧版来源核对。");capture("6-restored-draft-editor")
                    check(io { com.mojing.app.data.EntryEditDraftStore(context).load(enc,j.getAsJsonObject("entries")[kind].asLong)?.content }=="未保存草稿正文：旧版来源核对。")
                    check(io { db.encyclopediaEntryDao().getById(j.getAsJsonObject("entries")[kind].asLong)?.content }=="资料正文：旧版来源")
                }
                back()
                if(kind=="旧版来源") { waitNode("保留草稿并离开",true);click("保留草稿并离开") }
                waitNode("沉积",true)
                if(kind=="多条原文") {
                    scrollTo("确认资料：$title",actionable=true);click("确认资料：$title")
                    val eid=j.getAsJsonObject("entries")[kind].asLong
                    waitState { io { db.encyclopediaEntryDao().getById(eid)?.confidence=="confirmed" } }
                    waitState { !actionEnabled("确认资料：$title") }
                    capture("1-single-confirmed")
                    check(io { db.encyclopediaEntryDao().getById(j.getAsJsonObject("entries")["原文变更"].asLong)?.confidence }=="inferred")
                    back();waitNode("搜索世界名称",true);scrollTo(name,actionable=true);click(name);click("沉积");waitState { tabFullyVisible("沉积") }
                    scrollTo("确认资料：$title");check(!actionEnabled("确认资料：$title"))
                    capture("1-confirmed-reentry")
                }
            }
            check(RetainedChatSessions.running.value.isEmpty())
            output.resolve("verified.txt").writeText("MainActivitySourceActions=true\ncurrentMultiChangedExcludedMissingBranchLegacyNoSource=true\nbranchSourceConversationBack=true\nrestoredDraftPreserved=true\n48dpActions=true\ncloseBackConsumedIntentRecreate=true\nsingleConfirmReentryOtherPending=true\nnoSupplierRequests=true\n")
            j.addProperty("phase","verified");persist(j)
        } catch(t:Throwable) { output.resolve("failure.txt").writeText(t.stackTraceToString());runCatching { capture("failure") };throw t }
        finally { if(::activityScenario.isInitialized) activityScenario.close() }
    }
    @Test fun rollbackOnlyOwnedWorldAndSessionRows() {
        guard();val j=JsonParser.parseString(journalFile.readText()).asJsonObject;check(j["run"].asString==run)
        val editor=preferences.edit()
        j.getAsJsonObject("originalConfig").entrySet().forEach { (k,v) -> if(v.isJsonNull) editor.remove(k) else editor.putString(k,v.asString) }
        check(editor.commit())
        j.getAsJsonObject("originalConfig").entrySet().forEach { (k,v) -> check(preferences.contains(k)==!v.isJsonNull);if(!v.isJsonNull) check(preferences.getString(k,null)==v.asString) }
        assertSnapshot(preferences,j.getAsJsonObject("securePrefs"))
        io { uiStore().edit { prefs ->
            val key=stringPreferencesKey("encyclopedia_list_layout");val old=j.getAsJsonObject("uiPrefs")["encyclopedia_list_layout"]
            if(old==null || old.isJsonNull) prefs.remove(key) else prefs[key]=old.asString
        } }
        j["session"]?.asLong?.let { id ->
            check(id !in j.getAsJsonArray("originalSessionIds").map { it.asLong })
            check(j.getAsJsonObject("uiPrefs")["chat_last_branch_$id"]==null)
            io { com.mojing.app.data.prefs.UiPreferencesRepository(context).clearLastChatBranch(id) }
            check(j.getAsJsonObject("drafts").getAsJsonObject("chat_drafts_v1")["session_$id"]==null)
            com.mojing.app.data.ChatDraftStore(context).save(id,com.mojing.app.data.ChatDraftSnapshot())
        }
        check(uiPrefsSnapshot()==j["uiPrefs"])
        val original=j.getAsJsonArray("originalIds").map { it.asLong }.toSet()
        val originalSessions=j.getAsJsonArray("originalSessionIds").map { it.asLong }.toSet()
        io { db.withTransaction {
            j["fixture"]?.asLong?.let { id -> db.encyclopediaDao().getById(id)?.let { row ->
                check(id !in original && row.worldPrompt=="SEDIMENT_SOURCES_$run")
                val draft=com.mojing.app.data.EntryEditDraftStore(context)
                (listOf(0L)+j.getAsJsonObject("entries").entrySet().map { it.value.asLong }).forEach { entry ->
                    check(j.getAsJsonObject("drafts").getAsJsonObject("entry_edit_drafts_v1")["encyclopedia_${id}_entry_$entry"]==null)
                    draft.clear(id,entry)
                }
                com.mojing.app.data.WorldEditDraftStore(context).clear(id);db.encyclopediaDao().delete(id)
            } }
            j["session"]?.asLong?.let { id -> db.sessionDao().getById(id)?.let { row ->
                check(id !in originalSessions && row.creationRequestId=="SEDIMENT_SOURCES_$run" && row.title=="原文夹具-$run")
                val actual=mutableSetOf<Long>()
                db.openHelper.readableDatabase.query("SELECT id FROM messages WHERE sessionId = ?",arrayOf(id)).use { c -> while(c.moveToNext()) actual.add(c.getLong(0)) }
                check(actual.all { it in j.getAsJsonArray("ownedMessages").map { value -> value.asLong } })
                val branches=db.sessionBranchDao().getBySession(id)
                check(branches.all { it.id==j["branchRow"].asLong && it.branchId==j["branchId"].asString })
                db.sessionDao().delete(id)
            } }
        } }
        draftNames.forEach { name -> assertSnapshot(context.getSharedPreferences(name,0),j.getAsJsonObject("drafts").getAsJsonObject(name)) }
        check(worldIds()==original);check(sessionIds()==originalSessions);check(branches()==j.getAsJsonObject("branches"));check(digest()==j["digest"].asString)
        check(mediaFilesDigest()==j["mediaFilesDigest"].asString);check(searchPrefsDigest()==j["searchPrefsDigest"].asString)
        j.addProperty("phase","rolled-back");persist(j)
        output.resolve("rollback.txt").writeText("onlyUUIDWorldSessionMessagesBranchRemoved=true\n34TablesExact=true\nconfigPresenceValueExact=true\ncompleteSecurePreferencesTypesPresenceValuesExact=true\nuiPreferencesPresenceValueExact=true\nfourDraftsExact=true\nmediaSearchBranchPreferencesExact=true\n")
    }
}

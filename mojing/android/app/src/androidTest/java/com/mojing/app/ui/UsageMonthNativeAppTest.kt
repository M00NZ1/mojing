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

/** Full MainActivity monthly usage, with private exact preference journal and fresh owned cost records. */
class UsageMonthNativeAppTest {
    private lateinit var activity: MainActivity
    private lateinit var activityScenario: androidx.test.core.app.ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("usageMonthRun").orEmpty().also { UUID.fromString(it) }
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "usage-month-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "usage-month-$run").apply { mkdirs() }
    private val preferences get() = com.mojing.app.data.SecureStorage().apply { init(context) }.let { storage -> storage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(storage) } as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode", "theme_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "usage-month-$run.pending")
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
        check(args.getString("usageMonthCapture")=="true")
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

    private val billingPrefs get() = context.getSharedPreferences("mojing_billing_currency",0)
    private val platform get() = "usage-month-$run"
    private val platformName get() = "雾海用量-${run.take(8)}"
    private fun label(month:java.time.YearMonth)="${month.year}年${month.monthValue}月"
    private fun restore(p:SharedPreferences, expected:JsonObject) {
        val e=p.edit()
        (p.all.keys-expected.keySet()).forEach { e.remove(it) }
        expected.entrySet().forEach { (k,v) -> val row=v.asJsonObject;when(row["type"].asString) {
            "string" -> e.putString(k,row["value"].asString)
            "boolean" -> e.putBoolean(k,row["value"].asBoolean)
            "int" -> e.putInt(k,row["value"].asInt)
            "long" -> e.putLong(k,row["value"].asLong)
            "floatBits" -> e.putFloat(k,Float.fromBits(row["value"].asInt))
            "strings" -> e.putStringSet(k,row["value"].asJsonArray.map { it.asString }.toSet())
            else -> error("invalid preference type")
        } }
        check(e.commit());assertSnapshot(p,expected)
    }
    private fun recreate() { activityScenario.recreate();activityScenario.onActivity { activity=it };inst.waitForIdleSync() }
    @Test fun monthlyFlow() {
        guard();check(!journalFile.exists())
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val j=JsonObject().apply {
            addProperty("run",run);addProperty("digest",digest());addProperty("mediaFilesDigest",mediaFilesDigest());addProperty("searchPrefsDigest",searchPrefsDigest())
            add("securePrefs",snapshot(preferences));add("billingPrefs",snapshot(billingPrefs));add("uiPrefs",uiPrefsSnapshot());add("branches",branches())
            add("drafts",JsonObject().apply { draftNames.forEach { add(it,snapshot(context.getSharedPreferences(it,0))) } })
            add("records",com.google.gson.JsonArray())
        };persist(j)
        val current=java.time.YearMonth.now();val previous=current.minusMonths(1)
        io { db.withTransaction {
            val rows=mutableListOf<Long>()
            listOf(current,previous).forEachIndexed { index,month ->
                rows.add(db.costRecordDao().insert(CostRecordEntity(platformId=platform,platformName=platformName,modelName="雾海月度模型",currency="CNY",
                    costKnown=true,estimatedCost=if(index==0)1.25 else 2.5,totalTokens=if(index==0)1250 else 2500,status="success",
                    createdAt=month.atDay(3).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())))
            }
            j.add("records",com.google.gson.Gson().toJsonTree(rows));persist(j)
        } }
        check(billingPrefs.edit().putString("usd_to_cny","7.2").putString("rate_date","手动").putString("display_currency","CNY").commit())
        check(preferences.edit().putString("theme_mode",args.getString("captureTheme")?:"light").commit())
        try {
            activityScenario=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java);activityScenario.onActivity { activity=it }
            click("设置");click("用量与费用");waitNode(label(current),true);scrollTo(platformName,actionable=true);capture("01-current")
            scrollTo(label(current),forward=false);click("‹");waitNode(label(previous),true)
            val before=args.getString("usageMonthBefore")=="true"
            scrollTo(if(before)"本月费用" else "${label(previous)}费用",forward=false);capture("02-previous")
            check(visible(if(before)"本月 Token" else "${label(previous)} Token"))
            scrollTo(platformName,actionable=true);capture("03-month-platform");click(platformName)
            scrollTo(if(before)"平台合计" else "平台合计 · 全部历史",forward=false);capture("04-platform-history")
            scrollTo("雾海月度模型",actionable=true);click("雾海月度模型");scrollTo("查看请求信息",actionable=true);capture("05-model-history")
            back();waitNode(platformName,true);back();scrollTo(label(previous),forward=false);capture("06-return-month")
            recreate();scrollTo(label(previous),forward=false);capture("07-recreate-month")
            scrollTo(label(previous),forward=false);click("‹");waitNode(label(previous.minusMonths(1)),true)
            scrollTo(if(before)"本月暂无已计价记录" else "该月暂无已计价记录",forward=false);capture("08-empty-month")
            scrollTo(if(before)"本月暂无可绘制的费用数据" else "该月暂无可绘制的费用数据");capture("09-empty-chart")
            recreate();scrollTo(label(previous.minusMonths(1)),forward=false);capture("10-empty-recreate")
            output.resolve("flow.txt").writeText("fullMainActivity=true\npreviousMonthLabels=${!before}\nactivityRecreate=true\nprocessKillNotClaimed=true\nmanualRateNoHTTP=true\n")
        } catch(failure:Throwable) {
            capture("failure");output.resolve("failure-nodes.txt").writeText(nodes().joinToString("\n") { "${it.className} text=${it.text} desc=${it.contentDescription} visible=${it.isVisibleToUser}" });throw failure
        } finally { if(::activityScenario.isInitialized) activityScenario.close() }
    }
    @Test fun rollbackOnlyOwnedCostRecords() {
        guard();val j=JsonParser.parseString(journalFile.readText()).asJsonObject;check(j["run"].asString==run)
        restore(preferences,j.getAsJsonObject("securePrefs"));restore(billingPrefs,j.getAsJsonObject("billingPrefs"))
        val ids=j.getAsJsonArray("records").map { it.asLong }.toSet()
        io { db.withTransaction {
            db.openHelper.readableDatabase.query("SELECT id FROM llm_cost_records WHERE platformId=?",arrayOf(platform)).use { c ->
                val owned=mutableSetOf<Long>();while(c.moveToNext()) owned.add(c.getLong(0));check(owned==ids)
            }
            ids.forEach { id -> db.openHelper.writableDatabase.execSQL("DELETE FROM llm_cost_records WHERE id=? AND platformId=?",arrayOf<Any>(id,platform)) }
        } }
        check(digest()==j["digest"].asString);check(uiPrefsSnapshot()==j["uiPrefs"])
        check(mediaFilesDigest()==j["mediaFilesDigest"].asString);check(searchPrefsDigest()==j["searchPrefsDigest"].asString);check(branches()==j["branches"])
        draftNames.forEach { assertSnapshot(context.getSharedPreferences(it,0),j.getAsJsonObject("drafts").getAsJsonObject(it)) }
        output.resolve("rollback.txt").writeText("onlyOwnedUUIDCostRecordsRemoved=true\n34TablesExact=true\ncompleteSecurePreferencesTypesPresenceValuesExact=true\ncompleteBillingPreferencesTypesPresenceValuesExact=true\nuiPreferencesExact=true\nfourDraftsMediaSearchBranchesExact=true\n")
    }
}

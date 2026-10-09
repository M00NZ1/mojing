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

/** Current production APK story audit. Fresh synthetic story; independent private exact rollback. */
class WorldLibraryFilterNativeAppTest {
    private lateinit var activity: MainActivity
    private lateinit var activityScenario: androidx.test.core.app.ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = inst.targetContext
    private val run get() = args.getString("worldLibraryFilterRun").orEmpty().also { UUID.fromString(it) }
    private val db get() = EntryPointAccessors.fromApplication(context.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
    private val journalFile get() = File(context.filesDir, "world-library-filter-$run.json")
    private val output get() = File(context.getExternalFilesDir(null), "world-library-filter-$run").apply { mkdirs() }
    private val preferences get() = com.mojing.app.data.SecureStorage().apply { init(context) }.let { storage -> storage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.get(storage) } as SharedPreferences
    private val configKeys = listOf("public_api_key", "public_base_url", "public_model", "model_platforms_v1", "active_model_platform", "speaker_turn_mode", "theme_mode")
    private val draftNames = listOf("world_edit_drafts_v1", "character_edit_drafts_v1", "chat_drafts_v1", "entry_edit_drafts_v1")
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun persist(j: JsonObject) {
        val pending = File(context.filesDir, "world-library-filter-$run.pending")
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
            val labelNode=waitNode(text,true)
            var n:android.view.accessibility.AccessibilityNodeInfo?=labelNode
            while(n!=null && !n.isClickable) n=n.parent
            run {
                val bounds=android.graphics.Rect().also { labelNode.getBoundsInScreen(it) }
                if(n?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true) return
                // Compose tab state may change while accessibility reports false. Use the
                // observed on-screen target once; the next page assertion checks the result.
                check(!bounds.isEmpty && bounds.left>=0 && bounds.top>=0)
                val now=android.os.SystemClock.uptimeMillis()
                val down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0)
                val up=android.view.MotionEvent.obtain(now,now+80,android.view.MotionEvent.ACTION_UP,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0)
                try { check(inst.uiAutomation.injectInputEvent(down,true));check(inst.uiAutomation.injectInputEvent(up,true)) }
                finally { down.recycle();up.recycle() }
                return
            }
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



    private var backCaptureIndex = 0
    private fun back() {
        inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        capture("back-${++backCaptureIndex}")
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
        check(args.getString("worldLibraryFilterCapture")=="true")
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
    private val platform get() = "world-library-filter-$run"
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
    private val title get()="雪港回声 · ${run.take(8)}"
    private val characterName get()="艾琳 · ${run.take(8)}"
    private val worldName get()="雪港 · ${run.take(8)}"
    private val userText get()="这一次，我想留下来。\n有些话，我必须亲口说完。"
    @Test fun storyFlow() {
        guard();check(!journalFile.exists())
        inst.uiAutomation.serviceInfo=inst.uiAutomation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val j=JsonObject().apply {
            addProperty("run",run);addProperty("digest",digest());addProperty("mediaFilesDigest",mediaFilesDigest());addProperty("searchPrefsDigest",searchPrefsDigest())
            add("securePrefs",snapshot(preferences));add("billingPrefs",snapshot(billingPrefs));add("uiPrefs",uiPrefsSnapshot());add("branches",branches())
            add("drafts",JsonObject().apply { draftNames.forEach { add(it,snapshot(context.getSharedPreferences(it,0))) } })
            add("storyInput",snapshot(context.getSharedPreferences("story_opening_input_draft_v1",0)))
        };persist(j)
        io { db.withTransaction {
            val wid=db.encyclopediaDao().upsert(EncyclopediaEntity(name=worldName,description="北方海港，常年被雾与雪笼罩。灯塔记录着远行者的归期。",worldPrompt="SYNTHETIC_$run",genreTags="验收资料,世界"))
            j.addProperty("world",wid);persist(j)
            val owned=com.google.gson.JsonArray();j.add("ownedWorlds",owned);persist(j)
            for(n in 1..55) {
                val name="$worldName · ${n.toString().padStart(2,'0')}"
                val extra=db.encyclopediaDao().upsert(EncyclopediaEntity(name=name,worldPrompt="SYNTHETIC_$run",updatedAt=100L,pinnedAt=if(n%2==0) 50L else 0L,entryCount=999))
                owned.add(extra);persist(j)
                db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId=extra,title="港口$n",entryType="location",content="合成正文"))
            }
            val cid=db.characterDao().upsert(CharacterEntity(name=characterName,personaPrompt="冷静而温柔，记录潮汐与远行者的约定。",boundEncyclopediaId=wid))
            j.addProperty("character",cid);persist(j)
            val sid=db.sessionDao().insert(SessionEntity(title=title,creationRequestId=run,summary="风雪中的重逢，一封来自旧灯塔的回信。"))
            j.addProperty("session",sid);persist(j)
            db.participantDao().upsert(SessionParticipantEntity(sessionId=sid,characterId=cid))
            db.sessionWorldDao().upsert(SessionWorldEntity(sessionId=sid,encyclopediaId=wid,worldPrompt="雾港、灯塔与远行者。",narratorEnabled=true,narratorName="叙述者",autoSedimentEnabled=false))
            db.messageDao().insert(MessageEntity(sessionId=sid,speakerType="narrator",content="第一章 风雪中的重逢\n\n风从海面吹来，卷起细碎的雪，拍在港口的木栈上。远处的灯塔在雾中若隐若现，像一枚迟到的回信。"))
            db.messageDao().insert(MessageEntity(sessionId=sid,speakerType="character",characterId=cid,content="你还会回来啊。\n我以为，这次也像上次一样，只是路过。"))
            db.messageDao().insert(MessageEntity(sessionId=sid,speakerType="user",content=userText))
            db.messageDao().insert(MessageEntity(sessionId=sid,speakerType="character",characterId=cid,content="……\n她沉默片刻，望向海面。\n也许，这一次，一切都会不一样。<CHOICES><OPTION>告诉她你离开的真正目的</OPTION><OPTION>询问关于旧灯塔的传闻</OPTION><OPTION>保持沉默，静静陪着她</OPTION></CHOICES>"))
            val source=db.messageDao().insert(MessageEntity(sessionId=sid,speakerType="narrator",content="艾琳接过灯火，开始记录雪港潮汐。"))
            val entry=db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId=wid,title=characterName,entryType="character",summary="守灯人，记录潮汐与来往船只。",content="艾琳住在雪港灯塔，习惯用克制的语气表达最深的牵挂。",tags="人物,雪港",sourceSessionId=sid,sourceMessageId=source,confidence="inferred"))
            val place=db.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(encyclopediaId=wid,title="旧灯塔 · ${run.take(8)}",entryType="location",summary="雾中的灯塔与港口。",content="灯塔位于群山与海潮之间。"))
            db.timelineEventDao().upsert(TimelineEventEntity(encyclopediaId=wid,entryId=entry,title="灯塔初亮 · ${run.take(8)}",description="艾琳接过灯火，开始记录潮汐。",eventTime="前1023年",sortOrder=1))
            db.entryRelationDao().upsert(EntryRelationEntity(encyclopediaId=wid,fromEntryId=entry,toEntryId=place,relationType="守护",label="守灯人守护雪港"))

        } }
        val localPlatform=com.mojing.app.data.ModelPlatform(platform,"本机验收模型","http://127.0.0.1:1/v1","local-audit-no-provider",listOf("雪港验收模型"))
        check(preferences.edit().putString("theme_mode",args.getString("captureTheme")?:"light")
            .putString("model_platforms_v1",com.mojing.app.data.ModelPlatformCodec.encode(listOf(localPlatform)))
            .putString("active_model_platform",platform).putString("public_base_url",localPlatform.baseUrl)
            .putString("public_api_key",localPlatform.apiKey).putString("public_model",localPlatform.selectedModel).commit())
        try {
            activityScenario=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java);activityScenario.onActivity { activity=it }
            click("创作");waitNode("小说创作",true);click("世界");waitNode("新建世界",true)
            replaceInput(run.take(8));hideKeyboardIfOpen();scrollTo("$worldName · 54",actionable=true)
            scrollTo("全部",actionable=true);assertAction("全部");assertAction("置顶");capture("01-all-pinned-order")
            scrollTo("世界排序",actionable=true);click("世界排序");waitNode("名称",true);capture("02-sort-menu")
            click("名称");scrollTo(worldName,actionable=true);scrollTo("2 条目 · 1 人物 · 1 地点");capture("03-name-real-counts")
            scrollTo("下一页",actionable=true);click("下一页");scrollTo("第 2 页");waitNode("第 2 页",true);capture("04-name-page2")
            recreate();scrollTo("第 2 页");waitNode("第 2 页",true);capture("05-recreated-page2")
            scrollTo("$worldName · 24",actionable=true);click("$worldName · 24");waitNode("条目",true);capture("06-world-detail")
            scrollTo("世界设置",actionable=true);click("世界设置");waitNode("世界名称",true)
            replaceInput("$worldName · 24修订");hideKeyboardIfOpen();scrollTo("保存世界设置",actionable=true);click("保存世界设置");waitNode("已保存",true);capture("06b-world-edited")
            back();waitNode("条目",true);back();scrollTo("第 2 页");waitNode("第 2 页",true);capture("07-back-keeps-page")
            scrollTo("置顶",actionable=true);click("置顶");scrollTo("下一页",actionable=true);click("下一页");scrollTo("第 2 页");waitNode("第 2 页",true)
            scrollTo("$worldName · 50",actionable=true);scrollTo("1 条目 · 0 人物 · 1 地点");capture("08-pinned-full-set-page2")
            recreate();scrollTo("第 2 页");waitNode("第 2 页",true);capture("09-pinned-recreated")
            scrollTo("$worldName · 50",actionable=true)
            var rowNode:android.view.accessibility.AccessibilityNodeInfo?=waitNode("$worldName · 50",true)
            while(rowNode!=null && !rowNode.isLongClickable) rowNode=rowNode.parent
            check(rowNode?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK)==true)
            waitNode("取消置顶",true);click("取消置顶");scrollTo("第 1 页");waitNode("第 1 页",true);capture("09b-unpinned-refresh")
            scrollTo("世界排序",actionable=true);click("世界排序");click("最近更新");scrollTo("第 1 页");waitNode("第 1 页",true);capture("10-updated-first-page")
            scrollTo("搜索世界名称");replaceInput("missing-$run");hideKeyboardIfOpen();scrollTo("没有找到置顶世界");capture("11-empty-filter")
            scrollTo("查看全部世界",actionable=true);click("查看全部世界");scrollTo("没有找到世界");capture("12-empty-all")
            scrollTo("搜索世界名称");replaceInput(run.take(8));hideKeyboardIfOpen();scrollTo("世界排序",actionable=true);click("世界排序");click("名称")
            scrollTo(worldName,actionable=true);click(worldName);waitNode("条目",true);capture("13-return-detail")
            back();scrollTo(worldName,actionable=true);scrollTo("2 条目 · 1 人物 · 1 地点");capture("14-final-library")
            output.resolve("flow.txt").writeText("fullMainActivity=true\nsyntheticFixtureNoProvider=true\nB3RequiresSeparateLiveSSE=true\nprocessKillNotClaimed=true\n")
        } catch(failure:Throwable) {
            capture("failure");output.resolve("failure-nodes.txt").writeText(nodes().joinToString("\n") { "${it.className} text=${it.text} desc=${it.contentDescription} visible=${it.isVisibleToUser}" });throw failure
        } finally { if(::activityScenario.isInitialized) activityScenario.close() }
    }
    @Test fun rollbackOnlyOwnedStory() {
        guard();val j=JsonParser.parseString(journalFile.readText()).asJsonObject;check(j["run"].asString==run)
        val sid=j["session"]?.asLong ?: io { db.sessionDao().getByCreationRequestId(run)?.id } ?: 0L
        val cid=j["character"]?.asLong ?: 0L;val wid=j["world"]?.asLong ?: 0L
        io { db.withTransaction {
            if(sid>0L) { db.sessionDao().getById(sid)?.let { check(it.creationRequestId==run); db.sessionDao().delete(sid) } }
            if(cid>0L) { db.characterDao().getById(cid)?.let { check(it.name==characterName); db.characterDao().delete(cid) } }
            j.getAsJsonArray("ownedWorlds")?.forEach { value ->
                val extra=value.asLong
                db.encyclopediaDao().getById(extra)?.let { check(it.name.startsWith(worldName) && it.worldPrompt=="SYNTHETIC_$run");db.encyclopediaDao().delete(extra) }
            }
            if(wid>0L) { db.encyclopediaDao().getById(wid)?.let { check(it.name==worldName && it.worldPrompt=="SYNTHETIC_$run");db.encyclopediaDao().delete(wid) } }
        } }
        if(sid>0L) {
            val p=context.getSharedPreferences("chat_drafts_v1",0);val expected=j.getAsJsonObject("drafts").getAsJsonObject("chat_drafts_v1");val e=p.edit()
            p.all.keys.filter { it=="session_$sid" || it=="reply_recovery_v1_session_$sid" || it.startsWith("chapter_input_v1_${sid}_") }.forEach { check(!expected.has(it));e.remove(it) };check(e.commit())
            io { com.mojing.app.data.prefs.UiPreferencesRepository(context).clearLastChatBranch(sid) }
        }
        restore(preferences,j.getAsJsonObject("securePrefs"));restore(billingPrefs,j.getAsJsonObject("billingPrefs"))
        check(digest()==j["digest"].asString);check(uiPrefsSnapshot()==j["uiPrefs"])
        check(mediaFilesDigest()==j["mediaFilesDigest"].asString);check(searchPrefsDigest()==j["searchPrefsDigest"].asString);check(branches()==j["branches"])
        draftNames.forEach { assertSnapshot(context.getSharedPreferences(it,0),j.getAsJsonObject("drafts").getAsJsonObject(it)) }
        assertSnapshot(context.getSharedPreferences("story_opening_input_draft_v1",0),j.getAsJsonObject("storyInput"))
        output.resolve("rollback.txt").writeText("onlyOwnedUUIDStoryRemoved=true\n34TablesExact=true\ncompleteSecureBillingPreferenceTypesPresenceValuesExact=true\nuiPreferencesExact=true\nfourDraftsMediaSearchBranchesExact=true\n")
    }
}

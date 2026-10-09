package com.mojing.app.ui

import android.graphics.Bitmap
import android.os.Build
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertTextContains
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mojing.app.MainActivity
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.EntryRelationEntity
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.CostRecordEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.data.local.entity.TimelineEventEntity
import dagger.hilt.android.EntryPointAccessors



import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in complete-app visual capture. It deliberately does nothing on normal
 * instrumentation runs: the database is only extended when prototypeCapture=true.
 */
@RunWith(AndroidJUnit4::class)
class PrototypeAppCaptureTest {
    val rule = createAndroidComposeRule<MainActivity>()
    private val themeRule = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val generic = Build.FINGERPRINT.contains("generic", true) || Build.MODEL.startsWith("sdk_gphone") || Build.DEVICE.contains("emulator", true)
            if (args.getString("prototypeCapture") != "true" || !generic) return
            args.getString("captureTheme")?.let { theme ->
                val storage = com.mojing.app.data.SecureStorage().also { it.init(targetContext) }
                capturedStorage = storage
                originalTheme = storage.themeMode
                storage.themeMode = theme
            }
        }
        override fun after() { originalTheme?.let { capturedStorage?.themeMode = it } }
    }
    @get:Rule val orderedRules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(themeRule).around(rule)

    private val args get() = InstrumentationRegistry.getArguments()
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var fixture: Fixture
    private var originalTheme: String? = null
    private var capturedStorage: com.mojing.app.data.SecureStorage? = null

    @Before
    fun requireExplicitGenericCapture() {
        assumeTrue("prototypeCapture=true is required", args.getString("prototypeCapture") == "true")
        val generic = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.startsWith("sdk_gphone", ignoreCase = true) ||
            Build.DEVICE.contains("emulator", ignoreCase = true)
        assumeTrue("prototype capture is restricted to a generic emulator", generic)
        fixture = seedFixture()
        // MainActivity starts with the splash overlay. The normal back semantic
        // dismisses that overlay and leaves the real navigation stack intact.
        pressBack()
    }

    @Test
    fun worldTemplateMergePreservesExistingAndCanCancelCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val label = "归入验收 · ${java.util.UUID.randomUUID().toString().take(6)}"
        val before = runBlocking(Dispatchers.IO) { database.encyclopediaDao().getById(fixture.world.id)!! }
        val templateId = runBlocking(Dispatchers.IO) {
            val id = database.worldTemplateDao().upsert(com.mojing.app.data.local.entity.WorldTemplateEntity(
                label = label, summary = "模板简介", worldPrompt = "模板世界规则", coverImagePath = "template-cover.png"))
            database.worldLoreEntryDao().upsert(com.mojing.app.data.local.entity.WorldLoreEntryEntity(
                worldTemplateId = id, title = fixture.character.title, entryType = "人物", content = "不应覆盖的另一人设"))
            repeat(30) { index -> database.worldLoreEntryDao().upsert(com.mojing.app.data.local.entity.WorldLoreEntryEntity(
                worldTemplateId = id, title = "归入灯塔 $index", entryType = "地点", content = "港湾中的灯塔 $index")) }
            id
        }
        val oldEntry = runBlocking(Dispatchers.IO) { database.encyclopediaEntryDao().getById(fixture.character.id)!! }
        openTemplateMergeForCapture(label)
        val search = rule.onNode(hasSetTextAction() and hasAnyAncestor(hasContentDescription("搜索归入目标世界")), useUnmergedTree = true)
        search.performTextReplacement(fixture.world.name); hideFocusedKeyboardAndWait()
        rule.onNodeWithContentDescription("归入目标世界:${fixture.world.id}").performClick()
        waitForText("预览归入内容"); waitForText("归入世界")
        capture("world-merge-preview-preserves-defaults")
        rule.onNodeWithContentDescription("关闭归入世界").performClick()
        waitForText("设定工坊")
        org.junit.Assert.assertNull(runBlocking(Dispatchers.IO) { database.legacyWorldMappingDao().getByTemplateId(templateId) })
        org.junit.Assert.assertEquals(before, runBlocking(Dispatchers.IO) { database.encyclopediaDao().getById(fixture.world.id) })
        rule.onNodeWithContentDescription("更多操作").performClick()
        rule.onNodeWithText("归入世界").performClick()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasContentDescription("搜索归入目标世界")), useUnmergedTree = true).performTextReplacement(fixture.world.name)
        hideFocusedKeyboardAndWait()
        rule.onNodeWithContentDescription("归入目标世界:${fixture.world.id}").performClick()
        waitForText("预览归入内容")
        rule.onNodeWithContentDescription("确认归入世界").performClick()
        waitForText(fixture.world.name)
        rule.waitUntil(5_000) { runBlocking(Dispatchers.IO) { database.legacyWorldMappingDao().getByTemplateId(templateId)?.encyclopediaId == fixture.world.id } }
        val after = runBlocking(Dispatchers.IO) { database.encyclopediaDao().getById(fixture.world.id)!! }
        org.junit.Assert.assertEquals(before.description, after.description)
        org.junit.Assert.assertEquals(before.worldPrompt, after.worldPrompt)
        org.junit.Assert.assertEquals(before.coverImagePath, after.coverImagePath)
        org.junit.Assert.assertEquals(oldEntry.content, runBlocking(Dispatchers.IO) { database.encyclopediaEntryDao().getById(oldEntry.id)?.content })
        org.junit.Assert.assertEquals(before.entryCount + 30, after.entryCount)
        org.junit.Assert.assertEquals(31, runBlocking(Dispatchers.IO) { database.worldLoreEntryDao().getByTemplate(templateId).size })
        capture("world-merge-existing-opened")
    }

    @Test
    fun worldTemplateMergeExplicitFieldCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val label = "字段验收 · ${java.util.UUID.randomUUID().toString().take(6)}"
        val before = runBlocking(Dispatchers.IO) { database.encyclopediaDao().getById(fixture.world.id)!! }
        val templateId = runBlocking(Dispatchers.IO) { database.worldTemplateDao().upsert(com.mojing.app.data.local.entity.WorldTemplateEntity(
            label = label, summary = "明确不采用的简介", worldPrompt = "每次灯塔鸣响，港湾的潮汐就会倒流。", coverImagePath = "unused.png")) }
        openTemplateMergeForCapture(label)
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasContentDescription("搜索归入目标世界")), useUnmergedTree = true).performTextReplacement(fixture.world.name)
        hideFocusedKeyboardAndWait()
        rule.onNodeWithContentDescription("归入目标世界:${fixture.world.id}").performClick()
        waitForText("预览归入内容")
        rule.onNodeWithTag("世界模板合并内容").performScrollToNode(hasContentDescription("采用模板字段:WORLD_PROMPT"))
        rule.onNodeWithContentDescription("采用模板字段:WORLD_PROMPT").performClick()
        capture("world-merge-explicit-world-rule")
        rule.onNodeWithContentDescription("确认归入世界").performClick()
        rule.waitUntil(5_000) { runBlocking(Dispatchers.IO) { database.legacyWorldMappingDao().getByTemplateId(templateId) != null } }
        waitForText(fixture.world.name)
        val after = runBlocking(Dispatchers.IO) { database.encyclopediaDao().getById(fixture.world.id)!! }
        org.junit.Assert.assertEquals("每次灯塔鸣响，港湾的潮汐就会倒流。", after.worldPrompt)
        org.junit.Assert.assertEquals(before.description, after.description)
        org.junit.Assert.assertEquals(before.coverImagePath, after.coverImagePath)
        org.junit.Assert.assertEquals(before.name, after.name)
        capture("world-merge-selected-field-saved")
    }

    @Test
    fun worldTemplateCreateRemainsAvailableCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val label = "新世界验收 · ${java.util.UUID.randomUUID().toString().take(6)}"
        val templateId = runBlocking(Dispatchers.IO) { database.worldTemplateDao().upsert(com.mojing.app.data.local.entity.WorldTemplateEntity(
            label = label, summary = "海岸上的灯塔世界", worldPrompt = "潮汐与雾中的航行规则")) }
        openTemplateMergeForCapture(label)
        capture("world-merge-target-picker")
        rule.onNodeWithContentDescription("新建世界").performClick()
        rule.waitUntil(5_000) { runBlocking(Dispatchers.IO) { database.legacyWorldMappingDao().getByTemplateId(templateId) != null } }
        waitForText(label)
        val mapping = runBlocking(Dispatchers.IO) { database.legacyWorldMappingDao().getByTemplateId(templateId)!! }
        org.junit.Assert.assertNotEquals(fixture.world.id, mapping.encyclopediaId)
        org.junit.Assert.assertEquals(label, runBlocking(Dispatchers.IO) { database.encyclopediaDao().getById(mapping.encyclopediaId)?.name })
        capture("world-template-new-world-opened")
    }

    private fun openTemplateMergeForCapture(label: String) {
        openCreationWorld()
        rule.onNodeWithContentDescription("更多").performClick()
        rule.onNodeWithText("世界工坊").performClick()
        waitForText("设定工坊")
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(label)
        hideFocusedKeyboardAndWait()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(label).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("更多操作").performClick()
        rule.onNodeWithText("归入世界").performClick()
        waitForText("归入已有世界")
    }

    @Test
    fun characterStateViewCancelClearAndReopenCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val characterId = runBlocking(Dispatchers.IO) { database.participantDao().getBySession(fixture.sessionId).single().characterId }
        val json = """{"mood":"警惕但愿意倾听","attitudeToUser":"仍在观察你的来意","currentGoal":"查明灯塔中旧信的来源","recentKeyActions":["检查港口的潮汐钟"],"knownFacts":["东岸码头昨夜有人停留"],"relationshipChanges":["开始相信你的承诺"]}"""
        runBlocking(Dispatchers.IO) {
            database.characterStateDao().upsert(com.mojing.app.data.local.entity.SessionCharacterStateEntity(
                sessionId = fixture.sessionId, characterId = characterId, branchId = "main", dynamicStateJson = json))
            database.characterStateDao().upsert(com.mojing.app.data.local.entity.SessionCharacterStateEntity(
                sessionId = fixture.sessionId, characterId = characterId, branchId = "state-other-line", dynamicStateJson = "{\"mood\":\"另一条线的情绪\"}"))
        }
        clickBottom("对话"); findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        rule.onNodeWithContentDescription("会话设置与资料").performClick()
        rule.onNodeWithContentDescription("查看角色状态:$characterId").performClick()
        waitForText("警惕但愿意倾听"); waitForText("查明灯塔中旧信的来源")
        capture("character-state-current-line")
        rule.onNodeWithText("清除当前状态").assertIsDisplayed().performClick()
        waitForText("确认清除角色状态"); capture("character-state-clear-confirmation")
        rule.onNodeWithText("取消").performClick()
        org.junit.Assert.assertEquals(json, runBlocking(Dispatchers.IO) { database.characterStateDao().getBySessionAndCharacter(fixture.sessionId, characterId, "main")?.dynamicStateJson })
        rule.onNodeWithText("清除当前状态").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("确认清除角色状态").performClick()
        waitForText("当前还没有自动角色状态。对话达到现有自动更新节奏后会更新。")
        capture("character-state-cleared")
        org.junit.Assert.assertNull(runBlocking(Dispatchers.IO) { database.characterStateDao().getBySessionAndCharacter(fixture.sessionId, characterId, "main") })
        org.junit.Assert.assertNotNull(runBlocking(Dispatchers.IO) { database.characterStateDao().getBySessionAndCharacter(fixture.sessionId, characterId, "state-other-line") })
        rule.onNodeWithContentDescription("关闭角色状态").performClick()
        rule.onNodeWithContentDescription("查看角色状态:$characterId").performClick()
        waitForText("当前还没有自动角色状态。对话达到现有自动更新节奏后会更新。")
        capture("character-state-cleared-reopened")
    }

    @Test
    fun characterStateInvalidAndOversizedDisplayCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val characterId = runBlocking(Dispatchers.IO) { database.participantDao().getBySession(fixture.sessionId).single().characterId }
        val id = runBlocking(Dispatchers.IO) { database.characterStateDao().upsert(com.mojing.app.data.local.entity.SessionCharacterStateEntity(
            sessionId = fixture.sessionId, characterId = characterId, branchId = "main", snapshotIsValid = false,
            dynamicStateJson = "{\"mood\":\"不再沿用的旧情绪\"}")) }
        clickBottom("对话"); findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        rule.onNodeWithContentDescription("会话设置与资料").performClick()
        rule.onNodeWithContentDescription("查看角色状态:$characterId").performClick()
        waitForText("这份自动状态已标记为暂不用于回复。")
        capture("character-state-invalid")
        rule.onNodeWithContentDescription("关闭角色状态").performClick()
        runBlocking(Dispatchers.IO) { database.characterStateDao().upsert(com.mojing.app.data.local.entity.SessionCharacterStateEntity(
            id = id, sessionId = fixture.sessionId, characterId = characterId, branchId = "main", snapshotIsValid = true,
            dynamicStateJson = "{\"mood\":\"" + "过长内容".repeat(10_000) + "\"}")) }
        rule.onNodeWithContentDescription("查看角色状态:$characterId").performClick()
        waitForText("当前状态内容过长，暂时无法展示")
        capture("character-state-oversized")
        org.junit.Assert.assertTrue(runBlocking(Dispatchers.IO) { database.characterStateDao().getBySessionAndCharacter(fixture.sessionId, characterId, "main")!!.snapshotIsValid })
    }

    @Test
    fun eventWholeLineFilterSearchAndResolveCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val targetId = runBlocking(Dispatchers.IO) {
            var oldest = 0L
            repeat(70) { index ->
                val id = database.sessionEventNodeDao().insert(com.mojing.app.data.local.entity.SessionEventNodeEntity(
                    sessionId = fixture.sessionId, title = if (index == 0) "旧灯塔约定" else "已解决事件 $index",
                    description = if (index == 0) "待追问雪港回信" else "已确认的航行记录", resolved = index != 0,
                    createdAt = 1_700_000_000_000L + index))
                if (index == 0) oldest = id
            }
            oldest
        }
        clickBottom("对话"); findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        rule.onNodeWithContentDescription("会话设置与资料").performClick()
        rule.onAllNodesWithText("事件").filter(hasClickAction()).onLast().performClick()
        waitForText("已解决事件 69")
        rule.onNodeWithText("待跟进").performClick()
        waitForText("旧灯塔约定")
        capture("event-entire-line-pending")
        val search = rule.onNodeWithContentDescription("搜索故事线事件", useUnmergedTree = true)
        search.performTextReplacement("雪港")
        hideFocusedKeyboardAndWait(); waitForText("旧灯塔约定")
        capture("event-description-search")
        rule.onNodeWithText("标为已解决").performScrollTo().performClick()
        waitForText("没有匹配的事件，可修改搜索词或切换分类。")
        org.junit.Assert.assertTrue(runBlocking(Dispatchers.IO) {
            database.sessionEventNodeDao().getFilteredPageForBranch(fixture.sessionId, "main", "雪港", true, limit = 24).single().id == targetId
        })
        rule.onNodeWithText("已解决").performClick(); waitForText("旧灯塔约定")
        capture("event-resolved-requery")
        search.performTextReplacement("不存在的线索"); hideFocusedKeyboardAndWait()
        waitForText("没有匹配的事件，可修改搜索词或切换分类。")
        capture("event-search-empty")
    }

    @Test
    fun bookmarkNoteSearchBeyondFirstPageEditAndJumpCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val targetId = runBlocking(Dispatchers.IO) {
            var first = 0L
            repeat(41) { index ->
                val messageId = database.messageDao().insert(MessageEntity(sessionId = fixture.sessionId,
                    speakerType = "narrator", content = if (index == 0) "那封旧信仍在灯塔的木匣里。" else "收藏原文 $index"))
                val id = database.bookmarkDao().insert(com.mojing.app.data.local.entity.MessageBookmarkEntity(
                    sessionId = fixture.sessionId, messageId = messageId,
                    note = if (index == 0) "旧约定：雪港回信" else "航行备注 $index", createdAt = 1_700_000_000_000L + index))
                if (index == 0) first = id
            }
            repeat(100) { database.messageDao().insert(MessageEntity(sessionId = fixture.sessionId, speakerType = "narrator", content = "后续航程 $it")) }
            first
        }
        clickBottom("对话"); findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        rule.onNodeWithContentDescription("会话设置与资料").performClick()
        rule.onAllNodesWithText("书签").filter(hasClickAction()).onLast().performClick()
        waitForText("已加载 40 条收藏 · 还有更早记录")
        val search = rule.onNodeWithContentDescription("搜索收藏备注", useUnmergedTree = true)
        search.performTextReplacement("旧约"); hideFocusedKeyboardAndWait()
        waitForText("旧约定：雪港回信"); waitForText("已找到 1 条收藏")
        capture("bookmark-note-search-old-page")
        rule.onNodeWithContentDescription("编辑收藏备注").performClick()
        rule.onNodeWithContentDescription("收藏备注", useUnmergedTree = true).performTextReplacement("新约定：东岸码头")
        hideFocusedKeyboardAndWait()
        rule.onNodeWithContentDescription("保存备注").performClick()
        waitForText("没有匹配的收藏备注")
        capture("bookmark-edited-note-leaves-results")
        search.performTextReplacement("新约"); hideFocusedKeyboardAndWait()
        waitForText("新约定：东岸码头")
        org.junit.Assert.assertEquals(targetId, runBlocking(Dispatchers.IO) {
            database.bookmarkDao().searchPage(fixture.sessionId, "新约", null, null, 40).single().id
        })
        capture("bookmark-edited-note-found")
        rule.onNodeWithText("那封旧信仍在灯塔的木匣里。").performClick()
        waitForText("那封旧信仍在灯塔的木匣里。")
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("搜索收藏备注").fetchSemanticsNodes().none {
            rule.onNodeWithContentDescription("搜索收藏备注").isDisplayed()
        } }
        rule.onNode(hasText("那封旧信仍在灯塔的木匣里。") and hasAnyAncestor(hasContentDescription("对话正文"))).assertIsDisplayed()
        capture("bookmark-searched-source-opened")
    }

    @Test
    fun sedimentSourceVersionNoticeKeepsEntryContentCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val originalEntry = runBlocking(Dispatchers.IO) { database.encyclopediaEntryDao().getById(fixture.character.id)!! }
        val source = runBlocking(Dispatchers.IO) {
            val message = database.messageDao().getByIdInSession(originalEntry.sourceMessageId!!, fixture.sessionId)!!
            val meta = com.google.gson.Gson().toJson(linkedMapOf("source_branch_id" to "main",
                "source_message_ids" to listOf(message.id), "source_fingerprint_version" to 1,
                "source_message_fingerprints" to listOf(linkedMapOf("message_id" to message.id,
                    "fingerprint" to com.mojing.app.domain.encyclopedia.messageSourceFingerprint(message)))))
            database.encyclopediaEntryDao().upsert(originalEntry.copy(metaJson = meta))
            message
        }
        openCreationWorld(); rule.onNodeWithText(fixture.world.name).performClick()
        rule.onNodeWithText(fixture.character.title).performClick()
        rule.onNodeWithText("查看原文").performScrollTo().performClick()
        waitForText("来源内容未变更"); capture("source-version-current")
        pressBack()
        runBlocking(Dispatchers.IO) { database.messageDao().updateContent(source.id, "灯塔记录已经修订，码头改在东岸。") }
        rule.onNodeWithText("查看原文").performScrollTo().performClick()
        waitForText("来源内容已变更"); waitForText("灯塔记录已经修订，码头改在东岸。")
        capture("source-version-changed")
        pressBack()
        runBlocking(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL("UPDATE messages SET includeInContext=0 WHERE id=?", arrayOf(source.id)) }
        rule.onNodeWithText("查看原文").performScrollTo().performClick()
        waitForText("来源已退出采用上下文"); capture("source-version-excluded")
        org.junit.Assert.assertEquals(originalEntry.content, runBlocking(Dispatchers.IO) {
            database.encyclopediaEntryDao().getById(fixture.character.id)!!.content
        })
    }

    @Test
    fun worldLibraryDeleteKeepsFilterAndFallsBackFromLastPageCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val prefix = "分页验收${java.util.UUID.randomUUID().toString().take(6)}"
        val ids = runBlocking(Dispatchers.IO) {
            database.withTransaction {
                (1..26).map { index -> database.encyclopediaDao().upsert(EncyclopediaEntity(
                    name = "$prefix-$index", updatedAt = index.toLong(),
                )) }
            }
        }
        clickBottom("创作")
        rule.onNodeWithText("世界").performClick()
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(prefix)
        hideKeyboardAndWait()
        waitForText("$prefix-26")
        rule.waitUntil(5_000) { rule.onNodeWithText("下一页").isDisplayed() }
        rule.onNodeWithText("下一页").performClick()
        waitForText("第 2 页")
        rule.onNodeWithText("$prefix-2").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("更多操作").onFirst().performClick()
        rule.onAllNodesWithText("删除").onLast().performClick()
        waitForText("确认删除")
        rule.onAllNodesWithText("删除").onLast().performClick()
        rule.waitUntil(5_000) { runBlocking(Dispatchers.IO) { database.encyclopediaDao().getById(ids[1]) == null } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("$prefix-2").fetchSemanticsNodes().isEmpty() }
        waitForText("$prefix-1")
        rule.onNodeWithText("第 2 页").assertIsDisplayed()
        // Let the native short deletion Toast leave the screenshot and next flow.
        android.os.SystemClock.sleep(2_500)
        capture("world-library-delete-kept-second-page")
        rule.onAllNodesWithContentDescription("更多操作").onFirst().performClick()
        rule.onAllNodesWithText("删除").onLast().performClick()
        waitForText("确认删除")
        rule.onAllNodesWithText("删除").onLast().performClick()
        rule.waitUntil(5_000) { runBlocking(Dispatchers.IO) { database.encyclopediaDao().getById(ids[0]) == null } }
        waitForText("第 1 页")
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().assertTextContains(prefix)
        rule.onNodeWithText("下一页").assertIsNotEnabled()
        android.os.SystemClock.sleep(2_500)
        capture("world-library-delete-fell-back-first-page")
    }

    @Test
    fun removedManualSpeakerKeepsDraftCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val storage = com.mojing.app.data.SecureStorage().also { it.init(targetContext) }
        val originalMode = storage.speakerTurnMode
        val participant = runBlocking(Dispatchers.IO) { database.participantDao().getBySession(fixture.sessionId).single() }
        try {
            storage.speakerTurnMode = "manual"
            clickBottom("对话")
            findFixtureStory()
            rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
            waitForText(fixture.messageText)
            rule.onAllNodesWithText(fixture.characterName).filter(hasClickAction()).onLast().performClick()
            val draft = "角色移出后仍保留这段输入"
            rule.onNodeWithContentDescription("消息输入", useUnmergedTree = true).performTextReplacement(draft)
            hideKeyboardAndWait()
            runBlocking(Dispatchers.IO) { database.participantDao().delete(participant.id) }
            rule.onNodeWithContentDescription("发送").performClick()
            waitForText("选中的发言角色已移出当前对话，请重新选择")
            rule.onNodeWithContentDescription("消息输入", useUnmergedTree = true).assertTextContains(draft)
            org.junit.Assert.assertTrue(rule.onAllNodesWithText(fixture.characterName).filter(isSelectable()).fetchSemanticsNodes().isEmpty())
            org.junit.Assert.assertFalse(runBlocking(Dispatchers.IO) {
                database.messageDao().getMainMessagesTail(fixture.sessionId, 20).any { it.content == draft }
            })
            capture("manual-speaker-removed-draft-retained")
        } finally {
            runBlocking(Dispatchers.IO) { database.participantDao().upsert(participant) }
            storage.speakerTurnMode = originalMode
        }
    }

    @Test
    fun legacyChapterRenameKeepsSingleHeadingCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val id = runBlocking(Dispatchers.IO) {
            database.sessionWorldDao().getBySession(fixture.sessionId)!!.let {
                database.sessionWorldDao().upsert(it.copy(gameplayMode = "小说创作"))
            }
            database.messageDao().insert(MessageEntity(sessionId = fixture.sessionId, speakerType = "narrator",
                content = "#夜雨\n\n她在夜雨中走向码头。"))
        }
        clickBottom("对话")
        findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        rule.waitForIdle()
        if (rule.onNodeWithContentDescription("小说目录").isDisplayed()) {
            rule.onNodeWithContentDescription("小说目录").performClick()
        } else {
            rule.onNodeWithContentDescription("会话菜单").performClick()
            rule.onNodeWithText("小说目录").performClick()
        }
        waitForText("夜雨")
        rule.onNodeWithContentDescription("修改章节名称：夜雨").performClick()
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTextReplacement("渡口")
        hideFocusedKeyboardAndWait()
        capture("legacy-chapter-rename-editor")
        rule.onNodeWithText("保存").performClick()
        waitForText("渡口")
        val saved = runBlocking(Dispatchers.IO) { database.messageDao().getById(id)!! }
        org.junit.Assert.assertEquals("渡口\n\n她在夜雨中走向码头。", saved.content)
        org.junit.Assert.assertEquals("她在夜雨中走向码头。", com.mojing.app.domain.story.NovelChapter.body(saved))
        capture("legacy-chapter-renamed-directory")
        rule.onNodeWithText("渡口").performClick()
        waitForText("她在夜雨中走向码头。", substring = true)
        capture("legacy-chapter-renamed-reader")
    }

    @Test
    fun entryDeleteFailureRetryReturnsFromLastPageCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val lastTitle = "末页待删除条目"
        val lastId = runBlocking(Dispatchers.IO) {
            database.withTransaction {
                var id = 0L
                repeat(99) { index ->
                    id = database.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(
                        encyclopediaId = fixture.world.id, entryType = "location",
                        title = if (index == 98) lastTitle else "验收地点 $index",
                    ))
                }
                id
            }
        }
        val trigger = "capture_delete_failure_$lastId"
        runBlocking(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER $trigger BEFORE DELETE ON encyclopedia_entries WHEN OLD.id = $lastId BEGIN SELECT RAISE(ABORT, 'capture delete failure'); END")
        }
        try {
            openCreationWorld()
            rule.onNodeWithText(fixture.world.name).performClick()
            rule.onNodeWithText("下一页").performClick()
            waitForText(lastTitle)
            rule.onNodeWithText(lastTitle).performScrollTo().performTouchInput { longClick() }
            rule.onAllNodesWithText("删除").onLast().performClick()
            waitForText("确认删除条目")
            rule.onNodeWithText("取消").performClick()
            org.junit.Assert.assertNotNull(runBlocking(Dispatchers.IO) { database.encyclopediaEntryDao().getById(lastId) })
            rule.onNodeWithText(lastTitle).performTouchInput { longClick() }
            rule.onAllNodesWithText("删除").onLast().performClick()
            rule.onAllNodesWithText("删除").onLast().performClick()
            waitForText("删除失败，请重试")
            org.junit.Assert.assertNotNull(runBlocking(Dispatchers.IO) { database.encyclopediaEntryDao().getById(lastId) })
            capture("entry-delete-failed-retained")
            runBlocking(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL("DROP TRIGGER $trigger") }
            rule.onAllNodesWithText("删除").onLast().performClick()
            rule.waitUntil(5_000) {
                runBlocking(Dispatchers.IO) { database.encyclopediaEntryDao().getById(lastId) == null } &&
                    rule.onAllNodesWithText("确认删除条目").fetchSemanticsNodes().isEmpty()
            }
            rule.onNodeWithText("第 1 页").assertIsDisplayed()
            rule.onNodeWithText("下一页").assertIsNotEnabled()
            rule.onNodeWithText("100 条目").assertIsDisplayed()
            capture("entry-delete-returned-first-page")
        } finally {
            runBlocking(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger") }
        }
    }

    @Test
    fun platformDeleteCancelAndDefaultCapture() {
        val storage = com.mojing.app.data.SecureStorage().also { it.init(targetContext) }
        val field = storage.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
        val preferences = field.get(storage) as android.content.SharedPreferences
        val keys = listOf("model_platforms_v1", "active_model_platform", "public_api_key", "public_base_url", "public_model")
        // Preserve only the keys this synthetic scenario changes, in memory, never in test output.
        val before = keys.associateWith { preferences.getString(it, null) }
        val suffix = java.util.UUID.randomUUID().toString().take(6)
        val default = com.mojing.app.data.ModelPlatform("capture-default-$suffix", "默认验收$suffix", "https://example.invalid/v1", "synthetic-only", listOf("fixture-model"))
        val secondary = default.copy(id = "capture-secondary-$suffix", name = "备用验收$suffix")
        try {
            storage.saveModelPlatform(default)
            storage.saveModelPlatform(secondary, makeDefault = false)
            clickBottom("设置")
            rule.onNodeWithText("平台与模型").performClick()
            rule.onNodeWithContentDescription("删除平台${secondary.name}").performScrollTo().performClick()
            rule.onNodeWithText("取消").performClick()
            org.junit.Assert.assertTrue(storage.modelPlatforms().any { it.id == secondary.id })
            rule.onNodeWithContentDescription("删除平台${secondary.name}").performClick()
            capture("platform-delete-confirm")
            rule.onNodeWithText("删除").performClick()
            rule.waitUntil(5_000) { storage.modelPlatforms().none { it.id == secondary.id } }
            org.junit.Assert.assertEquals(default.id, storage.activeModelPlatformId())
            org.junit.Assert.assertEquals(default.apiKey, storage.publicApiKey)
            rule.onNodeWithContentDescription("删除平台${default.name}").performScrollTo().performClick()
            rule.onNodeWithText("这是默认平台", substring = true).assertIsDisplayed()
            capture("platform-delete-default-warning")
            rule.onNodeWithText("删除").performClick()
            rule.waitUntil(5_000) { storage.modelPlatforms().none { it.id == default.id } }
            org.junit.Assert.assertEquals("", storage.activeModelPlatformId())
            org.junit.Assert.assertEquals("", storage.publicApiKey)
            org.junit.Assert.assertEquals("", storage.publicModel)
            rule.onNodeWithText("添加平台").performScrollTo().performClick()
            rule.onNodeWithText("请输入平台名").assertIsDisplayed()
            capture("platform-add-after-delete")
            rule.onNodeWithText("取消").performClick()
        } finally {
            val editor = preferences.edit()
            before.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
            check(editor.commit()) { "Could not restore platform fixture configuration" }
        }
    }

    @Test
    fun storyPinUnpinAndFilteredLibraryCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        clickBottom("对话")
        findFixtureStory()
        val before = runBlocking(Dispatchers.IO) { database.sessionDao().getById(fixture.sessionId)!! }
        rule.onAllNodesWithContentDescription("更多操作").onFirst().performClick()
        rule.onAllNodesWithText("置顶").onLast().performClick()
        rule.waitUntil(5_000) {
            runBlocking(Dispatchers.IO) { database.sessionDao().getById(fixture.sessionId)!!.pinnedAt > 0L }
        }
        rule.onNodeWithText("已置顶").performClick()
        waitForText(fixture.sessionTitle)
        capture("story-library-pinned")
        rule.onAllNodesWithContentDescription("更多操作").onFirst().performClick()
        capture("story-library-unpin-menu")
        rule.onAllNodesWithText("取消置顶").onLast().performClick()
        rule.waitUntil(5_000) {
            runBlocking(Dispatchers.IO) { database.sessionDao().getById(fixture.sessionId)!!.pinnedAt == 0L } &&
                rule.onAllNodesWithContentDescription("更多操作").fetchSemanticsNodes().isEmpty()
        }
        capture("story-library-pinned-empty")
        rule.onNodeWithText("全部").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("更多操作").fetchSemanticsNodes().isNotEmpty() }
        org.junit.Assert.assertEquals(before, runBlocking(Dispatchers.IO) { database.sessionDao().getById(fixture.sessionId) })
        capture("story-library-unpinned")
    }

    @Test
    fun mediaPickerCancelKeepsDraftAndReturnsToChatCapture() {
        clickBottom("对话")
        findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        waitForText(fixture.messageText)
        val draft = "取消选择图片后仍保留的输入。"
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTextReplacement(draft)
        hideFocusedKeyboardAndWait()
        rule.onNodeWithContentDescription("更多输入工具").performClick()
        waitForText("输入工具")
        capture("media-input-tools")
        rule.onNodeWithText("添加图片").performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        rule.waitUntil(5_000) {
            val activePackage = automation.rootInActiveWindow?.packageName?.toString().orEmpty()
            activePackage.contains("documentsui") || activePackage.contains("photopicker") ||
                activePackage.contains("providers.media")
        }
        capture("media-system-picker")
        pressBack()
        waitForText(fixture.sessionTitle)
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().assertTextContains(draft)
        rule.onNodeWithContentDescription("更多输入工具").performClick()
        rule.onNodeWithText("生成配图").performClick()
        waitForText("生成并加入对话")
        rule.onNodeWithText("生成并加入对话").assertIsNotEnabled()
        capture("media-empty-generation")
        pressBack()
        waitForText(fixture.sessionTitle)
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().assertTextContains(draft)
        capture("media-cancel-kept-draft")
    }

    @Test
    fun resultConflictPreviewApplyAndReopenCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val original = "用户刚刚写下的世界设定：港口实行夜航禁令。"
        val generated = "生成结果：守灯人与远行者在风雪中重逢。"
        val title = "结果验收 · ${fixture.sessionId}"
        val ids = runBlocking(Dispatchers.IO) {
            val world = database.worldTemplateDao().upsert(com.mojing.app.data.local.entity.WorldTemplateEntity(label = title, worldPrompt = original))
            val task = database.generationTaskDao().insert(com.mojing.app.data.local.entity.GenerationTaskEntity(
                taskKind = com.mojing.app.data.local.entity.GenerationTaskKinds.WORLD_TEMPLATE_PROMPT_AI,
                title = title, status = com.mojing.app.data.local.entity.GenerationTaskStatus.COMPLETED,
                targetWorldTemplateId = world, payloadJson = "{}",
                resultJson = com.mojing.app.domain.generation.GenerationResultSnapshotCodec.encode(
                    com.mojing.app.domain.generation.GenerationResultSnapshot.WorldTemplate(null, generated))))
            world to task
        }
        clickBottom("对话")
        rule.onNodeWithText("生成记录").performClick()
        waitForText(title)
        rule.onNodeWithText(title).performClick()
        waitForText("对比并应用")
        rule.onNodeWithText("对比并应用").performScrollTo().performClick()
        waitForText("应用生成结果")
        waitForText(original)
        rule.onNodeWithText(original).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(generated).performScrollTo().assertIsDisplayed()
        capture("result-current-and-generated")
        rule.onNodeWithText("取消").performClick()
        check(runBlocking(Dispatchers.IO) { database.worldTemplateDao().getById(ids.first)?.worldPrompt } == original)
        rule.onNodeWithText("对比并应用").performScrollTo().performClick()
        waitForText("确认应用")
        rule.onNodeWithText("确认应用").performClick()
        rule.waitUntil(5_000) { runBlocking(Dispatchers.IO) { database.generationTaskDao().getById(ids.second)?.resultAppliedAt != null } }
        check(runBlocking(Dispatchers.IO) { database.worldTemplateDao().getById(ids.first)?.worldPrompt } == generated)
        capture("result-applied-record")
        pressBack()
        rule.onNodeWithText(title).performClick()
        capture("result-reopened-record")
    }

    @Test
    fun messageDraftRecreateRetainAndReopenCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val original = runBlocking(Dispatchers.IO) {
            database.messageDao().getMainBranchMessages(fixture.sessionId).single { it.content == fixture.messageText }.also {
                // This fixture edits narration, so acceptance never sends a provider request.
                database.openHelper.writableDatabase.execSQL("UPDATE messages SET speakerType = 'narrator' WHERE id = ?", arrayOf(it.id))
            }
        }
        clickBottom("对话")
        findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        waitForText(fixture.messageText)
        rule.onNodeWithText(fixture.messageText).performClick()
        waitForText("编辑")
        rule.onNodeWithText("编辑").performClick()
        waitForText("编辑消息")
        val content = "未提交的消息修订：守灯人确认了明日的约定。".repeat(900)
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTextReplacement(content)
        hideFocusedKeyboardAndWait()
        rule.activityRule.scenario.recreate()
        waitForText("编辑消息")
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().assertTextContains(content)
        rule.onNodeWithText("创建编辑分支").assertIsEnabled()
        capture("message-editor-recreated")
        rule.onNodeWithContentDescription("关闭消息编辑").performClick()
        waitForText("保留草稿")
        rule.onNodeWithText("保留草稿").performClick()
        waitForText(fixture.messageText)
        rule.onNodeWithText(fixture.messageText).performClick()
        rule.onNodeWithText("编辑").performClick()
        waitForText("编辑消息")
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().assertTextContains(content)
        rule.onNodeWithText("创建编辑分支").assertIsEnabled()
        capture("message-editor-reopened-draft")
        rule.onNodeWithContentDescription("关闭消息编辑").performClick()
        rule.onNodeWithText("放弃修改").performClick()
        waitForText(fixture.messageText)
        rule.onNodeWithText(fixture.messageText).performClick()
        rule.onNodeWithText("编辑").performClick()
        waitForText("编辑消息")
        val committed = "修订后的旁白：守灯人确认明天一早出发。"
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTextReplacement(committed)
        hideFocusedKeyboardAndWait()
        rule.onNodeWithText("创建编辑分支").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("编辑消息").fetchSemanticsNodes().isEmpty() }
        waitForText(committed)
        runBlocking(Dispatchers.IO) {
            check(database.messageDao().getById(original.id)?.content == fixture.messageText)
            val branches = database.sessionBranchDao().getBySession(fixture.sessionId).filter { it.branchId != "main" }
            check(branches.size == 1)
            check(database.messageDao().getBranchMessages(fixture.sessionId, branches.single().branchId).single().content == committed)
        }
        capture("message-editor-saved-once")
    }

    @Test
    fun worldDraftRecreateLeaveRestoreSaveCapture() {
        openCreationWorld()
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(fixture.world.name)
        hideFocusedKeyboardAndWait()
        rule.onAllNodesWithContentDescription("更多操作").onFirst().performClick()
        rule.onNodeWithText("世界设置").performClick()
        waitForText("世界名称")
        val changed = "${fixture.world.name} · 重建后保留"
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(changed)
        hideFocusedKeyboardAndWait()
        rule.activityRule.scenario.recreate()
        waitForText("世界名称")
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().assertTextContains(changed)
        capture("world-editor-recreated")
        pressBack()
        waitForText("保留草稿并离开")
        rule.onNodeWithText("保留草稿并离开").performClick()
        rule.onAllNodesWithContentDescription("更多操作").onFirst().performClick()
        rule.onNodeWithText("世界设置").performClick()
        waitForText("恢复草稿")
        capture("world-recover-draft")
        rule.onNodeWithText("恢复草稿").performClick()
        rule.onNodeWithText("保存世界设置").performClick()
        waitForText("已保存")
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        check(runBlocking(Dispatchers.IO) { database.encyclopediaDao().getById(fixture.world.id)?.name } == changed)
        capture("world-draft-saved")
    }

    @Test
    fun characterDraftRecreationAndSaveCapture() {
        clickBottom("创作")
        rule.onNodeWithText("角色").performClick()
        rule.onNodeWithText(fixture.characterName).performClick()
        rule.onNodeWithContentDescription("编辑角色").performClick()
        waitForText("姓名")
        val changed = "${fixture.characterName} · 已编辑"
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(changed)
        hideFocusedKeyboardAndWait()
        rule.activityRule.scenario.recreate()
        waitForText("姓名")
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().assertTextContains(changed)
        capture("character-editor-recreated")
        rule.onAllNodesWithText("保存").filter(hasClickAction()).onLast().performClick()
        waitForText("已保存")
        capture("character-editor-saved")
    }

    @Test
    fun novelDurableMultiBatchRecoveryAndSaveCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val store = com.mojing.app.data.StoryOpeningInputDraftStore(targetContext)
        val prefs = targetContext.getSharedPreferences("story_opening_input_draft_v1", 0)
        val before = prefs.all.mapValues { it.value as String }
        val recordKey = com.mojing.app.domain.story.StoryOpeningDraftCodec.KEY
        val receipt = runBlocking(Dispatchers.IO) { database.configDao().get(recordKey) }
        assumeTrue("Do not replace an active generation", store.loadGeneration() == null)
        assumeTrue("Do not replace a pending story", receipt == null || com.mojing.app.domain.story.StoryOpeningDraftCodec.decode(receipt.valueJson) is com.mojing.app.domain.story.StoryOpeningRecord.Saved)
        val id = java.util.UUID.randomUUID().toString()
        val title = "恢复验收${id.take(6)}"
        val chapters = (1..4).map { com.mojing.app.domain.story.StoryChapter(it, "第 $it 章", "第$it 章守灯人的约定。".repeat(2100)) }
        val partial = "未完成的第五章正文。".repeat(2100)
        val gson = com.google.gson.Gson()
        val first = gson.toJson(mapOf("chapters" to chapters.take(3)))
        val second = "{\"chapters\":[${gson.toJson(chapters.last())},{\"title\":\"第五章\",\"content\":${gson.toJson(partial).dropLast(1)}"
        try {
            runBlocking(Dispatchers.IO) {
                if (receipt != null) database.configDao().delete(recordKey)
                store.beginGeneration(com.mojing.app.data.StoryOpeningGenerationState(id,
                    com.mojing.app.data.StoryOpeningInputDraft(title, "", "", 5, null, null, emptySet()),
                    partial.takeLast(12000), "local-fixture", "已停止", first.length + second.length, 1000))
                check(store.appendGenerationDelta(id, first, 0, offset = 0))
                check(store.appendGenerationDelta(id, second, 1, offset = 0))
                check(store.persistCompletedChapters(id, chapters))
            }
            clickBottom("创作")
            rule.onNodeWithText("小说创作").performClick()
            waitForText("上次生成中断")
            capture("novel-complete-batches-and-partial-recovered")
            rule.activityRule.scenario.recreate()
            waitForText("上次生成中断")
            rule.onNodeWithText("保存已收到草稿").performScrollTo().performClick()
            rule.waitUntil(15_000) { runBlocking(Dispatchers.IO) {
                database.configDao().get(recordKey)?.let { com.mojing.app.domain.story.StoryOpeningDraftCodec.decode(it.valueJson) is com.mojing.app.domain.story.StoryOpeningRecord.Saved } == true
            } }
            val saved = runBlocking(Dispatchers.IO) { com.mojing.app.domain.story.StoryOpeningDraftCodec.decode(database.configDao().get(recordKey)!!.valueJson) } as com.mojing.app.domain.story.StoryOpeningRecord.Saved
            val messages = runBlocking(Dispatchers.IO) { database.messageDao().getMainMessagesBefore(saved.sessionId, Long.MAX_VALUE, 10).filter { it.speakerType == "narrator" }.sortedBy { it.id } }
            check(messages.size == 5)
            chapters.forEachIndexed { index, chapter -> check(messages[index].content.contains(chapter.content)) }
            check(messages.last().content.contains(partial))
            check(com.google.gson.JsonParser.parseString(messages.last().structuredContentJson).asJsonObject.get("chapter_incomplete").asBoolean)
            rule.waitUntil(5_000) {
                com.mojing.app.data.StoryOpeningInputDraftStore(targetContext).loadGeneration() == null
            }
            capture("novel-restored-five-chapters-saved")
        } finally {
            runBlocking(Dispatchers.IO) {
                store.clearGeneration(id)
                if (receipt == null) database.configDao().delete(recordKey) else database.configDao().set(receipt)
            }
            prefs.edit().clear().apply { before.forEach { (key, value) -> putString(key, value) } }.commit()
        }
    }

    @Test
    fun summaryEditDeleteAndOriginalPreservedCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val source = "摘要验收：灯塔约定不会因删除摘要消失。"
        val summary = "自动摘要：明日回到港口。"
        val ids = runBlocking(Dispatchers.IO) {
            val message = database.messageDao().insert(MessageEntity(sessionId = fixture.sessionId, speakerType = "narrator", content = source))
            val segment = database.sessionMemorySegmentDao().insert(com.mojing.app.data.local.entity.SessionMemorySegmentEntity(
                sessionId = fixture.sessionId, startMessageId = message, endMessageId = message, summary = summary))
            message to segment
        }
        clickBottom("对话")
        findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        waitForText(source)
        rule.onNodeWithContentDescription("会话设置与资料").performClick()
        rule.onAllNodesWithText("记忆").filter(hasClickAction()).onLast().performClick()
        rule.onNodeWithText("自动摘要").performClick()
        waitForText(summary)
        rule.onNodeWithText("编辑").performScrollTo().performClick()
        waitForText("编辑自动摘要")
        val changed = "用户修订：改在暮色降临后回到港口。"
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTextReplacement(changed)
        hideFocusedKeyboardAndWait()
        capture("summary-editor")
        rule.onNodeWithText("保存").performClick()
        waitForText(changed)
        capture("summary-edited")
        rule.onNodeWithText("删除").performScrollTo().performClick()
        waitForText("确认删除")
        capture("summary-delete-confirm")
        rule.onNodeWithText("确认删除").performClick()
        rule.waitUntil(5_000) { runBlocking(Dispatchers.IO) { database.sessionMemorySegmentDao().getBySessionAndBranch(fixture.sessionId, "main").none { it.id == ids.second } } }
        check(runBlocking(Dispatchers.IO) { database.messageDao().getById(ids.first)?.content } == source)
    }

    @Test
    fun allStorylineSearchOpensSideBranchAndKeepsOriginalCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val branch = "search-acceptance-${fixture.sessionId}"
        val text = "翡翠信笺：这段原文仅发生在归航故事线。"
        runBlocking(Dispatchers.IO) {
            val anchor = database.messageDao().getMainMessagesBefore(fixture.sessionId, Long.MAX_VALUE, 1).single().id
            database.sessionBranchDao().insert(com.mojing.app.data.local.entity.SessionBranchEntity(
                sessionId = fixture.sessionId, branchId = branch, sourceMessageId = anchor, label = "归航故事线"))
            database.messageDao().insert(MessageEntity(sessionId = fixture.sessionId, branchId = branch, speakerType = "narrator", content = text))
        }
        clickBottom("对话")
        findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        waitForText(fixture.messageText)
        rule.onNodeWithContentDescription("会话菜单").performClick()
        rule.onNodeWithText("搜索消息").performClick()
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement("翡翠信笺")
        hideFocusedKeyboardAndWait()
        rule.onNodeWithContentDescription("搜索").performClick()
        waitForText("共 0 条匹配消息")
        rule.onAllNodesWithText("全部故事线").filter(hasClickAction()).onFirst().performClick()
        rule.onNodeWithContentDescription("搜索").performClick()
        waitForText("共 1 条匹配消息")
        waitForText("归航故事线")
        capture("search-all-storylines-result")
        rule.onNodeWithText(text, substring = true).performClick()
        waitForText("打开对话")
        capture("search-side-branch-original")
        rule.onNodeWithText("打开对话").performClick()
        waitForText(text)
        capture("search-side-branch-opened")
        pressBack()
        findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        waitForText(text)
    }

    @Test
    fun largeHistorySearchAndOldWindowCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val oldText = "琥珀航图定位原文：守灯人把银色罗盘交给旅人。"
        val body = "海港灯塔的守夜人沿石阶走向码头，确认远行者留下的约定。".repeat(10)
        runBlocking(Dispatchers.IO) {
            for (batch in 0 until 5000 step 128) {
                database.messageDao().insertAll((batch until minOf(batch + 128, 5000)).map { index ->
                    MessageEntity(sessionId = fixture.sessionId, speakerType = "narrator",
                        content = if (index == 37) oldText else "航海记录 $index\n$body")
                })
            }
        }
        clickBottom("对话")
        findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        waitForText("航海记录 4999", substring = true)
        capture("large-history-latest")
        rule.onNodeWithContentDescription("会话菜单").performClick()
        rule.onNodeWithText("搜索消息").performClick()
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement("琥珀航图")
        hideFocusedKeyboardAndWait()
        rule.onNodeWithContentDescription("搜索").performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithText("共 1 条匹配消息").fetchSemanticsNodes().isNotEmpty() }
        capture("large-history-old-search-hit")
        rule.onNodeWithText(oldText, substring = true).performClick()
        waitForText("打开对话")
        rule.onNodeWithText("打开对话").performClick()
        waitForText(oldText)
        capture("large-history-old-window")
        rule.onNodeWithText("回到最新").performClick()
        waitForText("航海记录 4999", substring = true)
        capture("large-history-back-to-latest")
    }

    @Test
    fun templateDraftLeaveRestoreAndSaveCapture() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val database = EntryPointAccessors.fromApplication(app, PrototypeDatabaseEntryPoint::class.java).database()
        val label = "草稿验收 · ${java.util.UUID.randomUUID().toString().take(6)}"
        val changed = "$label · 修订"
        val id = runBlocking(Dispatchers.IO) {
            database.worldTemplateDao().upsert(com.mojing.app.data.local.entity.WorldTemplateEntity(
                templateId = java.util.UUID.randomUUID().toString(), label = label, worldPrompt = "海港灯塔与守灯人的世界。",
            ))
        }
        openCreationWorld()
        rule.onNodeWithContentDescription("更多").performClick()
        rule.onNodeWithText("世界工坊").performClick()
        waitForText("设定工坊")
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(label)
        hideFocusedKeyboardAndWait()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(label).filter(!hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(label).filter(!hasSetTextAction()).onLast().performClick()
        waitForText("模板名称")
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(changed)
        capture("template-draft-keyboard")
        hideFocusedKeyboardAndWait()
        rule.onNodeWithContentDescription("返回").performClick()
        waitForText("保留草稿并离开")
        capture("template-draft-leave")
        rule.onNodeWithText("保留草稿并离开").performClick()
        waitForText("设定工坊")
        rule.onAllNodesWithText(label).filter(!hasSetTextAction()).onLast().performClick()
        waitForText("恢复草稿")
        capture("template-draft-recover")
        rule.onNodeWithText("恢复草稿").performScrollTo().performClick()
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().assertTextContains(changed)
        rule.onNodeWithText("保存修改").performClick()
        rule.waitUntil(5_000) { runBlocking(Dispatchers.IO) { database.worldTemplateDao().getById(id)?.label == changed } }
        waitForText("已保存")
        capture("template-draft-saved")
        rule.onNodeWithContentDescription("返回").performClick()
        waitForText("设定工坊")
        rule.onAllNodesWithText(changed).filter(!hasSetTextAction()).onLast().performClick()
        waitForText("已保存")
        check(rule.onAllNodesWithText("恢复草稿").fetchSemanticsNodes().isEmpty())
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().assertTextContains(changed)
        capture("template-draft-reopened")
    }

    @Test
    fun memoryClearConfirmationAndSourceRangeCapture() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val database = EntryPointAccessors.fromApplication(app, PrototypeDatabaseEntryPoint::class.java).database()
        val firstText = "验收原文：灯塔约定从这里开始。"
        val lastText = "验收原文：守灯人确认了约定。"
        val summary = "验收摘要：守灯人与远行者约定次日见面。"
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                val first = database.messageDao().insert(MessageEntity(sessionId = fixture.sessionId, speakerType = "narrator", content = firstText))
                val last = database.messageDao().insert(MessageEntity(sessionId = fixture.sessionId, speakerType = "narrator", content = lastText))
                database.sessionMemorySegmentDao().insert(com.mojing.app.data.local.entity.SessionMemorySegmentEntity(
                    sessionId = fixture.sessionId, startMessageId = first, endMessageId = last, summary = summary,
                ))
                database.sessionContextMemoryDao().upsert(com.mojing.app.data.local.entity.SessionContextMemoryEntity(
                    sessionId = fixture.sessionId, globalSummary = "验收长期记忆：保留灯塔约定。",
                ))
            }
        }
        clickBottom("对话")
        findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        waitForText(lastText)
        fun openMemory() {
            rule.onNodeWithContentDescription("会话设置与资料").performClick()
            rule.onAllNodesWithText("记忆").filter(hasClickAction()).onLast().performClick()
            rule.onAllNodesWithText("长期记忆").filter(hasClickAction()).onFirst().performClick()
            waitForText("清空记忆")
        }
        openMemory()
        rule.onNodeWithText("清空记忆").performScrollTo().performClick()
        waitForText("确认清空")
        capture("memory-clear-confirm")
        rule.onNodeWithText("取消").performClick()
        check(runBlocking(Dispatchers.IO) { database.sessionContextMemoryDao().getBySessionAndBranch(fixture.sessionId, "main")?.globalSummary } == "验收长期记忆：保留灯塔约定。")
        rule.onNodeWithText("清空记忆").performClick()
        rule.onNodeWithText("确认清空").performClick()
        rule.waitUntil(5_000) { runBlocking(Dispatchers.IO) { database.sessionContextMemoryDao().getBySessionAndBranch(fixture.sessionId, "main")?.globalSummary.orEmpty().isEmpty() } }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("已清空长期记忆；后续对话会重新整理").fetchSemanticsNodes().isEmpty() }
        capture("memory-clear-complete")
        rule.onNodeWithText("自动摘要").performClick()
        waitForText(summary)
        rule.onNodeWithText("查看结束原文").performScrollTo().assertIsDisplayed()
        capture("memory-source-range")
        rule.onNodeWithText("查看结束原文").performClick()
        waitForText(lastText)
        capture("memory-source-end")
        openMemory()
        rule.onNodeWithText("自动摘要").performClick()
        rule.onNodeWithText("查看起始原文").performScrollTo().performClick()
        waitForText(firstText)
        capture("memory-source-start")
    }

    @Test
    fun optionalChoicesAllowReadingAndFreeInputCapture() {
        assumeTrue(args.getString("captureChoices") == "true")
        clickBottom("对话")
        findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        waitForText("剧情建议 · 可自由输入")
        capture("feedback-choices-initial")
        rule.onNodeWithContentDescription("对话正文").performScrollToNode(hasText(fixture.messageText))
        rule.onNodeWithText(fixture.messageText).performScrollTo().assertIsDisplayed()
        capture("feedback-choices-reading-without-selection")
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast()
            .performTextReplacement("我想先说说自己的计划，不选择以上建议。")
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast()
            .assertTextContains("我想先说说自己的计划，不选择以上建议。")
        rule.onNodeWithContentDescription("对话正文").performScrollToNode(hasText(fixture.messageText))
        rule.onNodeWithText(fixture.messageText).assertIsDisplayed()
        capture("feedback-choices-free-input")
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTextReplacement("")
        hideKeyboardAndWait()
    }

    @Test
    fun bookmarkNoteEditAndReopenCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val original = runBlocking(Dispatchers.IO) {
            val message = database.messageDao().getMainStoryContentsBefore(fixture.sessionId, Long.MAX_VALUE, 1).single()
            val mark = com.mojing.app.data.local.entity.MessageBookmarkEntity(
                sessionId = fixture.sessionId, messageId = message.id, createdAt = 1_700_000_000_000L)
            mark.copy(id = database.bookmarkDao().insert(mark))
        }
        clickBottom("对话")
        findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        rule.onNodeWithContentDescription("会话设置与资料").performClick()
        rule.onAllNodesWithText("书签").filter(hasClickAction()).onLast().performClick()
        waitForText("已加载 1 条收藏")
        rule.onNodeWithContentDescription("编辑收藏备注").performClick()
        val note = "她仍记得旧灯塔的约定，下次回来时追问那封未寄出的信。"
        val input = rule.onNodeWithContentDescription("收藏备注", useUnmergedTree = true)
        input.performTouchInput { click() }
        input.performTextReplacement(note)
        rule.onNodeWithContentDescription("保存备注").assertIsDisplayed()
        capture("bookmark-note-keyboard")
        hideFocusedKeyboardAndWait()
        rule.onNodeWithContentDescription("取消编辑收藏备注").performClick()
        rule.onNodeWithContentDescription("编辑收藏备注").performClick()
        rule.onNodeWithContentDescription("收藏备注", useUnmergedTree = true).assertTextContains(note)
        rule.onNodeWithContentDescription("保存备注").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("保存备注").fetchSemanticsNodes().isEmpty() }
        waitForText(note)
        runBlocking(Dispatchers.IO) {
            val saved = database.bookmarkDao().getByMessageId(original.messageId)!!
            org.junit.Assert.assertEquals(original.id, saved.id)
            org.junit.Assert.assertEquals(original.createdAt, saved.createdAt)
            org.junit.Assert.assertEquals(note, saved.note)
        }
        capture("bookmark-note-saved")
        pressBack()
        rule.onNodeWithContentDescription("会话设置与资料").performClick()
        rule.onAllNodesWithText("书签").filter(hasClickAction()).onLast().performClick()
        waitForText(note)
        capture("bookmark-note-reopened")
        rule.onNodeWithContentDescription("编辑收藏备注").performClick()
        rule.onNodeWithText("清除备注").performClick()
        rule.onNodeWithContentDescription("保存备注").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(note).fetchSemanticsNodes().isEmpty() }
        runBlocking(Dispatchers.IO) {
            org.junit.Assert.assertEquals("", database.bookmarkDao().getByMessageId(original.messageId)!!.note)
        }
    }

    @Test
    fun timelineEditAndRelationNoteCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val original = TimelineEventEntity(encyclopediaId = fixture.world.id, title = "风暴前夜",
            description = "守灯人尚未离开。", eventTime = "旧历八月", sortOrder = 2)
        val relationNote = "守灯人守护雾港，他答应在风暴前点亮旧灯塔。无论船只何时归来，这束灯光都会留在海岸，等待失散的旅人循光回家。"
        val eventId = runBlocking(Dispatchers.IO) {
            val relation = database.entryRelationDao().getByEncyclopedia(fixture.world.id).single()
            database.entryRelationDao().upsert(relation.copy(label = relationNote))
            database.timelineEventDao().upsert(original)
        }
        openCreationWorld()
        rule.onNodeWithText(fixture.world.name).performClick()
        rule.onAllNodesWithText("时间线").filter(hasClickAction()).onLast().performClick()
        waitForText(original.title)
        rule.onNodeWithText(original.title).performClick()
        rule.onNodeWithText("编辑事件").performClick()
        val title = rule.onNodeWithContentDescription("事件标题", useUnmergedTree = true)
        title.performTextReplacement("风暴后的归来")
        val description = rule.onNodeWithContentDescription("事件描述", useUnmergedTree = true)
        description.performScrollTo().performTouchInput { click() }
        description.performTextReplacement("守灯人带回旧信，港口重新亮起灯火。")
        rule.onNodeWithText("保存事件").assertIsDisplayed()
        capture("timeline-edit-keyboard")
        hideFocusedKeyboardAndWait()
        rule.onNodeWithContentDescription("时间标签", useUnmergedTree = true).performScrollTo().performTextReplacement("旧历九月")
        rule.onNodeWithContentDescription("排序", useUnmergedTree = true).performScrollTo().performTextReplacement("3")
        hideFocusedKeyboardAndWait()
        capture("timeline-edit-ready")
        rule.onNodeWithText("保存事件").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("保存事件").fetchSemanticsNodes().isEmpty() }
        waitForText("风暴后的归来")
        runBlocking(Dispatchers.IO) {
            val saved = database.timelineEventDao().getById(eventId)!!
            org.junit.Assert.assertEquals(fixture.world.id, saved.encyclopediaId)
            org.junit.Assert.assertEquals(original.createdAt, saved.createdAt)
            org.junit.Assert.assertEquals("风暴后的归来", saved.title)
            org.junit.Assert.assertEquals("守灯人带回旧信，港口重新亮起灯火。", saved.description)
            org.junit.Assert.assertEquals("旧历九月", saved.eventTime)
            org.junit.Assert.assertEquals(3, saved.sortOrder)
        }
        rule.waitUntil(6_000) { rule.onAllNodesWithText("事件已保存，可按排序翻页查看").fetchSemanticsNodes().isEmpty() }
        capture("timeline-edit-saved")
        rule.onAllNodesWithText("关系图").filter(hasClickAction()).onLast().performClick()
        waitForText("当前页关系图")
        rule.onNodeWithText("展开备注").performScrollTo().performClick()
        rule.onNodeWithText(relationNote).performScrollTo().assertIsDisplayed()
        capture("relation-note-visible")
    }

    @Test
    fun generationHistoryTitleAndKindSearchCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val prefix = "寻迹${fixture.sessionId}"
        val oldTitle = "$prefix · 旧日人物"
        val worldTitle = "$prefix · 世界补写"
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                database.generationTaskDao().insert(com.mojing.app.data.local.entity.GenerationTaskEntity(
                    taskKind = "character_persona_ai", title = oldTitle, status = "COMPLETED", payloadJson = "{}",
                    progressDone = 1, progressTotal = 1))
                database.generationTaskDao().insert(com.mojing.app.data.local.entity.GenerationTaskEntity(
                    taskKind = "encyclopedia_meta_fill", title = worldTitle, status = "COMPLETED", payloadJson = "{}",
                    progressDone = 1, progressTotal = 1, targetEncyclopediaId = fixture.world.id))
                repeat(160) { index -> database.generationTaskDao().insert(com.mojing.app.data.local.entity.GenerationTaskEntity(
                    taskKind = "encyclopedia_entries", title = "验收记录 ${fixture.sessionId} · $index", status = "COMPLETED",
                    payloadJson = "{}", progressDone = 1, progressTotal = 1)) }
            }
        }
        clickBottom("对话")
        rule.onNodeWithText("生成记录").performClick()
        val input = rule.onNodeWithContentDescription("搜索生成记录", useUnmergedTree = true)
        input.performTouchInput { click() }
        input.performTextReplacement(oldTitle)
        waitForGenerationCard(oldTitle)
        capture("generation-search-keyboard")
        hideFocusedKeyboardAndWait()
        input.performTextReplacement(worldTitle)
        waitForGenerationCard(worldTitle)
        input.performTextReplacement(prefix)
        waitForGenerationCard(oldTitle)
        waitForGenerationCard(worldTitle)
        capture("generation-search-old-history")
        rule.onAllNodesWithText("角色人设").filter(hasClickAction()).onFirst().performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(worldTitle).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText(oldTitle).assertIsDisplayed()
        capture("generation-search-kind")
        input.performTextReplacement("没有这样的记录${fixture.sessionId}")
        waitForText("没有匹配的生成记录")
        capture("generation-search-empty")
        input.performTextReplacement(oldTitle)
        waitForGenerationCard(oldTitle)
        rule.onAllNodesWithText(oldTitle).filter(hasClickAction() and !hasSetTextAction()).onFirst().performClick()
        waitForText("生成详情")
        capture("generation-search-detail")
    }

    @Test
    fun novelContentsSearchAndOldChapterJumpCapture() {
        runBlocking(Dispatchers.IO) {
            val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
            database.sessionWorldDao().getBySession(fixture.sessionId)?.let {
                database.sessionWorldDao().upsert(it.copy(gameplayMode = "小说创作"))
            }
            database.withTransaction {
                for (chapter in 1..70) {
                    val title = if (chapter == 7) "钟楼来信" else "航程 $chapter"
                    database.messageDao().insert(MessageEntity(sessionId = fixture.sessionId, speakerType = "narrator",
                        content = "第 $chapter 章 $title\n第 $chapter 次抵达旧港，守灯人把未寄出的信交给了旅人。",
                        structuredContentJson = com.mojing.app.domain.story.NovelChapter.metadata("{}", chapter, title)))
                }
            }
        }
        clickBottom("对话")
        findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        rule.waitForIdle()
        if (rule.onNodeWithContentDescription("小说目录").isDisplayed()) {
            rule.onNodeWithContentDescription("小说目录").performClick()
        } else {
            rule.onNodeWithContentDescription("会话菜单").performClick()
            rule.onNodeWithText("小说目录").performClick()
        }
        waitForText("航程 70")
        val search = rule.onNodeWithContentDescription("搜索小说目录", useUnmergedTree = true)
        search.assertIsEnabled().performTouchInput { click() }
        rule.waitUntil(5_000) {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys input_method")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText().contains("mInputShown=true") }
        }
        search.performTextReplacement("钟楼来信")
        waitForText("找到 1 项")
        capture("contents-search-keyboard")
        pressBack()
        capture("contents-search-old-result")
        search.performTextReplacement("不存在的章节标题")
        waitForText("没有匹配的目录，试试其他关键词。")
        rule.onNodeWithText("生成下一章").assertIsDisplayed()
        rule.onNodeWithText("导出小说 TXT").assertIsEnabled()
        capture("contents-search-empty")
        rule.onNodeWithContentDescription("清除目录搜索").performClick()
        waitForText("航程 70")
        search.performTextReplacement("第七章")
        waitForText("钟楼来信")
        capture("contents-search-number")
        rule.onNodeWithText("钟楼来信").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("小说目录").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("第 7 次抵达旧港", substring = true).assertIsDisplayed()
        capture("contents-search-jumped-to-old-chapter")
    }

    @Test
    fun inputToolsGridAndMacroCapture() {
        clickBottom("对话")
        findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        rule.onNodeWithContentDescription("更多输入工具").performClick()
        listOf("语音输入", "表情", "添加图片", "试听朗读", "生成旁白", "生成配图", "快捷词")
            .forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("试听朗读").assertIsNotEnabled()
        capture("input-tools-grid")
        rule.onNodeWithText("生成旁白").performClick()
        rule.onNodeWithContentDescription("剧情方向（可选）", useUnmergedTree = true).assertIsDisplayed()
        capture("input-tools-narrator")
        pressBack()
        rule.onNodeWithContentDescription("更多输入工具").performClick()
        rule.onNodeWithText("快捷词").performClick()
        rule.onNodeWithText("你的名字").performScrollTo().assertIsDisplayed()
        capture("input-tools-macros")
        rule.onNodeWithText("你的名字").performClick()
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().assertTextContains("{{user}}")
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTextReplacement("")
        hideKeyboardAndWait()
    }

    @Test
    fun characterSectionsAtEndCapture() {
        clickBottom("创作")
        rule.onNodeWithText("角色").performClick()
        rule.onNodeWithText(fixture.characterName).performClick()
        rule.onNodeWithContentDescription("编辑角色").performClick()
        rule.onNodeWithText("导出与高级设置").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("便携包、扩展设定与采样参数").performScrollTo().assertIsDisplayed()
        capture("character-sections-end")
        rule.onNodeWithText("角色形象").performScrollTo().performClick()
        capture("character-appearance-expanded")
        rule.onNodeWithText("角色形象").performClick()
        rule.onNodeWithText("创作辅助").performScrollTo().performClick()
        rule.onNodeWithText("插入宏变量").performScrollTo().assertIsDisplayed()
        capture("character-creative-expanded")
    }

    @Test
    fun editingSheetsAndProviderLogosCapture() {
        clickBottom("创作")
        capture("feedback-creation-icons-rounded-projects")
        rule.onNodeWithText("小说创作").performClick()
        rule.onNodeWithText("开篇方向").performScrollTo().performClick()
        capture("feedback-direction-editor")
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTouchInput { click() }
        rule.waitUntil(5_000) {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("dumpsys input_method")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
                it.readText().contains("mInputShown=true")
            }
        }
        capture("feedback-direction-keyboard")
        // The IME belongs to the modal window; system Back dismisses that IME first.
        pressBack()
        rule.onNodeWithText("完成").performClick()
        rule.onNodeWithText("风格与节奏").performScrollTo().performClick()
        capture("feedback-style-editor")
        rule.onNodeWithText("完成").performClick()
        pressBack()
        clickBottom("设置")
        rule.onNodeWithText("平台与模型").performClick()
        rule.onNodeWithText("添加平台").performClick()
        rule.onNodeWithText("请输入平台名").assertIsDisplayed()
        capture("feedback-platform-placeholders")
        rule.onNodeWithContentDescription("选择服务商预设").performClick()
        capture("feedback-provider-logos")
        pressBack()
        rule.onNodeWithText("取消").performClick()
    }

    @Test
    fun supplementaryPagesAndChatToolsCapture() {
        clickBottom("对话")
        rule.onNodeWithText("生成记录").performClick()
        waitForText("生成记录")
        capture("extra-generation-records")
        pressBack()
        findFixtureStory()
        capture("extra-story-filtered")
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        rule.onNodeWithText(fixture.messageText).assertIsDisplayed()
        rule.onNodeWithContentDescription("会话菜单").performClick()
        capture("extra-chat-menu")
        rule.onNodeWithText("搜索消息").performClick()
        rule.onNodeWithText("搜索消息").assertIsDisplayed()
        capture("extra-message-search")
        pressBack()
        rule.onNodeWithContentDescription("更多输入工具").performClick()
        rule.onNodeWithText("输入工具").assertIsDisplayed()
        capture("extra-input-tools")
        pressBack()
        rule.onNodeWithContentDescription("切换平台和模型").performClick()
        capture("extra-chat-model-picker")
        pressBack()
        rule.onNodeWithText("语音").performClick()
        capture("extra-chat-voice-picker")
        pressBack()
        pressBack()
        openCreationWorld()
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(fixture.world.name)
        rule.waitUntil(5_000) { rule.onAllNodesWithText(fixture.world.name).fetchSemanticsNodes().size >= 2 }
        hideKeyboardAndWait()
        capture("extra-world-library-filtered")
        rule.onAllNodesWithContentDescription("更多操作").onFirst().performClick()
        capture("extra-world-menu")
        waitForText("世界设置")
        rule.onNodeWithText("世界设置").performClick()
        rule.onNodeWithText("世界名称").assertIsDisplayed()
        capture("extra-world-settings")
        pressBack()
        rule.onNodeWithContentDescription("更多").performClick()
        rule.onNodeWithText("世界工坊").performClick()
        rule.onNodeWithText("设定工坊").assertIsDisplayed()
        hideKeyboardAndWait()
        capture("extra-world-workbench")
        rule.onNodeWithContentDescription("新建模板").assertIsDisplayed().performClick()
        rule.onNodeWithText("模板名称").assertIsDisplayed()
        capture("extra-template-editor")
    }

    @Test
    fun worldLibraryAndDetailCapture() {
        openCreationWorld()
        capture("c1-world-library")
        rule.onNodeWithText(fixture.world.name).performClick()
        rule.onAllNodesWithText("条目").filter(hasClickAction()).onLast().assertIsDisplayed()
        capture("c2-world-detail")
        if (args.getString("captureWithImages") == "true") {
            val collapsed = rule.onAllNodes(hasContentDescription("展开世界概览")).fetchSemanticsNodes().isNotEmpty()
            if (collapsed) rule.onNodeWithContentDescription("展开世界概览").performClick()
            rule.onNodeWithContentDescription("世界封面").assertIsDisplayed()
            capture("c2-world-cover-expanded")
            if (collapsed) rule.onNodeWithContentDescription("收起世界概览").performClick()
        }
    }

    @Test
    fun characterImportStopRollsBackCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val suffix = java.util.UUID.randomUUID().toString().take(6)
        val prefix = "停止验收-$suffix"
        val filename = "stop-$suffix.json"
        val json = com.mojing.app.ui.character.CharacterExportCodec.toJson(
            List(10_000) { CharacterEntity(name = "$prefix-$it", personaPrompt = "取消导入用合成资料", boundEncyclopediaId = fixture.world.id) },
            listOf(com.mojing.app.data.local.dao.EncyclopediaNameOption(fixture.world.id, fixture.world.name)),
        )
        val resolver = targetContext.contentResolver
        val uri = checkNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }))
        try {
            checkNotNull(resolver.openOutputStream(uri)).bufferedWriter().use { it.write(json) }
            clickBottom("创作")
            rule.onNodeWithText("角色").performClick()
            waitForText("全部角色")
            rule.onNodeWithContentDescription("更多").performClick()
            rule.onNodeWithText("导入（便携包", substring = true).performClick()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            rule.waitUntil(10_000) {
                automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(filename)
                    ?.any { it.text?.toString() == filename } == true
            }
            var item: android.view.accessibility.AccessibilityNodeInfo? =
                automation.rootInActiveWindow.findAccessibilityNodeInfosByText(filename).first { it.text?.toString() == filename }
            while (item != null && !item.isClickable) item = item.parent
            check(item?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
            waitForText("正在导入角色…")
            rule.onNodeWithContentDescription("停止导入").performClick()
            waitForText("已停止导入")
            check(runBlocking(Dispatchers.IO) { database.characterDao().getNamesStartingWith(prefix) }.isEmpty())
            check(runBlocking(Dispatchers.IO) {
                database.encyclopediaEntryDao().getByType(fixture.world.id, "character").none { it.title.startsWith(prefix) }
            })
            capture("character-import-stopped")
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun characterDocumentImportCancelAndRepeatCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val suffix = java.util.UUID.randomUUID().toString().take(6)
        val firstName = "导入灯塔-$suffix"
        val secondName = "导入航海-$suffix"
        val filename = "mx-$suffix.json"
        val json = com.mojing.app.ui.character.CharacterExportCodec.toJson(
            listOf(
                CharacterEntity(name = firstName, personaPrompt = "记录潮汐", boundEncyclopediaId = fixture.world.id),
                CharacterEntity(name = secondName, personaPrompt = "守护灯塔", boundEncyclopediaId = fixture.world.id),
            ),
            listOf(com.mojing.app.data.local.dao.EncyclopediaNameOption(fixture.world.id, fixture.world.name)),
        )
        val resolver = targetContext.contentResolver
        val uri = checkNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }))
        try {
            checkNotNull(resolver.openOutputStream(uri)).bufferedWriter().use { it.write(json) }
            clickBottom("创作")
            rule.onNodeWithText("角色").performClick()
            waitForText("全部角色")
            fun openPicker() {
                rule.onNodeWithContentDescription("更多").performClick()
                rule.onNodeWithText("导入（便携包", substring = true).performClick()
                rule.waitUntil(5_000) {
                    InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                        ?.packageName?.toString()?.contains("documentsui") == true
                }
            }
            openPicker()
            pressBack()
            waitForText("全部角色")
            capture("character-import-picker-cancel")
            repeat(2) {
                openPicker()
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                rule.waitUntil(10_000) {
                    automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(filename)?.any { it.text?.toString() == filename } == true
                }
                val node = automation.rootInActiveWindow.findAccessibilityNodeInfosByText(filename).first { it.text?.toString() == filename }
                var clickable: android.view.accessibility.AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                check(clickable?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
                try {
                    waitForText("成功导入 2 个角色")
                } catch (failure: Throwable) {
                    val tree = StringBuilder()
                    fun record(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0) {
                        if (node == null) return
                        tree.appendLine(" ".repeat(depth) + "${node.className} text=${node.text} desc=${node.contentDescription} clickable=${node.isClickable}")
                        for (i in 0 until node.childCount) record(node.getChild(i), depth + 1)
                    }
                    record(automation.rootInActiveWindow)
                    File(targetContext.getExternalFilesDir(null), "${args.getString("captureRun")}/import-window.txt").writeText(tree.toString())
                    val screenshot = File(targetContext.getExternalFilesDir(null), "${args.getString("captureRun")}/character-import-unfinished.png")
                    screenshot.outputStream().use { automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
                    throw failure
                }
                capture("character-import-result-${it + 1}")
            }
            val imported = runBlocking(Dispatchers.IO) {
                database.characterDao().getNamesStartingWith(firstName) + database.characterDao().getNamesStartingWith(secondName)
            }
            check(imported.toSet() == setOf(firstName, "${firstName}_2", secondName, "${secondName}_2"))
            val mirrors = runBlocking(Dispatchers.IO) { database.encyclopediaEntryDao().getByType(fixture.world.id, "character") }
            check(mirrors.count { it.title.startsWith(firstName) || it.title.startsWith(secondName) } == 4)
        } finally {
            // This URI was created by this test; keep imported synthetic fixtures for evidence.
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun entryCoverHintDraftRetainRecreateSaveAndDiscardCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val store = com.mojing.app.data.EntryEditDraftStore(targetContext)
        val originalContent = runBlocking(Dispatchers.IO) { database.encyclopediaEntryDao().getById(fixture.character.id)?.content }
        val hint = "远处的灯塔、薄雾与清晨的海面"
        fun hintField() = rule.onNodeWithTag("entry-cover-prompt-hint", useUnmergedTree = true)
        fun reopenEntry() {
            rule.onNodeWithText(fixture.character.title).performClick()
            waitForText("编辑条目")
            waitForText("生图补充说明（可选）")
        }
        openCreationWorld()
        rule.onNodeWithText(fixture.world.name).performClick()
        reopenEntry()
        hintField().performScrollTo().performTextReplacement(hint)
        hideFocusedKeyboardAndWait()
        rule.onAllNodesWithText("保存修改").onLast().assertIsEnabled()
        rule.onNodeWithContentDescription("返回").performClick()
        waitForText("保留草稿并离开")
        val retainBounds = rule.onNodeWithText("保留草稿并离开").fetchSemanticsNode().boundsInRoot
        val discardBounds = rule.onNodeWithText("放弃修改").fetchSemanticsNode().boundsInRoot
        val continueBounds = rule.onNodeWithText("继续编辑").fetchSemanticsNode().boundsInRoot
        check(retainBounds.bottom <= discardBounds.top && discardBounds.bottom <= continueBounds.top)
        capture("entry-hint-draft-leave")
        rule.onNodeWithText("保留草稿并离开").performClick()
        waitForText(fixture.character.title)
        check(runBlocking { store.load(fixture.world.id, fixture.character.id)?.coverPromptHint } == hint)
        reopenEntry()
        waitForText("恢复草稿")
        capture("entry-hint-draft-recover")
        rule.onNodeWithText("恢复草稿").performClick()
        hintField().performScrollTo().assertTextContains(hint)
        rule.activityRule.scenario.recreate()
        pressBack() // Dismiss the Activity splash overlay after recreation.
        waitForText("编辑条目")
        hintField().performScrollTo().assertTextContains(hint)
        capture("entry-hint-draft-recreated")
        rule.onAllNodesWithText("保存修改").onLast().performClick()
        rule.waitUntil(5_000) { runBlocking { store.load(fixture.world.id, fixture.character.id) == null } }
        waitForText("已保存")
        rule.onNodeWithContentDescription("返回").performClick()
        waitForText(fixture.character.title)
        reopenEntry()
        check(rule.onAllNodesWithText("恢复草稿").fetchSemanticsNodes().isEmpty())
        hintField().performScrollTo().performTextReplacement("这项临时提示应被放弃")
        hideFocusedKeyboardAndWait()
        rule.onNodeWithContentDescription("返回").performClick()
        waitForText("放弃修改")
        rule.onNodeWithText("放弃修改").performClick()
        waitForText("关系图")
        check(runBlocking { store.load(fixture.world.id, fixture.character.id) } == null)
        reopenEntry()
        check(rule.onAllNodesWithText("恢复草稿").fetchSemanticsNodes().isEmpty())
        check(hintField().performScrollTo().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.isEmpty())
        check(runBlocking(Dispatchers.IO) { database.encyclopediaEntryDao().getById(fixture.character.id)?.content } == originalContent)
        capture("entry-hint-draft-discarded")
    }

    @Test
    fun entryEditorAndSourceCapture() {
        openCreationWorld()
        rule.onNodeWithText(fixture.world.name).performClick()
        rule.onNodeWithText(fixture.character.title).performClick()
        rule.onNodeWithText("编辑条目").assertIsDisplayed()
        capture("c3-entry-editor")
        rule.onNodeWithText("查看原文").performScrollTo().performClick()
        rule.onNodeWithText("对话原文").assertIsDisplayed()
        capture("c6-source-preview")
        pressBack()
        pressBack()
        rule.onNodeWithText("沉积").performClick()
        rule.onNodeWithText("沉积").assertIsDisplayed()
        capture("c6-sedimentation")
    }

    @Test
    fun timelineAndRelationCapture() {
        openCreationWorld()
        rule.onNodeWithText(fixture.world.name).performClick()
        rule.onAllNodesWithText("时间线").filter(hasClickAction()).onLast().performClick()
        rule.onNodeWithText(fixture.timeline.title).assertIsDisplayed()
        capture("c4-timeline")
        rule.onAllNodesWithText("关系图").filter(hasClickAction()).onLast().performClick()
        rule.onNodeWithText("当前页关系图").assertIsDisplayed()
        capture("c5-relation-graph")
    }

    @Test
    fun backAndKeyboardStatesCapture() {
        openCreationWorld()
        rule.onNodeWithText(fixture.world.name).performClick()
        rule.onNodeWithText(fixture.character.title).performClick()
        rule.onNodeWithText("编辑条目").assertIsDisplayed()
        rule.onNodeWithText("标题").performClick()
        capture("c3-entry-keyboard")
        pressBack()
        pressBack()
        capture("c2-world-after-back")
    }

    @Test
    fun storyLibraryNewSessionChatActionsAndInfoTabsCapture() {
        clickBottom("对话")
        rule.onNodeWithText("故事库").assertIsDisplayed()
        capture("a1-story-library")
        rule.onNodeWithText("新建对话").performClick()
        rule.onNodeWithText("标题").assertIsDisplayed()
        capture("a2-new-session-sheet")
        pressBack()
        findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        rule.onNodeWithText(fixture.messageText).assertIsDisplayed()
        capture("a3-chat-normal")
        rule.onNodeWithContentDescription("阅读模式").performClick()
        rule.onNodeWithContentDescription("退出阅读模式").assertIsDisplayed()
        if (args.getString("captureChapterHeading") == "true") {
            rule.onNodeWithText("第一章 风雪中的重逢").performScrollTo().assertIsDisplayed()
        }
        capture("a4-chat-reader")
        rule.onNodeWithContentDescription("退出阅读模式").performClick()
        capture("a3-chat-after-reader")
        rule.onNodeWithContentDescription("对话正文").performScrollToNode(hasText(fixture.messageText))
        rule.onNodeWithText(fixture.messageText).performScrollTo().assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { rule.onNodeWithText("消息操作").isDisplayed() }
        rule.onNodeWithText("消息操作").assertIsDisplayed()
        capture("a5-message-actions")
        pressBack()
        rule.onNodeWithContentDescription("会话设置与资料").performClick()
        rule.onNodeWithText("角色").assertIsDisplayed()
        rule.onNodeWithText("世界").assertIsDisplayed()
        listOf("角色", "世界", "记忆", "事件", "书签").forEachIndexed { index, tab ->
            waitForText(tab)
            rule.onAllNodesWithText(tab).filter(hasClickAction()).onLast().performClick()
            capture("a6-chat-info-tab-${index + 1}")
        }
    }

    @Test
    fun creationHubNovelAndCharacterLibraryEditorCapture() {
        clickBottom("创作")
        rule.onAllNodesWithText("创作").onFirst().assertIsDisplayed()
        capture("b1-creation-hub")
        rule.onNodeWithText("小说创作").performClick()
        rule.onNodeWithText("故事背景 *").assertIsDisplayed()
        org.junit.Assert.assertTrue(rule.onAllNodesWithText("创作").filter(isSelectable()).fetchSemanticsNodes().isEmpty())
        capture("b2-novel-creation")
        pressBack()
        rule.onNodeWithText("角色").performClick()
        rule.onNodeWithText("角色").assertIsDisplayed()
        capture("b3-character-library")
        rule.onNodeWithText(fixture.characterName).performClick()
        rule.onNodeWithText("角色详情").assertIsDisplayed()
        capture("b4-character-detail")
        rule.onNodeWithContentDescription("编辑角色").performClick()
        rule.onNodeWithText("姓名").assertIsDisplayed()
        capture("b5-character-editor")
        rule.onNodeWithText("全屏编辑").performClick()
        rule.onNodeWithContentDescription("返回编辑表单").assertIsDisplayed()
        val editedPersona = "全屏编辑验收：守灯人记录潮汐，也记住每一位远行者的约定。\n".repeat(16)
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast()
            .performTextReplacement(editedPersona)
        capture("b5-character-fullscreen-editor")
        rule.onNodeWithText("完成").performClick()
        rule.onNodeWithText("编辑角色").assertIsDisplayed()
        rule.onNodeWithText("保存").performClick()
        rule.waitUntil(5_000) {
            runBlocking(Dispatchers.IO) {
                val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
                val characterId = database.participantDao().getBySession(fixture.sessionId).single().characterId
                database.characterDao().getById(characterId)?.personaPrompt == editedPersona
            }
        }
        capture("b5-character-fullscreen-saved")
    }

    @Test
    fun relatedStoriesPaginationAndThinkMaxSaveCapture() {
        val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
        val storage = com.mojing.app.data.SecureStorage().also { it.init(targetContext) }
        val previousAllow = storage.allowSessionThinkMax
        val characterId = runBlocking(Dispatchers.IO) { database.participantDao().getBySession(fixture.sessionId).single().characterId }
        val storyIds = mutableListOf<Long>()
        val titles = (0..7).map { "关联故事$it · ${fixture.characterName}" }
        try {
            runBlocking(Dispatchers.IO) {
                repeat(8) { index ->
                    val id = database.sessionDao().insert(SessionEntity(title = titles[index], updatedAt = System.currentTimeMillis() + index))
                    storyIds += id
                    database.participantDao().upsert(SessionParticipantEntity(sessionId = id, characterId = characterId))
                }
            }
            clickBottom("创作")
            rule.onNodeWithText("角色").performClick()
            rule.onNodeWithText(fixture.characterName).performClick()
            waitForText("相关故事")
            rule.onNodeWithText("下一页").performScrollTo().performClick()
            waitForText("第 2 页")
            rule.onNodeWithText(titles[2]).performScrollTo().assertIsDisplayed()
            capture("b6-related-stories-second-page")
            rule.onNodeWithText("上一页").performScrollTo().performClick()
            waitForText("第 1 页")
            rule.onNodeWithText(titles[7]).performScrollTo().assertIsDisplayed()
            capture("b6-related-stories-first-page")
            pressBack()
            pressBack()
            storage.allowSessionThinkMax = true
            clickBottom("对话")
            findFixtureStory()
            rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
            rule.onNodeWithContentDescription("会话设置与资料").performClick()
            rule.onAllNodesWithText("世界").filter(hasClickAction()).onLast().performClick()
            rule.onNodeWithText("本会话思考/Max").performScrollTo()
            rule.onNodeWithContentDescription("本会话思考/Max开关").performClick()
            rule.waitUntil(5_000) { runBlocking(Dispatchers.IO) { database.sessionDao().getById(fixture.sessionId)?.thinkMaxEnabled == true } }
            capture("a6-think-max-saved")
        } finally {
            storage.allowSessionThinkMax = previousAllow
            runBlocking(Dispatchers.IO) { storyIds.forEach { database.sessionDao().delete(it) } }
        }
    }

    @Test
    fun settingsFiveSectionsCapture() {
        runBlocking(Dispatchers.IO) {
            val database = EntryPointAccessors.fromApplication(targetContext.applicationContext, PrototypeDatabaseEntryPoint::class.java).database()
            database.costRecordDao().insert(CostRecordEntity(
                modelName = "原型验收模型", platformId = "prototype-local",
                platformName = "原型验收本地样本", currency = "CNY", estimatedCost = 0.32,
                promptTokens = 1200, completionTokens = 800, totalTokens = 2000,
            ))
        }
        clickBottom("设置")
        rule.onAllNodesWithText("设置").onFirst().assertIsDisplayed()
        org.junit.Assert.assertEquals(1, rule.onAllNodesWithText("设置").filter(isSelectable()).fetchSemanticsNodes().size)
        capture("d1-settings")
        listOf("平台与模型", "创作偏好", "个性化", "用量与费用", "应用更新").forEachIndexed { index, title ->
            rule.onNodeWithText(title).performClick()
            rule.onNodeWithText(title).assertIsDisplayed()
            org.junit.Assert.assertTrue(rule.onAllNodesWithText("设置").filter(isSelectable()).fetchSemanticsNodes().isEmpty())
            capture("d${index + 2}-${index + 1}-settings-${title}")
            if (title == "平台与模型") {
                rule.onNodeWithText("添加平台").performClick()
                rule.onNodeWithText("平台名称").assertIsDisplayed()
                capture("d2-platform-editor")
                rule.onNodeWithText("取消").performClick()
                rule.onAllNodesWithText("编辑").onLast().performScrollTo().performClick()
                rule.onNodeWithText("模型价格").assertIsDisplayed()
                capture("d2-model-price-editor")
                rule.onNodeWithText("取消").performClick()
            }
            if (title == "个性化") {
                rule.onNodeWithText("外观").performClick()
                rule.onNodeWithText("主题配色").assertIsDisplayed()
                capture("d4-appearance-tab")
            }
            hideKeyboardAndWait()
            rule.onNodeWithContentDescription("返回设置").performClick()
            waitForText("平台与模型")
        }
    }

    @Test
    fun novelGeneratingAndStopCapture() {
        val storage = rule.activity.secureStorage
        val oldKey = storage.publicApiKey
        val oldBase = storage.publicBaseUrl
        val oldModel = storage.publicModel
        val prefs = targetContext.getSharedPreferences("story_opening_input_draft_v1", android.content.Context.MODE_PRIVATE)
        val draftSnapshot = prefs.all.mapValues { it.value as String }
        assumeTrue("Existing pending generation is protected", !draftSnapshot.containsKey("generation"))
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val release = java.util.concurrent.CountDownLatch(1)
        val response = "第一章 灯塔初亮\n\n雾港在潮声里醒来，灯塔为远行者留着最后一束光。"
        val worker = Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 15_000
                    val input = socket.getInputStream().buffered()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val byte = input.read()
                        check(byte >= 0)
                        header.append(byte.toChar())
                    }
                    val contentLength = header.lines().firstOrNull { it.startsWith("Content-Length:", true) }
                        ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    repeat(contentLength) { check(input.read() >= 0) }
                    val json = com.google.gson.JsonObject().apply {
                        add("choices", com.google.gson.JsonArray().apply {
                            add(com.google.gson.JsonObject().apply {
                                add("delta", com.google.gson.JsonObject().apply {
                                    addProperty("content", "{\"title\":\"灯塔初亮\",\"chapters\":[{\"title\":\"第一章\",\"content\":" + com.google.gson.Gson().toJson(response))
                                })
                            })
                        })
                    }
                    val writer = socket.getOutputStream().bufferedWriter()
                    writer.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\ndata: $json\n\n")
                    writer.flush()
                    release.await(20, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            storage.publicApiKey = "prototype-local-only"
            storage.publicBaseUrl = "http://127.0.0.1:${server.localPort}/v1"
            storage.publicModel = "prototype-local-model"
            clickBottom("创作")
            rule.onNodeWithText("小说创作").performClick()
            rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst()
                .performTextReplacement("原型验收：守灯人在雾港等待一封来自未来的信。")
            hideKeyboardAndWait()
            rule.onNodeWithText("开始创作").performClick()
            try {
                rule.waitUntil(10_000) { rule.onAllNodesWithText(response).fetchSemanticsNodes().isNotEmpty() }
            } catch (error: Throwable) {
                capture("b3-generation-failure")
                throw error
            }
            rule.onNodeWithText(response).performScrollTo()
            capture("b3-novel-generating")
            rule.onNodeWithText("停止生成").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("已停止").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText(response).assertIsDisplayed()
            capture("b3-novel-stopped-preview")
            pressBack()
        } finally {
            release.countDown()
            server.close()
            worker.join(1_000)
            storage.publicApiKey = oldKey
            storage.publicBaseUrl = oldBase
            storage.publicModel = oldModel
            prefs.edit().clear().apply { draftSnapshot.forEach { (key, value) -> putString(key, value) } }.commit()
        }
    }

    @Test
    fun novelCompletedChaptersAndStopCapture() {
        val storage = rule.activity.secureStorage
        val oldKey = storage.publicApiKey
        val oldBase = storage.publicBaseUrl
        val oldModel = storage.publicModel
        val prefs = targetContext.getSharedPreferences("story_opening_input_draft_v1", android.content.Context.MODE_PRIVATE)
        val draftSnapshot = prefs.all.mapValues { it.value as String }
        assumeTrue("Existing pending generation is protected", !draftSnapshot.containsKey("generation"))
        val server = java.net.ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"))
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            runCatching {
                repeat(2) { batch ->
                    server.accept().use { socket ->
                        socket.soTimeout = 15_000
                        val input = socket.getInputStream().buffered()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val byte = input.read()
                            check(byte >= 0)
                            header.append(byte.toChar())
                        }
                        val length = header.lines().firstOrNull { it.startsWith("Content-Length:", true) }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                        repeat(length) { check(input.read() >= 0) }
                        val content = if (batch == 0) com.google.gson.Gson().toJson(mapOf(
                            "title" to "真实章节进度",
                            "chapters" to (1..3).map { mapOf("title" to "灯塔篇$it", "content" to "守灯人记录第${it}次潮汐。") },
                            "next_choices" to listOf("前往海港", "等待回信"),
                        )) else "{\"title\":\"真实章节进度\",\"chapters\":[{\"title\":\"灯塔篇4\",\"content\":\"第四次潮汐正在抵达"
                        val delta = com.google.gson.Gson().toJson(mapOf("choices" to listOf(mapOf("delta" to mapOf("content" to content)))))
                        val writer = socket.getOutputStream().bufferedWriter()
                        writer.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\ndata: $delta\n\n")
                        if (batch == 0) writer.write("data: [DONE]\n\n")
                        writer.flush()
                        if (batch == 1) release.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            storage.publicApiKey = "prototype-local-only"
            storage.publicBaseUrl = "http://127.0.0.1:${server.localPort}/v1"
            storage.publicModel = "prototype-local-model"
            clickBottom("创作")
            rule.onNodeWithText("小说创作").performClick()
            rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement("守灯人在海港等待回信。")
            hideKeyboardAndWait()
            rule.onNodeWithText("5 章").performScrollTo().performClick()
            rule.onNodeWithText("开始创作").performClick()
            waitForText("已完成章节")
            org.junit.Assert.assertTrue(rule.onAllNodesWithText("故事背景 *").fetchSemanticsNodes().isEmpty())
            rule.onNodeWithText("1. 灯塔篇1").performScrollTo().assertIsDisplayed()
            capture("b3-real-completed-chapters-generating")
            rule.onNodeWithText("停止生成").performClick()
            waitForText("已停止")
            rule.onNodeWithText("已完成 3/5 章").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("3. 灯塔篇3").performScrollTo().assertIsDisplayed()
            capture("b3-real-completed-chapters-stopped")
            rule.onNodeWithText("保存已收到草稿").performScrollTo().assertIsDisplayed()
            capture("b3-stopped-save-actions")
            pressBack()
            waitForText("小说创作")
            rule.onNodeWithText("小说创作").performClick()
            waitForText("上次生成中断")
            rule.onNodeWithText("3. 灯塔篇3").performScrollTo().assertIsDisplayed()
            org.junit.Assert.assertTrue(rule.onAllNodesWithText("故事背景 *").fetchSemanticsNodes().isEmpty())
            capture("b3-real-completed-chapters-reopened")
        } finally {
            release.countDown()
            server.close()
            worker.join(1_000)
            storage.publicApiKey = oldKey
            storage.publicBaseUrl = oldBase
            storage.publicModel = oldModel
            prefs.edit().clear().apply { draftSnapshot.forEach { (key, value) -> putString(key, value) } }.commit()
        }
    }

    @Test
    fun wideChatCapture() {
        assumeTrue("Wide capture only", targetContext.resources.configuration.screenWidthDp > 840)
        clickBottom("对话")
        findFixtureStory()
        rule.onAllNodesWithText(fixture.sessionTitle).onLast().performClick()
        rule.onNodeWithText("当前窗口").assertIsDisplayed()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(fixture.messageText).onLast().isDisplayed() }
        capture("a3-wide-chat")
        rule.onNodeWithContentDescription("阅读模式").performClick()
        capture("a4-wide-reader")
    }

    private fun openCreationWorld() {
        clickBottom("创作")
        rule.onNodeWithText("世界").performClick()
        rule.onNodeWithText("搜索世界名称").assertIsDisplayed()
        rule.onNodeWithText(fixture.world.name).assertIsDisplayed()
    }

    private fun findFixtureStory() {
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextReplacement(fixture.sessionTitle)
        hideKeyboardAndWait()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText(fixture.sessionTitle).fetchSemanticsNodes().size >= 2 &&
                rule.onAllNodesWithText(fixture.sessionTitle).onLast().isDisplayed()
        }
    }

    private fun waitForGenerationCard(title: String) {
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText(title).filter(hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun hideFocusedKeyboardAndWait() {
        // Modal sheets have their own Window; Activity decor insets can remain stale.
        fun keyboardShown(): Boolean {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("dumpsys input_method")
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                .bufferedReader().use { it.readText() }.contains("mInputShown=true")
        }
        if (keyboardShown()) pressBack()
        rule.waitUntil(5_000) { !keyboardShown() }
    }

    private fun hideKeyboardAndWait() {
        rule.runOnUiThread {
            androidx.core.view.WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            rule.activity.currentFocus?.clearFocus()
        }
        rule.waitUntil(5_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
        }
    }

    private fun clickBottom(label: String) {
        waitForText(label)
        rule.onAllNodesWithText(label).filter(hasClickAction()).onLast().performClick()
        rule.waitForIdle()
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        rule.waitUntil(5_000) {
            runCatching { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000)
        // Semantics can advance before the emulator submits its rendered frame.
        // Fence the actual window draw rather than treating a found node as a screenshot.
        repeat(2) {
            val committed = java.util.concurrent.CountDownLatch(1)
            rule.runOnUiThread {
                val decor = rule.activity.window.decorView
                if (Build.VERSION.SDK_INT >= 29 && decor.isHardwareAccelerated) {
                    decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                    decor.invalidate()
                } else decor.postOnAnimation { committed.countDown() }
            }
            check(committed.await(3, java.util.concurrent.TimeUnit.SECONDS)) { "Window frame was not committed" }
        }
        val directory = File(targetContext.getExternalFilesDir(null), args.getString("captureRun") ?: "prototype-app-20261002").apply { mkdirs() }
        rule.runOnUiThread {
            val window = rule.activity.window
            val content = rule.activity.findViewById<android.view.View>(android.R.id.content)
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            val focusedBarsAppearance = if (Build.VERSION.SDK_INT >= 30) {
                android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }
                    .joinToString(",") { it.windowInsetsController?.systemBarsAppearance?.toString(16) ?: "unavailable" }
            } else "legacy"
            val position = IntArray(2).also(content::getLocationOnScreen)
            File(directory, "$name-window.txt").writeText(
                "status=${window.statusBarColor.toUInt().toString(16)} navigation=${window.navigationBarColor.toUInt().toString(16)} " +
                    "flags=${window.attributes.flags.toUInt().toString(16)} " +
                    "appearanceLightStatusBars=${insetsController.isAppearanceLightStatusBars} " +
                    "appearanceLightNavigationBars=${insetsController.isAppearanceLightNavigationBars} " +
                    "focusedBarsAppearance=$focusedBarsAppearance " +
                    "content=${content.width}x${content.height}@${position.joinToString()} " +
                    "decor=${window.decorView.width}x${window.decorView.height}",
            )
        }
        val file = File(directory, "$name.png")
        file.outputStream().use { output ->
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun seedFixture(): Fixture = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val database = EntryPointAccessors.fromApplication(app, PrototypeDatabaseEntryPoint::class.java).database()
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val suffix = java.util.UUID.randomUUID().toString().take(6)
                val cover = if (args.getString("captureWithImages") != "true") "" else {
                    val directory = File(app.filesDir, "prototype-fixtures").apply { mkdirs() }
                    val file = File(directory, "local-image-$suffix.png")
                    val bitmap = android.graphics.Bitmap.createBitmap(440, 200, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.rgb(205, 218, 225))
                    val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(64, 99, 116) }
                    canvas.drawRect(0f, 125f, 440f, 200f, paint)
                    paint.color = android.graphics.Color.rgb(239, 242, 237)
                    canvas.drawRect(290f, 40f, 320f, 145f, paint)
                    paint.color = android.graphics.Color.rgb(239, 202, 110)
                    canvas.drawCircle(305f, 45f, 12f, paint)
                    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    file.absolutePath
                }
                val sessionTitle = "雪港回声 · $suffix"
                val sessionId = database.sessionDao().insert(SessionEntity(title = sessionTitle))
                val messageText = "这一次，我想留下来。\n有些话，我必须亲口说完。"
                val messageId = database.messageDao().insert(MessageEntity(
                    sessionId = sessionId,
                    speakerType = "narrator",
                    content = (if (args.getString("captureChapterHeading") == "true") "第一章 风雪中的重逢\n\n" else "") +
                        "风从海面吹来，卷起细碎的雪，拍在港口的木栈上。远处的灯塔在雾中若隐若现，像一枚迟到的回信。",
                ))
                val worldId = database.encyclopediaDao().upsert(EncyclopediaEntity(
                    name = "雪港 · $suffix",
                    description = "位于北方的海港城市，常年被雾与雪笼罩。这里曾是重要的贸易枢纽。",
                    coverImagePath = cover,
                    genreTags = "原型验收,世界",
                    entryCount = 2,
                ))
                val characterTitle = "艾琳 · $suffix"
                val locationTitle = "旧灯塔 · $suffix"
                val timelineTitle = "灯塔初亮 · $suffix"
                val characterEntryId = database.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(
                    encyclopediaId = worldId,
                    title = characterTitle,
                    entryType = "character",
                    summary = "冷静而温柔，外柔内坚。习惯用克制的语气表达最深的牵挂。",
                    content = "艾琳住在雾港灯塔，负责记录来往船只与潮汐。",
                    tags = "人物,雾港",
                    sourceSessionId = sessionId,
                    sourceMessageId = messageId,
                    confidence = "inferred",
                    coverImagePath = cover,
                ))
                val locationId = database.encyclopediaEntryDao().upsert(EncyclopediaEntryEntity(
                    encyclopediaId = worldId,
                    title = locationTitle,
                    entryType = "location",
                    summary = "潮声与灯塔交汇的港口。",
                    content = "雾港位于群山与海潮之间，是商旅和故事的交汇处。",
                    tags = "地点,港口",
                    coverImagePath = cover,
                ))
                database.timelineEventDao().upsert(TimelineEventEntity(
                    encyclopediaId = worldId,
                    entryId = characterEntryId,
                    title = timelineTitle,
                    description = "艾琳接过灯火，开始记录雾港的潮汐。",
                    eventTime = "前 1023 年",
                    sortOrder = 1,
                ))
                database.entryRelationDao().upsert(EntryRelationEntity(
                    encyclopediaId = worldId,
                    fromEntryId = characterEntryId,
                    toEntryId = locationId,
                    relationType = "守护",
                    label = "守灯人守护雾港",
                ))
                val characterId = database.characterDao().upsert(CharacterEntity(
                    name = characterTitle,
                    personaPrompt = "冷静而温柔，外柔内坚。习惯用克制的语气表达最深的牵挂。",
                    avatarImagePath = cover,
                    cardImagePath = cover,
                    boundEncyclopediaId = worldId,
                ))
                database.sessionWorldDao().upsert(SessionWorldEntity(
                    sessionId = sessionId,
                    encyclopediaId = worldId,
                    worldPrompt = "雾港的潮汐、灯塔与远行者构成当前故事背景。",
                    gameplayMode = "自由剧情",
                    narratorEnabled = true,
                    narratorName = "叙述者",
                ))
                database.participantDao().upsert(SessionParticipantEntity(
                    sessionId = sessionId,
                    characterId = characterId,
                    sortOrder = 0,
                ))
                database.messageDao().insert(MessageEntity(
                    sessionId = sessionId,
                    speakerType = "character",
                    characterId = characterId,
                    content = "你还会回来啊。\n我以为，这次也像上次一样，只是路过。",
                ))
                database.messageDao().insert(MessageEntity(
                    sessionId = sessionId,
                    speakerType = "user",
                    content = messageText,
                ))
                database.messageDao().insert(MessageEntity(
                    sessionId = sessionId,
                    speakerType = "character", characterId = characterId,
                    content = "……\n她沉默片刻，望向海面。\n也许，这一次，一切都会不一样。" +
                        if (args.getString("captureChoices") == "true")
                            "<CHOICES>" + (1..4).joinToString("") { "<OPTION>沿着港口寻找第${it}处线索，询问守灯人上一次风暴发生时的情况，再决定接下来如何行动。</OPTION>" } + "</CHOICES>"
                        else "",
                ))
                Fixture(
                    sessionId = sessionId,
                    world = EncyclopediaEntity(id = worldId, name = "雪港 · $suffix", description = "位于北方的海港城市，常年被雾与雪笼罩。这里曾是重要的贸易枢纽。", coverImagePath = cover, genreTags = "原型验收,世界", entryCount = 2),
                    character = EncyclopediaEntryEntity(id = characterEntryId, encyclopediaId = worldId, title = characterTitle, entryType = "character"),
                    timeline = TimelineEventEntity(title = timelineTitle, encyclopediaId = worldId),
                    sessionTitle = sessionTitle,
                    messageText = messageText,
                    characterName = characterTitle,
                )
            }
        }
    }

    data class Fixture(
        val sessionId: Long,
        val world: EncyclopediaEntity,
        val character: EncyclopediaEntryEntity,
        val timeline: TimelineEventEntity,
        val sessionTitle: String,
        val messageText: String,
        val characterName: String,
    )
}

package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.dao.MessageDao
import com.mojing.app.data.local.dao.SessionDao
import com.mojing.app.data.local.dao.CharacterDao
import com.mojing.app.data.local.dao.EncyclopediaDao
import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.dao.SessionMemorySegmentDao
import com.mojing.app.data.local.dao.SessionEventNodeDao
import com.mojing.app.data.local.dao.SessionBranchDao
import com.mojing.app.data.local.dao.ParticipantDao
import com.mojing.app.data.local.dao.WorldLoreEntryDao
import com.mojing.app.data.local.dao.WorldTemplateDao
import com.mojing.app.data.local.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var messageDao: MessageDao
    private lateinit var sessionDao: SessionDao
    private lateinit var sessionBranchDao: SessionBranchDao
    private lateinit var memorySegmentDao: SessionMemorySegmentDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        messageDao = db.messageDao()
        sessionDao = db.sessionDao()
        sessionBranchDao = db.sessionBranchDao()
        memorySegmentDao = db.sessionMemorySegmentDao()
    }

    @After
    fun teardown() { db.close() }

    @Test fun characterSnapshotCursorUsesVisibleUserIdsAndBranchState() = runBlocking {
        val sid = sessionDao.insert(SessionEntity(title = "角色状态分支"))
        val main = (1..20).map { messageDao.insert(MessageEntity(sessionId = sid, speakerType = "user", content = "主线 $it")) }
        sessionBranchDao.insert(SessionBranchEntity(sessionId = sid, branchId = "branch_a", sourceMessageId = main[9]))
        val branch = (1..8).map { messageDao.insert(MessageEntity(sessionId = sid, branchId = "branch_a", speakerType = "user", content = "分支 $it")) }

        assertEquals(main.drop(5).reversed(), messageDao.getRecentMainUserContextIdsAfter(sid, main[4], 15))
        assertEquals(branch.reversed() + main.subList(3, 10).reversed(),
            messageDao.getRecentVisibleUserContextIdsAfter(sid, "branch_a", 0L, 15))
        assertTrue(messageDao.setContextExcluded(sid, "branch_a", main[9], true))
        assertEquals(branch.reversed() + main.subList(8, 9),
            messageDao.getRecentVisibleUserContextIdsAfter(sid, "branch_a", main[7], 15))
        assertEquals(main.takeLast(15).reversed(), messageDao.getRecentMainUserContextIdsAfter(sid, 0L, 15))

        val characterId = db.characterDao().upsert(CharacterEntity(name = "林岚"))
        val states = db.characterStateDao()
        states.upsert(SessionCharacterStateEntity(sessionId = sid, characterId = characterId,
            branchId = "main", dynamicStateJson = "主线状态", lastSnapshotAttemptUserMessageId = main.last()))
        states.upsert(SessionCharacterStateEntity(sessionId = sid, characterId = characterId,
            branchId = "branch_a", dynamicStateJson = "分支状态", lastSnapshotAttemptUserMessageId = branch.last()))
        assertEquals("主线状态", states.getBySessionAndCharacter(sid, characterId, "main")?.dynamicStateJson)
        assertEquals("分支状态", states.getBySessionAndCharacter(sid, characterId, "branch_a")?.dynamicStateJson)
        messageDao.updateContent(main[8], "主线旧剧情已改")
        for (branchId in listOf("main", "branch_a")) {
            val state = states.getBySessionAndCharacter(sid, characterId, branchId)!!
            assertFalse(state.snapshotIsValid)
            assertEquals(0L, state.lastSnapshotAttemptUserMessageId)
            assertTrue(state.dynamicStateJson.endsWith("状态"))
        }
    }

    @Test fun recallRewindsSummaryTailsAcrossVisibleBranchesAndRollsBackOnFailure() = runBlocking {
        val sid = sessionDao.insert(SessionEntity(title = "摘要尾部"))
        val ids = (1..6).map { messageDao.insert(MessageEntity(sessionId = sid, content = "原文$it")) }
        sessionBranchDao.insert(SessionBranchEntity(sessionId = sid, branchId = "A", sourceMessageId = ids[3]))
        sessionBranchDao.insert(SessionBranchEntity(sessionId = sid, branchId = "B", sourceMessageId = ids[0]))
        val a = messageDao.insert(MessageEntity(sessionId = sid, branchId = "A", content = "继承剧情"))
        val b = messageDao.insert(MessageEntity(sessionId = sid, branchId = "B", content = "无关剧情"))
        val base = SessionMemorySegmentEntity(sessionId = sid, startMessageId = ids[0], endMessageId = ids[0], summary = "保留前段")
        memorySegmentDao.insert(base)
        memorySegmentDao.insert(base.copy(segmentIndex = 1, startMessageId = ids[1], endMessageId = ids[2], summary = "受影响"))
        memorySegmentDao.insert(base.copy(segmentIndex = 2, startMessageId = ids[3], endMessageId = ids[5], summary = "后续依赖"))
        memorySegmentDao.insert(base.copy(branchId = "A", startMessageId = a, endMessageId = a, summary = "继承依赖"))
        memorySegmentDao.insert(base.copy(branchId = "B", startMessageId = b, endMessageId = b, summary = "无关分支"))
        db.sessionMemoryCorrectionDao().insert(SessionMemoryCorrectionEntity(sessionId = sid, content = "用户纠正"))
        assertEquals(3, messageDao.previewRecallInSession(sid, ids[1]).affectedSummaryCount)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test_recall BEFORE DELETE ON messages BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try { messageDao.recallInSession(sid, ids[1]); fail("deletion must fail") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(3, memorySegmentDao.getBySessionAndBranch(sid, "main").size)
        assertNotNull(messageDao.getById(ids[1]))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test_recall")
        assertTrue(messageDao.recallInSession(sid, ids[1]).deleted)
        assertEquals(listOf("保留前段"), memorySegmentDao.getBySessionAndBranch(sid, "main").map { it.summary })
        assertTrue(memorySegmentDao.getBySessionAndBranch(sid, "A").isEmpty())
        assertEquals(1, memorySegmentDao.getBySessionAndBranch(sid, "B").size)
        assertEquals(1, db.sessionMemoryCorrectionDao().getVisible(sid, "main").size)
        val snapshot = com.mojing.app.domain.engine.MemoryCompactionStore(db).read(sid, "main", 2)
        assertEquals(ids[0], snapshot.afterMessageId)
        assertEquals(listOf(ids[2], ids[3]), snapshot.sources.map { it.id })
    }

    @Test fun compactionStoreRejectsStaleSourcesAndDuplicateCursorAndRetriesFailedInsert() = runBlocking {
        val sid = sessionDao.insert(SessionEntity(title = "摘要提交"))
        val id = messageDao.insert(MessageEntity(sessionId = sid, speakerType = "user", content = "最初原文"))
        val store = com.mojing.app.domain.engine.MemoryCompactionStore(db)
        val stale = store.read(sid, "main", 1)
        val result = com.mojing.app.data.local.entity.SessionMemorySegmentEntity(sessionId = sid, startMessageId = id, endMessageId = id, summary = "摘要")
        messageDao.updateContent(id, "改写后原文")
        assertFalse(store.commit(stale, result))
        val fresh = store.read(sid, "main", 1)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test_summary BEFORE INSERT ON session_memory_segments BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try { store.commit(fresh, result); fail("Insertion must fail") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertTrue(db.sessionMemorySegmentDao().getRecentForBranch(sid, "main").isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test_summary")
        assertTrue(store.commit(fresh, result))
        assertFalse(store.commit(fresh, result))
        assertEquals(1, db.sessionMemorySegmentDao().getRecentForBranch(sid, "main").size)
    }

    @Test fun derivedEventsAreAtomicAndRepeatedExtractionDoesNotDuplicateRows() = runBlocking {
        val sid = sessionDao.insert(SessionEntity(title = "事件事务"))
        val id = messageDao.insert(MessageEntity(sessionId = sid, content = "发现线索"))
        val source = requireNotNull(messageDao.getById(id))
        val valid = SessionEventNodeEntity(sessionId = sid, messageId = id, title = "线索")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test_event BEFORE INSERT ON session_event_nodes WHEN NEW.title = 'reject' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try {
            messageDao.commitDerivedEvents(sid, "main", listOf(source), listOf(valid, valid.copy(title = "reject")))
            fail("Second insertion must fail")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertTrue(db.sessionEventNodeDao().getBySession(sid).isEmpty())
        val first = messageDao.commitDerivedEvents(sid, "main", listOf(source), listOf(valid))
        val second = messageDao.commitDerivedEvents(sid, "main", listOf(source), listOf(valid))
        assertEquals(first.single().id, second.single().id)
        assertEquals(1, db.sessionEventNodeDao().getBySession(sid).size)
        messageDao.updateContent(id, "原文已修改")
        assertTrue(messageDao.commitDerivedEvents(sid, "main", listOf(source), listOf(valid.copy(title = "旧模型结果"))).isEmpty())
    }

    @Test fun eventNodesUseStableCreatedAtAndIdCursorPages() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "事件分页"))
        val dao = db.sessionEventNodeDao()
        val inserted = listOf(300L, 300L, 200L, 100L, 100L).mapIndexed { index, createdAt ->
            dao.insert(
                SessionEventNodeEntity(
                    sessionId = sessionId,
                    title = "事件$index",
                    createdAt = createdAt,
                ),
            )
        }

        val first = dao.getPageForBranch(sessionId, "main", limit = 3)
        val cursor = first.last()
        val second = dao.getPageForBranch(
            sessionId = sessionId,
            branchId = "main",
            beforeCreatedAt = cursor.createdAt,
            beforeId = cursor.id,
            limit = 3,
        )

        assertEquals(listOf(inserted[1], inserted[0], inserted[2]), first.map { it.id })
        assertEquals(listOf(inserted[4], inserted[3]), second.map { it.id })
        assertEquals(inserted.toSet(), (first + second).map { it.id }.toSet())
    }

    @Test
    fun recallRejectsReferencedMediaBeforeAnyOriginalOrAttachmentIsRemoved() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "来源保护"))
        val sourceId = messageDao.insert(MessageEntity(sessionId = sessionId, content = "原始消息"))
        val mediaId = messageDao.insert(MessageEntity(sessionId = sessionId, parentMessageId = sourceId, includeInContext = false, structuredContentJson = """{"derived_media_version":1,"derived_media_kind":"image"}"""))
        db.attachmentDao().insert(MessageAttachmentEntity(messageId = mediaId, storagePath = "/private/keep.png"))
        assertTrue(messageDao.previewRecallInSession(sessionId, sourceId).canRecall)
        sessionBranchDao.insert(SessionBranchEntity(sessionId = sessionId, branchId = "A", sourceMessageId = mediaId, isCheckpoint = true))
        assertFalse(messageDao.previewRecallInSession(sessionId, sourceId).canRecall)
        try {
            messageDao.recallInSession(sessionId, sourceId)
            fail("Referenced child must reject the entire recall")
        } catch (_: com.mojing.app.data.local.dao.MessageRecallBlockedException) { }
        assertNotNull(messageDao.getById(sourceId))
        assertNotNull(messageDao.getById(mediaId))
        assertEquals(1, db.attachmentDao().countByStoragePath("/private/keep.png"))
    }

    @Test
    fun branchInsertionAfterRecallRejectsMissingSource() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "撤回后的分叉"))
        val sourceId = messageDao.insert(MessageEntity(sessionId = sessionId, content = "普通消息"))
        assertTrue(messageDao.recallInSession(sessionId, sourceId).deleted)
        try {
            sessionBranchDao.insert(SessionBranchEntity(sessionId = sessionId, branchId = "A", sourceMessageId = sourceId))
            fail("A missing source cannot become a new branch")
        } catch (_: IllegalArgumentException) { }
        assertTrue(sessionBranchDao.getBySession(sessionId).isEmpty())
    }

    @Test
    fun insertAndQuery() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "测试会话"))
        messageDao.insert(MessageEntity(sessionId = sessionId, speakerType = "user", content = "你好"))
        messageDao.insert(MessageEntity(sessionId = sessionId, speakerType = "character", content = "你好呀"))

        val messages = messageDao.getMainBranchMessages(sessionId)
        assertEquals(2, messages.size)
        assertEquals("你好", messages[0].content)
        assertEquals("character", messages[1].speakerType)
    }

    @Test
    fun branchFilter() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "分支测试"))
        messageDao.insert(MessageEntity(sessionId = sessionId, content = "msg1", branchId = "main"))
        val msg2Id = messageDao.insert(MessageEntity(sessionId = sessionId, content = "msg2", branchId = "main"))
        messageDao.insert(MessageEntity(sessionId = sessionId, content = "msg3", branchId = "main"))
        messageDao.insert(MessageEntity(sessionId = sessionId, content = "branch_msg1", branchId = "branch_a"))
        messageDao.insert(MessageEntity(sessionId = sessionId, content = "branch_msg2", branchId = "branch_a"))

        val visible = messageDao.getVisibleMessages(sessionId, "branch_a", msg2Id)
        assertEquals(4, visible.size)
    }

    @Test
    fun searchMessages() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "搜索测试"))
        messageDao.insert(MessageEntity(sessionId = sessionId, content = "今天天气很好"))
        messageDao.insert(MessageEntity(sessionId = sessionId, content = "明天可能下雨"))
        messageDao.insert(MessageEntity(sessionId = sessionId, content = "天气预报说晴天"))

        val results = messageDao.searchMainMessages(sessionId, "天气", exactMatch = 0, limit = 10)
        assertEquals(2, results.size)
    }

    @Test
    fun indexedSearchHandlesChineseMixedTextExactModeUpdatesAndDeletes() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "全文索引"))
        val otherSessionId = sessionDao.insert(SessionEntity(title = "隔离会话"))
        val firstId = messageDao.insert(
            MessageEntity(sessionId = sessionId, content = "月港 Weather-7 的钟声🙂"),
        )
        messageDao.insert(MessageEntity(sessionId = sessionId, content = "天气预报说晴天"))
        messageDao.insert(MessageEntity(sessionId = otherSessionId, content = "月港的另一条记录"))

        assertEquals(
            listOf(firstId),
            messageDao.searchMainMessages(sessionId, "ｗＥＡＴＨＥＲ－７", 0, 10).map { it.id },
        )
        assertEquals(
            listOf(firstId),
            messageDao.searchMainMessages(sessionId, "钟声🙂", 0, 10).map { it.id },
        )
        assertTrue(messageDao.searchMainMessages(sessionId, "月港", 1, 10).isEmpty())
        assertEquals(
            listOf(firstId),
            messageDao.searchMainMessages(sessionId, "月港 Weather-7 的钟声🙂", 1, 10).map { it.id },
        )

        messageDao.updateContent(firstId, "海崖的新钟声")
        assertTrue(messageDao.searchMainMessages(sessionId, "月港", 0, 10).isEmpty())
        assertEquals(
            listOf(firstId),
            messageDao.searchMainMessages(sessionId, "海崖", 0, 10).map { it.id },
        )

        messageDao.delete(firstId)
        assertTrue(messageDao.searchMainMessages(sessionId, "海崖", 0, 10).isEmpty())
    }

    @Test
    fun indexedSearchUsesVisibleGeneratedTextAndSkipsUnselectedChoices() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "结构消息搜索"))
        val messageId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                content = "<NARRATION>钟声穿过长廊。</NARRATION>" +
                    "<SPEECH>门已经打开。</SPEECH>" +
                    "<CHOICES><OPTION>进入密室</OPTION><OPTION>转身离开</OPTION></CHOICES>",
            ),
        )

        assertEquals(
            listOf(messageId),
            messageDao.searchMainMessages(sessionId, "门已经打开", 0, 10).map { it.id },
        )
        assertEquals(
            listOf(messageId),
            messageDao.searchMainMessages(sessionId, "钟声穿过长廊。\n\n门已经打开。", 1, 10).map { it.id },
        )
        assertTrue(messageDao.searchMainMessages(sessionId, "进入密室", 0, 10).isEmpty())
        assertTrue(messageDao.searchMainMessages(sessionId, "转身离开", 0, 10).isEmpty())
    }

    @Test
    fun incompleteIndexFallsBackThenRebuildsInRestartableBatches() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "索引重建"))
        repeat(5) { index ->
            messageDao.insert(MessageEntity(sessionId = sessionId, content = "旧剧情-$index-星门"))
        }
        db.openHelper.writableDatabase.execSQL(
            "UPDATE messages SET searchNormalized = '', searchTerms = ''",
        )
        messageDao.upsertSearchIndexState(
            MessageSearchIndexStateEntity(indexVersion = 1, indexedThroughMessageId = 0L),
        )

        assertEquals(5, messageDao.searchMainMessages(sessionId, "星门", 0, 10).size)

        val firstBatch = messageDao.rebuildSearchIndexBatch(batchSize = 2, now = 1L)
        assertEquals(2, firstBatch.indexedCount)
        assertFalse(firstBatch.isComplete)
        assertEquals(5, messageDao.searchMainMessages(sessionId, "星门", 0, 10).size)

        var result = firstBatch
        while (!result.isComplete) {
            result = messageDao.rebuildSearchIndexBatch(batchSize = 2, now = 2L)
        }
        assertEquals(5, messageDao.searchMainMessages(sessionId, "星门", 0, 10).size)
        assertTrue(requireNotNull(messageDao.getSearchIndexState()).isComplete)
    }

    @Test
    fun boundedVisibleWindowsRespectNestedBranchesAndEditedAncestors() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "有界分支窗口"))
        val main1 = messageDao.insert(MessageEntity(sessionId = sessionId, content = "主线一"))
        val main2 = messageDao.insert(MessageEntity(sessionId = sessionId, content = "主线二"))
        messageDao.insert(MessageEntity(sessionId = sessionId, content = "主线三不可见"))

        val branchA = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "branch_a",
            sourceMessageId = main2,
        )
        sessionBranchDao.insert(branchA)
        val replacementId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                branchId = branchA.branchId,
                regeneratedFromMessageId = main2,
                content = "主线二编辑版",
            ),
        )
        val branchATail = messageDao.insert(
            MessageEntity(sessionId = sessionId, branchId = branchA.branchId, content = "A 后续"),
        )
        val branchB = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "branch_b",
            parentBranchId = branchA.branchId,
            sourceMessageId = branchATail,
        )
        sessionBranchDao.insert(branchB)
        val branchBTail = messageDao.insert(
            MessageEntity(sessionId = sessionId, branchId = branchB.branchId, content = "B 后续"),
        )

        val latest = messageDao.getVisibleMessagesTail(sessionId, branchB.branchId, 3)
        assertEquals(listOf(branchBTail, branchATail, replacementId), latest.map { it.id })
        assertEquals("B 后续", messageDao.getVisibleStoryCardPreview(sessionId, branchB.branchId)?.contentPrefix)
        val older = messageDao.getVisibleMessagesBefore(sessionId, branchB.branchId, replacementId, 3)
        assertEquals(listOf(main1), older.map { it.id })
        assertNull(messageDao.getVisibleMessageById(sessionId, branchB.branchId, main2))
        assertTrue(
            messageDao.searchVisibleMessages(
                sessionId, branchB.branchId, "主线三", exactMatch = 0, limit = 10,
            ).isEmpty(),
        )
        assertEquals(
            listOf("B 后续", "A 后续"),
            messageDao.getVisibleContextTail(sessionId, branchB.branchId, 2).map { it.content },
        )

        sessionBranchDao.deleteAllVisibilitySegments()
        assertTrue(messageDao.getVisibleMessagesTail(sessionId, branchB.branchId, 3).isEmpty())
        assertTrue(sessionBranchDao.repairVisibilitySegmentsIfNeeded())
        assertEquals("B 后续", messageDao.getVisibleStoryCardPreview(sessionId, branchB.branchId)?.contentPrefix)
        assertEquals(
            listOf(branchBTail, branchATail, replacementId),
            messageDao.getVisibleMessagesTail(sessionId, branchB.branchId, 3).map { it.id },
        )
        assertFalse(sessionBranchDao.repairVisibilitySegmentsIfNeeded())
        messageDao.insert(MessageEntity(sessionId = sessionId, branchId = branchB.branchId, content = "长".repeat(2000)))
        assertEquals(1024, messageDao.getVisibleStoryCardPreview(sessionId, branchB.branchId)?.contentPrefix?.length)
    }

    @Test
    fun nextStoryContextBatchUsesStableCursorEffectiveSelectionAndLimit() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "记忆压缩游标"))
        messageDao.insert(
            MessageEntity(sessionId = sessionId, speakerType = "system", content = "内部消息"),
        )
        val userId = messageDao.insert(
            MessageEntity(sessionId = sessionId, speakerType = "user", content = "抵达门前"),
        )
        val groupId = "memory-swipe"
        val originalId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                swipeGroupId = groupId,
                includeInContext = true,
                content = "原回复",
                createdAt = 100L,
            ),
        )
        val alternativeId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                swipeGroupId = groupId,
                includeInContext = false,
                content = "采用的备选回复",
                createdAt = 200L,
            ),
        )
        messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                includeInContext = false,
                content = "不进入上下文的派生消息",
            ),
        )
        val narratorId = messageDao.insert(
            MessageEntity(sessionId = sessionId, speakerType = "narrator", content = "门扉开启"),
        )
        val branch = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "memory-branch",
            sourceMessageId = narratorId,
        )
        sessionBranchDao.insert(branch)
        val branchUserId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                branchId = branch.branchId,
                speakerType = "user",
                content = "进入门内",
            ),
        )
        assertEquals(
            1,
            messageDao.selectSwipeVariantForBranch(
                sessionId = sessionId,
                branchId = branch.branchId,
                gid = groupId,
                messageId = alternativeId,
            ),
        )

        assertEquals(
            listOf(originalId, narratorId),
            messageDao.getNextStoryContextBatch(sessionId, "main", userId, 10).map { it.id },
        )
        assertEquals(
            listOf(alternativeId, narratorId),
            messageDao.getNextStoryContextBatch(sessionId, branch.branchId, userId, 2).map { it.id },
        )
        assertEquals(
            listOf(narratorId, branchUserId),
            messageDao.getNextStoryContextBatch(
                sessionId,
                branch.branchId,
                alternativeId,
                10,
            ).map { it.id },
        )
    }

    @Test
    fun deleteMessage() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "删除测试"))
        val msgId = messageDao.insert(MessageEntity(sessionId = sessionId, content = "待删除"))
        assertEquals(1, messageDao.getMainBranchMessages(sessionId).size)

        messageDao.delete(msgId)
        assertEquals(0, messageDao.getMainBranchMessages(sessionId).size)
    }

    @Test
    fun swipeSelectionIsIndependentAcrossExistingAndNewStorylines() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "故事线版本隔离"))
        val groupId = "reply-isolated"
        val originalId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                swipeGroupId = groupId,
                includeInContext = true,
                content = "原版本",
                createdAt = 100L,
            ),
        )
        val alternativeId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                swipeGroupId = groupId,
                includeInContext = false,
                content = "备选版本",
                createdAt = 200L,
            ),
        )
        val branchA = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "branch-a",
            sourceMessageId = alternativeId,
        )
        val branchB = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "branch-b",
            sourceMessageId = alternativeId,
        )
        sessionBranchDao.insert(branchA)
        sessionBranchDao.insert(branchB)

        assertEquals(1, messageDao.selectSwipeVariantForBranch(sessionId, branchA.branchId, groupId, alternativeId))
        assertEquals(listOf(originalId), messageDao.getMainContextTail(sessionId, 10).map { it.id })
        assertEquals(
            listOf(alternativeId),
            messageDao.getVisibleContextTail(sessionId, branchA.branchId, 10).map { it.id },
        )
        assertEquals(
            listOf(originalId),
            messageDao.getVisibleContextTail(sessionId, branchB.branchId, 10).map { it.id },
        )

        assertEquals(1, messageDao.selectSwipeVariantForBranch(sessionId, "main", groupId, alternativeId))
        assertEquals(listOf(alternativeId), messageDao.getMainContextTail(sessionId, 10).map { it.id })
        assertEquals(
            listOf(originalId),
            messageDao.getVisibleContextTail(sessionId, branchB.branchId, 10).map { it.id },
        )

        val branchC = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "branch-c",
            sourceMessageId = alternativeId,
        )
        sessionBranchDao.insert(branchC)
        assertEquals(
            listOf(alternativeId),
            messageDao.getVisibleContextTail(sessionId, branchC.branchId, 10).map { it.id },
        )
    }

    @Test
    fun swipeSelectionInvalidatesOnlyTheChangedStorylineContextMemory() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "回复版本记忆隔离"))
        val groupId = "reply-memory"
        val originalId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                swipeGroupId = groupId,
                includeInContext = true,
                content = "原版本",
                createdAt = 100L,
            ),
        )
        val alternativeId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                swipeGroupId = groupId,
                includeInContext = false,
                content = "备选版本",
                createdAt = 200L,
            ),
        )
        val branchA = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "memory-a",
            sourceMessageId = alternativeId,
        )
        val branchB = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "memory-b",
            sourceMessageId = alternativeId,
        )
        sessionBranchDao.insert(branchA)
        sessionBranchDao.insert(branchB)
        val contextMemoryDao = db.sessionContextMemoryDao()
        contextMemoryDao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = "main",
                globalSummary = "主线记忆",
            ),
        )
        contextMemoryDao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = branchA.branchId,
                globalSummary = "分支 A 记忆",
            ),
        )
        contextMemoryDao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = branchB.branchId,
                globalSummary = "分支 B 记忆",
            ),
        )

        assertEquals(
            1,
            messageDao.selectSwipeVariantForBranch(
                sessionId,
                branchA.branchId,
                groupId,
                alternativeId,
            ),
        )
        val invalidatedBranchA = requireNotNull(
            contextMemoryDao.getBySessionAndBranch(sessionId, branchA.branchId),
        )
        assertFalse(invalidatedBranchA.isValid)
        assertEquals(1L, invalidatedBranchA.revision)
        val mainMemory = requireNotNull(contextMemoryDao.getBySessionAndBranch(sessionId, "main"))
        assertTrue(mainMemory.isValid)
        assertEquals(0L, mainMemory.revision)
        val branchBMemory = requireNotNull(
            contextMemoryDao.getBySessionAndBranch(sessionId, branchB.branchId),
        )
        assertTrue(branchBMemory.isValid)
        assertEquals(0L, branchBMemory.revision)

        // 当前有效版本的重复选择是幂等操作，不应让仍可用的兄弟线记忆失效。
        assertEquals(
            1,
            messageDao.selectSwipeVariantForBranch(
                sessionId,
                branchB.branchId,
                groupId,
                originalId,
            ),
        )
        val branchBAfterNoOp = requireNotNull(
            contextMemoryDao.getBySessionAndBranch(sessionId, branchB.branchId),
        )
        assertTrue(branchBAfterNoOp.isValid)
        assertEquals(0L, branchBAfterNoOp.revision)
    }

    @Test
    fun storylineFallsBackToLatestVisibleVariantWhenFrozenDefaultIsBeyondItsAnchor() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "分支可见版本兜底"))
        val groupId = "reply-before-anchor"
        val visibleVariantId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                swipeGroupId = groupId,
                includeInContext = false,
                content = "分支仍可见的版本",
                createdAt = 100L,
            ),
        )
        val frozenDefaultId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                swipeGroupId = groupId,
                includeInContext = true,
                content = "锚点之后的主线默认版本",
                createdAt = 200L,
            ),
        )
        val branch = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "branch-before-default",
            sourceMessageId = visibleVariantId,
        )
        sessionBranchDao.insert(branch)

        assertEquals(listOf(frozenDefaultId), messageDao.getMainContextTail(sessionId, 10).map { it.id })
        assertEquals(
            listOf(visibleVariantId),
            messageDao.getVisibleContextTail(sessionId, branch.branchId, 10).map { it.id },
        )
    }

    @Test
    fun regeneratedBranchReplyDoesNotChangeMainDefault() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "分支重生成隔离"))
        val originalId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                content = "主线原回复",
                createdAt = 100L,
            ),
        )
        val branch = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "branch-regenerate",
            sourceMessageId = originalId,
        )
        sessionBranchDao.insert(branch)
        val contextMemoryDao = db.sessionContextMemoryDao()
        contextMemoryDao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = "main",
                globalSummary = "主线仍有效",
            ),
        )
        contextMemoryDao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = branch.branchId,
                globalSummary = "重生成前的分支记忆",
            ),
        )

        val regeneratedId = messageDao.insertAndSelectSwipeVariant(
            entity = MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                branchId = branch.branchId,
                swipeGroupId = "generated-group",
                content = "分支新回复",
                createdAt = 200L,
            ),
            branchId = branch.branchId,
            targetMessageId = originalId,
        )

        assertEquals("generated-group", messageDao.getById(originalId)?.swipeGroupId)
        assertTrue(requireNotNull(messageDao.getById(originalId)).includeInContext)
        assertFalse(requireNotNull(messageDao.getById(regeneratedId)).includeInContext)
        assertEquals(listOf(originalId), messageDao.getMainContextTail(sessionId, 10).map { it.id })
        assertEquals(
            listOf(regeneratedId),
            messageDao.getVisibleContextTail(sessionId, branch.branchId, 10).map { it.id },
        )
        val mainMemory = requireNotNull(contextMemoryDao.getBySessionAndBranch(sessionId, "main"))
        assertTrue(mainMemory.isValid)
        assertEquals(0L, mainMemory.revision)
        val branchMemory = requireNotNull(
            contextMemoryDao.getBySessionAndBranch(sessionId, branch.branchId),
        )
        assertFalse(branchMemory.isValid)
        assertEquals(1L, branchMemory.revision)
    }

    @Test
    fun failedRegenerationCommitRollsBackFirstGroupBinding() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "重生成事务回滚"))
        val originalId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                content = "仍需保留的原回复",
            ),
        )

        val result = runCatching {
            messageDao.insertAndSelectSwipeVariant(
                entity = MessageEntity(
                    sessionId = sessionId,
                    speakerType = "character",
                    branchId = "missing-branch",
                    swipeGroupId = "new-group",
                    content = "不应提交的新回复",
                ),
                branchId = "missing-branch",
                targetMessageId = originalId,
            )
        }

        assertTrue(result.isFailure)
        assertNull(messageDao.getById(originalId)?.swipeGroupId)
        assertEquals(1, messageDao.getMainBranchMessages(sessionId).size)
        assertNull(messageDao.getBranchSwipeSelection(sessionId, "missing-branch", "new-group"))
    }

    @Test
    fun editedSwipeReplyKeepsOneGroupAndSelectsReplacementOnlyInNewStoryline() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "编辑版本隔离"))
        val groupId = "edited-group"
        val originalId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                swipeGroupId = groupId,
                includeInContext = true,
                content = "原回复",
                createdAt = 100L,
            ),
        )
        messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                swipeGroupId = groupId,
                includeInContext = false,
                content = "另一个旧版本",
                createdAt = 150L,
            ),
        )
        val branch = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "edited-branch",
            sourceMessageId = originalId,
        )
        val replacementId = sessionBranchDao.insertEditedBranch(
            branch = branch,
            replacement = MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                branchId = branch.branchId,
                regeneratedFromMessageId = originalId,
                swipeGroupId = groupId,
                includeInContext = false,
                content = "编辑后的回复",
                createdAt = 200L,
            ),
            attachments = emptyList(),
        )

        assertEquals(
            replacementId,
            messageDao.getBranchSwipeSelection(sessionId, branch.branchId, groupId)?.selectedMessageId,
        )
        assertEquals(
            listOf(replacementId),
            messageDao.getVisibleContextTail(sessionId, branch.branchId, 10).map { it.id },
        )
        assertEquals(listOf(originalId), messageDao.getMainContextTail(sessionId, 10).map { it.id })
    }

    @Test
    fun recallActiveSwipeDeletesOwnedMediaAndRestoresRemainingVariant() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "撤回版本测试"))
        val otherSessionId = sessionDao.insert(SessionEntity(title = "同组隔离测试"))
        val groupId = "reply-1"
        val olderId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                swipeGroupId = groupId,
                includeInContext = false,
                content = "旧版本",
                createdAt = 100L,
            ),
        )
        val activeId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                swipeGroupId = groupId,
                content = "当前版本",
                createdAt = 200L,
            ),
        )
        // This branch inherits the reply, but the reply itself is not its source.
        val laterAnchorId = messageDao.insert(MessageEntity(sessionId = sessionId, content = "后续分叉点", includeInContext = false))
        val affectedBranch = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "recall-sees-active",
            sourceMessageId = laterAnchorId,
        )
        val siblingBeforeActive = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "recall-before-active",
            sourceMessageId = olderId,
        )
        sessionBranchDao.insert(affectedBranch)
        sessionBranchDao.insert(siblingBeforeActive)
        val contextMemoryDao = db.sessionContextMemoryDao()
        contextMemoryDao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = "main",
                globalSummary = "主线当前版本记忆",
                revision = 2L,
            ),
        )
        contextMemoryDao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = affectedBranch.branchId,
                globalSummary = "看见当前版本的分支记忆",
                revision = 4L,
            ),
        )
        contextMemoryDao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = siblingBeforeActive.branchId,
                globalSummary = "锚点更早的兄弟分支记忆",
                revision = 6L,
            ),
        )
        val mediaId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                parentMessageId = activeId,
                includeInContext = false,
                structuredContentJson = """{"derived_media_version":1,"derived_media_kind":"image"}""",
                content = "",
                createdAt = 300L,
            ),
        )
        val otherSessionActiveId = messageDao.insert(
            MessageEntity(
                sessionId = otherSessionId,
                speakerType = "character",
                swipeGroupId = groupId,
                content = "另一会话当前版本",
                createdAt = 400L,
            ),
        )
        val attachmentDao = db.attachmentDao()
        attachmentDao.insert(
            MessageAttachmentEntity(
                messageId = mediaId,
                storagePath = "/private/generated.png",
            ),
        )

        val result = messageDao.recallInSession(sessionId, activeId)

        assertTrue(result.deleted)
        assertEquals(listOf(mediaId, activeId), result.deletedMessageIds)
        assertEquals(listOf("/private/generated.png"), result.attachmentStoragePaths)
        assertEquals(olderId, result.fallbackSwipeMessageId)
        assertNull(messageDao.getById(activeId))
        assertNull(messageDao.getById(mediaId))
        assertFalse(requireNotNull(messageDao.getById(olderId)).includeInContext)
        assertEquals(listOf(olderId), messageDao.getMainContextTail(sessionId, 10).map { it.id })
        assertEquals(
            olderId,
            messageDao.getBranchSwipeSelection(sessionId, "main", groupId)?.selectedMessageId,
        )
        assertEquals(
            olderId,
            messageDao.getBranchSwipeSelection(
                sessionId,
                affectedBranch.branchId,
                groupId,
            )?.selectedMessageId,
        )
        val mainMemory = requireNotNull(contextMemoryDao.getBySessionAndBranch(sessionId, "main"))
        assertFalse(mainMemory.isValid)
        assertEquals(3L, mainMemory.revision)
        val affectedMemory = requireNotNull(
            contextMemoryDao.getBySessionAndBranch(sessionId, affectedBranch.branchId),
        )
        assertFalse(affectedMemory.isValid)
        assertEquals(5L, affectedMemory.revision)
        val siblingMemory = requireNotNull(
            contextMemoryDao.getBySessionAndBranch(sessionId, siblingBeforeActive.branchId),
        )
        assertTrue(siblingMemory.isValid)
        assertEquals(6L, siblingMemory.revision)
        assertTrue(requireNotNull(messageDao.getById(otherSessionActiveId)).includeInContext)
        assertTrue(attachmentDao.getByMessage(mediaId).isEmpty())
    }

    @Test
    fun visibleMemorySegmentsFollowBranchAndRejectEditedSourceRanges() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "分支记忆"))
        val main1 = messageDao.insert(MessageEntity(sessionId = sessionId, content = "主线一"))
        val main2 = messageDao.insert(MessageEntity(sessionId = sessionId, content = "主线二"))
        val main3 = messageDao.insert(MessageEntity(sessionId = sessionId, content = "主线三"))
        memorySegmentDao.insert(
            SessionMemorySegmentEntity(
                sessionId = sessionId,
                branchId = "main",
                segmentIndex = 0,
                startMessageId = main1,
                endMessageId = main1,
                summary = "可继承摘要",
            ),
        )
        memorySegmentDao.insert(
            SessionMemorySegmentEntity(
                sessionId = sessionId,
                branchId = "main",
                segmentIndex = 1,
                startMessageId = main2,
                endMessageId = main3,
                summary = "覆盖编辑点的旧摘要",
            ),
        )
        val branch = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "edit_$main2",
            sourceMessageId = main2,
        )
        sessionBranchDao.insert(branch)
        val replacement = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                branchId = branch.branchId,
                regeneratedFromMessageId = main2,
                content = "主线二编辑版",
            ),
        )
        memorySegmentDao.insert(
            SessionMemorySegmentEntity(
                sessionId = sessionId,
                branchId = branch.branchId,
                segmentIndex = 0,
                startMessageId = replacement,
                endMessageId = replacement,
                summary = "编辑分支摘要",
            ),
        )
        val sibling = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "sibling",
            sourceMessageId = main1,
        )
        sessionBranchDao.insert(sibling)
        db.sessionEventNodeDao().insert(
            SessionEventNodeEntity(
                sessionId = sessionId,
                branchId = "main",
                messageId = main1,
                title = "可继承事件",
                importance = 2,
            ),
        )
        db.sessionEventNodeDao().insert(
            SessionEventNodeEntity(
                sessionId = sessionId,
                branchId = "main",
                messageId = main2,
                title = "被编辑替换的事件",
                importance = 5,
            ),
        )
        db.sessionEventNodeDao().insert(
            SessionEventNodeEntity(
                sessionId = sessionId,
                branchId = branch.branchId,
                messageId = replacement,
                title = "编辑分支事件",
                importance = 3,
            ),
        )
        db.sessionEventNodeDao().insert(
            SessionEventNodeEntity(
                sessionId = sessionId,
                branchId = sibling.branchId,
                messageId = main1,
                title = "兄弟分支事件",
                importance = 4,
            ),
        )

        assertEquals(
            listOf("编辑分支摘要", "可继承摘要"),
            memorySegmentDao.getRecentForBranch(sessionId, branch.branchId).map { it.summary },
        )
        assertEquals(
            listOf("覆盖编辑点的旧摘要", "可继承摘要"),
            memorySegmentDao.getRecentForBranch(sessionId, "main").map { it.summary },
        )
        assertEquals(
            listOf("编辑分支事件", "可继承事件"),
            db.sessionEventNodeDao().getForBranch(sessionId, branch.branchId).map { it.title },
        )
    }

    @Test
    fun deletingMessageAtomicallyInvalidatesCoveringAutomaticMemory() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "删除记忆来源"))
        val first = messageDao.insert(MessageEntity(sessionId = sessionId, content = "保留"))
        val deleted = messageDao.insert(MessageEntity(sessionId = sessionId, content = "删除"))
        val siblingBeforeDeleted = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "delete-before-source",
            sourceMessageId = first,
        )
        val branchSeeingDeleted = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "delete-after-source",
            sourceMessageId = deleted,
        )
        val editedBranch = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "delete-replaced-source",
            sourceMessageId = deleted,
        )
        sessionBranchDao.insert(siblingBeforeDeleted)
        sessionBranchDao.insert(branchSeeingDeleted)
        sessionBranchDao.insert(editedBranch)
        val siblingOwnMessage = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                branchId = siblingBeforeDeleted.branchId,
                content = "兄弟分支自己的后续",
            ),
        )
        val editedReplacement = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                branchId = editedBranch.branchId,
                regeneratedFromMessageId = deleted,
                content = "已经替代待删原文的版本",
            ),
        )
        memorySegmentDao.insert(
            SessionMemorySegmentEntity(
                sessionId = sessionId,
                startMessageId = first,
                endMessageId = first,
                summary = "不受影响",
            ),
        )
        memorySegmentDao.insert(
            SessionMemorySegmentEntity(
                sessionId = sessionId,
                startMessageId = first,
                endMessageId = deleted,
                summary = "必须失效",
            ),
        )
        val contextMemoryDao = db.sessionContextMemoryDao()
        contextMemoryDao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = "main",
                sourceStartMessageId = first,
                sourceEndMessageId = deleted,
                globalSummary = "必须失效的通用记忆",
                revision = 2L,
            ),
        )
        contextMemoryDao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = branchSeeingDeleted.branchId,
                sourceStartMessageId = first,
                sourceEndMessageId = deleted,
                globalSummary = "看见待删消息的分支记忆",
                revision = 4L,
            ),
        )
        contextMemoryDao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = siblingBeforeDeleted.branchId,
                sourceStartMessageId = first,
                sourceEndMessageId = siblingOwnMessage,
                globalSummary = "数字范围覆盖但实际看不见待删消息",
                revision = 6L,
            ),
        )
        contextMemoryDao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = editedBranch.branchId,
                sourceStartMessageId = first,
                sourceEndMessageId = editedReplacement,
                globalSummary = "已采用替代消息的故事线记忆",
                revision = 8L,
            ),
        )
        db.sessionEventNodeDao().insert(
            SessionEventNodeEntity(
                sessionId = sessionId,
                messageId = deleted,
                title = "必须失效的事件",
            ),
        )

        assertTrue(messageDao.delete(deleted))
        assertNull(messageDao.getById(deleted))
        assertEquals(
            listOf("不受影响"),
            memorySegmentDao.getBySessionAndBranch(sessionId, "main").map { it.summary },
        )
        val mainMemory = requireNotNull(contextMemoryDao.getBySessionAndBranch(sessionId, "main"))
        assertFalse(mainMemory.isValid)
        assertEquals(3L, mainMemory.revision)
        val affectedBranchMemory = requireNotNull(
            contextMemoryDao.getBySessionAndBranch(sessionId, branchSeeingDeleted.branchId),
        )
        assertFalse(affectedBranchMemory.isValid)
        assertEquals(5L, affectedBranchMemory.revision)
        val siblingMemory = requireNotNull(
            contextMemoryDao.getBySessionAndBranch(sessionId, siblingBeforeDeleted.branchId),
        )
        assertTrue(siblingMemory.isValid)
        assertEquals(6L, siblingMemory.revision)
        val editedMemory = requireNotNull(
            contextMemoryDao.getBySessionAndBranch(sessionId, editedBranch.branchId),
        )
        assertTrue(editedMemory.isValid)
        assertEquals(8L, editedMemory.revision)
        assertTrue(db.sessionEventNodeDao().getBySession(sessionId).isEmpty())
        assertFalse(messageDao.delete(deleted))
    }

    @Test
    fun updateMessageContentInvalidatesEveryStorylineSeeingIt() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "更新测试"))
        val msgId = messageDao.insert(MessageEntity(sessionId = sessionId, content = "原始文本"))
        val branch = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "update-visible-branch",
            sourceMessageId = msgId,
        )
        sessionBranchDao.insert(branch)
        val contextMemoryDao = db.sessionContextMemoryDao()
        contextMemoryDao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = "main",
                globalSummary = "修改前主线记忆",
            ),
        )
        contextMemoryDao.upsert(
            SessionContextMemoryEntity(
                sessionId = sessionId,
                branchId = branch.branchId,
                globalSummary = "修改前分支记忆",
            ),
        )
        messageDao.updateContent(msgId, "修改后的文本")

        val messages = messageDao.getMainBranchMessages(sessionId)
        assertEquals("修改后的文本", messages.first().content)
        val mainMemory = requireNotNull(contextMemoryDao.getBySessionAndBranch(sessionId, "main"))
        assertFalse(mainMemory.isValid)
        assertEquals(1L, mainMemory.revision)
        val branchMemory = requireNotNull(
            contextMemoryDao.getBySessionAndBranch(sessionId, branch.branchId),
        )
        assertFalse(branchMemory.isValid)
        assertEquals(1L, branchMemory.revision)
    }

    @Test
    fun editedBranchTransactionCopiesMessageAndAttachmentsWithoutMutatingOriginal() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "编辑分支测试"))
        val originalId = messageDao.insert(
            MessageEntity(sessionId = sessionId, speakerType = "character", content = "原回复"),
        )
        val branch = SessionBranchEntity(
            sessionId = sessionId,
            branchId = "edit_$originalId",
            sourceMessageId = originalId,
        )
        val replacementId = sessionBranchDao.insertEditedBranch(
            branch = branch,
            replacement = MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                branchId = branch.branchId,
                regeneratedFromMessageId = originalId,
                content = "新回复",
            ),
            attachments = listOf(
                MessageAttachmentEntity(
                    id = 55L,
                    messageId = originalId,
                    storagePath = "F:/images/scene.png",
                ),
            ),
        )

        assertEquals("原回复", messageDao.getById(originalId)?.content)
        val replacement = messageDao.getById(replacementId)
        assertEquals("新回复", replacement?.content)
        assertEquals(originalId, replacement?.regeneratedFromMessageId)
        val copied = db.attachmentDao().getByMessage(replacementId).single()
        assertNotEquals(55L, copied.id)
        assertEquals("F:/images/scene.png", copied.storagePath)
    }

    @Test
    fun importBatchIsAtomicAndDuplicateRetryIsNoOp() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "导入事务测试"))
        val marker = "\"st_import_batch\":\"batch-a\""
        val messages = listOf(
            MessageEntity(
                sessionId = sessionId,
                speakerType = "user",
                content = "第一句",
                structuredContentJson = "{$marker}",
            ),
            MessageEntity(
                sessionId = sessionId,
                speakerType = "character",
                content = "第二句",
                structuredContentJson = "{$marker}",
            ),
        )

        assertEquals(2, messageDao.insertImportBatchIfAbsent(sessionId, "main", marker, messages))
        assertEquals(0, messageDao.insertImportBatchIfAbsent(sessionId, "main", marker, messages))
        assertEquals(listOf("第一句", "第二句"), messageDao.getMainBranchMessages(sessionId).map { it.content })
    }

    @Test
    fun invalidImportBatchLeavesNoPartialRows() = runBlocking {
        val sessionId = sessionDao.insert(SessionEntity(title = "导入回滚测试"))
        val marker = "\"st_import_batch\":\"batch-invalid\""
        db.openHelper.writableDatabase.execSQL(
            """
                CREATE TRIGGER fail_second_import_message
                BEFORE INSERT ON messages
                WHEN NEW.content = '触发事务失败'
                BEGIN
                    SELECT RAISE(ABORT, 'forced import failure');
                END
            """.trimIndent(),
        )
        val messages = listOf(
            MessageEntity(
                sessionId = sessionId,
                content = "原本有效的第一句",
                structuredContentJson = "{$marker}",
            ),
            MessageEntity(
                sessionId = sessionId,
                content = "触发事务失败",
                structuredContentJson = "{$marker}",
            ),
        )

        val failure = runCatching {
            messageDao.insertImportBatchIfAbsent(sessionId, "main", marker, messages)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(messageDao.getMainBranchMessages(sessionId).isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
class SessionDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var messageDao: MessageDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        sessionDao = db.sessionDao()
        messageDao = db.messageDao()
    }

    @After
    fun teardown() { db.close() }

    @Test
    fun createAndQuery() = runBlocking {
        val id = sessionDao.insert(SessionEntity(title = "新会话"))
        val session = sessionDao.getById(id)
        assertEquals("新会话", session?.title)
    }

    @Test
    fun updateTitle() = runBlocking {
        val id = sessionDao.insert(SessionEntity(title = "原标题"))
        sessionDao.updateTitle(id, "新标题")
        assertEquals("新标题", sessionDao.getById(id)?.title)
    }

    @Test
    fun generatedTitleOnlyReplacesPlaceholder() = runBlocking {
        val defaultId = sessionDao.insert(SessionEntity(title = "新对话", updatedAt = 1L))
        val blankId = sessionDao.insert(SessionEntity(title = "   ", updatedAt = 2L))
        val customId = sessionDao.insert(SessionEntity(title = "我的长篇故事", updatedAt = 3L))

        sessionDao.touchWithGeneratedTitle(defaultId, "第一幕：雨夜", updatedAt = 11L)
        sessionDao.touchWithGeneratedTitle(blankId, "第二幕：来客", updatedAt = 12L)
        sessionDao.touchWithGeneratedTitle(customId, "不应覆盖", updatedAt = 13L)

        assertEquals("第一幕：雨夜", sessionDao.getById(defaultId)?.title)
        assertEquals("第二幕：来客", sessionDao.getById(blankId)?.title)
        assertEquals("我的长篇故事", sessionDao.getById(customId)?.title)
        assertEquals(11L, sessionDao.getById(defaultId)?.updatedAt)
        assertEquals(12L, sessionDao.getById(blankId)?.updatedAt)
        assertEquals(13L, sessionDao.getById(customId)?.updatedAt)
    }

    @Test
    fun searchSessions() = runBlocking {
        sessionDao.insert(SessionEntity(title = "修仙对话"))
        sessionDao.insert(SessionEntity(title = "科技讨论"))
        sessionDao.insert(SessionEntity(title = "修仙日常"))

        val results = sessionDao.search("修仙")
        assertEquals(2, results.size)
    }

    @Test
    fun deleteSession() = runBlocking {
        val id = sessionDao.insert(SessionEntity(title = "待删除"))
        assertNotNull(sessionDao.getById(id))
        sessionDao.delete(id)
        assertNull(sessionDao.getById(id))
    }
}

@RunWith(AndroidJUnit4::class)
class EncyclopediaDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var encyclopediaDao: EncyclopediaDao
    private lateinit var entryDao: EncyclopediaEntryDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        encyclopediaDao = db.encyclopediaDao()
        entryDao = db.encyclopediaEntryDao()
    }

    @After
    fun teardown() { db.close() }

    @Test
    fun createAndQueryEncyclopedia() = runBlocking {
        val id = encyclopediaDao.upsert(EncyclopediaEntity(name = "测试百科", gameplayMode = "自由剧情"))
        val result = encyclopediaDao.getById(id)
        assertEquals("测试百科", result?.name)
        assertEquals("自由剧情", result?.gameplayMode)
    }

    @Test
    fun entryCrudAndFilter() = runBlocking {
        val encId = encyclopediaDao.upsert(EncyclopediaEntity(name = "百科"))
        entryDao.upsert(EncyclopediaEntryEntity(encyclopediaId = encId, title = "角色A", entryType = "character"))
        entryDao.upsert(EncyclopediaEntryEntity(encyclopediaId = encId, title = "地点A", entryType = "location"))
        entryDao.upsert(EncyclopediaEntryEntity(encyclopediaId = encId, title = "角色B", entryType = "character"))

        val all = entryDao.getByEncyclopedia(encId)
        assertEquals(3, all.size)

        val characters = entryDao.getByType(encId, "character")
        assertEquals(2, characters.size)
    }

    @Test
    fun searchEntries() = runBlocking {
        val encId = encyclopediaDao.upsert(EncyclopediaEntity(name = "搜索百科"))
        entryDao.upsert(EncyclopediaEntryEntity(encyclopediaId = encId, title = "龙族", content = "火焰之地"))
        entryDao.upsert(EncyclopediaEntryEntity(encyclopediaId = encId, title = "人族", content = "田园生活"))
        entryDao.upsert(EncyclopediaEntryEntity(encyclopediaId = encId, title = "凤凰", content = "火焰重生"))

        val results = entryDao.search(encId, "火焰")
        assertEquals(2, results.size)
    }

    @Test
    fun deleteEncyclopediaCascadesEntries() = runBlocking {
        val encId = encyclopediaDao.upsert(EncyclopediaEntity(name = "待删百科"))
        entryDao.upsert(EncyclopediaEntryEntity(encyclopediaId = encId, title = "条目1"))
        assertEquals(1, entryDao.getByEncyclopedia(encId).size)

        encyclopediaDao.delete(encId)
        assertNull(encyclopediaDao.getById(encId))
    }

    @Test
    fun updatingExistingEncyclopediaPreservesEntriesAndNeverRecreatesDeletedParent() = runBlocking {
        val encId = encyclopediaDao.upsert(EncyclopediaEntity(name = "更新保护百科"))
        entryDao.upsert(EncyclopediaEntryEntity(encyclopediaId = encId, title = "必须保留的条目"))
        val existing = requireNotNull(encyclopediaDao.getById(encId))

        encyclopediaDao.upsert(existing.copy(description = "整行更新", updatedAt = 2L))
        assertEquals(1, entryDao.getByEncyclopedia(encId).size)
        assertEquals(1, encyclopediaDao.updateCoverIfUnchanged(encId, "", "cover.jpg", 3L))
        assertEquals(0, encyclopediaDao.updateCoverIfUnchanged(encId, "", "late.jpg", 4L))
        assertEquals("cover.jpg", encyclopediaDao.getById(encId)?.coverImagePath)
        assertEquals(1, entryDao.getByEncyclopedia(encId).size)

        encyclopediaDao.delete(encId)
        assertEquals(0, encyclopediaDao.updateCover(encId, "late.jpg", 4L))
        assertNull(encyclopediaDao.getById(encId))
    }
}

@RunWith(AndroidJUnit4::class)
class WorldTemplateDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var templateDao: WorldTemplateDao
    private lateinit var loreDao: WorldLoreEntryDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        templateDao = db.worldTemplateDao()
        loreDao = db.worldLoreEntryDao()
    }

    @After
    fun teardown() { db.close() }

    @Test
    fun atomicTemplateUpdatesPreserveOtherFieldsAndDoNotRecreateDeletedRows() = runBlocking {
        val templateId = templateDao.upsert(
            WorldTemplateEntity(templateId = "safe", label = "原名称", summary = "原摘要"),
        )
        loreDao.upsert(WorldLoreEntryEntity(worldTemplateId = templateId, title = "必须保留的设定"))

        assertEquals(1, templateDao.updateCover(templateId, "cover.jpg", 2L))
        assertEquals(
            1,
            templateDao.updateGeneratedContentIfUnchanged(
                templateId,
                expectedSummary = "原摘要",
                expectedWorldPrompt = "",
                summary = "新摘要",
                worldPrompt = "新世界书",
                updatedAt = 3L,
            ),
        )
        assertEquals(
            0,
            templateDao.updateGeneratedContentIfUnchanged(
                templateId,
                expectedSummary = "原摘要",
                expectedWorldPrompt = "",
                summary = "迟到摘要",
                worldPrompt = "迟到世界书",
                updatedAt = 4L,
            ),
        )
        val updated = requireNotNull(templateDao.getById(templateId))
        assertEquals("原名称", updated.label)
        assertEquals("cover.jpg", updated.coverImagePath)
        assertEquals("新摘要", updated.summary)
        assertEquals(1, loreDao.getByTemplate(templateId).size)

        templateDao.delete(templateId)
        assertEquals(0, templateDao.updateCover(templateId, "late.jpg", 4L))
        assertNull(templateDao.getById(templateId))
    }
}

@RunWith(AndroidJUnit4::class)
class CharacterDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var characterDao: CharacterDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        characterDao = db.characterDao()
    }

    @After
    fun teardown() { db.close() }

    @Test
    fun createAndQueryCharacter() = runBlocking {
        val id = characterDao.upsert(CharacterEntity(name = "主角", personaPrompt = "勇敢的冒险者"))
        val result = characterDao.getById(id)
        assertEquals("主角", result?.name)
        assertEquals("勇敢的冒险者", result?.personaPrompt)
    }

    @Test
    fun toggleFavorite() = runBlocking {
        val id = characterDao.upsert(CharacterEntity(name = "收藏角色"))
        assertFalse(characterDao.getById(id)?.favorite ?: true)
        characterDao.toggleFavorite(id)
        assertTrue(characterDao.getById(id)?.favorite ?: false)
    }

    @Test
    fun deleteCharacter() = runBlocking {
        val id = characterDao.upsert(CharacterEntity(name = "临时角色"))
        assertNotNull(characterDao.getById(id))
        characterDao.delete(id)
        assertNull(characterDao.getById(id))
    }

    @Test
    fun getAllBoundExcludesUnboundCharacters() = runBlocking {
        characterDao.upsert(CharacterEntity(name = "未绑定", boundEncyclopediaId = 0L))
        characterDao.upsert(CharacterEntity(name = "已绑定", boundEncyclopediaId = 42L))
        val bound = characterDao.getAllBound()
        assertEquals(1, bound.size)
        assertEquals("已绑定", bound.first().name)
        assertEquals(42L, bound.first().boundEncyclopediaId)
    }

    @Test
    fun observeByEncyclopediaFiltersCorrectly() = runBlocking {
        characterDao.upsert(CharacterEntity(name = "A", boundEncyclopediaId = 1L))
        characterDao.upsert(CharacterEntity(name = "B", boundEncyclopediaId = 2L))
        characterDao.upsert(CharacterEntity(name = "C", boundEncyclopediaId = 1L))
        val enc1 = characterDao.getAll().filter { it.boundEncyclopediaId == 1L }
        assertEquals(2, enc1.size)
    }
}

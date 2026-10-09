package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BranchContextMemoryInheritanceDaoTest {
    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    private suspend fun seed(db: AppDatabase, count: Int = 81): Pair<Long, List<Long>> {
        val session = db.sessionDao().insert(SessionEntity(title = "合成记忆继承"))
        val ids = (1..count).map { db.messageDao().insert(MessageEntity(sessionId = session, content = "剧情$it")) }
        db.sessionContextMemoryDao().upsert(SessionContextMemoryEntity(sessionId = session,
            globalSummary = "已知剧情", userStateJson = "{\"location\":\"北塔\"}", continuityRulesJson = "[\"守约\"]",
            sourceStartMessageId = ids.first(), sourceEndMessageId = ids.last(), revision = 17))
        return session to ids
    }
    private suspend fun fork(db: AppDatabase, session: Long, anchor: Long, child: String = "child", parent: String = "main") =
        db.sessionBranchDao().insert(SessionBranchEntity(sessionId = session, branchId = child,
            parentBranchId = parent, sourceMessageId = anchor))
    private suspend fun memory(db: AppDatabase, session: Long, branch: String = "main") =
        db.sessionContextMemoryDao().getBySessionAndBranch(session, branch)

    @Test fun multiPageCloneHasIndependentIdRevisionAndParentRemainsUnchanged() = runBlocking {
        val db = database()
        try {
            val (s, ids) = seed(db)
            val parent = memory(db, s)!!
            fork(db, s, ids.last())
            val child = memory(db, s, "child")!!
            assertNotEquals(parent.id, child.id)
            assertEquals(parent.copy(id = child.id, branchId = "child", revision = 0,
                createdAt = child.createdAt, updatedAt = child.updatedAt), child)
            val revision = db.sessionContextMemoryDao().reserveNextRevision(s, "child", 123)
            assertEquals(1, revision)
            assertTrue(db.sessionContextMemoryDao().replaceIfRevisionMatches(child.copy(globalSummary = "子线进展"), revision))
            assertEquals(parent, memory(db, s))
            db.sessionContextMemoryDao().clearAndAdvanceRevision(s, "child", 456)
            assertFalse(db.sessionContextMemoryDao().replaceIfRevisionMatches(child, revision))
            assertFalse(memory(db, s, "child")!!.isValid)
            assertEquals(parent, memory(db, s))
        } finally { db.close() }
    }

    @Test fun futureInvalidMalformedAndMissingBoundaryAreRejected() = runBlocking {
        val db = database()
        try {
            val (s, ids) = seed(db, 4)
            val parent = memory(db, s)!!
            fork(db, s, ids[1], "future")
            assertNull(memory(db, s, "future"))
            listOf(parent.copy(isValid = false), parent.copy(sourceStartMessageId = 0),
                parent.copy(memoryVersion = 2), parent.copy(sourceEndMessageId = ids.last() + 1),
                parent.copy(sourceStartMessageId = ids[1])).forEachIndexed { i, row ->
                db.sessionContextMemoryDao().upsert(row)
                fork(db, s, ids.last(), "bad$i")
                assertNull(memory(db, s, "bad$i"))
            }
        } finally { db.close() }
    }

    @Test fun olderCompatiblePrefixCanBeInheritedWithLaterRawMessages() = runBlocking {
        val db = database()
        try {
            val (s, ids) = seed(db, 2)
            val parent = memory(db, s)!!
            val later = db.messageDao().insert(MessageEntity(sessionId = s, content = "尚未整理的近期原文"))
            fork(db, s, later)
            assertEquals(parent.globalSummary, memory(db, s, "child")!!.globalSummary)
            assertEquals(ids + later, db.messageDao().getNextStoryContextBatch(s, "child", 0, 40).map { it.id })
            assertEquals(parent, memory(db, s))
        } finally { db.close() }
    }

    @Test fun editCoveredSourceRejectsButEditAfterCoverageKeepsIndependentPrefix() = runBlocking {
        val db = database()
        try {
            val (s, ids) = seed(db, 2)
            val parent = memory(db, s)!!
            val source = db.messageDao().getById(ids.last())!!
            db.sessionBranchDao().insertEditedBranch(
                SessionBranchEntity(sessionId = s, branchId = "covered", sourceMessageId = source.id),
                source.copy(id = 0, branchId = "covered", regeneratedFromMessageId = source.id, content = "已改剧情"), emptyList())
            assertNull(memory(db, s, "covered"))
            val laterId = db.messageDao().insert(MessageEntity(sessionId = s, content = "新原文"))
            val later = db.messageDao().getById(laterId)!!
            db.sessionBranchDao().insertEditedBranch(
                SessionBranchEntity(sessionId = s, branchId = "after", sourceMessageId = laterId),
                later.copy(id = 0, branchId = "after", regeneratedFromMessageId = laterId, content = "新编辑"), emptyList())
            assertNotNull(memory(db, s, "after"))
            assertEquals(parent, memory(db, s))
        } finally { db.close() }
    }

    @Test fun childExclusionOrSelectionDifferenceRejectsSnapshot() = runBlocking {
        val db = database()
        try {
            val (s, ids) = seed(db, 2)
            db.messageDao().insertContextExclusion(BranchContextExclusionEntity(s, "excluded", "m${ids.first()}"))
            fork(db, s, ids.last(), "excluded")
            assertNull(memory(db, s, "excluded"))
            val source = db.messageDao().getById(ids.last())!!
            db.messageDao().updateSwipeGroupId(source.id, "group")
            val variant = db.messageDao().insert(source.copy(id = 0, swipeGroupId = "group", includeInContext = false, content = "另一版本"))
            db.messageDao().upsertBranchSwipeSelectionRaw(BranchSwipeSelectionEntity(s, "selected", "group", variant))
            fork(db, s, variant, "selected")
            assertNull(memory(db, s, "selected"))
        } finally { db.close() }
    }

    @Test fun parentExclusionCopiesOnlyCompatibleEffectiveSources() = runBlocking {
        val db = database()
        try {
            val (s, ids) = seed(db, 3)
            assertTrue(db.messageDao().setContextExcluded(s, "main", ids.first(), true))
            val parent = memory(db, s)!!.copy(isValid = true, sourceStartMessageId = ids[1])
            db.sessionContextMemoryDao().upsert(parent)
            fork(db, s, ids.last())
            assertNotNull(memory(db, s, "child"))
            assertEquals(listOf(ids[1], ids[2]), db.messageDao().getNextStoryContextBatch(s, "child", 0, 40).map { it.id })
            assertEquals(parent, memory(db, s))
        } finally { db.close() }
    }

    @Test fun existingClearedChildIsNeverResurrectedAndMultiLevelCloneRemainsLocal() = runBlocking {
        val db = database()
        try {
            val (s, ids) = seed(db, 3)
            db.sessionContextMemoryDao().clearAndAdvanceRevision(s, "cleared", 10)
            val cleared = memory(db, s, "cleared")
            fork(db, s, ids.last(), "cleared")
            assertEquals(cleared, memory(db, s, "cleared"))
            fork(db, s, ids.last(), "B")
            val b = memory(db, s, "B")!!
            fork(db, s, ids.last(), "C", "B")
            assertNotNull(memory(db, s, "C"))
            assertTrue(db.messageDao().setContextExcluded(s, "C", ids.first(), true))
            assertFalse(memory(db, s, "C")!!.isValid)
            assertEquals(b, memory(db, s, "B"))
        } finally { db.close() }
    }

    @Test fun inheritedSourceEditInvalidatesParentAndChildWithRevisionAdvance() = runBlocking {
        val db = database()
        try {
            val (s, ids) = seed(db, 3)
            fork(db, s, ids.last())
            db.messageDao().updateContent(ids.first(), "纠正原文")
            assertFalse(memory(db, s)!!.isValid)
            assertEquals(18, memory(db, s)!!.revision)
            assertFalse(memory(db, s, "child")!!.isValid)
            assertEquals(1, memory(db, s, "child")!!.revision)
        } finally { db.close() }
    }

    @Test fun failedReplacementRollsBackBranchAndNeverLeavesInheritedMemory() = runBlocking {
        val db = database()
        try {
            val (s, ids) = seed(db, 2)
            val parent = memory(db, s)!!
            val source = db.messageDao().getById(ids.last())!!
            try {
                db.sessionBranchDao().insertEditedBranch(
                    SessionBranchEntity(sessionId = s, branchId = "broken", sourceMessageId = source.id),
                    source.copy(id = 0, sessionId = s + 9999, branchId = "broken", regeneratedFromMessageId = source.id), emptyList())
                fail("expected foreign key failure")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertNull(db.sessionBranchDao().getByBranch(s, "broken"))
            assertNull(memory(db, s, "broken"))
            assertEquals(parent, memory(db, s))
        } finally { db.close() }
    }
}

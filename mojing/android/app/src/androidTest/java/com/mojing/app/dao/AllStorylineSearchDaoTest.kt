package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.data.local.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllStorylineSearchDaoTest {
    @Test fun searchNeighboursPageOnlyAdoptedRepliesAndKeepExcludedOriginals() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val session = db.sessionDao().insert(SessionEntity(title = "搜索上下文"))
            val dao = db.messageDao()
            val before = dao.insert(MessageEntity(sessionId = session, content = "排除的前文", includeInContext = false))
            val old = dao.insert(MessageEntity(sessionId = session, swipeGroupId = "reply", content = "旧回复"))
            val selected = dao.insert(MessageEntity(sessionId = session, swipeGroupId = "reply", content = "采用回复", includeInContext = false))
            val later = dao.insert(MessageEntity(sessionId = session, swipeGroupId = "reply", content = "未采用后版本", includeInContext = false))
            val after = dao.insert(MessageEntity(sessionId = session, content = "普通后文"))
            dao.selectSwipeVariantForBranch(session, "main", "reply", selected)
            assertEquals(listOf(selected, before), dao.getMainAdoptedSearchMessagesBefore(session, after, 2).map { it.id })
            assertEquals(listOf(selected, after), dao.getMainAdoptedSearchMessagesAfter(session, before, 2).map { it.id })
            assertEquals(listOf(before), dao.getMainAdoptedSearchMessagesBefore(session, selected, 1).map { it.id })
            assertEquals(listOf(after), dao.getMainAdoptedSearchMessagesAfter(session, selected, 1).map { it.id })
            assertTrue(dao.getMainAdoptedSearchMessagesBefore(session, before, 2).isEmpty())
            assertTrue(dao.getMainAdoptedSearchMessagesAfter(session, after, 2).isEmpty())
            assertEquals(listOf(later, selected, old, before), dao.getMainMessagesBefore(session, after, 4).map { it.id })

            db.sessionBranchDao().insert(SessionBranchEntity(sessionId = session, branchId = "child", sourceMessageId = after))
            // Different branch adoption must not borrow the parent's or sibling's version.
            dao.selectSwipeVariantForBranch(session, "child", "reply", old)
            db.sessionBranchDao().insert(SessionBranchEntity(sessionId = session, branchId = "sibling", sourceMessageId = after))
            val sibling = dao.insert(MessageEntity(sessionId = session, branchId = "sibling", content = "兄弟线不可见"))
            val child = dao.insert(MessageEntity(sessionId = session, branchId = "child", content = "子线排除原文", includeInContext = false))
            assertEquals(listOf(old, before), dao.getVisibleAdoptedSearchMessagesBefore(session, "child", after, 2).map { it.id })
            assertEquals(listOf(old, after), dao.getVisibleAdoptedSearchMessagesAfter(session, "child", before, 2).map { it.id })
            assertEquals(listOf(child), dao.getVisibleAdoptedSearchMessagesAfter(session, "child", after, 2).map { it.id })
            assertEquals(listOf(after, old, before), dao.getVisibleAdoptedSearchMessagesBefore(session, "child", child, 8).map { it.id })
            assertTrue(dao.getVisibleAdoptedSearchMessagesAfter(session, "child", child, 2).isEmpty())
            assertTrue(dao.getVisibleAdoptedSearchMessagesBefore(session, "child", before, 2).isEmpty())
            assertNull(dao.getVisibleAdoptedSearchMessageById(session, "child", sibling))
            assertEquals(selected, dao.getMainAdoptedSearchMessageById(session, selected)?.id)
            assertEquals(old, dao.getById(old)?.id)
            assertEquals(later, dao.getById(later)?.id)
        } finally { db.close() }
    }

    @Test fun searchUsesAdoptedMainVariantAndVisibleBranchVariant() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val session = db.sessionDao().insert(SessionEntity(title = "搜索"))
            val character = db.characterDao().upsert(CharacterEntity(name = "阿沅"))
            val mainOld = db.messageDao().insert(MessageEntity(sessionId = session, characterId = character, speakerType = "character",
                swipeGroupId = "g", content = "旧版本雨夜"))
            val mainNew = db.messageDao().insert(MessageEntity(sessionId = session, characterId = character, speakerType = "character",
                swipeGroupId = "g", includeInContext = false, content = "采用版本月光"))
            db.messageDao().selectSwipeVariantForBranch(session, "main", "g", mainOld)
            assertEquals(mainOld, db.messageDao().getMainAdoptedSearchMessageById(session, mainOld)?.id)
            db.messageDao().selectSwipeVariantForBranch(session, "main", "g", mainNew)
            assertEquals(mainNew, db.messageDao().getMainAdoptedSearchMessageById(session, mainNew)?.id)
            assertNull(db.messageDao().getMainAdoptedSearchMessageById(session, mainOld))
            val branch = "side"
            db.sessionBranchDao().insert(SessionBranchEntity(sessionId = session, branchId = branch, sourceMessageId = mainOld))
            val branchVariant = db.messageDao().insert(MessageEntity(sessionId = session, branchId = branch, characterId = character,
                speakerType = "character", regeneratedFromMessageId = mainOld, swipeGroupId = "g", includeInContext = false, content = "子线中文雨声"))
            db.messageDao().selectSwipeVariantForBranch(session, branch, "g", branchVariant)
            assertEquals(branchVariant, db.messageDao().getVisibleAdoptedSearchMessageById(session, branch, branchVariant)?.id)
            val branchNew = db.messageDao().insert(MessageEntity(sessionId = session, branchId = branch, characterId = character,
                speakerType = "character", swipeGroupId = "g", includeInContext = false, content = "子线另一个版本"))
            db.messageDao().selectSwipeVariantForBranch(session, branch, "g", branchNew)
            assertNull(db.messageDao().getVisibleAdoptedSearchMessageById(session, branch, branchVariant))
            assertEquals(branchNew, db.messageDao().getVisibleAdoptedSearchMessageById(session, branch, branchNew)?.id)
            db.messageDao().selectSwipeVariantForBranch(session, branch, "g", branchVariant)

            val mainOldHits = db.messageDao().searchMainMessages(session, "旧版本", 0, 40)
            val mainNewHits = db.messageDao().searchMainMessages(session, "月光", 0, 40)
            val branchHits = db.messageDao().searchVisibleMessages(session, branch, "中文", 0, 40)
            assertTrue(mainOldHits.isEmpty())
            assertEquals(listOf(mainNew), mainNewHits.map { it.id })
            assertEquals(listOf(branchVariant), branchHits.map { it.id })
            assertEquals(listOf(branchVariant), db.messageDao().searchVisibleMessages(session, branch, "阿沅", 0, 40).map { it.id })

            db.messageDao().updateContent(mainNew, "改写后的正文")
            assertTrue(db.messageDao().searchMainMessages(session, "月光", 0, 40).isEmpty())
            assertEquals(listOf(mainNew), db.messageDao().searchMainMessages(session, "改写", 0, 40).map { it.id })

            // Editing replacements cannot be recalled: preserve that product invariant.
            assertTrue(runCatching { db.messageDao().delete(branchVariant) }.exceptionOrNull() is com.mojing.app.data.local.dao.MessageRecallBlockedException)
            val removable = db.messageDao().insert(MessageEntity(sessionId = session, branchId = branch, content = "可撤回的渡船", includeInContext = false))
            assertEquals(listOf(removable), db.messageDao().searchVisibleMessages(session, branch, "渡船", 0, 40).map { it.id })
            assertEquals(removable, db.messageDao().getVisibleAdoptedSearchMessageById(session, branch, removable)?.id)
            db.messageDao().delete(removable)
            assertTrue(db.messageDao().searchVisibleMessages(session, branch, "渡船", 0, 40).isEmpty())
            val excluded = db.messageDao().insert(MessageEntity(sessionId = session, content = "排除上下文仍需显示", includeInContext = false))
            assertEquals(listOf(excluded), db.messageDao().searchMainMessages(session, "排除上下文", 0, 40).map { it.id })
            assertEquals(excluded, db.messageDao().getMainAdoptedSearchMessageById(session, excluded)?.id)
            assertFalse(db.messageDao().searchMainMessages(session, "改写", 0, 40).isEmpty())
        } finally { db.close() }
    }
}

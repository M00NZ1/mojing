package com.mojing.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import java.io.File

class AvatarImageModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun classifiesImmediateAndRetiredValuesWithoutFileAccess() {
        assertEquals(AvatarImagePath.Immediate("https://example.test/a.png"), classifyAvatarImagePath(" https://example.test/a.png "))
        assertEquals(AvatarImagePath.Immediate("content://media/1"), classifyAvatarImagePath("content://media/1"))
        assertEquals(AvatarImagePath.Retired, classifyAvatarImagePath("android.resource://com.mojing.app/drawable/creation_story_art"))
        assertFalse(hasUserImage("android.resource://com.mojing.app/drawable/creation_story_art"))
    }

    @Test
    fun missingLocalPathRemainsAUserImageAndUsesLocalClassification() {
        val path = File(temporaryFolder.newFolder("missing"), "avatar.png").path
        assertTrue(classifyAvatarImagePath(path) is AvatarImagePath.Local)
        assertTrue(hasUserImage(path))
    }

    @Test
    fun missingRevisionUsesZeroAndDoesNotReadLastModified() = runBlocking {
        val file = mockk<File>()
        var statThreadId = -1L
        every { file.exists() } answers {
            statThreadId = Thread.currentThread().id
            false
        }

        assertEquals(0L, readAvatarImageFileRevision(file))
        assertNotEquals(Thread.currentThread().id, statThreadId)
        verify(exactly = 1) { file.exists() }
        verify(exactly = 0) { file.lastModified() }
    }

    @Test
    fun existingFileMtimeChangesProduceDifferentCacheKeys() = runBlocking {
        val file = temporaryFolder.newFile("avatar.png")
        assertTrue(file.setLastModified(1_000L))
        val firstRevision = readAvatarImageFileRevision(file)
        val first = avatarImageCacheKey(file, firstRevision)

        assertTrue(file.setLastModified(2_000L))
        val secondRevision = readAvatarImageFileRevision(file)
        val second = avatarImageCacheKey(file, secondRevision)

        assertEquals(1_000L, firstRevision)
        assertEquals(2_000L, secondRevision)
        assertTrue(first != second)
    }
}

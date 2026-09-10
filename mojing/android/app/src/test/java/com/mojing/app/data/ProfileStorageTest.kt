package com.mojing.app.data

import android.content.SharedPreferences
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class ProfileStorageTest {
    @Test fun profileUsesOneCommitAndReportsWriteFailure() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.commit() } returns false andThen true
        val storage = SecureStorage()
        SecureStorage::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(storage, prefs)
        assertThrows(IllegalStateException::class.java) { storage.saveUserProfile("名字", "设定", "#123456") }
        storage.saveUserProfile("名字", "设定", "#123456")
        verify(exactly = 2) { prefs.edit(); editor.commit() }
        verify(exactly = 2) { editor.putString("user_name", "名字"); editor.putString("user_description", "设定"); editor.putString("user_avatar_color", "#123456") }
        verify(exactly = 0) { editor.putString("user_avatar_image_path", any()); editor.apply() }
    }
}

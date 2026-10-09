package com.mojing.app.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionCharacterStateEntity
import com.mojing.app.data.local.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterStateDaoIsolationTest {
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun teardown() = db.close()

    @Test
    fun deleteOnlyRemovesRequestedSessionCharacterAndBranch() = runBlocking {
        val sessions = db.sessionDao()
        val characters = db.characterDao()
        val states = db.characterStateDao()
        val sessionA = sessions.insert(SessionEntity(title = "A"))
        val sessionB = sessions.insert(SessionEntity(title = "B"))
        val characterA = characters.upsert(CharacterEntity(name = "甲"))
        val characterB = characters.upsert(CharacterEntity(name = "乙"))
        states.upsert(SessionCharacterStateEntity(sessionId = sessionA, characterId = characterA, branchId = "main", dynamicStateJson = "a-main"))
        states.upsert(SessionCharacterStateEntity(sessionId = sessionA, characterId = characterA, branchId = "branch-a", dynamicStateJson = "a-branch"))
        states.upsert(SessionCharacterStateEntity(sessionId = sessionA, characterId = characterB, branchId = "main", dynamicStateJson = "b-main"))
        states.upsert(SessionCharacterStateEntity(sessionId = sessionB, characterId = characterA, branchId = "main", dynamicStateJson = "other-session"))

        assertEquals(1, states.deleteBySessionCharacterBranch(sessionA, characterA, "main"))
        assertNull(states.getBySessionAndCharacter(sessionA, characterA, "main"))
        assertNotNull(states.getBySessionAndCharacter(sessionA, characterA, "branch-a"))
        assertNotNull(states.getBySessionAndCharacter(sessionA, characterB, "main"))
        assertNotNull(states.getBySessionAndCharacter(sessionB, characterA, "main"))
    }
}

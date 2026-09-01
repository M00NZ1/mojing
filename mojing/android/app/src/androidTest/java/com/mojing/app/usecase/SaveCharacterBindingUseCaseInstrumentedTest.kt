package com.mojing.app.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.CharacterExpressionEntity
import com.mojing.app.data.local.entity.CharacterProfileEntity
import com.mojing.app.data.local.entity.SessionCharacterStateEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.domain.encyclopedia.CharacterEncyclopediaSync
import com.mojing.app.domain.usecase.SaveCharacterBindingUseCase
import com.mojing.app.domain.usecase.DeleteCharacterUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SaveCharacterBindingUseCaseInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var saveCharacterBinding: SaveCharacterBindingUseCase
    private var firstEncyclopediaId: Long = 0
    private var secondEncyclopediaId: Long = 0

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        saveCharacterBinding = SaveCharacterBindingUseCase(database)
        firstEncyclopediaId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "第一百科"))
        secondEncyclopediaId = database.encyclopediaDao().upsert(EncyclopediaEntity(name = "第二百科"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createMoveUnbindAndRepeatedSaveKeepOneCorrectMirror() = runBlocking {
        val characterId = saveCharacterBinding(
            CharacterEntity(
                name = "林岚",
                personaPrompt = "冷静的调查员",
                boundEncyclopediaId = firstEncyclopediaId,
            ),
        )
        assertMirror(firstEncyclopediaId, characterId, "林岚")

        val created = database.characterDao().getById(characterId)!!
        saveCharacterBinding(
            created.copy(
                name = "林岚·改",
                personaPrompt = "更谨慎的调查员",
                boundEncyclopediaId = secondEncyclopediaId,
            ),
        )
        assertEquals(0, mirrors(firstEncyclopediaId, characterId).size)
        assertMirror(secondEncyclopediaId, characterId, "林岚·改")

        saveCharacterBinding(database.characterDao().getById(characterId)!!)
        assertMirror(secondEncyclopediaId, characterId, "林岚·改")

        saveCharacterBinding(
            database.characterDao().getById(characterId)!!.copy(boundEncyclopediaId = 0),
        )
        assertEquals(0, mirrors(secondEncyclopediaId, characterId).size)
        assertEquals(0L, database.characterDao().getById(characterId)?.boundEncyclopediaId)
    }

    @Test
    fun deleteCharacterRemovesMirrorAndCharacterTogether() = runBlocking {
        val characterId = saveCharacterBinding(
            CharacterEntity(name = "待删除", boundEncyclopediaId = firstEncyclopediaId),
        )

        DeleteCharacterUseCase(database)(characterId)

        assertNull(database.characterDao().getById(characterId))
        assertEquals(0, mirrors(firstEncyclopediaId, characterId).size)
    }

    @Test
    fun updatingCharacterPreservesProfileExpressionParticipantAndSessionState() = runBlocking {
        val characterId = saveCharacterBinding(
            CharacterEntity(name = "林岚", boundEncyclopediaId = firstEncyclopediaId),
        )
        val sessionId = database.sessionDao().insert(SessionEntity(title = "测试会话"))
        database.characterProfileDao().upsert(
            CharacterProfileEntity(characterId = characterId, rawPersonaText = "原档案"),
        )
        database.expressionDao().upsert(
            CharacterExpressionEntity(characterId = characterId, expression = "smile"),
        )
        database.participantDao().upsert(
            SessionParticipantEntity(sessionId = sessionId, characterId = characterId),
        )
        database.characterStateDao().upsert(
            SessionCharacterStateEntity(sessionId = sessionId, characterId = characterId),
        )

        saveCharacterBinding(
            database.characterDao().getById(characterId)!!.copy(name = "林岚·改"),
        )

        assertEquals("原档案", database.characterProfileDao().getByCharacter(characterId)?.rawPersonaText)
        assertEquals(1, database.expressionDao().getByCharacter(characterId).size)
        assertEquals(1, database.participantDao().getBySession(sessionId).size)
        assertEquals(
            characterId,
            database.characterStateDao().getBySessionAndCharacter(sessionId, characterId)?.characterId,
        )
    }

    private suspend fun assertMirror(encyclopediaId: Long, characterId: Long, title: String) {
        val mirrors = mirrors(encyclopediaId, characterId)
        assertEquals(1, mirrors.size)
        assertEquals(title, mirrors.single().title)
    }

    private suspend fun mirrors(encyclopediaId: Long, characterId: Long) =
        database.encyclopediaEntryDao().getByType(encyclopediaId, "character")
            .filter { CharacterEncyclopediaSync.readLinkedCharacterId(it.metaJson) == characterId }
}

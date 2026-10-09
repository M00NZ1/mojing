package com.mojing.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorldEditDraftStoreInstrumentedTest {
    private lateinit var store: WorldEditDraftStore
    private val id = 987654L

    @Before fun setUp() = runBlocking {
        store = WorldEditDraftStore(ApplicationProvider.getApplicationContext())
        store.clear(id)
    }

    @After fun tearDown() = runBlocking { store.clear(id) }

    @Test fun commitSaveLoadAndClearRoundTrip() = runBlocking {
        val draft = WorldEditDraft("世界", "简介", "提示词", "自由剧情", "规则")
        store.save(id, draft)
        assertEquals(draft, store.load(id))
        store.clear(id)
        assertEquals(null, store.load(id))
    }
}

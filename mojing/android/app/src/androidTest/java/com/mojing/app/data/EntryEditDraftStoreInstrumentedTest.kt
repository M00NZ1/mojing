package com.mojing.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EntryEditDraftStoreInstrumentedTest {
    private val isolatedName = "entry_hint_test_" + java.util.UUID.randomUUID().toString()
    private val context = object : android.content.ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
            super.getSharedPreferences(isolatedName, mode)
    }
    private val preferences by lazy { context.getSharedPreferences("entry_edit_drafts_v1", Context.MODE_PRIVATE) }

    @Before
    fun setUp() {
        preferences.edit().clear().commit()
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun oldV1DraftWithoutCoverPromptHintDecodesAsEmpty() = runBlocking {
        preferences.edit().putString(
            "encyclopedia_3_entry_0",
            """{"version":1,"snapshot":{"title":"旧草稿","entryType":"character","summary":"","content":"正文","tags":"","confidence":"confirmed","metaJson":"{}","isFeatured":false,"coverImagePath":""}}""",
        ).commit()

        val draft = EntryEditDraftStore(context).load(3L, 0L)

        assertNotNull(draft)
        assertEquals("旧草稿", draft?.title)
        assertEquals("", draft?.coverPromptHint)
    }
}

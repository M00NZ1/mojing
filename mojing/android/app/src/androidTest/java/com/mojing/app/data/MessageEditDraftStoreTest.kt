package com.mojing.app.data

import android.content.Context
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MessageEditDraftStoreTest {
    @Test
    fun scopeAndVersionArePersistedInAtomicJson() = runBlocking {
        val fixture = Fixture()
        val scope = MessageEditDraftScope(7L, "branch/one", 11L)
        val draft = MessageEditDraft(scope, "fingerprint", "改后", "原文", 4L)
        fixture.store.save(draft)
        val root = JsonParser.parseString(fixture.store.fileFor(scope).readText()).asJsonObject
        assertEquals(1, root.get("version").asInt)
        assertEquals("branch/one", root.get("branchId").asString)
        assertEquals(draft, fixture.store.load(scope))
    }

    @Test
    fun clearOnlyRemovesTheExpectedRevision() = runBlocking {
        val fixture = Fixture()
        val scope = MessageEditDraftScope(1L, "main", 2L)
        fixture.store.save(MessageEditDraft(scope, "f", "编辑", "原文", 2L))
        assertFalse(fixture.store.clear(scope, 1L))
        assertTrue(fixture.store.fileFor(scope).isFile)
        assertTrue(fixture.store.clear(scope, 2L))
        assertFalse(fixture.store.fileFor(scope).exists())
    }

    @Test
    fun staleSaveCannotOverwriteNewerRevision() = runBlocking {
        val fixture = Fixture()
        val scope = MessageEditDraftScope(1L, "main", 4L)
        val newer = MessageEditDraft(scope, "f", "新版本", "原文", 9L)
        val older = MessageEditDraft(scope, "f", "旧版本", "原文", 8L)
        fixture.store.save(newer)
        assertEquals(newer, fixture.store.save(older))
        assertEquals(newer, fixture.store.load(scope))
    }

    @Test
    fun concurrentClearAndSaveCannotDeleteOrReplaceNewerRevision() = runBlocking {
        val fixture = Fixture()
        val scope = MessageEditDraftScope(1L, "main", 5L)
        val first = MessageEditDraft(scope, "f", "第一版", "原文", 1L)
        val second = MessageEditDraft(scope, "f", "第二版", "原文", 2L)
        fixture.store.save(first)
        coroutineScope {
            val clear = async { fixture.store.clear(scope, 1L) }
            val save = async { fixture.store.save(second) }
            clear.await()
            save.await()
        }
        assertEquals(second, fixture.store.load(scope))
    }

    @Test
    fun malformedDraftIsKeptForForensics() = runBlocking {
        val fixture = Fixture()
        val scope = MessageEditDraftScope(1L, "main", 3L)
        val file = fixture.store.fileFor(scope)
        file.parentFile!!.mkdirs()
        file.writeText("{broken")
        val error = runCatching { fixture.store.load(scope) }.exceptionOrNull()
        assertTrue(error is UnreadableMessageEditDraft)
        assertTrue(file.isFile)
    }

    private class Fixture {
        private val root = Files.createTempDirectory("message-edit-drafts").toFile()
        val store: MessageEditDraftStore

        init {
            val context = object : android.content.ContextWrapper(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext) {
                override fun getFilesDir() = root
            }
            store = MessageEditDraftStore(context)
        }
    }
}

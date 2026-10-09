package com.mojing.app.domain.usecase

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.AutoImageMetadata
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.domain.chat.MainBranchMediaBundleCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class MainBranchMediaBundleReadSnapshotInstrumentedTest {
    private lateinit var base: Context
    private lateinit var isolated: IsolatedContext
    private lateinit var db: AppDatabase
    private val files = mutableListOf<File>()
    private var sessionId = 0L

    @Before
    fun setUp() = runBlocking(Dispatchers.IO) {
        base = ApplicationProvider.getApplicationContext()
        isolated = IsolatedContext(base)
        db = Room.databaseBuilder(isolated, AppDatabase::class.java, "snapshot-${UUID.randomUUID()}.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries().build()
        sessionId = db.sessionDao().insert(SessionEntity(title = "快照隔离"))
    }

    @After
    fun tearDown() {
        db.close()
        isolated.root.deleteRecursively()
    }

    @Test
    fun exportSnapshotFreezes256PageAndDoesNotBlockConcurrentWrite() = runBlocking(Dispatchers.IO) {
        val first = db.messageDao().insert(MessageEntity(sessionId = sessionId, content = "原始第一页"))
        val image = validPng("snapshot")
        db.messageDao().insertMessageAttachmentRaw(MessageAttachmentEntity(
            messageId = first, assetType = "image", fileName = "snapshot.png", mimeType = "image/png", storagePath = image.absolutePath,
        ))
        repeat(299) { index -> db.messageDao().insert(MessageEntity(sessionId = sessionId, content = "记录-$index")) }
        var changed = false
        var lateId = 0L
        val output = ByteArrayOutputStream()
        useCase().exportBundle(output, sessionId, {}, { stage, count ->
            if (stage == "正在整理主线记录" && count == 256 && !changed) {
                changed = true
                withTimeout(5_000) {
                    db.messageDao().updateContent(first, "修改后的第一页")
                    lateId = db.messageDao().insert(MessageEntity(sessionId = sessionId, content = "快照之后新增"))
                }
            }
        })
        assertTrue(changed)
        val chat = extractChat(output.toByteArray())
        assertTrue(chat.contains("原始第一页"))
        assertFalse(chat.contains("修改后的第一页"))
        assertFalse(chat.contains("快照之后新增"))
        assertTrue(lateId > 0)
    }

    @Test
    fun finalOwnerGuardFailureRollsBackMediaBundleRowsAndAttachments() = runBlocking(Dispatchers.IO) {
        val batch = "final-guard"
        var guardCalls = 0
        val failure = runCatching {
            db.messageDao().insertMediaBundleIfAbsent(sessionId, "main", batch, 2, null,
                beforeCommit = {
                    guardCalls++
                    if (guardCalls == 2) error("owner changed after media bundle rows")
                },
                writeRows = { insertMessage, insertAttachments ->
                    val first = insertMessage(MessageEntity(sessionId = sessionId, branchId = "main", content = "", structuredContentJson = marker(batch)))
                    val second = insertMessage(MessageEntity(sessionId = sessionId, branchId = "main", content = "", structuredContentJson = marker(batch)))
                    insertAttachments(listOf(MessageAttachmentEntity(messageId = first, fileName = "a.png", mimeType = "image/png", storagePath = "a")))
                    insertAttachments(listOf(MessageAttachmentEntity(messageId = second, fileName = "b.png", mimeType = "image/png", storagePath = "b")))
                    2
                })
        }
        assertTrue(failure.isFailure)
        assertEquals(2, guardCalls)
        assertEquals(0, db.messageDao().countImportBatch(sessionId, "main", "\"mojing_media_bundle_batch\":\"$batch\""))
        assertEquals(0, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM message_attachments").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun textFirstRejectsMediaAndImportedDerivedRowsRecallWithAttachments() = runBlocking(Dispatchers.IO) {
        val character = db.characterDao().upsert(CharacterEntity(name = "快照角色", personaPrompt = "验收"))
        db.participantDao().upsert(SessionParticipantEntity(sessionId = sessionId, characterId = character))
        val parent = db.messageDao().insert(MessageEntity(sessionId = sessionId, content = "父消息"))
        val child = db.messageDao().insert(MessageEntity(
            sessionId = sessionId, speakerType = "character", characterId = character, parentMessageId = parent,
            content = "", includeInContext = false, structuredContentJson = AutoImageMetadata.create(
                "快照配图", AutoImageMetadata.STATE_COMPLETE, "snapshot-token",
            ),
        ))
        val image = validPng("recall")
        db.messageDao().insertMessageAttachmentRaw(MessageAttachmentEntity(messageId = child, assetType = "image", fileName = "recall.png", mimeType = "image/png", storagePath = image.absolutePath))
        val output = ByteArrayOutputStream()
        useCase().exportBundle(output, sessionId, {}, { _, _ -> })
        val bundle = File(isolated.cacheDir, "recall.zip").apply { writeBytes(output.toByteArray()) }
        val target = db.sessionDao().insert(SessionEntity(title = "导入目标"))
        db.participantDao().upsert(SessionParticipantEntity(sessionId = target, characterId = character))
        val bundleDir = File(isolated.cacheDir, "validated")
        val opened = MainBranchMediaBundleCodec.open(bundle, bundleDir)
        val chatText = opened.chatJson.readText()
        val fingerprint = com.mojing.app.domain.chat.TavernChatImportParser.Fingerprint()
        com.mojing.app.domain.chat.TavernChatImportParser.forEachRow(StringReader(chatText)) { fingerprint.add(it) }
        val textBatch = fingerprint.batchId()
        db.messageDao().insertImportStreamIfAbsent(target, "main", textBatch, fingerprint.count, character, StringReader(chatText)) { }
        val rejection = runCatching {
            useCase().importBundle(target, "main", { bundle.inputStream() }, {}, { _, _ -> })
        }
        assertTrue(rejection.isFailure)
        opened.close()
        // A separate empty target proves the media rows carry derived metadata and recall cascade.
        val recallTarget = db.sessionDao().insert(SessionEntity(title = "撤回目标"))
        db.participantDao().upsert(SessionParticipantEntity(sessionId = recallTarget, characterId = character))
        val imported = useCase().importBundle(recallTarget, "main", { bundle.inputStream() }, {}, { _, _ -> })
        assertEquals(2, imported.messageCount)
        val importedRows = db.messageDao().getMainBranchMessages(recallTarget)
        val importedParent = importedRows.first { it.content == "父消息" }
        val impact = db.messageDao().recallInSession(recallTarget, importedParent.id)
        assertTrue(impact.deleted)
        assertEquals(0, db.messageDao().getMainBranchMessages(recallTarget).size)
    }

    @Test
    fun mediaFirstThenSameTextFingerprintReturnsDuplicateWithoutNewRows() = runBlocking(Dispatchers.IO) {
        val character = db.characterDao().upsert(CharacterEntity(name = "媒体先行角色", personaPrompt = "验收"))
        db.participantDao().upsert(SessionParticipantEntity(sessionId = sessionId, characterId = character))
        val parent = db.messageDao().insert(MessageEntity(sessionId = sessionId, content = "媒体先行父消息"))
        val child = db.messageDao().insert(MessageEntity(
            sessionId = sessionId, speakerType = "character", characterId = character, parentMessageId = parent,
            content = "", includeInContext = false,
            structuredContentJson = AutoImageMetadata.create("媒体先行", AutoImageMetadata.STATE_COMPLETE, "media-first"),
        ))
        val image = validPng("media-first")
        db.messageDao().insertMessageAttachmentRaw(MessageAttachmentEntity(messageId = child, assetType = "image", fileName = "media-first.png", mimeType = "image/png", storagePath = image.absolutePath))
        val bytes = ByteArrayOutputStream()
        useCase().exportBundle(bytes, sessionId, {}, { _, _ -> })
        val bundle = File(isolated.cacheDir, "media-first.zip").apply { writeBytes(bytes.toByteArray()) }
        val target = db.sessionDao().insert(SessionEntity(title = "媒体先行目标"))
        db.participantDao().upsert(SessionParticipantEntity(sessionId = target, characterId = character))
        useCase().importBundle(target, "main", { bundle.inputStream() }, {}, { _, _ -> })
        val opened = MainBranchMediaBundleCodec.open(bundle, File(isolated.cacheDir, "media-first-validated"))
        val chatText = opened.chatJson.readText()
        val fingerprint = com.mojing.app.domain.chat.TavernChatImportParser.Fingerprint()
        com.mojing.app.domain.chat.TavernChatImportParser.forEachRow(StringReader(chatText)) { fingerprint.add(it) }
        val before = db.messageDao().getMainBranchMessages(target).size
        val duplicate = db.messageDao().insertImportStreamIfAbsent(target, "main", fingerprint.batchId(), fingerprint.count, character, StringReader(chatText)) { }
        opened.close()
        assertEquals(0, duplicate)
        assertEquals(before, db.messageDao().getMainBranchMessages(target).size)
    }

    private fun marker(batch: String) = "{\"mojing_media_bundle_batch\":\"$batch\"}"

    private fun validPng(name: String): File {
        val file = File(isolated.filesDir, "$name-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(80, 120, 140))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        files += file
        return file
    }

    private fun extractChat(bytes: ByteArray): String {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "chat.json") return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("chat.json missing")
    }

    private fun useCase() = MainBranchMediaBundleUseCase(
        isolated, db.messageDao(), db.attachmentDao(), db.characterDao(), db.participantDao(),
        db.sessionDao(), db.sessionBranchDao(), db,
    )

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        val root = File(base.cacheDir, "media-snapshot-isolated-${UUID.randomUUID()}").apply { mkdirs() }
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(File(root, "databases").apply { mkdirs() }, name)
    }
}

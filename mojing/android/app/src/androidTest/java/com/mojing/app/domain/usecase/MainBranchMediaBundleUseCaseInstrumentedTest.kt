package com.mojing.app.domain.usecase

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.graphics.Bitmap
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MainBranchMediaBundleUseCaseInstrumentedTest {
    private lateinit var context: android.content.Context
    private lateinit var database: AppDatabase
    private lateinit var sourceSession: SessionEntity
    private lateinit var targetSession: SessionEntity
    private var characterId = 0L
    private val ownedFiles = mutableListOf<File>()
    private lateinit var testRoot: File

    @Before
    fun setUp() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<android.content.Context>()
        testRoot = File(base.cacheDir, "media-bundle-isolated-${UUID.randomUUID()}").apply { check(mkdirs()) }
        context = object : android.content.ContextWrapper(base) {
            override fun getFilesDir() = File(testRoot, "files").apply { mkdirs() }
            override fun getCacheDir() = File(testRoot, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(testRoot, "databases/$name").apply { parentFile!!.mkdirs() }
        }
        database = Room.databaseBuilder(context, AppDatabase::class.java, "bundle-test.db")
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).allowMainThreadQueries().build()
        val character = database.characterDao().upsert(CharacterEntity(name = "媒体验收角色-${UUID.randomUUID()}", personaPrompt = "本地验收"))
        characterId = character
        sourceSession = database.sessionDao().let { dao ->
            val id = dao.insert(SessionEntity(title = "媒体包来源"))
            database.participantDao().upsert(SessionParticipantEntity(sessionId = id, characterId = character))
            SessionEntity(id = id, title = "媒体包来源")
        }
        targetSession = database.sessionDao().let { dao ->
            val id = dao.insert(SessionEntity(title = "媒体包目标"))
            database.participantDao().upsert(SessionParticipantEntity(sessionId = id, characterId = character))
            SessionEntity(id = id, title = "媒体包目标")
        }
    }

    @After
    fun tearDown() {
        ownedFiles.forEach { it.delete() }
        database.close()
        if (::testRoot.isInitialized) testRoot.deleteRecursively()
    }

    @Test
    fun exportThenImportPreservesMediaRowsRolesParentsMetadataAndMoreThan256Messages() = runBlocking {
        val sourceIds = seedSourceMessages(300)
        val bytes = exportBytes()
        val imported = useCase().importBundle(
            targetSession.id, "main", { ByteArrayInputStream(bytes) }, {}, { _, _ -> },
        )

        assertEquals(300, imported.messageCount)
        assertEquals(4, imported.mediaCount)
        val rows = database.messageDao().getMainBranchMessages(targetSession.id)
        assertEquals(300, rows.size)
        assertTrue(rows.all { it.structuredContentJson.contains("mojing_media_bundle_batch") })
        assertEquals("", rows.first().content)
        assertEquals(false, rows.first().includeInContext)
        assertEquals("character", rows[1].speakerType)
        assertEquals(characterId, rows[1].characterId)
        assertTrue(rows[1].structuredContentJson.contains("generationPrompt"))
        assertEquals(rows[1].id, rows[2].parentMessageId)
        assertEquals("narrator", rows[3].speakerType)
        assertEquals(4, database.attachmentDao().getByMessages(rows.map { it.id }).size)
        assertTrue(sourceIds.isNotEmpty())
    }

    @Test
    fun duplicateImportIsReportedWithoutWritingAgain() = runBlocking {
        seedSourceMessages(3)
        val bytes = exportBytes()
        val first = useCase().importBundle(targetSession.id, "main", { ByteArrayInputStream(bytes) }, {}, { _, _ -> })
        val second = useCase().importBundle(targetSession.id, "main", { ByteArrayInputStream(bytes) }, {}, { _, _ -> })
        assertEquals(3, first.messageCount)
        assertTrue(!first.duplicate)
        assertTrue(second.duplicate)
        assertEquals(3, database.messageDao().getMainBranchMessages(targetSession.id).size)
    }

    @Test
    fun missingAndDuplicateRoleNamesAreRejectedBeforeWriting() = runBlocking {
        seedSourceMessages(1)
        val bytes = exportBytes()
        database.participantDao().deleteBySession(targetSession.id)
        val missing = runCatching {
            useCase().importBundle(targetSession.id, "main", { ByteArrayInputStream(bytes) }, {}, { _, _ -> })
        }
        assertTrue(missing.isFailure)
        assertEquals(0, database.messageDao().getMainBranchMessages(targetSession.id).size)

        val duplicate = database.characterDao().upsert(CharacterEntity(name = database.characterDao().getById(characterId)!!.name, personaPrompt = "重复"))
        database.participantDao().upsert(SessionParticipantEntity(sessionId = targetSession.id, characterId = characterId))
        database.participantDao().upsert(SessionParticipantEntity(sessionId = targetSession.id, characterId = duplicate))
        val rejected = runCatching {
            useCase().importBundle(targetSession.id, "main", { ByteArrayInputStream(bytes) }, {}, { _, _ -> })
        }
        assertTrue(rejected.isFailure)
        assertEquals(0, database.messageDao().getMainBranchMessages(targetSession.id).size)
    }

    @Test
    fun cancellationDuringSecondMediaCopyLeavesNoRequestFilesOrRows() = runBlocking {
        seedSourceMessages(2)
        val bytes = exportBytes()
        var firstMediaCopied = false
        val failure = runCatching {
            useCase().importBundle(targetSession.id, "main", { ByteArrayInputStream(bytes) }, {
                if (firstMediaCopied) throw kotlinx.coroutines.CancellationException("test cancellation")
            }, { stage, count -> if (stage == "正在准备媒体" && count == 1) firstMediaCopied = true })
        }
        assertTrue(failure.isFailure)
        assertEquals(0, database.messageDao().getMainBranchMessages(targetSession.id).size)
        assertTrue(File(context.filesDir, "attachments/${targetSession.id}").listFiles().orEmpty().isEmpty())
    }

    private suspend fun seedSourceMessages(count: Int): List<Long> = database.withTransaction {
        val ids = ArrayList<Long>(count)
        repeat(count) { index ->
            val id = database.messageDao().insert(MessageEntity(
                sessionId = sourceSession.id,
                speakerType = if (index == 3) "narrator" else "character",
                characterId = if (index == 3) null else characterId,
                parentMessageId = ids.lastOrNull(),
                content = if (index == 0) "" else "记录-$index",
                structuredContentJson = if (index == 1) "{\"derived_media_kind\":\"image\",\"generationPrompt\":\"海边灯塔\"}" else "{}",
                includeInContext = index != 0,
                createdAt = index.toLong() + 1,
            ))
            ids += id
            if (index == 0) attach(id, "manual.png", "image/png", "image")
            if (index == 1) attach(id, "auto.png", "image/png", "image")
            if (index == 2) attach(id, "voice-1.wav", "audio/wav", "voice")
            if (index == 3) attach(id, "voice-2.wav", "audio/wav", "voice")
        }
        ids
    }

    private suspend fun attach(messageId: Long, name: String, mime: String, type: String) {
        val file = File(context.cacheDir, "media-bundle-itest-${UUID.randomUUID()}-$name")
        if (mime.startsWith("image/")) {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(77, 128, 149))
            file.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
            bitmap.recycle()
        } else {
            file.writeBytes(silenceWav())
        }
        ownedFiles += file
        database.messageDao().insertMessageAttachmentRaw(MessageAttachmentEntity(
            messageId = messageId, assetType = type, fileName = name, mimeType = mime,
            storagePath = file.absolutePath, generationPrompt = if (type == "image") "海边灯塔" else "",
            generationModel = if (type == "image") "local" else "",
        ))
    }

    private fun silenceWav(): ByteArray {
        val dataLength = 8_000
        val output = ByteArrayOutputStream(44 + dataLength)
        fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun little(value: Int) { output.write(value and 0xff); output.write((value shr 8) and 0xff); output.write((value shr 16) and 0xff); output.write((value shr 24) and 0xff) }
        fun littleShort(value: Int) { output.write(value and 0xff); output.write((value shr 8) and 0xff) }
        ascii("RIFF"); little(36 + dataLength); ascii("WAVEfmt "); little(16); littleShort(1); littleShort(1)
        little(16_000); little(32_000); littleShort(2); littleShort(16); ascii("data"); little(dataLength)
        repeat(dataLength) { output.write(0) }
        return output.toByteArray()
    }

    private suspend fun exportBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        useCase().exportBundle(output, sourceSession.id, {}, { _, _ -> })
        return output.toByteArray()
    }

    private fun useCase() = MainBranchMediaBundleUseCase(
        context,
        database.messageDao(), database.attachmentDao(), database.characterDao(),
        database.participantDao(), database.sessionDao(), database.sessionBranchDao(), database = database,
    )
}

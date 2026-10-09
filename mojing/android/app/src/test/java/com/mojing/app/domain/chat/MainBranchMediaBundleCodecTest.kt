package com.mojing.app.domain.chat

import kotlinx.coroutines.runBlocking
import com.mojing.app.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.io.IOException
import com.google.gson.JsonParser

class MainBranchMediaBundleCodecTest {
    @Test
    fun escapedManifestIsBoundedWhileWritingAndLeavesCallerStreamOpen() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("mojing-manifest-budget").toFile()
        try {
            val chat = dir.resolve("chat.json").apply { writeText("{}") }
            val index = dir.resolve("index.jsonl").apply { writeText("") }
            var closed = false
            val output = object : ByteArrayOutputStream() {
                override fun close() { closed = true; super.close() }
            }
            val source = MainBranchMediaBundleCodec.ExportSource(
                MainBranchMediaBundleCodec.ExportManifest(7, 1, listOf(
                    MainBranchMediaBundleCodec.SourceCharacter(3, "角色" + "\u0001".repeat(4000))
                )), chat, index
            ) { error("No media should be read") }
            var rejected = false
            try {
                MainBranchMediaBundleCodec.write(source, output,
                    MainBranchMediaBundleCodec.Limits(maxJsonElementBytes = 8192))
            } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertTrue(!closed)
            val zipFile = dir.resolve("partial.zip").apply { writeBytes(output.toByteArray()) }
            ZipFile(zipFile).use { archive ->
                assertEquals(1, archive.size())
                assertTrue(archive.getEntry("manifest.json").size <= 8192)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun chatWriterKeepsParentOptionalAndLegacyShape() = runBlocking<Unit> {
        val out = ByteArrayOutputStream()
        MainBranchChatExportWriter.write(out, 7, 1, 2) { _, _, _ -> listOf(
            MessageEntity(id = 1, sessionId = 7, content = "root"),
            MessageEntity(id = 2, sessionId = 7, parentMessageId = 1, content = "child")
        ) }
        val root = JsonParser.parseString(out.toString(Charsets.UTF_8)).asJsonObject.getAsJsonArray("messages")
        assertTrue(!root[0].asJsonObject.has("parentMessageId"))
        assertEquals(1L, root[1].asJsonObject.get("parentMessageId").asLong)
    }

    @Test
    fun roundTripStreamsMessagesAndMediaFromRequestFiles() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("mojing-bundle").toFile()
        try {
            val chat = dir.resolve("chat.json").apply {
                writeText("""{"format":"mojing_chat_export","version":1,"sessionId":7,"messages":[{"id":1,"sessionId":7,"speakerType":"user","branchId":"main","content":"","structuredContentJson":"{}","includeInContext":true,"createdAt":10},{"id":2,"sessionId":7,"speakerType":"character","characterId":3,"branchId":"main","parentMessageId":1,"content":"reply","structuredContentJson":"{\"kind\":\"x\"}","includeInContext":false,"createdAt":11}]}""")
            }
            val media = dir.resolve("source.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val index = dir.resolve("index.jsonl").apply {
                writeText("""{"sourceMessageId":1,"ordinal":0,"assetType":"image","fileName":"source.bin","mimeType":"image/png","byteLength":3,"sha256":"039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81","generationPrompt":"","generationModel":"","createdAt":12}
""")
            }
            val output = ByteArrayOutputStream()
            MainBranchMediaBundleCodec.write(
                MainBranchMediaBundleCodec.ExportSource(
                    MainBranchMediaBundleCodec.ExportManifest(7, 99, listOf(MainBranchMediaBundleCodec.SourceCharacter(3, "角色"))),
                    chat, index
                ) { media }, output
            )
            val zip = dir.resolve("bundle.zip").apply { writeBytes(output.toByteArray()) }
            ZipFile(zip).use { z -> assertTrue(z.getEntry("media/0") != null); assertTrue(z.getEntry("media/index.jsonl") != null) }
            val opened = MainBranchMediaBundleCodec.open(zip, dir.resolve("request"))
            val ids = mutableListOf<Long>(); MainBranchMediaBundleCodec.readMessages(opened) { ids += it.id }
            assertEquals(listOf(1L, 2L), ids)
            var count = 0; MainBranchMediaBundleCodec.readMediaForMessage(opened, 1) { count++ }
            assertEquals(1, count)
            opened.close()
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun readsMoreThanOnePageWithoutCollectingMessages() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("mojing-many").toFile()
        try {
            val messages = (1..300).joinToString(",") { "{\"id\":$it,\"sessionId\":7,\"speakerType\":\"user\",\"branchId\":\"main\",\"content\":\"消息 $it\",\"structuredContentJson\":\"{}\",\"includeInContext\":true,\"createdAt\":$it}" }
            val many = dir.resolve("many-chat.json").apply { writeText("{\"format\":\"mojing_chat_export\",\"version\":1,\"sessionId\":7,\"messages\":[$messages]}") }
            val opened = run { val out = ByteArrayOutputStream(); val media = dir.resolve("m.bin").apply { writeBytes(byteArrayOf(1,2,3)) }; val index = dir.resolve("index.jsonl").apply { writeText("") }; MainBranchMediaBundleCodec.write(MainBranchMediaBundleCodec.ExportSource(MainBranchMediaBundleCodec.ExportManifest(7, 1, emptyList()), many, index) { media }, out); val zip = dir.resolve("many.zip").apply { writeBytes(out.toByteArray()) }; MainBranchMediaBundleCodec.open(zip, dir.resolve("request")) }
            var count = 0; MainBranchMediaBundleCodec.readMessages(opened) { count++ }
            assertEquals(300, count); opened.close()
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun fingerprintIgnoresExportTimestampButIncludesMediaMetadata() = runBlocking<Unit> {
        val first = Files.createTempDirectory("mojing-fp1").toFile(); val second = Files.createTempDirectory("mojing-fp2").toFile()
        try {
            val a = fingerprintFixture(first, 10, "prompt-a", 1); val b = fingerprintFixture(second, 99, "prompt-a", 1)
            assertEquals(a, b)
            val third = Files.createTempDirectory("mojing-fp3").toFile()
            try { val c = fingerprintFixture(third, 10, "prompt-b", 1); assertNotEquals(a, c) } finally { third.deleteRecursively() }
            val fourth = Files.createTempDirectory("mojing-fp4").toFile()
            try { val c = fingerprintFixture(fourth, 10, "prompt-a", 2); assertNotEquals(a, c) } finally { fourth.deleteRecursively() }
        } finally { first.deleteRecursively(); second.deleteRecursively() }
    }

    @Test
    fun rejectsHashSizeMissingOwnerParentDuplicateAndExtraEntries() = runBlocking<Unit> {
        reject(rawBundle(mapOf("manifest.json" to manifest(), "chat.json" to chat(""), "media/index.jsonl" to mediaRow(1, 0, sha(byteArrayOf(1)), 1).toByteArray(), "media/0" to byteArrayOf(9))), "bad-hash")
        reject(rawBundle(mapOf("manifest.json" to manifest(), "chat.json" to chat(""), "media/index.jsonl" to mediaRow(1, 0, sha(byteArrayOf(1,2,3))).toByteArray(), "media/0" to byteArrayOf(9))), "bad-size")
        reject(rawBundle(mapOf("manifest.json" to manifest(), "chat.json" to chat(""), "media/index.jsonl" to mediaRow(88, 0, sha(byteArrayOf(1,2,3))).toByteArray(), "media/0" to byteArrayOf(1,2,3))), "bad-owner")
        reject(rawBundle(mapOf("manifest.json" to manifest(), "chat.json" to chat("\"parentMessageId\":2,", "gap").toString(Charsets.UTF_8).replace("\"id\":1", "\"id\":3").toByteArray(), "media/index.jsonl" to "".toByteArray())), "bad-parent")
        reject(rawBundle(mapOf("manifest.json" to manifest(), "chat.json" to chat(""), "media/index.jsonl" to mediaRow(1, 0, sha(byteArrayOf(1,2,3))).toByteArray(), "media/0" to byteArrayOf(1,2,3), "media/1" to byteArrayOf(1))), "extra")
        reject(rawBundle(mapOf("manifest.json" to manifest(), "chat.json" to chat(""), "media/index.jsonl" to (mediaRow(1, 0, sha(byteArrayOf(1,2,3))) + mediaRow(1, 0, sha(byteArrayOf(1,2,3)))).toByteArray(), "media/0" to byteArrayOf(1,2,3))), "dup-ordinal")
    }

    @Test
    fun rejectsTraversalAndOversizedJsonElement() = runBlocking<Unit> {
        reject(rawBundle(mapOf("manifest.json" to manifest(), "chat.json" to chat(""), "media/index.jsonl" to mediaRow(1, 0, sha(byteArrayOf(1,2,3))).replace("file.png", "../file.png").toByteArray(), "media/0" to byteArrayOf(1,2,3))), "path")
        val longContent = "x".repeat(1024)
        val zip = rawBundle(mapOf("manifest.json" to manifest(), "chat.json" to chat("", longContent), "media/index.jsonl" to "".toByteArray()))
        reject(zip, "size", MainBranchMediaBundleCodec.Limits(maxJsonElementBytes = 256))
    }

    @Test
    fun validatesLongParentChainAndControlCharacterRoleName() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("mojing-chain").toFile()
        try {
            val messages = (1..10_000).joinToString(",") { id -> "{\"id\":$id,\"sessionId\":7,\"speakerType\":\"user\",\"branchId\":\"main\",\"parentMessageId\":${if (id == 1) "null" else id - 1},\"content\":\"x\",\"structuredContentJson\":\"{}\",\"includeInContext\":true,\"createdAt\":$id}" }
            val chat = dir.resolve("chat.json").apply { writeText("{\"format\":\"mojing_chat_export\",\"version\":1,\"sessionId\":7,\"messages\":[$messages]}") }
            val index = dir.resolve("index.jsonl").apply { writeText("") }
            val out = ByteArrayOutputStream(); MainBranchMediaBundleCodec.write(MainBranchMediaBundleCodec.ExportSource(MainBranchMediaBundleCodec.ExportManifest(7, 1, listOf(MainBranchMediaBundleCodec.SourceCharacter(3, "角色\n\t\r\u0001二"))), chat, index) { chat }, out)
            val zip = dir.resolve("chain.zip").apply { writeBytes(out.toByteArray()) }; val opened = MainBranchMediaBundleCodec.open(zip, dir.resolve("request")); var count = 0; MainBranchMediaBundleCodec.readMessages(opened) { count++ }; assertEquals(10_000, count); assertEquals("角色\n\t\r\u0001二", opened.manifest.sourceCharacters.single().name); opened.close()
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun rejectsTruncatedZipCentralDirectory() {
        val source = rawBundle(mapOf("manifest.json" to manifest(), "chat.json" to chat(""), "media/index.jsonl" to "".toByteArray()))
        val truncated = Files.createTempFile("mojing-truncated", ".zip").toFile()
        try {
            truncated.writeBytes(source.readBytes().copyOf(source.length().toInt() - 8))
            val root = Files.createTempDirectory("mojing-truncated-root").toFile()
            try { assertThrows(IOException::class.java) { runBlocking<Unit> { MainBranchMediaBundleCodec.open(truncated, root.resolve("request")); Unit } } } finally { root.deleteRecursively() }
        } finally { source.delete(); truncated.delete() }
    }

    @Test
    fun boundsAdvertisedCentralDirectoryBeforeZipFileAllocation() = runBlocking<Unit> {
        val source = rawBundle(mapOf("manifest.json" to manifest(), "chat.json" to chat(), "media/index.jsonl" to byteArrayOf()))
        try {
            val original = source.readBytes()
            val end = original.size - 22
            fun rejectModified(offset: Int, value: Long, width: Int) {
                val changed = original.copyOf()
                repeat(width) { changed[end + offset + it] = (value ushr (8 * it)).toByte() }
                val archive = Files.createTempFile("mojing-central-limit", ".zip").toFile()
                val directory = Files.createTempDirectory("mojing-central-request").toFile()
                try {
                    archive.writeBytes(changed)
                    assertThrows(IllegalArgumentException::class.java) {
                        runBlocking { MainBranchMediaBundleCodec.open(archive, directory.resolve("request")) }
                    }
                    assertTrue(!directory.resolve("request").exists())
                } finally { archive.delete(); directory.deleteRecursively() }
            }
            rejectModified(10, 16_388, 2)
            rejectModified(12, 32L * 1024 * 1024, 4)
        } finally { source.delete() }
    }

    @Test
    fun acceptsZip64EndRecordWithoutRequiringGigabytesOfFixtureData() = runBlocking<Unit> {
        val source = rawBundle(mapOf("manifest.json" to manifest(), "chat.json" to chat(), "media/index.jsonl" to byteArrayOf()))
        val directory = Files.createTempDirectory("mojing-zip64").toFile()
        try {
            val bytes = source.readBytes()
            val endOffset = bytes.size - 22
            fun read32(offset: Int): Long = (0 until 4).fold(0L) { value, index -> value or ((bytes[endOffset + offset + index].toLong() and 255) shl (index * 8)) }
            val record = ByteArray(56)
            val locator = ByteArray(20)
            val end = bytes.copyOfRange(endOffset, bytes.size)
            fun put(target: ByteArray, offset: Int, value: Long, width: Int) { repeat(width) { target[offset + it] = (value ushr (8 * it)).toByte() } }
            put(record, 0, 0x06064b50, 4); put(record, 4, 44, 8)
            put(record, 12, 45, 2); put(record, 14, 45, 2)
            put(record, 24, 3, 8); put(record, 32, 3, 8)
            put(record, 40, read32(12), 8); put(record, 48, read32(16), 8)
            put(locator, 0, 0x07064b50, 4); put(locator, 8, endOffset.toLong(), 8); put(locator, 16, 1, 4)
            put(end, 8, 65_535, 2); put(end, 10, 65_535, 2)
            put(end, 12, 0xffffffffL, 4); put(end, 16, 0xffffffffL, 4)
            val archive = directory.resolve("zip64.zip")
            archive.outputStream().use { it.write(bytes, 0, endOffset); it.write(record); it.write(locator); it.write(end) }
            MainBranchMediaBundleCodec.open(archive, directory.resolve("request")).use { handle ->
                var count = 0
                MainBranchMediaBundleCodec.readMessages(handle) { count++ }
                assertEquals(1, count)
            }
        } finally { source.delete(); directory.deleteRecursively() }
    }

    private fun fingerprintFixture(dir: File, exportedAt: Long, prompt: String, createdAt: Long): String = runBlocking {
        val media = dir.resolve("m.bin").apply { writeBytes(byteArrayOf(1,2,3)) }
        val chat = dir.resolve("chat.json").apply { writeText("{\"format\":\"mojing_chat_export\",\"version\":1,\"sessionId\":7,\"messages\":[{\"id\":1,\"sessionId\":7,\"speakerType\":\"user\",\"branchId\":\"main\",\"content\":\"有图\",\"structuredContentJson\":\"{}\",\"includeInContext\":true,\"createdAt\":$createdAt}]}") }
        val index = dir.resolve("index.jsonl").apply { writeText(mediaRow(1, 0, sha(byteArrayOf(1,2,3))).replace("提示", prompt)) }
        val out = ByteArrayOutputStream(); MainBranchMediaBundleCodec.write(MainBranchMediaBundleCodec.ExportSource(MainBranchMediaBundleCodec.ExportManifest(7, exportedAt, listOf(MainBranchMediaBundleCodec.SourceCharacter(3,"角色"))), chat, index) { media }, out)
        val zip = dir.resolve("x.zip").apply { writeBytes(out.toByteArray()) }; val opened = MainBranchMediaBundleCodec.open(zip, dir.resolve("request")); val fp = MainBranchMediaBundleCodec.fingerprint(opened); opened.close(); fp
    }

    private suspend fun fixture(dir: File, indexText: String, ignored: String, media: ByteArray): File {
        val chat = dir.resolve("chat.json").apply { writeText("{\"format\":\"mojing_chat_export\",\"version\":1,\"sessionId\":7,\"messages\":[{\"id\":1,\"sessionId\":7,\"speakerType\":\"user\",\"branchId\":\"main\",\"content\":\"有图\",\"structuredContentJson\":\"{}\",\"includeInContext\":true,\"createdAt\":1}]}") }
        val index = dir.resolve("index.jsonl").apply { writeText(indexText) }; val file = dir.resolve("m.bin").apply { writeBytes(media) }; val out = ByteArrayOutputStream()
        MainBranchMediaBundleCodec.write(MainBranchMediaBundleCodec.ExportSource(MainBranchMediaBundleCodec.ExportManifest(7, 1, listOf(MainBranchMediaBundleCodec.SourceCharacter(3,"角色"))), chat, index) { file }, out)
        return dir.resolve("bundle.zip").apply { writeBytes(out.toByteArray()) }
    }

    private fun manifest() = "{\"format\":\"mojing_chat_media_bundle\",\"version\":1,\"scope\":\"main_branch\",\"sessionId\":7,\"exportedAt\":1,\"sourceCharacters\":[{\"id\":3,\"name\":\"角色\"}]}".toByteArray()
    private fun chat(extraFields: String = "", content: String = "有图") = "{\"format\":\"mojing_chat_export\",\"version\":1,\"sessionId\":7,\"messages\":[{\"id\":1,\"sessionId\":7,\"speakerType\":\"user\",\"branchId\":\"main\",$extraFields\"content\":\"$content\",\"structuredContentJson\":\"{}\",\"includeInContext\":true,\"createdAt\":1}]}".toByteArray()
    private fun mediaRow(id: Long, ordinal: Long, hash: String, byteLength: Long = 3) = "{\"sourceMessageId\":$id,\"ordinal\":$ordinal,\"assetType\":\"image\",\"fileName\":\"file.png\",\"mimeType\":\"image/png\",\"byteLength\":$byteLength,\"sha256\":\"$hash\",\"generationPrompt\":\"提示\",\"generationModel\":\"模型\",\"createdAt\":1}\n"
    private fun rawBundle(entries: Map<String, ByteArray>): File { val file = Files.createTempFile("mojing-raw", ".zip").toFile(); ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }; return file }
    private fun reject(zip: File, prefix: String, limits: MainBranchMediaBundleCodec.Limits = MainBranchMediaBundleCodec.Limits()) {
        val root = Files.createTempDirectory("mojing-$prefix").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) { runBlocking<Unit> { MainBranchMediaBundleCodec.open(zip, root.resolve("request"), limits); Unit } }
        } finally { root.deleteRecursively(); zip.delete() }
    }
    private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

package com.mojing.app.domain.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.io.Reader
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import java.nio.file.Files

/** Streaming codec for the Android main-branch media bundle. */
object MainBranchMediaBundleCodec {
    const val FORMAT = "mojing_chat_media_bundle"
    const val VERSION = 1
    const val SCOPE = "main_branch"
    const val CHAT_FORMAT = "mojing_chat_export"
    private const val MEDIA_PREFIX = "media/"
    private const val MEDIA_INDEX = "media/index.jsonl"

    data class Limits(
        val maxInputZipBytes: Long = 8L * 1024 * 1024 * 1024,
        val maxExpandedBytes: Long = 8L * 1024 * 1024 * 1024,
        // JDK ZipOutputStream retains its central directory. Cap metadata memory independently
        // of history length (all textual history remains a single streamed chat.json entry).
        val maxEntryCount: Int = 16_387,
        val maxSingleMediaBytes: Long = 512L * 1024 * 1024,
        val maxJsonElementBytes: Long = 16L * 1024 * 1024,
    )

    data class SourceCharacter(val id: Long, val name: String)
    data class ExportManifest(
        val sessionId: Long,
        val exportedAt: Long,
        val sourceCharacters: List<SourceCharacter>,
    )

    data class ExportSource(
        val manifest: ExportManifest,
        val chatJson: File,
        val mediaIndexJsonl: File,
        val mediaFile: suspend (entryName: String) -> File,
    )

    data class MessageRecord(
        val id: Long,
        val sessionId: Long,
        val speakerType: String,
        val characterId: Long?,
        val branchId: String,
        val parentMessageId: Long?,
        val content: String,
        val structuredContentJson: String,
        val includeInContext: Boolean,
        val createdAt: Long,
    )

    data class MediaRecord(
        val sourceMessageId: Long,
        val ordinal: Long,
        val assetType: String,
        val fileName: String,
        val mimeType: String,
        val byteLength: Long,
        val sha256: String,
        val generationPrompt: String,
        val generationModel: String,
        val createdAt: Long,
    ) { val bytes: Long get() = byteLength; val entry: String get() = "media/$ordinal" }

    class BundleHandle internal constructor(
        val root: File,
        val manifest: ExportManifest,
        internal val chatJson: File,
        internal val mediaIndex: File,
        internal val mediaRecords: File,
        private val ownershipMarker: File,
    ) : Closeable {
        internal val recordsReader = RandomAccessFile(mediaRecords, "r")
        internal val indexReader = RandomAccessFile(mediaIndex, "r")
        internal var nextMediaPosition = 0L
        internal var lastMediaStart = 0L
        internal var previousSource = Long.MIN_VALUE
        override fun close() {
            try { recordsReader.close() } finally { indexReader.close() }
            if (!Files.isSymbolicLink(root.toPath()) && ownershipMarker.isFile &&
                ownershipMarker.readText() == "mojing_chat_media_bundle_v1") root.deleteRecursively()
        }
    }

    suspend fun write(source: ExportSource, output: OutputStream, limits: Limits = Limits()): Long {
        require(source.chatJson.isFile && source.mediaIndexJsonl.isFile)
        validateManifest(source.manifest)
        val zip = ZipOutputStream(object : java.io.FilterOutputStream(output) {
            override fun close() { flush() }
        })
        var expanded = 0L
        var entries = 0
        fun begin(name: String) {
            require(name == "manifest.json" || name == "chat.json" || name == MEDIA_INDEX || name.matches(Regex("media/[0-9]+")))
            require(entries < limits.maxEntryCount) { "媒体包最多包含 ${limits.maxEntryCount - 3} 个媒体" }
            zip.putNextEntry(ZipEntry(name)); entries++
        }
        suspend fun copyFile(file: File, max: Long): Long {
            require(file.isFile)
            var count = 0L
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    count += n
                    require(count <= max && expanded + n <= limits.maxExpandedBytes)
                    zip.write(buffer, 0, n); expanded += n
                }
            }
            return count
        }
        try {
        begin("manifest.json")
        val context = currentCoroutineContext()
        var manifestBytes = 0L
        val manifestSink = object : OutputStream() {
            override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                context.ensureActive()
                require(manifestBytes + length <= limits.maxJsonElementBytes &&
                    expanded + length <= limits.maxExpandedBytes)
                zip.write(bytes, offset, length)
                manifestBytes += length
                expanded += length
            }
        }
        JsonWriter(OutputStreamWriter(manifestSink, Charsets.UTF_8)).use { json ->
            json.beginObject()
            json.name("format").value(FORMAT)
            json.name("version").value(VERSION)
            json.name("scope").value(SCOPE)
            json.name("sessionId").value(source.manifest.sessionId)
            json.name("exportedAt").value(source.manifest.exportedAt)
            json.name("sourceCharacters").beginArray()
            source.manifest.sourceCharacters.forEach { role ->
                context.ensureActive()
                json.beginObject().name("id").value(role.id).name("name").value(role.name).endObject()
            }
            json.endArray().endObject()
        }
        zip.closeEntry()
        begin("chat.json"); copyFile(source.chatJson, limits.maxJsonElementBytes.coerceAtLeast(limits.maxExpandedBytes)); zip.closeEntry()
        begin(MEDIA_INDEX)
        RandomAccessFile(source.mediaIndexJsonl, "r").use { reader ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = readLineBytes(reader, limits.maxJsonElementBytes)?.toString(Charsets.UTF_8) ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                parseMedia(trimmed, limits)
                val bytes = trimmed.toByteArray(Charsets.UTF_8)
                require(expanded + bytes.size + 1 <= limits.maxExpandedBytes)
                zip.write(bytes); zip.write('\n'.code); expanded += bytes.size + 1
            }
        }
        zip.closeEntry()
        RandomAccessFile(source.mediaIndexJsonl, "r").use { reader ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = readLineBytes(reader, limits.maxJsonElementBytes)?.toString(Charsets.UTF_8) ?: break
                if (line.isBlank()) continue
                val row = parseMedia(line.trim(), limits)
                val file = source.mediaFile("media/${row.ordinal}")
                require(file.isFile && file.length() == row.byteLength)
                require(sha256(file) == row.sha256.lowercase(Locale.ROOT))
                begin("media/${row.ordinal}"); copyFile(file, limits.maxSingleMediaBytes); zip.closeEntry()
            }
        }
        zip.finish(); return entries.toLong()
        } finally { zip.close() }
    }

    /** Extracts and validates once into requestDir. The caller owns requestDir lifecycle. */
    suspend fun open(inputZip: File, requestDir: File, limits: Limits = Limits()): BundleHandle {
        require(inputZip.isFile && inputZip.length() <= limits.maxInputZipBytes)
        // Require the completed central directory too; a truncated export may contain valid
        // local entries while still being an incomplete ZIP document.
        preflightCentralDirectory(inputZip, limits)
        val centralCount = ZipFile(inputZip).use { archive ->
            require(archive.size() in 3..limits.maxEntryCount)
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                currentCoroutineContext().ensureActive()
                val entry = entries.nextElement()
                require(!entry.isDirectory && safeEntry(entry.name))
            }
            archive.size()
        }
        require(!requestDir.exists() && requestDir.mkdirs())
        val ownershipMarker = requestDir.resolve(".__mojing_bundle_request")
        ownershipMarker.writeText("mojing_chat_media_bundle_v1")
        var total = 0L; var count = 0
        var manifest: ExportManifest? = null; var chat: File? = null; var index: File? = null
        ZipInputStream(BufferedInputStream(FileInputStream(inputZip))).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                count++; require(count <= limits.maxEntryCount)
                val name = entry.name
                require(!entry.isDirectory && safeEntry(name))
                val target = File(requestDir, name)
                require(target.canonicalFile.parentFile == requestDir.resolve(name.substringBeforeLast('/', "")).canonicalFile || name == "manifest.json" || name == "chat.json" || name == MEDIA_INDEX)
                target.parentFile?.mkdirs()
                require(target.createNewFile())
                FileOutputStream(target, false).use { out ->
                    var bytes = 0L; val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive(); val n = zip.read(buffer); if (n < 0) break
                        bytes += n; total += n; require(total <= limits.maxExpandedBytes)
                        if (name.startsWith(MEDIA_PREFIX) && name != MEDIA_INDEX) require(bytes <= limits.maxSingleMediaBytes)
                        digest.update(buffer, 0, n); out.write(buffer, 0, n)
                    }
                }
                when (name) { "manifest.json" -> manifest = parseManifest(File(requestDir, name), limits); "chat.json" -> chat = File(requestDir, name); MEDIA_INDEX -> index = File(requestDir, name) }
                zip.closeEntry()
            }
        }
        require(count == centralCount) { "媒体包目录不完整" }
        val m = requireNotNull(manifest); val c = requireNotNull(chat); val i = requireNotNull(index)
        val messageIds = requestDir.resolve(".__message-ids.bin")
        val emptyMessageIds = requestDir.resolve(".__empty-message-ids.bin")
        validateChat(c, m, limits, messageIds, emptyMessageIds)
        val records = requestDir.resolve(".__media-index.bin")
        validateIndex(i, requestDir, m, limits, records, messageIds, emptyMessageIds)
        return BundleHandle(requestDir, m, c, i, records, ownershipMarker)
    }

    suspend fun readMessages(handle: BundleHandle, onMessage: suspend (MessageRecord) -> Unit) {
        JsonReader(BoundedJsonReader(InputStreamReader(FileInputStream(handle.chatJson), Charsets.UTF_8.newDecoder()), Limits().maxJsonElementBytes)).use { json ->
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "messages" -> { json.beginArray(); while (json.peek() != JsonToken.END_ARRAY) { currentCoroutineContext().ensureActive(); onMessage(readMessage(json)) }; json.endArray() }
                    else -> json.skipValue()
                }
            }
            json.endObject()
        }
    }

    suspend fun readMediaForMessage(handle: BundleHandle, sourceMessageId: Long, onMedia: suspend (MediaRecord) -> Unit) {
        require(sourceMessageId > 0)
        val records = handle.recordsReader
        val index = handle.indexReader
        val count = records.length() / RECORD_SIZE
        if (count == 0L) return
        var position = if (sourceMessageId == handle.previousSource) handle.lastMediaStart else handle.nextMediaPosition
        if (sourceMessageId < handle.previousSource) {
            var low = 0L; var high = count
            while (low < high) {
                val mid = (low + high) ushr 1; records.seek(mid * RECORD_SIZE)
                if (records.readLong() < sourceMessageId) low = mid + 1 else high = mid
            }
            position = low
        }
        while (position < count) {
            records.seek(position * RECORD_SIZE)
            if (records.readLong() >= sourceMessageId) break
            position++
        }
        handle.lastMediaStart = position
        handle.previousSource = sourceMessageId
        while (position < count) {
            currentCoroutineContext().ensureActive()
            records.seek(position * RECORD_SIZE)
            val id = records.readLong(); val offset = records.readLong(); val length = records.readInt(); records.readInt()
            if (id != sourceMessageId) break
            val bytes = ByteArray(length); index.seek(offset); index.readFully(bytes)
            onMedia(parseMedia(String(bytes, Charsets.UTF_8), Limits()))
            position++
        }
        handle.nextMediaPosition = position
    }

    suspend fun openMedia(handle: BundleHandle, media: MediaRecord): InputStream {
        val file = File(handle.root, "media/${media.ordinal}")
        require(file.isFile && file.length() == media.byteLength && sha256(file) == media.sha256.lowercase(Locale.ROOT))
        return FileInputStream(file)
    }

    suspend fun fingerprint(handle: BundleHandle): String {
        val digest = MessageDigest.getInstance("SHA-256")
        handle.manifest.sourceCharacters.sortedBy { it.id }.forEach { digestField(digest, it.id.toString()); digestField(digest, it.name) }
        readMessages(handle) { message ->
            digestField(digest, message.id.toString()); digestField(digest, message.speakerType); digestField(digest, message.characterId?.toString().orEmpty()); digestField(digest, message.parentMessageId?.toString().orEmpty()); digestField(digest, message.content); digestField(digest, message.structuredContentJson); digestField(digest, message.includeInContext.toString()); digestField(digest, message.createdAt.toString())
            readMediaForMessage(handle, message.id) { media -> digestField(digest, media.ordinal.toString()); digestField(digest, media.assetType); digestField(digest, media.fileName); digestField(digest, media.mimeType); digestField(digest, media.byteLength.toString()); digestField(digest, media.sha256.lowercase(Locale.ROOT)); digestField(digest, media.generationPrompt); digestField(digest, media.generationModel); digestField(digest, media.createdAt.toString()) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readMessage(json: JsonReader): MessageRecord {
        var id = 0L; var session = 0L; var speaker = ""; var character: Long? = null; var branch = ""; var parent: Long? = null
        var content = ""; var structured = "{}"; var include = true; var created = 0L
        val fields = HashSet<String>()
        json.beginObject(); while (json.hasNext()) { val field = json.nextName(); require(fields.size < 32 && fields.add(field)) { "消息包含重复或过多字段" }; when (field) {
            "id" -> id = json.nextLong(); "sessionId" -> session = json.nextLong(); "speakerType" -> speaker = json.nextString()
            "characterId" -> character = if (json.peek() == JsonToken.NULL) { json.nextNull(); null } else json.nextLong()
            "branchId" -> branch = json.nextString(); "parentMessageId" -> parent = if (json.peek() == JsonToken.NULL) { json.nextNull(); null } else json.nextLong()
            "content" -> content = json.nextString(); "structuredContentJson" -> structured = json.nextString(); "includeInContext" -> include = json.nextBoolean(); "createdAt" -> created = json.nextLong(); else -> json.skipValue()
        } }; json.endObject()
        require(fields.containsAll(listOf("id", "sessionId", "speakerType", "branchId", "content", "structuredContentJson", "includeInContext", "createdAt"))) { "媒体包消息字段不完整" }
        return MessageRecord(id, session, speaker, character, branch, parent, content, structured, include, created)
    }

    private fun parseManifest(file: File, limits: Limits): ExportManifest {
        require(file.length() <= limits.maxJsonElementBytes); val o = JsonParser.parseString(file.readText()).asJsonObject
        require(o.get("format").asString == FORMAT && o.get("version").asInt == VERSION && o.get("scope").asString == SCOPE)
        val array = o.getAsJsonArray("sourceCharacters")
        require(array.size() <= 4096) { "来源角色数量过多" }
        val chars = array.map { it.asJsonObject }.map { SourceCharacter(it.get("id").asLong, it.get("name").asString) }
        validateManifest(ExportManifest(o.get("sessionId").asLong, o.get("exportedAt").asLong, chars)); return ExportManifest(o.get("sessionId").asLong, o.get("exportedAt").asLong, chars)
    }

    private suspend fun validateChat(file: File, manifest: ExportManifest, limits: Limits, idsFile: File, emptyIdsFile: File) {
        require(file.length() <= limits.maxExpandedBytes)
        JsonReader(BoundedJsonReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8.newDecoder()), limits.maxJsonElementBytes)).use { json ->
            var session = 0L; var previous = 0L
            val characterIds = manifest.sourceCharacters.map { it.id }.toSet()
            val seenFields = HashSet<String>()
            RandomAccessFile(idsFile, "rw").use { ids -> RandomAccessFile(emptyIdsFile, "rw").use { emptyIds ->
                json.beginObject()
                while (json.hasNext()) { currentCoroutineContext().ensureActive(); val field = json.nextName(); require(seenFields.size < 32 && seenFields.add(field)); when (field) {
                    "format" -> require(json.nextString() == CHAT_FORMAT)
                    "version" -> require(json.nextInt() == VERSION)
                    "sessionId" -> { session = json.nextLong(); require(session == manifest.sessionId) }
                    "messages" -> { json.beginArray(); while (json.peek() != JsonToken.END_ARRAY) { currentCoroutineContext().ensureActive(); val message = readMessage(json); require(message.sessionId == manifest.sessionId && message.branchId == "main" && message.id > previous); require(message.content.length <= limits.maxJsonElementBytes); require(message.parentMessageId == null || message.parentMessageId < message.id); if (message.parentMessageId != null) require(hasMessageId(ids, message.parentMessageId)); require(message.speakerType in setOf("user", "character", "narrator")); if (message.speakerType == "narrator" || message.speakerType == "user") require(message.characterId == null); if (message.speakerType == "character") require(message.characterId != null && characterIds.contains(message.characterId)); require(runCatching { JsonParser.parseString(message.structuredContentJson).isJsonObject }.getOrDefault(false)); if (message.content.isEmpty()) emptyIds.writeLong(message.id); ids.seek(ids.length()); ids.writeLong(message.id); previous = message.id }; json.endArray() }
                    else -> json.skipValue()
                } }
                json.endObject(); require(session == manifest.sessionId && seenFields.containsAll(listOf("format", "version", "messages"))); require(json.peek() == JsonToken.END_DOCUMENT)
            }
        } }
    }
    private suspend fun validateIndex(file: File, root: File, manifest: ExportManifest, limits: Limits, records: File, idsFile: File, emptyIdsFile: File) {
        var previousSource = Long.MIN_VALUE; var previousOrdinal = -1L; var previousOffset = -1L; var expectedOrdinal = 0L
        RandomAccessFile(idsFile, "r").use { ids -> RandomAccessFile(file, "r").use { raw -> RandomAccessFile(records, "rw").use { out ->
            while (raw.filePointer < raw.length()) {
                currentCoroutineContext().ensureActive()
                val offset = raw.filePointer; val lineBytes = readLineBytes(raw, limits.maxJsonElementBytes) ?: break; val line = String(lineBytes, Charsets.UTF_8); if (line.isBlank()) continue
                val r = parseMedia(line, limits)
                require(r.sourceMessageId > 0 && r.ordinal == expectedOrdinal)
                require(hasMessageId(ids, r.sourceMessageId))
                require(r.sourceMessageId > previousSource || (r.sourceMessageId == previousSource && r.ordinal > previousOrdinal))
                val mediaFile = File(root, "media/${r.ordinal}")
                require(mediaFile.isFile && mediaFile.length() == r.byteLength && r.byteLength <= limits.maxSingleMediaBytes)
                require(sha256(mediaFile) == r.sha256.lowercase(Locale.ROOT))
                require(r.sha256.matches(Regex("[0-9a-fA-F]{64}")) && r.assetType.isNotBlank() && r.mimeType.isNotBlank())
                require(r.fileName.isNotBlank() && !r.fileName.contains('/') && !r.fileName.contains('\\') && r.fileName != "." && r.fileName != "..")
                require((r.assetType.equals("image", true) && r.mimeType.startsWith("image/", true)) || (r.assetType.equals("voice", true) && r.mimeType.startsWith("audio/", true)) || r.assetType.equals("file", true))
                require(offset > previousOffset)
                out.writeLong(r.sourceMessageId); out.writeLong(offset); out.writeInt(lineBytes.size); out.writeInt(0)
                previousSource = r.sourceMessageId; previousOrdinal = r.ordinal; previousOffset = offset; expectedOrdinal++
            }
        } } }
        RandomAccessFile(records, "r").use { recordReader ->
            RandomAccessFile(emptyIdsFile, "r").use { empty ->
                while (empty.filePointer < empty.length()) require(hasRecordSource(recordReader, empty.readLong()))
            }
        }
        val mediaDir = root.resolve("media"); require(mediaDir.isDirectory)
        Files.newDirectoryStream(mediaDir.toPath()).use { entries -> entries.forEach { child -> require(child.fileName.toString() == "index.jsonl" || child.fileName.toString().toLongOrNull()?.let { it in 0 until expectedOrdinal } == true) } }
    }
    private fun hasMessageId(f: RandomAccessFile, id: Long): Boolean { var low=0L; var high=f.length()/8; while(low<high){val m=(low+high) ushr 1; f.seek(m*8); val v=f.readLong(); if(v<id)low=m+1 else high=m}; return low < f.length()/8 && run { f.seek(low*8); f.readLong()==id } }
    private fun hasRecordSource(f: RandomAccessFile, id: Long): Boolean { var low=0L; var high=f.length()/RECORD_SIZE; while(low<high){val m=(low+high) ushr 1; f.seek(m*RECORD_SIZE); val v=f.readLong(); if(v<id)low=m+1 else high=m}; return low < f.length()/RECORD_SIZE && run { f.seek(low*RECORD_SIZE); f.readLong()==id } }
    private fun parseMedia(line: String, limits: Limits): MediaRecord { require(line.toByteArray(Charsets.UTF_8).size <= limits.maxJsonElementBytes); val o = JsonParser.parseString(line).asJsonObject; return MediaRecord(o.get("sourceMessageId").asLong, o.get("ordinal").asLong, o.get("assetType").asString, o.get("fileName").asString, o.get("mimeType").asString, o.get("byteLength").asLong, o.get("sha256").asString, o.get("generationPrompt")?.asString.orEmpty(), o.get("generationModel")?.asString.orEmpty(), o.get("createdAt")?.asLong ?: 0L) }
    private fun validateManifest(m: ExportManifest) { require(m.sessionId > 0); require(m.sourceCharacters.size <= 4096); require(m.sourceCharacters.all { it.name.toByteArray(Charsets.UTF_8).size <= 4096 }); require(m.sourceCharacters.map { it.id }.distinct().size == m.sourceCharacters.size); require(m.sourceCharacters.map { it.name }.distinct().size == m.sourceCharacters.size); require(m.sourceCharacters.all { it.id > 0 && it.name.isNotBlank() }) }
    // ZipFile allocates the central directory during construction. Bound its metadata before
    // opening it, including ZIP64 packages whose payload or central offset exceeds 4 GiB.
    private fun preflightCentralDirectory(file: File, limits: Limits) {
        RandomAccessFile(file, "r").use { input ->
            val tailLength = minOf(input.length(), 65_557L).toInt()
            if (tailLength < 22) throw java.util.zip.ZipException("媒体包 ZIP 中央目录不完整")
            val tail = ByteArray(tailLength)
            val tailOffset = input.length() - tailLength
            input.seek(tailOffset); input.readFully(tail)
            fun unsigned(bytes: ByteArray, offset: Int, width: Int): Long {
                var result = 0L
                for (index in 0 until width) result = result or ((bytes[offset + index].toLong() and 255) shl (index * 8))
                return result
            }
            val end = (tailLength - 22 downTo 0).firstOrNull { offset ->
                unsigned(tail, offset, 4) == 0x06054b50L &&
                    offset + 22 + unsigned(tail, offset + 20, 2) == tailLength.toLong()
            } ?: throw java.util.zip.ZipException("媒体包 ZIP 中央目录不完整")
            val endOffset = tailOffset + end
            require(unsigned(tail, end + 4, 2) == 0L && unsigned(tail, end + 6, 2) == 0L) { "不支持分卷媒体包" }
            var count = unsigned(tail, end + 10, 2)
            var diskCount = unsigned(tail, end + 8, 2)
            var centralBytes = unsigned(tail, end + 12, 4)
            var centralOffset = unsigned(tail, end + 16, 4)
            var metadataStart = endOffset
            if (count == 65_535L || diskCount == 65_535L || centralBytes == 0xffffffffL || centralOffset == 0xffffffffL) {
                if (endOffset < 20) throw java.util.zip.ZipException("ZIP64 媒体包目录不完整")
                val locator = ByteArray(20)
                input.seek(endOffset - 20); input.readFully(locator)
                require(unsigned(locator, 0, 4) == 0x07064b50L && unsigned(locator, 4, 4) == 0L && unsigned(locator, 16, 4) == 1L)
                val zip64Offset = unsigned(locator, 8, 8)
                require(zip64Offset >= 0 && zip64Offset <= endOffset - 76)
                val record = ByteArray(56)
                input.seek(zip64Offset); input.readFully(record)
                val recordLength = unsigned(record, 4, 8)
                require(unsigned(record, 0, 4) == 0x06064b50L && recordLength >= 44 && recordLength <= endOffset - 20 - zip64Offset - 12)
                require(unsigned(record, 16, 4) == 0L && unsigned(record, 20, 4) == 0L) { "不支持分卷媒体包" }
                diskCount = unsigned(record, 24, 8)
                count = unsigned(record, 32, 8)
                centralBytes = unsigned(record, 40, 8)
                centralOffset = unsigned(record, 48, 8)
                metadataStart = zip64Offset
            }
            require(count in 3L..limits.maxEntryCount.toLong() && diskCount == count) { "媒体包条目数量超过上限或目录无效" }
            require(centralBytes in 0L..16L * 1024 * 1024) { "媒体包目录超过解析上限" }
            require(centralOffset >= 0 && centralOffset <= metadataStart - centralBytes) { "媒体包目录不完整" }
        }
    }

    private fun safeEntry(name: String) = name == "manifest.json" || name == "chat.json" || name == MEDIA_INDEX || name.matches(Regex("media/(0|[1-9][0-9]*)"))
    private const val RECORD_SIZE = 24L
    /** Bound strings and complete row objects before Gson allocates them. */
    private class BoundedJsonReader(private val delegate: Reader, private val maxBytes: Long) : Reader() {
        private var depth = 0
        private var inString = false
        private var escaped = false
        private var stringBytes = 0L
        private var rowBytes = 0L
        private var rowActive = false

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            val read = delegate.read(buffer, offset, length)
            if (read < 0) return read
            for (index in offset until offset + read) {
                val char = buffer[index]
                val bytes = when {
                    char.code < 128 -> 1
                    char.code < 2048 -> 2
                    char.isLowSurrogate() -> 1
                    else -> 3
                }
                if (rowActive) { rowBytes += bytes; require(rowBytes <= maxBytes) { "单条消息超过解析上限" } }
                if (inString) {
                    stringBytes += bytes
                    require(stringBytes <= maxBytes) { "文本字段超过解析上限" }
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> inString = false
                    }
                } else when (char) {
                    '"' -> { inString = true; stringBytes = 0 }
                    '{', '[' -> {
                        depth++
                        require(depth <= 64) { "媒体包 JSON 层级过深" }
                        if (depth == 3 && char == '{') { rowActive = true; rowBytes = 1 }
                    }
                    '}', ']' -> {
                        if (depth == 3 && rowActive) rowActive = false
                        depth--
                    }
                }
            }
            return read
        }
        override fun close() { delegate.close() }
    }
    private fun readLineBytes(raw: RandomAccessFile, maxBytes: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        while (true) { val b = raw.read(); if (b < 0) return if (out.size() == 0) null else out.toByteArray(); if (b == '\n'.code) break; require(out.size().toLong() < maxBytes); out.write(b) }
        val bytes = out.toByteArray(); return if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.copyOf(bytes.size - 1) else bytes
    }
    private fun digestField(digest: MessageDigest, value: String) { val bytes = value.toByteArray(Charsets.UTF_8); digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8)); digest.update(':'.code.toByte()); digest.update(bytes); digest.update('\n'.code.toByte()) }
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    private suspend fun sha256(file: File): String = FileInputStream(file).use { sha256(it) }
    private suspend fun sha256(input: InputStream): String { val d = MessageDigest.getInstance("SHA-256"); val b = ByteArray(DEFAULT_BUFFER_SIZE); while (true) { currentCoroutineContext().ensureActive(); val n = input.read(b); if (n < 0) break; d.update(b, 0, n) }; return d.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } }
}

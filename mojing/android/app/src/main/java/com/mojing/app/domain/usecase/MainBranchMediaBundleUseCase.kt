package com.mojing.app.domain.usecase

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.mojing.app.data.local.AutoVoiceMetadata
import com.mojing.app.data.local.dao.*
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.contextSelectionKey
import com.mojing.app.data.local.AppDatabase
import com.mojing.app.domain.chat.MainBranchChatExportWriter
import com.mojing.app.domain.chat.MainBranchMediaBundleCodec
import com.mojing.app.domain.chat.MainBranchMediaBundleCodec.ExportManifest
import com.mojing.app.domain.chat.MainBranchMediaBundleCodec.ExportSource
import com.mojing.app.domain.chat.MainBranchMediaBundleCodec.MediaRecord
import com.mojing.app.domain.chat.MainBranchMediaBundleCodec.SourceCharacter
import com.mojing.app.domain.chat.TavernChatImportParser
import com.mojing.app.util.ChatAttachmentFiles
import kotlinx.coroutines.*
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** Android-only append into a chosen storyline. Original messages and existing files are never replaced. */
class MainBranchMediaBundleUseCase(
    private val context: Context,
    private val messages: MessageDao,
    private val attachments: AttachmentDao,
    private val characters: CharacterDao,
    private val participants: ParticipantDao,
    private val sessions: SessionDao,
    private val branches: SessionBranchDao,
    private val database: AppDatabase? = null,
) {
    data class ImportResult(val messageCount: Int, val mediaCount: Int, val duplicate: Boolean)
    var commitOutcomeUnknown: Boolean = false
        private set
    var committedImportResult: ImportResult? = null
        private set
    private val codec = MainBranchMediaBundleCodec
    private val limits = MainBranchMediaBundleCodec.Limits()
    private val gson = Gson()

    suspend fun exportBundle(
        output: OutputStream,
        sessionId: Long,
        ensureOwner: suspend () -> Unit,
        onProgress: suspend (String, Int) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        val root = requestRoot(requestId)
        try {
            ensureOwner()
            val chat = File(root, "chat.json")
            val pendingIndex = File(root, "pending-index.jsonl")
            val sourceRoles = linkedMapOf<Long, String>()
            var mediaCount = 0
            var processedMessages = 0
            val exportedAt = System.currentTimeMillis()
            // Freeze JSON/descriptors on a dedicated read-only WAL snapshot, leaving Room's
            // writer available. Media bytes, hashes, ZIP and SAF writes follow after it closes.
            val messageCount = withReadSnapshot { snapshot ->
                val upper = snapshot.maxMessageId(sessionId)
                pendingIndex.bufferedWriter().use { index ->
                    chat.outputStream().buffered().use { stream ->
                        MainBranchChatExportWriter.write(stream, sessionId, exportedAt, upper) { after, max, count ->
                            ensureOwner()
                            val page = snapshot.messagePage(sessionId, after, max, count)
                            val ids = page.mapNotNull { it.characterId }.distinct()
                            if (ids.isNotEmpty()) snapshot.roles(ids).forEach {
                                check(it.second.toByteArray().size <= 4096) { "角色名称超过媒体包字段上限" }
                                sourceRoles[it.first] = it.second
                            }
                            check(sourceRoles.size <= MAX_SOURCE_ROLES) { "主线参与角色过多，暂不支持此媒体包" }
                            val snapshotContext = currentCoroutineContext()
                            if (page.isNotEmpty()) snapshot.forEachAttachment(page.map { it.id }) { attachment ->
                                    snapshotContext.ensureActive()
                                    check(mediaCount < limits.maxEntryCount - 3) { "媒体条目过多" }
                                    check(attachment.generationPrompt.toByteArray().size < limits.maxJsonElementBytes / 2) {
                                        "媒体生成描述超过单项解析上限"
                                    }
                                    index.write(gson.toJson(PendingMedia(attachment.messageId, mediaCount++, attachment)))
                                    index.newLine()
                            }
                            processedMessages += page.size
                            onProgress("正在整理主线记录", processedMessages)
                            page
                        }
                    }
                }
            }
            check(messageCount > 0) { "主线没有可导出的记录" }
            val index = File(root, "media/index.jsonl")
            check(index.parentFile!!.mkdirs()) { "无法创建临时媒体目录" }
            var expandedBytes = chat.length()
            index.bufferedWriter().use { destination ->
                pendingIndex.bufferedReader().use { source ->
                    var copied = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = readBoundedLine(source, limits.maxJsonElementBytes) ?: break
                        ensureOwner()
                        val pending = gson.fromJson(line, PendingMedia::class.java)
                        val original = File(pending.attachment.storagePath)
                        check(original.isFile && original.canRead() && !Files.isSymbolicLink(original.toPath())) {
                            "部分媒体文件无法读取，请检查原消息后重新导出"
                        }
                        val target = File(root, "media/${pending.ordinal}")
                        val copiedFile = original.inputStream().use { copyAndHash(it, target, limits.maxSingleMediaBytes) }
                        expandedBytes += copiedFile.first
                        check(expandedBytes <= limits.maxExpandedBytes) { "媒体包超过磁盘安全上限" }
                        val a = pending.attachment
                        destination.write(gson.toJson(MediaRecord(pending.messageId, pending.ordinal.toLong(),
                            a.assetType, safeFileName(a.fileName).ifBlank { original.name }, a.mimeType, copiedFile.first, copiedFile.second,
                            a.generationPrompt, a.generationModel, a.createdAt)))
                        destination.newLine()
                        copied++
                        onProgress(if (expandedBytes > SOFT_BYTES) "媒体包较大，正在复制媒体" else "正在复制媒体", copied)
                    }
                }
            }
            ensureOwner()
            // Preflight our own package before emitting a successful archive (parents/empty rows included).
            val archive = File(root, "prepared.zip")
            archive.outputStream().buffered().use { destination ->
                codec.write(ExportSource(ExportManifest(sessionId, exportedAt,
                    sourceRoles.map { SourceCharacter(it.key, it.value) }), chat, index,
                    mediaFile = { name -> File(root, name) }), destination, limits)
            }
            codec.open(archive, File(root, "validated"), limits).use { /* complete package validation */ }
            ensureOwner()
            onProgress("正在写出媒体包", messageCount.toInt())
            archive.inputStream().use { input -> copyToOutput(input, output, limits.maxInputZipBytes) }
            ensureOwner()
            messageCount
        } finally {
            withContext(NonCancellable) { deleteRequestCache(root, requestId) }
        }
    }

    suspend fun importBundle(
        sessionId: Long,
        branchId: String,
        openInput: () -> InputStream,
        ensureOwner: suspend () -> Unit,
        onProgress: suspend (String, Int) -> Unit,
    ): ImportResult = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        val root = requestRoot(requestId)
        activeRequests.add(requestId)
        var knownBatchId: String? = null
        var knownMessageCount = 0
        var knownMediaCount = 0
        var writeAttempted = false
        try {
            ensureOwner()
            ChatAttachmentFiles.cleanupOrphanedBundleFiles(context, sessionId, activeRequests) {
                attachments.countByStoragePath(it) > 0
            }
            val archive = File(root, "input.zip")
            // SAF provider is opened exactly once, all further passes use this private immutable file.
            openInput().use { input -> copyAndHash(input, archive, limits.maxInputZipBytes) }
            onProgress("正在校验媒体包", 0)
            codec.open(archive, File(root, "validated"), limits).use { bundle ->
                val batchId = codec.fingerprint(bundle)
                var count = 0
                var mediaCount = 0
                codec.readMessages(bundle) { message ->
                    count++
                    codec.readMediaForMessage(bundle, message.id) { mediaCount++ }
                }
                check(count > 0) { "媒体包没有记录" }
                val textFingerprint = TavernChatImportParser.Fingerprint()
                bundle.chatJson.bufferedReader().use { reader ->
                    TavernChatImportParser.forEachRow(reader) { textFingerprint.add(it) }
                }
                val textBatchId = if (textFingerprint.count > 0) textFingerprint.batchId() else null
                ensureOwner()
                knownBatchId = batchId
                knownMessageCount = count
                knownMediaCount = mediaCount
                check(messages.targetStorylineExists(sessionId, branchId)) { "目标故事线已不存在" }
                val marker = "\"mojing_media_bundle_batch\":\"$batchId\""
                val already = messages.countImportBatch(sessionId, branchId, marker)
                if (already == count) return@use ImportResult(count, mediaCount, true)
                check(already == 0) { "当前故事线媒体包记录不完整，请检查后重试" }
                if (textBatchId != null) check(messages.countImportBatch(sessionId, branchId,
                    "\"st_import_batch\":\"$textBatchId\"") == 0) {
                    "当前故事线已导入对应文字记录。请新建未导过记录的故事线，再导入媒体包"
                }
                val roleMap = resolveRoles(sessionId, bundle.manifest.sourceCharacters)
                var copied = 0
                codec.readMessages(bundle) { message ->
                    codec.readMediaForMessage(bundle, message.id) { media ->
                        ensureOwner()
                        codec.openMedia(bundle, media).use { input ->
                            ChatAttachmentFiles.copyInputToRequestFile(context, sessionId, requestId,
                                media.ordinal.toInt(), input, limits.maxSingleMediaBytes)
                        }
                        copied++
                        onProgress("正在准备媒体", copied)
                    }
                }
                val mappingFile = File(root, "id-map.bin")
                writeAttempted = true
                val inserted = messages.insertMediaBundleIfAbsent(sessionId, branchId, batchId, count,
                    textBatchId, beforeCommit = {
                        ensureOwner()
                        check(resolveRoles(sessionId, bundle.manifest.sourceCharacters) == roleMap) {
                            "参与角色已变化，请重新选择媒体包"
                        }
                    }, writeRows = { insertMessage, insertAttachments ->
                        DiskIdMap(mappingFile).use { idMap ->
                            var written = 0
                            var textRowIndex = 0
                            codec.readMessages(bundle) { message ->
                                ensureOwner()
                                var hasMedia = false
                                codec.readMediaForMessage(bundle, message.id) { hasMedia = true }
                                val structured = JsonParser.parseString(message.structuredContentJson).asJsonObject
                                structured.addProperty("mojing_media_bundle_batch", batchId)
                                structured.addProperty("mojing_media_bundle_source", message.id)
                                structured.remove("st_import_batch")
                                structured.remove("st_import_index")
                                if (message.content.trim().isNotEmpty() && textBatchId != null) {
                                    structured.addProperty("st_import_batch", textBatchId)
                                    structured.addProperty("st_import_index", textRowIndex++)
                                }
                                val derived = structured.get("derived_media_kind")?.asString in setOf("image", "voice")
                                if (derived) {
                                    structured.addProperty("auto_media_attempt_token", UUID.randomUUID().toString())
                                    if (structured.get("auto_media_state")?.asString == "running")
                                        structured.addProperty("auto_media_state", "interrupted")
                                }
                                val entity = MessageEntity(sessionId = sessionId, branchId = branchId,
                                    speakerType = message.speakerType,
                                    characterId = if (message.speakerType == "character") roleMap[message.characterId]
                                        ?: error("来源角色未匹配") else null,
                                    parentMessageId = message.parentMessageId?.let { idMap.lookup(it)
                                        ?: error("来源父消息不存在于主线包") },
                                    content = if (derived && (AutoVoiceMetadata.isLegacyRunningContent(message.content) ||
                                        message.content.trim() == "配图生成中…")) "生成已中断" else message.content,
                                    structuredContentJson = structured.toString(),
                                    includeInContext = if (derived || (hasMedia && message.content.isBlank())) false else message.includeInContext,
                                    createdAt = message.createdAt)
                                val newId = insertMessage(entity)
                                idMap.append(message.id, newId)
                                val page = ArrayList<MessageAttachmentEntity>(128)
                                codec.readMediaForMessage(bundle, message.id) { media ->
                                    page += MessageAttachmentEntity(messageId = newId, assetType = media.assetType,
                                        fileName = media.fileName, mimeType = media.mimeType,
                                        storagePath = ChatAttachmentFiles.requestMediaFile(context, sessionId, requestId,
                                            media.ordinal.toInt()).absolutePath,
                                        generationPrompt = media.generationPrompt, generationModel = media.generationModel,
                                        createdAt = media.createdAt)
                                    if (page.size == 128) { insertAttachments(page.toList()); page.clear() }
                                }
                                if (page.isNotEmpty()) insertAttachments(page)
                                written++
                                if (written % 128 == 0) onProgress("正在保存到当前故事线", written)
                            }
                            written
                        }
                    })
                // The DAO returns only after its atomic transaction commits; no cancellable work follows.
                ImportResult(count, mediaCount, inserted == 0)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    if (writeAttempted) runCatching {
                        knownBatchId?.let { batchId ->
                            if (messages.countImportBatch(sessionId, branchId,
                                "\"mojing_media_bundle_batch\":\"$batchId\"") == knownMessageCount) {
                                committedImportResult = ImportResult(knownMessageCount, knownMediaCount, false)
                            }
                        }
                    }
                    .onFailure { commitOutcomeUnknown = true }
                    // Cleanup failure cannot skip cache cleanup or leave an immortal active owner.
                    // A failed reference query keeps media conservatively for a later exact orphan sweep.
                    runCatching { ChatAttachmentFiles.cleanupRequestFiles(context, sessionId, requestId) {
                        attachments.countByStoragePath(it) > 0
                    } }
                    runCatching { deleteRequestCache(root, requestId) }
                } finally {
                    activeRequests.remove(requestId)
                }
            }
        }
    }

    private suspend fun resolveRoles(sessionId: Long, source: List<SourceCharacter>): Map<Long, Long> {
        check(source.size <= MAX_SOURCE_ROLES) { "来源角色数量过多" }
        val ids = participants.getBySession(sessionId).map { it.characterId }
        check(ids.size <= MAX_SOURCE_ROLES) { "当前会话参与角色数量过多" }
        val names = ids.chunked(128).flatMap { characters.getNamesByIds(it) }.groupBy { it.name }
        return source.associate { role ->
            val matches = names[role.name].orEmpty()
            check(matches.size == 1) {
                if (matches.isEmpty()) "请在会话设置 → 参与角色中添加“${role.name}”，再导入媒体包"
                else "参与角色“${role.name}”重名，请在角色资料中区分名称后重试"
            }
            role.id to matches.single().id
        }
    }

    private data class PendingMedia(val messageId: Long, val ordinal: Int, val attachment: MessageAttachmentEntity)

    /** A read-only connection to the same Room database, on one fixed thread across suspension.
     * BEGIN DEFERRED in WAL pins a coherent read snapshot without owning Room's write executor.
     */
    private suspend fun <T> withReadSnapshot(block: suspend (ReadSnapshot) -> T): T {
        val name = requireNotNull(database?.openHelper?.databaseName) { "媒体包需要已打开的本地数据库" }
        val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            return withContext(dispatcher) {
                SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { readDb ->
                    readDb.execSQL("BEGIN DEFERRED")
                    try { block(ReadSnapshot(readDb)) }
                    finally { readDb.execSQL("ROLLBACK") }
                }
            }
        } finally { dispatcher.close() }
    }

    private class ReadSnapshot(private val db: SQLiteDatabase) {
        fun maxMessageId(sessionId: Long): Long = db.rawQuery(
            "SELECT COALESCE(MAX(id),0) FROM messages WHERE sessionId=? AND branchId='main'",
            arrayOf(sessionId.toString()),
        ).use { it.moveToFirst(); it.getLong(0) }

        fun messagePage(sessionId: Long, after: Long, upper: Long, limit: Int): List<MessageEntity> {
            val page = db.rawQuery("SELECT id,sessionId,speakerType,characterId,branchId,parentMessageId," +
                "content,structuredContentJson,includeInContext,createdAt,swipeGroupId FROM messages " +
                "WHERE sessionId=? AND branchId='main' AND id>? AND id<=? ORDER BY id LIMIT ?",
                arrayOf(sessionId.toString(), after.toString(), upper.toString(), limit.toString())).use { cursor ->
                buildList { while (cursor.moveToNext()) add(MessageEntity(
                    id = cursor.getLong(0), sessionId = cursor.getLong(1), speakerType = cursor.getString(2),
                    characterId = if (cursor.isNull(3)) null else cursor.getLong(3), branchId = cursor.getString(4),
                    parentMessageId = if (cursor.isNull(5)) null else cursor.getLong(5), content = cursor.getString(6),
                    structuredContentJson = cursor.getString(7), includeInContext = cursor.getInt(8) != 0,
                    createdAt = cursor.getLong(9), swipeGroupId = if (cursor.isNull(10)) null else cursor.getString(10))) }
            }
            if (page.isEmpty()) return page
            // Reuse the exact DAO selection SQL; no second implementation of swipe selection rules.
            val selected = db.rawQuery("SELECT id FROM ($MAIN_SELECTED_MESSAGES_QUERY) WHERE id IN (${marks(page.size)})",
                arrayOf(sessionId.toString()) + page.map { it.id.toString() }).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }
            val keys = page.map { it.contextSelectionKey() }.distinct()
            val excluded = db.rawQuery("SELECT messageKey FROM branch_context_exclusions " +
                "WHERE sessionId=? AND branchId='main' AND messageKey IN (${marks(keys.size)})",
                arrayOf(sessionId.toString()) + keys).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            return page.map { it.copy(includeInContext = it.id in selected && it.contextSelectionKey() !in excluded) }
        }

        fun roles(ids: List<Long>): List<Pair<Long, String>> = db.rawQuery(
            "SELECT id,name FROM characters WHERE id IN (${marks(ids.size)})", ids.map(Long::toString).toTypedArray(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1)) } }

        fun forEachAttachment(ids: List<Long>, onAttachment: (MessageAttachmentEntity) -> Unit) = db.rawQuery(
            "SELECT messageId,assetType,fileName,mimeType,storagePath,generationPrompt,generationModel,createdAt " +
                "FROM message_attachments WHERE messageId IN (${marks(ids.size)}) ORDER BY messageId,id",
            ids.map(Long::toString).toTypedArray(),
        ).use { cursor -> while (cursor.moveToNext()) onAttachment(MessageAttachmentEntity(
            messageId = cursor.getLong(0), assetType = cursor.getString(1), fileName = cursor.getString(2),
            mimeType = cursor.getString(3), storagePath = cursor.getString(4), generationPrompt = cursor.getString(5),
            generationModel = cursor.getString(6), createdAt = cursor.getLong(7))) }

        private fun marks(count: Int) = List(count) { "?" }.joinToString(",")
    }

    private fun requestRoot(requestId: String): File {
        val dir = File(context.cacheDir, "chat_media_bundle/$requestId")
        check(!dir.exists() && dir.mkdirs()) { "无法创建媒体包临时目录，请检查存储空间" }
        return dir
    }

    private fun deleteRequestCache(root: File, requestId: String) {
        val parent = File(context.cacheDir, "chat_media_bundle").canonicalFile
        check(root.name == UUID.fromString(requestId).toString() && root.canonicalFile.parentFile == parent)
        if (!Files.isSymbolicLink(root.toPath())) root.deleteRecursively()
    }

    private suspend fun copyAndHash(input: InputStream, target: File, maxBytes: Long): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val count = target.outputStream().buffered().use { output -> copyToOutput(input, output, maxBytes, digest) }
        check(count > 0) { "媒体包或媒体文件为空" }
        return count to digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private suspend fun copyToOutput(input: InputStream, output: OutputStream, max: Long, digest: MessageDigest? = null): Long {
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            bytes += read
            check(bytes <= max) { "媒体包超过磁盘安全上限" }
            output.write(buffer, 0, read)
            digest?.update(buffer, 0, read)
        }
        return bytes
    }

    private fun safeFileName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')

    private fun readBoundedLine(reader: Reader, maxBytes: Long): String? {
        val value = StringBuilder()
        var bytes = 0L
        while (true) {
            val next = reader.read()
            if (next < 0) return if (value.isEmpty()) null else value.toString()
            if (next == '\n'.code) return value.toString()
            bytes += if (next < 128) 1 else if (next < 2048) 2 else 3
            check(bytes <= maxBytes) { "媒体元信息超过单项解析上限" }
            value.append(next.toChar())
        }
    }

    private class DiskIdMap(file: File) : Closeable {
        private val records = RandomAccessFile(file, "rw")
        fun append(source: Long, target: Long) { records.seek(records.length()); records.writeLong(source); records.writeLong(target) }
        fun lookup(source: Long): Long? {
            var low = 0L; var high = records.length() / 16 - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                records.seek(mid * 16)
                val candidate = records.readLong()
                if (candidate == source) return records.readLong()
                if (candidate < source) low = mid + 1 else high = mid - 1
            }
            return null
        }
        override fun close() { records.close() }
    }

    companion object {
        private const val MAX_SOURCE_ROLES = 4096
        private const val SOFT_BYTES = 512L * 1024 * 1024
        private val activeRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }
}

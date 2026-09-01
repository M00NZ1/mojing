package com.mojing.app.service

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 本地 Room 数据库备份/恢复。
 *
 * 备份必须把 WAL 相关文件作为同一个快照处理；恢复必须先解压、校验完整性，再替换旧文件。
 * 任何校验或替换步骤失败都不会删除原数据库。
 */
object BackupService {
    private const val DATABASE_NAME = "mojing.db"
    private const val MANIFEST_NAME = "manifest.properties"
    private const val FORMAT = "mojing_backup"
    private const val VERSION = 2
    private const val MAX_ARCHIVE_ENTRY_BYTES = 512L * 1024L * 1024L
    private const val MAX_ARCHIVE_BYTES = 768L * 1024L * 1024L
    private val COMPONENT_SUFFIXES = listOf("", "-wal", "-shm")
    private val ALLOWED_ENTRIES = setOf(
        DATABASE_NAME,
        "$DATABASE_NAME-wal",
        "$DATABASE_NAME-shm",
        MANIFEST_NAME,
        "settings.txt", // 兼容旧版备份，内容不参与恢复
    )

    fun createBackup(context: Context, outputFile: File): Boolean {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        if (!dbFile.isFile || sameFile(dbFile, outputFile)) return false
        val parent = outputFile.absoluteFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false

        val workDir = File(parent, ".mojing-backup-${UUID.randomUUID()}")
        val tempOutput = File(parent, ".${outputFile.name}.tmp-${UUID.randomUUID()}")
        return try {
            if (!workDir.mkdirs()) return false
            val snapshot = snapshotDatabase(dbFile, workDir) ?: return false
            if (!writeArchive(tempOutput, snapshot)) return false
            if (!validateBackupArchive(tempOutput)) return false
            replaceFile(tempOutput, outputFile)
        } catch (_: Exception) {
            false
        } finally {
            tempOutput.delete()
            workDir.deleteRecursively()
        }
    }

    fun restoreBackup(context: Context, inputFile: File): Boolean {
        if (!inputFile.isFile) return false
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        if (sameFile(dbFile, inputFile)) return false
        val parent = dbFile.absoluteFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false

        val stagingDir = File(parent, ".mojing-restore-${UUID.randomUUID()}")
        val rollbackDir = File(parent, ".mojing-restore-old-${UUID.randomUUID()}")
        var preserveRollbackOnFailure = false
        return try {
            if (!stagingDir.mkdirs()) return false
            if (!extractDatabaseSnapshot(inputFile, stagingDir)) return false
            val stagedDb = File(stagingDir, DATABASE_NAME)
            if (!isDatabaseIntegrityOk(stagedDb)) return false
            if (!rollbackDir.mkdirs()) return false

            if (!moveCurrentDatabaseAside(dbFile, rollbackDir)) {
                preserveRollbackOnFailure = !restoreRollback(dbFile, rollbackDir)
                return false
            }
            if (!installStagedDatabase(dbFile, stagingDir) || !isDatabaseIntegrityOk(dbFile)) {
                removeDatabaseComponents(dbFile)
                preserveRollbackOnFailure = !restoreRollback(dbFile, rollbackDir)
                return false
            }
            rollbackDir.deleteRecursively()
            true
        } catch (_: Exception) {
            removeDatabaseComponents(dbFile)
            preserveRollbackOnFailure = !restoreRollback(dbFile, rollbackDir)
            false
        } finally {
            stagingDir.deleteRecursively()
            if (!preserveRollbackOnFailure && rollbackDir.exists()) rollbackDir.deleteRecursively()
        }
    }

    /** 仅校验 ZIP 结构，供 JVM 测试和调用方在实际恢复前做快速拒绝。 */
    internal fun validateBackupArchive(inputFile: File): Boolean {
        if (!inputFile.isFile) return false
        val names = HashSet<String>()
        var totalBytes = 0L
        var hasDatabase = false
        return try {
            ZipInputStream(FileInputStream(inputFile)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name !in ALLOWED_ENTRIES || !names.add(entry.name)) return false
                    val entryBytes = drainLimited(zip, MAX_ARCHIVE_ENTRY_BYTES)
                    totalBytes += entryBytes
                    if (totalBytes > MAX_ARCHIVE_BYTES) return false
                    if (entry.name == DATABASE_NAME) hasDatabase = true
                }
            }
            hasDatabase
        } catch (_: Exception) {
            false
        }
    }

    private fun snapshotDatabase(dbFile: File, workDir: File): List<File>? {
        // 先尽量把 WAL 合并到主库。若数据库正在忙，仍保留并复制 WAL/SHM，完整性校验会决定是否接受快照。
        checkpointWal(dbFile)
        repeat(3) { attempt ->
            val attemptDir = File(workDir, "attempt-$attempt")
            if (!attemptDir.mkdirs()) return@repeat
            val copied = COMPONENT_SUFFIXES.mapNotNull { suffix ->
                val source = File(dbFile.parentFile, dbFile.name + suffix)
                if (!source.isFile) null else {
                    val target = File(attemptDir, source.name)
                    copyFile(source, target)
                    target
                }
            }
            val copiedDb = File(attemptDir, DATABASE_NAME)
            if (copiedDb.isFile && isDatabaseIntegrityOk(copiedDb)) return copied
            attemptDir.deleteRecursively()
        }
        return null
    }

    private fun writeArchive(outputFile: File, snapshot: List<File>): Boolean {
        return try {
            ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
                val manifest = Properties().apply {
                    setProperty("format", FORMAT)
                    setProperty("version", VERSION.toString())
                    setProperty("database", DATABASE_NAME)
                    setProperty("components", snapshot.joinToString(",") { it.name })
                }
                zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                manifest.store(zip, null)
                zip.closeEntry()
                snapshot.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun extractDatabaseSnapshot(inputFile: File, stagingDir: File): Boolean {
        if (!validateBackupArchive(inputFile)) return false
        val extracted = HashSet<String>()
        var totalBytes = 0L
        return try {
            ZipInputStream(FileInputStream(inputFile)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name !in ALLOWED_ENTRIES || !extracted.add(entry.name)) return false
                    val target = File(stagingDir, entry.name)
                    val bytes = if (entry.name == MANIFEST_NAME || entry.name == "settings.txt") {
                        drainLimited(zip, MAX_ARCHIVE_ENTRY_BYTES)
                    } else {
                        FileOutputStream(target).use { output -> copyLimited(zip, output, MAX_ARCHIVE_ENTRY_BYTES) }
                    }
                    totalBytes += bytes
                    if (totalBytes > MAX_ARCHIVE_BYTES) return false
                }
            }
            val manifest = File(stagingDir, MANIFEST_NAME)
            if (manifest.isFile) {
                Properties().apply { FileInputStream(manifest).use { input -> load(input) } }.let {
                    if (it.getProperty("format") != FORMAT || it.getProperty("database") != DATABASE_NAME) return false
                }
            }
            File(stagingDir, DATABASE_NAME).isFile
        } catch (_: Exception) {
            false
        }
    }

    private fun moveCurrentDatabaseAside(dbFile: File, rollbackDir: File): Boolean {
        for (suffix in COMPONENT_SUFFIXES) {
            val source = File(dbFile.parentFile, dbFile.name + suffix)
            if (source.isFile && !moveFile(source, File(rollbackDir, source.name))) {
                restoreRollback(dbFile, rollbackDir)
                return false
            }
        }
        return true
    }

    private fun installStagedDatabase(dbFile: File, stagingDir: File): Boolean {
        for (suffix in COMPONENT_SUFFIXES) {
            val staged = File(stagingDir, dbFile.name + suffix)
            if (staged.isFile && !moveFile(staged, File(dbFile.parentFile, staged.name))) return false
        }
        return true
    }

    private fun restoreRollback(dbFile: File, rollbackDir: File): Boolean {
        if (!rollbackDir.isDirectory) return true
        var ok = true
        for (suffix in COMPONENT_SUFFIXES) {
            val saved = File(rollbackDir, dbFile.name + suffix)
            if (saved.isFile) {
                val target = File(dbFile.parentFile, saved.name)
                if (target.exists()) target.delete()
                if (!moveFile(saved, target)) ok = false
            }
        }
        return ok
    }

    private fun removeDatabaseComponents(dbFile: File) {
        COMPONENT_SUFFIXES.forEach { suffix -> File(dbFile.parentFile, dbFile.name + suffix).delete() }
    }

    private fun checkpointWal(dbFile: File): Boolean {
        return try {
            SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor -> cursor.moveToFirst() }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isDatabaseIntegrityOk(dbFile: File): Boolean {
        if (!dbFile.isFile) return false
        return try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun copyFile(source: File, target: File) {
        FileInputStream(source).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
    }

    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, limit: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > limit) throw IllegalArgumentException("backup entry too large")
            output.write(buffer, 0, read)
        }
    }

    private fun drainLimited(input: java.io.InputStream, limit: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > limit) throw IllegalArgumentException("backup entry too large")
        }
    }

    private fun moveFile(source: File, target: File): Boolean {
        return runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        }.getOrElse {
            runCatching {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            }.getOrDefault(false)
        }
    }

    private fun replaceFile(source: File, target: File): Boolean {
        if (target.exists() && !target.delete()) return false
        return moveFile(source, target)
    }

    private fun sameFile(left: File, right: File): Boolean =
        runCatching { left.canonicalFile == right.canonicalFile }.getOrDefault(left.absolutePath == right.absolutePath)
}

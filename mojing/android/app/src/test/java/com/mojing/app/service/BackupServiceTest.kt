package com.mojing.app.service

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupServiceTest {
    @Test
    fun acceptsDatabaseArchiveWithWalAndShm() {
        val dir = Files.createTempDirectory("backup-service-test").toFile()
        try {
            val archive = File(dir, "backup.zip")
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                listOf("manifest.properties", "mojing.db", "mojing.db-wal", "mojing.db-shm").forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write("data".toByteArray())
                    zip.closeEntry()
                }
            }

            assertTrue(BackupService.validateBackupArchive(archive))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsPathTraversalAndArchivesWithoutDatabase() {
        val dir = Files.createTempDirectory("backup-service-test").toFile()
        try {
            val traversal = File(dir, "traversal.zip")
            ZipOutputStream(FileOutputStream(traversal)).use { zip ->
                zip.putNextEntry(ZipEntry("../mojing.db"))
                zip.write("bad".toByteArray())
                zip.closeEntry()
            }
            val noDatabase = File(dir, "no-db.zip")
            ZipOutputStream(FileOutputStream(noDatabase)).use { zip ->
                zip.putNextEntry(ZipEntry("settings.txt"))
                zip.write("not a database".toByteArray())
                zip.closeEntry()
            }

            assertFalse(BackupService.validateBackupArchive(traversal))
            assertFalse(BackupService.validateBackupArchive(noDatabase))
        } finally {
            dir.deleteRecursively()
        }
    }
}

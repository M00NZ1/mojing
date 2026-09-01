package com.mojing.app.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class LogExportManagerTest {
    @Test
    fun exportZipShouldContainLogsAndAppInfo() {
        val tmp = createTempDir()
        File(tmp, "session.log").writeText("a")
        File(tmp, "session.prev.log").writeText("b")

        val out = ByteArrayOutputStream()
        LogExportManager.exportZip(
            outputStream = out,
            logDir = tmp,
            appInfoJson = """{"k":"v"}""",
        )

        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                names.add(e.name)
            }
        }
        assertTrue(names.contains("session.log"))
        assertTrue(names.contains("session.prev.log"))
        assertTrue(names.contains("app_info.json"))
    }
}


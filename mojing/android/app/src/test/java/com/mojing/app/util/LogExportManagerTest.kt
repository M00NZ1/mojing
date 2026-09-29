package com.mojing.app.util

import org.junit.Assert.assertFalse
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

    @Test
    fun exportRedactsOldMultilineReplyBodiesButKeepsCrashStack() {
        val tmp = createTempDir()
        File(tmp, "session.log").writeText(
            "2026-09-28 20:57:30.000 I Narrator: session=5 done rawLen=12 raw=私密正文第一行\n" +
                "私密正文第二行\n" +
                "2026-09-28 20:57:31.000 I Narrator: session=5 displayLen=12 display=另一段私密正文\n" +
                "另一段续行\n" +
                "2026-09-28 20:57:32.000 E Crash: uncaught thread=main\n" +
                "java.lang.IllegalArgumentException: duplicate key\n",
        )
        val out = ByteArrayOutputStream()
        LogExportManager.exportZip(out, tmp, "{}")

        val logs = ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name == "session.log") return@use zis.readBytes().toString(Charsets.UTF_8)
            }
            ""
        }
        assertFalse(logs.contains("私密正文"))
        assertFalse(logs.contains("另一段续行"))
        assertTrue(logs.contains("rawLen=12 raw=[redacted]"))
        assertTrue(logs.contains("displayLen=12 display=[redacted]"))
        assertTrue(logs.contains("E Crash: uncaught thread=main"))
        assertTrue(logs.contains("java.lang.IllegalArgumentException: duplicate key"))
    }
}


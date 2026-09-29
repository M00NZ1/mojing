package com.mojing.app.util

import android.content.Context
import android.os.Build
import com.mojing.app.BuildConfig
import com.google.gson.JsonObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogExportManager {
    private val fileNameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val exportedAtFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private val logRecordStart = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} [VDIWE] [A-Za-z][A-Za-z0-9_]*: ")

    fun defaultZipFileName(nowMs: Long = System.currentTimeMillis()): String {
        val ts = synchronized(fileNameFmt) { fileNameFmt.format(Date(nowMs)) }
        return "mojing_log_${ts}.zip"
    }

    fun resolveLogDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "usb_logs")
    }

    fun appInfoJson(context: Context, nowMs: Long = System.currentTimeMillis()): String {
        val root = JsonObject()
        val exportedAt = synchronized(exportedAtFmt) { exportedAtFmt.format(Date(nowMs)) }
        root.addProperty("exported_at", exportedAt)
        root.addProperty("package_name", context.packageName)
        root.addProperty("version_name", BuildConfig.VERSION_NAME)
        root.addProperty("version_code", BuildConfig.VERSION_CODE)
        val device = JsonObject()
        device.addProperty("manufacturer", Build.MANUFACTURER ?: "")
        device.addProperty("model", Build.MODEL ?: "")
        device.addProperty("sdk_int", Build.VERSION.SDK_INT)
        device.addProperty("release", Build.VERSION.RELEASE ?: "")
        root.add("device", device)
        return root.toString()
    }

    fun exportZip(
        outputStream: OutputStream,
        logDir: File,
        appInfoJson: String,
    ) {
        ZipOutputStream(outputStream).use { zos ->
            writeFileIfExists(zos, File(logDir, "session.log"), "session.log")
            writeFileIfExists(zos, File(logDir, "session.prev.log"), "session.prev.log")
            writeString(zos, "app_info.json", appInfoJson)
            zos.finish()
        }
    }

    private fun writeString(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun writeFileIfExists(zos: ZipOutputStream, file: File, entryName: String) {
        if (!file.isFile) return
        zos.putNextEntry(ZipEntry(entryName))
        BufferedReader(InputStreamReader(BufferedInputStream(FileInputStream(file)), Charsets.UTF_8)).use { input ->
            var skippingOldReplyBody = false
            input.forEachLine { line ->
                val isRecord = logRecordStart.containsMatchIn(line)
                if (isRecord) skippingOldReplyBody = false
                if (!skippingOldReplyBody) {
                    val bodyMarker = when {
                        isRecord && " rawLen=" in line && " raw=" in line -> " raw="
                        isRecord && " displayLen=" in line && " display=" in line -> " display="
                        else -> null
                    }
                    val safeLine = if (bodyMarker == null) line else {
                        skippingOldReplyBody = true
                        line.substringBefore(bodyMarker) + bodyMarker + "[redacted]"
                    }
                    zos.write((safeLine + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        }
        zos.closeEntry()
    }
}


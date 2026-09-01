package com.mojing.app.util

import android.app.Application
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 会话级文本日志，写入应用专属外置目录，便于 USB（MTP）连接电脑后直接拷贝。
 *
 * 典型路径：`Android/data/com.mojing.app/files/usb_logs/session.log`
 */
object UsbSessionLog {

    private val lock = Any()
    private val initialized = AtomicBoolean(false)
    private var logDir: File? = null
    private var currentFile: File? = null

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private const val MAX_FILE_BYTES = 20 * 1024 * 1024L

    /** 供设置页等展示；未 init 时返回说明字符串 */
    fun logDirectoryHint(app: Application): String {
        val base = app.getExternalFilesDir(null) ?: app.filesDir
        return File(base, "usb_logs").absolutePath
    }

    fun init(application: Application) {
        if (!initialized.compareAndSet(false, true)) return
        synchronized(lock) {
            val base = application.getExternalFilesDir(null) ?: application.filesDir
            val dir = File(base, "usb_logs").apply { mkdirs() }
            logDir = dir
            currentFile = File(dir, "session.log")
            appendUnlocked(
                "--- app start pid=${android.os.Process.myPid()} pkg=${application.packageName} ---\n" +
                    "USB 拷贝：连接电脑选「文件传输」，在以上包名路径下打开 files/usb_logs/session.log\n" +
                    "绝对目录：${dir.absolutePath}\n",
            )
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                e("Crash", "uncaught thread=${thread.name}", throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun i(tag: String, message: String) = log("I", tag, message, null)

    fun w(tag: String, message: String) = log("W", tag, message, null)

    fun e(tag: String, message: String, throwable: Throwable? = null) = log("E", tag, message, throwable)

    private fun log(level: String, tag: String, message: String, throwable: Throwable?) {
        val stamp = synchronized(timeFmt) { timeFmt.format(Date()) }
        val body = buildString {
            append(stamp).append(' ').append(level).append(' ').append(tag).append(": ").append(message).append('\n')
            if (throwable != null) {
                val stackTrace = runCatching { Log.getStackTraceString(throwable) }
                    .getOrElse { throwable.stackTraceToString() }
                append(stackTrace).append('\n')
            }
        }
        synchronized(lock) {
            rotateIfNeededUnlocked()
            appendUnlocked(body)
        }
        runCatching {
            when (level) {
                "E" -> if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
                "W" -> if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
                else -> Log.i(tag, message)
            }
        }
    }

    private fun rotateIfNeededUnlocked() {
        val f = currentFile ?: return
        if (!f.exists() || f.length() <= MAX_FILE_BYTES) return
        val dir = logDir ?: return
        val prev = File(dir, "session.prev.log")
        runCatching {
            if (prev.exists()) prev.delete()
            f.renameTo(prev)
        }
        val newFile = File(dir, "session.log")
        currentFile = newFile
        appendToFile(newFile, "--- log rotated (prev -> session.prev.log) ---\n")
    }

    private fun appendUnlocked(text: String) {
        appendToFile(currentFile, text)
    }

    private fun appendToFile(file: File?, text: String) {
        if (file == null) return
        runCatching {
            FileOutputStream(file, true).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
    }
}

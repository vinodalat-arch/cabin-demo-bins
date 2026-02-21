package com.incabin

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent crash/event log written to app internal storage.
 * Thread-safe via @Synchronized writes. Rotates at 500KB.
 *
 * Pure functions (formatLine, shouldRotate) are fully unit-testable
 * with no Android dependencies.
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val FILE_NAME = "crash_log.txt"
    private const val PREV_FILE_NAME = "crash_log_prev.txt"
    private const val MAX_SIZE_BYTES = 500L * 1024  // 500KB

    private var logFile: File? = null
    private var prevFile: File? = null
    private var initialized = false

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Initialize with app filesDir. Call once in Service.onCreate(). */
    fun init(filesDir: File) {
        logFile = File(filesDir, FILE_NAME)
        prevFile = File(filesDir, PREV_FILE_NAME)
        initialized = true
        info("CrashLog", "Crash log initialized")
    }

    @Synchronized
    fun log(level: String, tag: String, message: String) {
        if (!initialized) return
        try {
            val file = logFile ?: return
            if (shouldRotate(file.length(), MAX_SIZE_BYTES)) {
                rotate()
            }
            val line = formatLine(level, tag, message, dateFormat.format(Date()))
            file.appendText(line + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write crash log", e)
        }
    }

    @Synchronized
    fun logException(tag: String, message: String, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString().take(2000)  // Cap stack trace length
        log("ERROR", tag, "$message\n$stackTrace")
    }

    fun error(tag: String, message: String) = log("ERROR", tag, message)
    fun warn(tag: String, message: String) = log("WARN", tag, message)
    fun info(tag: String, message: String) = log("INFO", tag, message)

    private fun rotate() {
        try {
            val file = logFile ?: return
            val prev = prevFile ?: return
            if (prev.exists()) prev.delete()
            file.renameTo(prev)
        } catch (e: Exception) {
            Log.w(TAG, "Log rotation failed", e)
        }
    }

    /** Format a log line. Pure function — no Android dependencies. */
    fun formatLine(level: String, tag: String, message: String, timestamp: String): String {
        return "$timestamp [$level] $tag: $message"
    }

    /** Check if log file should rotate. Pure function. */
    fun shouldRotate(currentSize: Long, maxSize: Long): Boolean {
        return currentSize > maxSize
    }
}

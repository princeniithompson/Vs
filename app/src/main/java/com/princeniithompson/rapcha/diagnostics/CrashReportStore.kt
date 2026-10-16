package com.princeniithompson.rapcha.diagnostics

import java.io.File

/**
 * Writes an uncaught exception to a private file so the next process start
 * can show it in diagnostics. The write is synchronous because the process
 * is about to die.
 */
object CrashReportStore {
    const val FILE_NAME = "crash_pending.txt"

    fun write(filesDir: File, thread: Thread, throwable: Throwable) {
        val file = File(filesDir, FILE_NAME)
        file.writeText(buildString {
            append("thread=").append(thread.name).append('\n')
            append(throwable.stackTraceToString())
        })
    }

    fun consume(filesDir: File): String? {
        val file = File(filesDir, FILE_NAME)
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return null
        } finally {
            file.delete()
        }
        return text.takeIf { it.isNotBlank() }
    }
}

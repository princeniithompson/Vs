package com.princeniithompson.rapcha.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType

data class ProcessExitRecord(
    val timestamp: Long,
    val reason: String,
    val description: String
)

/**
 * Logs historical process exits once. The same reason is not written again
 * on later launches.
 */
object ProcessExitDiagnostics {
    private const val TAG = "ProcessExitDiagnostics"
    private const val PREFS_NAME = "rapcha_exit_reasons"
    private const val KEY_SEEN = "seen_exit_fingerprints"
    private const val MAX_SEEN = 64
    private const val MAX_REASONS = 20

    fun fingerprint(record: ProcessExitRecord): String {
        return "${record.timestamp}|${record.reason}|${record.description}"
    }

    fun format(record: ProcessExitRecord): String {
        return "process_exit reason=${record.reason} description=${record.description} timestamp=${record.timestamp}"
    }

    fun selectNew(records: List<ProcessExitRecord>, alreadySeen: Set<String>): List<ProcessExitRecord> {
        val seen = alreadySeen.toMutableSet()
        return records.filter { record ->
            val key = fingerprint(record)
            if (key in seen) {
                false
            } else {
                seen.add(key)
                true
            }
        }
    }

    fun recordHistoricalExits(context: Context) {
        try {
            logNewExitReasons(context, readHistoricalExits(context))
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read historical process exits", t)
        }
    }

    fun logNewExitReasons(context: Context, records: List<ProcessExitRecord>) {
        if (records.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toMutableSet()
        val fresh = selectNew(records, seen)
        fresh.forEach { record ->
            AppLogRepository.logEvent(
                DiagnosticSource.APP,
                DiagnosticType.PROCESS_EXIT,
                format(record)
            )
            seen.add(fingerprint(record))
        }
        if (fresh.isEmpty()) return
        val capped = seen.sortedByDescending { fingerprintTimestamp(it) }.take(MAX_SEEN).toSet()
        prefs.edit().putStringSet(KEY_SEEN, HashSet(capped)).apply()
    }

    private fun fingerprintTimestamp(fingerprint: String): Long {
        return fingerprint.substringBefore('|').toLongOrNull() ?: 0L
    }

    private fun readHistoricalExits(context: Context): List<ProcessExitRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return Api30Reader.read(context)
    }

    private object Api30Reader {
        fun read(context: Context): List<ProcessExitRecord> {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            val infos = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_REASONS)
            return infos.map { info ->
                ProcessExitRecord(
                    timestamp = info.timestamp,
                    reason = reasonLabel(info.reason),
                    description = info.description?.toString().orEmpty()
                )
            }
        }

        private fun reasonLabel(reason: Int): String {
            return when (reason) {
                ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
                ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                ApplicationExitInfo.REASON_CRASH -> "CRASH"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                ApplicationExitInfo.REASON_OTHER -> "OTHER"
                ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
                ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
                ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
                else -> "REASON_$reason"
            }
        }
    }
}

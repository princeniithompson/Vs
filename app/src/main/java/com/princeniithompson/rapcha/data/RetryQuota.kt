package com.princeniithompson.rapcha.data

import android.content.Context
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Local free-tier counter for offline Retry. Resets at midnight Pacific time.
 * This is a client-side guard; Gemini remains the source of truth for 429s.
 */
object RetryQuota {
    const val DAILY_LIMIT = 25
    private const val PREFS = "rapcha_offline_retry"
    private const val KEY_DAY = "pacific_day"
    private const val KEY_USED = "used_count"
    private val PACIFIC: TimeZone = TimeZone.getTimeZone("America/Los_Angeles")

    fun pacificDayKey(nowMillis: Long): String {
        val calendar = Calendar.getInstance(PACIFIC, Locale.US)
        calendar.timeInMillis = nowMillis
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun remaining(context: Context, nowMillis: Long = System.currentTimeMillis()): Int {
        val used = usedCount(context, nowMillis)
        return (DAILY_LIMIT - used).coerceAtLeast(0)
    }

    fun recordAttempt(context: Context, nowMillis: Long = System.currentTimeMillis()): Int {
        val prefs = prefs(context)
        val day = pacificDayKey(nowMillis)
        val used = if (prefs.getString(KEY_DAY, null) == day) {
            prefs.getInt(KEY_USED, 0)
        } else {
            0
        }
        val next = (used + 1).coerceAtMost(DAILY_LIMIT)
        prefs.edit().putString(KEY_DAY, day).putInt(KEY_USED, next).commit()
        return (DAILY_LIMIT - next).coerceAtLeast(0)
    }

    fun markDailyExhausted(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putString(KEY_DAY, pacificDayKey(nowMillis))
            .putInt(KEY_USED, DAILY_LIMIT)
            .commit()
    }

    internal fun resetForTest(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun usedCount(context: Context, nowMillis: Long): Int {
        val prefs = prefs(context)
        val day = pacificDayKey(nowMillis)
        if (prefs.getString(KEY_DAY, null) != day) return 0
        return prefs.getInt(KEY_USED, 0).coerceAtLeast(0)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

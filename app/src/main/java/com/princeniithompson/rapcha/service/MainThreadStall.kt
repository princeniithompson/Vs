package com.princeniithompson.rapcha.service

/**
 * Main-thread stall check measured on the same clock as [android.os.Handler.postDelayed]
 * ([android.os.SystemClock.uptimeMillis]). Elapsed realtime keeps advancing while the
 * device sleeps, so it reports a stall every time the screen turns back on.
 * The watchdog is not scheduled while the screen is off.
 */
object MainThreadStall {
    const val INTERVAL_MS = 2_000L
    const val LATE_THRESHOLD_MS = 500L

    fun latenessMs(postedAtUptime: Long, nowUptime: Long, intervalMs: Long = INTERVAL_MS): Long {
        return nowUptime - postedAtUptime - intervalMs
    }

    fun shouldReport(latenessMs: Long): Boolean = latenessMs > LATE_THRESHOLD_MS

    fun shouldSchedule(screenInteractive: Boolean): Boolean = screenInteractive
}

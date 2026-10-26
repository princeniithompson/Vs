package com.princeniithompson.rapcha.service.floating

/**
 * One SHOWN and one HIDDEN per visibility change. Field focus and the keyboard
 * both ask the bubble to move, and those two calls used to log the same event twice.
 */
internal class BubbleSignalLog {
    private var visible: Boolean? = null

    fun shouldLog(shown: Boolean): Boolean {
        if (visible == shown) return false
        visible = shown
        return true
    }

    fun forget() {
        visible = null
    }
}

internal object BubbleTiming {
    fun latencyMs(signalAtElapsed: Long, nowElapsed: Long): Long {
        if (signalAtElapsed <= 0L) return 0L
        return (nowElapsed - signalAtElapsed).coerceAtLeast(0L)
    }
}

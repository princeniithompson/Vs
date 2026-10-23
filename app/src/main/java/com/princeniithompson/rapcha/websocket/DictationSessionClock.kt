package com.princeniithompson.rapcha.websocket

/**
 * Ten-minute dictation clock. It lives outside the websocket client so a
 * piece split or a fresh socket does not restart the session.
 */
class DictationSessionClock {
    private var warned = false
    private var armed = false
    private var stopped = false

    data class Tick(
        val warn: Boolean,
        val armFinishAtPause: Boolean,
        val hardStop: Boolean
    )

    fun onTick(sessionMs: Long, singleConnection: Boolean): Tick {
        val warn = !warned && sessionMs >= WARNING_MS
        if (warn) warned = true
        val arm = !armed && sessionMs >= PAUSE_FINISH_MS
        if (arm) armed = true
        val limit = if (singleConnection) SINGLE_CONNECTION_HARD_STOP_MS else HARD_STOP_MS
        val stop = !stopped && sessionMs >= limit
        if (stop) stopped = true
        return Tick(warn = warn, armFinishAtPause = arm, hardStop = stop)
    }

    fun shouldSplitOnGoAway(sessionMs: Long): Boolean {
        return !stopped && sessionMs < PAUSE_FINISH_MS
    }

    companion object {
        const val WARNING_MS = 9 * 60 * 1000L
        const val PAUSE_FINISH_MS = 9 * 60 * 1000L + 50_000L
        const val HARD_STOP_MS = 10 * 60 * 1000L
        const val SINGLE_CONNECTION_HARD_STOP_MS = 9 * 60 * 1000L + 55_000L
    }
}

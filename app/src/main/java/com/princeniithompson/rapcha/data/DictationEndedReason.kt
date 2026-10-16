package com.princeniithompson.rapcha.data

import java.util.Locale

/**
 * Maps a connection failure message onto a stable diagnostics reason.
 * A missing API key is not a dropped socket.
 */
object DictationEndedReason {
    const val MISSING_API_KEY = "missing_api_key"
    const val PING_TIMEOUT = "ping_timeout"
    const val CONNECTION_DROP = "connection_drop"

    fun fromMessage(message: String): String {
        val lower = message.lowercase(Locale.US)
        if (lower.contains("api key") || lower.contains("apikey") || lower.contains("placeholder")) {
            return MISSING_API_KEY
        }
        val pingTimeout = lower.contains("ping") || lower.contains("pong") || lower.contains("timeout")
        return if (pingTimeout) PING_TIMEOUT else CONNECTION_DROP
    }
}

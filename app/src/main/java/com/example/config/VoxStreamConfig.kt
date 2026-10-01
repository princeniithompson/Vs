package com.example.config

/**
 * VoxStream Central Configuration & Constants.
 * Provides a single source of truth for audio parameters, timeouts, model fallbacks,
 * and API key validation.
 */
object VoxStreamConfig {
    /** Audio chunk size in bytes (100ms of 16kHz 16-bit mono PCM) */
    const val AUDIO_CHUNK_SIZE: Int = 3200

    /** High-pass filter cutoff frequency in Hertz */
    const val HPF_CUTOFF_HZ: Float = 120f

    /** Ping interval for persistent WebSocket connection in seconds */
    const val PING_INTERVAL_SECONDS: Long = 45L

    /** Debounce interval for polish button taps in milliseconds */
    const val POLISH_DEBOUNCE_MS: Long = 800L

    /** Maximum auto-reconnect attempts for WebSocket */
    const val MAX_RECONNECT_ATTEMPTS: Int = 3

    /** Default Gemini Live Transcribe model */
    const val DEFAULT_LIVE_MODEL: String = "models/gemini-2.0-flash-exp"

    /** Placeholder API key template value */
    const val PLACEHOLDER_API_KEY: String = "MY_GEMINI_API_KEY"

    /**
     * Gemini REST/Polish Model Fallback Chain in prioritized order.
     */
    val GEMINI_MODEL_FALLBACKS: List<String> = listOf(
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.0-flash",
        "gemini-1.5-flash"
    )

    /**
     * Case-insensitive, whitespace-trimmed API key validation check.
     * Returns true if the key is null, empty, or equals the placeholder string.
     */
    fun isPlaceholderApiKey(apiKey: String?): Boolean {
        if (apiKey.isNullOrBlank()) return true
        val trimmed = apiKey.trim()
        return trimmed.isEmpty() || trimmed.equals(PLACEHOLDER_API_KEY, ignoreCase = true)
    }

    /**
     * Returns true if the API key is a non-empty, non-placeholder valid key string.
     */
    fun isValidApiKey(apiKey: String?): Boolean {
        return !isPlaceholderApiKey(apiKey)
    }
}

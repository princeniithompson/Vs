package com.princeniithompson.rapcha.config

/**
 * Rapcha Central Configuration & Constants.
 * Provides a single source of truth for audio parameters, timeouts, model fallbacks,
 * and API key validation.
 */
object RapchaConfig {
    /** Audio chunk size in bytes (100ms of 16kHz 16-bit mono PCM) */
    const val AUDIO_CHUNK_SIZE: Int = 3200

    /** High-pass filter cutoff frequency in Hertz */
    const val HPF_CUTOFF_HZ: Float = 75f

    /** Ping interval for persistent WebSocket connection in seconds (25s to avoid cellular carrier NAT timeouts) */
    const val PING_INTERVAL_SECONDS: Long = 25L

    /** Debounce interval for polish button taps in milliseconds */
    const val POLISH_DEBOUNCE_MS: Long = 800L

    /** Maximum auto-reconnect attempts for WebSocket */
    const val MAX_RECONNECT_ATTEMPTS: Int = 3

    /** Feature flag: Application detection (enabled per user request; layout space preserved) */
    const val IS_APP_DETECTION_ENABLED: Boolean = true

    /** Default Gemini Live Transcribe model */
    const val DEFAULT_LIVE_MODEL: String = "models/gemini-2.0-flash-exp"

    /** Primary Gemini REST/Polish Model */
    const val POLISH_PRIMARY_MODEL: String = "gemini-3.5-flash-lite"

    /** Non-live model used when Live fails and the user taps Retry. */
    const val TRANSCRIBE_MODEL: String = "gemini-3.5-transcribe"

    /**
     * Stage-2 model when Live two-step is on. Text turn on the same
     * BidiGenerateContent socket as transcription. The Polish button calls this
     * model directly. It is not a unary flash model.
     */
    const val STAGE2_LIVE_MODEL: String = "models/gemini-3.8-live"

    /**
     * Wait for setupComplete. This is separate from the no-token clock.
     * live-polish-4 started the 4.5s clock at connect, so a slow handshake
     * was reported as "timed out before setup" with an empty frame list.
     */
    const val STAGE2_LIVE_SETUP_MS: Long = 12_000L

    /**
     * After the text turn is sent, give up if no transcript chunk arrives.
     * Chunks that are already flowing are not cut by this clock.
     */
    const val STAGE2_LIVE_TIMEOUT_MS: Long = 6_000L

    /** Return accumulated transcript this long after the last chunk if turnComplete is late. */
    const val STAGE2_LIVE_IDLE_MS: Long = 700L

    /** Absolute cap while transcript chunks are still arriving. Not an empty wait. */
    const val STAGE2_LIVE_HARD_CAP_MS: Long = 12_000L

    /** Same debounce as Polish so a double tap cannot spend two free-tier retries. */
    const val RETRY_DEBOUNCE_MS: Long = POLISH_DEBOUNCE_MS

    /** Placeholder API key template value */
    const val PLACEHOLDER_API_KEY: String = "MY_GEMINI_API_KEY"

    /**
     * Gemini REST/Polish Model Fallback Chain in prioritized order.
     */
    val GEMINI_MODEL_FALLBACKS: List<String> = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.8-flash"
    )

    /**
     * Case-insensitive, whitespace-trimmed API key validation check.
     * Returns true if the key is null, empty, or equals the placeholder string.
     */
    fun isPlaceholderApiKey(apiKey: String?): Boolean {
        return com.princeniithompson.rapcha.core.ApiConfig.isPlaceholder(apiKey)
    }

    /**
     * Returns true if the API key is a non-empty, non-placeholder valid key string.
     */
    fun isValidApiKey(apiKey: String?): Boolean {
        return !isPlaceholderApiKey(apiKey)
    }
}

package com.princeniithompson.rapcha.websocket

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.princeniithompson.rapcha.core.ApiConfig
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.ConnectionState
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.data.DictationEndedReason
import com.princeniithompson.rapcha.data.LogLevel
import com.princeniithompson.rapcha.data.TranscriptPrivacy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Structured error types for Gemini Live WebSocket communication.
 */
sealed class GeminiLiveError(open val message: String, open val cause: Throwable? = null) {
    data class MissingApiKey(override val message: String) : GeminiLiveError(message)
    data class NetworkFailure(override val message: String, override val cause: Throwable? = null) : GeminiLiveError(message, cause)
    data class ServerError(val code: Int, val status: String, override val message: String) : GeminiLiveError(message)
    data class ProtocolError(override val message: String, override val cause: Throwable? = null) : GeminiLiveError(message, cause)
    data class SetupFailed(override val message: String) : GeminiLiveError(message)
}

class GeminiLiveWebSocketClient(
    private val onSetupComplete: () -> Unit,
    private val onInterimTranscription: (String) -> Unit,
    private val onFinalizedTranscription: (String) -> Unit,
    private val onStateChanged: (ConnectionState) -> Unit,
    private val onLog: (LogLevel, String, String, String?) -> Unit,
    private val onError: (String) -> Unit,
    okHttpClient: OkHttpClient? = null,
    private val onStructuredError: ((GeminiLiveError) -> Unit)? = null,
    private val isSessionActive: () -> Boolean = { false },
    private val onSessionLimit: (message: String, finalizeNow: Boolean) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "GeminiLiveWS"
        const val DEFAULT_MODEL = "models/gemini-3.5-transcribe-live"
        const val WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val MAX_RECONNECT_ATTEMPTS = 10
        const val PING_INTERVAL_SECONDS = 45L

        /**
         * gemini-3.5-transcribe-live documents a 10 minute continuous session.
         * Warn before that, then finish the utterance while there is still time
         * for the final transcript to arrive.
         */
        const val SESSION_LIMIT_WARNING_MS = 9 * 60 * 1000L
        const val SESSION_LIMIT_FINALIZE_MS = 9 * 60 * 1000L + 30_000L
        const val FINAL_TRANSCRIPT_TIMEOUT_MS = 8_000L
        const val GO_AWAY_FINALIZE_THRESHOLD_MS = 45_000L

        // Single-connection enforcement and instance tracking
        private val activeSocketCount = AtomicInteger(0)
        private val currentActiveClient = AtomicReference<GeminiLiveWebSocketClient?>(null)

        fun getActiveSocketCount(): Int = activeSocketCount.get()

        internal fun releaseActiveSocket(): Int = activeSocketCount.updateAndGet { max(0, it - 1) }

        internal fun debugSetActiveSocketCount(value: Int) {
            activeSocketCount.set(value.coerceAtLeast(0))
        }

        fun terminateActiveLiveSockets() {
            val client = currentActiveClient.getAndSet(null)
            try {
                client?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Error in terminateActiveLiveSockets", e)
            }
        }

        fun buildWebSocketRequest(apiKey: String): Request {
            val trimmedKey = apiKey.trim()
            if (ApiConfig.isPlaceholder(trimmedKey)) {
                throw IllegalArgumentException("Gemini API Key is missing or placeholder. Please provide a valid key in Secrets or Settings.")
            }
            return Request.Builder()
                .url(WS_BASE_URL)
                .addHeader("x-goog-api-key", trimmedKey)
                .build()
        }
    }

    private val client: OkHttpClient = okHttpClient ?: ApiConfig.createOkHttpClient()
        .newBuilder()
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isSetupComplete = AtomicBoolean(false)
    private val isConnectingGuard = AtomicBoolean(false)
    private val isExplicitlyClosed = AtomicBoolean(false)
    private val hasDisconnected = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private var activeModel: String = DEFAULT_MODEL
    private var lastApiKey: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingReconnectRunnable: Runnable? = null
    private val activeTurnText = StringBuilder()

    private val activityStarted = AtomicBoolean(false)
    private val activityEnded = AtomicBoolean(false)
    private val endpointSettled = AtomicBoolean(false)
    private val sessionClockStarted = AtomicBoolean(false)
    private val sessionLimitWarned = AtomicBoolean(false)
    private val sessionLimitFinalized = AtomicBoolean(false)
    @Volatile private var lastInterimText: String = ""
    @Volatile private var resolvedEndpointText: String = ""
    private val endpointContinuation = AtomicReference<CancellableContinuation<String>?>(null)
    private var endpointTimeoutRunnable: Runnable? = null
    private var sessionWarningRunnable: Runnable? = null
    private var sessionFinalizeRunnable: Runnable? = null

    val setupComplete: Boolean
        get() = isSetupComplete.get()

    val isConnecting: Boolean
        get() = isConnectingGuard.get()

    val isReconnecting: Boolean
        get() = !isExplicitlyClosed.get() && reconnectAttempts.get() > 0 && !isSetupComplete.get()

    val activeSessionExists: Boolean
        get() = webSocket != null

    private var isSmartMode: Boolean = false
    private var customVocabularyList: List<String> = emptyList()

    /**
     * Connect to Gemini Live WebSocket.
     * Guarded with AtomicBoolean to prevent race conditions and concurrent connection attempts.
     */
    fun connect(
        apiKey: String,
        model: String = DEFAULT_MODEL,
        smartMode: Boolean = false,
        customVocabulary: List<String> = emptyList()
    ) {
        val trimmedKey = apiKey.trim()
        if (ApiConfig.isPlaceholder(trimmedKey)) {
            val errMsg = "Gemini API Key is missing or placeholder. Please provide a valid key in Secrets or Settings."
            Log.e(TAG, "[ConnectionState] FAILED: $errMsg")
            onLog(LogLevel.ERROR, TAG, errMsg, null)
            notifyError(errMsg, GeminiLiveError.MissingApiKey(errMsg))
            notifyStateChanged(ConnectionState.Error(errMsg))
            throw IllegalArgumentException(errMsg)
        }

        // Concurrency guard: atomic check-and-set prevents duplicate or overlapping connections
        if (!isConnectingGuard.compareAndSet(false, true)) {
            Log.w(TAG, "Connection attempt ignored: connection is already in progress or session active")
            return
        }

        // Single-connection enforcement: Terminate any previously active client instance across the app
        val previousClient = currentActiveClient.getAndSet(this)
        if (previousClient != null && previousClient !== this) {
            Log.w(TAG, "[SingleConnection] Disconnecting prior Gemini Live client instance to enforce single live connection")
            try {
                previousClient.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Error terminating previous client instance", e)
            }
        }

        // Cancel any pending auto-reconnect before initiating a new connection
        cancelPendingReconnect()

        // Ensure only one WebSocket session can exist at a time by closing any existing socket
        closeExistingWebSocket()

        val resolvedModel = model.ifBlank { DEFAULT_MODEL }.trim()

        lastApiKey = trimmedKey
        activeModel = resolvedModel
        isSmartMode = smartMode
        customVocabularyList = customVocabulary
        hasDisconnected.set(false)
        isExplicitlyClosed.set(false)
        reconnectAttempts.set(0)
        isSetupComplete.set(false)
        resetManualEndpointState()
        Log.i(TAG, "[ConnectionState] CONNECTING to $WS_BASE_URL ($activeModel)...")
        notifyStateChanged(ConnectionState.Connecting)

        connectInternal()
    }

    private fun connectInternal() {
        if (isExplicitlyClosed.get()) {
            isConnectingGuard.set(false)
            return
        }

        // Cancel any pending reconnect runnable and tear down any lingering socket
        cancelPendingReconnect()
        closeExistingWebSocket()

        val request = try {
            buildWebSocketRequest(lastApiKey)
        } catch (e: Exception) {
            isConnectingGuard.set(false)
            val errMsg = "Failed to build WebSocket request: ${e.message}"
            Log.e(TAG, "[ConnectionState] FAILED building request: $errMsg", e)
            notifyError(errMsg, GeminiLiveError.MissingApiKey(errMsg))
            notifyStateChanged(ConnectionState.Error(errMsg))
            return
        }

        val modeLabel = if (isSmartMode) "SMART (filler-cleanup + punctuation)" else "VERBATIM (raw fastest)"
        val vocabCount = customVocabularyList.size
        val attemptNum = reconnectAttempts.get()
        if (attemptNum == 0) {
            Log.i(TAG, "[ConnectionState] CONNECTING: Opening socket to $WS_BASE_URL ($activeModel) [mode=$modeLabel, vocab=$vocabCount, pingInterval=${PING_INTERVAL_SECONDS}s]")
            onLog(LogLevel.INFO, TAG, "Opening WebSocket connection to $WS_BASE_URL ($activeModel) in $modeLabel mode with $vocabCount custom vocabulary terms (pingInterval=${PING_INTERVAL_SECONDS}s)", null)
        } else {
            Log.i(TAG, "[ConnectionState] RECONNECTING: Attempt $attemptNum/$MAX_RECONNECT_ATTEMPTS to $WS_BASE_URL ($activeModel)...")
            onLog(LogLevel.INFO, TAG, "Reconnecting WebSocket (attempt $attemptNum/$MAX_RECONNECT_ATTEMPTS) to $WS_BASE_URL ($activeModel)...", null)
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isExplicitlyClosed.get()) {
                    Log.d(TAG, "[ConnectionState] onOpen received but client explicitly closed, closing socket immediately")
                    webSocket.close(1000, "Closed by client")
                    isConnectingGuard.set(false)
                    return
                }

                val currentLiveCount = activeSocketCount.incrementAndGet()
                activeTurnText.clear()
                if (!activityEnded.get()) {
                    activityStarted.set(false)
                }
                val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                val openMsg = "[$timeStr] WebSocket open (HTTP ${response.code}). Active live sockets: $currentLiveCount"
                Log.i(TAG, "[ConnectionState] CONNECTED: $openMsg")
                onLog(LogLevel.INFO, TAG, "WebSocket connected successfully (HTTP ${response.code}). Active sockets: $currentLiveCount. Sending initial setup payload ($modeLabel, vocab=$vocabCount)...", null)
                notifyStateChanged(ConnectionState.ConnectedWaitingSetup)

                // Step A: Send initial setup JSON
                val transcriptionConfig = JSONObject().apply {
                    if (isSmartMode) {
                        // AudioTranscriptionConfig.mode is documented for gemini-3.5-transcribe-live.
                        put("mode", "SMART")
                    }
                    if (customVocabularyList.isNotEmpty()) {
                        val vocabArray = org.json.JSONArray()
                        customVocabularyList.forEach { term ->
                            val trimmed = term.trim()
                            if (trimmed.isNotEmpty()) {
                                vocabArray.put(trimmed)
                            }
                        }
                        put("customVocabulary", vocabArray)
                    }
                }

                val systemPromptText = buildString {
                    append("You are an ultra-accurate real-time voice dictation engine. Transcribe ONLY the words spoken by the primary user closest to the microphone. Completely ignore background ambient sounds, restaurant noise, kitchen clatter, passing vehicles, and background conversations. Never transcribe non-speech sounds. Output only the transcribed text with proper capitalization and punctuation. Do not reply conversationally, do not answer questions, and do not add commentary—only output the verbatim transcription of what was said.")
                    if (customVocabularyList.isNotEmpty()) {
                        val termsStr = customVocabularyList.filter { it.isNotBlank() }.joinToString(", ")
                        if (termsStr.isNotBlank()) {
                            append(" Prioritize and accurately transcribe the following custom vocabulary terms, names, and email addresses with exact casing and spelling: $termsStr.")
                        }
                    }
                }

                val setupJson = JSONObject().apply {
                    val setupObj = JSONObject().apply {
                        put("model", activeModel)
                        put("systemInstruction", JSONObject().apply {
                            put("parts", org.json.JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", systemPromptText)
                                })
                            })
                        })
                        put("generationConfig", JSONObject().apply {
                            put("responseModalities", org.json.JSONArray().apply {
                                put("TEXT")
                            })
                        })
                        put("inputAudioTranscription", transcriptionConfig)
                        put("realtimeInputConfig", JSONObject().apply {
                            put("automaticActivityDetection", JSONObject().apply {
                                put("disabled", true)
                            })
                        })
                    }
                    put("setup", setupObj)
                }

                val payload = setupJson.toString()
                val sent = webSocket.send(payload)
                if (sent) {
                    val vadDiagnostics = "[Manual VAD] automaticActivityDetection.disabled=true; activityEnd is sent only when the user completes"
                    Log.i(TAG, vadDiagnostics)
                    onLog(LogLevel.SENT, TAG, "Setup message sent to server ($vadDiagnostics)", payload)
                } else {
                    val err = "Failed to send setup message over socket"
                    Log.e(TAG, "[ConnectionState] FAILED: $err")
                    onLog(LogLevel.ERROR, TAG, err, payload)
                    notifyStateChanged(ConnectionState.Error(err))
                    notifyError(err, GeminiLiveError.SetupFailed(err))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val text = bytes.utf8()
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                val reasonDetail = if (reason.isNotBlank()) reason else "Normal shutdown"
                Log.d(TAG, "[ConnectionState] DISCONNECTING: WebSocket closing (code: $code, reason: $reasonDetail)")
                onLog(LogLevel.INFO, TAG, "WebSocket closing (code: $code, reason: $reasonDetail)", null)
                if (!isExplicitlyClosed.get()) {
                    notifyStateChanged(ConnectionState.Stopping)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val currentLiveCount = releaseActiveSocket()
                val reasonDetail = if (reason.isNotBlank()) reason else "No reason provided"
                Log.i(TAG, "[SingleConnection] WebSocket closed (code: $code, reason: $reasonDetail). Active live sockets: $currentLiveCount")
                isSetupComplete.set(false)

                if (isExplicitlyClosed.get()) {
                    isConnectingGuard.set(false)
                    lastApiKey = ""
                    onLog(LogLevel.INFO, TAG, "WebSocket closed cleanly (code: $code, reason: $reasonDetail)", null)
                    notifyStateChanged(ConnectionState.Idle)
                } else {
                    handleDisconnectOrFailure("WebSocket closed (code: $code, reason: $reasonDetail)", null)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val currentLiveCount = releaseActiveSocket()
                Log.i(TAG, "[SingleConnection] WebSocket failure: ${t.message}. Active live sockets: $currentLiveCount")

                val primaryMsg = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                val endedReason = DictationEndedReason.fromMessage(primaryMsg)

                val errorDetails = buildString {
                    append("ended_reason: $endedReason | WebSocket failure: ")
                    append(primaryMsg)
                    if (response != null) {
                        append(" [HTTP ${response.code}: ${response.message}]")
                        try {
                            val body = response.body?.string()
                            if (!body.isNullOrBlank()) {
                                append(" - Response: ${body.take(200)}")
                            }
                        } catch (_: Exception) {}
                    }
                    val cause = t.cause
                    if (cause != null && cause.message != t.message) {
                        append(" (Caused by: ${cause.message ?: cause.javaClass.simpleName})")
                    }
                }

                Log.w(TAG, "[ConnectionState] FAILURE detected: $errorDetails", t)
                handleDisconnectOrFailure(errorDetails, t)
            }
        })
    }

    private fun handleDisconnectOrFailure(errorDetails: String, throwable: Throwable?) {
        if (activityEnded.get()) {
            settleEndpoint(lastInterimText)
        }
        if (isExplicitlyClosed.get() || activityEnded.get()) {
            isConnectingGuard.set(false)
            return
        }

        isSetupComplete.set(false)

        if (!isSessionActive()) {
            isConnectingGuard.set(false)
            cancelPendingReconnect()
            val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val idleMsg = "[$timeStr] Websocket closed while no dictation is active; reconnect suppressed | $errorDetails"
            Log.i(TAG, "[ConnectionState] IDLE: $idleMsg")
            onLog(LogLevel.INFO, TAG, idleMsg, null)
            notifyStateChanged(ConnectionState.Idle)
            return
        }

        val sessionStillActive = true
        val attempts = reconnectAttempts.incrementAndGet()

        if (lastApiKey.isNotBlank()) {
            // True exponential backoff with jitter:
            // delay = min(30000, base * 2^(attempt-1)) + random(0..500 ms)
            // base starts at 1000 ms.
            val attemptExponent = (attempts - 1).coerceIn(0, 15)
            val rawBackoff = (1000L * (1L shl attemptExponent)).coerceAtMost(30000L)
            val jitter = Random.nextLong(0, 501)
            val backoffMs = (rawBackoff + jitter).coerceAtMost(30500L)

            val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val reconnectLog = "[$timeStr][Reconnect] Attempt $attempts (delay: ${backoffMs}ms, base: ${rawBackoff}ms, jitter: ${jitter}ms) | sessionActive: $sessionStillActive | activeSockets: ${activeSocketCount.get()} | reason: $errorDetails"
            Log.i(TAG, "[ConnectionState] RECONNECTING: $reconnectLog")
            onLog(LogLevel.INFO, TAG, reconnectLog, null)
            AppLogRepository.logEvent(
                DiagnosticSource.BUBBLE,
                DiagnosticType.SESSION_RECONNECT,
                reconnectLog
            )
            notifyStateChanged(ConnectionState.Connecting)

            cancelPendingReconnect()
            val runnable = Runnable {
                if (!isExplicitlyClosed.get() && isSessionActive()) {
                    connectInternal()
                } else {
                    isConnectingGuard.set(false)
                }
            }
            pendingReconnectRunnable = runnable
            mainHandler.postDelayed(runnable, backoffMs)
            return
        }

        // Retries exhausted (only when session was not active) or non-retryable fatal error
        isConnectingGuard.set(false)
        lastApiKey = ""
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val fatalMsg = "[$timeStr][Reconnect] Failed permanently after $attempts attempts: $errorDetails"
        Log.e(TAG, "[ConnectionState] FAILED: $fatalMsg", throwable)
        onLog(LogLevel.ERROR, TAG, fatalMsg, null)
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.ERROR,
            "WebSocket failed permanently after $attempts reconnect attempts: ${errorDetails.take(120)}"
        )
        notifyStateChanged(ConnectionState.Error(fatalMsg))
        notifyError(fatalMsg, GeminiLiveError.NetworkFailure(fatalMsg, throwable))
    }

    private fun handleIncomingMessage(rawJson: String) {
        try {
            val json = JSONObject(rawJson)

            // Step B: Check for setupComplete
            if (json.has("setupComplete")) {
                isSetupComplete.set(true)
                val wasReconnecting = reconnectAttempts.get() > 0
                val attemptsUsed = reconnectAttempts.get()
                reconnectAttempts.set(0)
                Log.i(TAG, "[ConnectionState] STREAMING: setupComplete acknowledged by Gemini Live server!")
                val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                val ackMsg = if (wasReconnecting) {
                    "[$timeStr][Reconnect] Successfully recovered and reconnected after $attemptsUsed attempts! Active sockets: ${activeSocketCount.get()}"
                } else {
                    "setupComplete acknowledged by Gemini Live server!"
                }
                onLog(LogLevel.RECEIVED, TAG, ackMsg, rawJson)
                if (wasReconnecting) {
                    AppLogRepository.logEvent(
                        DiagnosticSource.BUBBLE,
                        DiagnosticType.SESSION_RECONNECT,
                        ackMsg
                    )
                }
                notifyStateChanged(ConnectionState.Streaming)
                scheduleSessionLimitClock()
                notifySetupComplete()
                return
            }

            val goAway = json.optJSONObject("goAway")
            if (goAway != null) {
                handleGoAway(goAway)
            }

            // Check for server errors
            if (json.has("error")) {
                val errorObj = json.optJSONObject("error")
                val code = errorObj?.optInt("code", 0) ?: 0
                val status = errorObj?.optString("status") ?: ""
                val errMsg = errorObj?.optString("message") ?: json.optString("error")
                val detailedError = buildString {
                    append("Gemini Live server error")
                    if (code != 0) append(" (code $code)")
                    if (status.isNotBlank()) append(" [$status]")
                    append(": $errMsg")
                }
                Log.e(TAG, "[ConnectionState] SERVER_ERROR: $detailedError")
                onLog(LogLevel.ERROR, TAG, detailedError, rawJson)
                notifyStateChanged(ConnectionState.Error(detailedError))
                notifyError(detailedError, GeminiLiveError.ServerError(code, status, detailedError))
                return
            }

            // Step C & frame text extraction
            val serverContent = json.optJSONObject("serverContent")
            if (serverContent != null) {
                // 1. Model text response parts (from live model turn)
                val modelTurn = serverContent.optJSONObject("modelTurn")
                val parts = modelTurn?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i)
                        val text = part?.optString("text")
                        if (!text.isNullOrEmpty()) {
                            activeTurnText.append(text)
                        }
                    }
                    val currentTurn = activeTurnText.toString()
                    if (currentTurn.isNotEmpty()) {
                        onLog(LogLevel.RECEIVED, TAG, "Live Text: ${TranscriptPrivacy.summary(currentTurn)}", null)
                        notifyInterimTranscription(currentTurn)
                    }
                }

                // Interim hypotheses stay in the live preview for the whole activity.
                // inputTranscription is authoritative only after the user presses Complete
                // (activityEnd). Silence must not commit or split the utterance.
                val interimObj = serverContent.optJSONObject("interimInputTranscription")
                val interimText = interimObj?.optString("text").orEmpty()
                if (interimText.isNotEmpty()) {
                    onLog(LogLevel.RECEIVED, TAG, "Interim: ${TranscriptPrivacy.summary(interimText)}", null)
                    notifyInterimTranscription(interimText)
                }

                val finalizedObj = serverContent.optJSONObject("inputTranscription")
                val finalizedText = finalizedObj?.optString("text").orEmpty()
                val turnFinished = serverContent.optBoolean("turnComplete", false) ||
                    serverContent.optBoolean("interrupted", false)

                if (!activityEnded.get()) {
                    if (finalizedText.isNotEmpty()) {
                        onLog(LogLevel.RECEIVED, TAG, "Preview update before activityEnd: ${TranscriptPrivacy.summary(finalizedText)}", null)
                        notifyInterimTranscription(finalizedText)
                    }
                    if (turnFinished) {
                        activeTurnText.clear()
                    }
                } else if (finalizedText.isNotEmpty()) {
                    onLog(LogLevel.RECEIVED, TAG, "Finalized: ${TranscriptPrivacy.summary(finalizedText)}", null)
                    settleEndpoint(finalizedText)
                } else if (turnFinished) {
                    val completedText = activeTurnText.toString().trim()
                    activeTurnText.clear()
                    if (completedText.isNotEmpty()) {
                        onLog(LogLevel.INFO, TAG, "Turn completed: ${TranscriptPrivacy.summary(completedText)}", null)
                        settleEndpoint(completedText)
                    }
                }
            } else {
                onLog(
                    LogLevel.RECEIVED,
                    TAG,
                    "Server message: ${TranscriptPrivacy.maskSensitive(rawJson.take(120))}...",
                    null
                )
            }
        } catch (e: Exception) {
            val err = "Error parsing incoming frame JSON: ${e.message}"
            Log.e(TAG, err, e)
            onLog(LogLevel.ERROR, TAG, "JSON parse error: ${e.message}", TranscriptPrivacy.maskSensitive(rawJson).take(180))
            notifyError(err, GeminiLiveError.ProtocolError(err, e))
        }
    }

    /**
     * Step C: Send 100ms PCM chunk Base64-encoded via realtimeInput.mediaChunks
     * CRITICAL: Must only be called once isSetupComplete is true.
     */
    fun sendAudioChunk(pcmChunk: ByteArray): Boolean {
        val ws = webSocket
        if (ws == null || !isSetupComplete.get() || isExplicitlyClosed.get() || activityEnded.get()) {
            return false
        }
        if (!signalActivityStart()) {
            return false
        }

        return try {
            val base64Data = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
            val chunkJson = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    val mediaChunksArray = org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", base64Data)
                        })
                    }
                    put("mediaChunks", mediaChunksArray)
                })
            }
            ws.send(chunkJson.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Error sending audio chunk (socket disconnected or buffer full): ${e.message}")
            false
        }
    }

    /**
     * Send a 100ms zero-PCM padding chunk during audio recorder session restarts
     * to keep WebSocket VAD state continuously alive without dropouts.
     */
    fun sendZeroPaddingChunk(): Boolean {
        val zeroBuffer = ByteArray(3200) // 100ms at 16kHz mono 16-bit PCM = 3200 bytes
        return sendAudioChunk(zeroBuffer)
    }

    /**
     * Opens the manual activity. Safe to call more than once; audio chunks also call this
     * so the first frame is never sent outside an activity.
     */
    fun signalActivityStart(): Boolean {
        if (activityEnded.get() || isExplicitlyClosed.get() || !isSetupComplete.get()) return false
        if (activityStarted.get()) return true
        val ws = webSocket ?: return false
        return try {
            val startJson = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("activityStart", JSONObject())
                })
            }
            val sent = ws.send(startJson.toString())
            if (sent) {
                activityStarted.set(true)
                onLog(LogLevel.SENT, TAG, "Sent activityStart", startJson.toString())
            }
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Error sending activityStart", e)
            false
        }
    }

    /**
     * Ends the manual activity. This is the only endpoint signal, and callers must use it
     * only when the user presses Complete or the session limit is reached.
     * audioStreamEnd is not used while automatic activity detection is disabled.
     */
    fun signalActivityEnd(): Boolean {
        val firstEnd = activityEnded.compareAndSet(false, true)
        cancelSessionLimitTimers()
        val ws = webSocket
        if (!firstEnd) {
            return ws != null && isSetupComplete.get() && !isExplicitlyClosed.get()
        }
        if (ws == null || !isSetupComplete.get() || isExplicitlyClosed.get()) return false
        return try {
            val endJson = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("activityEnd", JSONObject())
                })
            }
            val sent = ws.send(endJson.toString())
            if (sent) {
                onLog(LogLevel.SENT, TAG, "Sent activityEnd", endJson.toString())
            }
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Error sending activityEnd", e)
            false
        }
    }

    /**
     * Sends activityEnd and suspends until the authoritative transcript arrives.
     * If the server does not answer in time, the last interim preview is returned.
     */
    suspend fun endActivityAndAwaitFinal(): String {
        if (endpointSettled.get()) return resolvedEndpointText.ifBlank { lastInterimText.trim() }
        return suspendCancellableCoroutine { cont ->
            endpointContinuation.set(cont)
            cont.invokeOnCancellation {
                endpointContinuation.compareAndSet(cont, null)
            }
            val sent = signalActivityEnd()
            if (!sent) {
                settleEndpoint(lastInterimText)
                return@suspendCancellableCoroutine
            }
            val timeout = Runnable { settleEndpoint(lastInterimText) }
            endpointTimeoutRunnable = timeout
            mainHandler.postDelayed(timeout, FINAL_TRANSCRIPT_TIMEOUT_MS)
        }
    }

    /**
     * Kept so older stop paths cannot emit audioStreamEnd. Manual VAD must not
     * send that signal; it would let the server close the turn on its own.
     */
    fun signalStreamEnd() {
        Log.i(TAG, "audioStreamEnd suppressed; activityEnd is sent only when the user completes")
    }

    fun disconnect() {
        if (!hasDisconnected.compareAndSet(false, true)) {
            Log.d(TAG, "[ConnectionState] disconnect() already invoked; skipping duplicate call")
            return
        }
        Log.i(TAG, "[ConnectionState] DISCONNECT requested by client (deterministic teardown)")
        isExplicitlyClosed.set(true)
        reconnectAttempts.set(MAX_RECONNECT_ATTEMPTS)
        cancelPendingReconnect()
        cancelSessionLimitTimers()
        if (activityEnded.get()) {
            settleEndpoint(lastInterimText)
        }
        closeExistingWebSocket()

        currentActiveClient.compareAndSet(this, null)

        isSetupComplete.set(false)
        isConnectingGuard.set(false)
        lastApiKey = ""
        notifyStateChanged(ConnectionState.Idle)
    }

    private fun cancelPendingReconnect() {
        pendingReconnectRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        pendingReconnectRunnable = null
    }

    private fun closeExistingWebSocket() {
        val existing = webSocket
        webSocket = null
        if (existing != null) {
            try {
                val count = releaseActiveSocket()
                Log.i(TAG, "[SingleConnection] Existing WebSocket cancelled. Active live sockets: $count")
                try {
                    existing.close(1000, "Session ended")
                } catch (_: Exception) {}
                existing.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling existing WebSocket: ${e.message}")
            }
        }
    }

    private fun notifyError(message: String, structuredError: GeminiLiveError? = null) {
        val structured = structuredError ?: GeminiLiveError.NetworkFailure(message)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onError(message)
            onStructuredError?.invoke(structured)
        } else {
            mainHandler.post {
                onError(message)
                onStructuredError?.invoke(structured)
            }
        }
    }

    private fun notifyStateChanged(state: ConnectionState) {
        val stateName = when (state) {
            is ConnectionState.Idle -> "IDLE"
            is ConnectionState.Connecting -> if (reconnectAttempts.get() > 0) "RECONNECTING" else "CONNECTING"
            is ConnectionState.ConnectedWaitingSetup -> "CONNECTED_WAITING_SETUP"
            is ConnectionState.Streaming -> "STREAMING"
            is ConnectionState.Stopping -> "STOPPING"
            is ConnectionState.Error -> "FAILED"
        }
        Log.i(TAG, "[ConnectionState] State updated: $stateName")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onStateChanged(state)
        } else {
            mainHandler.post { onStateChanged(state) }
        }
    }

    private fun notifySetupComplete() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onSetupComplete()
        } else {
            mainHandler.post { onSetupComplete() }
        }
    }

    private fun notifyInterimTranscription(text: String) {
        if (text.isNotEmpty()) {
            lastInterimText = text
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onInterimTranscription(text)
        } else {
            mainHandler.post { onInterimTranscription(text) }
        }
    }

    private fun resetManualEndpointState() {
        activityStarted.set(false)
        activityEnded.set(false)
        endpointSettled.set(false)
        sessionClockStarted.set(false)
        sessionLimitWarned.set(false)
        sessionLimitFinalized.set(false)
        lastInterimText = ""
        resolvedEndpointText = ""
        endpointContinuation.set(null)
        cancelSessionLimitTimers()
        endpointTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        endpointTimeoutRunnable = null
    }

    private fun scheduleSessionLimitClock() {
        if (!sessionClockStarted.compareAndSet(false, true)) return
        val warning = Runnable {
            notifySessionLimit(
                "Rapcha's live session is nearing its 10 minute limit. Press Complete soon.",
                finalizeNow = false
            )
        }
        val finalize = Runnable {
            notifySessionLimit(
                "Rapcha is finishing this dictation before the 10 minute live session limit.",
                finalizeNow = true
            )
        }
        sessionWarningRunnable = warning
        sessionFinalizeRunnable = finalize
        mainHandler.postDelayed(warning, SESSION_LIMIT_WARNING_MS)
        mainHandler.postDelayed(finalize, SESSION_LIMIT_FINALIZE_MS)
    }

    private fun cancelSessionLimitTimers() {
        sessionWarningRunnable?.let { mainHandler.removeCallbacks(it) }
        sessionFinalizeRunnable?.let { mainHandler.removeCallbacks(it) }
        sessionWarningRunnable = null
        sessionFinalizeRunnable = null
    }

    private fun handleGoAway(goAway: JSONObject) {
        val timeLeftMs = parseDurationMillis(goAway.opt("timeLeft"))
        val finalizeNow = timeLeftMs == null || timeLeftMs <= GO_AWAY_FINALIZE_THRESHOLD_MS
        val message = if (finalizeNow) {
            "Rapcha is finishing this dictation because the live session is about to close."
        } else {
            "Rapcha's live session will close soon. Press Complete to keep this dictation."
        }
        onLog(LogLevel.INFO, TAG, "goAway timeLeftMs=$timeLeftMs finalize=$finalizeNow", goAway.toString())
        notifySessionLimit(message, finalizeNow)
    }

    private fun notifySessionLimit(message: String, finalizeNow: Boolean) {
        if (finalizeNow) {
            if (!sessionLimitFinalized.compareAndSet(false, true)) return
            cancelSessionLimitTimers()
        } else if (!sessionLimitWarned.compareAndSet(false, true)) {
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onSessionLimit(message, finalizeNow)
        } else {
            mainHandler.post { onSessionLimit(message, finalizeNow) }
        }
    }

    private fun settleEndpoint(text: String) {
        if (!activityEnded.get()) return
        if (!endpointSettled.compareAndSet(false, true)) return
        endpointTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        endpointTimeoutRunnable = null
        val resolved = text.trim().ifBlank { lastInterimText.trim() }
        resolvedEndpointText = resolved
        if (resolved.isNotEmpty()) {
            notifyFinalizedTranscription(resolved)
        }
        val cont = endpointContinuation.getAndSet(null)
        if (cont != null && cont.isActive) {
            cont.resume(resolved)
        }
    }

    private fun parseDurationMillis(value: Any?): Long? {
        if (value == null || value == JSONObject.NULL) return null
        return when (value) {
            is Number -> (value.toDouble() * 1000.0).toLong()
            is String -> parseDurationString(value)
            is JSONObject -> {
                if (!value.has("seconds") && !value.has("nanos")) return null
                val seconds = value.optDouble("seconds", 0.0)
                val nanos = value.optDouble("nanos", 0.0)
                (seconds * 1000.0 + nanos / 1_000_000.0).toLong()
            }
            else -> null
        }
    }

    private fun parseDurationString(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.endsWith("ms") -> trimmed.removeSuffix("ms").trim().toDoubleOrNull()?.toLong()
            trimmed.endsWith("s") -> trimmed.removeSuffix("s").trim().toDoubleOrNull()?.times(1000.0)?.toLong()
            trimmed.endsWith("m") -> trimmed.removeSuffix("m").trim().toDoubleOrNull()?.times(60_000.0)?.toLong()
            else -> trimmed.toDoubleOrNull()?.times(1000.0)?.toLong()
        }
    }

    private fun notifyFinalizedTranscription(text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onFinalizedTranscription(text)
        } else {
            mainHandler.post { onFinalizedTranscription(text) }
        }
    }
}

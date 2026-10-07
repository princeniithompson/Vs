package com.example.websocket

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.example.core.ApiConfig
import com.example.data.AppLogRepository
import com.example.data.ConnectionState
import com.example.data.DiagnosticSource
import com.example.data.DiagnosticType
import com.example.data.LogLevel
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
import kotlin.random.Random

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
    private val isSessionActive: () -> Boolean = { false }
) {
    companion object {
        private const val TAG = "GeminiLiveWS"
        const val DEFAULT_MODEL = "models/gemini-3.5-transcribe-live"
        const val WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val MAX_RECONNECT_ATTEMPTS = 10
        const val PING_INTERVAL_SECONDS = 45L

        // Single-connection enforcement and instance tracking
        private val activeSocketCount = AtomicInteger(0)
        private val currentActiveClient = AtomicReference<GeminiLiveWebSocketClient?>(null)

        fun getActiveSocketCount(): Int = activeSocketCount.get()

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
                val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                val openMsg = "[$timeStr] WebSocket open (HTTP ${response.code}). Active live sockets: $currentLiveCount"
                Log.i(TAG, "[ConnectionState] CONNECTED: $openMsg")
                onLog(LogLevel.INFO, TAG, "WebSocket connected successfully (HTTP ${response.code}). Active sockets: $currentLiveCount. Sending initial setup payload ($modeLabel, vocab=$vocabCount)...", null)
                notifyStateChanged(ConnectionState.ConnectedWaitingSetup)

                // Step A: Send initial setup JSON
                val transcriptionConfig = JSONObject().apply {
                    if (isSmartMode) {
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
                                put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                                put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                                put("prefixPaddingMs", 200)
                                put("silenceDurationMs", 1600)
                            })
                        })
                    }
                    put("setup", setupObj)
                }

                val payload = setupJson.toString()
                val sent = webSocket.send(payload)
                if (sent) {
                    onLog(LogLevel.SENT, TAG, "Setup message sent to server", payload)
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
                val currentLiveCount = activeSocketCount.decrementAndGet().coerceAtLeast(0)
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
                val currentLiveCount = activeSocketCount.decrementAndGet().coerceAtLeast(0)
                Log.i(TAG, "[SingleConnection] WebSocket failure: ${t.message}. Active live sockets: $currentLiveCount")

                val primaryMsg = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                val isPingPongTimeout = primaryMsg.contains("ping", ignoreCase = true) ||
                                       primaryMsg.contains("pong", ignoreCase = true) ||
                                       primaryMsg.contains("timeout", ignoreCase = true)
                val endedReason = if (isPingPongTimeout) "ping_timeout" else "connection_drop"

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
        if (isExplicitlyClosed.get()) {
            isConnectingGuard.set(false)
            return
        }

        isSetupComplete.set(false)

        val sessionStillActive = isSessionActive()
        val attempts = reconnectAttempts.incrementAndGet()
        val maxAttempts = if (sessionStillActive) Int.MAX_VALUE else MAX_RECONNECT_ATTEMPTS

        if (attempts <= maxAttempts && lastApiKey.isNotBlank()) {
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
                if (!isExplicitlyClosed.get() && (isSessionActive() || reconnectAttempts.get() <= MAX_RECONNECT_ATTEMPTS)) {
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
                        DiagnosticType.SESSION_START,
                        ackMsg
                    )
                }
                notifyStateChanged(ConnectionState.Streaming)
                notifySetupComplete()
                return
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
                        onLog(LogLevel.RECEIVED, TAG, "Live Text: \"$currentTurn\"", null)
                        notifyInterimTranscription(currentTurn)
                    }
                }

                // 2. Interim hypothesis (updates fast, partial text)
                val interimObj = serverContent.optJSONObject("interimInputTranscription")
                if (interimObj != null && interimObj.has("text")) {
                    val interimText = interimObj.optString("text")
                    if (interimText.isNotEmpty()) {
                        onLog(LogLevel.RECEIVED, TAG, "Interim: \"$interimText\"", null)
                        notifyInterimTranscription(interimText)
                    }
                }

                // 3. Finalized transcription (once a segment completes)
                val finalizedObj = serverContent.optJSONObject("inputTranscription")
                if (finalizedObj != null && finalizedObj.has("text")) {
                    val finalizedText = finalizedObj.optString("text")
                    if (finalizedText.isNotEmpty()) {
                        onLog(LogLevel.RECEIVED, TAG, "Finalized: \"$finalizedText\"", null)
                        notifyFinalizedTranscription(finalizedText)
                    }
                }

                if (serverContent.optBoolean("turnComplete", false) || serverContent.optBoolean("interrupted", false)) {
                    val completedText = activeTurnText.toString().trim()
                    activeTurnText.clear()
                    if (completedText.isNotEmpty()) {
                        onLog(LogLevel.INFO, TAG, "Turn completed: \"$completedText\"", null)
                        notifyFinalizedTranscription(completedText)
                    }
                }
            } else {
                onLog(LogLevel.RECEIVED, TAG, "Server message: ${rawJson.take(120)}...", rawJson)
            }
        } catch (e: Exception) {
            val err = "Error parsing incoming frame JSON: ${e.message}"
            Log.e(TAG, err, e)
            onLog(LogLevel.ERROR, TAG, "JSON parse error: ${e.message}", rawJson)
            notifyError(err, GeminiLiveError.ProtocolError(err, e))
        }
    }

    /**
     * Step C: Send 100ms PCM chunk Base64-encoded via realtimeInput.mediaChunks
     * CRITICAL: Must only be called once isSetupComplete is true.
     */
    fun sendAudioChunk(pcmChunk: ByteArray): Boolean {
        val ws = webSocket
        if (ws == null || !isSetupComplete.get() || isExplicitlyClosed.get()) {
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
     * Step D: On user stop, signal audioStreamEnd
     */
    fun signalStreamEnd() {
        val ws = webSocket
        if (ws != null && isSetupComplete.get()) {
            try {
                val endJson = JSONObject().apply {
                    put("realtimeInput", JSONObject().apply {
                        put("audioStreamEnd", true)
                    })
                }
                ws.send(endJson.toString())
                onLog(LogLevel.SENT, TAG, "Sent audioStreamEnd signal", endJson.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error sending audioStreamEnd", e)
            }
        }
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
                val count = activeSocketCount.decrementAndGet().coerceAtLeast(0)
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
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onInterimTranscription(text)
        } else {
            mainHandler.post { onInterimTranscription(text) }
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

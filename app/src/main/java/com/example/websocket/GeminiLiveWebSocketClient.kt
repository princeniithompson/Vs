package com.example.websocket

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.example.data.AppLogRepository
import com.example.data.ConnectionState
import com.example.data.DiagnosticSource
import com.example.data.DiagnosticType
import com.example.data.LogLevel
import okhttp3.CertificatePinner
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
    private val onStructuredError: ((GeminiLiveError) -> Unit)? = null
) {
    companion object {
        private const val TAG = "GeminiLiveWS"
        const val DEFAULT_MODEL = "models/gemini-3.5-transcribe-live"
        const val WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MAX_RECONNECT_ATTEMPTS = 3

        val DEFAULT_CERTIFICATE_PINNER: CertificatePinner = CertificatePinner.Builder()
            .add("generativelanguage.googleapis.com", "sha256/kIdpMS077tAh+gS+4V74k/Xy49qJ2q69PzVvj/vF1+Q=")
            .add("generativelanguage.googleapis.com", "sha256/mEflZT5enoR1FuXLgYYGqnVEoZvMF9c2bVB9esBcW5g=")
            .add("generativelanguage.googleapis.com", "sha256/hxqRlPTuQrg9q2yBoMuMvGzOUMtPF0Ces3w0PC+BCMQ=")
            .build()

        fun buildWebSocketRequest(apiKey: String): Request {
            val trimmedKey = apiKey.trim()
            if (trimmedKey.isEmpty() || trimmedKey.equals("MY_GEMINI_API_KEY", ignoreCase = true)) {
                throw IllegalArgumentException("Gemini API Key is missing or placeholder. Please provide a valid key in Secrets or Settings.")
            }
            return Request.Builder()
                .url(WS_BASE_URL)
                .addHeader("x-goog-api-key", trimmedKey)
                .build()
        }
    }

    private val client: OkHttpClient = okHttpClient ?: OkHttpClient.Builder()
        .certificatePinner(DEFAULT_CERTIFICATE_PINNER)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite read timeout for persistent WebSocket
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(45, TimeUnit.SECONDS) // Tolerant 45s ping interval to prevent aggressive dropouts
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private val isSetupComplete = AtomicBoolean(false)
    private val isConnectingGuard = AtomicBoolean(false)
    private val isExplicitlyClosed = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private var activeModel: String = DEFAULT_MODEL
    private var lastApiKey: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingReconnectRunnable: Runnable? = null

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
        if (trimmedKey.isEmpty() || trimmedKey.equals("MY_GEMINI_API_KEY", ignoreCase = true)) {
            val errMsg = "Gemini API Key is missing or placeholder. Please provide a valid key in Secrets or Settings."
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

        // Cancel any pending auto-reconnect before initiating a new connection
        cancelPendingReconnect()

        // Ensure only one WebSocket session can exist at a time by closing any existing socket
        closeExistingWebSocket()

        lastApiKey = trimmedKey
        activeModel = model
        isSmartMode = smartMode
        customVocabularyList = customVocabulary
        isExplicitlyClosed.set(false)
        reconnectAttempts.set(0)
        isSetupComplete.set(false)
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
            notifyError(errMsg, GeminiLiveError.MissingApiKey(errMsg))
            notifyStateChanged(ConnectionState.Error(errMsg))
            return
        }

        val modeLabel = if (isSmartMode) "SMART (filler-cleanup + punctuation)" else "VERBATIM (raw fastest)"
        val vocabCount = customVocabularyList.size
        val attemptNum = reconnectAttempts.get()
        if (attemptNum == 0) {
            onLog(LogLevel.INFO, TAG, "Opening WebSocket connection to $WS_BASE_URL ($activeModel) in $modeLabel mode with $vocabCount custom vocabulary terms", null)
        } else {
            onLog(LogLevel.INFO, TAG, "Reconnecting WebSocket (attempt $attemptNum/$MAX_RECONNECT_ATTEMPTS) to $WS_BASE_URL ($activeModel)...", null)
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isExplicitlyClosed.get()) {
                    webSocket.close(1000, "Closed by client")
                    isConnectingGuard.set(false)
                    return
                }

                Log.d(TAG, "WebSocket connection opened. Sending Step A initial setup JSON ($modeLabel, vocab=$vocabCount)...")
                onLog(LogLevel.INFO, TAG, "WebSocket opened. Sending initial setup payload ($modeLabel, vocab=$vocabCount)...", null)
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
                    append("You are a precise real-time voice typing engine. Ignore continuous background noise such as ceiling fans, air conditioning, road noise, television audio, and distant chatter. Focus exclusively on the primary speaker's voice. Produce clean, punctuated text and remove filler words (um, uh, like) and self-corrections.")
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
                                put("prefixPaddingMs", 300)
                                put("silenceDurationMs", 2000)
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
                Log.d(TAG, "WebSocket closing (code: $code, reason: $reasonDetail)")
                onLog(LogLevel.INFO, TAG, "WebSocket closing (code: $code, reason: $reasonDetail)", null)
                if (!isExplicitlyClosed.get()) {
                    notifyStateChanged(ConnectionState.Stopping)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val reasonDetail = if (reason.isNotBlank()) reason else "No reason provided"
                Log.d(TAG, "WebSocket closed (code: $code, reason: $reasonDetail)")
                isSetupComplete.set(false)

                if (code == 1000 || isExplicitlyClosed.get()) {
                    isConnectingGuard.set(false)
                    lastApiKey = ""
                    onLog(LogLevel.INFO, TAG, "WebSocket closed cleanly (code: 1000, reason: $reasonDetail)", null)
                    notifyStateChanged(ConnectionState.Idle)
                } else {
                    handleDisconnectOrFailure("WebSocket closed abnormally (code: $code, reason: $reasonDetail)", null)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
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

        val attempts = reconnectAttempts.incrementAndGet()
        if (attempts <= MAX_RECONNECT_ATTEMPTS && lastApiKey.isNotBlank()) {
            val reconnectLog = "WebSocket connection dropped ($errorDetails). Scheduling auto-reconnect (attempt $attempts/$MAX_RECONNECT_ATTEMPTS)..."
            Log.i(TAG, reconnectLog)
            onLog(LogLevel.INFO, TAG, reconnectLog, null)
            AppLogRepository.logEvent(
                DiagnosticSource.APP,
                DiagnosticType.SESSION_RECONNECT,
                "Attempting auto-reconnect ($attempts/$MAX_RECONNECT_ATTEMPTS) due to: ${errorDetails.take(120)}"
            )
            notifyStateChanged(ConnectionState.Connecting)

            val backoffMs = (attempts * 300L).coerceAtMost(1200L)
            cancelPendingReconnect()
            val runnable = Runnable {
                if (!isExplicitlyClosed.get()) {
                    connectInternal()
                } else {
                    isConnectingGuard.set(false)
                }
            }
            pendingReconnectRunnable = runnable
            mainHandler.postDelayed(runnable, backoffMs)
            return
        }

        // Retries exhausted or non-retryable fatal error
        isConnectingGuard.set(false)
        lastApiKey = ""
        val fatalMsg = "ended_reason: connection_failed_after_retries | $errorDetails"
        Log.e(TAG, fatalMsg, throwable)
        onLog(LogLevel.ERROR, TAG, fatalMsg, null)
        AppLogRepository.logEvent(
            DiagnosticSource.APP,
            DiagnosticType.ERROR,
            "WebSocket failed permanently after $MAX_RECONNECT_ATTEMPTS reconnect attempts"
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
                reconnectAttempts.set(0)
                Log.i(TAG, "setupComplete received from Gemini Live server!")
                val ackMsg = if (wasReconnecting) {
                    "setupComplete acknowledged! Session resumed after reconnect."
                } else {
                    "setupComplete acknowledged by Gemini Live!"
                }
                onLog(LogLevel.RECEIVED, TAG, ackMsg, rawJson)
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
                Log.e(TAG, detailedError)
                onLog(LogLevel.ERROR, TAG, detailedError, rawJson)
                notifyStateChanged(ConnectionState.Error(detailedError))
                notifyError(detailedError, GeminiLiveError.ServerError(code, status, detailedError))
                return
            }

            // Step C & frame text extraction
            val serverContent = json.optJSONObject("serverContent")
            if (serverContent != null) {
                // Interim hypothesis (updates fast, partial text)
                val interimObj = serverContent.optJSONObject("interimInputTranscription")
                if (interimObj != null && interimObj.has("text")) {
                    val interimText = interimObj.optString("text")
                    if (interimText.isNotEmpty()) {
                        onLog(LogLevel.RECEIVED, TAG, "Interim: \"$interimText\"", null)
                        notifyInterimTranscription(interimText)
                    }
                }

                // Finalized transcription (once a segment completes)
                val finalizedObj = serverContent.optJSONObject("inputTranscription")
                if (finalizedObj != null && finalizedObj.has("text")) {
                    val finalizedText = finalizedObj.optString("text")
                    if (finalizedText.isNotEmpty()) {
                        onLog(LogLevel.RECEIVED, TAG, "Finalized: \"$finalizedText\"", null)
                        notifyFinalizedTranscription(finalizedText)
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    onLog(LogLevel.INFO, TAG, "Turn completed by server", null)
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
     * Step C: Send 100ms PCM chunk Base64-encoded via realtimeInput.audio
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
                    put("audio", JSONObject().apply {
                        put("data", base64Data)
                        put("mimeType", "audio/pcm;rate=16000")
                    })
                })
            }
            ws.send(chunkJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk", e)
            notifyError("Error sending audio chunk: ${e.message}", GeminiLiveError.ProtocolError("Send audio chunk failed", e))
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
        isExplicitlyClosed.set(true)
        reconnectAttempts.set(MAX_RECONNECT_ATTEMPTS)
        cancelPendingReconnect()
        closeExistingWebSocket()

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

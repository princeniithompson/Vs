package com.princeniithompson.rapcha.service.floating

import android.util.Log
import com.princeniithompson.rapcha.config.RapchaConfig
import com.princeniithompson.rapcha.core.ApiConfig
import com.princeniithompson.rapcha.websocket.GeminiLiveWebSocketClient
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class LivePolishResponse(
    val text: String?,
    val error: String?,
    val timedOut: Boolean,
    val trace: String = "",
    val firstTokenMs: Long = -1
) {
    fun withTrace(
        assembler: LivePolishTranscriptAssembler,
        startedAt: Long,
        firstTokenAt: Long,
        now: Long
    ): LivePolishResponse {
        val first = if (firstTokenAt < 0) -1 else (firstTokenAt - startedAt).coerceAtLeast(0)
        val line = "elapsed_ms=${(now - startedAt).coerceAtLeast(0)} first_token_ms=$first frames=${assembler.trace()}"
        val reason = if (error.isNullOrBlank()) error else "$error $line"
        return copy(error = reason, trace = line, firstTokenMs = first)
    }
}

/**
 * One short text-to-text Live session. The audio transcription client stays untouched.
 * Auth matches transcription: WebSocket to BidiGenerateContent with x-goog-api-key, never a query key.
 */
class LiveTextPolishClient(
    private val httpClient: OkHttpClient = ApiConfig.createOkHttpClient(),
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) : Stage2Model {

    override fun polish(request: Stage2Request): LivePolishResponse {
        val apiKey = request.apiKey.trim()
        if (ApiConfig.isPlaceholder(apiKey)) {
            return LivePolishResponse(null, "Gemini API key is missing", timedOut = false)
        }
        val assembler = LivePolishTranscriptAssembler()
        val failure = AtomicReference<String?>(null)
        val contentSent = AtomicBoolean(false)
        var socket: WebSocket? = null
        val started = clock()
        var turnSentAt = 0L
        var firstTokenAt = -1L
        var lastGrowthAt = 0L
        var seenChars = 0

        val setup = LivePolishProtocol.setupMessage(request.systemInstruction)
        val turn = LivePolishProtocol.clientContentMessage(request.userText)

        try {
            val wsRequest = GeminiLiveWebSocketClient.buildWebSocketRequest(apiKey)
            socket = httpClient.newWebSocket(wsRequest, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!webSocket.send(setup)) {
                        failure.compareAndSet(null, "Could not send Live setup")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (assembler.absorb(text) == LivePolishSignal.SETUP_COMPLETE &&
                        contentSent.compareAndSet(false, true) &&
                        !webSocket.send(turn)
                    ) {
                        failure.compareAndSet(null, "Could not send the transcript turn")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onMessage(webSocket, bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    val detail = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                    val message = if (code != null) "Live socket failed (HTTP $code): $detail" else "Live socket failed: $detail"
                    failure.compareAndSet(null, message)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!assembler.turnComplete && failure.get() == null && !assembler.setupComplete) {
                        failure.compareAndSet(null, "Live socket closed before setup ($code)")
                    }
                }
            })

            while (true) {
                val now = clock()
                val textNow = assembler.text()
                if (textNow.length > seenChars) {
                    seenChars = textNow.length
                    lastGrowthAt = now
                    if (firstTokenAt < 0) firstTokenAt = now
                }
                if (contentSent.get() && turnSentAt == 0L) turnSentAt = now
                val socketError = failure.get()
                val protocolError = assembler.error
                if (textNow.isNotBlank() && protocolError == null && socketError == null) {
                    val idle = lastGrowthAt > 0L && now - lastGrowthAt >= RapchaConfig.STAGE2_LIVE_IDLE_MS
                    if (assembler.turnComplete || idle) {
                        return done(textNow, null, false, assembler, started, firstTokenAt)
                    }
                }
                if (assembler.turnComplete || socketError != null || protocolError != null) {
                    return LivePolishOutcome.decide(
                        finished = true,
                        assembledText = textNow,
                        socketError = socketError,
                        protocolError = protocolError,
                        setupCompleted = assembler.setupComplete
                    ).withTrace(assembler, started, firstTokenAt, now)
                }
                val silentFor = if (turnSentAt == 0L) now - started else now - turnSentAt
                if (textNow.isBlank() && silentFor >= RapchaConfig.STAGE2_LIVE_TIMEOUT_MS) {
                    return LivePolishOutcome.decide(
                        finished = false,
                        assembledText = "",
                        socketError = socketError,
                        protocolError = protocolError,
                        setupCompleted = assembler.setupComplete
                    ).withTrace(assembler, started, firstTokenAt, now)
                }
                if (now - started >= RapchaConfig.STAGE2_LIVE_HARD_CAP_MS) {
                    return done(textNow, null, false, assembler, started, firstTokenAt)
                }
                Thread.sleep(40)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stage-2 Live polish failed: ${e.message}")
            return LivePolishResponse(null, e.message ?: e.javaClass.simpleName, timedOut = false)
        } finally {
            try {
                socket?.close(1000, "stage2 done")
            } catch (_: Exception) {
            }
            try {
                socket?.cancel()
            } catch (_: Exception) {
            }
        }
    }

    private fun done(
        text: String,
        error: String?,
        timedOut: Boolean,
        assembler: LivePolishTranscriptAssembler,
        started: Long,
        firstTokenAt: Long
    ): LivePolishResponse {
        return LivePolishResponse(text, error, timedOut).withTrace(assembler, started, firstTokenAt, clock())
    }

    companion object {
        private const val TAG = "LiveTextPolish"
    }
}

/**
 * A late turn marker must not throw away text the model already sent.
 * An empty timeout stays a failure so the caller can fall back.
 */
object LivePolishOutcome {
    fun decide(
        finished: Boolean,
        assembledText: String,
        socketError: String?,
        protocolError: String?,
        setupCompleted: Boolean
    ): LivePolishResponse {
        val text = assembledText.trim()
        if (text.isNotEmpty() && protocolError == null && socketError == null) {
            return LivePolishResponse(text, null, timedOut = false)
        }
        if (!finished) {
            val why = if (setupCompleted) {
                "Stage-2 Live polish timed out after setup"
            } else {
                "Stage-2 Live polish timed out before setup"
            }
            return LivePolishResponse(null, why, timedOut = true)
        }
        val reason = socketError ?: protocolError ?: "Stage-2 Live polish returned no text"
        return LivePolishResponse(null, reason, timedOut = false)
    }
}

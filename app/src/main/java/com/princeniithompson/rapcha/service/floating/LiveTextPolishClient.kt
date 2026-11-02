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

    override fun polish(request: Stage2Request): LivePolishResponse = runSession(request, sendTurn = true)

    /** Opens the same Stage-2 socket and returns when setupComplete arrives. */
    fun ping(apiKey: String): LivePolishResponse = runSession(
        Stage2Request(
            apiKey = apiKey,
            systemInstruction = "Reply with the single word ready.",
            userText = "ready"
        ),
        sendTurn = false
    )

    private fun runSession(request: Stage2Request, sendTurn: Boolean): LivePolishResponse {
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
                    assembler.mark("open:${response.code}")
                    if (!webSocket.send(setup)) {
                        assembler.mark("setup_send_fail")
                        failure.compareAndSet(null, "Could not send Live setup")
                    } else {
                        assembler.mark("setup_sent")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val signal = assembler.absorb(text)
                    if (signal == LivePolishSignal.SETUP_COMPLETE && contentSent.compareAndSet(false, true)) {
                        if (!sendTurn) return
                        if (webSocket.send(turn)) {
                            assembler.mark("turn_sent")
                        } else {
                            assembler.mark("turn_send_fail")
                            failure.compareAndSet(null, "Could not send the transcript turn")
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onMessage(webSocket, bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    val detail = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                    val body = try {
                        response?.body?.string()?.take(120).orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    assembler.mark("fail:${code ?: "-"}:${detail.take(40)}")
                    val message = buildString {
                        append(if (code != null) "Live socket failed (HTTP $code): $detail" else "Live socket failed: $detail")
                        if (body.isNotBlank()) append(" body=").append(body)
                    }
                    failure.compareAndSet(null, message)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    assembler.mark("close:$code")
                    if (!assembler.turnComplete && failure.get() == null && !assembler.setupComplete) {
                        val why = reason.ifBlank { "no reason" }.take(80)
                        failure.compareAndSet(null, "Live socket closed before setup ($code $why)")
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
                if (!sendTurn && assembler.setupComplete) {
                    return done("setup", null, false, assembler, started, now)
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
                val waitingForSetup = !assembler.setupComplete
                val setupLate = waitingForSetup && now - started >= RapchaConfig.STAGE2_LIVE_SETUP_MS
                val turnLate = !waitingForSetup && textNow.isBlank() && turnSentAt > 0L &&
                    now - turnSentAt >= RapchaConfig.STAGE2_LIVE_TIMEOUT_MS
                if (setupLate || turnLate) {
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

    fun userLine(error: String?): String {
        val detail = error.orEmpty()
        val busy = detail.contains("503") ||
            detail.contains("high demand", ignoreCase = true) ||
            detail.contains("UNAVAILABLE")
        val tail = " Your words were inserted."
        if (busy) return "Gemini is busy.$tail"
        if (detail.contains("before setup", ignoreCase = true)) {
            return "Live polish timed out before setup.$tail"
        }
        if (detail.contains("after setup", ignoreCase = true)) {
            return "Live polish timed out after setup.$tail"
        }
        if (detail.contains("HTTP", ignoreCase = true) || detail.contains("closed", ignoreCase = true) ||
            detail.contains("connect", ignoreCase = true) || detail.contains("failed", ignoreCase = true)
        ) {
            val short = detail.substringBefore(" frames=").substringBefore(" elapsed_ms=").take(72)
            return "Live polish could not connect ($short).$tail"
        }
        return "Live polish failed.$tail"
    }
}

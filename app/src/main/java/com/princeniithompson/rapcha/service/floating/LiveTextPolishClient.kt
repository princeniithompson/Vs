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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class LivePolishResponse(
    val text: String?,
    val error: String?,
    val timedOut: Boolean
)

/**
 * One short text-to-text Live session. The audio transcription client stays untouched.
 * Auth matches transcription: WebSocket to BidiGenerateContent with x-goog-api-key, never a query key.
 */
class LiveTextPolishClient(
    private val httpClient: OkHttpClient = ApiConfig.createOkHttpClient(),
    private val timeoutMs: Long = RapchaConfig.STAGE2_LIVE_TIMEOUT_MS
) : Stage2Model {

    override fun polish(request: Stage2Request): LivePolishResponse {
        val apiKey = request.apiKey.trim()
        if (ApiConfig.isPlaceholder(apiKey)) {
            return LivePolishResponse(null, "Gemini API key is missing", timedOut = false)
        }
        val assembler = LivePolishTranscriptAssembler()
        val latch = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val contentSent = AtomicBoolean(false)
        var socket: WebSocket? = null

        val setup = LivePolishProtocol.setupMessage(request.systemInstruction)
        val turn = LivePolishProtocol.clientContentMessage(request.userText)

        try {
            val wsRequest = GeminiLiveWebSocketClient.buildWebSocketRequest(apiKey)
            socket = httpClient.newWebSocket(wsRequest, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!webSocket.send(setup)) {
                        failure.compareAndSet(null, "Could not send Live setup")
                        latch.countDown()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (assembler.absorb(text)) {
                        LivePolishSignal.SETUP_COMPLETE -> {
                            if (contentSent.compareAndSet(false, true) && !webSocket.send(turn)) {
                                failure.compareAndSet(null, "Could not send the transcript turn")
                                latch.countDown()
                            }
                        }
                        LivePolishSignal.TURN_COMPLETE, LivePolishSignal.ERROR -> latch.countDown()
                        LivePolishSignal.TEXT, LivePolishSignal.IGNORE -> Unit
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
                    latch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!assembler.turnComplete && failure.get() == null && !assembler.setupComplete) {
                        failure.compareAndSet(null, "Live socket closed before setup ($code)")
                    }
                    latch.countDown()
                }
            })

            val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return LivePolishOutcome.decide(
                finished = finished,
                assembledText = assembler.text(),
                socketError = failure.get(),
                protocolError = assembler.error,
                setupCompleted = assembler.setupComplete
            )
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

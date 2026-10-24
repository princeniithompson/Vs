package com.princeniithompson.rapcha.websocket

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualEndpointingTest {

    private class FakeWebSocket : WebSocket {
        val sentMessages = mutableListOf<String>()
        override fun cancel() {}
        override fun close(code: Int, reason: String?): Boolean = true
        override fun queueSize(): Long = 0L
        override fun request(): Request = Request.Builder().url("https://generativelanguage.googleapis.com").build()
        override fun send(text: String): Boolean {
            sentMessages.add(text)
            return true
        }
        override fun send(bytes: ByteString): Boolean = true
    }

    private class Harness(
        val smartMode: Boolean = false,
        val onInterim: (String) -> Unit = {},
        val onFinal: (String) -> Unit = {},
        val onPiece: (String) -> Unit = {},
        val onGoAway: (Long?) -> Unit = {},
        val onSessionLimit: (String, Boolean) -> Unit = { _, _ -> },
        val sessionActive: () -> Boolean = { false }
    ) {
        val sockets = mutableListOf<FakeWebSocket>()
        val listeners = mutableListOf<WebSocketListener>()
        val socket: FakeWebSocket
            get() = sockets.first()
        val listener: WebSocketListener
            get() = listeners.first()
        val client = GeminiLiveWebSocketClient(
            onSetupComplete = {},
            onInterimTranscription = onInterim,
            onFinalizedTranscription = onFinal,
            onStateChanged = {},
            onLog = { _, _, _, _ -> },
            onError = {},
            okHttpClient = object : OkHttpClient() {
                override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                    val created = FakeWebSocket()
                    sockets.add(created)
                    listeners.add(listener)
                    return created
                }
            },
            isSessionActive = sessionActive,
            onSessionLimit = onSessionLimit,
            onPieceCommitted = onPiece,
            onGoAway = onGoAway
        )

        fun open(modelSmart: Boolean = smartMode) {
            client.connect(
                apiKey = "AIzaSyFakeKeyForManualVadTesting12345",
                smartMode = modelSmart
            )
            val response = Response.Builder()
                .request(Request.Builder().url("https://generativelanguage.googleapis.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .build()
            listener.onOpen(socket, response)
            listener.onMessage(socket, """{"setupComplete":{}}""")
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `setup disables automatic activity detection and does not arm silence endpointing`() {
        val harness = Harness()
        harness.open(modelSmart = false)
        val setup = JSONObject(harness.socket.sentMessages.first()).getJSONObject("setup")
        val detection = setup.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertTrue(detection.getBoolean("disabled"))
        assertFalse(detection.has("silenceDurationMs"))
        assertFalse(detection.has("prefixPaddingMs"))
        assertFalse(setup.getJSONObject("inputAudioTranscription").has("mode"))
        assertFalse(harness.socket.sentMessages.any { it.contains("audioStreamEnd") })
        harness.client.disconnect()
    }

    @Test
    fun `smart mode sends the documented transcription mode field`() {
        val harness = Harness()
        harness.open(modelSmart = true)
        val transcription = JSONObject(harness.socket.sentMessages.first())
            .getJSONObject("setup")
            .getJSONObject("inputAudioTranscription")
        assertEquals("SMART", transcription.getString("mode"))
        harness.client.disconnect()
    }

    @Test
    fun `pauses update the preview and do not finalize`() {
        val interims = mutableListOf<String>()
        var finalized = 0
        val harness = Harness(
            onInterim = { interims.add(it) },
            onFinal = { finalized++ }
        )
        harness.open()
        harness.listener.onMessage(
            harness.socket,
            """{"serverContent":{"interimInputTranscription":{"text":"hello"}}}"""
        )
        harness.listener.onMessage(
            harness.socket,
            """{"serverContent":{"inputTranscription":{"text":"hello there"},"turnComplete":true}}"""
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("hello", "hello there"), interims)
        assertEquals(0, finalized)
        assertTrue(harness.socket.sentMessages.none { it.contains("activityEnd") })
        harness.client.disconnect()
    }

    @Test
    fun `activityEnd waits for the final transcript and never sends audioStreamEnd`() {
        var finalized = ""
        val harness = Harness(onFinal = { finalized = it })
        harness.open()
        harness.client.sendAudioChunk(InterimPreviewMonitor.speechPcm(sample = 4000, sampleCount = 160))
        var result = ""
        CoroutineScope(Dispatchers.Main).launch {
            result = harness.client.endActivityAndAwaitFinal()
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(harness.socket.sentMessages.any { JSONObject(it).optJSONObject("realtimeInput")?.has("activityStart") == true })
        assertTrue(harness.socket.sentMessages.any { JSONObject(it).optJSONObject("realtimeInput")?.has("activityEnd") == true })
        assertFalse(harness.socket.sentMessages.any { it.contains("audioStreamEnd") })
        val audio = harness.socket.sentMessages
            .mapNotNull { JSONObject(it).optJSONObject("realtimeInput")?.optJSONObject("audio") }
        assertTrue(audio.isNotEmpty())
        assertEquals("audio/pcm;rate=16000", audio.first().getString("mimeType"))
        assertFalse(harness.socket.sentMessages.any { it.contains("mediaChunks") })

        harness.listener.onMessage(
            harness.socket,
            """{"serverContent":{"inputTranscription":{"text":"finished sentence"}}}"""
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("finished sentence", result)
        assertEquals("finished sentence", finalized)
        harness.client.disconnect()
    }

    @Test
    fun `missing final falls back to the last interim transcript`() {
        val harness = Harness()
        harness.open()
        harness.listener.onMessage(
            harness.socket,
            """{"serverContent":{"interimInputTranscription":{"text":"still talking"}}}"""
        )
        shadowOf(Looper.getMainLooper()).idle()
        var result = ""
        CoroutineScope(Dispatchers.Main).launch {
            result = harness.client.endActivityAndAwaitFinal()
        }
        shadowOf(Looper.getMainLooper()).idleFor(
            GeminiLiveWebSocketClient.FINAL_TRANSCRIPT_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )
        assertEquals("still talking", result)
        assertFalse(harness.socket.sentMessages.any { it.contains("audioStreamEnd") })
        harness.client.disconnect()
    }

    @Test
    fun `the socket does not arm the ten minute clock`() {
        val events = mutableListOf<Boolean>()
        val harness = Harness(onSessionLimit = { _, finalizeNow -> events.add(finalizeNow) })
        harness.open()
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertTrue(events.isEmpty())
        harness.client.disconnect()
    }

    @Test
    fun `goAway is handed to the session clock instead of finishing immediately`() {
        var timeLeft: Long? = null
        val finishes = mutableListOf<Boolean>()
        val harness = Harness(
            onGoAway = { timeLeft = it },
            onSessionLimit = { _, finalizeNow -> finishes.add(finalizeNow) }
        )
        harness.open()
        harness.listener.onMessage(harness.socket, """{"goAway":{"timeLeft":"20s"}}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(20_000L, timeLeft)
        assertTrue(finishes.isEmpty())
        harness.client.disconnect()
    }

    @Test
    fun `a pause after two and a half minutes commits the piece and keeps listening`() {
        val pieces = mutableListOf<String>()
        val harness = Harness(onPiece = { pieces.add(it) })
        harness.open()
        val speech = InterimPreviewMonitor.speechPcm(sample = 4000, sampleCount = 16_000)
        repeat(150) { index ->
            harness.client.sendAudioChunk(speech)
            if (index % 5 == 4) {
                harness.listener.onMessage(
                    harness.socket,
                    """{"serverContent":{"interimInputTranscription":{"text":"first piece stays"}}}"""
                )
            }
        }
        harness.client.sendAudioChunk(ByteArray(32_000))
        harness.listener.onMessage(
            harness.socket,
            """{"serverContent":{"inputTranscription":{"text":"first piece stays"}}}"""
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("first piece stays"), pieces)
        val starts = harness.socket.sentMessages.count {
            JSONObject(it).optJSONObject("realtimeInput")?.has("activityStart") == true
        }
        val ends = harness.socket.sentMessages.count {
            JSONObject(it).optJSONObject("realtimeInput")?.has("activityEnd") == true
        }
        assertEquals(2, starts)
        assertEquals(1, ends)
        assertFalse(harness.socket.sentMessages.any { it.contains("audioStreamEnd") })
        harness.client.disconnect()
    }

    @Test
    fun `reconnect keeps the words already heard`() {
        val pieces = mutableListOf<String>()
        val interims = mutableListOf<String>()
        val harness = Harness(
            onInterim = { interims.add(it) },
            onPiece = { pieces.add(it) },
            sessionActive = { true }
        )
        harness.open()
        harness.listener.onMessage(
            harness.socket,
            """{"serverContent":{"interimInputTranscription":{"text":"keep these words"}}}"""
        )
        shadowOf(Looper.getMainLooper()).idle()
        harness.listener.onClosed(harness.socket, 1001, "dropped")
        shadowOf(Looper.getMainLooper()).idle()
        harness.listener.onMessage(
            harness.socket,
            """{"serverContent":{"interimInputTranscription":{"text":"new piece"}}}"""
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("keep these words"), pieces)
        assertEquals("new piece", interims.last())
        harness.client.disconnect()
    }

    @Test
    fun `a fresh split keeps listening on the next socket`() {
        val pieces = mutableListOf<String>()
        val harness = Harness(onPiece = { pieces.add(it) })
        harness.open()
        harness.client.sendAudioChunk(InterimPreviewMonitor.speechPcm(sampleCount = 1600))
        harness.listener.onMessage(
            harness.socket,
            """{"serverContent":{"interimInputTranscription":{"text":"alpha beta stays"}}}"""
        )
        shadowOf(Looper.getMainLooper()).idle()
        harness.client.requestFreshConnectionSplit()
        harness.listener.onMessage(
            harness.socket,
            """{"serverContent":{"inputTranscription":{"text":"alpha beta stays"}}}"""
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, harness.sockets.size)
        val nextListener = harness.listeners[1]
        val nextSocket = harness.sockets[1]
        val response = Response.Builder()
            .request(Request.Builder().url("https://generativelanguage.googleapis.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()
        nextListener.onOpen(nextSocket, response)
        nextListener.onMessage(nextSocket, """{"setupComplete":{}}""")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("alpha beta stays"), pieces)
        assertTrue(nextSocket.sentMessages.any {
            JSONObject(it).optJSONObject("realtimeInput")?.has("activityStart") == true
        })
        assertTrue(nextSocket.sentMessages.any {
            JSONObject(it).optJSONObject("realtimeInput")?.optJSONObject("audio") != null
        })
        nextListener.onMessage(
            nextSocket,
            """{"serverContent":{"interimInputTranscription":{"text":"gamma delta"}}}"""
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("alpha beta stays gamma delta", harness.client.fullTranscript())
        harness.client.disconnect()
    }

    @Test
    fun `a near silent piece drops a vocabulary hallucination`() {
        val pieces = mutableListOf<String>()
        val harness = Harness(onPiece = { pieces.add(it) })
        harness.open()
        val silence = ByteArray(32_000)
        repeat(151) {
            harness.client.sendAudioChunk(silence)
        }
        harness.listener.onMessage(
            harness.socket,
            """{"serverContent":{"inputTranscription":{"text":"Please send the invoice to prince niithompson at example"}}}"""
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(pieces.isEmpty())
        assertEquals("", harness.client.fullTranscript())
        harness.client.disconnect()
    }

    @Test
    fun `a silent final finishes without waiting for the transcript timeout`() {
        val harness = Harness()
        harness.open()
        repeat(4) {
            harness.client.sendAudioChunk(ByteArray(3_200))
        }
        var result = "unset"
        CoroutineScope(Dispatchers.Main).launch {
            result = harness.client.endActivityAndAwaitFinal()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", result)
        harness.listener.onMessage(
            harness.socket,
            """{"serverContent":{"inputTranscription":{"text":"Please send the invoice to prince niithompson"}}}"""
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", harness.client.fullTranscript())
        harness.client.disconnect()
    }
}

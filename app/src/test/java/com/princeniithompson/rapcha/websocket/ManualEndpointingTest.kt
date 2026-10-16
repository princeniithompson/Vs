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
        val onSessionLimit: (String, Boolean) -> Unit = { _, _ -> }
    ) {
        val socket = FakeWebSocket()
        lateinit var listener: WebSocketListener
        val client = GeminiLiveWebSocketClient(
            onSetupComplete = {},
            onInterimTranscription = onInterim,
            onFinalizedTranscription = onFinal,
            onStateChanged = {},
            onLog = { _, _, _, _ -> },
            onError = {},
            okHttpClient = object : OkHttpClient() {
                override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                    this@Harness.listener = listener
                    return socket
                }
            },
            onSessionLimit = onSessionLimit
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
        harness.client.sendAudioChunk(ByteArray(320))
        var result = ""
        CoroutineScope(Dispatchers.Main).launch {
            result = harness.client.endActivityAndAwaitFinal()
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(harness.socket.sentMessages.any { JSONObject(it).optJSONObject("realtimeInput")?.has("activityStart") == true })
        assertTrue(harness.socket.sentMessages.any { JSONObject(it).optJSONObject("realtimeInput")?.has("activityEnd") == true })
        assertFalse(harness.socket.sentMessages.any { it.contains("audioStreamEnd") })

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
    fun `session clock warns and then asks to finish before the ten minute limit`() {
        val events = mutableListOf<Boolean>()
        val harness = Harness(onSessionLimit = { _, finalizeNow -> events.add(finalizeNow) })
        harness.open()
        shadowOf(Looper.getMainLooper()).idleFor(
            GeminiLiveWebSocketClient.SESSION_LIMIT_FINALIZE_MS,
            TimeUnit.MILLISECONDS
        )
        assertEquals(listOf(false, true), events)
        harness.client.disconnect()
    }

    @Test
    fun `goAway with little time left asks to finish`() {
        val events = mutableListOf<Boolean>()
        val harness = Harness(onSessionLimit = { _, finalizeNow -> events.add(finalizeNow) })
        harness.open()
        harness.listener.onMessage(harness.socket, """{"goAway":{"timeLeft":"20s"}}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true), events)
        harness.client.disconnect()
    }
}

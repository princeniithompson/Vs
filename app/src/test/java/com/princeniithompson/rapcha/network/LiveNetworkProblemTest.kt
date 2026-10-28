package com.princeniithompson.rapcha.network

import android.content.Context
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.ConnectionState
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.data.NetworkProblemPolicy
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.service.LiveNetworkSignals
import com.princeniithompson.rapcha.service.floating.FloatingDictationSessionManager
import com.princeniithompson.rapcha.websocket.GeminiLiveWebSocketClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveNetworkProblemTest {

    @Before
    fun reset() {
        LiveNetworkSignals.resetForTest()
    }

    @Test
    fun resolvePhrasesAndNoSocketClockAreExact() {
        assertTrue(
            NetworkProblemPolicy.isResolveFailure(
                "Unable to resolve host \"generativelanguage.googleapis.com\": No address associated with hostname"
            )
        )
        assertTrue(
            NetworkProblemPolicy.isResolveFailure(
                "android.system.GaiException: android_getaddrinfo failed: EAI_NODATA (No address associated with hostname)"
            )
        )
        assertTrue(NetworkProblemPolicy.isResolveFailure("java.net.UnknownHostException"))
        assertFalse(NetworkProblemPolicy.isResolveFailure("connection reset"))
        assertFalse(NetworkProblemPolicy.isResolveFailure("sent ping but didn't receive pong"))
        assertFalse(NetworkProblemPolicy.isResolveFailure("Bonsam"))
        assertFalse(NetworkProblemPolicy.noSocketOpen(3_999L, socketsOpened = 0, setupComplete = false))
        assertTrue(NetworkProblemPolicy.noSocketOpen(4_000L, socketsOpened = 0, setupComplete = false))
        assertFalse(NetworkProblemPolicy.noSocketOpen(9_000L, socketsOpened = 1, setupComplete = false))
        assertFalse(NetworkProblemPolicy.noSocketOpen(9_000L, socketsOpened = 0, setupComplete = true))
    }

    @Test
    fun noSocketTimeoutTurnsTheBubbleRedAndSetupClearsIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = FloatingDictationSessionManager(
            context = context,
            onTranscriptUpdated = {},
            onInterimReceived = {},
            onFinalSegmentReceived = {},
            onConnectionStateChanged = {},
            onAmplitudeChanged = {},
            onError = {},
            onDurationTicked = {}
        )
        manager.isRecording = true
        manager.flagNoSocketIfNeeded(3_999L)
        assertFalse(FloatingBubbleManager.isNetworkProblem.value)

        manager.flagNoSocketIfNeeded(NetworkProblemPolicy.NO_SOCKET_TIMEOUT_MS)
        assertTrue(FloatingBubbleManager.isNetworkProblem.value)
        assertEquals(1, networkEvents("no_socket").size)
        manager.flagNoSocketIfNeeded(5_000L)
        assertEquals(1, networkEvents("no_socket").size)

        val export = AppLogRepository.getFullExportFormatted()
        assertTrue(export.contains("[BUBBLE] [NETWORK_PROBLEM] no_socket | sockets=0 setup=false timeout_ms=4000"))

        manager.onLiveConnectionState(ConnectionState.Connecting, reconnecting = false)
        assertTrue(FloatingBubbleManager.isNetworkProblem.value)
        manager.onLiveConnectionState(ConnectionState.Error("offline"), reconnecting = false)
        assertTrue(FloatingBubbleManager.isNetworkProblem.value)
        manager.onLiveConnectionState(ConnectionState.Streaming, reconnecting = false)
        assertFalse(FloatingBubbleManager.isNetworkProblem.value)

        manager.onLiveConnectionState(ConnectionState.Connecting, reconnecting = true)
        assertTrue(FloatingBubbleManager.isNetworkProblem.value)
        manager.onLiveConnectionState(ConnectionState.ConnectedWaitingSetup, reconnecting = false)
        assertFalse(FloatingBubbleManager.isNetworkProblem.value)
        manager.isRecording = false
    }

    @Test
    fun dnsFailureRaisesTheBubbleAndAPlainDropDoesNot() {
        val active = AtomicBoolean(true)
        val dnsReasons = mutableListOf<String>()
        val dns = failingClient(
            active = active,
            failure = java.net.UnknownHostException(
                "Unable to resolve host \"generativelanguage.googleapis.com\": EAI_NODATA (No address associated with hostname)"
            ),
            onNetworkProblem = { dnsReasons.add(it) }
        )
        dns.connect("AIzaSyFakeKeyForDnsBubbleTest123456789")
        assertTrue(dnsReasons.single().contains("dns_or_resolve"))
        assertTrue(dnsReasons.single().contains("generativelanguage.googleapis.com"))
        assertTrue(dnsReasons.single().contains("EAI_NODATA"))
        LiveNetworkSignals.raise(DiagnosticSource.BUBBLE, dnsReasons.single())
        LiveNetworkSignals.raise(DiagnosticSource.BUBBLE, dnsReasons.single())
        assertTrue(FloatingBubbleManager.isNetworkProblem.value)
        assertEquals(1, networkEvents("dns_or_resolve").size)
        val export = AppLogRepository.getFullExportFormatted()
        assertTrue(export.contains("[BUBBLE] [NETWORK_PROBLEM] dns_or_resolve"))
        assertTrue(export.contains("EAI_NODATA"))
        active.set(false)
        dns.disconnect()
        ShadowLooper.shadowMainLooper().idleFor(2, TimeUnit.SECONDS)

        val drops = mutableListOf<String>()
        val reset = failingClient(
            active = AtomicBoolean(true),
            failure = java.io.IOException("connection reset"),
            onNetworkProblem = { drops.add(it) }
        )
        reset.connect("AIzaSyFakeKeyForDnsBubbleTest123456789")
        assertTrue(drops.isEmpty())
        reset.disconnect()
        ShadowLooper.shadowMainLooper().idleFor(2, TimeUnit.SECONDS)
    }

    private fun networkEvents(prefix: String) = AppLogRepository.diagnosticEntries.value.filter {
        it.type == DiagnosticType.NETWORK_PROBLEM && it.message.startsWith(prefix)
    }

    private fun failingClient(
        active: AtomicBoolean,
        failure: Throwable,
        onNetworkProblem: (String) -> Unit
    ): GeminiLiveWebSocketClient {
        val http = object : OkHttpClient() {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                val socket = object : WebSocket {
                    override fun cancel() {}
                    override fun close(code: Int, reason: String?): Boolean = true
                    override fun queueSize(): Long = 0L
                    override fun request(): Request = request
                    override fun send(text: String): Boolean = true
                    override fun send(bytes: okio.ByteString): Boolean = true
                }
                listener.onFailure(socket, failure, null)
                return socket
            }
        }
        return GeminiLiveWebSocketClient(
            onSetupComplete = {},
            onInterimTranscription = {},
            onFinalizedTranscription = {},
            onStateChanged = {},
            onLog = { _, _, _, _ -> },
            onError = {},
            okHttpClient = http,
            isSessionActive = { active.get() },
            onNetworkProblem = onNetworkProblem
        )
    }
}

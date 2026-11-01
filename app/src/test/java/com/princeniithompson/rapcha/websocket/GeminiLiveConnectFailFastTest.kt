package com.princeniithompson.rapcha.websocket

import android.os.Looper
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.ConnectionState
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.data.LogLevel
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiLiveConnectFailFastTest {

    private class FakeWebSocket : WebSocket {
        var isCancelled: Boolean = false
        override fun cancel() {
            isCancelled = true
        }
        override fun close(code: Int, reason: String?): Boolean = true
        override fun queueSize(): Long = 0L
        override fun request(): Request = Request.Builder().url("https://generativelanguage.googleapis.com").build()
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
    }

    private class DialHarness(private val sessionActive: () -> Boolean) {
        val attempts = AtomicInteger(0)
        val sockets = mutableListOf<FakeWebSocket>()
        val listeners = mutableListOf<WebSocketListener>()
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val states = mutableListOf<ConnectionState>()
        val client = GeminiLiveWebSocketClient(
            onSetupComplete = {},
            onInterimTranscription = {},
            onFinalizedTranscription = {},
            onStateChanged = { states.add(it) },
            onLog = { level, _, message, _ ->
                if (level != LogLevel.SENT) logs.add(message)
            },
            onError = { errors.add(it) },
            okHttpClient = object : OkHttpClient() {
                override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                    attempts.incrementAndGet()
                    val socket = FakeWebSocket()
                    sockets.add(socket)
                    listeners.add(listener)
                    return socket
                }
            },
            isSessionActive = sessionActive
        )

        fun connect() {
            GeminiLiveWebSocketClient.debugSetActiveSocketCount(0)
            client.connect("AIzaSyFakeKeyForLiveConnectFailFast12345")
        }

        fun openResponse(): Response {
            return Response.Builder()
                .request(Request.Builder().url("https://generativelanguage.googleapis.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .build()
        }
    }

    @Test
    fun liveClientPrefersIpv4AndFailsTheDialInThreeSeconds() {
        val http = GeminiLiveWebSocketClient.createDefaultLiveOkHttpClient()
        assertEquals(GeminiLiveWebSocketClient.SOCKET_OPEN_DEADLINE_MS.toInt(), http.connectTimeoutMillis)
        assertEquals(60_000, http.readTimeoutMillis)
        assertEquals(GeminiLiveWebSocketClient.PING_INTERVAL_SECONDS * 1000, http.pingIntervalMillis.toLong())
        assertTrue(http.dns is GeminiLiveDns)
    }

    @Test
    fun dnsPrefersIpv4WithoutDroppingIpv6OnlyAnswers() {
        val ipv6 = InetAddress.getByAddress(byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))
        val ipv4 = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        val ordered = GeminiLiveDns.preferIpv4(listOf(ipv6, ipv4))
        assertTrue(ordered[0] is Inet4Address)
        assertEquals(ipv4, ordered[0])
        assertEquals(ipv6, ordered[1])
        assertEquals(listOf(ipv6), GeminiLiveDns.preferIpv4(listOf(ipv6)))

        val dns = GeminiLiveDns(
            system = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(ipv6, ipv4)
            },
            lookupTimeoutMs = 1_000L
        )
        val lookedUp = dns.lookup("generativelanguage.googleapis.com")
        assertTrue(lookedUp[0] is Inet4Address)
    }

    @Test
    fun dnsLookupFailsFastWhenTheResolverStalls() {
        val dns = GeminiLiveDns(
            system = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    Thread.sleep(5_000L)
                    return emptyList()
                }
            },
            lookupTimeoutMs = 200L
        )
        val started = System.nanoTime()
        val error = runCatching { dns.lookup("generativelanguage.googleapis.com") }.exceptionOrNull()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(error is UnknownHostException)
        assertTrue(error?.message?.contains("no_socket") == true)
        assertTrue("dns lookup hung for ${elapsedMs}ms", elapsedMs < 2_000L)
    }

    @Test
    fun noSocketWithinThreeSecondsCancelsAndRetriesOnce() {
        val harness = DialHarness(sessionActive = { true })
        val before = AppLogRepository.diagnosticEntries.value.size
        harness.connect()
        assertEquals(1, harness.attempts.get())

        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(GeminiLiveWebSocketClient.SOCKET_OPEN_DEADLINE_MS - 1, TimeUnit.MILLISECONDS)
        assertEquals(1, harness.attempts.get())
        assertTrue(harness.errors.isEmpty())

        looper.idleFor(1, TimeUnit.MILLISECONDS)
        looper.idle()
        assertEquals("first miss retries once", 2, harness.attempts.get())
        assertTrue(harness.sockets[0].isCancelled)
        assertTrue(harness.errors.isEmpty())
        assertTrue(harness.logs.any { it.contains("attempt=1/2") && it.contains("reason=cancelled no_socket") })
        assertTrue(harness.logs.any { it.contains("attempt=2/2") && it.contains("reason=retried") })

        looper.idleFor(GeminiLiveWebSocketClient.SOCKET_OPEN_DEADLINE_MS, TimeUnit.MILLISECONDS)
        looper.idle()
        assertEquals("second miss does not open a third dial", 2, harness.attempts.get())
        assertTrue(harness.sockets[1].isCancelled)
        assertEquals(1, harness.errors.size)
        assertTrue(harness.errors.single().contains("reason=failed no_socket"))
        assertTrue(harness.states.last() is ConnectionState.Error)

        val added = AppLogRepository.diagnosticEntries.value.take(
            AppLogRepository.diagnosticEntries.value.size - before
        )
        assertTrue(added.any { it.type == DiagnosticType.WARNING && it.message.contains("reason=cancelled no_socket") })
        assertTrue(added.any { it.type == DiagnosticType.SESSION_RECONNECT && it.message.contains("reason=retried") })
        assertTrue(added.any { it.type == DiagnosticType.ERROR && it.message.contains("reason=failed no_socket") })
        harness.client.disconnect()
    }

    @Test
    fun idleSessionDoesNotRetryAHungDial() {
        val harness = DialHarness(sessionActive = { false })
        harness.connect()
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(8, TimeUnit.SECONDS)
        assertEquals(1, harness.attempts.get())
        assertTrue(harness.sockets.single().isCancelled)
        assertTrue(harness.errors.isEmpty())
        assertTrue(harness.logs.any { it.contains("retry_suppressed session_idle") })
        assertTrue(harness.states.last() is ConnectionState.Idle)
        harness.client.disconnect()
    }

    @Test
    fun openSocketCancelsTheDeadline() {
        val harness = DialHarness(sessionActive = { true })
        harness.connect()
        harness.listeners.single().onOpen(harness.sockets.single(), harness.openResponse())
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertEquals(1, harness.attempts.get())
        assertFalse(harness.sockets.single().isCancelled)
        assertTrue(harness.errors.isEmpty())
        harness.client.disconnect()
    }

    @Test
    fun failureAfterOpenStillUsesReconnectBackoff() {
        val harness = DialHarness(sessionActive = { true })
        harness.connect()
        val first = harness.listeners.single()
        first.onOpen(harness.sockets.single(), harness.openResponse())
        first.onFailure(harness.sockets.single(), java.io.IOException("connection reset"), null)
        shadowOf(Looper.getMainLooper()).idleFor(2500, TimeUnit.MILLISECONDS)
        assertEquals(2, harness.attempts.get())
        assertTrue(harness.errors.isEmpty())
        harness.client.disconnect()
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertEquals(2, harness.attempts.get())
    }
}

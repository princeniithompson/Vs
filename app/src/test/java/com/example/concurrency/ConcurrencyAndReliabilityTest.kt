package com.example.concurrency

import android.os.Looper
import android.os.SystemClock
import com.example.service.FloatingBubbleService
import com.example.websocket.GeminiLiveError
import com.example.websocket.GeminiLiveWebSocketClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConcurrencyAndReliabilityTest {

    private val testValidApiKey = "AIzaSyFakeKeyForConcurrencyTesting12345"

    /**
     * Fake WebSocket that tracks whether it was cancelled or closed.
     */
    private class FakeWebSocket : WebSocket {
        var isCancelled: Boolean = false
        var isClosed: Boolean = false
        var sentMessages = mutableListOf<String>()

        override fun cancel() {
            isCancelled = true
        }

        override fun close(code: Int, reason: String?): Boolean {
            isClosed = true
            return true
        }

        override fun queueSize(): Long = 0L

        override fun request(): Request = Request.Builder().url("https://generativelanguage.googleapis.com").build()

        override fun send(text: String): Boolean {
            sentMessages.add(text)
            return true
        }

        override fun send(bytes: ByteString): Boolean = true
    }

    @Test
    fun `rapid connect calls result in exactly one connection attempt`() {
        val connectionAttempts = AtomicInteger(0)
        val createdWebSockets = mutableListOf<FakeWebSocket>()

        val fakeClient = OkHttpClient.Builder().build()
        // Subclass OkHttpClient by overriding newWebSocket behavior via custom WebSocket.Factory
        val fakeWebSocketFactory = WebSocket.Factory { request, listener ->
            connectionAttempts.incrementAndGet()
            val socket = FakeWebSocket()
            createdWebSockets.add(socket)
            socket
        }

        // We can pass a customized OkHttpClient whose newWebSocket calls our factory
        val customClient = fakeClient.newBuilder()
            .build()

        // Reflection or using WebSocket.Factory wrapper in OkHttpClient
        val clientWithMockFactory = object : OkHttpClient() {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                connectionAttempts.incrementAndGet()
                val socket = FakeWebSocket()
                createdWebSockets.add(socket)
                return socket
            }
        }

        val liveClient = GeminiLiveWebSocketClient(
            onSetupComplete = {},
            onInterimTranscription = {},
            onFinalizedTranscription = {},
            onStateChanged = {},
            onLog = { _, _, _, _ -> },
            onError = {},
            okHttpClient = clientWithMockFactory
        )

        val threadCount = 8
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        for (i in 0 until threadCount) {
            Thread {
                try {
                    startLatch.await()
                    liveClient.connect(testValidApiKey)
                } catch (_: Exception) {
                } finally {
                    doneLatch.countDown()
                }
            }.start()
        }

        startLatch.countDown()
        assertTrue("All threads should finish connecting", doneLatch.await(3, TimeUnit.SECONDS))

        // Assert only ONE connection attempt was made across all rapid concurrent calls
        assertEquals(
            "Exactly one WebSocket connection should be initiated despite multiple concurrent calls",
            1,
            connectionAttempts.get()
        )
        assertEquals("Exactly one FakeWebSocket instance created", 1, createdWebSockets.size)
        assertTrue("Client indicates active session exists", liveClient.activeSessionExists)

        // Now disconnect and verify cleanup
        liveClient.disconnect()
        assertFalse("Session should be closed after disconnect", liveClient.activeSessionExists)
        assertTrue("WebSocket should be cancelled on disconnect", createdWebSockets[0].isCancelled)

        // After disconnect, a subsequent connect call should be accepted
        liveClient.connect(testValidApiKey)
        assertEquals(
            "New connection should be allowed after disconnect",
            2,
            connectionAttempts.get()
        )
    }

    @Test
    fun `error callbacks are invoked on main thread and structured error is passed`() {
        var errorReportedOnMainThread = false
        var receivedError: String? = null
        var receivedStructuredError: GeminiLiveError? = null

        val liveClient = GeminiLiveWebSocketClient(
            onSetupComplete = {},
            onInterimTranscription = {},
            onFinalizedTranscription = {},
            onStateChanged = {},
            onLog = { _, _, _, _ -> },
            onError = { errMsg ->
                receivedError = errMsg
                errorReportedOnMainThread = (Looper.myLooper() == Looper.getMainLooper())
            },
            onStructuredError = { structured ->
                receivedStructuredError = structured
            }
        )

        // Calling connect with missing key should trigger error on main thread
        try {
            liveClient.connect("")
        } catch (_: IllegalArgumentException) {
        }

        assertTrue("Error must be invoked on the main looper", errorReportedOnMainThread)
        assertNotNull("Error message must not be null", receivedError)
        assertTrue("Structured error must be MissingApiKey", receivedStructuredError is GeminiLiveError.MissingApiKey)
    }

    @Test
    fun `FloatingBubbleService onDestroy cancels pending polish debounce job and clears timestamp`() {
        val serviceController = Robolectric.buildService(FloatingBubbleService::class.java)
        val service = serviceController.get()

        // Set up service
        serviceController.create()

        // Simulate a pending polish debounce job and click timestamp
        val testScope = CoroutineScope(Dispatchers.Main)
        val polishDeferred = CompletableDeferred<Unit>()
        val pendingDebounceJob = testScope.launch {
            delay(5000L)
            polishDeferred.complete(Unit)
        }

        service.polishDebounceJob = pendingDebounceJob
        service.lastPolishClickTime = SystemClock.elapsedRealtime()

        assertTrue("Debounce job should initially be active", pendingDebounceJob.isActive)
        assertTrue("lastPolishClickTime should be non-zero", service.lastPolishClickTime > 0L)

        // Destroy service while polish debounce is pending
        serviceController.destroy()

        // Execute any pending loopers to confirm no delayed actions trigger
        shadowOf(Looper.getMainLooper()).idle()

        // Assert job is cancelled and lastPolishClickTime is cleared
        assertTrue("Pending polish debounce job must be cancelled on service destroy", pendingDebounceJob.isCancelled)
        assertNull("polishDebounceJob field must be null after onDestroy", service.polishDebounceJob)
        assertEquals("lastPolishClickTime must be reset to 0L", 0L, service.lastPolishClickTime)
        assertFalse("Delayed polish action must not have completed", polishDeferred.isCompleted)
    }
}

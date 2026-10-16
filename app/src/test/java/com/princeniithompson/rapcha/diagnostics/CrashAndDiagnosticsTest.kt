package com.princeniithompson.rapcha.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.Service
import android.content.Context
import com.princeniithompson.rapcha.RapchaApplication
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.data.DictationEndedReason
import com.princeniithompson.rapcha.data.TranscriptPrivacy
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.service.FloatingBubbleService
import com.princeniithompson.rapcha.service.MicrophoneForegroundPolicy
import com.princeniithompson.rapcha.service.floating.FloatingDictationSessionManager
import com.princeniithompson.rapcha.websocket.GeminiLiveWebSocketClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashAndDiagnosticsTest {

    @Test
    fun transcriptSummaryMasksEmailAndPhone() {
        val summary = TranscriptPrivacy.summary("Email ada@example.com or call 555-123-4567 today")
        assertFalse(summary.contains("ada@example.com"))
        assertFalse(summary.contains("555-123-4567"))
        assertTrue(summary.contains("[email]"))
        assertTrue(summary.contains("[phone]"))
        assertTrue(summary.contains("words"))
        assertTrue(summary.contains("chars"))
    }

    @Test
    fun missingApiKeyIsNotAConnectionDrop() {
        assertEquals(
            DictationEndedReason.MISSING_API_KEY,
            DictationEndedReason.fromMessage("Gemini API Key is missing or placeholder. Please provide a valid key.")
        )
        assertEquals(
            DictationEndedReason.PING_TIMEOUT,
            DictationEndedReason.fromMessage("sent ping but didn't receive pong")
        )
        assertEquals(
            DictationEndedReason.CONNECTION_DROP,
            DictationEndedReason.fromMessage("connection reset")
        )
    }

    @Test
    fun activeSocketCountNeverGoesNegative() {
        GeminiLiveWebSocketClient.debugSetActiveSocketCount(2)
        assertEquals(1, GeminiLiveWebSocketClient.releaseActiveSocket())
        assertEquals(0, GeminiLiveWebSocketClient.releaseActiveSocket())
        assertEquals(0, GeminiLiveWebSocketClient.releaseActiveSocket())
        GeminiLiveWebSocketClient.debugSetActiveSocketCount(0)
    }

    @Test
    fun idleSocketDoesNotReconnect() {
        GeminiLiveWebSocketClient.debugSetActiveSocketCount(0)
        val attempts = AtomicInteger(0)
        val active = AtomicBoolean(false)
        val client = failingClient(attempts, active)
        client.connect("AIzaSyFakeKeyForCrashDiagnostics12345")
        ShadowLooper.shadowMainLooper().idleFor(8, TimeUnit.SECONDS)
        assertEquals(1, attempts.get())
        assertEquals(0, GeminiLiveWebSocketClient.getActiveSocketCount())
        client.disconnect()
    }

    @Test
    fun activeDictationSchedulesReconnect() {
        GeminiLiveWebSocketClient.debugSetActiveSocketCount(0)
        val attempts = AtomicInteger(0)
        val active = AtomicBoolean(true)
        val client = failingClient(attempts, active)
        client.connect("AIzaSyFakeKeyForCrashDiagnostics12345")
        ShadowLooper.shadowMainLooper().idleFor(2500, TimeUnit.MILLISECONDS)
        assertTrue("expected a reconnect while dictation is active, attempts=${attempts.get()}", attempts.get() >= 2)
        active.set(false)
        client.disconnect()
        ShadowLooper.shadowMainLooper().idleFor(5, TimeUnit.SECONDS)
        GeminiLiveWebSocketClient.debugSetActiveSocketCount(0)
    }

    @Test
    fun crashFileIsLoggedOnceAsCrashEvent() {
        val app = ApplicationProvider.getApplicationContext<RapchaApplication>()
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var chained = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chained = true }
        app.installCrashHandler()
        try {
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(
                Thread.currentThread(),
                IllegalStateException("boom-marker")
            )
            assertTrue(chained)
            val pending = File(app.filesDir, CrashReportStore.FILE_NAME)
            assertTrue(pending.exists())
            assertTrue(pending.readText().contains("boom-marker"))
            RapchaApplication.publishPendingCrash(app)
            val crashes = AppLogRepository.diagnosticEntries.value.filter {
                it.type == DiagnosticType.CRASH && it.message.contains("boom-marker")
            }
            assertEquals(1, crashes.size)
            RapchaApplication.publishPendingCrash(app)
            assertEquals(
                1,
                AppLogRepository.diagnosticEntries.value.count {
                    it.type == DiagnosticType.CRASH && it.message.contains("boom-marker")
                }
            )
            assertFalse(pending.exists())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun historicalExitReasonsAreLoggedOnceIncludingDescription() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val record = ProcessExitRecord(
            timestamp = 1_700_000_000_000L,
            reason = "OTHER",
            description = "ForegroundServiceDidNotStartInTime"
        )
        ProcessExitDiagnostics.logNewExitReasons(context, listOf(record, record))
        ProcessExitDiagnostics.logNewExitReasons(context, listOf(record))
        val matches = AppLogRepository.diagnosticEntries.value.filter {
            it.type == DiagnosticType.PROCESS_EXIT &&
                it.message.contains("ForegroundServiceDidNotStartInTime")
        }
        assertEquals(1, matches.size)
        assertTrue(matches[0].message.contains("description=ForegroundServiceDidNotStartInTime"))
        assertTrue(matches[0].message.contains("reason=OTHER"))
    }

    @Test
    fun injectionDiagnosticsStoreRedactedSummaryAndDoNotAddSessionEnd() {
        val before = AppLogRepository.diagnosticEntries.value.count {
            it.type == DiagnosticType.SESSION_END && it.message.startsWith("Injected")
        }
        AppLogRepository.recordInjectionResult(
            durationMs = 1200L,
            wordCount = 6,
            targetApp = "Keep",
            transcriptText = "Email ada@example.com or call 555-123-4567 today",
            source = DiagnosticSource.APP
        )
        val after = AppLogRepository.diagnosticEntries.value.count {
            it.type == DiagnosticType.SESSION_END && it.message.startsWith("Injected")
        }
        assertEquals(before, after)
        val summary = AppLogRepository.liveStats.value.lastTranscriptSummary
        assertFalse(summary.contains("ada@example.com"))
        assertFalse(summary.contains("555-123-4567"))
        assertTrue(summary.contains("[email]"))
        assertTrue(summary.contains("[phone]"))
    }

    @Test
    fun sessionEndIsLoggedOncePerDictation() {
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
        manager.logSessionEndOnce("dedupe-marker first")
        manager.logSessionEndOnce("dedupe-marker second")
        val matches = AppLogRepository.diagnosticEntries.value.filter { it.message.contains("dedupe-marker") }
        assertEquals(1, matches.size)
        assertEquals(DiagnosticType.SESSION_END, matches[0].type)
    }

    @Test
    fun bubbleInitDoesNotAutoStartFromBackground() {
        assertFalse(FloatingBubbleManager.shouldAutoStartBubbleFromInit(enabled = true, canOverlay = true, uiForeground = false))
        assertTrue(FloatingBubbleManager.shouldAutoStartBubbleFromInit(enabled = true, canOverlay = true, uiForeground = true))
        assertFalse(FloatingBubbleManager.shouldAutoStartBubbleFromInit(enabled = false, canOverlay = true, uiForeground = true))
    }

    @Test
    fun bubbleServiceIsNotSticky() {
        val controller = Robolectric.buildService(FloatingBubbleService::class.java).create()
        val result = controller.get().onStartCommand(null, 0, 1)
        assertEquals(Service.START_NOT_STICKY, result)
        controller.destroy()
    }

    @Test
    fun microphoneForegroundRequiresAnAllowedProcessState() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val activityManager = app.getSystemService(ActivityManager::class.java)
        val background = ActivityManager.RunningAppProcessInfo().apply {
            pid = android.os.Process.myPid()
            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        }
        shadowOf(activityManager).setProcesses(listOf(background))
        assertFalse(MicrophoneForegroundPolicy.isAllowed(app))

        val foregroundService = ActivityManager.RunningAppProcessInfo().apply {
            pid = android.os.Process.myPid()
            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        }
        shadowOf(activityManager).setProcesses(listOf(foregroundService))
        assertTrue(MicrophoneForegroundPolicy.isAllowed(app))
    }

    private fun failingClient(attempts: AtomicInteger, sessionActive: AtomicBoolean): GeminiLiveWebSocketClient {
        val http = object : OkHttpClient() {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                attempts.incrementAndGet()
                val socket = object : WebSocket {
                    override fun cancel() {}
                    override fun close(code: Int, reason: String?): Boolean = true
                    override fun queueSize(): Long = 0L
                    override fun request(): Request = request
                    override fun send(text: String): Boolean = true
                    override fun send(bytes: okio.ByteString): Boolean = true
                }
                listener.onFailure(socket, java.io.IOException("connection reset"), null)
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
            isSessionActive = { sessionActive.get() }
        )
    }
}

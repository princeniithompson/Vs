package com.princeniithompson.rapcha.diagnostics

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.service.floating.LivePolishResponse
import com.princeniithompson.rapcha.service.floating.Stage2Category
import com.princeniithompson.rapcha.service.floating.Stage2LivePolish
import com.princeniithompson.rapcha.service.floating.Stage2Model
import com.princeniithompson.rapcha.websocket.GeminiLiveWebSocketClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkAndDeviceDiagnosticsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        NetworkAndDeviceDiagnostics.resetForTest()
        AppLogRepository.init(context)
        AppLogRepository.clearAllDiagnostics()
    }

    @Test
    fun exportHeaderContainsDeviceAndCurrentNetworkLines() {
        val export = AppLogRepository.getFullExportFormatted()
        assertTrue("Expected Device line in export header", export.contains("Device: "))
        assertTrue("Expected Current Network line in export header", export.contains("Current Network: "))
        assertTrue("Expected Android SDK marker in export header", export.contains("Android "))
    }

    @Test
    fun transitionEvaluatorDetectsTransportSwitchDropsCaptiveAndBandwidthDrop() {
        val wifi = NetworkSnapshot(
            airplaneMode = false,
            connected = true,
            validated = true,
            captivePortal = false,
            metered = false,
            transport = "WIFI",
            downKbps = 50_000,
            upKbps = 20_000
        )
        val cellular = wifi.copy(transport = "CELLULAR", metered = true, downKbps = 12_000)
        val offline = cellular.copy(connected = false, validated = false, transport = "NONE", downKbps = 0, upKbps = 0)
        val captive = wifi.copy(validated = false, captivePortal = true)
        val slowWifi = wifi.copy(downKbps = 256, upKbps = 100)

        val wifiToCell = NetworkAndDeviceDiagnostics.evaluateTransition(wifi, cellular)
        assertNotNull(wifiToCell)
        assertEquals(DiagnosticType.NET_STATE_CHANGE, wifiToCell!!.type)
        assertTrue(wifiToCell.message.contains("transition=WIFI->CELLULAR"))

        val drop = NetworkAndDeviceDiagnostics.evaluateTransition(cellular, offline)
        assertNotNull(drop)
        assertEquals(DiagnosticType.NET_STATE_CHANGE, drop!!.type)
        assertTrue(drop.message.contains("transition=ONLINE->OFFLINE"))

        val portal = NetworkAndDeviceDiagnostics.evaluateTransition(wifi, captive)
        assertNotNull(portal)
        assertEquals(DiagnosticType.NET_STATE_CHANGE, portal!!.type)
        assertTrue(portal.message.contains("CAPTIVE_PORTAL_DETECTED"))

        val bwDrop = NetworkAndDeviceDiagnostics.evaluateTransition(wifi, slowWifi)
        assertNotNull(bwDrop)
        assertEquals(DiagnosticType.NET_SLOW_WARN, bwDrop!!.type)
        assertTrue(bwDrop.message.contains("bandwidth_drop"))

        assertNull(NetworkAndDeviceDiagnostics.evaluateTransition(wifi, wifi))
    }

    @Test
    fun airplaneModeBroadcastLogsNetAirplaneModeEvent() {
        NetworkAndDeviceDiagnostics.startMonitoring(context)
        val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
            putExtra("state", true)
        }
        context.sendBroadcast(intent)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val matches = AppLogRepository.diagnosticEntries.value.filter {
            it.type == DiagnosticType.NET_AIRPLANE_MODE && it.message.contains("airplane_mode=on")
        }
        assertTrue("Expected NET_AIRPLANE_MODE event on airplane broadcast", matches.isNotEmpty())
        NetworkAndDeviceDiagnostics.stopMonitoring(context)
    }

    @Test
    fun stage2SlowCallAndFailureEmitNetworkWarnings() {
        val entries = mutableListOf<Pair<DiagnosticType, String>>()
        var time = 1_000L
        val slowClock = {
            val current = time
            time += 6_200L
            current
        }

        Stage2LivePolish.execute(
            rawTranscript = "hello test transcript",
            category = Stage2Category.GENERAL,
            protectedTerms = emptyList(),
            stage1Dropped = false,
            apiKey = "AIzaSyTestKey1234567890",
            model = Stage2Model {
                LivePolishResponse(text = "Hello test transcript.", error = null, timedOut = false)
            },
            nowMs = slowClock,
            log = { type, msg -> entries.add(type to msg) }
        )
        assertTrue(
            "Expected NET_SLOW_WARN when latency exceeds 5s",
            entries.any { it.first == DiagnosticType.NET_SLOW_WARN && it.second.contains("stage=stage2_polish") }
        )

        entries.clear()
        Stage2LivePolish.execute(
            rawTranscript = "hello test transcript",
            category = Stage2Category.GENERAL,
            protectedTerms = emptyList(),
            stage1Dropped = false,
            apiKey = "AIzaSyTestKey1234567890",
            model = Stage2Model {
                LivePolishResponse(text = null, error = "SocketTimeoutException", timedOut = true)
            },
            log = { type, msg -> entries.add(type to msg) }
        )
        assertTrue(
            "Expected NET_FAILURE on failed Stage 2 call",
            entries.any { it.first == DiagnosticType.NET_FAILURE && it.second.contains("SocketTimeoutException") }
        )
    }

    @Test
    fun webSocketFailureLogsDetailedNetFailureDiagnostic() {
        NetworkAndDeviceDiagnostics.initContext(context)
        GeminiLiveWebSocketClient.debugSetActiveSocketCount(0)
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
                listener.onFailure(socket, IOException("Software caused connection abort"), null)
                return socket
            }
        }
        val client = GeminiLiveWebSocketClient(
            onSetupComplete = {},
            onInterimTranscription = {},
            onFinalizedTranscription = {},
            onStateChanged = {},
            onLog = { _, _, _, _ -> },
            onError = {},
            okHttpClient = http,
            diagnosticSource = DiagnosticSource.BUBBLE,
            isSessionActive = { false }
        )
        client.connect("AIzaSyFakeKeyForNetDiagnostics12345")
        val failures = AppLogRepository.diagnosticEntries.value.filter {
            it.type == DiagnosticType.NET_FAILURE && it.message.contains("IOException")
        }
        assertTrue("Expected NET_FAILURE diagnostic with exception class", failures.isNotEmpty())
        assertTrue(failures.first().message.contains("Software caused connection abort"))
        client.disconnect()
        GeminiLiveWebSocketClient.debugSetActiveSocketCount(0)
    }
}

package com.princeniithompson.rapcha.diagnostics

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.princeniithompson.rapcha.BuildConfig
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import java.util.concurrent.atomic.AtomicBoolean

data class DeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val brand: String,
    val device: String,
    val androidVersion: String,
    val sdkInt: Int,
    val formFactor: String,
    val smallestWidthDp: Int,
    val ramTotalMb: Long,
    val lowRamDevice: Boolean,
    val appVersion: String,
    val versionCode: Int
) {
    fun displayName(): String {
        val mfg = manufacturer.trim()
        val mdl = model.trim()
        return when {
            mfg.isBlank() -> mdl.ifBlank { "Unknown" }
            mdl.startsWith(mfg, ignoreCase = true) -> mdl
            else -> "$mfg $mdl".trim()
        }
    }

    fun exportHeaderLine(): String {
        val ramLabel = if (ramTotalMb > 0L) "${ramTotalMb}MB" else "unknown"
        val lowRamLabel = if (lowRamDevice) "yes" else "no"
        val codename = device.ifBlank { "unknown" }
        return "${displayName()} ($codename) | Android $androidVersion (API $sdkInt) | $formFactor (sw${smallestWidthDp}dp) | RAM=$ramLabel low_ram=$lowRamLabel | App=$appVersion($versionCode)"
    }

    fun compactSessionTag(): String {
        val safeName = displayName().replace(Regex("\\s+"), "_").take(36)
        val ramTag = if (ramTotalMb > 0L) " ram_mb=$ramTotalMb" else ""
        return "device=$safeName form=$formFactor android=${androidVersion}_sdk$sdkInt$ramTag low_ram=${if (lowRamDevice) "yes" else "no"}"
    }
}

data class NetworkSnapshot(
    val airplaneMode: Boolean,
    val connected: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
    val metered: Boolean,
    val transport: String,
    val downKbps: Int,
    val upKbps: Int
) {
    val isOfflineOrBlocked: Boolean
        get() = airplaneMode || !connected || captivePortal

    val isLowBandwidth: Boolean
        get() = connected && ((downKbps in 1..LOW_DOWN_KBPS) || (upKbps in 1..LOW_UP_KBPS))

    val isSlowOrDegraded: Boolean
        get() = (connected && !validated) || captivePortal || isLowBandwidth

    fun stateSignature(): String {
        return "$airplaneMode|$connected|$validated|$captivePortal|$metered|$transport"
    }

    fun offlineReason(): String? {
        return when {
            airplaneMode -> "airplane_mode"
            !connected -> "no_internet"
            captivePortal -> "captive_portal"
            !validated -> "unvalidated_internet"
            else -> null
        }
    }

    fun toLogTag(): String {
        return "net=$transport online=${yesNo(connected)} validated=${yesNo(validated)} captive=${yesNo(captivePortal)} metered=${yesNo(metered)} airplane=${if (airplaneMode) "on" else "off"} down_kbps=$downKbps up_kbps=$upKbps"
    }

    private fun yesNo(v: Boolean): String = if (v) "yes" else "no"

    companion object {
        const val LOW_DOWN_KBPS = 512
        const val LOW_UP_KBPS = 128

        val UNKNOWN = NetworkSnapshot(
            airplaneMode = false,
            connected = false,
            validated = false,
            captivePortal = false,
            metered = false,
            transport = "UNKNOWN",
            downKbps = 0,
            upKbps = 0
        )
    }
}

/**
 * Process-wide device identity and network state diagnostics monitor.
 * Captures transport switches (Wi-Fi <-> Cellular), offline drops, airplane mode changes,
 * captive/unvalidated connections, slow handshakes/REST calls, and network error root causes.
 */
object NetworkAndDeviceDiagnostics {
    private const val TAG = "NetDeviceDiag"
    const val SLOW_WS_HANDSHAKE_MS = 2_000L
    const val SLOW_STAGE2_MS = 5_000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedDeviceSnapshot: DeviceSnapshot? = null

    private val started = AtomicBoolean(false)
    private val stateLock = Any()

    @Volatile
    private var lastNetworkSnapshot: NetworkSnapshot? = null

    @Volatile
    private var lastAirplaneMode: Boolean? = null

    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var airplaneReceiver: BroadcastReceiver? = null

    fun initContext(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        if (cachedDeviceSnapshot == null) {
            cachedDeviceSnapshot = readDeviceSnapshot(app)
        }
    }

    fun startMonitoring(context: Context) {
        val app = context.applicationContext ?: context
        initContext(app)
        if (!started.compareAndSet(false, true)) return

        val device = readDeviceSnapshot(app)
        cachedDeviceSnapshot = device
        AppLogRepository.setDeviceSummary(device.exportHeaderLine())
        AppLogRepository.logEvent(
            DiagnosticSource.APP,
            DiagnosticType.DEVICE_INFO,
            "${device.exportHeaderLine()} | ${readNetworkSnapshot(app).toLogTag()}"
        )

        val initialNet = readNetworkSnapshot(app)
        synchronized(stateLock) {
            lastNetworkSnapshot = initialNet
            lastAirplaneMode = initialNet.airplaneMode
        }
        if (initialNet.airplaneMode) {
            AppLogRepository.logEvent(
                DiagnosticSource.APP,
                DiagnosticType.NET_AIRPLANE_MODE,
                "airplane_mode=on phase=startup ${initialNet.toLogTag()}"
            )
        } else if (!initialNet.connected) {
            AppLogRepository.logEvent(
                DiagnosticSource.APP,
                DiagnosticType.NET_STATE_CHANGE,
                "transition=STARTUP_OFFLINE ${initialNet.toLogTag()}"
            )
        } else if (initialNet.isSlowOrDegraded) {
            AppLogRepository.logEvent(
                DiagnosticSource.APP,
                DiagnosticType.NET_SLOW_WARN,
                "stage=startup reason=${initialNet.offlineReason() ?: "low_bandwidth"} ${initialNet.toLogTag()}"
            )
        }

        registerConnectivityMonitor(app)
        registerAirplaneModeReceiver(app)
    }

    fun stopMonitoring(context: Context) {
        val app = context.applicationContext ?: context
        if (!started.compareAndSet(true, false)) return
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            connectivityCallback?.let { cb -> cm?.unregisterNetworkCallback(cb) }
        } catch (_: Throwable) {
        } finally {
            connectivityCallback = null
        }
        try {
            airplaneReceiver?.let { r -> app.unregisterReceiver(r) }
        } catch (_: Throwable) {
        } finally {
            airplaneReceiver = null
        }
    }

    fun deviceExportSummary(context: Context? = appContext): String {
        val ctx = context ?: appContext
        val snap = if (ctx != null) readDeviceSnapshot(ctx) else (cachedDeviceSnapshot ?: readDeviceSnapshot(null))
        cachedDeviceSnapshot = snap
        return snap.exportHeaderLine()
    }

    fun compactDeviceTag(context: Context? = appContext): String {
        val ctx = context ?: appContext
        val snap = if (ctx != null) readDeviceSnapshot(ctx) else (cachedDeviceSnapshot ?: readDeviceSnapshot(null))
        cachedDeviceSnapshot = snap
        return snap.compactSessionTag()
    }

    fun currentNetworkSnapshot(context: Context? = appContext): NetworkSnapshot {
        val ctx = context ?: appContext ?: return NetworkSnapshot.UNKNOWN
        return readNetworkSnapshot(ctx)
    }

    fun currentTag(context: Context? = appContext): String {
        val ctx = context ?: appContext ?: return "net=unknown"
        return readNetworkSnapshot(ctx).toLogTag()
    }

    fun checkOfflineOrAirplaneReason(context: Context? = appContext): String? {
        val ctx = context ?: appContext ?: return null
        return readNetworkSnapshot(ctx).offlineReason()
    }

    fun recordSlowNetworkWarning(
        source: DiagnosticSource,
        stage: String,
        latencyMs: Long,
        details: String = "",
        context: Context? = appContext
    ) {
        val netTag = currentTag(context)
        val extra = if (details.isNotBlank()) " $details" else ""
        AppLogRepository.logEvent(
            source,
            DiagnosticType.NET_SLOW_WARN,
            "stage=$stage latency_ms=$latencyMs$extra $netTag"
        )
    }

    fun recordNetworkFailure(
        source: DiagnosticSource,
        stage: String,
        reason: String,
        httpCode: Int? = null,
        exceptionClass: String? = null,
        context: Context? = appContext
    ) {
        val netTag = currentTag(context)
        val httpPart = if (httpCode != null && httpCode > 0) " http=$httpCode" else ""
        val excPart = if (!exceptionClass.isNullOrBlank()) " exception=$exceptionClass" else ""
        val cleanReason = reason.replace('\n', ' ').trim().take(200)
        AppLogRepository.logEvent(
            source,
            DiagnosticType.NET_FAILURE,
            "stage=$stage$httpPart$excPart reason=$cleanReason $netTag"
        )
    }

    internal fun evaluateTransition(
        previous: NetworkSnapshot?,
        current: NetworkSnapshot
    ): TransitionReport? {
        if (previous == null) return null
        if (previous.stateSignature() == current.stateSignature()) {
            if (current.connected && !previous.isLowBandwidth && current.isLowBandwidth) {
                return TransitionReport(
                    type = DiagnosticType.NET_SLOW_WARN,
                    message = "stage=bandwidth_drop reason=low_bandwidth prev_down_kbps=${previous.downKbps} curr_down_kbps=${current.downKbps} ${current.toLogTag()}"
                )
            }
            return null
        }

        val transition = when {
            previous.connected && !current.connected -> "ONLINE->OFFLINE"
            !previous.connected && current.connected -> "OFFLINE->ONLINE"
            previous.transport != current.transport -> "${previous.transport}->${current.transport}"
            previous.validated && !current.validated -> "VALIDATED->UNVALIDATED"
            !previous.validated && current.validated -> "UNVALIDATED->VALIDATED"
            !previous.captivePortal && current.captivePortal -> "CAPTIVE_PORTAL_DETECTED"
            previous.metered != current.metered -> if (current.metered) "UNMETERED->METERED" else "METERED->UNMETERED"
            else -> "CAPABILITIES_CHANGED"
        }

        return TransitionReport(
            type = DiagnosticType.NET_STATE_CHANGE,
            message = "transition=$transition prev_net=${previous.transport} curr_net=${current.transport} ${current.toLogTag()}"
        )
    }

    data class TransitionReport(
        val type: DiagnosticType,
        val message: String
    )

    fun readDeviceSnapshot(context: Context?): DeviceSnapshot {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val brand = Build.BRAND.orEmpty().trim()
        val device = Build.DEVICE.orEmpty().trim()
        val androidVer = Build.VERSION.RELEASE.orEmpty().ifBlank { Build.VERSION.SDK_INT.toString() }
        val sdkInt = Build.VERSION.SDK_INT

        var swDp = 0
        var ramMb = 0L
        var lowRam = false
        if (context != null) {
            try {
                swDp = context.resources.configuration.smallestScreenWidthDp
            } catch (_: Throwable) {
            }
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am != null) {
                    lowRam = am.isLowRamDevice
                    val mi = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mi)
                    if (mi.totalMem > 0L) {
                        ramMb = mi.totalMem / (1024L * 1024L)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        val formFactor = if (swDp >= 600) "tablet" else "phone"
        return DeviceSnapshot(
            manufacturer = manufacturer,
            model = model,
            brand = brand,
            device = device,
            androidVersion = androidVer,
            sdkInt = sdkInt,
            formFactor = formFactor,
            smallestWidthDp = swDp,
            ramTotalMb = ramMb,
            lowRamDevice = lowRam,
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE
        )
    }

    fun readNetworkSnapshot(context: Context): NetworkSnapshot {
        val airplane = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        } catch (_: Throwable) {
            false
        }
        val cm = try {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (_: Throwable) {
            null
        } ?: return NetworkSnapshot(
            airplaneMode = airplane,
            connected = false,
            validated = false,
            captivePortal = false,
            metered = false,
            transport = "NONE",
            downKbps = 0,
            upKbps = 0
        )

        val active = try {
            cm.activeNetwork
        } catch (_: Throwable) {
            null
        }
        val caps = try {
            active?.let { cm.getNetworkCapabilities(it) }
        } catch (_: Throwable) {
            null
        }

        if (caps == null) {
            return NetworkSnapshot(
                airplaneMode = airplane,
                connected = false,
                validated = false,
                captivePortal = false,
                metered = false,
                transport = "NONE",
                downKbps = 0,
                upKbps = 0
            )
        }

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val captivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val transport = resolveTransportLabel(caps)
        val downKbps = caps.linkDownstreamBandwidthKbps.coerceAtLeast(0)
        val upKbps = caps.linkUpstreamBandwidthKbps.coerceAtLeast(0)

        return NetworkSnapshot(
            airplaneMode = airplane,
            connected = hasInternet,
            validated = validated,
            captivePortal = captivePortal,
            metered = metered,
            transport = transport,
            downKbps = downKbps,
            upKbps = upKbps
        )
    }

    internal fun resolveTransportLabel(caps: NetworkCapabilities): String {
        val parts = ArrayList<String>(3)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts.add("WIFI")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts.add("CELLULAR")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) parts.add("ETHERNET")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) parts.add("BLUETOOTH")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) parts.add("VPN")
        if (parts.isEmpty()) return "OTHER"
        return parts.joinToString("+")
    }

    private fun registerConnectivityMonitor(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                inspectAndLogNetworkChange(context)
            }

            override fun onLost(network: Network) {
                inspectAndLogNetworkChange(context)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                inspectAndLogNetworkChange(context)
            }

            override fun onUnavailable() {
                inspectAndLogNetworkChange(context)
            }
        }
        connectivityCallback = cb
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, cb)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed registering connectivity monitor: ${t.javaClass.simpleName}")
        }
    }

    private fun registerAirplaneModeReceiver(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent?) {
                if (intent?.action != Intent.ACTION_AIRPLANE_MODE_CHANGED) return
                val enabled = intent.getBooleanExtra("state", readNetworkSnapshot(ctx).airplaneMode)
                val currentNet = readNetworkSnapshot(ctx).copy(airplaneMode = enabled)
                val changed = synchronized(stateLock) {
                    val prev = lastAirplaneMode
                    lastAirplaneMode = enabled
                    lastNetworkSnapshot = currentNet
                    prev == null || prev != enabled
                }
                if (changed) {
                    val label = if (enabled) "on" else "off"
                    AppLogRepository.logEvent(
                        DiagnosticSource.APP,
                        DiagnosticType.NET_AIRPLANE_MODE,
                        "airplane_mode=$label ${currentNet.toLogTag()}"
                    )
                }
            }
        }
        airplaneReceiver = receiver
        try {
            val filter = IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed registering airplane receiver: ${t.javaClass.simpleName}")
        }
    }

    internal fun inspectAndLogNetworkChange(context: Context) {
        val current = readNetworkSnapshot(context)
        val report = synchronized(stateLock) {
            val prev = lastNetworkSnapshot
            lastNetworkSnapshot = current
            lastAirplaneMode = current.airplaneMode
            evaluateTransition(prev, current)
        } ?: return

        AppLogRepository.logEvent(
            DiagnosticSource.APP,
            report.type,
            report.message
        )
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTest() {
        started.set(false)
        synchronized(stateLock) {
            lastNetworkSnapshot = null
            lastAirplaneMode = null
        }
        cachedDeviceSnapshot = null
        appContext = null
    }
}

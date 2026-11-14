package com.princeniithompson.rapcha.service.floating

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.PowerManager
import android.view.Choreographer
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.diagnostics.BubbleHealthLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RenderModePreference(val stored: String, val label: String) {
    AUTO("auto", "Auto (Adapts to RAM & battery)"),
    ALWAYS_FULL("full", "Always full visuals"),
    ALWAYS_LIGHT("light", "Always light (Battery & low-RAM friendly)");

    companion object {
        fun fromStored(value: String?): RenderModePreference {
            return entries.firstOrNull { it.stored.equals(value, ignoreCase = true) } ?: AUTO
        }
    }
}

object RenderBudgetEngine {
    const val LOW_RAM_BYTES_THRESHOLD = 4_831_838_208L // 4.5 GB
    const val LOW_MEMORY_CLASS_MB = 256
    const val JANK_FRAME_THRESHOLD_NS = 32_000_000L // 32 ms
    const val JANK_WINDOW_NS = 3_000_000_000L // 3 s
    const val JANK_RATIO_TRIGGER = 0.20

    /** v23: 4 GB and 6 GB phones (Galaxy A15/A17 class) start in light mode. */
    const val WEAK_DEVICE_RAM_BYTES = 6_979_321_856L // 6.5 GB
    /** v23: fastest core below 2.5 GHz = budget SoC (Helio G99 2.2, Dimensity 6100+ 2.2, Exynos 1330 2.4). */
    const val WEAK_CPU_MAX_FREQ_KHZ = 2_500_000L

    /** v23: Build.SOC_MODEL prefixes of budget chips with weak GPUs (A15/A17 and similar). */
    val WEAK_SOC_PREFIXES = listOf(
        "MT6789", // Helio G99 (A15 4G, A17 4G)
        "MT6835", // Dimensity 6100+ (A15 5G)
        "MT6833", "MT6853", "MT6877", "MT6768", "MT6769", "MT6785", "MT6765", "MT6762",
        "S5E8535", // Exynos 1330 (A17 5G, A14 5G)
        "S5E8825", // Exynos 1280
        "S5E8835", // Exynos 1380
        "S5E3830", "S5E9611",
        "SM6225", "SM6115", "SM4350", "SM4375", "SM4450", "SM6375", "SM6125", "SM6150", "SM7125",
        "UMS9230", "T606", "T610", "T612", "T616", "T618"
    )

    fun isWeakSoc(socModel: String?): Boolean {
        val model = socModel?.trim()?.uppercase() ?: return false
        if (model.isEmpty()) return false
        return WEAK_SOC_PREFIXES.any { model.startsWith(it) }
    }

    /** True for budget hardware that should not run blur + continuous glow by default. */
    fun isWeakDevice(
        isLowRamDevice: Boolean,
        totalRamBytes: Long,
        socModel: String?,
        maxCpuFreqKhz: Long
    ): Boolean {
        return isLowRamDevice ||
            (totalRamBytes in 1L..WEAK_DEVICE_RAM_BYTES) ||
            isWeakSoc(socModel) ||
            (maxCpuFreqKhz in 1L until WEAK_CPU_MAX_FREQ_KHZ)
    }

    fun shouldUseLowPower(
        preference: RenderModePreference,
        isLowRamDevice: Boolean,
        totalRamBytes: Long,
        memoryClassMb: Int,
        trimMemoryLowOrCritical: Boolean,
        isPowerSaveMode: Boolean,
        jankDetected: Boolean,
        weakDevice: Boolean = false
    ): Boolean {
        return when (preference) {
            RenderModePreference.ALWAYS_FULL -> false
            RenderModePreference.ALWAYS_LIGHT -> true
            RenderModePreference.AUTO -> {
                weakDevice ||
                isLowRamDevice ||
                    (totalRamBytes in 1L until LOW_RAM_BYTES_THRESHOLD) ||
                    (memoryClassMb in 1..LOW_MEMORY_CLASS_MB) ||
                    trimMemoryLowOrCritical ||
                    isPowerSaveMode ||
                    jankDetected
            }
        }
    }

    fun shouldMonitorFrames(
        overlayVisible: Boolean,
        preference: RenderModePreference,
        currentLowPower: Boolean,
        jankDetected: Boolean
    ): Boolean {
        return overlayVisible &&
            preference == RenderModePreference.AUTO &&
            !currentLowPower &&
            !jankDetected
    }
}

object RenderBudget {
    private const val PREFS_NAME = "voxstream_bubble_prefs"
    private const val KEY_RENDER_MODE = "render_mode_preference"

    private val _preference = MutableStateFlow(RenderModePreference.AUTO)
    val preference: StateFlow<RenderModePreference> = _preference.asStateFlow()

    private val _lowPower = MutableStateFlow(false)
    val lowPower: StateFlow<Boolean> = _lowPower.asStateFlow()

    @Volatile
    private var trimMemoryLowOrCritical = false

    @Volatile
    private var jankDetected = false

    @Volatile
    private var lastTriggerReason = "none"

    @Volatile
    private var monitoringFrames = false

    @Volatile
    private var lastOverlayVisibleRequested = false

    private var windowStartNs = 0L
    private var lastFrameNs = 0L
    private var totalFramesInWindow = 0
    private var slowFramesInWindow = 0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!monitoringFrames) return
            if (windowStartNs == 0L) {
                windowStartNs = frameTimeNanos
                lastFrameNs = frameTimeNanos
            } else {
                val delta = frameTimeNanos - lastFrameNs
                lastFrameNs = frameTimeNanos
                totalFramesInWindow++
                if (delta > RenderBudgetEngine.JANK_FRAME_THRESHOLD_NS) {
                    slowFramesInWindow++
                }
                if ((frameTimeNanos - windowStartNs) >= RenderBudgetEngine.JANK_WINDOW_NS) {
                    val ratio = if (totalFramesInWindow > 0) {
                        slowFramesInWindow.toDouble() / totalFramesInWindow.toDouble()
                    } else 0.0
                    val hitJank = totalFramesInWindow >= 12 && ratio > RenderBudgetEngine.JANK_RATIO_TRIGGER
                    if (hitJank && !jankDetected) {
                        jankDetected = true
                        appContext?.let {
                            refresh(
                                context = it,
                                reason = "jank_ratio=${"%.2f".format(ratio)} slow=$slowFramesInWindow/$totalFramesInWindow"
                            )
                        }
                    }
                    windowStartNs = frameTimeNanos
                    totalFramesInWindow = 0
                    slowFramesInWindow = 0
                }
            }
            if (monitoringFrames) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var weakDeviceCache: Boolean? = null

    @Volatile
    private var weakDeviceDetail: String = ""

    private fun readMaxCpuFreqKhz(): Long {
        var max = 0L
        try {
            val cpus = java.io.File("/sys/devices/system/cpu").listFiles { f ->
                f.name.matches(Regex("cpu[0-9]+"))
            } ?: return 0L
            for (cpu in cpus) {
                val f = java.io.File(cpu, "cpufreq/cpuinfo_max_freq")
                val v = try { f.readText().trim().toLongOrNull() ?: 0L } catch (_: Throwable) { 0L }
                if (v > max) max = v
            }
        } catch (_: Throwable) {
        }
        return max
    }

    private fun weakDevice(isLowRam: Boolean, totalBytes: Long): Boolean {
        weakDeviceCache?.let { return it }
        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) {
            try { android.os.Build.SOC_MODEL } catch (_: Throwable) { null }
        } else {
            null
        } ?: android.os.Build.HARDWARE
        val freq = readMaxCpuFreqKhz()
        val weak = RenderBudgetEngine.isWeakDevice(isLowRam, totalBytes, soc, freq)
        weakDeviceDetail = "soc=$soc max_cpu_khz=$freq"
        weakDeviceCache = weak
        return weak
    }

    fun init(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _preference.value = RenderModePreference.fromStored(prefs.getString(KEY_RENDER_MODE, null))
        refresh(app, reason = "init")
    }

    fun setPreference(context: Context, pref: RenderModePreference) {
        val app = context.applicationContext ?: context
        appContext = app
        _preference.value = pref
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RENDER_MODE, pref.stored)
            .apply()
        refresh(app, reason = "user_${pref.stored}")
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(context: Context, level: Int) {
        val app = context.applicationContext ?: context
        appContext = app
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            trimMemoryLowOrCritical = true
            refresh(app, reason = "trim_memory_$level")
        }
    }

    fun setOverlayFrameMonitoring(active: Boolean) {
        lastOverlayVisibleRequested = active
        val effective = RenderBudgetEngine.shouldMonitorFrames(
            overlayVisible = active,
            preference = _preference.value,
            currentLowPower = _lowPower.value,
            jankDetected = jankDetected
        )
        if (effective == monitoringFrames) return
        monitoringFrames = effective
        windowStartNs = 0L
        lastFrameNs = 0L
        totalFramesInWindow = 0
        slowFramesInWindow = 0
        try {
            val choreographer = Choreographer.getInstance()
            if (effective) {
                choreographer.postFrameCallback(frameCallback)
            } else {
                choreographer.removeFrameCallback(frameCallback)
            }
        } catch (_: Throwable) {
            monitoringFrames = false
        }
    }

    fun refresh(context: Context, reason: String = "check") {
        val app = context.applicationContext ?: context
        appContext = app
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val memInfo = ActivityManager.MemoryInfo()
        try {
            am?.getMemoryInfo(memInfo)
        } catch (_: Throwable) {}

        val isLowRam = try { am?.isLowRamDevice == true } catch (_: Throwable) { false }
        val totalBytes = memInfo.totalMem
        val memoryClass = try { am?.memoryClass ?: 0 } catch (_: Throwable) { 0 }
        val powerSave = try { pm?.isPowerSaveMode == true } catch (_: Throwable) { false }

        val weak = weakDevice(isLowRam, totalBytes)
        val shouldLowPower = RenderBudgetEngine.shouldUseLowPower(
            preference = _preference.value,
            isLowRamDevice = isLowRam,
            totalRamBytes = totalBytes,
            memoryClassMb = memoryClass,
            trimMemoryLowOrCritical = trimMemoryLowOrCritical,
            isPowerSaveMode = powerSave,
            jankDetected = jankDetected,
            weakDevice = weak
        )

        val previous = _lowPower.value
        _lowPower.value = shouldLowPower
        if (lastOverlayVisibleRequested) {
            setOverlayFrameMonitoring(true)
        }
        if (shouldLowPower != previous || (shouldLowPower && reason != lastTriggerReason && reason != "check")) {
            lastTriggerReason = reason
            BubbleHealthLog.record(
                context = app,
                type = DiagnosticType.LOW_POWER_RENDER,
                message = "enabled=$shouldLowPower pref=${_preference.value.stored} reason=$reason low_ram=$isLowRam total_mb=${totalBytes / (1024 * 1024)} mem_class=$memoryClass power_save=$powerSave trim=$trimMemoryLowOrCritical jank=$jankDetected weak_device=$weak $weakDeviceDetail"
            )
        }
    }
}

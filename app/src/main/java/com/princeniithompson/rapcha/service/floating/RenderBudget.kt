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

    fun shouldUseLowPower(
        preference: RenderModePreference,
        isLowRamDevice: Boolean,
        totalRamBytes: Long,
        memoryClassMb: Int,
        trimMemoryLowOrCritical: Boolean,
        isPowerSaveMode: Boolean,
        jankDetected: Boolean
    ): Boolean {
        return when (preference) {
            RenderModePreference.ALWAYS_FULL -> false
            RenderModePreference.ALWAYS_LIGHT -> true
            RenderModePreference.AUTO -> {
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

        val shouldLowPower = RenderBudgetEngine.shouldUseLowPower(
            preference = _preference.value,
            isLowRamDevice = isLowRam,
            totalRamBytes = totalBytes,
            memoryClassMb = memoryClass,
            trimMemoryLowOrCritical = trimMemoryLowOrCritical,
            isPowerSaveMode = powerSave,
            jankDetected = jankDetected
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
                message = "enabled=$shouldLowPower pref=${_preference.value.stored} reason=$reason low_ram=$isLowRam total_mb=${totalBytes / (1024 * 1024)} mem_class=$memoryClass power_save=$powerSave trim=$trimMemoryLowOrCritical jank=$jankDetected"
            )
        }
    }
}

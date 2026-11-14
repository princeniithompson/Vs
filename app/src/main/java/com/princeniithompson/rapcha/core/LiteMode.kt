package com.princeniithompson.rapcha.core

import android.content.Context
import android.util.Log

/** User override for Lite mode. AUTO follows the weak-device check. */
enum class LitePreference(val stored: String, val label: String) {
    AUTO("auto", "Auto (on for weak phones)"),
    ON("on", "Always on"),
    OFF("off", "Always off");

    companion object {
        fun fromStored(value: String?): LitePreference =
            entries.firstOrNull { it.stored.equals(value, ignoreCase = true) } ?: AUTO
    }
}

/**
 * v24 Lite mode: on weak phones Rapcha only records, runs Gemini Smart, types the text, and stops.
 * Decided once per process in Application.onCreate, before anything heavy starts, so features
 * that Lite turns off are never initialised. A preference change applies on the next app start.
 */
object LiteMode {
    private const val TAG = "LiteMode"
    // Own tiny prefs file: read on the main thread at process start, so keep it small.
    private const val PREFS_NAME = "rapcha_lite"
    private const val KEY_PREFERENCE = "lite_preference"

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var weakDevice: Boolean = false
        private set

    @Volatile
    private var decided = false

    fun resolve(preference: LitePreference, weakDevice: Boolean): Boolean = when (preference) {
        LitePreference.ON -> true
        LitePreference.OFF -> false
        LitePreference.AUTO -> weakDevice
    }

    /** Lite sessions: one-step Gemini Smart capture, never Live / Lyra / three-step. */
    fun smartOnlyPlan() = com.princeniithompson.rapcha.service.floating.PolishSectionPlan(
        mode = com.princeniithompson.rapcha.service.floating.SectionPolishMode.SMART,
        stage1 = com.princeniithompson.rapcha.service.floating.Stage1Capture.SMART,
        threeStep = false
    )

    /** Idempotent. Safe to call from Application, services and the activity. */
    @Synchronized
    fun decide(context: Context): Boolean {
        if (decided) return enabled
        val app = context.applicationContext ?: context
        val weak = try {
            com.princeniithompson.rapcha.service.floating.RenderBudget.detectWeakDevice(app)
        } catch (t: Throwable) {
            Log.w(TAG, "Weak-device check failed", t)
            false
        }
        weakDevice = weak
        enabled = resolve(preference(app), weak)
        decided = true
        Log.i(TAG, "lite=$enabled weak_device=$weak")
        return enabled
    }

    fun preference(context: Context): LitePreference {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return LitePreference.fromStored(prefs.getString(KEY_PREFERENCE, null))
    }

    /** Stored now; [enabled] changes on the next process start (features are gated at init). */
    fun setPreference(context: Context, preference: LitePreference) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFERENCE, preference.stored)
            .apply()
    }

    internal fun setForTest(lite: Boolean) {
        enabled = lite
        decided = true
    }

    internal fun resetForTest() {
        enabled = false
        weakDevice = false
        decided = false
    }
}

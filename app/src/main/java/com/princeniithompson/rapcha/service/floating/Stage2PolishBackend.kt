package com.princeniithompson.rapcha.service.floating

import android.content.SharedPreferences

/**
 * Where Complete's Stage-2 polish runs.
 * [NON_LIVE] is the default and leaves today's Complete and Polish-button behavior alone.
 * [LIVE] forces Stage 1 verbatim and polishes on Complete with gemini-3.8-live.
 */
enum class Stage2PolishBackend {
    NON_LIVE,
    LIVE;

    companion object {
        const val PREFS_KEY = "stage2_polish_backend"
        private const val STORED_LIVE = "live"

        fun fromStored(value: String?): Stage2PolishBackend {
            return if (value == STORED_LIVE) LIVE else NON_LIVE
        }
    }

    fun storedValue(): String = if (this == LIVE) STORED_LIVE else "non_live"
}

/**
 * Toggle ON uses the Live model. Toggle OFF stays on the unary flash chain.
 * gemini-3.8-live is never a fallback for the off path, and flash is not the on path.
 */
object Stage2PolishRoute {
    fun usesLiveModel(liveTwoStep: Boolean): Boolean = liveTwoStep

    fun model(liveTwoStep: Boolean): String? {
        return if (liveTwoStep) com.princeniithompson.rapcha.config.RapchaConfig.STAGE2_LIVE_MODEL else null
    }
}

object Stage2PolishSettings {
    fun read(prefs: SharedPreferences): Stage2PolishBackend {
        return Stage2PolishBackend.fromStored(prefs.getString(Stage2PolishBackend.PREFS_KEY, null))
    }

    fun write(prefs: SharedPreferences, backend: Stage2PolishBackend) {
        prefs.edit().putString(Stage2PolishBackend.PREFS_KEY, backend.storedValue()).apply()
    }

    fun isLive(prefs: SharedPreferences): Boolean = read(prefs) == Stage2PolishBackend.LIVE
}

/**
 * Live two-step needs a raw Stage-1 transcript. Smart stays available for the
 * one-step path and is only forced off while this backend is selected.
 * The saved Smart preference is not overwritten.
 */
object Stage2SessionPolicy {
    fun effectiveSmartMode(savedSmartMode: Boolean, backend: Stage2PolishBackend): Boolean {
        return if (backend == Stage2PolishBackend.LIVE) false else savedSmartMode
    }
}

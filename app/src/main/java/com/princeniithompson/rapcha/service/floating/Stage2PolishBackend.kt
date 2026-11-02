package com.princeniithompson.rapcha.service.floating

import android.content.SharedPreferences
import com.princeniithompson.rapcha.service.AppCategory

/**
 * Power-user override. [AUTO] is the hybrid default: AI apps use two-step Live,
 * everything else stays Smart. A change applies on the next section, not mid-speech.
 */
enum class Stage2PolishBackend {
    AUTO,
    NON_LIVE,
    LIVE;

    companion object {
        const val PREFS_KEY = "stage2_polish_backend"

        fun fromStored(value: String?): Stage2PolishBackend {
            return when (value) {
                "live" -> LIVE
                "non_live", "smart" -> NON_LIVE
                else -> AUTO
            }
        }
    }

    fun storedValue(): String = when (this) {
        LIVE -> "live"
        NON_LIVE -> "non_live"
        AUTO -> "auto"
    }
}

enum class SectionPolishMode {
    TWO_STEP_LIVE,
    SMART
}

/**
 * Category decides the section. Unknown and non-AI stay Smart.
 * The override wins over the category, and the result is locked for the section.
 */
object HybridPolishPolicy {
    fun lock(override: Stage2PolishBackend, aiApp: Boolean): SectionPolishMode {
        return when (override) {
            Stage2PolishBackend.LIVE -> SectionPolishMode.TWO_STEP_LIVE
            Stage2PolishBackend.NON_LIVE -> SectionPolishMode.SMART
            Stage2PolishBackend.AUTO -> if (aiApp) SectionPolishMode.TWO_STEP_LIVE else SectionPolishMode.SMART
        }
    }

    /**
     * AI comes from the Stage-2 table or from AppClassifier / AppRegistry
     * (ChatGPT, Gemini, Claude, Grok, DeepSeek, Qwen, Kimi, and the rest).
     * Message, work, social, and unknown stay non-AI.
     */
    fun isAiTarget(stage2Category: Stage2Category, polishCategory: AppCategory): Boolean {
        return stage2Category == Stage2Category.AI || polishCategory == AppCategory.AI_CHAT
    }

    /** Lyra applies only when the locked target is an AI app. Force Live on WhatsApp stays MESSAGE. */
    fun liveCategory(stage2Category: Stage2Category, aiApp: Boolean): Stage2Category {
        return if (aiApp) Stage2Category.AI else stage2Category
    }
}

/**
 * Snapshot taken when the bubble section starts. Later package changes,
 * detection labels, and hamburger edits do not replace it.
 */
data class LockedPolishSection(
    val mode: SectionPolishMode,
    val stage2Category: Stage2Category,
    val polishCategory: AppCategory,
    val appName: String,
    val systemInstruction: String
)

/**
 * Toggle ON uses only models/gemini-3.8-live. Toggle OFF stays on the unary flash chain.
 */
object Stage2PolishRoute {
    fun usesLiveModel(liveTwoStep: Boolean): Boolean = liveTwoStep

    fun usesUnaryFallback(liveTwoStep: Boolean): Boolean = !liveTwoStep

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

package com.princeniithompson.rapcha.service.floating

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Monitors client-side usage against Google AI Studio limits for gemini-3.5-flash-lite:
 * - 15 RPM (rolling 60-second window; enters fallback cooldown at 14/15 used)
 * - 250,000 TPM (rolling 60-second window; enters fallback cooldown at 235,000 used or projection > 250K)
 * - 500 RPD (daily Pacific midnight reset; enters fallback cooldown at 495/500 used or server daily 429)
 *
 * When a limit is approached or HTTP 429 is observed, Three-Step Stage-2 polish is automatically
 * paused during the cooldown period and falls back to standard Gemini 3.5 Smart without modifying
 * the user's saved toggle. Once the rolling 60s window or Pacific day resets, the higher tier
 * automatically reactivates.
 */
object PolishQuotaMonitor {

    const val MODEL_FLASH_LITE = "gemini-3.5-flash-lite"

    const val MAX_RPM = 15
    const val SAFE_RPM_THRESHOLD = 14

    const val MAX_TPM = 250_000
    const val SAFE_TPM_THRESHOLD = 235_000

    const val MAX_RPD = 500
    const val SAFE_RPD_THRESHOLD = 495

    const val WINDOW_MS = 60_000L
    private const val COOLDOWN_BUFFER_MS = 1_000L
    private const val DEFAULT_429_COOLDOWN_MS = 61_000L

    private const val PREFS_NAME = "rapcha_polish_quota_monitor"
    private const val KEY_PACIFIC_DAY = "pacific_day"
    private const val KEY_RPD_USED = "rpd_used"
    private const val KEY_COOLDOWN_UNTIL = "cooldown_until_ms"
    private const val KEY_COOLDOWN_REASON = "cooldown_reason"

    private val PACIFIC_TZ: TimeZone = TimeZone.getTimeZone("America/Los_Angeles")

    enum class LimitReason(val label: String) {
        NONE("Active"),
        RPM_LIMIT("RPM limit (14/15)"),
        TPM_LIMIT("TPM limit (250K)"),
        RPD_LIMIT("Daily limit (500)"),
        SERVER_429("Rate limit (429)")
    }

    data class QuotaStatus(
        val available: Boolean,
        val reason: LimitReason,
        val rpmUsed: Int,
        val rpmLimit: Int,
        val tpmUsed: Int,
        val tpmLimit: Int,
        val rpdUsed: Int,
        val rpdLimit: Int,
        val cooldownRemainingMs: Long
    )

    private data class TokenEntry(
        val timestampMs: Long,
        var tokens: Int
    )

    private val lock = Any()
    private val requestTimestamps = ArrayDeque<Long>()
    private val tokenWindow = ArrayDeque<TokenEntry>()

    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        // Conservative ~3.5 chars/token estimate for mixed English + symbols
        return ((text.length + 2) / 3).coerceAtLeast(1)
    }

    fun pacificDayKey(nowMs: Long): String {
        val cal = Calendar.getInstance(PACIFIC_TZ, Locale.US)
        cal.timeInMillis = nowMs
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun nextPacificMidnightMs(nowMs: Long): Long {
        val cal = Calendar.getInstance(PACIFIC_TZ, Locale.US)
        cal.timeInMillis = nowMs
        cal.add(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 5)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun status(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        projectedTokens: Int = 0
    ): QuotaStatus = synchronized(lock) {
        pruneWindowsLocked(nowMs)
        val prefs = prefs(context)
        val today = pacificDayKey(nowMs)
        val storedDay = prefs.getString(KEY_PACIFIC_DAY, null)
        var rpdUsed = if (storedDay == today) prefs.getInt(KEY_RPD_USED, 0).coerceAtLeast(0) else 0
        var cooldownUntil = prefs.getLong(KEY_COOLDOWN_UNTIL, 0L)
        var cooldownReason = parseReason(prefs.getString(KEY_COOLDOWN_REASON, null))

        // Reset RPD & daily cooldown cleanly when Pacific midnight rolls over
        if (storedDay != null && storedDay != today) {
            rpdUsed = 0
            if (cooldownReason == LimitReason.RPD_LIMIT) {
                cooldownUntil = 0L
                cooldownReason = LimitReason.NONE
            }
            prefs.edit()
                .putString(KEY_PACIFIC_DAY, today)
                .putInt(KEY_RPD_USED, 0)
                .putLong(KEY_COOLDOWN_UNTIL, cooldownUntil)
                .putString(KEY_COOLDOWN_REASON, cooldownReason.name)
                .apply()
        }

        val rpmUsed = requestTimestamps.size
        val tpmUsed = tokenWindow.sumOf { it.tokens }

        // Auto-reactivate once explicit cooldown has elapsed AND rolling usage has cleared
        if (cooldownUntil in 1..nowMs) {
            val rollingHealthy = rpmUsed < SAFE_RPM_THRESHOLD &&
                tpmUsed < SAFE_TPM_THRESHOLD &&
                rpdUsed < SAFE_RPD_THRESHOLD
            if (rollingHealthy) {
                cooldownUntil = 0L
                cooldownReason = LimitReason.NONE
                prefs.edit()
                    .putLong(KEY_COOLDOWN_UNTIL, 0L)
                    .putString(KEY_COOLDOWN_REASON, LimitReason.NONE.name)
                    .apply()
            }
        }

        val activeCooldown = cooldownUntil > nowMs
        if (activeCooldown) {
            return@synchronized QuotaStatus(
                available = false,
                reason = if (cooldownReason == LimitReason.NONE) LimitReason.SERVER_429 else cooldownReason,
                rpmUsed = rpmUsed,
                rpmLimit = MAX_RPM,
                tpmUsed = tpmUsed,
                tpmLimit = MAX_TPM,
                rpdUsed = rpdUsed,
                rpdLimit = MAX_RPD,
                cooldownRemainingMs = (cooldownUntil - nowMs).coerceAtLeast(0L)
            )
        }

        val triggeredReason = when {
            rpdUsed >= SAFE_RPD_THRESHOLD -> LimitReason.RPD_LIMIT
            rpmUsed >= SAFE_RPM_THRESHOLD -> LimitReason.RPM_LIMIT
            tpmUsed >= SAFE_TPM_THRESHOLD ||
                (projectedTokens > 0 && tpmUsed + projectedTokens > MAX_TPM) -> LimitReason.TPM_LIMIT
            else -> LimitReason.NONE
        }

        if (triggeredReason != LimitReason.NONE) {
            val newUntil = computeCooldownUntilLocked(triggeredReason, nowMs)
            prefs.edit()
                .putString(KEY_PACIFIC_DAY, today)
                .putLong(KEY_COOLDOWN_UNTIL, newUntil)
                .putString(KEY_COOLDOWN_REASON, triggeredReason.name)
                .apply()
            return@synchronized QuotaStatus(
                available = false,
                reason = triggeredReason,
                rpmUsed = rpmUsed,
                rpmLimit = MAX_RPM,
                tpmUsed = tpmUsed,
                tpmLimit = MAX_TPM,
                rpdUsed = rpdUsed,
                rpdLimit = MAX_RPD,
                cooldownRemainingMs = (newUntil - nowMs).coerceAtLeast(0L)
            )
        }

        return@synchronized QuotaStatus(
            available = true,
            reason = LimitReason.NONE,
            rpmUsed = rpmUsed,
            rpmLimit = MAX_RPM,
            tpmUsed = tpmUsed,
            tpmLimit = MAX_TPM,
            rpdUsed = rpdUsed,
            rpdLimit = MAX_RPD,
            cooldownRemainingMs = 0L
        )
    }

    fun isPrimaryAvailable(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        projectedTokens: Int = 0
    ): Boolean = status(context, nowMs, projectedTokens).available

    /**
     * Checks availability and reserves one RPM slot + estimated input/output TPM + 1 RPD slot.
     * Returns false (and arms cooldown) when 14/15 RPM, ~250K TPM, or 495/500 RPD is reached.
     */
    fun tryAcquire(
        context: Context,
        estimatedTokens: Int,
        nowMs: Long = System.currentTimeMillis()
    ): QuotaStatus = synchronized(lock) {
        val current = status(context, nowMs, estimatedTokens)
        if (!current.available) {
            return@synchronized current
        }

        val prefs = prefs(context)
        val today = pacificDayKey(nowMs)
        val prevRpd = if (prefs.getString(KEY_PACIFIC_DAY, null) == today) {
            prefs.getInt(KEY_RPD_USED, 0).coerceAtLeast(0)
        } else {
            0
        }
        val nextRpd = prevRpd + 1
        val safeEstimate = estimatedTokens.coerceAtLeast(1)

        requestTimestamps.addLast(nowMs)
        tokenWindow.addLast(TokenEntry(nowMs, safeEstimate))

        val edit = prefs.edit()
            .putString(KEY_PACIFIC_DAY, today)
            .putInt(KEY_RPD_USED, nextRpd)

        // Note: We allow THIS call (since before acquiring we were < 14), and if this call
        // pushed us onto 14/15 or 495/500 or 235K TPM, the VERY NEXT check enters cooldown automatically.
        edit.apply()

        val rpmNow = requestTimestamps.size
        val tpmNow = tokenWindow.sumOf { it.tokens }
        return@synchronized QuotaStatus(
            available = true,
            reason = LimitReason.NONE,
            rpmUsed = rpmNow,
            rpmLimit = MAX_RPM,
            tpmUsed = tpmNow,
            tpmLimit = MAX_TPM,
            rpdUsed = nextRpd,
            rpdLimit = MAX_RPD,
            cooldownRemainingMs = 0L
        )
    }

    /**
     * Reconciles estimated tokens with actual usageMetadata.totalTokenCount returned by Gemini.
     */
    fun recordActualTokens(
        context: Context,
        actualTotalTokens: Int,
        estimatedTokens: Int = 0,
        nowMs: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        if (actualTotalTokens <= 0) return@synchronized
        pruneWindowsLocked(nowMs)
        val last = tokenWindow.lastOrNull()
        if (last != null && nowMs - last.timestampMs < WINDOW_MS) {
            last.tokens = actualTotalTokens
        } else {
            tokenWindow.addLast(TokenEntry(nowMs, actualTotalTokens))
        }
        // If actual usage pushed TPM over threshold, arm cooldown for upcoming calls
        val tpmNow = tokenWindow.sumOf { it.tokens }
        if (tpmNow >= SAFE_TPM_THRESHOLD) {
            val until = computeCooldownUntilLocked(LimitReason.TPM_LIMIT, nowMs)
            prefs(context).edit()
                .putLong(KEY_COOLDOWN_UNTIL, until)
                .putString(KEY_COOLDOWN_REASON, LimitReason.TPM_LIMIT.name)
                .apply()
        }
    }

    /**
     * Records an HTTP 429 from Gemini API so Three-Step / 3.5 Flash Lite immediately enters
     * cooldown and falls back until the rate limit window or Pacific midnight resets.
     */
    fun recordRateLimitError(
        context: Context,
        isDailyQuota: Boolean = false,
        retryAfterSeconds: Long? = null,
        nowMs: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        val prefs = prefs(context)
        val today = pacificDayKey(nowMs)
        val reason = if (isDailyQuota) LimitReason.RPD_LIMIT else LimitReason.SERVER_429
        val until = if (isDailyQuota) {
            nextPacificMidnightMs(nowMs)
        } else {
            val waitMs = ((retryAfterSeconds ?: 60L).coerceIn(10L, 300L) * 1000L) + COOLDOWN_BUFFER_MS
            nowMs + waitMs
        }
        val edit = prefs.edit()
            .putString(KEY_PACIFIC_DAY, today)
            .putLong(KEY_COOLDOWN_UNTIL, until)
            .putString(KEY_COOLDOWN_REASON, reason.name)
        if (isDailyQuota) {
            edit.putInt(KEY_RPD_USED, MAX_RPD)
        }
        edit.apply()
    }

    fun resetForTest(context: Context) = synchronized(lock) {
        requestTimestamps.clear()
        tokenWindow.clear()
        prefs(context).edit().clear().commit()
    }

    private fun pruneWindowsLocked(nowMs: Long) {
        val cutoff = nowMs - WINDOW_MS
        while (requestTimestamps.isNotEmpty() && requestTimestamps.first() <= cutoff) {
            requestTimestamps.removeFirst()
        }
        while (tokenWindow.isNotEmpty() && tokenWindow.first().timestampMs <= cutoff) {
            tokenWindow.removeFirst()
        }
    }

    private fun computeCooldownUntilLocked(reason: LimitReason, nowMs: Long): Long {
        return when (reason) {
            LimitReason.RPD_LIMIT -> nextPacificMidnightMs(nowMs)
            LimitReason.RPM_LIMIT -> {
                val oldest = requestTimestamps.firstOrNull()
                if (oldest != null) oldest + WINDOW_MS + COOLDOWN_BUFFER_MS else nowMs + DEFAULT_429_COOLDOWN_MS
            }
            LimitReason.TPM_LIMIT -> {
                val oldest = tokenWindow.firstOrNull()?.timestampMs
                if (oldest != null) oldest + WINDOW_MS + COOLDOWN_BUFFER_MS else nowMs + DEFAULT_429_COOLDOWN_MS
            }
            LimitReason.SERVER_429 -> nowMs + DEFAULT_429_COOLDOWN_MS
            LimitReason.NONE -> 0L
        }
    }

    private fun parseReason(raw: String?): LimitReason {
        if (raw.isNullOrBlank()) return LimitReason.NONE
        return LimitReason.entries.firstOrNull { it.name == raw } ?: LimitReason.NONE
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

package com.princeniithompson.rapcha.websocket

/**
 * Decides when one manual-VAD piece has to close.
 *
 * Piece time counts every streamed audio byte, silence included. Connection
 * age is wall-clock time since this socket opened, which is the clock that
 * stopped interim text near 223 seconds.
 */
class DictationPiecePlanner(
    private val pauseAfterMs: Long = PAUSE_AFTER_MS,
    private val forceAfterMs: Long = FORCE_AFTER_MS,
    private val pauseMs: Long = PAUSE_MS,
    private val rescueGapMs: Long = RESCUE_GAP_MS,
    private val preconnectLeadMs: Long = PRECONNECT_LEAD_MS,
    private val deathAgeMs: Long = DEATH_AGE_MS,
    private val speechRmsThreshold: Double = InterimPreviewMonitor.SPEECH_RMS_THRESHOLD,
    private val speechHoldMs: Long = InterimPreviewMonitor.SPEECH_HOLD_MS
) {
    enum class Reason {
        PAUSE,
        FORCED,
        RESCUE,
        GO_AWAY
    }

    data class Split(
        val reason: Reason,
        val replay: Boolean,
        val freshConnection: Boolean,
        val pieceNumber: Int,
        val pieceAudioMs: Long,
        val connectionAgeMs: Long,
        val pauseLengthMs: Long,
        val rmsMin: Double,
        val rmsMax: Double,
        val rmsMean: Double
    )

    data class AgeMarker(val connectionAgeMs: Long, val interimPastLimit: Boolean)

    var freshConnectionMode: Boolean = false
        private set

    private var pieceNumber = 1
    private var pieceAudioMs = 0L
    private var quietRunMs = 0L
    private var lastSpeechAt = 0L
    private var speechWithoutInterimMs = 0L
    private var connectionOpenedAt = -1L
    private var interimAfterDeath = false
    private var deathMarkerValue: Boolean? = null
    private var pauseAnnounced = false
    private var pauseLatched = false
    private var preconnectArmed = false
    private var wantsPreconnect = false
    private var rmsMin = Double.POSITIVE_INFINITY
    private var rmsMax = 0.0
    private var rmsSum = 0.0
    private var rmsCount = 0

    val quietMs: Long
        get() = quietRunMs

    fun resetSession() {
        freshConnectionMode = false
        pieceNumber = 1
        pieceAudioMs = 0L
        quietRunMs = 0L
        lastSpeechAt = 0L
        speechWithoutInterimMs = 0L
        connectionOpenedAt = -1L
        interimAfterDeath = false
        deathMarkerValue = null
        pauseAnnounced = false
        pauseLatched = false
        preconnectArmed = false
        wantsPreconnect = false
        resetRms()
    }

    fun onConnectionOpened(nowMs: Long) {
        connectionOpenedAt = nowMs
        interimAfterDeath = false
        deathMarkerValue = null
        preconnectArmed = false
        wantsPreconnect = false
    }

    fun onPieceStarted(@Suppress("UNUSED_PARAMETER") nowMs: Long) {
        pieceAudioMs = 0L
        quietRunMs = 0L
        lastSpeechAt = 0L
        speechWithoutInterimMs = 0L
        pauseAnnounced = false
        pauseLatched = false
        preconnectArmed = false
        wantsPreconnect = false
        resetRms()
    }

    fun onInterim(nowMs: Long) {
        speechWithoutInterimMs = 0L
        if (connectionAge(nowMs) >= deathAgeMs) {
            interimAfterDeath = true
        }
    }

    fun armFreshMode() {
        freshConnectionMode = true
    }

    fun connectionAge(nowMs: Long): Long {
        if (connectionOpenedAt < 0L) return 0L
        return (nowMs - connectionOpenedAt).coerceAtLeast(0L)
    }

    fun interimPastDeathAge(): Boolean = interimAfterDeath

    fun consumePreconnect(): Boolean {
        if (!wantsPreconnect) return false
        wantsPreconnect = false
        return true
    }

    fun consumeAudiblePause(): Boolean {
        if (!pauseAnnounced) return false
        pauseAnnounced = false
        return true
    }

    /**
     * @return a split, or null while this piece should keep streaming.
     * [consumePreconnect] and [consumeAudiblePause] report the side effects.
     */
    fun onAudio(pcm: ByteArray, nowMs: Long): Split? {
        val chunkMs = ((pcm.size / BYTES_PER_MS).coerceAtLeast(1)).toLong()
        val rms = InterimPreviewMonitor.pcm16leRms(pcm)
        recordRms(rms)
        pieceAudioMs += chunkMs
        if (rms >= speechRmsThreshold) {
            quietRunMs = 0L
            lastSpeechAt = nowMs
            speechWithoutInterimMs += chunkMs
            pauseAnnounced = false
            pauseLatched = false
        } else {
            quietRunMs += chunkMs
            if (quietRunMs >= pauseMs && !pauseLatched) {
                pauseLatched = true
                pauseAnnounced = true
            }
        }
        if (freshConnectionMode && !preconnectArmed && pieceAudioMs >= pauseAfterMs - preconnectLeadMs) {
            preconnectArmed = true
            wantsPreconnect = true
        }
        val speechHeld = lastSpeechAt > 0L && nowMs - lastSpeechAt <= speechHoldMs
        if (speechHeld && speechWithoutInterimMs >= rescueGapMs) {
            freshConnectionMode = true
            return split(Reason.RESCUE, replay = true, fresh = true, nowMs)
        }
        if (pieceAudioMs >= forceAfterMs) {
            return split(Reason.FORCED, replay = true, fresh = freshConnectionMode, nowMs)
        }
        if (pieceAudioMs >= pauseAfterMs && quietRunMs >= pauseMs) {
            return split(Reason.PAUSE, replay = false, fresh = freshConnectionMode, nowMs)
        }
        return null
    }

    fun forceFreshSplit(nowMs: Long): Split {
        freshConnectionMode = true
        return split(Reason.GO_AWAY, replay = true, fresh = true, nowMs)
    }

    fun ageMarker(nowMs: Long): AgeMarker? {
        val age = connectionAge(nowMs)
        if (age < deathAgeMs) return null
        val past = interimAfterDeath
        if (deathMarkerValue == null) {
            deathMarkerValue = past
            return AgeMarker(age, past)
        }
        if (past && deathMarkerValue == false) {
            deathMarkerValue = true
            return AgeMarker(age, true)
        }
        return null
    }

    fun advancePieceNumber() {
        pieceNumber += 1
    }

    private fun split(reason: Reason, replay: Boolean, fresh: Boolean, nowMs: Long): Split {
        val mean = if (rmsCount == 0) 0.0 else rmsSum / rmsCount
        return Split(
            reason = reason,
            replay = replay,
            freshConnection = fresh,
            pieceNumber = pieceNumber,
            pieceAudioMs = pieceAudioMs,
            connectionAgeMs = connectionAge(nowMs),
            pauseLengthMs = quietRunMs,
            rmsMin = if (rmsMin == Double.POSITIVE_INFINITY) 0.0 else rmsMin,
            rmsMax = rmsMax,
            rmsMean = mean
        )
    }

    private fun recordRms(rms: Double) {
        rmsCount += 1
        rmsSum += rms
        if (rms < rmsMin) rmsMin = rms
        if (rms > rmsMax) rmsMax = rms
    }

    private fun resetRms() {
        rmsMin = Double.POSITIVE_INFINITY
        rmsMax = 0.0
        rmsSum = 0.0
        rmsCount = 0
    }

    companion object {
        const val PAUSE_AFTER_MS = 150_000L
        const val FORCE_AFTER_MS = 195_000L
        const val PAUSE_MS = 700L
        const val RESCUE_GAP_MS = 6_000L
        const val PRECONNECT_LEAD_MS = 10_000L
        const val DEATH_AGE_MS = 223_000L
        const val REPLAY_MS = 2_000L
        const val PIECE_FINAL_TIMEOUT_MS = 5_000L
        const val BYTES_PER_MS = 32
        const val REPLAY_BYTES = 16_000 * 2 * 2
    }
}

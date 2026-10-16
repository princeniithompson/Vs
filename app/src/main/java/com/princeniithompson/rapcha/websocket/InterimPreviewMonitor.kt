package com.princeniithompson.rapcha.websocket

import kotlin.math.sqrt

/**
 * Watches a live dictation for a stalled transcription preview.
 * Silence does not count: a stall requires speech-level PCM while audio is
 * still being sent and no live transcription message has arrived.
 */
class InterimPreviewMonitor(
    private val stallAfterMs: Long = STALL_AFTER_MS,
    private val speechHoldMs: Long = SPEECH_HOLD_MS,
    private val heartbeatEveryMs: Long = HEARTBEAT_EVERY_MS,
    private val speechRmsThreshold: Double = SPEECH_RMS_THRESHOLD,
    private val sendFailureLimit: Int = SEND_FAILURE_LIMIT
) {
    enum class Phase {
        LISTENING,
        PREVIEW_DELAYED,
        CONNECTION_LOST
    }

    sealed class Event {
        data class Stall(val gapMs: Long) : Event()
        data class Resumed(val gapMs: Long) : Event()
        data class Heartbeat(
            val interimCount: Int,
            val lastInterimAgeMs: Long,
            val bytesSent: Long,
            val socketState: String
        ) : Event()
    }

    data class Tick(
        val events: List<Event>,
        val phase: Phase,
        val phaseChanged: Boolean
    )

    private val lock = Any()
    private var running = false
    private var startedAt = 0L
    private var lastInterimAt: Long? = null
    private var interimCount = 0
    private var lastSpeechAt = 0L
    private var bytesSent = 0L
    private var consecutiveSendFailures = 0
    private var socketState = "connecting"
    private var stalled = false
    private var maxGapMs = 0L
    private var lastHeartbeatAt = 0L
    private var activityEndAt: Long? = null
    private var finalizeLatencyMs: Long? = null
    private var phase = Phase.LISTENING

    fun start(nowMs: Long) {
        synchronized(lock) {
            running = true
            startedAt = nowMs
            lastInterimAt = null
            interimCount = 0
            lastSpeechAt = 0L
            bytesSent = 0L
            consecutiveSendFailures = 0
            socketState = "connecting"
            stalled = false
            maxGapMs = 0L
            lastHeartbeatAt = nowMs
            activityEndAt = null
            finalizeLatencyMs = null
            phase = Phase.LISTENING
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
        }
    }

    fun onSocketState(state: String) {
        synchronized(lock) {
            socketState = state
        }
    }

    fun onPcm(pcm: ByteArray, accepted: Boolean, nowMs: Long) {
        synchronized(lock) {
            if (!running) return
            bytesSent += pcm.size.toLong()
            if (pcm16leRms(pcm) >= speechRmsThreshold) {
                lastSpeechAt = nowMs
            }
            if (accepted) {
                consecutiveSendFailures = 0
            } else {
                consecutiveSendFailures += 1
            }
        }
    }

    /**
     * A live transcription message arrived (interimInputTranscription, or
     * inputTranscription while the manual activity is still open).
     */
    fun onInterim(nowMs: Long): Event.Resumed? {
        synchronized(lock) {
            if (!running) return null
            val previous = lastInterimAt ?: startedAt
            val gap = (nowMs - previous).coerceAtLeast(0L)
            if (gap > maxGapMs) maxGapMs = gap
            lastInterimAt = nowMs
            interimCount += 1
            if (!stalled) return null
            stalled = false
            return Event.Resumed(gap)
        }
    }

    fun onActivityEnd(nowMs: Long) {
        synchronized(lock) {
            if (activityEndAt == null) activityEndAt = nowMs
        }
    }

    fun onFinalTranscript(nowMs: Long) {
        synchronized(lock) {
            val started = activityEndAt ?: return
            if (finalizeLatencyMs == null) {
                finalizeLatencyMs = (nowMs - started).coerceAtLeast(0L)
            }
        }
    }

    fun poll(nowMs: Long, socketState: String): Tick {
        synchronized(lock) {
            this.socketState = socketState
            if (!running) {
                return Tick(emptyList(), phase, false)
            }
            val events = mutableListOf<Event>()
            val lost = isConnectionLost()
            val speechNow = lastSpeechAt > 0L && nowMs - lastSpeechAt <= speechHoldMs
            val anchor = lastInterimAt ?: startedAt
            val interimAge = (nowMs - anchor).coerceAtLeast(0L)
            if (!lost && speechNow && bytesSent > 0L && interimAge > stallAfterMs && !stalled) {
                stalled = true
                if (interimAge > maxGapMs) maxGapMs = interimAge
                events += Event.Stall(interimAge)
            }
            if (nowMs - lastHeartbeatAt >= heartbeatEveryMs) {
                lastHeartbeatAt = nowMs
                events += Event.Heartbeat(
                    interimCount = interimCount,
                    lastInterimAgeMs = if (lastInterimAt == null) -1L else interimAge,
                    bytesSent = bytesSent,
                    socketState = socketState
                )
            }
            val next = when {
                lost -> Phase.CONNECTION_LOST
                stalled -> Phase.PREVIEW_DELAYED
                else -> Phase.LISTENING
            }
            val changed = next != phase
            phase = next
            return Tick(events, next, changed)
        }
    }

    fun summary(): String {
        synchronized(lock) {
            val latency = finalizeLatencyMs?.toString() ?: "none"
            return "interim_count=$interimCount max_interim_gap_ms=$maxGapMs finalize_latency_ms=$latency"
        }
    }

    private fun isConnectionLost(): Boolean {
        if (consecutiveSendFailures >= sendFailureLimit) return true
        return socketState == "closed" || socketState == "failed"
    }

    companion object {
        const val STALL_AFTER_MS = 8_000L
        const val SPEECH_HOLD_MS = 1_500L
        const val HEARTBEAT_EVERY_MS = 30_000L

        /**
         * Raw RMS of signed 16-bit PCM. Speech is well above this; a quiet
         * pause is not, so silence does not look like a stalled preview.
         */
        const val SPEECH_RMS_THRESHOLD = 200.0
        const val SEND_FAILURE_LIMIT = 8

        fun pcm16leRms(pcm: ByteArray): Double {
            val samples = pcm.size / 2
            if (samples <= 0) return 0.0
            var sumSquares = 0.0
            for (i in 0 until samples) {
                val low = pcm[i * 2].toInt() and 0xFF
                val high = pcm[i * 2 + 1].toInt()
                val unsigned = (high shl 8) or low
                val sample = if (unsigned > 32767) unsigned - 65536 else unsigned
                sumSquares += sample.toDouble() * sample.toDouble()
            }
            return sqrt(sumSquares / samples)
        }

        fun speechPcm(sample: Int = 4000, sampleCount: Int = 160): ByteArray {
            val bytes = ByteArray(sampleCount * 2)
            for (i in 0 until sampleCount) {
                val value = if (i % 2 == 0) sample else -sample
                bytes[i * 2] = (value and 0xFF).toByte()
                bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            return bytes
        }

        fun silencePcm(sampleCount: Int = 160): ByteArray = ByteArray(sampleCount * 2)

        fun polishingMessage(durationSeconds: Int): String {
            val minutes = ((durationSeconds.coerceAtLeast(0) + 59) / 60).coerceAtLeast(1)
            val unit = if (minutes == 1) "minute" else "minutes"
            return "Polishing $minutes $unit of speech..."
        }
    }
}

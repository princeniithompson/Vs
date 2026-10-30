package com.princeniithompson.rapcha.service.floating

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.princeniithompson.rapcha.config.RapchaConfig
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.data.RetryClipStore
import com.princeniithompson.rapcha.data.RetryQuota
import com.princeniithompson.rapcha.data.TranscriptPrivacy
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class RetryClipLength {
    OK,
    WARN,
    BLOCKED
}

object OfflineRetryRules {
    const val WARN_AFTER_MS = 4 * 60 * 1000L
    const val BLOCK_AT_MS = 5 * 60 * 1000L

    fun lengthFor(durationMs: Long): RetryClipLength {
        return when {
            durationMs >= BLOCK_AT_MS -> RetryClipLength.BLOCKED
            durationMs >= WARN_AFTER_MS -> RetryClipLength.WARN
            else -> RetryClipLength.OK
        }
    }

    fun shouldOffer(
        recording: Boolean,
        networkProblem: Boolean,
        holdingFailure: Boolean,
        busy: Boolean,
        dataBytes: Long
    ): Boolean {
        if (busy) return true
        if (dataBytes <= 0L) return false
        if (holdingFailure) return true
        return recording && networkProblem
    }

    fun retryEnabled(
        retriesLeft: Int,
        length: RetryClipLength,
        busy: Boolean,
        durationMs: Long
    ): Boolean {
        return !busy && retriesLeft > 0 && length != RetryClipLength.BLOCKED && durationMs > 0L
    }

    fun statusLine(
        retriesLeft: Int,
        length: RetryClipLength,
        busy: Boolean,
        message: String?
    ): String {
        if (busy) return "Uploading…"
        if (!message.isNullOrBlank()) return message
        if (length == RetryClipLength.BLOCKED) return "Too long to retry"
        if (retriesLeft <= 0) return "0 left today"
        if (length == RetryClipLength.WARN) return "Long clip · $retriesLeft left today"
        return "$retriesLeft left today"
    }

    fun messageFor(kind: TranscribeResult.Kind): String {
        return when (kind) {
            TranscribeResult.Kind.QUOTA_DAILY -> "Daily limit reached. Clip kept."
            TranscribeResult.Kind.RATE_LIMIT -> "Too many requests. Wait and retry."
            TranscribeResult.Kind.BAD_REQUEST -> "Request rejected. Clip kept."
            TranscribeResult.Kind.BAD_KEY -> "API key rejected. Clip kept."
            TranscribeResult.Kind.IO -> "Couldn't reach Gemini. Clip kept."
            TranscribeResult.Kind.SERVER -> "Gemini error. Clip kept."
            TranscribeResult.Kind.EMPTY -> "No speech recognized. Clip kept."
            TranscribeResult.Kind.EMPTY_AUDIO -> "No audio to retry"
        }
    }
}

class RetryTapGate(
    private val debounceMs: Long = RapchaConfig.RETRY_DEBOUNCE_MS,
    private val now: () -> Long = { SystemClock.elapsedRealtime() }
) {
    enum class Decision { START, DEBOUNCED, BUSY }

    private var lastAccepted = Long.MIN_VALUE
    private val inFlight = AtomicBoolean(false)

    fun tryStart(): Decision {
        synchronized(this) {
            if (inFlight.get()) return Decision.BUSY
            val time = now()
            if (lastAccepted != Long.MIN_VALUE && time - lastAccepted < debounceMs) {
                return Decision.DEBOUNCED
            }
            lastAccepted = time
            inFlight.set(true)
            return Decision.START
        }
    }

    fun finish() {
        inFlight.set(false)
    }
}

/**
 * Manual offline Retry. Live stays in charge until this is tapped.
 * A failed attempt keeps the clip; success hands text to the Complete inject path.
 */
class OfflineRetryCoordinator(
    private val client: TranscribeFallbackClient = TranscribeFallbackClient(),
    private val nowElapsed: () -> Long = { SystemClock.elapsedRealtime() },
    private val nowWall: () -> Long = { System.currentTimeMillis() }
) {
    private val gate = RetryTapGate(now = nowElapsed)
    private val holding = AtomicBoolean(false)

    @Volatile
    private var busy = false

    @Volatile
    private var statusMessage: String? = null

    @Volatile
    private var loggedOffer = false

    fun isHolding(): Boolean = holding.get()

    fun onNewSession() {
        holding.set(false)
        statusMessage = null
        loggedOffer = false
        FloatingBubbleManager.setOfflineRetry(null)
    }

    fun holdAfterFailure() {
        holding.set(true)
    }

    fun clearHold() {
        holding.set(false)
        statusMessage = null
        loggedOffer = false
        FloatingBubbleManager.setOfflineRetry(null)
    }

    fun restorePending(context: Context) {
        val pending = RetryClipStore.latestPending(nowWall()) ?: return
        if (pending.dataBytes <= 0L) return
        holding.set(true)
        publish(context, recording = false, networkProblem = true)
    }

    fun publish(context: Context, recording: Boolean, networkProblem: Boolean): Boolean {
        val bytes = RetryClipStore.peekDataBytes()
        val show = OfflineRetryRules.shouldOffer(
            recording = recording,
            networkProblem = networkProblem,
            holdingFailure = holding.get(),
            busy = busy,
            dataBytes = bytes
        )
        if (!show) {
            if (!busy) FloatingBubbleManager.setOfflineRetry(null)
            return false
        }
        val duration = RetryClipStore.durationMsForBytes(bytes)
        val length = OfflineRetryRules.lengthFor(duration)
        val left = RetryQuota.remaining(context, nowWall())
        if (!loggedOffer && !busy) {
            loggedOffer = true
            AppLogRepository.logEvent(
                DiagnosticSource.BUBBLE,
                DiagnosticType.RETRY_OFFERED,
                "duration_ms=$duration bytes=$bytes left=$left"
            )
        }
        FloatingBubbleManager.setOfflineRetry(
            FloatingBubbleManager.OfflineRetryOffer(
                retriesLeft = left,
                durationMs = duration,
                length = length,
                busy = busy,
                message = if (busy) null else statusMessage
            )
        )
        return true
    }

    suspend fun runRetry(
        context: Context,
        apiKey: String,
        vocabulary: List<String>,
        prepareClip: suspend () -> File?,
        onAcceptedText: suspend (String) -> Unit
    ) {
        when (gate.tryStart()) {
            RetryTapGate.Decision.DEBOUNCED -> {
                Log.d(TAG, "Retry tap debounced")
                return
            }
            RetryTapGate.Decision.BUSY -> return
            RetryTapGate.Decision.START -> Unit
        }
        busy = true
        holding.set(true)
        statusMessage = null
        publish(context, recording = false, networkProblem = true)
        var accepted = false
        try {
            val file = prepareClip()
            if (file == null || !file.exists() || file.length() <= WAV_HEADER_BYTES) {
                statusMessage = "No audio to retry"
                logFailure("kind=empty_audio")
                return
            }
            val dataBytes = (file.length() - WAV_HEADER_BYTES).coerceAtLeast(0L)
            val duration = RetryClipStore.durationMsForBytes(dataBytes)
            val length = OfflineRetryRules.lengthFor(duration)
            if (length == RetryClipLength.BLOCKED) {
                statusMessage = "Too long to retry"
                logFailure("kind=too_long duration_ms=$duration")
                return
            }
            val left = RetryQuota.remaining(context, nowWall())
            if (left <= 0) {
                statusMessage = "0 left today"
                logFailure("kind=quota_daily")
                return
            }
            val vocabCount = TranscribeFallbackClient.prepareCustomVocabulary(vocabulary).size
            AppLogRepository.logEvent(
                DiagnosticSource.BUBBLE,
                DiagnosticType.RETRY_STARTED,
                "model=${RapchaConfig.TRANSCRIBE_MODEL} mode=SMART vocab=$vocabCount duration_ms=$duration bytes=${file.length()} left=$left"
            )
            val result = withContext(Dispatchers.IO) {
                client.transcribe(apiKey, file, vocabulary)
            }
            when (result) {
                is TranscribeResult.Success -> {
                    if (result.quotaCharge) RetryQuota.recordAttempt(context, nowWall())
                    val text = result.text.trim()
                    if (text.isEmpty()) {
                        statusMessage = "No speech recognized. Clip kept."
                        logFailure("kind=empty")
                    } else {
                        AppLogRepository.logEvent(
                            DiagnosticSource.BUBBLE,
                            DiagnosticType.RETRY_SUCCESS,
                            "words=${TranscriptPrivacy.wordCount(text)} chars=${text.length}"
                        )
                        accepted = true
                        holding.set(false)
                        statusMessage = null
                        FloatingBubbleManager.setOfflineRetry(null)
                        onAcceptedText(text)
                    }
                }
                is TranscribeResult.Failure -> {
                    if (result.kind == TranscribeResult.Kind.QUOTA_DAILY) {
                        RetryQuota.markDailyExhausted(context, nowWall())
                    } else if (result.quotaCharge) {
                        RetryQuota.recordAttempt(context, nowWall())
                    }
                    statusMessage = OfflineRetryRules.messageFor(result.kind)
                    val status = result.status?.let { " status=$it" }.orEmpty()
                    logFailure("kind=${result.kind.name.lowercase()} http=${result.httpCode ?: 0}$status")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Retry failed: ${t.javaClass.simpleName}")
            statusMessage = "Couldn't reach Gemini. Clip kept."
            logFailure("kind=io")
        } finally {
            busy = false
            gate.finish()
            if (!accepted) {
                holding.set(true)
                publish(context, recording = false, networkProblem = true)
            }
        }
    }

    private fun logFailure(message: String) {
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.RETRY_FAILED,
            message
        )
    }

    companion object {
        private const val TAG = "OfflineRetry"
        private const val WAV_HEADER_BYTES = 44L
    }
}

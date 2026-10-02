package com.example.ui.components.diagnostics

import com.example.data.AppLogRepository
import com.example.data.DiagnosticLogEntry
import com.example.data.DiagnosticSource
import com.example.data.DiagnosticType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles recording diagnostic events across the app and floating overlay,
 * and organizes event history into chronological day-grouped hierarchies for presentation.
 */
object DiagnosticsEventTracker {

    fun trackSessionStart(source: DiagnosticSource, details: String = "Session initiated") {
        AppLogRepository.logEvent(source, DiagnosticType.SESSION_START, details)
    }

    fun trackSessionEnd(
        source: DiagnosticSource,
        durationSeconds: Int,
        chunksSent: Int,
        endedReason: String = "normal"
    ) {
        val message = "ended_reason: $endedReason | Duration: ${durationSeconds}s, Chunks: $chunksSent"
        AppLogRepository.logEvent(source, DiagnosticType.SESSION_END, message)
    }

    fun trackTranscript(source: DiagnosticSource, text: String) {
        val sample = if (text.length > 80) text.take(80) + "..." else text
        AppLogRepository.logEvent(source, DiagnosticType.TRANSCRIPT_FINAL, sample)
    }

    fun trackPolishCalled(source: DiagnosticSource, charCount: Int) {
        AppLogRepository.logEvent(source, DiagnosticType.POLISH_CALLED, "Transcript length: $charCount chars")
    }

    fun trackPolishSuccess(source: DiagnosticSource, resultSample: String) {
        val sample = if (resultSample.length > 60) resultSample.take(60) + "..." else resultSample
        AppLogRepository.logEvent(source, DiagnosticType.POLISH_SUCCESS, "Result: $sample")
    }

    fun trackPolishFailed(source: DiagnosticSource, error: String) {
        AppLogRepository.logEvent(source, DiagnosticType.POLISH_FAILED, error)
    }

    fun trackNoisyEnvironment(source: DiagnosticSource, details: String) {
        AppLogRepository.logEvent(source, DiagnosticType.NOISY_ENVIRONMENT, details)
    }

    fun trackSafeModeTriggered(source: DiagnosticSource, packageName: String?) {
        AppLogRepository.logEvent(source, DiagnosticType.SAFE_MODE_TRIGGERED, "Smart Safe Mode engaged for package: $packageName")
    }

    fun trackError(source: DiagnosticSource, error: String) {
        AppLogRepository.logEvent(source, DiagnosticType.ERROR, error)
    }

    fun trackWarning(source: DiagnosticSource, warning: String) {
        AppLogRepository.logEvent(source, DiagnosticType.WARNING, warning)
    }

    fun trackReconnect(source: DiagnosticSource, message: String) {
        AppLogRepository.logEvent(source, DiagnosticType.SESSION_RECONNECT, message)
    }

    /**
     * Groups a flat list of diagnostic log entries by day (e.g. "2026-10-02"), newest day first.
     */
    fun buildLogHistory(
        entries: List<DiagnosticLogEntry>,
        dayFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    ): Map<String, List<DiagnosticLogEntry>> {
        return entries.groupBy { dayFormat.format(Date(it.timestamp)) }
    }
}

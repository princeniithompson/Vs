package com.example.data

sealed interface ConnectionState {
    object Idle : ConnectionState
    object Connecting : ConnectionState
    object ConnectedWaitingSetup : ConnectionState
    object Streaming : ConnectionState
    object Stopping : ConnectionState
    data class Error(val message: String) : ConnectionState
}

enum class LogLevel {
    INFO,
    SENT,
    RECEIVED,
    ERROR
}

/**
 * Source of the event: in-app recorder or the floating bubble service
 */
enum class DiagnosticSource {
    APP,
    BUBBLE
}

/**
 * Event-level diagnostic types specified for unified logging
 */
enum class DiagnosticType {
    SESSION_START,
    SESSION_END,
    TRANSCRIPT_FINAL,
    POLISH_CALLED,
    POLISH_SUCCESS,
    POLISH_FAILED,
    ERROR,
    WARNING,
    NOISY_ENVIRONMENT,
    SESSION_RECONNECT,
    SAFE_MODE_TRIGGERED
}

data class DiagnosticLogEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: DiagnosticSource,
    val type: DiagnosticType,
    val message: String
)

data class DiagnosticNote(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val text: String
)

// Legacy LogEntry kept for backward compatibility with live low-level socket stream viewing if needed
data class LogEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val payload: String? = null
)

data class LiveStats(
    val chunksBuffered: Int = 0,
    val chunksSent: Int = 0,
    val bytesSent: Long = 0L,
    val durationSeconds: Int = 0,
    val setupCompleted: Boolean = false,
    val interimCount: Int = 0,
    val finalizedCount: Int = 0,
    val lastError: String? = null
)

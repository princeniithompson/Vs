package com.example.service

/**
 * Result outcomes for Whisperflow's Accessibility write operations.
 */
enum class AxWriteOutcome {
    SUCCESS,
    FAILED,
    SKIPPED
}

/**
 * Encapsulates the verified outcome of an accessibility text commit operation.
 */
data class NodeWriteResult(
    val outcome: AxWriteOutcome,
    val details: String? = null,
    val writtenText: String? = null,
    val initialTextLength: Int = 0,
    val finalTextLength: Int = 0,
    val verificationAttemptsUsed: Int = 0
)

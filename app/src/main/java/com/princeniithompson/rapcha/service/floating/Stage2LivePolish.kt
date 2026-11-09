package com.princeniithompson.rapcha.service.floating

import com.princeniithompson.rapcha.data.DiagnosticType

data class Stage2Request(
    val apiKey: String,
    val systemInstruction: String,
    val userText: String
)

fun interface Stage2Model {
    fun polish(request: Stage2Request): LivePolishResponse
}

enum class Stage2Outcome {
    SUCCESS,
    FALLBACK_RAW
}

data class Stage2Paste(
    val text: String,
    val outcome: Stage2Outcome,
    val error: String?,
    val latencyMs: Long,
    val category: Stage2Category,
    val stage1Flushed: Boolean,
    val timedOut: Boolean
)

/**
 * Runs Stage-2 polish and always returns text when the raw buffer is non-blank.
 * A timeout, socket error, or empty model reply pastes the raw transcript.
 */
object Stage2LivePolish {

    fun execute(
        rawTranscript: String,
        category: Stage2Category,
        protectedTerms: List<String>,
        stage1Dropped: Boolean,
        apiKey: String,
        model: Stage2Model,
        appName: String = "App",
        systemInstruction: String? = null,
        partIndex: Int = 1,
        partCount: Int = 1,
        sourceChars: Int = -1,
        logHeader: Boolean = true,
        log: (DiagnosticType, String) -> Unit = { _, _ -> },
        nowMs: () -> Long = { System.currentTimeMillis() }
    ): Stage2Paste {
        val raw = rawTranscript.trim()
        val piece = if (partCount > 1) "chunk=$partIndex/$partCount" else ""
        val script = listOf(nonAsciiHint(raw), piece).filter { it.isNotBlank() }.joinToString(" ")
        if (logHeader) {
            log(
                DiagnosticType.STAGE2_LIVE_START,
                "category=$category model=${LivePolishProtocol.MODEL} stage1_flush=${yesNo(stage1Dropped)} chars=${raw.length} source_chars=${if (sourceChars >= 0) sourceChars else raw.length} pieces=$partCount $piece $script"
            )
        }
        val started = nowMs()
        val response = try {
            model.polish(
                Stage2Request(
                    apiKey = apiKey,
                    systemInstruction = systemInstruction ?: Stage2LivePrompt.systemInstruction(
                        category,
                        appName,
                        protectedTerms
                    ),
                    userText = Stage2LivePrompt.userTurn(
                        category,
                        raw,
                        protectedTerms,
                        partIndex = partIndex,
                        partCount = partCount
                    )
                )
            )
        } catch (t: Throwable) {
            LivePolishResponse(
                text = null,
                error = t.message ?: t.javaClass.simpleName,
                timedOut = false
            )
        }
        val latency = (nowMs() - started).coerceAtLeast(0L)
        val polished = response.text?.trim().orEmpty()
        val failed = response.error != null || response.timedOut || polished.isEmpty()
        val paste = if (failed) {
            Stage2Paste(
                text = raw,
                outcome = Stage2Outcome.FALLBACK_RAW,
                error = response.error ?: "empty Stage-2 text",
                latencyMs = latency,
                category = category,
                stage1Flushed = stage1Dropped,
                timedOut = response.timedOut
            )
        } else {
            Stage2Paste(
                text = polished,
                outcome = Stage2Outcome.SUCCESS,
                error = null,
                latencyMs = latency,
                category = category,
                stage1Flushed = stage1Dropped,
                timedOut = false
            )
        }
        logResult(paste, response, script, log)
        return paste
    }

    /** Two-step Live never calls the unary flash models. A failed turn inserts the raw transcript. */
    fun rawOnFailure(rawTranscript: String): String = rawTranscript

    fun nonAsciiHint(text: String): String {
        val nonAscii = text.any { it.isLetter() && it.code > 127 }
        return if (nonAscii) "non_ascii=yes" else "non_ascii=no"
    }

    private fun logResult(
        paste: Stage2Paste,
        response: LivePolishResponse,
        script: String,
        log: (DiagnosticType, String) -> Unit
    ) {
        val base = "category=${paste.category} latency_ms=${paste.latencyMs} stage1_flush=${yesNo(paste.stage1Flushed)} chars=${paste.text.length} $script"
        val detail = response.trace.ifBlank { "first_token_ms=${response.firstTokenMs}" }
        if (paste.outcome == Stage2Outcome.SUCCESS) {
            log(DiagnosticType.STAGE2_LIVE_SUCCESS, "$base model=${LivePolishProtocol.MODEL} $detail")
            return
        }
        val reason = paste.error?.take(240) ?: "unknown"
        log(
            DiagnosticType.STAGE2_LIVE_FAILED,
            "$base model=${LivePolishProtocol.MODEL} reason=$reason timed_out=${yesNo(paste.timedOut)} $detail"
        )
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}

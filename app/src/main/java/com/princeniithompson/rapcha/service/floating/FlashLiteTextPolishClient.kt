package com.princeniithompson.rapcha.service.floating

import android.content.Context
import com.princeniithompson.rapcha.config.RapchaConfig

/**
 * Stage-2 model adapter backed by gemini-3.5-flash-lite (monitored against 15 RPM / 250K TPM / 500 RPD).
 * When quota is exhausted or in cooldown, signals Stage-2 bypass so Rapcha seamlessly turns off
 * Three-Step Stage-2 polish and falls back to the cleaned Stage-1 Gemini 3.5 Smart text, then
 * auto-reactivates as soon as the cooldown window expires.
 */
class FlashLiteTextPolishClient(
    private val context: Context?,
    private val onQuotaFallback: ((String) -> Unit)? = null
) : Stage2Model {

    override fun polish(request: Stage2Request): LivePolishResponse {
        val started = System.currentTimeMillis()
        val result = FloatingPolishClient.polishStage2(
            context = context,
            apiKey = request.apiKey,
            systemInstruction = request.systemInstruction,
            userText = request.userText,
            allowSecondaryFlashFallback = false
        )
        val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(0L)
        val netTag = com.princeniithompson.rapcha.diagnostics.NetworkAndDeviceDiagnostics.currentTag(context)
        if (!result.text.isNullOrBlank()) {
            return LivePolishResponse(
                text = result.text,
                error = null,
                timedOut = false,
                firstTokenMs = elapsed,
                trace = "model=${result.modelUsed} http=${result.httpCode} tokens=${result.totalTokens} ms=$elapsed $netTag"
            )
        }
        if (result.quotaBypassed || result.rateLimited) {
            val detail = result.errorDetail ?: "3.5 Flash Lite quota limit reached"
            onQuotaFallback?.invoke(detail)
        }
        val excTag = result.exceptionClass?.let { " exc=$it" }.orEmpty()
        return LivePolishResponse(
            text = null,
            error = result.errorDetail ?: "Stage-2 3.5 Flash Lite returned empty text",
            timedOut = false,
            firstTokenMs = elapsed,
            trace = "model=${RapchaConfig.STAGE2_FLASH_LITE_MODEL} http=${result.httpCode}$excTag quota_bypass=${result.quotaBypassed} ms=$elapsed $netTag"
        )
    }
}

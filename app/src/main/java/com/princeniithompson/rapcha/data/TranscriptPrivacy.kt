package com.princeniithompson.rapcha.data

/**
 * Diagnostics-only transcript redaction. Injection, the live transcript UI,
 * history, and vocabulary matching keep the original text.
 */
object TranscriptPrivacy {
    private const val PREVIEW_CHARS = 60

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex(
        """(?<!\d)(?:\+?\d{1,3}[\s.-]?)?(?:\(\d{3}\)[\s.-]*|\d{3}[\s.-])\d{3}[\s.-]\d{4}(?!\d)|(?<!\d)\+\d{1,3}(?:[\s.-]*\d{2,4}){2,5}(?!\d)|(?<!\d)\d{10,15}(?!\d)"""
    )

    fun maskSensitive(text: String): String {
        return PHONE.replace(EMAIL.replace(text, "[email]"), "[phone]")
    }

    fun summary(text: String): String {
        val trimmed = text.trim()
        val words = trimmed.split(Regex("\\s+")).count { it.isNotBlank() }
        val masked = maskSensitive(trimmed).replace("\"", "'")
        val preview = if (masked.length > PREVIEW_CHARS) {
            masked.take(PREVIEW_CHARS) + "…"
        } else {
            masked
        }
        return "$words words, ${trimmed.length} chars, preview=\"$preview\""
    }
}

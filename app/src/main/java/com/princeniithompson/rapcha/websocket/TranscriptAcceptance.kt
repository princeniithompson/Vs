package com.princeniithompson.rapcha.websocket

import java.util.Locale

/**
 * Decides whether a piece's transcript is real speech.
 *
 * Silent pieces in the field log sat around RMS 130, with peaks under 300.
 * Speech pieces sat in the thousands. A near-silent piece is not sent to the
 * model for a final, and any text it already produced is dropped. A quiet
 * piece that comes back as nothing but vocabulary terms is dropped too.
 */
object TranscriptAcceptance {
    const val NEAR_SILENT_MEAN = 250.0
    const val NEAR_SILENT_MAX = 450.0
    const val QUIET_MEAN = 400.0
    const val QUIET_MAX = 800.0

    fun isNearSilent(rmsMean: Double, rmsMax: Double, samples: Int = 1): Boolean {
        if (samples <= 0) return false
        return rmsMean < NEAR_SILENT_MEAN && rmsMax < NEAR_SILENT_MAX
    }

    fun isQuiet(rmsMean: Double, rmsMax: Double): Boolean {
        return rmsMean < QUIET_MEAN && rmsMax < QUIET_MAX
    }

    /**
     * Logged when [accept] drops non-blank text. The message has a word count and RMS, never the words.
     */
    fun silenceDroppedMessage(raw: String, accepted: String, rmsMean: Double, rmsMax: Double): String? {
        if (raw.isBlank() || accepted.isNotBlank()) return null
        val words = raw.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val mean = String.format(Locale.US, "%.0f", rmsMean)
        val max = String.format(Locale.US, "%.0f", rmsMax)
        return "words=$words rms_mean=$mean rms_max=$max"
    }

    fun accept(text: String, rmsMean: Double, rmsMax: Double, vocabulary: List<String>, samples: Int = 1): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (isNearSilent(rmsMean, rmsMax, samples)) return ""
        if (isQuiet(rmsMean, rmsMax) && isOnlyVocabulary(trimmed, vocabulary)) return ""
        return trimmed
    }

    fun vocabularyClause(terms: List<String>): String {
        val listed = terms.map { it.trim() }.filter { it.isNotEmpty() }
        if (listed.isEmpty()) return ""
        return " Use this vocabulary only to correct the spelling of words the speaker clearly says: ${listed.joinToString(", ")}. Never insert a term that was not spoken. If there is no clear speech, output nothing."
    }

    fun isOnlyVocabulary(text: String, vocabulary: List<String>): Boolean {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return false
        val allowed = vocabulary.flatMap { tokenize(it) }.toSet()
        if (allowed.isEmpty()) return false
        return tokens.all { it in allowed }
    }

    private fun tokenize(text: String): List<String> {
        return text.lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
    }
}

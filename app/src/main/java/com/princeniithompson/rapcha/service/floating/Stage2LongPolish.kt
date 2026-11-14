package com.princeniithompson.rapcha.service.floating

import com.princeniithompson.rapcha.config.RapchaConfig

/**
 * A multi-minute Stage-1 transcript made gemini-3.8-live sit silent past the
 * 6s first-token clock, then the raw transcript was inserted. Long text is
 * split into sentence-sized turns, and each turn is given time to speak.
 */
object Stage2TranscriptChunker {
    const val MAX_CHARS: Int = 24_000

    fun chunks(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        if (text.length <= MAX_CHARS) return listOf(text)
        val parts = ArrayList<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            if (rest.length <= MAX_CHARS) {
                parts.add(rest)
                break
            }
            val window = rest.substring(0, MAX_CHARS)
            val end = breakAfter(window).coerceIn(1, rest.length)
            val piece = rest.substring(0, end).trim()
            if (piece.isNotEmpty()) parts.add(piece)
            rest = rest.substring(end).trim()
        }
        return parts
    }

    fun join(prefix: String, addition: String): String {
        if (prefix.isEmpty()) return addition
        if (addition.isEmpty()) return prefix
        if (prefix.endsWith("\n") || addition.startsWith("\n")) return prefix + addition
        return "$prefix $addition"
    }

    private fun breakAfter(window: String): Int {
        val paragraph = window.lastIndexOf("\n\n")
        if (paragraph >= 500) return paragraph
        val line = window.lastIndexOf('\n')
        if (line >= 500) return line + 1
        val sentence = maxOf(
            window.lastIndexOf(". "),
            window.lastIndexOf("? "),
            window.lastIndexOf("! ")
        )
        if (sentence >= 500) return sentence + 1
        val space = window.lastIndexOf(' ')
        return if (space >= 500) space else window.length
    }
}

object Stage2LiveBudget {
    /** A warm Stage-2 socket opened at the start of a long dictation is not reused. */
    const val MAX_WARM_AGE_MS: Long = 45_000L

    fun firstTokenMs(userChars: Int): Long {
        val chars = userChars.coerceAtLeast(0)
        val extra = (chars / 250) * 1_000L
        val base = RapchaConfig.STAGE2_LIVE_TIMEOUT_MS + extra
        val bumped = if (chars > 900) base.coerceAtLeast(12_000L) else base
        return bumped.coerceAtMost(40_000L)
    }

    fun hardCapMs(userChars: Int): Long {
        val scaled = RapchaConfig.STAGE2_LIVE_HARD_CAP_MS + userChars.coerceAtLeast(0) * 25L
        return scaled.coerceAtMost(90_000L)
    }

    fun warmEnough(ageMs: Long): Boolean = ageMs in 0..MAX_WARM_AGE_MS
}

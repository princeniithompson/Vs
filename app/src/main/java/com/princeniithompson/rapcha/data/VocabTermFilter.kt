package com.princeniithompson.rapcha.data

/**
 * Hard reject for dictionary suggestions. Numbers, list markers, and punctuation
 * are not useful ASR terms. "10." must never be offered.
 *
 * Prompt rule for any suggestion model: never suggest numbers, ordinals,
 * list markers, or punctuation. Only suggest names, brands, and unusual spellings.
 */
object VocabTermFilter {
    const val PROMPT_RULE =
        "Never suggest numbers, ordinals, or list markers such as 10, 10., 10), 1., or #10. " +
            "Never suggest punctuation. Only suggest names, brands, and unusual spellings."

    fun rejectReason(term: String): String? {
        val raw = term.trim()
        if (raw.isEmpty()) return "empty"
        if (CommonEnglishCollocations.shouldSkip(raw)) return "common_phrase"
        if (raw.none { it.isLetterOrDigit() }) return "punctuation"
        if (LIST_MARKER.matches(raw)) return "list_marker"
        val core = raw.trim { !it.isLetterOrDigit() }
        if (core.isEmpty()) return "punctuation"
        if (LIST_MARKER.matches(core) || ORDINAL.matches(core)) return "list_marker"
        val letters = core.count { it.isLetter() }
        val digits = core.count { it.isDigit() }
        val alnum = core.filter { it.isLetterOrDigit() }
        if (alnum.isNotEmpty() && alnum.all { it.isDigit() }) return "digits"
        if (digits >= 3 && digits > letters) return "mostly_digits"
        if (letters < 2) return "not_a_word"
        return null
    }

    fun isUsable(term: String): Boolean = rejectReason(term) == null

    private val LIST_MARKER = Regex("""[#]?\d+[.)\]]?""")
    private val ORDINAL = Regex("""\d+(?:st|nd|rd|th)""", RegexOption.IGNORE_CASE)
}

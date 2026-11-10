package com.princeniithompson.rapcha.data

/**
 * Ordinary multi-word English that must not be offered as a personal dictionary term.
 * A hyphenated token such as "two-step" is one spelling to the counter, and SCOWL
 * often does not list that exact form, so the phrase itself is rejected here.
 */
internal object CommonEnglishCollocations {
    private val BLOCKED = setOf(
        "two step",
        "two steps",
        "two stepping",
        "step by step"
    )

    private val SQUEEZED = setOf(
        "twostep",
        "twosteps",
        "twostepping",
        "stepbystep"
    )

    /**
     * Very common pieces. A hyphenated or spaced token is ordinary when every piece is here.
     * Rare names and spellings such as "30-frst" are not in this set, so they can still be asked.
     */
    private val EVERYDAY = setOf(
        "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "with", "by", "at",
        "one", "two", "three", "four", "five",
        "step", "steps", "stepping",
        "set", "up", "out", "off", "over", "under",
        "time", "times", "day", "days", "way", "ways",
        "new", "old", "good", "real", "live", "next", "first", "last"
    )

    fun shouldSkip(token: String): Boolean {
        val raw = token.trim().lowercase()
        if (raw.isEmpty()) return false
        val spaced = raw.replace(Regex("[-_]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (spaced in BLOCKED) return true
        if (spaced.replace(" ", "") in SQUEEZED) return true
        val parts = spaced.split(" ")
        return parts.size >= 2 && parts.all { it in EVERYDAY }
    }

    /** Indexes of tokens that are themselves ordinary collocations, or that form one with a neighbor. */
    fun indexesToSkip(tokens: List<String>): Set<Int> {
        val skip = HashSet<Int>()
        tokens.forEachIndexed { index, token ->
            if (shouldSkip(token)) skip.add(index)
        }
        for (index in 0 until tokens.size - 1) {
            val phrase = "${tokens[index]} ${tokens[index + 1]}"
            if (shouldSkip(phrase)) {
                skip.add(index)
                skip.add(index + 1)
            }
        }
        return skip
    }
}

package com.princeniithompson.rapcha.data

/**
 * Counts spellings across finished sessions and decides when to ask the user
 * to add one to the dictionary. Nothing here writes the dictionary.
 */
object VocabSuggestionPolicy {
    const val SESSION_THRESHOLD = 3

    /** After No or a swipe, stay quiet for this spelling. */
    const val DISMISS_SNOOZE_MS = 14L * 24 * 60 * 60 * 1000

    /** After Edit, give the user a day to save a corrected spelling. */
    const val EDIT_SNOOZE_MS = 24L * 60 * 60 * 1000

    private val STOPWORDS = setOf(
        "a", "an", "the", "i", "me", "my", "we", "our", "ours", "you", "your", "yours",
        "he", "she", "it", "they", "them", "his", "her", "hers", "its", "their", "theirs",
        "to", "of", "and", "or", "in", "on", "for", "with", "at", "by", "from", "into",
        "out", "up", "off", "over", "under", "about", "as", "than", "then", "too", "of",
        "is", "am", "are", "was", "were", "be", "been", "being", "do", "did", "does",
        "have", "has", "had", "not", "but", "if", "so", "no", "nor", "yet",
        "this", "that", "these", "those", "there", "here", "what", "when", "where",
        "who", "whom", "whose", "which", "how", "why",
        "just", "very", "also", "can", "could", "will", "would", "shall", "should",
        "may", "might", "must",
        "ok", "okay", "oh", "um", "uh", "ah", "hmm",
        "im", "ive", "id", "ill", "i'm", "i've", "i'd", "i'll",
        "its", "it's", "that's", "thats", "there's", "theres",
        "hes", "he's", "shes", "she's", "were", "we're", "youre", "you're",
        "theyre", "they're", "dont", "don't", "doesnt", "doesn't", "didnt", "didn't",
        "cant", "can't", "wont", "won't", "lets", "let's", "whats", "what's",
        "heres", "here's", "gonna", "wanna"
    )

    data class SpellingRecord(
        val sessionCount: Int = 0,
        val casingVotes: Map<String, Int> = emptyMap(),
        val snoozeUntilMs: Long = 0L,
        val prompted: Boolean = false
    ) {
        val display: String
            get() = bestCasing(casingVotes)

        fun isReady(nowMs: Long): Boolean {
            return sessionCount >= SESSION_THRESHOLD && !prompted && nowMs >= snoozeUntilMs
        }

        fun isPersonalReady(nowMs: Long): Boolean {
            return sessionCount >= 1 && !prompted && nowMs >= snoozeUntilMs
        }
    }

    data class Suggestion(
        val key: String,
        val display: String,
        val sessionCount: Int
    )

    data class ObserveResult(
        val records: Map<String, SpellingRecord>,
        val suggestion: Suggestion?
    )

    /**
     * One finished session. Repeated spellings inside [text] count once.
     * A blank session leaves [records] unchanged.
     *
     * Rare spellings that are not ordinary English need [SESSION_THRESHOLD]
     * distinct finished sessions and an exact key. "30frst" and "30first" stay apart.
     * Emails, phone-like numbers, @handles, and the single token after an intro
     * phrase can be asked on the first session. Intro leftovers stop at and, who,
     * that, a comma, or the end of the text. Only that one token is kept.
     * "Prince Thompson" is not saved as one entry.
     *
     * [isOrdinaryWord] is SCOWL size 60 plus the supplementary common list.
     * Ordinary words and [dictionaryTerms] are not counted. A snoozed key is
     * quiet for both the three-session path and the one-session personal path.
     */
    fun observe(
        text: String,
        records: Map<String, SpellingRecord>,
        dictionaryTerms: Collection<String>,
        nowMs: Long,
        isOrdinaryWord: (String) -> Boolean = { false }
    ): ObserveResult {
        if (text.isBlank()) return ObserveResult(records, null)

        val known = dictionaryKeys(dictionaryTerms)
        val next = records.toMutableMap()
        next.keys.removeAll { key -> key in known || isOrdinaryWord(key) }

        val seenThisSession = LinkedHashSet<String>()
        val personalThisSession = LinkedHashSet<String>()
        val casingAdds = HashMap<String, MutableMap<String, Int>>()

        fun note(token: String, personal: Boolean) {
            if (!canTrack(token)) return
            val key = token.lowercase()
            if (key in known || isOrdinaryWord(key)) return
            if (key in seenThisSession) {
                if (personal) personalThisSession.add(key)
                return
            }
            seenThisSession.add(key)
            if (personal) personalThisSession.add(key)
            val bucket = casingAdds.getOrPut(key) { HashMap() }
            bucket[token] = (bucket[token] ?: 0) + 1
        }

        for (token in personalIdentifiers(text)) note(token, personal = true)
        for (token in countableTokens(text)) note(token, personal = false)

        for (key in seenThisSession) {
            val previous = next[key] ?: SpellingRecord()
            val votes = previous.casingVotes.toMutableMap()
            for ((casing, count) in casingAdds[key].orEmpty()) {
                votes[casing] = (votes[casing] ?: 0) + count
            }
            next[key] = previous.copy(
                sessionCount = previous.sessionCount + 1,
                casingVotes = votes
            )
        }

        val personalReady = seenThisSession.filter { key ->
            key in personalThisSession && next[key]?.isPersonalReady(nowMs) == true
        }
        val pool = if (personalReady.isNotEmpty()) {
            personalReady
        } else {
            seenThisSession.filter { key -> next[key]?.isReady(nowMs) == true }
        }

        var best: Suggestion? = null
        for (key in pool) {
            val record = next[key] ?: continue
            val candidate = Suggestion(
                key = key,
                display = record.display.ifBlank { key },
                sessionCount = record.sessionCount
            )
            val currentBest = best
            if (currentBest == null || compareSuggestions(candidate, currentBest) > 0) {
                best = candidate
            }
        }

        val chosen = best
        if (chosen != null) {
            val current = next[chosen.key]
            if (current != null) {
                next[chosen.key] = current.copy(prompted = true)
            }
        }
        return ObserveResult(next, chosen)
    }

    fun dismiss(
        records: Map<String, SpellingRecord>,
        key: String,
        nowMs: Long
    ): Map<String, SpellingRecord> {
        val record = records[key] ?: return records
        return records + (key to record.copy(
            prompted = false,
            snoozeUntilMs = nowMs + DISMISS_SNOOZE_MS
        ))
    }

    fun noteEditOpened(
        records: Map<String, SpellingRecord>,
        key: String,
        nowMs: Long
    ): Map<String, SpellingRecord> {
        val record = records[key] ?: return records
        return records + (key to record.copy(
            prompted = false,
            snoozeUntilMs = nowMs + EDIT_SNOOZE_MS
        ))
    }

    fun accept(
        records: Map<String, SpellingRecord>,
        key: String
    ): Map<String, SpellingRecord> {
        if (key !in records) return records
        return records - key
    }

    fun clearPrompt(
        records: Map<String, SpellingRecord>,
        key: String
    ): Map<String, SpellingRecord> {
        val record = records[key] ?: return records
        if (!record.prompted) return records
        return records + (key to record.copy(prompted = false))
    }

    /**
     * Emails, phone-like numbers, @handles, and the one token after an intro phrase.
     * The intro token stops at and, who, that, a comma, or the end of the text.
     */
    fun personalIdentifiers(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val source = text.replace('\u2019', '\'').replace('\u2018', '\'')
        val found = ArrayList<String>()
        val emailSpans = ArrayList<IntRange>()
        for (match in EMAIL.findAll(source)) {
            found.add(match.value)
            emailSpans.add(match.range)
        }
        for (match in HANDLE.findAll(source)) {
            if (emailSpans.any { match.range.first in it }) continue
            found.add(match.value)
        }
        for (match in PHONE.findAll(source)) {
            val raw = match.groupValues[1].trim().replace(Regex("\\s+"), " ")
            val digits = raw.count { it.isDigit() }
            if (digits in 10..15) found.add(raw)
        }
        for (phrase in INTRO_PHRASES) {
            val regex = Regex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE)
            for (match in regex.findAll(source)) {
                val rest = source.substring(match.range.last + 1)
                val token = firstContentToken(cutAtStop(rest)) ?: continue
                found.add(token)
            }
        }
        return found
    }

    fun countableTokens(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val tokens = ArrayList<String>()
        for (raw in text.split(Regex("\\s+"))) {
            val token = normalizeToken(raw)
            if (!isCountable(token)) continue
            tokens.add(token)
        }
        return tokens
    }

    fun bestCasing(votes: Map<String, Int>): String {
        if (votes.isEmpty()) return ""
        return votes.entries.maxWith(
            compareBy<Map.Entry<String, Int>> { casingRank(it.key) }
                .thenBy { it.value }
                .thenByDescending { it.key.length }
                .thenBy { it.key }
        ).key
    }

    internal fun dictionaryKeys(terms: Collection<String>): Set<String> {
        if (terms.isEmpty()) return emptySet()
        val keys = HashSet<String>()
        for (term in terms) {
            for (part in term.trim().split(Regex("\\s+"))) {
                val token = normalizeToken(part)
                if (token.isNotEmpty()) keys.add(token.lowercase())
            }
        }
        return keys
    }

    private fun compareSuggestions(candidate: Suggestion, current: Suggestion): Int {
        val byCount = candidate.sessionCount.compareTo(current.sessionCount)
        if (byCount != 0) return byCount
        val byLength = candidate.display.length.compareTo(current.display.length)
        if (byLength != 0) return byLength
        return current.key.compareTo(candidate.key)
    }

    private val INTRO_PHRASES = listOf(
        "my nickname is",
        "my name is",
        "i am",
        "i'm",
        "im",
        "called",
        "this is"
    )

    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val HANDLE = Regex("(?<![A-Za-z0-9@])@[A-Za-z0-9_]{2,30}")
    private val PHONE = Regex("(?<!\\d)(\\+?\\d[\\d\\s().-]{5,}\\d)(?!\\d)")
    private val INTRO_STOP = Regex("\\b(?:and|who|that)\\b", RegexOption.IGNORE_CASE)

    private fun cutAtStop(rest: String): String {
        val comma = rest.indexOf(',')
        val untilComma = if (comma >= 0) rest.substring(0, comma) else rest
        val stop = INTRO_STOP.find(untilComma) ?: return untilComma
        return untilComma.substring(0, stop.range.first)
    }

    private fun firstContentToken(slice: String): String? {
        for (raw in slice.trim().split(Regex("\\s+"))) {
            val token = normalizeToken(raw)
            if (token.isEmpty()) continue
            if (token.lowercase() in STOPWORDS) continue
            return token
        }
        return null
    }

    private fun canTrack(token: String): Boolean {
        if (token.isEmpty() || token.length > 80) return false
        return token.any { it.isLetter() || it.isDigit() }
    }

    private fun isCountable(token: String): Boolean {
        if (token.isEmpty() || token.length > 80) return false
        if (!token.any { it.isLetter() }) return false
        if (token.count { it.isLetter() } < 2) return false
        return token.lowercase() !in STOPWORDS
    }

    private fun normalizeToken(raw: String): String {
        var start = 0
        var end = raw.length
        while (start < end && isEdgeJunk(raw[start])) start++
        while (end > start && isEdgeJunk(raw[end - 1])) end--
        return if (start >= end) "" else raw.substring(start, end)
    }

    private fun isEdgeJunk(c: Char): Boolean {
        if (c == '@') return false
        return !c.isLetterOrDigit()
    }

    /**
     * Best casing we have actually seen. A proper-noun form wins over lowercase,
     * and lowercase wins over all-caps. Frequency breaks ties inside one rank.
     */
    private fun casingRank(token: String): Int {
        val hasUpper = token.any { it.isUpperCase() }
        val hasLower = token.any { it.isLowerCase() }
        return when {
            hasUpper && hasLower -> 3
            hasUpper -> 1
            else -> 2
        }
    }
}

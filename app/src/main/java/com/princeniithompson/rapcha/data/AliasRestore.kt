package com.princeniithompson.rapcha.data

/**
 * Restore-only spelling fix. A confirmed spoken alias is replaced with the
 * stored spelling. "thirty first" also matches ThirtyFirst and thirty-first.
 * It does not fuzzy-match a different phrase such as Grokbot, Desi First, or 31st.
 */
data class AliasRestoreResult(
    val text: String,
    val replacementCount: Int,
    val restoredTerms: List<String>
)

object AliasRestore {
    fun restore(text: String, items: List<VocabularyItem>): AliasRestoreResult {
        if (text.isEmpty()) return AliasRestoreResult(text, 0, emptyList())
        val rules = rulesFor(items)
        if (rules.isEmpty()) return AliasRestoreResult(text, 0, emptyList())

        val restored = linkedSetOf<String>()
        var count = 0
        val out = StringBuilder()
        var index = 0
        while (index < text.length) {
            val hit = if (isTokenBoundary(text, index)) matchAt(text, index, rules) else null
            if (hit == null) {
                out.append(text[index])
                index += 1
            } else {
                out.append(hit.term)
                count += 1
                restored.add(hit.term)
                index = hit.end
            }
        }
        return AliasRestoreResult(out.toString(), count, restored.toList())
    }

    fun matchesExact(text: String, phrase: String): Boolean {
        return countExact(text, phrase) > 0
    }

    fun countExact(text: String, phrase: String): Int {
        if (text.isBlank() || phrase.isBlank()) return 0
        return boundaryRegex(phrase).findAll(text).count()
    }

    fun trickyTermsPresent(text: String, items: List<VocabularyItem>): List<String> {
        return items.asSequence()
            .filter { it.vocabClass == VocabClass.TRICKY }
            .map { it.term.trim() }
            .filter { it.isNotEmpty() && countExact(text, it) > 0 }
            .distinctBy { it.lowercase() }
            .toList()
    }

    /**
     * Polish may rewrite freely, but it must not invent a Tricky spelling that
     * was not already in the restored transcript, and it must not drop one that was.
     */
    fun guardPolishedText(before: String, polished: String, items: List<VocabularyItem>): String {
        val restored = restore(polished, items).text
        val trickyTerms = items.asSequence()
            .filter { it.vocabClass == VocabClass.TRICKY }
            .map { it.term.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .toList()
        var current = restored
        for (term in trickyTerms) {
            current = retainAtMost(current, term, countExact(before, term))
        }
        for (term in trickyTerms) {
            val needed = countExact(before, term)
            if (needed > 0 && countExact(current, term) < needed) {
                return before
            }
        }
        return current
    }

    private data class AliasRule(
        val term: String,
        val tokens: List<String>,
        val joined: String
    )

    private data class Hit(val end: Int, val term: String)

    private fun rulesFor(items: List<VocabularyItem>): List<AliasRule> {
        return items.flatMap { item ->
            val term = item.term.trim()
            if (term.isEmpty()) return@flatMap emptyList()
            val termJoined = VocabClassifier.matchKey(term).joined
            VocabClassifier.aliasParts(item.spokenAlias).mapNotNull { part ->
                if (!VocabClassifier.isUsableAlias(part)) return@mapNotNull null
                val key = VocabClassifier.matchKey(part)
                if (key.joined.length < 2 || key.joined == termJoined) return@mapNotNull null
                AliasRule(term, key.tokens, key.joined)
            }
        }.distinctBy { it.term.lowercase() to it.joined }
            .sortedWith(compareByDescending<AliasRule> { it.joined.length }.thenByDescending { it.tokens.size })
    }

    private fun matchAt(text: String, index: Int, rules: List<AliasRule>): Hit? {
        for (rule in rules) {
            val end = matchRule(text, index, rule) ?: continue
            return Hit(end, rule.term)
        }
        return null
    }

    private fun matchRule(text: String, index: Int, rule: AliasRule): Int? {
        val tokenEnd = readTokenEnd(text, index)
        if (tokenEnd == index) return null
        val tokenKey = VocabClassifier.matchKey(text.substring(index, tokenEnd)).joined
        if (tokenKey == rule.joined) return tokenEnd
        if (rule.tokens.size < 2) return null

        var cursor = index
        for ((position, expected) in rule.tokens.withIndex()) {
            if (position > 0) {
                val afterSeparator = skipSeparators(text, cursor)
                if (afterSeparator == cursor) return null
                cursor = afterSeparator
            }
            val end = readTokenEnd(text, cursor)
            if (end == cursor) return null
            val got = VocabClassifier.matchKey(text.substring(cursor, end)).joined
            if (got != expected) return null
            cursor = end
        }
        return cursor
    }

    private fun isTokenBoundary(text: String, index: Int): Boolean {
        if (index <= 0) return true
        val previous = text[index - 1]
        return !previous.isLetter() && !previous.isDigit()
    }

    private fun readTokenEnd(text: String, index: Int): Int {
        var cursor = index
        while (cursor < text.length) {
            val ch = text[cursor]
            if (!ch.isLetter() && !ch.isDigit()) break
            cursor += 1
        }
        return cursor
    }

    private fun skipSeparators(text: String, index: Int): Int {
        var cursor = index
        var sawSeparator = false
        while (cursor < text.length) {
            val ch = text[cursor]
            if (ch.isLetter() || ch.isDigit()) break
            sawSeparator = true
            cursor += 1
        }
        return if (sawSeparator) cursor else index
    }

    private fun retainAtMost(text: String, phrase: String, allowed: Int): String {
        if (phrase.isBlank() || allowed < 0) return text
        var seen = 0
        val stripped = boundaryRegex(phrase).replace(text) { match ->
            seen += 1
            if (seen <= allowed) match.value else ""
        }
        if (seen <= allowed) return text
        return stripped.replace(Regex("[ \\t]{2,}"), " ").replace(Regex("\\n{3,}"), "\n\n")
    }

    private fun boundaryRegex(phrase: String): Regex {
        return Regex("(?<![\\p{L}\\p{N}])(?:${phraseBody(phrase)})(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }

    private fun phraseBody(phrase: String): String {
        val tokens = phrase.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tokens.joinToString("\\s+") { Regex.escape(it) }
    }
}

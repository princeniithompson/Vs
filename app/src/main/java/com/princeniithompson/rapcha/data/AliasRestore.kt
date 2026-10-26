package com.princeniithompson.rapcha.data

/**
 * Restore-only spelling fix. A confirmed spoken alias is replaced with the
 * stored spelling. Nothing else is rewritten: "Grok bot" does not become "30frst"
 * unless the user taught that exact alias.
 */
data class AliasRestoreResult(
    val text: String,
    val replacementCount: Int,
    val restoredTerms: List<String>
)

object AliasRestore {
    fun restore(text: String, items: List<VocabularyItem>): AliasRestoreResult {
        if (text.isEmpty()) return AliasRestoreResult(text, 0, emptyList())
        val rules = items.mapNotNull { item ->
            val alias = VocabClassifier.normalizeAlias(item.spokenAlias)
            val term = item.term.trim()
            if (term.isEmpty() || !VocabClassifier.isUsableAlias(alias)) return@mapNotNull null
            if (alias.equals(term, ignoreCase = true)) return@mapNotNull null
            alias to term
        }.sortedByDescending { it.first.length }
        if (rules.isEmpty()) return AliasRestoreResult(text, 0, emptyList())

        val pattern = rules.joinToString("|") { phraseBody(it.first) }
        val regex = Regex("(?<![\\p{L}\\p{N}])(?:$pattern)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        val restored = linkedSetOf<String>()
        var count = 0
        val replaced = regex.replace(text) { match ->
            val hit = rules.firstOrNull { ruleMatches(match.value, it.first) }
            if (hit == null) {
                match.value
            } else {
                count += 1
                restored.add(hit.second)
                hit.second
            }
        }
        return AliasRestoreResult(replaced, count, restored.toList())
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

    private fun ruleMatches(matched: String, alias: String): Boolean {
        val matchedTokens = matched.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val aliasTokens = alias.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (matchedTokens.size != aliasTokens.size) return false
        return matchedTokens.zip(aliasTokens).all { (left, right) -> left.equals(right, ignoreCase = true) }
    }
}

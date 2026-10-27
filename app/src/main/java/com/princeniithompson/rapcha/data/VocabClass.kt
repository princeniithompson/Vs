package com.princeniithompson.rapcha.data

/**
 * Class P (Easy) is a pronounceable word or phrase Live may see.
 * Class N (Tricky) is a digit-and-letter mix, an email, or a term that
 * contains @ . or _. Tricky spellings are never sent to Live.
 */
enum class VocabClass {
    EASY,
    TRICKY
}

object VocabClassifier {
    private val EMAIL = Regex("""[^\s@]+@[^\s@]+\.[^\s@]+""")

    fun classify(term: String): VocabClass {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return VocabClass.EASY
        if (EMAIL.containsMatchIn(trimmed)) return VocabClass.TRICKY
        if (trimmed.any { it == '@' || it == '.' || it == '_' }) return VocabClass.TRICKY
        val hasLetter = trimmed.any { it.isLetter() }
        val hasDigit = trimmed.any { it.isDigit() }
        if (hasLetter && hasDigit) return VocabClass.TRICKY
        return VocabClass.EASY
    }

    /**
     * Comma-separated spoken triggers, whitespace collapsed. Empty pieces are dropped.
     * "thirty first, three oh first" is two aliases. Matching ignores hyphens.
     */
    fun aliasParts(raw: String): List<String> {
        return raw.split(',')
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
    }

    fun normalizeAlias(raw: String): String {
        return aliasParts(raw).joinToString(", ")
    }

    fun isUsableAlias(alias: String): Boolean {
        val parts = aliasParts(alias)
        if (parts.isEmpty()) return false
        return parts.all { part -> part.length >= 2 && part.any { it.isLetter() } }
    }

    fun aliasError(alias: String): String? {
        if (alias.isBlank()) return null
        if (!isUsableAlias(alias)) {
            return "Use normal words. Separate extra ways of saying it with commas."
        }
        return null
    }

    /**
     * Forms used only for restore matching.
     * "thirty-first" and "thirty first" share tokens [thirty, first] and joined "thirtyfirst".
     */
    fun matchKey(alias: String): AliasMatchKey {
        val tokens = Regex("[\\p{L}\\p{N}]+")
            .findAll(alias.lowercase())
            .map { it.value }
            .filter { it.isNotEmpty() }
            .toList()
        return AliasMatchKey(tokens, tokens.joinToString(""))
    }
}

data class AliasMatchKey(
    val tokens: List<String>,
    val joined: String
)

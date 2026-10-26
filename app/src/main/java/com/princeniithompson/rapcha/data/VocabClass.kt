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

    fun normalizeAlias(raw: String): String {
        return raw.trim().replace(Regex("\\s+"), " ")
    }

    fun isUsableAlias(alias: String): Boolean {
        val normalized = normalizeAlias(alias)
        return normalized.length >= 2 && normalized.any { it.isLetter() }
    }

    fun aliasError(alias: String): String? {
        val normalized = normalizeAlias(alias)
        if (normalized.isEmpty()) return null
        if (!isUsableAlias(normalized)) return "Say it as a word or short phrase"
        return null
    }
}

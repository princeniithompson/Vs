package com.princeniithompson.rapcha.data

/**
 * Spellings merged into the vocabulary Live and Retry SMART already send.
 * They are not written into the user's Dictionary. A saved term with the same
 * spelling stays first and keeps the user's casing.
 *
 * The same tokens are ordinary words in [SupplementaryCommonWords], so the
 * suggestion prompt does not ask to add them.
 */
object BuiltInVocabulary {
    val seeds: List<String> = listOf(
        "MCP",
        "API",
        "API key",
        "VPN",
        "APK",
        "DNS",
        "HTTP",
        "HTTPS",
        "Gemini",
        "Grok",
        "WhatsApp"
    )

    fun merge(userTerms: List<String>): List<String> {
        val seen = HashSet<String>(userTerms.size + seeds.size)
        val merged = ArrayList<String>(userTerms.size + seeds.size)
        fun add(raw: String) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return
            if (!seen.add(trimmed.lowercase())) return
            merged.add(trimmed)
        }
        userTerms.forEach(::add)
        seeds.forEach(::add)
        return merged
    }
}

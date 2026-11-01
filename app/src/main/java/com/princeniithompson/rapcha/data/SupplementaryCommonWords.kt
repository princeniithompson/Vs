package com.princeniithompson.rapcha.data

/**
 * Names people dictate constantly that are not part of the user's Dictionary.
 * Kept separate from SCOWL and from [CustomVocabularyRepository].
 * Matching is case-insensitive and exact. "30frst" is not folded into "30first",
 * and "dns" does not match "adns".
 *
 * Tech tokens match [BuiltInVocabulary] so suggestion prompts stay quiet.
 * Those spellings are still sent to Live and Retry as recognition seeds.
 */
internal object SupplementaryCommonWords {
    private val WORDS = setOf(
        "whatsapp",
        "youtube",
        "chatgpt",
        "openai",
        "tiktok",
        "accra",
        "ghana",
        "instagram",
        "facebook",
        "snapchat",
        "gmail",
        "kumasi",
        "takoradi",
        "mcp",
        "api",
        "vpn",
        "apk",
        "dns",
        "http",
        "https",
        "gemini",
        "grok"
    )

    fun contains(token: String): Boolean = token.lowercase() in WORDS
}

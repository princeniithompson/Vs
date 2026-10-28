package com.princeniithompson.rapcha.data

/**
 * Names and tokens people dictate constantly that are not part of the user's Dictionary.
 * Kept separate from SCOWL and from [CustomVocabularyRepository].
 * Matching is case-insensitive and exact. "30frst" is not folded into "30first",
 * and "dns" does not swallow "Bonsam", "Kweku", or "Zalefant".
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
        // Tech, network, and money tokens. Exact spelling only.
        "2fa",
        "ai",
        "api",
        "cli",
        "csv",
        "dns",
        "gbps",
        "ghz",
        "ghs",
        "gpt",
        "gsm",
        "http",
        "https",
        "json",
        "kbps",
        "llm",
        "lte",
        "mbps",
        "mms",
        "mtn",
        "otp",
        "sdk",
        "sms",
        "ssid",
        "ssl",
        "tcp",
        "tls",
        "udp",
        "ui",
        "uri",
        "url",
        "usd",
        "ussd",
        "ux",
        "vpn",
        "wi-fi",
        "wifi"
    )

    fun contains(token: String): Boolean = token.lowercase() in WORDS
}

package com.princeniithompson.rapcha.service.floating

/**
 * Inline generateContent is one request. Files API stays for payloads that
 * would exceed the conservative inline audio ceiling.
 */
object RetryTransport {
    const val INLINE_BASE64_LIMIT = 15 * 1024 * 1024

    fun base64Size(encodedBytes: Int): Int {
        if (encodedBytes <= 0) return 0
        return ((encodedBytes + 2) / 3) * 4
    }

    fun preferInline(encodedBytes: Int, limit: Int = INLINE_BASE64_LIMIT): Boolean {
        return encodedBytes > 0 && base64Size(encodedBytes) <= limit
    }
}

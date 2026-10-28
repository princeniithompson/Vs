package com.princeniithompson.rapcha.data

import java.util.Locale

/**
 * Decides when a live session should use the same red-bubble path as airplane mode.
 * A resolve failure is exact phrase matching on the socket error. A missing socket
 * is a clock check. Neither path folds or prefixes user vocabulary.
 */
object NetworkProblemPolicy {
    /** A session with no SOCKET_OPEN by this age is treated as a network problem. */
    const val NO_SOCKET_TIMEOUT_MS = 4_000L

    fun isResolveFailure(details: String): Boolean {
        if (details.isBlank()) return false
        val lower = details.lowercase(Locale.US)
        return RESOLVE_MARKERS.any { marker -> lower.contains(marker) }
    }

    fun resolveReason(details: String): String {
        val compact = details.replace(Regex("\\s+"), " ").trim().take(220)
        return "dns_or_resolve | $compact"
    }

    fun noSocketReason(): String {
        return "no_socket | sockets=0 setup=false timeout_ms=$NO_SOCKET_TIMEOUT_MS"
    }

    /**
     * True once the session has waited [NO_SOCKET_TIMEOUT_MS] and Live has
     * neither opened a socket nor finished setup.
     */
    fun noSocketOpen(elapsedMs: Long, socketsOpened: Int, setupComplete: Boolean): Boolean {
        if (setupComplete || socketsOpened > 0) return false
        return elapsedMs >= NO_SOCKET_TIMEOUT_MS
    }

    private val RESOLVE_MARKERS = listOf(
        "unable to resolve host",
        "unknownhost",
        "failed to resolve",
        "no address associated",
        "nodename nor servname",
        "name or service not known",
        "temporary failure in name resolution",
        "android_getaddrinfo",
        "eai_nodata",
        "eai_noname",
        "eai_again",
        "eai_fail"
    )
}

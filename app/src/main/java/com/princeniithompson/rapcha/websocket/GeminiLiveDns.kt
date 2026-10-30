package com.princeniithompson.rapcha.websocket

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * DNS for the Gemini Live socket.
 *
 * OkHttp 4.10 has no Happy Eyeballs. [Dns.SYSTEM] follows Android's
 * resolver order, which is IPv6 first, and a stuck lookup is not covered by
 * [okhttp3.OkHttpClient] connectTimeout. This implementation bounds the lookup
 * and tries IPv4 addresses before IPv6 when both exist. IPv6-only answers are
 * left unchanged.
 */
class GeminiLiveDns(
    private val system: Dns = Dns.SYSTEM,
    private val lookupTimeoutMs: Long = LOOKUP_TIMEOUT_MS
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = resolve(hostname)
        if (resolved.isEmpty()) {
            throw UnknownHostException("no_socket dns returned no addresses host=$hostname")
        }
        return preferIpv4(resolved)
    }

    private fun resolve(hostname: String): List<InetAddress> {
        val task = java.util.concurrent.FutureTask { system.lookup(hostname) }
        val thread = Thread(task, "rapcha-live-dns").apply { isDaemon = true }
        thread.start()
        return try {
            task.get(lookupTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            throw UnknownHostException(
                "no_socket dns lookup exceeded ${lookupTimeoutMs}ms host=$hostname dns=ipv4-first"
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw UnknownHostException("no_socket dns lookup interrupted host=$hostname")
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is UnknownHostException) throw cause
            throw UnknownHostException(
                "no_socket dns lookup failed host=$hostname: ${cause?.message ?: e.message}"
            ).apply { initCause(cause) }
        }
    }

    companion object {
        const val LOOKUP_TIMEOUT_MS = 2_000L

        fun preferIpv4(addresses: List<InetAddress>): List<InetAddress> {
            if (addresses.size < 2) return addresses
            val ipv4 = ArrayList<InetAddress>(addresses.size)
            val other = ArrayList<InetAddress>(addresses.size)
            for (address in addresses) {
                if (address is Inet4Address) ipv4.add(address) else other.add(address)
            }
            if (ipv4.isEmpty() || other.isEmpty()) return addresses
            return ipv4 + other
        }
    }
}

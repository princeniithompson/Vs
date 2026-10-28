package com.princeniithompson.rapcha.service

import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.ConnectionState
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import java.util.concurrent.atomic.AtomicReference

/**
 * Turns the floating bubble red for a live network problem and writes one
 * diagnostics event per distinct reason. Setup, or a socket opening, clears it.
 * A plain Connecting update does not, so a session that never opens a socket
 * stays red until Live actually recovers.
 */
object LiveNetworkSignals {
    private val lastReason = AtomicReference<String?>(null)

    fun raise(source: DiagnosticSource, reason: String) {
        val trimmed = reason.trim()
        FloatingBubbleManager.setNetworkProblem(true)
        if (trimmed.isEmpty()) return
        while (true) {
            val previous = lastReason.get()
            if (previous == trimmed) return
            if (lastReason.compareAndSet(previous, trimmed)) {
                AppLogRepository.logEvent(source, DiagnosticType.NETWORK_PROBLEM, trimmed)
                return
            }
        }
    }

    fun clear() {
        FloatingBubbleManager.setNetworkProblem(false)
        lastReason.set(null)
    }

    /**
     * Airplane mode and hard reconnects already arrive as [ConnectionState.Error]
     * or as Connecting while a retry is in flight. Those turn the bubble red.
     * Streaming and an opened socket mean setup can proceed, so the red clears.
     * The first Connecting state of a session does not clear a no-socket flag.
     */
    fun onConnectionState(state: ConnectionState, reconnecting: Boolean) {
        val failed = state is ConnectionState.Error ||
            (state is ConnectionState.Connecting && reconnecting)
        val recovered = state is ConnectionState.Streaming ||
            state is ConnectionState.ConnectedWaitingSetup
        when {
            failed -> FloatingBubbleManager.setNetworkProblem(true)
            recovered -> clear()
        }
    }

    internal fun resetForTest() {
        lastReason.set(null)
        FloatingBubbleManager.setNetworkProblem(false)
    }
}

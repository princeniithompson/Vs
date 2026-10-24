package com.princeniithompson.rapcha.service

/**
 * One published Safe Mode decision.
 * A candidate has to stay the same for [STABLE_MS] before it publishes,
 * and a published value cannot flip again until [MIN_FLIP_GAP_MS] has passed.
 * Alternating true and false inside that window never settles, so it never publishes.
 */
class SafeModeGate(
    private val now: () -> Long,
    private val stableMs: Long = STABLE_MS,
    private val minFlipGapMs: Long = MIN_FLIP_GAP_MS
) {
    var published: Boolean? = null
        private set

    private var candidate: Boolean? = null
    private var candidateSince: Long = 0L
    private var lastFlipAt: Long = 0L

    /**
     * @return the new published value, or null when nothing should change yet.
     */
    fun consider(next: Boolean): Boolean? {
        if (published == next) {
            candidate = null
            return null
        }
        // The resting state is "not sensitive". Accepting that baseline is not a flip,
        // so the first real engage is not held back by the gap.
        if (published == null && !next && candidate == null) {
            published = false
            candidate = null
            return null
        }
        if (candidate != next) {
            candidate = next
            candidateSince = now()
            return null
        }
        if (now() - candidateSince < stableMs) return null
        if (lastFlipAt != 0L && now() - lastFlipAt < minFlipGapMs) return null
        published = next
        lastFlipAt = now()
        candidate = null
        return next
    }

    fun notePublished(value: Boolean, at: Long = now()) {
        published = value
        candidate = null
        lastFlipAt = at
    }

    companion object {
        const val STABLE_MS = 500L
        const val MIN_FLIP_GAP_MS = 1000L
    }
}

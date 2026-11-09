package com.princeniithompson.rapcha.service.lens

/**
 * Pure geometry and privacy rules for the lens. No window or model calls.
 */
data class ScreenRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

    fun area(): Int {
        val w = (right - left).coerceAtLeast(0)
        val h = (bottom - top).coerceAtLeast(0)
        return w * h
    }
}

enum class LensSource { ACCESSIBILITY, OCR }

data class LensCandidate(
    val text: String,
    val bounds: ScreenRect,
    val source: LensSource,
    val editable: Boolean,
    val password: Boolean,
    val sensitivePackage: Boolean,
    val secureWindow: Boolean,
    val ownOverlay: Boolean
) {
    val blocked: Boolean
        get() = password || sensitivePackage || secureWindow || ownOverlay || text.isBlank()
}

object LensSelection {
    fun pick(candidates: List<LensCandidate>, x: Int, y: Int): LensCandidate? {
        val containing = candidates.filter { it.bounds.contains(x, y) && it.text.isNotBlank() }
        val smallest = containing.minWithOrNull(compareBy<LensCandidate> { it.bounds.area() }.thenBy { it.text.length })
            ?: return null
        if (smallest.blocked) return null
        return smallest
    }

    /**
     * Replace the selected span inside a field. One match only.
     * A missing or repeated span is not rewritten.
     */
    fun splice(fieldText: String, selected: String, output: String): String? {
        if (selected.isEmpty() || output.isEmpty()) return null
        val first = fieldText.indexOf(selected)
        if (first < 0) return null
        if (fieldText.indexOf(selected, first + selected.length) >= 0) return null
        return fieldText.replaceRange(first, first + selected.length, output)
    }
}

/**
 * Double-tap gate for the lifesaver bubble.
 * Times match ViewConfiguration: 40ms minimum, 300ms maximum, measured from the
 * first finger-up to the second finger-down. A tap that moves past the touch
 * slop, lasts 300ms, uses a second finger, or is cancelled is not a tap.
 */
class LensDoubleTapDetector(
    private val touchSlopPx: Float,
    private val doubleTapSlopPx: Float,
    private val minGapMs: Long = MIN_GAP_MS,
    private val maxGapMs: Long = MAX_GAP_MS,
    private val maxTapMs: Long = MAX_TAP_MS
) {
    data class Decision(
        val cancelPendingTap: Boolean = false,
        val scheduleSingleTapAtMs: Long? = null,
        val fireSingleTap: Boolean = false,
        val activateLens: Boolean = false,
        val startBubbleDrag: Boolean = false,
        val draggingBubble: Boolean = false,
        val endBubbleDrag: Boolean = false
    )

    private enum class Phase { IDLE, DOWN, PENDING, SECOND }

    private var phase = Phase.IDLE
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var firstX = 0f
    private var firstY = 0f
    private var firstUpAt = 0L
    private var moved = false
    private var extraPointer = false
    private var dragging = false

    fun down(timeMs: Long, x: Float, y: Float, pointerCount: Int): Decision {
        if (pointerCount > 1) {
            val cancel = phase == Phase.PENDING
            reset()
            return Decision(cancelPendingTap = cancel)
        }
        if (phase == Phase.PENDING) {
            val gap = timeMs - firstUpAt
            val near = hypot(x - firstX, y - firstY) <= doubleTapSlopPx
            if (gap in minGapMs..maxGapMs && near) {
                phase = Phase.SECOND
                downAt = timeMs
                downX = x
                downY = y
                moved = false
                extraPointer = false
                dragging = false
                return Decision(cancelPendingTap = true)
            }
            phase = Phase.DOWN
            downAt = timeMs
            downX = x
            downY = y
            moved = false
            extraPointer = false
            dragging = false
            return Decision(cancelPendingTap = true)
        }
        phase = Phase.DOWN
        downAt = timeMs
        downX = x
        downY = y
        moved = false
        extraPointer = false
        dragging = false
        return Decision()
    }

    fun move(timeMs: Long, x: Float, y: Float, pointerCount: Int): Decision {
        if (phase != Phase.DOWN && phase != Phase.SECOND) return Decision()
        if (pointerCount > 1) {
            extraPointer = true
            return Decision(cancelPendingTap = phase == Phase.SECOND)
        }
        val travel = hypot(x - downX, y - downY)
        if (!dragging && travel > touchSlopPx) {
            moved = true
            dragging = true
            val wasSecond = phase == Phase.SECOND
            phase = Phase.DOWN
            return Decision(cancelPendingTap = wasSecond, startBubbleDrag = true, draggingBubble = true)
        }
        if (dragging) return Decision(draggingBubble = true)
        if (timeMs - downAt > maxTapMs) moved = true
        return Decision()
    }

    fun up(timeMs: Long, x: Float, y: Float, cancelled: Boolean): Decision {
        val wasSecond = phase == Phase.SECOND
        val held = timeMs - downAt
        val tap = !cancelled && !moved && !extraPointer && !dragging && held in 0..maxTapMs
        if (wasSecond && tap) {
            reset()
            return Decision(activateLens = true)
        }
        if (dragging) {
            reset()
            return Decision(endBubbleDrag = true)
        }
        if (phase == Phase.DOWN && tap) {
            phase = Phase.PENDING
            firstX = downX
            firstY = downY
            firstUpAt = timeMs
            return Decision(scheduleSingleTapAtMs = timeMs + maxGapMs)
        }
        reset()
        return Decision()
    }

    fun tick(timeMs: Long): Decision {
        if (phase == Phase.PENDING && timeMs >= firstUpAt + maxGapMs) {
            reset()
            return Decision(fireSingleTap = true)
        }
        return Decision()
    }

    fun reset() {
        phase = Phase.IDLE
        moved = false
        extraPointer = false
        dragging = false
    }

    private fun hypot(dx: Float, dy: Float): Float = kotlin.math.hypot(dx, dy)

    companion object {
        const val MIN_GAP_MS = 40L
        const val MAX_GAP_MS = 300L
        const val MAX_TAP_MS = 300L
    }
}

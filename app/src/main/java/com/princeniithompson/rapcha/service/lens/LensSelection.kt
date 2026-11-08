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

enum class BubblePointer {
    TAP,
    DRAG,
    LONG_PRESS,
    LENS
}

object BubblePointerPolicy {
    fun resolve(longPressFired: Boolean, dragged: Boolean, lensEnabled: Boolean): BubblePointer {
        return when {
            longPressFired && dragged && lensEnabled -> BubblePointer.LENS
            dragged -> BubblePointer.DRAG
            longPressFired -> BubblePointer.LONG_PRESS
            else -> BubblePointer.TAP
        }
    }
}

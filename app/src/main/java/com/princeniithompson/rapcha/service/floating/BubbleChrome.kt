package com.princeniithompson.rapcha.service.floating

/**
 * Global recording chrome. Original stays the default until the user taps
 * Select as active on the compact page. Swiping the preview does not switch it.
 *
 * Compact sizes are taken from the phone screenshot at about 2.6 px/dp.
 * Cancel 47dp, pill 90×47dp with a 23.5dp corner, Complete 47dp, gaps 6dp.
 */
enum class BubbleChromeStyle {
    ORIGINAL,
    COMPACT;

    fun stored(): String = name.lowercase()

    companion object {
        fun fromStored(value: String?): BubbleChromeStyle {
            return if (value.equals(COMPACT.stored(), ignoreCase = true)) COMPACT else ORIGINAL
        }
    }
}

object BubbleChromeGeometry {
    const val CANCEL_DP = 47
    const val PILL_WIDTH_DP = 90
    const val PILL_HEIGHT_DP = 47
    const val COMPLETE_DP = 47
    const val GAP_DP = 6
    const val SESSION_CAP_SECONDS = 600
}

/**
 * Expanded full-width chrome stays pinned to x = 0.
 * A compact window, even while the session is expanded, can move on both axes.
 */
object OverlayDragMath {
    fun nextX(
        currentX: Int,
        dx: Int,
        screenWidth: Int,
        windowWidth: Int,
        lockHorizontal: Boolean
    ): Int {
        if (lockHorizontal) return 0
        val maxX = (screenWidth - windowWidth).coerceAtLeast(0)
        return (currentX + dx).coerceIn(0, maxX)
    }

    fun locksHorizontal(expanded: Boolean, matchParentWidth: Boolean): Boolean {
        return expanded && matchParentWidth
    }
}

object CompactGlowLevel {
    /** Same 0–1 amplitude as the original container, with extra gain for the small pill. */
    fun fromAmplitude(raw: Float): Float = (raw.coerceIn(0f, 1f) * 1.85f).coerceAtMost(1f)
}

object RecordingGlowPreview {
    /** Same idle interval as the Home words/apps carousel. */
    const val IDLE_PAGE_MS = 19_000L
    const val PAGE_COUNT = 2

    fun nextPage(settledPage: Int): Int = (settledPage + 1) % PAGE_COUNT

    fun shouldAutoAdvance(idle: Boolean, userInteracting: Boolean, onScreen: Boolean): Boolean {
        return idle && !userInteracting && onScreen
    }

    /** Only an explicit button press changes the saved style. */
    fun styleAfterPreview(current: BubbleChromeStyle, page: Int, buttonPressed: Boolean): BubbleChromeStyle {
        if (!buttonPressed) return current
        return if (page == 0) BubbleChromeStyle.ORIGINAL else BubbleChromeStyle.COMPACT
    }
}

object DynamicTonePolicy {
    const val LUMINOUS = "luminous"
    const val DEEP = "deep"

    fun normalize(stored: String?): String {
        return if (stored == DEEP) DEEP else LUMINOUS
    }
}

object SessionClockFormat {
    fun format(elapsedSeconds: Int): String {
        val capped = elapsedSeconds.coerceIn(0, BubbleChromeGeometry.SESSION_CAP_SECONDS)
        val minutes = capped / 60
        val seconds = capped % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}

object CompleteActionRoute {
    /**
     * The visible Complete control. Two-step still runs the existing Live finish.
     * One-step still runs the existing Smart finish. There is no manual Polish button.
     */
    fun usesLiveFinish(sectionIsTwoStepLive: Boolean): Boolean = sectionIsTwoStepLive
}

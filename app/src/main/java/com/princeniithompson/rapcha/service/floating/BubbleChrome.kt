package com.princeniithompson.rapcha.service.floating

/**
 * Global recording chrome. Words is the original bubble. Mini is the compact bubble.
 * The saved value lives in the existing bubble_chrome_style preference.
 * Older "original" and "compact" values still load.
 *
 * Compact sizes are taken from the phone screenshot at about 2.6 px/dp.
 * Cancel 47dp, pill 90×47dp with a 23.5dp corner, Complete 47dp, gaps 6dp.
 */
enum class BubbleChromeStyle {
    ORIGINAL,
    COMPACT;

    /** Persisted activeContainer. words is original, mini is compact. */
    fun stored(): String = if (this == COMPACT) MINI else WORDS

    fun activeContainer(): String = stored()

    companion object {
        const val WORDS = "words"
        const val MINI = "mini"

        fun fromStored(value: String?): BubbleChromeStyle {
            return when (value?.lowercase()) {
                MINI, "compact" -> COMPACT
                else -> ORIGINAL
            }
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

    fun styleForPage(page: Int): BubbleChromeStyle {
        return if (page == 0) BubbleChromeStyle.ORIGINAL else BubbleChromeStyle.COMPACT
    }

    /**
     * Moving the preview, including the 19-second auto-scroll and a swipe,
     * never changes the saved bubble. Only an explicit choice does.
     */
    fun styleAfterPreview(current: BubbleChromeStyle, page: Int, explicitChoice: Boolean): BubbleChromeStyle {
        if (!explicitChoice) return current
        return styleForPage(page)
    }
}

/**
 * Opaque fills for the Luminous and Deep controls. None of these carry alpha.
 */
object GlowModePaint {
    const val LUMINOUS = 0xFF34D399.toInt()
    const val DEEP = 0xFF065F46.toInt()
    const val IDLE = 0xFFD1FAE5.toInt()
    const val ON_SELECTED = 0xFFFFFFFF.toInt()
    const val ON_IDLE = 0xFF064E3B.toInt()

    fun chip(selected: Boolean, mode: String): Int {
        if (!selected) return IDLE
        return if (mode == DynamicTonePolicy.DEEP) DEEP else LUMINOUS
    }

    fun label(selected: Boolean): Int = if (selected) ON_SELECTED else ON_IDLE

    fun isOpaque(argb: Int): Boolean = argb ushr 24 == 0xFF

    /** Forces a full alpha byte so a tone chip cannot be drawn translucent. */
    fun opaque(argb: Int): Int = argb or 0xFF000000.toInt()
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

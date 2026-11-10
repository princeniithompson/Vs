package com.princeniithompson.rapcha.service.floating

import com.princeniithompson.rapcha.ui.components.GlowAnimationCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleChromeTest {
    @Test
    fun `style stays original until the active button is pressed`() {
        assertEquals(BubbleChromeStyle.ORIGINAL, BubbleChromeStyle.fromStored(null))
        assertEquals(BubbleChromeStyle.ORIGINAL, BubbleChromeStyle.fromStored("original"))
        assertEquals(BubbleChromeStyle.COMPACT, BubbleChromeStyle.fromStored("compact"))
        val current = BubbleChromeStyle.ORIGINAL
        assertEquals(current, RecordingGlowPreview.styleAfterPreview(current, page = 1, buttonPressed = false))
        assertEquals(
            BubbleChromeStyle.COMPACT,
            RecordingGlowPreview.styleAfterPreview(current, page = 1, buttonPressed = true)
        )
        assertEquals(
            BubbleChromeStyle.ORIGINAL,
            RecordingGlowPreview.styleAfterPreview(BubbleChromeStyle.COMPACT, page = 0, buttonPressed = true)
        )
    }

    @Test
    fun `preview auto scroll matches the home idle interval and pauses while busy`() {
        assertEquals(19_000L, RecordingGlowPreview.IDLE_PAGE_MS)
        assertEquals(1, RecordingGlowPreview.nextPage(0))
        assertEquals(0, RecordingGlowPreview.nextPage(1))
        assertTrue(RecordingGlowPreview.shouldAutoAdvance(idle = true, userInteracting = false, onScreen = true))
        assertFalse(RecordingGlowPreview.shouldAutoAdvance(idle = false, userInteracting = false, onScreen = true))
        assertFalse(RecordingGlowPreview.shouldAutoAdvance(idle = true, userInteracting = true, onScreen = true))
        assertFalse(RecordingGlowPreview.shouldAutoAdvance(idle = true, userInteracting = false, onScreen = false))
    }

    @Test
    fun `muted and accent tones migrate to luminous and deep stays`() {
        assertEquals(DynamicTonePolicy.LUMINOUS, DynamicTonePolicy.normalize(null))
        assertEquals(DynamicTonePolicy.LUMINOUS, DynamicTonePolicy.normalize("muted"))
        assertEquals(DynamicTonePolicy.LUMINOUS, DynamicTonePolicy.normalize("tertiary"))
        assertEquals(DynamicTonePolicy.DEEP, DynamicTonePolicy.normalize("deep"))
    }

    @Test
    fun `all eight glows remain and the clock caps at ten minutes`() {
        assertEquals(8, GlowAnimationCatalogue.styles.size)
        assertEquals(8, GlowAnimationCatalogue.styles.map { it.id }.distinct().size)
        assertEquals("00:00", SessionClockFormat.format(0))
        assertEquals("01:24", SessionClockFormat.format(84))
        assertEquals("10:00", SessionClockFormat.format(600))
        assertEquals("10:00", SessionClockFormat.format(601))
    }
}

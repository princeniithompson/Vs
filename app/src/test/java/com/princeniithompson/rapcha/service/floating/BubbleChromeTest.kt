package com.princeniithompson.rapcha.service.floating

import com.princeniithompson.rapcha.ui.components.GlowAnimationCatalogue
import com.princeniithompson.rapcha.ui.components.WordsBubblePreviewSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleChromeTest {
    @Test
    fun `words and mini map onto the existing bubble style and older values still load`() {
        assertEquals(BubbleChromeStyle.ORIGINAL, BubbleChromeStyle.fromStored(null))
        assertEquals("words", BubbleChromeStyle.ORIGINAL.stored())
        assertEquals("mini", BubbleChromeStyle.COMPACT.stored())
        assertEquals(BubbleChromeStyle.ORIGINAL, BubbleChromeStyle.fromStored("words"))
        assertEquals(BubbleChromeStyle.ORIGINAL, BubbleChromeStyle.fromStored("original"))
        assertEquals(BubbleChromeStyle.COMPACT, BubbleChromeStyle.fromStored("mini"))
        assertEquals(BubbleChromeStyle.COMPACT, BubbleChromeStyle.fromStored("compact"))
        assertEquals(DynamicTonePolicy.LUMINOUS, DynamicTonePolicy.normalize(null))
        assertEquals(GlowAnimationCatalogue.DEFAULT_ID, "breathing_horizon")
    }

    @Test
    fun `auto scroll and swipes never change the active bubble`() {
        val words = BubbleChromeStyle.ORIGINAL
        val mini = BubbleChromeStyle.COMPACT
        assertEquals(words, RecordingGlowPreview.styleAfterPreview(words, page = 1, explicitChoice = false))
        assertEquals(mini, RecordingGlowPreview.styleAfterPreview(mini, page = 0, explicitChoice = false))
        var page = 0
        var active = words
        repeat(4) {
            page = RecordingGlowPreview.nextPage(page)
            active = RecordingGlowPreview.styleAfterPreview(active, page, explicitChoice = false)
        }
        assertEquals(words, active)
        assertEquals(mini, RecordingGlowPreview.styleAfterPreview(words, page = 1, explicitChoice = true))
        assertEquals(words, RecordingGlowPreview.styleForPage(0))
        assertEquals(mini, RecordingGlowPreview.styleForPage(1))
    }

    @Test
    fun `luminous and deep colors are opaque`() {
        listOf(
            GlowModePaint.LUMINOUS,
            GlowModePaint.DEEP,
            GlowModePaint.IDLE,
            GlowModePaint.ON_SELECTED,
            GlowModePaint.ON_IDLE,
            GlowModePaint.chip(selected = true, DynamicTonePolicy.LUMINOUS),
            GlowModePaint.chip(selected = true, DynamicTonePolicy.DEEP),
            GlowModePaint.chip(selected = false, DynamicTonePolicy.LUMINOUS),
            GlowModePaint.label(selected = true),
            GlowModePaint.label(selected = false)
        ).forEach { color ->
            assertTrue(GlowModePaint.isOpaque(color))
        }
        val washed = 0x4034D399
        assertTrue(GlowModePaint.isOpaque(GlowModePaint.opaque(washed)))
        assertEquals(0xFF34D399.toInt(), GlowModePaint.opaque(washed))
    }

    @Test
    fun `glow list keeps its names in catalogue order and the words bubble stays blank`() {
        assertEquals(
            listOf(
                "Breathing Horizon",
                "Sunset Mirage",
                "Gentle Wave Slice",
                "Flashlight Core Beacon",
                "Electric Sunburst",
                "Bioluminescent Shimmer",
                "Liquid Specular Glass",
                "Velvet Ambient Calm"
            ),
            GlowAnimationCatalogue.styles.map { it.name }
        )
        assertEquals(listOf("Cancel", "Polish"), WordsBubblePreviewSpec.labels)
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
        assertEquals(47, BubbleChromeGeometry.CANCEL_DP)
        assertEquals(90, BubbleChromeGeometry.PILL_WIDTH_DP)
        assertEquals(47, BubbleChromeGeometry.PILL_HEIGHT_DP)
        assertEquals(47, BubbleChromeGeometry.COMPLETE_DP)
        assertEquals(6, BubbleChromeGeometry.GAP_DP)
    }

    @Test
    fun `expanded full-width chrome locks x and a compact window can move sideways`() {
        assertTrue(OverlayDragMath.locksHorizontal(expanded = true, matchParentWidth = true))
        assertFalse(OverlayDragMath.locksHorizontal(expanded = true, matchParentWidth = false))
        assertEquals(0, OverlayDragMath.nextX(40, 30, 1080, 1080, lockHorizontal = true))
        assertEquals(70, OverlayDragMath.nextX(40, 30, 1080, 200, lockHorizontal = false))
        assertEquals(880, OverlayDragMath.nextX(860, 40, 1080, 200, lockHorizontal = false))
        assertEquals(0, OverlayDragMath.nextX(10, -40, 1080, 200, lockHorizontal = false))
        assertEquals(0.2f, CompactGlowLevel.fromAmplitude(0.2f / 1.85f), 0.001f)
        assertEquals(1f, CompactGlowLevel.fromAmplitude(0.9f), 0.001f)
    }
}

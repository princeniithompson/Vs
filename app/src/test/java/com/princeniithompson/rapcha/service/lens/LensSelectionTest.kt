package com.princeniithompson.rapcha.service.lens

import com.princeniithompson.rapcha.config.RapchaConfig
import com.princeniithompson.rapcha.service.floating.LivePolishProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LensSelectionTest {

    @Test
    fun `smallest text node under the point wins and blocked nodes are refused`() {
        val parent = candidate("Home screen", 0, 0, 400, 800)
        val word = candidate("Home", 40, 40, 120, 80)
        val password = candidate("secret", 40, 40, 90, 70, password = true)
        assertEquals("Home", LensSelection.pick(listOf(parent, word), 50, 50)?.text)
        assertNull(LensSelection.pick(listOf(parent, password), 50, 50))
        assertNull(LensSelection.pick(listOf(candidate("bank", 0, 0, 40, 40, sensitive = true)), 10, 10))
    }

    @Test
    fun `one selected span can be replaced and a repeated span cannot`() {
        assertEquals("Casa", LensSelection.splice("Home", "Home", "Casa"))
        assertEquals("Go Casa now", LensSelection.splice("Go Home now", "Home", "Casa"))
        assertNull(LensSelection.splice("Home Home", "Home", "Casa"))
        assertNull(LensSelection.splice("Hello", "Home", "Casa"))
    }

    @Test
    fun `two short taps within the window arm the lens and cancel the single tap`() {
        val taps = detector()
        val first = tap(taps, downAt = 1_000L, upAt = 1_080L)
        assertEquals(1_080L + LensDoubleTapDetector.MAX_GAP_MS, first.scheduleSingleTapAtMs)
        assertFalse(first.activateLens)
        val secondDown = taps.down(1_200L, 4f, 4f, 1)
        assertTrue(secondDown.cancelPendingTap)
        assertFalse(secondDown.activateLens)
        assertFalse(taps.tick(1_080L + LensDoubleTapDetector.MAX_GAP_MS).fireSingleTap)
        val secondUp = taps.up(1_260L, 4f, 4f, cancelled = false)
        assertTrue(secondUp.activateLens)
        assertFalse(secondUp.fireSingleTap)
        assertNull(taps.tick(1_400L).scheduleSingleTapAtMs)
        assertFalse(taps.tick(1_400L).fireSingleTap)
    }

    @Test
    fun `slow far dragged held cancelled and multitouch taps do not arm the lens`() {
        val slow = detector()
        tap(slow, downAt = 0L, upAt = 80L)
        val late = slow.down(80L + LensDoubleTapDetector.MAX_GAP_MS + 1, 0f, 0f, 1)
        assertFalse(late.activateLens)
        assertTrue(late.cancelPendingTap)

        val far = detector()
        tap(far, downAt = 0L, upAt = 80L)
        val away = far.down(200L, 400f, 0f, 1)
        assertFalse(away.activateLens)
        assertTrue(away.cancelPendingTap)

        val dragged = detector()
        dragged.down(0L, 0f, 0f, 1)
        val drag = dragged.move(40L, 40f, 0f, 1)
        assertTrue(drag.startBubbleDrag)
        assertFalse(dragged.up(80L, 40f, 0f, cancelled = false).activateLens)

        val held = detector()
        held.down(0L, 0f, 0f, 1)
        assertNull(held.up(0L + LensDoubleTapDetector.MAX_TAP_MS + 1, 0f, 0f, cancelled = false).scheduleSingleTapAtMs)
        val heldThenDrag = detector()
        heldThenDrag.down(0L, 0f, 0f, 1)
        assertFalse(heldThenDrag.move(LensDoubleTapDetector.MAX_TAP_MS + 1, 1f, 0f, 1).activateLens)
        val afterHold = heldThenDrag.move(LensDoubleTapDetector.MAX_TAP_MS + 40, 40f, 0f, 1)
        assertTrue(afterHold.startBubbleDrag)
        assertFalse(afterHold.activateLens)

        val cancelled = detector()
        cancelled.down(0L, 0f, 0f, 1)
        val abort = cancelled.up(60L, 0f, 0f, cancelled = true)
        assertFalse(abort.activateLens)
        assertNull(abort.scheduleSingleTapAtMs)

        val multi = detector()
        val extra = multi.down(0L, 0f, 0f, 2)
        assertFalse(extra.activateLens)
        assertNull(multi.up(50L, 0f, 0f, cancelled = false).scheduleSingleTapAtMs)

        val secondDrag = detector()
        tap(secondDrag, 0L, 50L)
        assertTrue(secondDrag.down(150L, 0f, 0f, 1).cancelPendingTap)
        val moved = secondDrag.move(180L, 40f, 0f, 1)
        assertTrue(moved.startBubbleDrag)
        assertFalse(moved.activateLens)
        val draggedUp = secondDrag.up(220L, 40f, 0f, cancelled = false)
        assertFalse(draggedUp.activateLens)
        assertTrue(draggedUp.endBubbleDrag)
        assertNull(draggedUp.scheduleSingleTapAtMs)
        assertFalse(secondDrag.tick(1_000L).fireSingleTap)

        val rebound = detector()
        tap(rebound, 0L, 50L)
        rebound.down(50L + LensDoubleTapDetector.MIN_GAP_MS - 1, 0f, 0f, 1)
        val again = rebound.up(100L, 0f, 0f, cancelled = false)
        assertFalse(again.activateLens)
        assertEquals(100L + LensDoubleTapDetector.MAX_GAP_MS, again.scheduleSingleTapAtMs)
    }

    @Test
    fun `a single tap waits out the double-tap window before it fires`() {
        val taps = detector()
        val first = tap(taps, downAt = 500L, upAt = 560L)
        assertEquals(560L + LensDoubleTapDetector.MAX_GAP_MS, first.scheduleSingleTapAtMs)
        assertFalse(taps.tick(560L + LensDoubleTapDetector.MAX_GAP_MS - 1).fireSingleTap)
        assertTrue(taps.tick(560L + LensDoubleTapDetector.MAX_GAP_MS).fireSingleTap)
    }

    @Test
    fun `after the lens is armed a later drag is not another double tap`() {
        val taps = detector()
        tap(taps, 0L, 50L)
        taps.down(150L, 0f, 0f, 1)
        assertTrue(taps.up(200L, 0f, 0f, cancelled = false).activateLens)
        taps.reset()
        taps.down(1_000L, 0f, 0f, 1)
        val drag = taps.move(1_040L, 30f, 0f, 1)
        assertTrue(drag.startBubbleDrag)
        assertFalse(drag.activateLens)
        assertTrue(taps.up(1_100L, 30f, 0f, cancelled = false).endBubbleDrag)
    }

    @Test
    fun `lens prompts use gemini-3_8-live and leave lyra alone`() {
        assertEquals("models/gemini-3.8-live", LensPrompts.MODEL)
        assertEquals(RapchaConfig.STAGE2_LIVE_MODEL, LensPrompts.MODEL)
        val system = LensPrompts.system(LensAction.TRANSLATE, "Spanish", "")
        assertFalse(system.contains("flash"))
        assertFalse(system.contains("Lyra"))
        val setup = JSONObject(LivePolishProtocol.setupMessage(system))
        val root = setup.getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", root.getString("model"))
        val config = root.getJSONObject("generationConfig")
        assertEquals("AUDIO", config.getJSONArray("responseModalities").getString(0))
        assertEquals(
            "Zephyr",
            config.getJSONObject("speechConfig").getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig").getString("voiceName")
        )
        assertTrue(root.has("outputAudioTranscription"))
        val raw = setup.toString()
        assertTrue(raw.indexOf("responseModalities") > raw.indexOf("generationConfig"))
    }

    @Test
    fun `tone presets seed the three defaults`() {
        val raw = org.json.JSONArray().apply {
            LensSettings.DEFAULT_TONES.forEach { tone ->
                put(org.json.JSONObject().put("name", tone.name).put("emoji", tone.emoji).put("instruction", tone.instruction))
            }
        }.toString()
        val parsed = LensSettings.parse(raw)
        assertEquals(listOf("Friendly", "Professional", "Casual"), parsed.map { it.name })
        assertEquals("😊", parsed.first().emoji)
    }

    private fun detector(): LensDoubleTapDetector = LensDoubleTapDetector(touchSlopPx = 16f, doubleTapSlopPx = 48f)

    private fun tap(detector: LensDoubleTapDetector, downAt: Long, upAt: Long): LensDoubleTapDetector.Decision {
        detector.down(downAt, 0f, 0f, 1)
        return detector.up(upAt, 0f, 0f, cancelled = false)
    }

    private fun candidate(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        password: Boolean = false,
        sensitive: Boolean = false
    ): LensCandidate {
        return LensCandidate(
            text = text,
            bounds = ScreenRect(left, top, right, bottom),
            source = LensSource.ACCESSIBILITY,
            editable = false,
            password = password,
            sensitivePackage = sensitive,
            secureWindow = false,
            ownOverlay = false
        )
    }
}

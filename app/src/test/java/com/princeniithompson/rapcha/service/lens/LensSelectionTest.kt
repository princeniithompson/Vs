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
    fun `long press drag is the lens only when the control is on`() {
        assertEquals(BubblePointer.LENS, BubblePointerPolicy.resolve(true, true, true))
        assertEquals(BubblePointer.DRAG, BubblePointerPolicy.resolve(true, true, false))
        assertEquals(BubblePointer.DRAG, BubblePointerPolicy.resolve(false, true, true))
        assertEquals(BubblePointer.TAP, BubblePointerPolicy.resolve(false, false, true))
        assertEquals(BubblePointer.LONG_PRESS, BubblePointerPolicy.resolve(true, false, true))
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

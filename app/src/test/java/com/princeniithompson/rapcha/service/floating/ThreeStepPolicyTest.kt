package com.princeniithompson.rapcha.service.floating

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.princeniithompson.rapcha.config.RapchaConfig
import com.princeniithompson.rapcha.websocket.DictationPiecePlanner
import com.princeniithompson.rapcha.websocket.GeminiLiveWebSocketClient
import com.princeniithompson.rapcha.websocket.InterimPreviewMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreeStepPolicyTest {

    @Test
    fun `missing three-step preference is on and an explicit off stays off`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = context.getSharedPreferences("three_step_default", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertFalse(prefs.contains(ThreeStepPolishSettings.PREFS_KEY))
        assertTrue(ThreeStepPolishSettings.read(prefs))
        ThreeStepPolishSettings.write(prefs, false)
        assertFalse(ThreeStepPolishSettings.read(prefs))
        ThreeStepPolishSettings.write(prefs, true)
        assertTrue(ThreeStepPolishSettings.read(prefs))
    }

    @Test
    fun `three-step applies only to an eligible AI live section`() {
        val onAi = ThreeStepPolicy.resolve(
            Stage2PolishBackend.AUTO,
            aiApp = true,
            threeStepEnabled = true,
            savedSmartMode = false
        )
        assertEquals(SectionPolishMode.TWO_STEP_LIVE, onAi.mode)
        assertEquals(Stage1Capture.SMART, onAi.stage1)
        assertTrue(onAi.threeStep)

        val offAi = ThreeStepPolicy.resolve(
            Stage2PolishBackend.AUTO,
            aiApp = true,
            threeStepEnabled = false,
            savedSmartMode = true
        )
        assertEquals(SectionPolishMode.TWO_STEP_LIVE, offAi.mode)
        assertEquals(Stage1Capture.VERBATIM, offAi.stage1)
        assertFalse(offAi.threeStep)

        val whatsApp = ThreeStepPolicy.resolve(
            Stage2PolishBackend.AUTO,
            aiApp = false,
            threeStepEnabled = true,
            savedSmartMode = true
        )
        assertEquals(SectionPolishMode.SMART, whatsApp.mode)
        assertEquals(Stage1Capture.SMART, whatsApp.stage1)
        assertFalse(whatsApp.threeStep)

        val forceSmart = ThreeStepPolicy.resolve(
            Stage2PolishBackend.NON_LIVE,
            aiApp = true,
            threeStepEnabled = true,
            savedSmartMode = false
        )
        assertEquals(SectionPolishMode.SMART, forceSmart.mode)
        assertEquals(Stage1Capture.VERBATIM, forceSmart.stage1)
        assertFalse(forceSmart.threeStep)

        val forceLive = ThreeStepPolicy.resolve(
            Stage2PolishBackend.LIVE,
            aiApp = true,
            threeStepEnabled = true,
            savedSmartMode = false
        )
        assertTrue(forceLive.threeStep)
        assertEquals(Stage1Capture.SMART, forceLive.stage1)

        val forceLiveChat = ThreeStepPolicy.resolve(
            Stage2PolishBackend.LIVE,
            aiApp = false,
            threeStepEnabled = true,
            savedSmartMode = false
        )
        assertEquals(SectionPolishMode.TWO_STEP_LIVE, forceLiveChat.mode)
        assertEquals(Stage1Capture.VERBATIM, forceLiveChat.stage1)
        assertFalse(forceLiveChat.threeStep)
    }

    @Test
    fun `a locked plan does not change when the toggle does`() {
        val locked = ThreeStepPolicy.resolve(Stage2PolishBackend.AUTO, true, true, false)
        val next = ThreeStepPolicy.resolve(Stage2PolishBackend.AUTO, true, false, false)
        assertTrue(locked.threeStep)
        assertFalse(next.threeStep)
        assertEquals(Stage1Capture.SMART, locked.stage1)
    }

    @Test
    fun `three-step uses the two live models and does not call flash`() {
        assertEquals("models/gemini-3.5-transcribe-live", GeminiLiveWebSocketClient.DEFAULT_MODEL)
        assertEquals("models/gemini-3.8-live", RapchaConfig.STAGE2_LIVE_MODEL)
        assertFalse(RapchaConfig.GEMINI_MODEL_FALLBACKS.any { it.contains("3.8-live") })
        assertFalse(Stage2PolishRoute.usesUnaryFallback(true))
    }

    @Test
    fun `lyra and chef prompts are unchanged`() {
        val chef = PolishPromptBuilder.CHEF_GOLDEN_RULES
        val lyra = LyraPolishInstruction.forKeyboard("App")
        assertEquals("e859edadd4296033646a9a932bbbff5508abb7e3db336f4e1af25829ce6b85ae", sha256(chef))
        assertTrue(lyra.contains(chef))
        assertTrue(lyra.contains(LyraPolishInstruction.NEVER_ANSWER_RULE))
        assertFalse(lyra.contains(LyraPolishInstruction.WELCOME_MESSAGE))
        assertEquals("de423dabf487b00344e2b2c85b83c1ecb2c4b2679cc8ada23860e7506f63b8e7", sha256(lyra))
    }

    @Test
    fun `delivery stops are permanent and suspicious pieces keep the raw text`() {
        assertEquals(
            DeliveryHalt.HOME,
            PolishDelivery.halt("com.anthropic.claude", "com.google.android.apps.nexuslauncher", false, false, true)
        )
        assertEquals(
            DeliveryHalt.TARGET_LEFT,
            PolishDelivery.halt("com.anthropic.claude", "com.whatsapp", false, false, false)
        )
        assertEquals(
            DeliveryHalt.CANCELLED,
            PolishDelivery.halt("com.anthropic.claude", "com.whatsapp", false, true, false)
        )
        assertEquals(
            DeliveryHalt.SENSITIVE,
            PolishDelivery.halt("com.anthropic.claude", "com.bank", true, false, false)
        )
        assertFalse(PolishDelivery.mayCopyFinal(DeliveryHalt.CANCELLED, alreadyCopied = false))
        assertFalse(PolishDelivery.mayCopyFinal(DeliveryHalt.SENSITIVE, alreadyCopied = false))
        assertTrue(PolishDelivery.mayCopyFinal(DeliveryHalt.TARGET_LEFT, alreadyCopied = false))
        assertFalse(PolishDelivery.mayCopyFinal(DeliveryHalt.TARGET_LEFT, alreadyCopied = true))
        val session = DeliverySession(1L, "com.anthropic.claude")
        session.note(DeliveryHalt.HOME)
        session.note(DeliveryHalt.TARGET_LEFT)
        assertEquals(DeliveryHalt.HOME, session.halt)
        assertFalse(session.acceptsInsert())
        assertTrue(PolishDelivery.suspiciousPiece(1293, 19))
        assertTrue(PolishDelivery.suspiciousPiece(1681, 20))
        assertFalse(PolishDelivery.suspiciousPiece(1348, 500))
        assertFalse(PolishDelivery.suspiciousPiece(40, 19))
        assertFalse(PolishDelivery.readableField("android.webkit.WebView"))
        assertTrue(PolishDelivery.readableField("android.widget.EditText"))
    }

    @Test
    fun `a rescue does not schedule another rescue during the cooldown`() {
        val planner = DictationPiecePlanner()
        planner.onConnectionOpened(0L)
        planner.onPieceStarted(0L)
        val speech = InterimPreviewMonitor.speechPcm(sampleCount = 16_000)
        var split: DictationPiecePlanner.Split? = null
        repeat(6) { index ->
            split = planner.onAudio(speech, index * 1_000L)
        }
        assertEquals(DictationPiecePlanner.Reason.RESCUE, split?.reason)
        planner.onPieceStarted(6_000L)
        var again: DictationPiecePlanner.Split? = null
        repeat(8) { index ->
            again = planner.onAudio(speech, 7_000L + index * 1_000L)
        }
        assertNull(again)
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

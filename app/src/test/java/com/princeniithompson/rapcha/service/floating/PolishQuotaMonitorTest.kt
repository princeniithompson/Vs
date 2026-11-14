package com.princeniithompson.rapcha.service.floating

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PolishQuotaMonitorTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PolishQuotaMonitor.resetForTest(app)
    }

    @Test
    fun `14 of 15 rpm pauses three-step into cooldown and auto-reactivates after 60s window`() {
        var now = 1_700_000_000_000L
        repeat(14) { index ->
            val status = PolishQuotaMonitor.tryAcquire(app, estimatedTokens = 200, nowMs = now + index * 1_000L)
            assertTrue("call $index should succeed", status.available)
        }

        // At 14/15 RPM in the rolling minute, the guard proactively trips cooldown
        val blocked = PolishQuotaMonitor.status(app, nowMs = now + 15_000L)
        assertFalse(blocked.available)
        assertEquals(PolishQuotaMonitor.LimitReason.RPM_LIMIT, blocked.reason)
        assertEquals(14, blocked.rpmUsed)

        // ThreeStepPolicy turns off Three-Step and falls back to 1-step Gemini 3.5 Smart
        val fallbackPlan = ThreeStepPolicy.resolve(
            override = Stage2PolishBackend.AUTO,
            aiApp = true,
            threeStepEnabled = true,
            savedSmartMode = false,
            quotaAvailable = blocked.available
        )
        assertFalse(fallbackPlan.threeStep)
        assertEquals(SectionPolishMode.SMART, fallbackPlan.mode)
        assertEquals(Stage1Capture.SMART, fallbackPlan.stage1)

        // Once the rolling 60s window expires, Three-Step auto-reactivates without user action
        now += PolishQuotaMonitor.WINDOW_MS + 2_000L
        val recovered = PolishQuotaMonitor.status(app, nowMs = now)
        assertTrue(recovered.available)
        assertEquals(PolishQuotaMonitor.LimitReason.NONE, recovered.reason)
        val restoredPlan = ThreeStepPolicy.resolve(
            override = Stage2PolishBackend.AUTO,
            aiApp = true,
            threeStepEnabled = true,
            savedSmartMode = false,
            quotaAvailable = recovered.available
        )
        assertTrue(restoredPlan.threeStep)
        assertEquals(SectionPolishMode.TWO_STEP_LIVE, restoredPlan.mode)
    }

    @Test
    fun `250k tpm threshold triggers cooldown and clears after rolling minute`() {
        val t0 = 1_700_000_000_000L
        val first = PolishQuotaMonitor.tryAcquire(app, estimatedTokens = 150_000, nowMs = t0)
        assertTrue(first.available)
        PolishQuotaMonitor.recordActualTokens(app, actualTotalTokens = 240_000, nowMs = t0 + 500L)

        val blocked = PolishQuotaMonitor.status(app, nowMs = t0 + 1_000L)
        assertFalse(blocked.available)
        assertEquals(PolishQuotaMonitor.LimitReason.TPM_LIMIT, blocked.reason)

        val cleared = PolishQuotaMonitor.status(app, nowMs = t0 + PolishQuotaMonitor.WINDOW_MS + 2_000L)
        assertTrue(cleared.available)
        assertEquals(0, cleared.tpmUsed)
    }

    @Test
    fun `daily quota cooldown lasts until pacific midnight and resets next day`() {
        // 2026-11-12 23:55:00 PST
        val beforeMidnight = 1_794_556_500_000L
        PolishQuotaMonitor.recordRateLimitError(
            context = app,
            isDailyQuota = true,
            nowMs = beforeMidnight
        )
        val blocked = PolishQuotaMonitor.status(app, nowMs = beforeMidnight + 30_000L)
        assertFalse(blocked.available)
        assertEquals(PolishQuotaMonitor.LimitReason.RPD_LIMIT, blocked.reason)
        assertEquals(PolishQuotaMonitor.MAX_RPD, blocked.rpdUsed)

        // Advance 10 minutes past midnight PST -> next day automatically reactivates
        val afterMidnight = beforeMidnight + 10 * 60_000L
        val reset = PolishQuotaMonitor.status(app, nowMs = afterMidnight)
        assertTrue(reset.available)
        assertEquals(0, reset.rpdUsed)
    }

    @Test
    fun `reconcileFinalTranscript keeps multi-piece ending when post runnable trails resume`() {
        val accumulated = "Piece one finished. Piece two finished. Piece three finished."
        val endpointFinal = "Piece one finished. Piece two finished. Piece three finished. Final piece four sentence."
        val reconciled = FloatingDictationSessionManager.reconcileFinalTranscript(accumulated, endpointFinal)
        assertEquals(endpointFinal, reconciled)
    }
}

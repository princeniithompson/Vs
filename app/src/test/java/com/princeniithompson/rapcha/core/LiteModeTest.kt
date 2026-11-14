package com.princeniithompson.rapcha.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.service.floating.RenderBudgetEngine
import com.princeniithompson.rapcha.service.floating.SectionPolishMode
import com.princeniithompson.rapcha.service.floating.Stage1Capture
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiteModeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // RapchaApplication.onCreate already decided for the test process; start each test clean.
    @Before
    fun clean() = LiteMode.resetForTest()

    @After
    fun reset() = LiteMode.resetForTest()

    @Test
    fun `auto follows the weak-device check and the override wins`() {
        assertTrue(LiteMode.resolve(LitePreference.AUTO, weakDevice = true))
        assertFalse(LiteMode.resolve(LitePreference.AUTO, weakDevice = false))
        assertTrue(LiteMode.resolve(LitePreference.ON, weakDevice = false))
        assertFalse(LiteMode.resolve(LitePreference.OFF, weakDevice = true))
        assertEquals(LitePreference.AUTO, LitePreference.fromStored(null))
        assertEquals(LitePreference.AUTO, LitePreference.fromStored("garbage"))
    }

    @Test
    fun `galaxy A15 and A17 class hardware counts as weak`() {
        val gb = 1024L * 1024L * 1024L
        // 4 GB / 6 GB variants qualify on RAM alone.
        assertTrue(RenderBudgetEngine.isWeakDevice(false, 4 * gb, null, 0))
        assertTrue(RenderBudgetEngine.isWeakDevice(false, 6 * gb, null, 0))
        // 8 GB variants qualify on the SoC (Helio G99, Dimensity 6100+, Exynos 1330) or CPU clock.
        assertTrue(RenderBudgetEngine.isWeakDevice(false, 8 * gb, "MT6789", 0))
        assertTrue(RenderBudgetEngine.isWeakDevice(false, 8 * gb, "MT6835", 0))
        assertTrue(RenderBudgetEngine.isWeakDevice(false, 8 * gb, "s5e8535", 0))
        assertTrue(RenderBudgetEngine.isWeakDevice(false, 8 * gb, null, 2_200_000))
        // A flagship (Pixel 7 class: 8 GB, Tensor G2, 2.85 GHz) stays on the full app.
        assertFalse(RenderBudgetEngine.isWeakDevice(false, 8 * gb, "GS201", 2_850_000))
    }

    @Test
    fun `stored override decides once per process`() {
        LiteMode.setPreference(context, LitePreference.ON)
        assertTrue(LiteMode.decide(context))
        // A later change is stored but does not flip the running process.
        LiteMode.setPreference(context, LitePreference.OFF)
        assertTrue(LiteMode.decide(context))
        assertEquals(LitePreference.OFF, LiteMode.preference(context))
    }

    @Test
    fun `lite sessions are one-step smart only`() {
        val plan = LiteMode.smartOnlyPlan()
        assertEquals(SectionPolishMode.SMART, plan.mode)
        assertEquals(Stage1Capture.SMART, plan.stage1)
        assertFalse(plan.threeStep)
    }

    @Test
    fun `lite keeps only errors and crashes in the log`() {
        assertTrue(AppLogRepository.keepsEvent(lite = false, type = DiagnosticType.BUBBLE_TAP))
        assertFalse(AppLogRepository.keepsEvent(lite = true, type = DiagnosticType.BUBBLE_TAP))
        assertFalse(AppLogRepository.keepsEvent(lite = true, type = DiagnosticType.SESSION_START))
        assertTrue(AppLogRepository.keepsEvent(lite = true, type = DiagnosticType.ERROR))
        assertTrue(AppLogRepository.keepsEvent(lite = true, type = DiagnosticType.CRASH))

        LiteMode.setForTest(true)
        val before = AppLogRepository.diagnosticEntries.value.size
        AppLogRepository.logEvent(DiagnosticSource.BUBBLE, DiagnosticType.BUBBLE_TAP, "lite_test_tap")
        assertEquals(before, AppLogRepository.diagnosticEntries.value.size)
        AppLogRepository.logEvent(DiagnosticSource.BUBBLE, DiagnosticType.ERROR, "lite_test_error")
        assertEquals("lite_test_error", AppLogRepository.diagnosticEntries.value.first().message)
    }

    @Test
    fun `lite turns haptics off`() {
        LiteMode.setForTest(true)
        val vibrator = context.getSystemService(android.os.Vibrator::class.java)
        com.princeniithompson.rapcha.service.floating.FloatingHapticManager.trigger(
            context,
            com.princeniithompson.rapcha.service.floating.FloatingHapticType.TRANSCRIPTION_START
        )
        assertFalse(org.robolectric.Shadows.shadowOf(vibrator).isVibrating)
        // Control: the same call vibrates when Lite is off.
        LiteMode.setForTest(false)
        com.princeniithompson.rapcha.service.floating.FloatingHapticManager.trigger(
            context,
            com.princeniithompson.rapcha.service.floating.FloatingHapticType.TRANSCRIPTION_START
        )
        assertTrue(org.robolectric.Shadows.shadowOf(vibrator).isVibrating)
    }
}

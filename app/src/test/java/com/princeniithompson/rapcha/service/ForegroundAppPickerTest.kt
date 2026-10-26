package com.princeniithompson.rapcha.service

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
class ForegroundAppPickerTest {

    private fun sensitive(pkg: String) = SafeModeClassifier.isSensitiveApp(null, pkg)

    private fun ignored(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("inputmethod") ||
            lower.startsWith("com.princeniithompson.rapcha") ||
            lower == "com.android.systemui"
    }

    @Test
    fun `a banking window stays in front of voiceflow and the launcher`() {
        val windows = listOf(
            ForegroundAppPicker.AppWindow("com.omkar.voiceflow", active = true, focused = true),
            ForegroundAppPicker.AppWindow("com.google.android.apps.nexuslauncher", active = true),
            ForegroundAppPicker.AppWindow("com.sportybet.android.gp.gh"),
            ForegroundAppPicker.AppWindow("com.google.android.inputmethod.latin", active = true, focused = true),
            ForegroundAppPicker.AppWindow("com.princeniithompson.rapcha", active = true)
        )
        assertEquals(
            "com.sportybet.android.gp.gh",
            ForegroundAppPicker.pick(windows, ::ignored, ::sensitive)
        )
        assertEquals(
            "com.sportybet.android.gp.gh",
            ForegroundAppPicker.resolve(
                windows = windows,
                eventPackage = "com.omkar.voiceflow",
                heldPackage = "com.sportybet.android.gp.gh",
                ignored = ::ignored,
                packageSensitive = ::sensitive
            )
        )
    }

    @Test
    fun `mtn and gcb win before safe mode is already held`() {
        val mtn = listOf(
            ForegroundAppPicker.AppWindow("com.omkar.voiceflow", active = true, focused = true),
            ForegroundAppPicker.AppWindow("com.mtngh.mymtn")
        )
        assertEquals(
            "com.mtngh.mymtn",
            ForegroundAppPicker.pick(mtn, ::ignored, ::sensitive)
        )
        val gcb = listOf(
            ForegroundAppPicker.AppWindow("com.google.android.apps.nexuslauncher", active = true),
            ForegroundAppPicker.AppWindow("com.modefin.gcbui", focused = true)
        )
        assertEquals("com.modefin.gcbui", ForegroundAppPicker.pick(gcb, ::ignored, ::sensitive))
    }

    @Test
    fun `the launcher is foreground only when it is the only app window`() {
        val home = listOf(
            ForegroundAppPicker.AppWindow("com.google.android.apps.nexuslauncher", active = true, focused = true)
        )
        assertEquals(
            "com.google.android.apps.nexuslauncher",
            ForegroundAppPicker.resolve(
                windows = home,
                eventPackage = "com.google.android.apps.nexuslauncher",
                heldPackage = "com.mtngh.mymtn",
                ignored = ::ignored,
                packageSensitive = ::sensitive
            )
        )
    }

    @Test
    fun `a voiceflow event with no windows does not clear a held bank`() {
        assertNull(
            ForegroundAppPicker.resolve(
                windows = emptyList(),
                eventPackage = "com.omkar.voiceflow",
                heldPackage = "com.sportybet.android.gp.gh",
                ignored = ::ignored,
                packageSensitive = ::sensitive
            )
        )
    }

    @Test
    fun `leaving the bank for grok is allowed once the bank window is gone`() {
        val grok = listOf(ForegroundAppPicker.AppWindow("ai.x.grok", active = true, focused = true))
        assertEquals(
            "ai.x.grok",
            ForegroundAppPicker.resolve(
                windows = grok,
                eventPackage = "ai.x.grok",
                heldPackage = "com.mtngh.mymtn",
                ignored = ::ignored,
                packageSensitive = ::sensitive
            )
        )
    }

    @Test
    fun `an enabled ime package is ignored even when its name is not an ime`() {
        val imes = EnabledImePackages.parse(
            listOf(
                "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME",
                "com.omkar.voiceflow/.ime.Voice"
            ),
            "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
        )
        assertTrue(imes.contains("com.google.android.inputmethod.latin"))
        assertTrue(imes.contains("com.omkar.voiceflow"))
        val windows = listOf(
            ForegroundAppPicker.AppWindow("com.omkar.voiceflow", active = true, focused = true),
            ForegroundAppPicker.AppWindow("com.modefin.gcbui")
        )
        assertEquals(
            "com.modefin.gcbui",
            ForegroundAppPicker.pick(windows, { imes.contains(it.lowercase()) || ignored(it) }, ::sensitive)
        )
    }

    @Test
    fun `a cutoff copies and a normal complete with a field injects`() {
        assertEquals(
            SessionPlacement.Path.CLIPBOARD,
            SessionPlacement.forFinishedSession(fieldFocused = true, cutOffBeforeComplete = true)
        )
        assertEquals(
            SessionPlacement.Path.CLIPBOARD,
            SessionPlacement.forFinishedSession(fieldFocused = false, cutOffBeforeComplete = false)
        )
        assertEquals(
            SessionPlacement.Path.INJECT,
            SessionPlacement.forFinishedSession(fieldFocused = true, cutOffBeforeComplete = false)
        )
        val bank = SafeModeClassifier.classify(packageName = "com.sportybet.android.gp.gh")
        assertTrue(SessionPlacement.holdsImmediately(bank))
        val password = SafeModeClassifier.classify(
            packageName = "com.android.settings",
            focused = SafeModeClassifier.FieldSignal(isPassword = true, editable = true, packageName = "com.android.settings")
        )
        assertTrue(password.sensitive)
        assertFalse(SessionPlacement.holdsImmediately(password))
    }
}

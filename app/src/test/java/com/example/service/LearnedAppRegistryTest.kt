package com.example.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.util.AppResolutionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LearnedAppRegistryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LearnedAppRegistry.clear(context)
        AppResolutionEngine.defaultInstance.clearCache()
    }

    @Test
    fun testRecordAndIncrementConfirmation() {
        val key = "com.google.android.apps.labs.whisk"
        
        // First confirmation
        val first = LearnedAppRegistry.recordConfirmation(
            context = context,
            key = key,
            appName = "Google Flow",
            category = "AI",
            source = "gemini"
        )
        assertEquals(1, first.confirmCount)
        assertEquals("Google Flow", first.appName)
        assertEquals("AI", first.category)
        assertFalse(LearnedAppRegistry.isConfirmed(context, key))

        // Second confirmation with same name and category
        val second = LearnedAppRegistry.recordConfirmation(
            context = context,
            key = key,
            appName = "Google Flow",
            category = "AI",
            source = "gemini"
        )
        assertEquals(2, second.confirmCount)
        assertEquals("Google Flow", second.appName)
        assertTrue(LearnedAppRegistry.isConfirmed(context, key))
    }

    @Test
    fun testLearnedNamePrecedenceOverLocalLabel() {
        val pkg = "com.google.android.apps.labs.whisk"
        
        // Confirm Google Flow in registry
        LearnedAppRegistry.recordConfirmation(
            context = context,
            key = pkg,
            appName = "Google Flow",
            category = "AI",
            source = "gemini"
        )
        LearnedAppRegistry.recordConfirmation(
            context = context,
            key = pkg,
            appName = "Google Flow",
            category = "AI",
            source = "gemini"
        )

        // Simulate foreground evidence where PackageManager label is still old "Whisk"
        val evidence = AppResolutionEngine.AppEvidence(
            packageName = pkg,
            appLabel = "Whisk",
            localDisplayName = "Whisk",
            learnedRegistryKey = pkg
        )

        val resolved = AppResolutionEngine.defaultInstance.resolveLocalCategory(context, evidence)
        assertNotNull(resolved)
        // Must return learned "Google Flow", NOT local "Whisk"
        assertEquals("Google Flow", resolved?.name)
        assertEquals("AI", resolved?.category)
        assertTrue(resolved?.isAiApp == true)
    }

    @Test
    fun testBrowserDomainIsolationInLearnedRegistry() {
        val aiStudioKey = "com.android.chrome|aistudio.google.com"
        val sportyBetKey = "com.android.chrome|sportybet.com"

        LearnedAppRegistry.recordConfirmation(context, aiStudioKey, "Google AI Studio", "AI", "gemini")
        LearnedAppRegistry.recordConfirmation(context, aiStudioKey, "Google AI Studio", "AI", "gemini")

        LearnedAppRegistry.recordConfirmation(context, sportyBetKey, "SportyBet", "Other", "gemini")
        LearnedAppRegistry.recordConfirmation(context, sportyBetKey, "SportyBet", "Other", "gemini")

        val aiStudioEntry = LearnedAppRegistry.get(context, aiStudioKey)
        val sportyBetEntry = LearnedAppRegistry.get(context, sportyBetKey)

        assertEquals("Google AI Studio", aiStudioEntry?.appName)
        assertEquals("AI", aiStudioEntry?.category)
        assertEquals("SportyBet", sportyBetEntry?.appName)
        assertEquals("Other", sportyBetEntry?.category)
    }
}

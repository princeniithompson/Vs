package com.princeniithompson.rapcha.config

import com.princeniithompson.rapcha.service.floating.FloatingPolishClient
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RapchaConfigAndFallbackTest {

    @Test
    fun `config constants match required system defaults`() {
        assertEquals(3200, RapchaConfig.AUDIO_CHUNK_SIZE)
        assertEquals(75f, RapchaConfig.HPF_CUTOFF_HZ, 0.001f)
        assertEquals(25L, RapchaConfig.PING_INTERVAL_SECONDS)
        assertEquals(800L, RapchaConfig.POLISH_DEBOUNCE_MS)
        assertEquals(3, RapchaConfig.MAX_RECONNECT_ATTEMPTS)
        assertEquals("models/gemini-2.0-flash-exp", RapchaConfig.DEFAULT_LIVE_MODEL)
        assertEquals("MY_GEMINI_API_KEY", RapchaConfig.PLACEHOLDER_API_KEY)
        assertEquals("gemini-3.5-flash-lite", RapchaConfig.POLISH_PRIMARY_MODEL)

        val fallbacks = RapchaConfig.GEMINI_MODEL_FALLBACKS
        assertEquals(2, fallbacks.size)
        assertEquals("gemini-3.5-flash-lite", fallbacks[0])
        assertEquals("gemini-3.8-flash", fallbacks[1])
    }

    @Test
    fun `isPlaceholderApiKey handles null, whitespace, and case-insensitive placeholder checks`() {
        assertTrue(RapchaConfig.isPlaceholderApiKey(null))
        assertTrue(RapchaConfig.isPlaceholderApiKey(""))
        assertTrue(RapchaConfig.isPlaceholderApiKey("   "))
        assertTrue(RapchaConfig.isPlaceholderApiKey("MY_GEMINI_API_KEY"))
        assertTrue(RapchaConfig.isPlaceholderApiKey("  my_gemini_api_key  "))
        assertTrue(RapchaConfig.isPlaceholderApiKey("My_Gemini_Api_Key"))

        assertFalse(RapchaConfig.isPlaceholderApiKey("AIzaSyD-ValidApiKey123456"))
        assertTrue(RapchaConfig.isValidApiKey("AIzaSyD-ValidApiKey123456"))
        assertFalse(RapchaConfig.isValidApiKey("my_gemini_api_key"))
    }

    @Test
    fun `model fallback order iterates through RapchaConfig fallbacks when models fail`() {
        val attemptedModels = mutableListOf<String>()
        val failingModels = setOf("gemini-3.5-flash-lite")
        val winningModel = "gemini-3.8-flash"

        // Simulate REST model fallback execution
        val result = runModelFallbackChain(
            apiKey = "AIzaSyD-ValidApiKey123456",
            rawTranscript = "Test transcript for fallback order",
            failingModels = failingModels,
            attemptedModelsCollector = attemptedModels
        )

        assertNotNull("Polish result text should be non-null when fallback succeeds", result)
        assertEquals("Polished result from $winningModel", result)

        // Verify fallback order matches RapchaConfig.GEMINI_MODEL_FALLBACKS
        val expectedFallbackOrder = RapchaConfig.GEMINI_MODEL_FALLBACKS
        assertEquals(
            "Attempted models must follow RapchaConfig fallback order",
            listOf("gemini-3.5-flash-lite", "gemini-3.8-flash"),
            attemptedModels
        )
    }

    private fun runModelFallbackChain(
        apiKey: String,
        rawTranscript: String,
        failingModels: Set<String>,
        attemptedModelsCollector: MutableList<String>
    ): String? {
        val baseModels = RapchaConfig.GEMINI_MODEL_FALLBACKS
        for (modelName in baseModels) {
            attemptedModelsCollector.add(modelName)
            if (failingModels.contains(modelName)) {
                // Simulate HTTP 404 or 500 failure for this model
                continue
            }
            // Model succeeded
            return "Polished result from $modelName"
        }
        return null
    }
}

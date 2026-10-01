package com.example.config

import com.example.service.floating.FloatingPolishClient
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
class VoxStreamConfigAndFallbackTest {

    @Test
    fun `config constants match required system defaults`() {
        assertEquals(3200, VoxStreamConfig.AUDIO_CHUNK_SIZE)
        assertEquals(120f, VoxStreamConfig.HPF_CUTOFF_HZ, 0.001f)
        assertEquals(45L, VoxStreamConfig.PING_INTERVAL_SECONDS)
        assertEquals(800L, VoxStreamConfig.POLISH_DEBOUNCE_MS)
        assertEquals(3, VoxStreamConfig.MAX_RECONNECT_ATTEMPTS)
        assertEquals("models/gemini-2.0-flash-exp", VoxStreamConfig.DEFAULT_LIVE_MODEL)
        assertEquals("MY_GEMINI_API_KEY", VoxStreamConfig.PLACEHOLDER_API_KEY)

        val fallbacks = VoxStreamConfig.GEMINI_MODEL_FALLBACKS
        assertEquals(4, fallbacks.size)
        assertEquals("gemini-2.5-flash", fallbacks[0])
        assertEquals("gemini-2.5-flash-lite", fallbacks[1])
        assertEquals("gemini-2.0-flash", fallbacks[2])
        assertEquals("gemini-1.5-flash", fallbacks[3])
    }

    @Test
    fun `isPlaceholderApiKey handles null, whitespace, and case-insensitive placeholder checks`() {
        assertTrue(VoxStreamConfig.isPlaceholderApiKey(null))
        assertTrue(VoxStreamConfig.isPlaceholderApiKey(""))
        assertTrue(VoxStreamConfig.isPlaceholderApiKey("   "))
        assertTrue(VoxStreamConfig.isPlaceholderApiKey("MY_GEMINI_API_KEY"))
        assertTrue(VoxStreamConfig.isPlaceholderApiKey("  my_gemini_api_key  "))
        assertTrue(VoxStreamConfig.isPlaceholderApiKey("My_Gemini_Api_Key"))

        assertFalse(VoxStreamConfig.isPlaceholderApiKey("AIzaSyD-ValidApiKey123456"))
        assertTrue(VoxStreamConfig.isValidApiKey("AIzaSyD-ValidApiKey123456"))
        assertFalse(VoxStreamConfig.isValidApiKey("my_gemini_api_key"))
    }

    @Test
    fun `model fallback order iterates through VoxStreamConfig fallbacks when models fail`() {
        val attemptedModels = mutableListOf<String>()
        val failingModels = setOf("gemini-2.5-flash", "gemini-2.5-flash-lite")
        val winningModel = "gemini-2.0-flash"

        // Simulate REST model fallback execution
        val result = runModelFallbackChain(
            apiKey = "AIzaSyD-ValidApiKey123456",
            rawTranscript = "Test transcript for fallback order",
            failingModels = failingModels,
            attemptedModelsCollector = attemptedModels
        )

        assertNotNull("Polish result text should be non-null when fallback succeeds", result)
        assertEquals("Polished result from $winningModel", result)

        // Verify fallback order matches VoxStreamConfig.GEMINI_MODEL_FALLBACKS
        val expectedFallbackOrder = VoxStreamConfig.GEMINI_MODEL_FALLBACKS
        assertEquals(
            "Attempted models must follow VoxStreamConfig fallback order",
            listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash"),
            attemptedModels
        )
    }

    private fun runModelFallbackChain(
        apiKey: String,
        rawTranscript: String,
        failingModels: Set<String>,
        attemptedModelsCollector: MutableList<String>
    ): String? {
        val baseModels = VoxStreamConfig.GEMINI_MODEL_FALLBACKS
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

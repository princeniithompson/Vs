package com.example.util

import com.example.util.AppResolutionEngine.AppEvidence
import com.example.util.AppResolutionEngine.ResolvedCategory
import com.example.util.AppResolutionEngine.ResolvedIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Automated regression test suite for AppResolutionEngine multi-signal evidence-based resolver.
 * Tests all 18 requirements specified in the architecture specification.
 */
class AppResolutionEngineTest {

    private lateinit var resolver: AppResolutionEngine

    @Before
    fun setUp() {
        resolver = AppResolutionEngine(100)
    }

    // 1. Gemini native package: com.google.android.apps.gemini -> AI · Gemini
    @Test
    fun testGeminiNativePackage() {
        val evidence = AppEvidence(packageName = "com.google.android.apps.gemini")
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("Gemini", result?.name)
        assertEquals("AI", result?.category)
        assertTrue(result?.isAiApp == true)
        assertEquals("AI · Gemini", result?.formatted)
        assertEquals(ResolvedIdentity.GEMINI, result?.identity)
    }

    // 2. Gemini legacy package: com.google.android.apps.bard -> AI · Gemini
    @Test
    fun testGeminiLegacyPackage() {
        val evidence = AppEvidence(packageName = "com.google.android.apps.bard")
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("Gemini", result?.name)
        assertEquals("AI", result?.category)
        assertTrue(result?.isAiApp == true)
        assertEquals("AI · Gemini", result?.formatted)
        assertEquals(ResolvedIdentity.GEMINI, result?.identity)
    }

    // 3. QuickSearchBox with explicit Gemini UI: package = com.google.android.googlequicksearchbox, visible text = "Ask Gemini" -> AI · Gemini
    @Test
    fun testQuickSearchBoxWithAskGeminiText() {
        val evidence = AppEvidence(
            packageName = "com.google.android.googlequicksearchbox",
            visibleNodeTexts = listOf("Ask Gemini", "Type or say something")
        )
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("Gemini", result?.name)
        assertEquals("AI", result?.category)
        assertTrue(result?.isAiApp == true)
        assertEquals("AI · Gemini", result?.formatted)
        assertEquals(ResolvedIdentity.GEMINI, result?.identity)
    }

    // 4. QuickSearchBox with Gemini title -> AI · Gemini
    @Test
    fun testQuickSearchBoxWithGeminiTitle() {
        val evidence = AppEvidence(
            packageName = "com.google.android.googlequicksearchbox",
            windowTitle = "Gemini"
        )
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("Gemini", result?.name)
        assertEquals("AI", result?.category)
        assertTrue(result?.isAiApp == true)
        assertEquals("AI · Gemini", result?.formatted)
        assertEquals(ResolvedIdentity.GEMINI, result?.identity)
    }

    // 5. QuickSearchBox normal Google Search UI -> NOT Gemini (Other · Google)
    @Test
    fun testQuickSearchBoxNormalGoogleSearch() {
        val evidence = AppEvidence(
            packageName = "com.google.android.googlequicksearchbox",
            windowTitle = "Google Search",
            visibleNodeTexts = listOf("Weather today", "Top stories", "Images", "Maps")
        )
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("Google", result?.name)
        assertEquals("Other", result?.category)
        assertFalse(result?.isAiApp == true)
        assertEquals("Other · Google", result?.formatted)
        assertEquals(ResolvedIdentity.GOOGLE_SEARCH, result?.identity)
    }

    // 6. QuickSearchBox with generic "Search" class only -> NOT automatically Gemini
    @Test
    fun testQuickSearchBoxGenericSearchClassOnly() {
        val evidence = AppEvidence(
            packageName = "com.google.android.googlequicksearchbox",
            className = "android.widget.SearchLayout",
            visibleNodeTexts = listOf("Search the web", "Trending searches")
        )
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("Google", result?.name)
        assertEquals("Other", result?.category)
        assertFalse(result?.isAiApp == true)
        assertEquals("Other · Google", result?.formatted)
    }

    // 7. Chrome + Google AI Studio title -> AI · Google AI Studio
    @Test
    fun testChromeGoogleAiStudioTitle() {
        val evidence = AppEvidence(
            packageName = "com.android.chrome",
            windowTitle = "Google AI Studio"
        )
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("Google AI Studio", result?.name)
        assertEquals("AI", result?.category)
        assertTrue(result?.isAiApp == true)
        assertEquals("AI · Google AI Studio", result?.formatted)
        assertEquals(ResolvedIdentity.GOOGLE_AI_STUDIO, result?.identity)
    }

    // 8. Chrome + AI Studio visible developer UI text -> AI · Google AI Studio
    @Test
    fun testChromeAiStudioVisibleText() {
        val evidence = AppEvidence(
            packageName = "com.android.chrome",
            visibleNodeTexts = listOf("System instructions", "Prompt gallery", "Get API key", "Run (Ctrl+Enter)")
        )
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("Google AI Studio", result?.name)
        assertEquals("AI", result?.category)
        assertTrue(result?.isAiApp == true)
        assertEquals("AI · Google AI Studio", result?.formatted)
        assertEquals(ResolvedIdentity.GOOGLE_AI_STUDIO, result?.identity)
    }

    // 9. Chrome + aistudio.google.com domain -> AI · Google AI Studio
    @Test
    fun testChromeAiStudioDomain() {
        val evidence = AppEvidence(
            packageName = "com.android.chrome",
            urlOrDomain = "aistudio.google.com/prompts/new_chat"
        )
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("Google AI Studio", result?.name)
        assertEquals("AI", result?.category)
        assertTrue(result?.isAiApp == true)
        assertEquals("AI · Google AI Studio", result?.formatted)
        assertEquals(ResolvedIdentity.GOOGLE_AI_STUDIO, result?.identity)
    }

    // 10. Chrome + ordinary webpage containing the words "AI Studio" -> NOT automatically Google AI Studio
    @Test
    fun testChromeOrdinaryWebpageWithAiStudioWords() {
        val evidence = AppEvidence(
            packageName = "com.android.chrome",
            windowTitle = "Tech News - Latest Updates",
            urlOrDomain = "technews.com/articles/123",
            visibleNodeTexts = listOf("This article discusses AI Studio features introduced by Google last week.")
        )
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("Chrome", result?.name)
        assertEquals("Other", result?.category)
        assertFalse(result?.isAiApp == true)
        assertEquals("Other · Chrome", result?.formatted)
        assertEquals(ResolvedIdentity.CHROME, result?.identity)
    }

    // 11. Chrome + ordinary webpage containing "API key" -> NOT automatically Google AI Studio
    @Test
    fun testChromeOrdinaryWebpageWithApiKeyWords() {
        val evidence = AppEvidence(
            packageName = "com.android.chrome",
            windowTitle = "Developer Blog",
            urlOrDomain = "devblog.org/security",
            visibleNodeTexts = listOf("How to safely store your API key in Android local properties.")
        )
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("Chrome", result?.name)
        assertEquals("Other", result?.category)
        assertFalse(result?.isAiApp == true)
        assertEquals("Other · Chrome", result?.formatted)
        assertEquals(ResolvedIdentity.CHROME, result?.identity)
    }

    // 12. Unknown application with strong AI conversational evidence -> AI category
    @Test
    fun testUnknownAppWithStrongAiEvidence() {
        val evidence = AppEvidence(
            packageName = "com.superai.assistant",
            appLabel = "SuperAI",
            visibleNodeTexts = listOf("New chat", "Ask anything", "Regenerate response", "Select model")
        )
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("AI Assistant", result?.name)
        assertEquals("AI", result?.category)
        assertTrue(result?.isAiApp == true)
        assertEquals("AI · AI Assistant", result?.formatted)
        assertEquals(ResolvedCategory.AI_ASSISTANT, result?.resolvedCategory)
    }

    // 13. Unknown application with insufficient evidence -> Other / Unknown
    @Test
    fun testUnknownAppWithInsufficientEvidence() {
        val evidence = AppEvidence(
            packageName = "com.simple.tools",
            appLabel = "Tools",
            visibleNodeTexts = listOf("This application uses an internal API for sync.")
        )
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("Tools", result?.name)
        assertEquals("Other", result?.category)
        assertFalse(result?.isAiApp == true)
        assertEquals("Other · Tools", result?.formatted)
        assertEquals(ResolvedIdentity.UNKNOWN, result?.identity)
    }

    // 14. Existing ChatGPT mapping remains correct
    @Test
    fun testChatGPTMappingPreserved() {
        val evidence = AppEvidence(packageName = "com.openai.chatgpt")
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("ChatGPT", result?.name)
        assertEquals("AI", result?.category)
        assertTrue(result?.isAiApp == true)
        assertEquals("AI · ChatGPT", result?.formatted)
        assertEquals(ResolvedIdentity.CHATGPT, result?.identity)
    }

    // 15. Existing Grok mapping remains correct
    @Test
    fun testGrokMappingPreserved() {
        val evidence = AppEvidence(packageName = "ai.x.grok")
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("Grok", result?.name)
        assertEquals("AI", result?.category)
        assertTrue(result?.isAiApp == true)
        assertEquals("AI · Grok", result?.formatted)
        assertEquals(ResolvedIdentity.GROK, result?.identity)
    }

    // 16. Existing WhatsApp mapping remains correct
    @Test
    fun testWhatsAppMappingPreserved() {
        val evidence = AppEvidence(packageName = "com.whatsapp")
        val result = resolver.resolveWithEvidence(evidence)

        assertNotNull(result)
        assertEquals("WhatsApp", result?.name)
        assertEquals("Social", result?.category)
        assertFalse(result?.isAiApp == true)
        assertEquals("Social · WhatsApp", result?.formatted)
        assertEquals(ResolvedIdentity.WHATSAPP, result?.identity)
    }

    // 17. Context/cache does not leak: Gemini -> normal Google resolves independently
    @Test
    fun testCacheSessionSeparationQuickSearchBox() {
        val geminiEvidence = AppEvidence(
            packageName = "com.google.android.googlequicksearchbox",
            visibleNodeTexts = listOf("Ask Gemini")
        )
        val geminiResult = resolver.resolveWithEvidence(geminiEvidence)
        assertEquals("AI · Gemini", geminiResult?.formatted)

        val googleEvidence = AppEvidence(
            packageName = "com.google.android.googlequicksearchbox",
            windowTitle = "Google Search",
            visibleNodeTexts = listOf("Weather today", "Top stories")
        )
        val googleResult = resolver.resolveWithEvidence(googleEvidence)
        assertEquals("Other · Google", googleResult?.formatted)

        assertNotEquals(geminiResult?.formatted, googleResult?.formatted)
    }

    // 18. Context/cache does not leak: Google AI Studio -> ordinary Chrome resolves independently
    @Test
    fun testCacheSessionSeparationChrome() {
        val aiStudioEvidence = AppEvidence(
            packageName = "com.android.chrome",
            windowTitle = "Google AI Studio",
            urlOrDomain = "aistudio.google.com"
        )
        val aiStudioResult = resolver.resolveWithEvidence(aiStudioEvidence)
        assertEquals("AI · Google AI Studio", aiStudioResult?.formatted)

        val normalChromeEvidence = AppEvidence(
            packageName = "com.android.chrome",
            windowTitle = "Wikipedia",
            urlOrDomain = "wikipedia.org"
        )
        val normalChromeResult = resolver.resolveWithEvidence(normalChromeEvidence)
        assertEquals("Other · Chrome", normalChromeResult?.formatted)

        assertNotEquals(aiStudioResult?.formatted, normalChromeResult?.formatted)
    }
}

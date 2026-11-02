package com.princeniithompson.rapcha.service.floating

import com.princeniithompson.rapcha.data.VocabSuggestionPolicy
import com.princeniithompson.rapcha.data.VocabTermFilter
import com.princeniithompson.rapcha.service.AppCategory
import com.princeniithompson.rapcha.service.AppClassifier
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
class HybridPolishPolicyTest {

    @Test
    fun `auto locks live for ai apps and smart for everyone else`() {
        assertEquals(SectionPolishMode.TWO_STEP_LIVE, mode("com.openai.chatgpt", "ChatGPT"))
        assertEquals(SectionPolishMode.TWO_STEP_LIVE, mode("com.google.android.apps.bard", "Gemini"))
        assertEquals(SectionPolishMode.TWO_STEP_LIVE, mode("com.anthropic.claude", "Claude"))
        assertEquals(SectionPolishMode.TWO_STEP_LIVE, mode("ai.x.grok", "Grok"))
        assertEquals(SectionPolishMode.TWO_STEP_LIVE, mode("com.deepseek.chat", "DeepSeek"))
        assertEquals(SectionPolishMode.TWO_STEP_LIVE, mode("com.alibaba.qwen", "Qwen"))
        assertEquals(SectionPolishMode.TWO_STEP_LIVE, mode("com.moonshot.kimichat", "Kimi"))

        assertEquals(SectionPolishMode.SMART, mode("com.whatsapp", "WhatsApp"))
        assertEquals(SectionPolishMode.SMART, mode("org.telegram.messenger", "Telegram"))
        assertEquals(SectionPolishMode.SMART, mode("com.instagram.android", "Instagram"))
        assertEquals(SectionPolishMode.SMART, mode("com.zhiliaoapp.musically", "TikTok"))
        assertEquals(SectionPolishMode.SMART, mode("com.android.chrome", "Chrome"))
        assertEquals(SectionPolishMode.SMART, mode("com.google.android.keep", "Keep"))
        assertEquals(SectionPolishMode.SMART, mode("com.google.android.apps.notes", "Notepad"))
        assertEquals(SectionPolishMode.SMART, mode("com.google.android.apps.nexuslauncher", "Launcher"))
        assertEquals(SectionPolishMode.SMART, mode(null, null))
    }

    @Test
    fun `force live and force smart override the category and do not rewrite a stored lock`() {
        val whatsApp = decide("com.whatsapp", "WhatsApp")
        assertFalse(whatsApp.second)
        assertEquals(
            SectionPolishMode.TWO_STEP_LIVE,
            HybridPolishPolicy.lock(Stage2PolishBackend.LIVE, whatsApp.second)
        )
        val chatGpt = decide("com.openai.chatgpt", "ChatGPT")
        assertTrue(chatGpt.second)
        assertEquals(
            SectionPolishMode.SMART,
            HybridPolishPolicy.lock(Stage2PolishBackend.NON_LIVE, chatGpt.second)
        )

        val locked = HybridPolishPolicy.lock(Stage2PolishBackend.AUTO, aiApp = true)
        assertEquals(SectionPolishMode.TWO_STEP_LIVE, locked)
        assertEquals(
            SectionPolishMode.SMART,
            HybridPolishPolicy.lock(Stage2PolishBackend.NON_LIVE, aiApp = true)
        )
        assertEquals(SectionPolishMode.TWO_STEP_LIVE, locked)

        assertEquals(Stage2Category.AI, HybridPolishPolicy.liveCategory(Stage2Category.OTHER, aiApp = true))
        assertEquals(Stage2Category.MESSAGE, HybridPolishPolicy.liveCategory(Stage2Category.MESSAGE, aiApp = false))
    }

    @Test
    fun `vocab filter drops list markers and keeps a real spelling`() {
        listOf("10.", "10", "1)", "10)", "1.", "#10", "10th", "1st", "...", "", "two-step", "two step", "two steps")
            .forEach { term ->
                assertFalse(term, VocabTermFilter.isUsable(term))
            }
        assertTrue(VocabTermFilter.isUsable("Niithompson"))
        assertTrue(VocabTermFilter.isUsable("30frst"))
        assertTrue(VocabTermFilter.PROMPT_RULE.contains("Never suggest numbers"))
        assertTrue(VocabTermFilter.PROMPT_RULE.contains("ordinals"))

        var junk = emptyMap<String, VocabSuggestionPolicy.SpellingRecord>()
        repeat(3) {
            val result = VocabSuggestionPolicy.observe("10. 10. 10.", junk, emptyList(), NOW)
            junk = result.records
            assertNull(result.suggestion)
        }

        var records = emptyMap<String, VocabSuggestionPolicy.SpellingRecord>()
        var suggestion: VocabSuggestionPolicy.Suggestion? = null
        repeat(3) {
            val result = VocabSuggestionPolicy.observe("Niithompson", records, emptyList(), NOW)
            records = result.records
            suggestion = result.suggestion
        }
        assertEquals("Niithompson", suggestion?.display)
    }

    private fun mode(packageName: String?, appName: String?): SectionPolishMode {
        val decision = decide(packageName, appName)
        return HybridPolishPolicy.lock(Stage2PolishBackend.AUTO, decision.second)
    }

    private fun decide(packageName: String?, appName: String?): Pair<Stage2Category, Boolean> {
        val stage2 = Stage2CategoryMapper.map(packageName, appName)
        val polish = AppClassifier.classify(packageName, appName)
        return stage2 to HybridPolishPolicy.isAiTarget(stage2, polish)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

package com.princeniithompson.rapcha.service.floating

import com.princeniithompson.rapcha.data.VocabSuggestionPolicy
import com.princeniithompson.rapcha.service.AppCategory
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
class LiveTwoStepFollowUpTest {

    @Test
    fun `live two-step shows cancel and polish only and the label is polish`() {
        val on = BubbleActionPlan.resolve(
            liveTwoStep = true,
            polishing = false,
            isAiApp = true,
            optimizePrompt = true
        )
        assertEquals(listOf(BubbleAction.CANCEL, BubbleAction.POLISH), on.actions)
        assertFalse(on.showsComplete)
        assertEquals("Polish", on.polishLabel)
        assertTrue(on.polishIsPrimary)
        assertFalse(on.polishLabel.equals("Publish", ignoreCase = true))

        val busy = BubbleActionPlan.resolve(
            liveTwoStep = true,
            polishing = true,
            isAiApp = false,
            optimizePrompt = false
        )
        assertEquals("Polishing...", busy.polishLabel)
        assertFalse(busy.showsComplete)
    }

    @Test
    fun `two-step off restores cancel polish and complete`() {
        val messaging = BubbleActionPlan.resolve(
            liveTwoStep = false,
            polishing = false,
            isAiApp = false,
            optimizePrompt = false
        )
        assertEquals(
            listOf(BubbleAction.CANCEL, BubbleAction.POLISH, BubbleAction.COMPLETE),
            messaging.actions
        )
        assertEquals("Polish", messaging.polishLabel)
        assertFalse(messaging.polishIsPrimary)

        val optimize = BubbleActionPlan.resolve(
            liveTwoStep = false,
            polishing = false,
            isAiApp = true,
            optimizePrompt = true
        )
        assertTrue(optimize.showsComplete)
        assertEquals("Optimize", optimize.polishLabel)

        val clean = BubbleActionPlan.resolve(
            liveTwoStep = false,
            polishing = false,
            isAiApp = true,
            optimizePrompt = false
        )
        assertEquals("Clean", clean.polishLabel)
    }

    @Test
    fun `ai polish uses lyra and never answers or welcomes`() {
        val instruction = PolishPromptBuilder.buildSystemInstruction(AppCategory.AI_CHAT, "Grok Bot")
        assertTrue("missing Lyra", instruction.contains("Lyra"))
        assertTrue("missing DECONSTRUCT", instruction.contains("DECONSTRUCT"))
        assertTrue("missing never-answer rule", instruction.contains(LyraPolishInstruction.NEVER_ANSWER_RULE))
        assertTrue("missing prompt-only output rule", instruction.contains("Output ONLY the polished prompt text"))
        assertTrue("missing app name", instruction.contains("Grok Bot"))
        assertFalse("welcome leaked into the keyboard instruction", instruction.contains(LyraPolishInstruction.WELCOME_MESSAGE))
        assertFalse(instruction.contains("What Changed"))
        assertFalse(instruction.contains("Your Optimized Prompt"))
    }

    @Test
    fun `message work and other polish do not use lyra`() {
        val message = PolishPromptBuilder.buildSystemInstruction(AppCategory.MESSAGING, "WhatsApp")
        val work = PolishPromptBuilder.buildSystemInstruction(AppCategory.NOTES_DOCS, "Notion")
        val other = PolishPromptBuilder.buildSystemInstruction(AppCategory.OTHER, "App")
        for (instruction in listOf(message, work, other)) {
            assertFalse(instruction.contains("Lyra"))
            assertFalse(instruction.contains(LyraPolishInstruction.WELCOME_MESSAGE))
            assertTrue(instruction.contains("NEVER answer"))
        }
        assertTrue(message.contains("Direct Messaging"))
        assertTrue(work.contains("Notes & Documentation"))
        assertTrue(other.contains("General text input"))
    }

    @Test
    fun `two step collocations are never suggested for the dictionary`() {
        val phrases = listOf(
            "I thought the two-step is going to work",
            "I thought the two step is going to work",
            "two steps",
            "two-steps",
            "step-by-step"
        )
        for (phrase in phrases) {
            var records = emptyMap<String, VocabSuggestionPolicy.SpellingRecord>()
            var suggestion: VocabSuggestionPolicy.Suggestion? = null
            repeat(3) {
                val result = VocabSuggestionPolicy.observe(phrase, records, emptyList(), NOW)
                records = result.records
                suggestion = result.suggestion
            }
            val keys = records.keys.joinToString(" ")
            assertFalse(keys, keys.contains("two"))
            assertFalse(keys, keys.contains("step"))
            val display = suggestion?.display?.lowercase().orEmpty()
            assertFalse(display.contains("two"))
            assertFalse(display.contains("step"))
        }
    }

    @Test
    fun `a rare word beside two-step can still be suggested`() {
        var records = emptyMap<String, VocabSuggestionPolicy.SpellingRecord>()
        var suggestion: VocabSuggestionPolicy.Suggestion? = null
        repeat(3) {
            val result = VocabSuggestionPolicy.observe(
                "Zylophant two-step",
                records,
                emptyList(),
                NOW
            )
            records = result.records
            suggestion = result.suggestion
        }
        assertEquals("zylophant", suggestion?.key)
        assertFalse(records.containsKey("two-step"))
        assertNull(records["two"])
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

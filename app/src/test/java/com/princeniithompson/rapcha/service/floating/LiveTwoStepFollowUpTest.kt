package com.princeniithompson.rapcha.service.floating

import com.princeniithompson.rapcha.config.RapchaConfig
import com.princeniithompson.rapcha.data.DiagnosticType
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
    fun `recording chrome is cancel and complete for live and one-step`() {
        val on = BubbleActionPlan.resolve(
            liveTwoStep = true,
            polishing = false,
            isAiApp = true,
            optimizePrompt = true
        )
        assertEquals(listOf(BubbleAction.CANCEL, BubbleAction.COMPLETE), on.actions)
        assertTrue(on.showsComplete)
        assertFalse(on.actions.contains(BubbleAction.POLISH))
        assertTrue(CompleteActionRoute.usesLiveFinish(true))
        assertFalse(CompleteActionRoute.usesLiveFinish(false))

        val busy = BubbleActionPlan.resolve(
            liveTwoStep = true,
            polishing = true,
            isAiApp = false,
            optimizePrompt = false
        )
        assertEquals(listOf(BubbleAction.CANCEL, BubbleAction.COMPLETE), busy.actions)
    }

    @Test
    fun `toggle on selects gemini-3_8-live and not unary flash`() {
        assertTrue(Stage2PolishRoute.usesLiveModel(true))
        assertEquals("models/gemini-3.8-live", Stage2PolishRoute.model(true))
        assertFalse(Stage2PolishRoute.usesUnaryFallback(true))
        assertTrue(Stage2PolishRoute.usesUnaryFallback(false))
        assertEquals(12_000L, RapchaConfig.STAGE2_LIVE_SETUP_MS)
        assertEquals(6_000L, RapchaConfig.STAGE2_LIVE_TIMEOUT_MS)
        assertTrue(RapchaConfig.STAGE2_LIVE_HARD_CAP_MS < 15_000L)
        assertTrue(LivePolishOutcome.userLine("Stage-2 Live polish timed out before setup").contains("before setup"))
        assertFalse(LivePolishOutcome.userLine("Stage-2 Live polish timed out before setup").contains("busy"))
        assertTrue(LivePolishOutcome.userLine("HTTP 503 high demand UNAVAILABLE").contains("busy"))
        assertFalse(RapchaConfig.GEMINI_MODEL_FALLBACKS.any { it.contains("3.8-live") })
        assertFalse(Stage2PolishRoute.model(true)!!.contains("flash-lite"))
        assertFalse(Stage2PolishRoute.model(true)!!.contains("gemini-3.8-flash"))
        assertFalse(Stage2PolishRoute.usesLiveModel(false))
        assertEquals(null, Stage2PolishRoute.model(false))
    }

    @Test
    fun `late live text is kept and an empty timeout stays a failure`() {
        val kept = LivePolishOutcome.decide(
            finished = false,
            assembledText = "Send the note to Jordan.",
            socketError = null,
            protocolError = null,
            setupCompleted = true
        )
        assertEquals("Send the note to Jordan.", kept.text)
        assertFalse(kept.timedOut)

        val empty = LivePolishOutcome.decide(
            finished = false,
            assembledText = "",
            socketError = null,
            protocolError = null,
            setupCompleted = false
        )
        assertEquals(null, empty.text)
        assertTrue(empty.timedOut)
        assertTrue(empty.error!!.contains("before setup"))
    }

    @Test
    fun `live stage-2 ai system instruction is chef and lyra stays in the user turn`() {
        val ai = Stage2LivePrompt.systemInstruction(Stage2Category.AI, "Grok Bot", listOf("Niithompson"))
        assertEquals(PolishPromptBuilder.CHEF_GOLDEN_RULES, ai)
        assertFalse(ai.contains("Lyra"))
        val turn = Stage2LivePrompt.userTurn(
            Stage2Category.AI,
            "hello",
            listOf("Niithompson"),
            appName = "Grok Bot"
        )
        assertTrue(turn.contains("You are Lyra"))
        assertTrue(turn.contains(LyraPolishInstruction.NEVER_ANSWER_RULE))
        assertTrue(turn.contains("Niithompson"))
        assertFalse(turn.contains(LyraPolishInstruction.WELCOME_MESSAGE))

        val message = Stage2LivePrompt.systemInstruction(Stage2Category.MESSAGE, "WhatsApp", emptyList())
        assertFalse(message.contains("Lyra"))
        assertTrue(message.contains("MESSAGE"))
    }

    @Test
    fun `live failure can fall back without pretending the live model was skipped`() {
        val events = mutableListOf<DiagnosticType>()
        val paste = Stage2LivePolish.execute(
            rawTranscript = "raw words",
            category = Stage2Category.AI,
            protectedTerms = emptyList(),
            stage1Dropped = false,
            apiKey = "test-key",
            appName = "Grok Bot",
            model = { request ->
                assertEquals(PolishPromptBuilder.CHEF_GOLDEN_RULES, request.systemInstruction)
                assertFalse(request.systemInstruction.contains("Lyra"))
                assertTrue(request.userText.contains("You are Lyra"))
                assertFalse(request.systemInstruction.contains("gemini-3.5-flash-lite"))
                LivePolishResponse(null, "Stage-2 Live polish timed out before setup", timedOut = true)
            },
            log = { type, message ->
                events.add(type)
                assertTrue(message.contains("models/gemini-3.8-live"))
                assertFalse(message.contains("gemini-3.5-flash-lite"))
            }
        )
        assertEquals(Stage2Outcome.FALLBACK_RAW, paste.outcome)
        assertEquals(listOf(DiagnosticType.STAGE2_LIVE_START, DiagnosticType.STAGE2_LIVE_FAILED), events)
        assertEquals("raw words", Stage2LivePolish.rawOnFailure("raw words"))
        assertFalse(events.any { it == DiagnosticType.POLISH_CALLED })
    }

    @Test
    fun `one-step also shows only cancel and complete`() {
        val messaging = BubbleActionPlan.resolve(
            liveTwoStep = false,
            polishing = false,
            isAiApp = false,
            optimizePrompt = false
        )
        assertEquals(listOf(BubbleAction.CANCEL, BubbleAction.COMPLETE), messaging.actions)
        assertFalse(messaging.actions.contains(BubbleAction.POLISH))
        val optimize = BubbleActionPlan.resolve(
            liveTwoStep = false,
            polishing = false,
            isAiApp = true,
            optimizePrompt = true
        )
        assertEquals(listOf(BubbleAction.CANCEL, BubbleAction.COMPLETE), optimize.actions)
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

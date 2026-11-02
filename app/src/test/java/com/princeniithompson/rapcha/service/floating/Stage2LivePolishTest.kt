package com.princeniithompson.rapcha.service.floating

import android.app.Application
import android.content.Context
import com.princeniithompson.rapcha.data.DiagnosticType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage2LivePolishTest {

    @Test
    fun `category mapping follows the message work ai other table`() {
        assertEquals(Stage2Category.MESSAGE, Stage2CategoryMapper.map("com.whatsapp", "WhatsApp"))
        assertEquals(Stage2Category.MESSAGE, Stage2CategoryMapper.map("org.telegram.messenger", "Telegram"))
        assertEquals(Stage2Category.MESSAGE, Stage2CategoryMapper.map("org.thoughtcrime.securesms", "Signal"))
        assertEquals(Stage2Category.MESSAGE, Stage2CategoryMapper.map("com.discord", "Discord"))

        assertEquals(Stage2Category.WORK, Stage2CategoryMapper.map("com.google.android.gm", "Gmail"))
        assertEquals(Stage2Category.WORK, Stage2CategoryMapper.map("com.microsoft.office.outlook", "Outlook"))
        assertEquals(Stage2Category.WORK, Stage2CategoryMapper.map("com.Slack", "Slack"))
        assertEquals(Stage2Category.WORK, Stage2CategoryMapper.map("com.microsoft.teams", "Teams"))
        assertEquals(Stage2Category.WORK, Stage2CategoryMapper.map("com.microsoft.office.word", "Word"))
        assertEquals(Stage2Category.WORK, Stage2CategoryMapper.map("notion.id", "Notion"))

        assertEquals(Stage2Category.AI, Stage2CategoryMapper.map("com.openai.chatgpt", "ChatGPT"))
        assertEquals(Stage2Category.AI, Stage2CategoryMapper.map("com.anthropic.claude", "Claude"))
        assertEquals(Stage2Category.AI, Stage2CategoryMapper.map("com.google.android.apps.bard", "Gemini"))
        assertEquals(Stage2Category.AI, Stage2CategoryMapper.map("ai.x.grok", "Grok"))
        assertEquals(Stage2Category.AI, Stage2CategoryMapper.map("com.grokbot", "Grok Bot"))

        assertEquals(Stage2Category.OTHER, Stage2CategoryMapper.map("com.instagram.android", "Instagram"))
        assertEquals(Stage2Category.OTHER, Stage2CategoryMapper.map("com.android.documentsui", "Files"))
        assertEquals(Stage2Category.OTHER, Stage2CategoryMapper.map(null, null))
        assertEquals(
            Stage2Category.MESSAGE,
            Stage2CategoryMapper.map("com.android.chrome", "Chrome", "https://web.whatsapp.com/")
        )
        assertEquals(
            Stage2Category.WORK,
            Stage2CategoryMapper.map(null, null, "Detected: Slack -> Social")
        )
    }

    @Test
    fun `live two-step forces smart off and non-live keeps the saved smart setting`() {
        assertFalse(
            Stage2SessionPolicy.effectiveSmartMode(
                savedSmartMode = true,
                backend = Stage2PolishBackend.LIVE
            )
        )
        assertFalse(
            Stage2SessionPolicy.effectiveSmartMode(
                savedSmartMode = false,
                backend = Stage2PolishBackend.LIVE
            )
        )
        assertTrue(
            Stage2SessionPolicy.effectiveSmartMode(
                savedSmartMode = true,
                backend = Stage2PolishBackend.NON_LIVE
            )
        )
        assertFalse(
            Stage2SessionPolicy.effectiveSmartMode(
                savedSmartMode = false,
                backend = Stage2PolishBackend.NON_LIVE
            )
        )
    }

    @Test
    fun `stage-2 backend defaults to non-live`() {
        assertEquals(Stage2PolishBackend.NON_LIVE, Stage2PolishBackend.fromStored(null))
        assertEquals(Stage2PolishBackend.NON_LIVE, Stage2PolishBackend.fromStored("non_live"))
        assertEquals(Stage2PolishBackend.NON_LIVE, Stage2PolishBackend.fromStored("unexpected"))
        assertEquals(Stage2PolishBackend.LIVE, Stage2PolishBackend.fromStored("live"))

        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = context.getSharedPreferences("stage2_polish_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertEquals(Stage2PolishBackend.NON_LIVE, Stage2PolishSettings.read(prefs))
        Stage2PolishSettings.write(prefs, Stage2PolishBackend.LIVE)
        assertTrue(Stage2PolishSettings.isLive(prefs))
        Stage2PolishSettings.write(prefs, Stage2PolishBackend.NON_LIVE)
        assertFalse(Stage2PolishSettings.isLive(prefs))
    }

    @Test
    fun `stage-2 failure timeout and empty text paste the raw transcript`() {
        val raw = "äh hallo, ich wollte nur kurz sagen wir kommen später"
        val events = mutableListOf<DiagnosticType>()

        val failed = Stage2LivePolish.execute(
            rawTranscript = raw,
            category = Stage2Category.MESSAGE,
            protectedTerms = listOf("Niithompson"),
            stage1Dropped = false,
            apiKey = "test-key",
            model = { _ ->
                LivePolishResponse(text = null, error = "HTTP 500", timedOut = false)
            },
            log = { type, _ -> events.add(type) }
        )
        assertEquals(raw, failed.text)
        assertEquals(Stage2Outcome.FALLBACK_RAW, failed.outcome)
        assertEquals(
            listOf(
                DiagnosticType.STAGE2_LIVE_START,
                DiagnosticType.STAGE2_LIVE_FAILED
            ),
            events
        )
        assertFalse(events.contains(DiagnosticType.POLISH_CALLED))

        var stage2CalledAfterDrop = false
        val timedOut = Stage2LivePolish.execute(
            rawTranscript = raw,
            category = Stage2Category.WORK,
            protectedTerms = emptyList(),
            stage1Dropped = true,
            apiKey = "test-key",
            model = { _ ->
                stage2CalledAfterDrop = true
                LivePolishResponse(text = null, error = "Stage-2 Live polish timed out", timedOut = true)
            },
            log = { _, _ -> }
        )
        assertTrue(stage2CalledAfterDrop)
        assertEquals(raw, timedOut.text)
        assertTrue(timedOut.stage1Flushed)
        assertTrue(timedOut.timedOut)
        assertEquals(Stage2Outcome.FALLBACK_RAW, timedOut.outcome)

        val empty = Stage2LivePolish.execute(
            rawTranscript = raw,
            category = Stage2Category.AI,
            protectedTerms = emptyList(),
            stage1Dropped = false,
            apiKey = "test-key",
            model = { _ -> LivePolishResponse(text = "   ", error = null, timedOut = true) },
            log = { _, _ -> }
        )
        assertEquals(raw, empty.text)
        assertTrue(empty.timedOut)
    }

    @Test
    fun `stage-2 success returns the polished text and keeps a protected term in the request`() {
        var userText = ""
        val paste = Stage2LivePolish.execute(
            rawTranscript = "um tell Niithompson we are late",
            category = Stage2Category.MESSAGE,
            protectedTerms = listOf("Niithompson"),
            stage1Dropped = false,
            apiKey = "test-key",
            model = { request ->
                userText = request.userText
                assertTrue(request.systemInstruction.contains("Do not translate"))
                assertTrue(request.systemInstruction.contains("Niithompson"))
                LivePolishResponse("Tell Niithompson we are late.", null, timedOut = false)
            },
            log = { _, _ -> }
        )
        assertEquals("Tell Niithompson we are late.", paste.text)
        assertEquals(Stage2Outcome.SUCCESS, paste.outcome)
        assertTrue(userText.contains("[APP_CATEGORY]"))
        assertTrue(userText.contains("MESSAGE"))
        assertTrue(userText.contains("Niithompson"))
        assertTrue(userText.contains("um tell Niithompson we are late"))
    }

    @Test
    fun `live text setup targets gemini-3_8-live and reads model text until turn complete`() {
        val setup = JSONObject(
            LivePolishProtocol.setupMessage("keep the language", LivePolishProtocol.MODEL)
        ).getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        assertEquals("TEXT", setup.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertFalse(setup.has("inputAudioTranscription"))
        assertFalse(setup.toString().contains("realtimeInput"))

        val turn = LivePolishProtocol.clientContentMessage("[APP_CATEGORY]\nAI\n\n[RAW_TRANSCRIPT]\näh hallo")
        val clientContent = JSONObject(turn).getJSONObject("clientContent")
        assertTrue(clientContent.getBoolean("turnComplete"))
        assertTrue(clientContent.getJSONArray("turns").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text").contains("äh hallo"))

        val assembler = LivePolishTranscriptAssembler()
        assertEquals(
            LivePolishSignal.SETUP_COMPLETE,
            assembler.absorb("""{"setupComplete":{}}""")
        )
        assembler.absorb("""{"serverContent":{"modelTurn":{"parts":[{"text":"Äh "}]}}}""")
        assembler.absorb("""{"serverContent":{"modelTurn":{"parts":[{"thought":true,"text":"hidden"}]}}}""")
        assembler.absorb("""{"serverContent":{"modelTurn":{"parts":[{"text":"hallo."}]},"turnComplete":true}}""")
        assertTrue(assembler.turnComplete)
        assertEquals("Äh hallo.", assembler.text())
    }
}

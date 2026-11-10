package com.princeniithompson.rapcha.service.floating

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.princeniithompson.rapcha.data.CaptureMode
import com.princeniithompson.rapcha.data.HistoryRepository
import com.princeniithompson.rapcha.data.LyraRating
import com.princeniithompson.rapcha.data.LyraReviewExport
import com.princeniithompson.rapcha.data.LyraReviewPolicy
import com.princeniithompson.rapcha.data.LyraReviewTag
import com.princeniithompson.rapcha.data.SessionPipeline
import com.princeniithompson.rapcha.data.VoiceSessionBuilder
import org.json.JSONObject
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
class LyraReviewTest {

    @Test
    fun `only a finished Lyra session can be rated`() {
        val twoStep = session(pipeline = "two", lyra = "Paste this prompt")
        val threeStep = session(pipeline = "three", lyra = "Paste this prompt")
        assertTrue(twoStep.reviewable)
        assertTrue(threeStep.reviewable)
        assertEquals(LyraRating.UNRATED, twoStep.rating)
        assertTrue(LyraReviewPolicy.shouldPrompt(twoStep))

        val smart = session(pipeline = "smart", lyra = "Clean text")
        val fallback = session(pipeline = "two", lyra = null)
        val otherModel = session(pipeline = "two", lyra = "Edited", ai = false)
        val sensitive = session(pipeline = "two", lyra = "Paste this prompt", sensitive = true)
        assertFalse(smart.reviewable)
        assertFalse(fallback.reviewable)
        assertFalse(otherModel.reviewable)
        assertFalse(sensitive.reviewable)
        assertFalse(LyraReviewPolicy.shouldPrompt(smart))
        assertFalse(LyraReviewPolicy.shouldPrompt(fallback))
        assertFalse(LyraReviewPolicy.commentRequired(LyraRating.DOWN))
    }

    @Test
    fun `one session keeps frozen raw, completed smart, and lyra as different texts`() {
        val raw = "I need seven crates of eggs. Actually, reduce it to three crates"
        val smart = "I need three crates of eggs."
        val lyra = "Buy three crates of eggs."
        val three = session(pipeline = "three", raw = raw, smart = smart, lyra = lyra)
        assertEquals(listOf("raw", "smart", "lyra"), three.stages.map { it.role })
        assertEquals(raw, three.stage("raw"))
        assertEquals(smart, three.stage("smart"))
        assertEquals(lyra, three.stage("lyra"))
        assertTrue(three.rawAvailable)
        assertFalse(three.rawNote.orEmpty().contains("not provided"))
        val updated = session(pipeline = "three", raw = three.stage("raw")!!, smart = "Formatted later", lyra = lyra)
        assertEquals(raw, updated.stage("raw"))
        assertEquals("Formatted later", updated.stage("smart"))

        val two = session(pipeline = "two", raw = "um buy milk", lyra = "Buy milk")
        assertEquals(listOf("raw", "lyra"), two.stages.map { it.role })
        assertNull(two.stage("smart"))

        val timedOut = session(pipeline = "three", raw = raw, smartCompleted = false, lyra = null)
        assertEquals(listOf("raw"), timedOut.stages.map { it.role })
        assertFalse(timedOut.reviewable)
        assertEquals(LyraReviewPolicy.SMART_INCOMPLETE_NOTE, timedOut.rawNote)
    }

    @Test
    fun `a down rating keeps tags and an empty comment and an ignored session stays unrated`() {
        val original = session(pipeline = "two", lyra = "Buy milk")
        assertEquals(LyraRating.UNRATED, original.rating)
        val down = LyraReviewPolicy.apply(
            original,
            LyraRating.DOWN,
            tags = listOf(LyraReviewTag.OTHER, LyraReviewTag.REMOVED_WORDS),
            comment = ""
        )
        assertEquals(LyraRating.DOWN, down.rating)
        assertEquals(listOf(LyraReviewTag.REMOVED_WORDS, LyraReviewTag.OTHER), down.tags)
        assertEquals("", down.comment)
        val up = LyraReviewPolicy.apply(down, LyraRating.UP, comment = "kept the name")
        assertEquals(LyraRating.UP, up.rating)
        assertTrue(up.tags.isEmpty())
        assertEquals("kept the name", up.comment)
        val smart = session(pipeline = "smart", lyra = "Hey")
        assertEquals(smart, LyraReviewPolicy.apply(smart, LyraRating.DOWN))
    }

    @Test
    fun `export lists counts and one json line with the texts`() {
        val up = LyraReviewPolicy.apply(session(pipeline = "two", id = "up", lyra = "Buy milk"), LyraRating.UP)
        val down = LyraReviewPolicy.apply(
            session(
                pipeline = "three",
                id = "down",
                raw = "um seven crates actually three",
                smart = "Three crates.",
                lyra = "Buy milk"
            ),
            LyraRating.DOWN,
            tags = listOf(LyraReviewTag.REMOVED_WORDS),
            comment = "dropped eggs"
        )
        val unrated = session(pipeline = "two", id = "later", lyra = "Still waiting")
        val smart = session(pipeline = "smart", id = "wa", lyra = "Hey")
        val fallback = session(pipeline = "two", id = "raw", lyra = null)
        val sensitive = session(pipeline = "three", id = "secret", lyra = "hidden", sensitive = true)
        val export = LyraReviewExport.section(listOf(smart, fallback, sensitive, unrated, down, up))
        assertTrue(export.contains("LYRA_REVIEW"))
        assertTrue(export.contains("up=1 down=1 unrated=1"))
        assertFalse(export.contains("Hey"))
        assertFalse(export.contains("hidden"))
        assertFalse(export.contains("session_id: raw"))
        val jsonLines = export.lines().filter { it.startsWith("JSON ") }
        assertEquals(3, jsonLines.size)
        val parsed = JSONObject(jsonLines.first { it.contains("\"down\"") }.removePrefix("JSON "))
        assertEquals("down", parsed.getString("session_id"))
        assertEquals("three-step", parsed.getString("mode"))
        assertEquals("down", parsed.getString("rating"))
        assertEquals("um seven crates actually three", parsed.getString("raw"))
        assertEquals("Three crates.", parsed.getString("smart"))
        assertEquals("Buy milk", parsed.getString("lyra"))
        assertEquals("raw", parsed.getString("compare_from"))
        assertEquals("dropped eggs", parsed.getString("comment"))
        assertEquals("removed_words", parsed.getJSONArray("tags").getString(0))
        val two = JSONObject(jsonLines.first { it.contains("\"up\"") }.removePrefix("JSON "))
        assertEquals("um buy milk and eggs", two.getString("raw"))
        assertEquals("raw", two.getString("compare_from"))
    }

    @Test
    fun `a saved session reloads as one row and accepts a later rating`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        HistoryRepository.clearForTest()
        HistoryRepository.init(context)
        HistoryRepository.clearHistory()
        val record = session(pipeline = "two", lyra = "Buy milk")
        HistoryRepository.saveVoiceSession(record)
        assertEquals(1, HistoryRepository.historyItems.value.size)
        HistoryRepository.clearForTest()
        HistoryRepository.init(context)
        val loaded = HistoryRepository.historyItems.value.single()
        assertEquals("s1", loaded.session?.sessionId)
        assertEquals("um buy milk and eggs", loaded.session?.stage("raw"))
        assertEquals("Buy milk", loaded.session?.stage("lyra"))
        val rated = HistoryRepository.updateLyraReview("s1", LyraRating.DOWN, comment = "")
        assertEquals(LyraRating.DOWN, rated?.rating)
        assertEquals("", rated?.comment)
        assertEquals(1, HistoryRepository.lyraReviewSessions().size)
        HistoryRepository.clearHistory()
    }

    private fun session(
        pipeline: String,
        id: String = "s1",
        raw: String = "um buy milk and eggs",
        smart: String? = null,
        lyra: String? = null,
        ai: Boolean = true,
        sensitive: Boolean = false,
        smartCompleted: Boolean = true
    ): com.princeniithompson.rapcha.data.VoiceSessionRecord {
        if (pipeline == "smart") {
            return VoiceSessionBuilder.build(
                sessionId = id,
                timestamp = 1_700_000_000_000L,
                appName = "WhatsApp",
                stage1 = CaptureMode.VERBATIM,
                threeStep = false,
                usedLiveModel = false,
                aiLyra = false,
                stage1Text = raw,
                deliveredText = lyra ?: raw,
                lyraText = null,
                unaryPolishText = lyra,
                sensitive = sensitive
            )
        }
        return VoiceSessionBuilder.captured(
            sessionId = id,
            timestamp = 1_700_000_000_000L,
            appName = "Claude",
            threeStep = pipeline == "three",
            rawText = raw,
            smartText = if (pipeline == "three") smart ?: "Buy milk and eggs." else null,
            lyraText = lyra,
            smartCompleted = smartCompleted,
            aiLyra = ai,
            sensitive = sensitive
        )
    }
}

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
        val threeStep = session(pipeline = "three", lyra = "Paste this prompt", stage1 = CaptureMode.SMART)
        assertTrue(twoStep.reviewable)
        assertTrue(threeStep.reviewable)
        assertEquals(LyraRating.UNRATED, twoStep.rating)
        assertTrue(LyraReviewPolicy.shouldPrompt(twoStep))

        val smart = session(pipeline = "smart", lyra = null, unary = "Clean text")
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
    fun `one session keeps every stage and does not invent a smart-mode raw transcript`() {
        val three = session(
            pipeline = "three",
            stage1 = CaptureMode.SMART,
            stage1Text = "Buy milk and eggs",
            lyra = "Buy milk and eggs, listed for the model"
        )
        assertEquals("s1", three.sessionId)
        assertEquals(SessionPipeline.THREE_STEP, three.pipeline)
        assertFalse(three.rawAvailable)
        assertNull(three.stage("raw"))
        assertEquals("Buy milk and eggs", three.stage("smart"))
        assertEquals("Buy milk and eggs, listed for the model", three.stage("lyra"))
        assertEquals(LyraReviewPolicy.RAW_NOTE, three.rawNote)
        assertEquals(listOf("smart", "lyra"), three.stages.map { it.role })

        val two = session(pipeline = "two", stage1Text = "um buy milk", lyra = "Buy milk")
        assertEquals(listOf("raw", "lyra"), two.stages.map { it.role })
        assertTrue(two.rawAvailable)
        assertNull(two.stage("smart"))

        val message = session(pipeline = "smart", stage1Text = "um hey", unary = "Hey")
        assertEquals(listOf("raw", "smart_polish"), message.stages.map { it.role })
        assertFalse(message.reviewable)
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
        val smart = session(pipeline = "smart", unary = "Hey")
        assertEquals(smart, LyraReviewPolicy.apply(smart, LyraRating.DOWN))
    }

    @Test
    fun `export lists counts and one json line with the texts`() {
        val up = LyraReviewPolicy.apply(session(pipeline = "two", id = "up", lyra = "Buy milk"), LyraRating.UP)
        val down = LyraReviewPolicy.apply(
            session(pipeline = "three", id = "down", stage1 = CaptureMode.SMART, lyra = "Buy milk"),
            LyraRating.DOWN,
            tags = listOf(LyraReviewTag.REMOVED_WORDS),
            comment = "dropped eggs"
        )
        val unrated = session(pipeline = "two", id = "later", lyra = "Still waiting")
        val smart = session(pipeline = "smart", id = "wa", unary = "Hey")
        val fallback = session(pipeline = "two", id = "raw", lyra = null)
        val sensitive = session(pipeline = "three", id = "secret", stage1 = CaptureMode.SMART, lyra = "hidden", sensitive = true)
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
        assertTrue(parsed.isNull("raw"))
        assertEquals("um buy milk and eggs", parsed.getString("smart"))
        assertEquals("Buy milk", parsed.getString("lyra"))
        assertEquals("smart", parsed.getString("compare_from"))
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
        stage1: CaptureMode = CaptureMode.VERBATIM,
        stage1Text: String = "um buy milk and eggs",
        lyra: String? = null,
        unary: String? = null,
        ai: Boolean = true,
        sensitive: Boolean = false
    ) = VoiceSessionBuilder.build(
        sessionId = id,
        timestamp = 1_700_000_000_000L,
        appName = "Claude",
        stage1 = stage1,
        threeStep = pipeline == "three",
        usedLiveModel = pipeline == "two" || pipeline == "three",
        aiLyra = ai && (pipeline == "two" || pipeline == "three"),
        stage1Text = stage1Text,
        deliveredText = lyra ?: unary ?: stage1Text,
        lyraText = lyra,
        unaryPolishText = unary,
        sensitive = sensitive
    )
}

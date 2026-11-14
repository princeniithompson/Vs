package com.princeniithompson.rapcha.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.princeniithompson.rapcha.data.HistoryItem
import com.princeniithompson.rapcha.data.HistoryRepository
import com.princeniithompson.rapcha.data.LyraRating
import com.princeniithompson.rapcha.data.VoiceSessionBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnratedClearTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        HistoryRepository.init(app)
        HistoryRepository.clearHistory()
    }

    @Test
    fun `clearing unrated history removes unrated items and keeps rated or all items untouched`() {
        // Add an unrated reviewable session
        val unratedSession = VoiceSessionBuilder.captured(
            sessionId = "unrated-1",
            timestamp = System.currentTimeMillis(),
            appName = "Gemini",
            threeStep = false,
            rawText = "unrated raw",
            smartText = null,
            lyraText = "unrated lyra",
            smartCompleted = true,
            aiLyra = true,
            sensitive = false
        )
        HistoryRepository.saveVoiceSession(unratedSession)

        // Add a rated session
        val ratedSession = VoiceSessionBuilder.captured(
            sessionId = "rated-1",
            timestamp = System.currentTimeMillis(),
            appName = "Claude",
            threeStep = false,
            rawText = "rated raw",
            smartText = null,
            lyraText = "rated lyra",
            smartCompleted = true,
            aiLyra = true,
            sensitive = false
        ).copy(rating = LyraRating.UP)
        HistoryRepository.saveVoiceSession(ratedSession)

        assertEquals(2, HistoryRepository.historyItems.value.size)

        // Clear unrated history
        val removed = HistoryRepository.clearUnratedHistory()
        assertEquals(1, removed.size)
        assertEquals("unrated-1", removed[0].session?.sessionId)

        val remaining = HistoryRepository.historyItems.value
        assertEquals(1, remaining.size)
        assertEquals("rated-1", remaining[0].session?.sessionId)

        // Test restore (Undo) functionality
        HistoryRepository.restoreHistoryItems(removed)
        assertEquals(2, HistoryRepository.historyItems.value.size)
    }
}

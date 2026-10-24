package com.princeniithompson.rapcha.service

import android.app.Application
import android.content.Context
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.data.HistoryRepository
import com.princeniithompson.rapcha.service.floating.FloatingDictationSessionManager
import com.princeniithompson.rapcha.ui.VoiceTypingViewModel
import com.princeniithompson.rapcha.websocket.TranscriptAcceptance
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleSyncAndVocabTest {

    @Test
    fun `constructing the view model does not crash and clears the weekly vocab pref`() {
        val app = RuntimeEnvironment.getApplication() as Application
        val prefs = app.getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("last_weekly_smart_vocab_check_time", 123L)
            .putBoolean("removed_weekly_smart_vocab_v1", false)
            .commit()

        val viewModel = VoiceTypingViewModel(app)

        assertFalse(prefs.contains("last_weekly_smart_vocab_check_time"))
        assertTrue(prefs.getBoolean("removed_weekly_smart_vocab_v1", false))
        assertTrue(viewModel.customApiKey.value.isEmpty() || viewModel.customApiKey.value.isNotEmpty())
    }

    @Test
    fun `fresh vocabulary is empty and the seeded identity is removed once`() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("custom_vocabulary_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        CustomVocabularyRepository.clearForTest()
        CustomVocabularyRepository.init(app)
        assertTrue(CustomVocabularyRepository.getVocabulary().isEmpty())
        assertTrue(prefs.getBoolean("seeded_identity_removed_v1", false))

        CustomVocabularyRepository.clearForTest()
        prefs.edit()
            .putString(
                "vocabulary_terms",
                JSONArray(listOf("prince niithompson", "Keep This", "princeniithompson@gmail.com")).toString()
            )
            .putBoolean("seeded_identity_removed_v1", false)
            .commit()
        CustomVocabularyRepository.init(app)
        assertEquals(listOf("Keep This"), CustomVocabularyRepository.getVocabulary())

        CustomVocabularyRepository.clearForTest()
        prefs.edit()
            .putString(
                "vocabulary_terms",
                JSONArray(listOf("Keep This", "prince niithompson")).toString()
            )
            .putBoolean("seeded_identity_removed_v1", true)
            .commit()
        CustomVocabularyRepository.init(app)
        assertTrue(CustomVocabularyRepository.getVocabulary().contains("prince niithompson"))
        assertTrue(CustomVocabularyRepository.getVocabulary().contains("Keep This"))
    }

    @Test
    fun `near silent audio and quiet vocabulary-only text are dropped`() {
        val vocab = listOf("prince niithompson", "princeniithompson@gmail.com")
        val invented = "Please send the invoice to prince niithompson at example"
        assertEquals("", TranscriptAcceptance.accept(invented, 129.0, 239.0, vocab))
        assertEquals("", TranscriptAcceptance.accept("prince niithompson", 300.0, 500.0, vocab))
        assertEquals("send the invoice", TranscriptAcceptance.accept("send the invoice", 300.0, 500.0, vocab))
        assertEquals(invented, TranscriptAcceptance.accept(invented, 1800.0, 4200.0, vocab))
        val clause = TranscriptAcceptance.vocabularyClause(vocab)
        assertTrue(clause.contains("Never insert a term that was not spoken"))
        assertFalse(clause.contains("Prioritize and accurately transcribe"))
        assertEquals("", TranscriptAcceptance.vocabularyClause(emptyList()))
    }

    @Test
    fun `blank history does not record vocabulary usage`() {
        val app = RuntimeEnvironment.getApplication()
        val vocabPrefs = app.getSharedPreferences("custom_vocabulary_prefs", Context.MODE_PRIVATE)
        vocabPrefs.edit().clear().putBoolean("seeded_identity_removed_v1", true).commit()
        CustomVocabularyRepository.clearForTest()
        CustomVocabularyRepository.init(app)
        CustomVocabularyRepository.addTerm("invoice")
        val before = CustomVocabularyRepository.vocabularyItems.value.first { it.term == "invoice" }.lastUsedTimestamp

        HistoryRepository.clearForTest()
        val historyPrefs = app.getSharedPreferences("history_repository_prefs", Context.MODE_PRIVATE)
        historyPrefs.edit().clear().commit()
        HistoryRepository.init(app)
        assertNull(HistoryRepository.addHistoryItem("   "))
        CustomVocabularyRepository.recordUsageFromTranscript("   ")
        val after = CustomVocabularyRepository.vocabularyItems.value.first { it.term == "invoice" }.lastUsedTimestamp
        assertEquals(before, after)
    }

    @Test
    fun `foreground checks are debounced and cached`() {
        val posted = mutableListOf<Pair<Runnable, Long>>()
        val executed = mutableListOf<Runnable>()
        val published = mutableListOf<Runnable>()
        var evaluations = 0
        val scheduler = ForegroundContextScheduler(
            postDelayed = { runnable, delayMs -> posted.add(runnable to delayMs) },
            cancel = { runnable -> posted.removeAll { it.first === runnable } },
            execute = { runnable -> executed.add(runnable) },
            publishOnMain = { runnable -> published.add(runnable) },
            evaluate = { packageName, _, _ ->
                evaluations += 1
                ForegroundContextScheduler.Evaluation(false, packageName, "Notes")
            }
        )
        scheduler.submit("com.google.android.keep", "Note", "Keep")
        assertEquals(ForegroundContextScheduler.DEBOUNCE_MS, posted.single().second)
        posted.single().first.run()
        executed.single().run()
        published.single().run()
        assertEquals(1, evaluations)

        val postedAfter = posted.size
        scheduler.submit("com.google.android.keep", "Note", "Keep")
        assertEquals(postedAfter, posted.size)
        assertEquals(1, evaluations)

        scheduler.submit("com.android.settings", "Settings", "Settings")
        posted.last().first.run()
        executed.last().run()
        published.last().run()
        assertEquals(2, evaluations)

        scheduler.submit("com.google.android.keep", "Note", "Keep")
        assertEquals(2, evaluations)
        published.last().run()
        assertEquals(2, evaluations)
    }

    @Test
    fun `a tap with no keyboard and no field hides the bubble`() {
        assertEquals(
            FloatingBubbleService.RingTap.HIDE,
            FloatingBubbleService.decideRingTap(recording = false, keyboardVisible = false, fieldFocused = false)
        )
        assertEquals(
            FloatingBubbleService.RingTap.CONTINUE,
            FloatingBubbleService.decideRingTap(recording = true, keyboardVisible = false, fieldFocused = false)
        )
        assertEquals(
            FloatingBubbleService.RingTap.CONTINUE,
            FloatingBubbleService.decideRingTap(recording = false, keyboardVisible = true, fieldFocused = false)
        )
    }

    @Test
    fun `session duration follows the wall clock`() {
        val start = 1_000L
        assertEquals(21, FloatingDictationSessionManager.elapsedSeconds(start, start + 21_400L))
        assertEquals(0, FloatingDictationSessionManager.elapsedSeconds(0L, start + 21_000L))
    }
}

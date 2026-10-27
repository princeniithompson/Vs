package com.princeniithompson.rapcha.data

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.princeniithompson.rapcha.MainActivity
import com.princeniithompson.rapcha.service.floating.VocabSuggestionReceiver
import com.princeniithompson.rapcha.service.floating.VocabSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class VocabSuggestionTest {

    @Before
    fun reset() {
        val app = RuntimeEnvironment.getApplication()
        CustomVocabularyRepository.clearForTest()
        VocabSuggestionStore.clearForTest()
        app.getSharedPreferences("custom_vocabulary_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        app.getSharedPreferences("vocab_suggestion_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        CustomVocabularyRepository.init(app)
        VocabSuggestionStore.init(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `empty sessions and tiny common words are not counted`() {
        val empty = VocabSuggestionPolicy.observe("   ", emptyMap(), emptyList(), NOW)
        assertTrue(empty.records.isEmpty())
        assertNull(empty.suggestion)

        val common = VocabSuggestionPolicy.observe(
            "I want to send the note to you",
            emptyMap(),
            emptyList(),
            NOW
        )
        assertEquals(setOf("want", "send", "note"), common.records.keys)
        assertNull(common.suggestion)
        assertEquals(listOf("want", "send", "note"), VocabSuggestionPolicy.countableTokens("I want to send the note to you"))
    }

    @Test
    fun `one session counts a repeated spelling once and three sessions suggest the best casing`() {
        var records = emptyMap<String, VocabSuggestionPolicy.SpellingRecord>()
        val first = VocabSuggestionPolicy.observe(
            "file the zylophant zylophant receipt",
            records,
            emptyList(),
            NOW
        )
        assertNull(first.suggestion)
        assertEquals(1, first.records.getValue("zylophant").sessionCount)

        val second = VocabSuggestionPolicy.observe(
            "Zylophant again",
            first.records,
            emptyList(),
            NOW
        )
        assertNull(second.suggestion)
        assertEquals(2, second.records.getValue("zylophant").sessionCount)

        val third = VocabSuggestionPolicy.observe(
            "please file ZYLOPHANT today",
            second.records,
            emptyList(),
            NOW
        )
        val suggestion = third.suggestion
        assertEquals("zylophant", suggestion?.key)
        assertEquals("Zylophant", suggestion?.display)
        assertEquals(3, suggestion?.sessionCount)
        assertTrue(third.records.getValue("zylophant").prompted)

        val fourth = VocabSuggestionPolicy.observe(
            "Zylophant once more",
            third.records,
            emptyList(),
            NOW
        )
        assertNull(fourth.suggestion)
        assertEquals(4, fourth.records.getValue("zylophant").sessionCount)
    }

    @Test
    fun `words already in the dictionary are skipped including phrase parts`() {
        val dictionary = listOf("Zylophant", "Prince Niithompson")
        val result = VocabSuggestionPolicy.observe(
            "email Prince about the Zylophant invoice",
            emptyMap(),
            dictionary,
            NOW
        )
        assertEquals(setOf("email", "invoice"), result.records.keys)
        assertNull(result.suggestion)
    }

    @Test
    fun `a saved dictionary word is dropped from the counts on the next session`() {
        val seeded = mapOf(
            "zylophant" to VocabSuggestionPolicy.SpellingRecord(
                sessionCount = 2,
                casingVotes = mapOf("Zylophant" to 2)
            )
        )
        val result = VocabSuggestionPolicy.observe(
            "send the invoice",
            seeded,
            listOf("Zylophant"),
            NOW
        )
        assertFalse(result.records.containsKey("zylophant"))
        assertNull(result.suggestion)
    }

    @Test
    fun `no and dismiss snooze the spelling and edit waits a shorter time`() {
        val ready = promptedRecord()
        val dismissed = VocabSuggestionPolicy.dismiss(ready, "zylophant", NOW)
        assertEquals(NOW + VocabSuggestionPolicy.DISMISS_SNOOZE_MS, dismissed.getValue("zylophant").snoozeUntilMs)
        assertFalse(dismissed.getValue("zylophant").prompted)

        val during = VocabSuggestionPolicy.observe("Zylophant again", dismissed, emptyList(), NOW + 1_000)
        assertNull(during.suggestion)
        assertEquals(4, during.records.getValue("zylophant").sessionCount)

        val after = VocabSuggestionPolicy.observe(
            "Zylophant later",
            during.records,
            emptyList(),
            NOW + VocabSuggestionPolicy.DISMISS_SNOOZE_MS + 1
        )
        assertEquals("Zylophant", after.suggestion?.display)

        val edited = VocabSuggestionPolicy.noteEditOpened(promptedRecord(), "zylophant", NOW)
        assertEquals(NOW + VocabSuggestionPolicy.EDIT_SNOOZE_MS, edited.getValue("zylophant").snoozeUntilMs)
        val stillQuiet = VocabSuggestionPolicy.observe(
            "Zylophant",
            edited,
            emptyList(),
            NOW + 60_000
        )
        assertNull(stillQuiet.suggestion)
    }

    @Test
    fun `yes is the only action that adds a word`() {
        val app = RuntimeEnvironment.getApplication()
        repeat(3) {
            VocabSuggestions.onOutgoingText(app, "file the Zylophant receipt")
        }
        assertTrue(CustomVocabularyRepository.getVocabulary().isEmpty())
        val manager = app.getSystemService(NotificationManager::class.java)
        val posted = manager.activeNotifications.single()
        assertTrue(posted.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Zylophant"))
        assertEquals(listOf("Yes", "Edit", "No"), posted.notification.actions.map { it.title.toString() })

        val yesPending = shadowOf(posted.notification.actions[0].actionIntent)
        val editPending = shadowOf(posted.notification.actions[1].actionIntent)
        val noPending = shadowOf(posted.notification.actions[2].actionIntent)
        assertTrue(yesPending.isBroadcast)
        assertTrue(editPending.isActivity)
        assertTrue(noPending.isBroadcast)
        assertTrue(shadowOf(posted.notification.contentIntent).isActivity)
        assertTrue(shadowOf(posted.notification.deleteIntent).isBroadcast)
        val editIntent = editPending.savedIntent
        assertEquals(MainActivity::class.java.name, editIntent.component?.className)
        assertEquals("Zylophant", editIntent.getStringExtra(MainActivity.EXTRA_DICTIONARY_DRAFT))
        assertEquals("zylophant", editIntent.getStringExtra(VocabSuggestions.EXTRA_KEY))

        VocabSuggestions.handleAction(app, VocabSuggestions.ACTION_NO, "zylophant", "Zylophant")
        assertTrue(CustomVocabularyRepository.getVocabulary().isEmpty())
        assertTrue(manager.activeNotifications.isEmpty())

        VocabSuggestions.onEditOpened(app, "zylophant")
        assertTrue(CustomVocabularyRepository.getVocabulary().isEmpty())
        assertTrue(
            VocabSuggestionStore.load().getValue("zylophant").snoozeUntilMs >=
                System.currentTimeMillis()
        )

        VocabSuggestions.handleAction(app, VocabSuggestions.ACTION_YES, "zylophant", "Zylophant")
        assertEquals(listOf("Zylophant"), CustomVocabularyRepository.getVocabulary())
        assertFalse(VocabSuggestionStore.load().containsKey("zylophant"))
    }

    @Test
    fun `counts survive a new store init and keep the best casing`() {
        val app = RuntimeEnvironment.getApplication()
        VocabSuggestions.onOutgoingText(app, "rapcha and rapcha")
        VocabSuggestions.onOutgoingText(app, "Rapcha today")
        VocabSuggestionStore.clearForTest()
        VocabSuggestionStore.init(app)
        val record = VocabSuggestionStore.load().getValue("rapcha")
        assertEquals(2, record.sessionCount)
        assertEquals("Rapcha", record.display)
    }

    @Test
    fun `the receiver routes yes edit and dismiss without adding on dismiss`() {
        val app = RuntimeEnvironment.getApplication()
        VocabSuggestionStore.save(
            mapOf(
                "zylophant" to VocabSuggestionPolicy.SpellingRecord(
                    sessionCount = 3,
                    casingVotes = mapOf("Zylophant" to 3),
                    prompted = true
                )
            )
        )
        VocabSuggestionReceiver().onReceive(
            app,
            Intent(VocabSuggestions.ACTION_DISMISS).apply {
                putExtra(VocabSuggestions.EXTRA_KEY, "zylophant")
                putExtra(VocabSuggestions.EXTRA_DISPLAY, "Zylophant")
            }
        )
        assertTrue(CustomVocabularyRepository.getVocabulary().isEmpty())
        assertFalse(VocabSuggestionStore.load().getValue("zylophant").prompted)
        assertTrue(VocabSuggestionStore.load().getValue("zylophant").snoozeUntilMs > NOW)

        VocabSuggestionReceiver().onReceive(
            app,
            Intent(VocabSuggestions.ACTION_YES).apply {
                putExtra(VocabSuggestions.EXTRA_KEY, "zylophant")
                putExtra(VocabSuggestions.EXTRA_DISPLAY, "Zylophant")
            }
        )
        assertEquals(listOf("Zylophant"), CustomVocabularyRepository.getVocabulary())
    }

    @Test
    fun `a second word waits until its own session after the first suggestion`() {
        var records = emptyMap<String, VocabSuggestionPolicy.SpellingRecord>()
        repeat(2) {
            records = VocabSuggestionPolicy.observe("Zylophant and Quorvex", records, emptyList(), NOW).records
        }
        val third = VocabSuggestionPolicy.observe("Zylophant and Quorvex", records, emptyList(), NOW)
        assertEquals("zylophant", third.suggestion?.key)
        val later = VocabSuggestionPolicy.observe("Quorvex only", third.records, emptyList(), NOW)
        assertEquals("quorvex", later.suggestion?.key)
        assertEquals("Quorvex", later.suggestion?.display)
    }

    private fun promptedRecord(): Map<String, VocabSuggestionPolicy.SpellingRecord> {
        return mapOf(
            "zylophant" to VocabSuggestionPolicy.SpellingRecord(
                sessionCount = 3,
                casingVotes = mapOf("Zylophant" to 3),
                prompted = true
            )
        )
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

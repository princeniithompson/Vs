package com.princeniithompson.rapcha.data

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.princeniithompson.rapcha.MainActivity
import com.princeniithompson.rapcha.service.floating.RapchaTextInserter
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

    @Test
    fun `scowl size 60 and the common list are bundled and skip ordinary words`() {
        val app = RuntimeEnvironment.getApplication()
        val notice = app.assets.open(EnglishLexicon.COPYRIGHT_ASSET).bufferedReader().readText()
        assertTrue(notice.contains("Copyright 2000-2018 by Kevin Atkinson"))
        assertTrue(notice.contains("Permission to use, copy, modify, distribute and sell these word"))
        EnglishLexicon.ensureLoaded(app)
        assertTrue(EnglishLexicon.loadedWordCount() > 100_000)
        assertTrue(EnglishLexicon.isOrdinary("tired"))
        assertTrue(EnglishLexicon.isOrdinary("difficult"))
        assertTrue(EnglishLexicon.isOrdinary("going"))
        assertTrue(EnglishLexicon.isOrdinary("nickname"))
        assertTrue(EnglishLexicon.isOrdinary("WhatsApp"))
        assertTrue(EnglishLexicon.isOrdinary("YouTube"))
        assertTrue(EnglishLexicon.isOrdinary("ChatGPT"))
        assertTrue(EnglishLexicon.isOrdinary("OpenAI"))
        assertTrue(EnglishLexicon.isOrdinary("TikTok"))
        assertTrue(EnglishLexicon.isOrdinary("Accra"))
        assertTrue(EnglishLexicon.isOrdinary("Ghana"))
        assertFalse(EnglishLexicon.isOrdinary("30frst"))
        assertFalse(EnglishLexicon.isOrdinary("30first"))

        val appNotifications = app.getSystemService(NotificationManager::class.java)
        repeat(3) {
            VocabSuggestions.onOutgoingText(
                app,
                "I'm tired and going to Ghana on WhatsApp and YouTube"
            )
        }
        VocabSuggestions.onOutgoingText(app, "This is difficult")
        assertTrue(VocabSuggestionStore.load().isEmpty())
        assertTrue(appNotifications.activeNotifications.isEmpty())
    }

    @Test
    fun `nickname intro suggests only the following token once`() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(
            listOf("30frst"),
            VocabSuggestionPolicy.personalIdentifiers("my nickname is 30frst, thanks")
        )
        assertEquals(
            listOf("30frst"),
            VocabSuggestionPolicy.personalIdentifiers("my nickname is 30frst and who that called")
        )
        assertEquals(
            listOf("tired"),
            VocabSuggestionPolicy.personalIdentifiers("I'm tired")
        )
        assertEquals(
            listOf("difficult"),
            VocabSuggestionPolicy.personalIdentifiers("This is difficult")
        )
        assertEquals(
            listOf("Prince"),
            VocabSuggestionPolicy.personalIdentifiers("my name is Prince Thompson")
        )

        VocabSuggestions.onOutgoingText(app, "my nickname is 30frst and who knows")
        assertEquals(setOf("30frst"), VocabSuggestionStore.load().keys)
        assertEquals(1, VocabSuggestionStore.load().getValue("30frst").sessionCount)
        val manager = app.getSystemService(NotificationManager::class.java)
        val text = manager.activeNotifications.single().notification.extras
            .getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text.contains("30frst"))
        assertFalse(text.contains("nickname"))

        val name = VocabSuggestionPolicy.observe(
            "my name is Prince Thompson",
            emptyMap(),
            emptyList(),
            NOW,
            EnglishLexicon::isOrdinary
        )
        assertNull(name.suggestion)
        assertFalse(name.records.containsKey("prince"))
        assertFalse(name.records.containsKey("prince thompson"))
    }

    @Test
    fun `rare spellings need three exact sessions and do not fuzzy merge`() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        VocabSuggestions.onOutgoingText(app, "note 30frst")
        VocabSuggestions.onOutgoingText(app, "note 30first")
        VocabSuggestions.onOutgoingText(app, "note 30frst")
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(2, VocabSuggestionStore.load().getValue("30frst").sessionCount)
        assertEquals(1, VocabSuggestionStore.load().getValue("30first").sessionCount)

        VocabSuggestions.onOutgoingText(app, "note 30frst")
        val text = manager.activeNotifications.single().notification.extras
            .getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text.contains("30frst"))
        assertFalse(text.contains("30first"))
        assertEquals(3, VocabSuggestionStore.load().getValue("30frst").sessionCount)
        assertEquals(1, VocabSuggestionStore.load().getValue("30first").sessionCount)
    }

    @Test
    fun `no snooze blocks the same personal identifier on the next session`() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        VocabSuggestions.onOutgoingText(app, "my nickname is 30frst")
        assertEquals(1, manager.activeNotifications.size)
        VocabSuggestions.handleAction(app, VocabSuggestions.ACTION_NO, "30frst", "30frst")
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(CustomVocabularyRepository.getVocabulary().isEmpty())

        VocabSuggestions.onOutgoingText(app, "my nickname is 30frst")
        assertTrue(manager.activeNotifications.isEmpty())
        val snooze = VocabSuggestionStore.load().getValue("30frst").snoozeUntilMs
        assertTrue(snooze >= System.currentTimeMillis() + 13L * 24 * 60 * 60 * 1000)
    }

    @Test
    fun `emails handles and phone numbers suggest once`() {
        val app = RuntimeEnvironment.getApplication()
        val ids = VocabSuggestionPolicy.personalIdentifiers(
            "ping prince@example.com or @nii_handle or +233 20 111 2222"
        )
        assertTrue(ids.any { it.equals("prince@example.com", ignoreCase = true) })
        assertTrue(ids.any { it == "@nii_handle" })
        assertTrue(ids.any { it.contains("233") && it.count { ch -> ch.isDigit() } >= 10 })

        VocabSuggestions.onOutgoingText(app, "ping prince@example.com please")
        val manager = app.getSystemService(NotificationManager::class.java)
        val text = manager.activeNotifications.single().notification.extras
            .getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text.contains("prince@example.com"))
        assertEquals(1, VocabSuggestionStore.load().getValue("prince@example.com").sessionCount)
        assertFalse(VocabSuggestionStore.load().containsKey("ping"))
        assertFalse(VocabSuggestionStore.load().containsKey("please"))
    }

    @Test
    fun `failed inject and failed clipboard cutoff do not increment`() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(VocabSuggestions.countsAsOutgoing(RapchaTextInserter.InsertionResult.Failed))
        assertTrue(VocabSuggestions.countsAsOutgoing(RapchaTextInserter.InsertionResult.SuccessDirect))
        assertTrue(
            VocabSuggestions.countsAsOutgoing(RapchaTextInserter.InsertionResult.SuccessClipboardFallback)
        )

        VocabSuggestions.onConfirmedPlacement(
            app,
            "zylophant showed up",
            RapchaTextInserter.InsertionResult.Failed
        )
        VocabSuggestions.onConfirmedClipboard(app, "zylophant showed up", copied = false)
        VocabSuggestions.onConfirmedPlacement(
            app,
            "   ",
            RapchaTextInserter.InsertionResult.SuccessDirect
        )
        assertTrue(VocabSuggestionStore.load().isEmpty())

        VocabSuggestions.onConfirmedPlacement(
            app,
            "zylophant showed up",
            RapchaTextInserter.InsertionResult.SuccessClipboardFallback
        )
        assertEquals(1, VocabSuggestionStore.load().getValue("zylophant").sessionCount)
        VocabSuggestions.onConfirmedClipboard(app, "quorvex showed up", copied = true)
        assertEquals(1, VocabSuggestionStore.load().getValue("quorvex").sessionCount)
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

package com.princeniithompson.rapcha.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StopTheSnapRepositoryTest {

    @Test
    fun `v2 metadata migrates to v3 and tricky terms stay out of live`() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("custom_vocabulary_prefs", Context.MODE_PRIVATE)
        val terms = JSONArray(listOf("30frst", "Prince Nii", "me@mail.com", "snake_case"))
        val meta = JSONObject()
        meta.put("30frst", termMeta("30frst", added = 1_000L, used = 2_000L))
        meta.put("prince nii", termMeta("Prince Nii", added = 3_000L, used = 4_000L))
        prefs.edit().clear()
            .putString("vocabulary_terms", terms.toString())
            .putString("vocabulary_metadata_v2", meta.toString())
            .putBoolean("seeded_identity_removed_v1", true)
            .commit()
        CustomVocabularyRepository.clearForTest()
        CustomVocabularyRepository.init(app)

        assertEquals(
            listOf("30frst", "Prince Nii", "me@mail.com", "snake_case"),
            CustomVocabularyRepository.getVocabulary()
        )
        assertEquals(listOf("Prince Nii"), CustomVocabularyRepository.liveTerms(smartMode = true))
        assertEquals(listOf("Prince Nii"), CustomVocabularyRepository.liveTerms(smartMode = false))
        val tricky = CustomVocabularyRepository.vocabularyItems.value.first { it.term == "30frst" }
        assertEquals(VocabClass.TRICKY, tricky.vocabClass)
        assertFalse(tricky.trusted)
        assertEquals(2_000L, tricky.lastUsedTimestamp)
        assertTrue(prefs.contains("vocabulary_metadata_v3"))
        assertFalse(CustomVocabularyRepository.redactForDiagnostics("mail 30frst me@mail.com").contains("30frst"))
        assertFalse(CustomVocabularyRepository.redactForDiagnostics("mail 30frst me@mail.com").contains("me@mail.com"))

        CustomVocabularyRepository.clearForTest()
        CustomVocabularyRepository.init(app)
        assertEquals(
            VocabClass.TRICKY,
            CustomVocabularyRepository.vocabularyItems.value.first { it.term == "30frst" }.vocabClass
        )
        assertTrue(CustomVocabularyRepository.getVocabulary().contains("30frst"))
    }

    @Test
    fun `live terms are easy trusted only and capped by mode`() {
        val app = freshRepo()
        repeat(70) { index ->
            assertTrue(CustomVocabularyRepository.addTerm("easy$index"))
        }
        assertTrue(CustomVocabularyRepository.addTerm("30frst", spokenAlias = "thirty first"))
        assertTrue(CustomVocabularyRepository.addTerm("me@mail.com"))

        val smart = CustomVocabularyRepository.liveTerms(smartMode = true)
        val verbatim = CustomVocabularyRepository.liveTerms(smartMode = false)
        assertEquals(CustomVocabularyRepository.SMART_LIVE_CAP, smart.size)
        assertEquals(CustomVocabularyRepository.VERBATIM_LIVE_CAP, verbatim.size)
        assertEquals("easy69", smart.first())
        assertFalse(smart.contains("easy0"))
        assertTrue(verbatim.contains("easy10"))
        assertFalse(verbatim.contains("easy0"))
        assertFalse(smart.any { it == "30frst" || it.contains("@") })
        assertFalse(verbatim.any { it == "30frst" || it.contains("@") })
        assertTrue(CustomVocabularyRepository.getVocabulary().contains("30frst"))
        assertTrue(app.packageName.isNotEmpty())
    }

    @Test
    fun `an easy override is sent and a tricky override is not`() {
        freshRepo()
        assertTrue(CustomVocabularyRepository.addTerm("30frst", classOverride = VocabClass.EASY))
        assertTrue(CustomVocabularyRepository.liveTerms(smartMode = false).contains("30frst"))
        assertTrue(
            CustomVocabularyRepository.editTerm(
                oldTerm = "30frst",
                newTerm = "30frst",
                classOverride = VocabClass.TRICKY
            )
        )
        assertFalse(CustomVocabularyRepository.liveTerms(smartMode = false).contains("30frst"))
        assertTrue(CustomVocabularyRepository.getVocabulary().contains("30frst"))
    }

    @Test
    fun `live output of a tricky term is not strong usage`() {
        freshRepo()
        val meta = JSONObject()
        meta.put("30frst", termMeta("30frst", added = 1_000L, used = 5_000L))
        meta.put("invoice", termMeta("invoice", added = 1_000L, used = 5_000L))
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("custom_vocabulary_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("vocabulary_terms", JSONArray(listOf("30frst", "invoice")).toString())
            .putString("vocabulary_metadata_v2", meta.toString())
            .putBoolean("seeded_identity_removed_v1", true)
            .commit()
        CustomVocabularyRepository.clearForTest()
        CustomVocabularyRepository.init(RuntimeEnvironment.getApplication())

        CustomVocabularyRepository.recordUsageFromTranscript("see 30frst in the invoices")
        assertEquals(5_000L, used("30frst"))
        assertEquals(5_000L, used("invoice"))

        CustomVocabularyRepository.recordUsageFromTranscript("send the invoice today")
        assertTrue(used("invoice") > 5_000L)
        assertEquals(5_000L, used("30frst"))

        CustomVocabularyRepository.syncUsageFromHistory(
            listOf(HistoryItem(text = "old snap 30frst", timestamp = 9_000L))
        )
        assertEquals(5_000L, used("30frst"))

        assertTrue(
            CustomVocabularyRepository.editTerm(
                oldTerm = "30frst",
                newTerm = "30frst",
                spokenAlias = "thirty first"
            )
        )
        val restored = CustomVocabularyRepository.prepareOutgoingTranscript(
            "say thirty first now",
            DiagnosticSource.APP
        )
        assertEquals("say 30frst now", restored)
        assertTrue(used("30frst") > 5_000L)
        val logged = AppLogRepository.diagnosticEntries.value.joinToString("\n") { it.message }
        assertTrue(logged.contains("would_restore=1"))
        assertFalse(logged.contains("30frst"))
        assertFalse(logged.contains("thirty first"))
    }

    private fun freshRepo(): android.app.Application {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("custom_vocabulary_prefs", Context.MODE_PRIVATE)
            .edit().clear().putBoolean("seeded_identity_removed_v1", true).commit()
        CustomVocabularyRepository.clearForTest()
        CustomVocabularyRepository.init(app)
        return app
    }

    private fun used(term: String): Long {
        return CustomVocabularyRepository.vocabularyItems.value.first { it.term == term }.lastUsedTimestamp
    }

    private fun termMeta(term: String, added: Long, used: Long): JSONObject {
        return JSONObject().apply {
            put("term", term)
            put("addedTimestamp", added)
            put("lastUsedTimestamp", used)
            put("isKeepDismissed", false)
        }
    }
}

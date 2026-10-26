package com.princeniithompson.rapcha.data

import com.princeniithompson.rapcha.websocket.TranscriptAcceptance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopTheSnapLogicTest {

    @Test
    fun `classifies easy words and tricky spellings`() {
        assertEquals(VocabClass.EASY, VocabClassifier.classify("Prince Nii"))
        assertEquals(VocabClass.EASY, VocabClassifier.classify("Kubernetes"))
        assertEquals(VocabClass.EASY, VocabClassifier.classify("GG api"))
        assertEquals(VocabClass.TRICKY, VocabClassifier.classify("30frst"))
        assertEquals(VocabClass.TRICKY, VocabClassifier.classify("me@mail.com"))
        assertEquals(VocabClass.TRICKY, VocabClassifier.classify("snake_case"))
        assertEquals(VocabClass.TRICKY, VocabClassifier.classify("file.txt"))
        assertEquals(VocabClass.TRICKY, VocabClassifier.classify("GPT-4"))
    }

    @Test
    fun `restore replaces only an exact confirmed alias`() {
        val items = listOf(
            VocabularyItem(
                term = "30frst",
                vocabClass = VocabClass.TRICKY,
                spokenAlias = "thirty first",
                trusted = false
            )
        )
        val grok = AliasRestore.restore("I said Grok bot and Grokbot", items)
        assertEquals("I said Grok bot and Grokbot", grok.text)
        assertEquals(0, grok.replacementCount)

        val hit = AliasRestore.restore("Meet Thirty  First, please", items)
        assertEquals("Meet 30frst, please", hit.text)
        assertEquals(listOf("30frst"), hit.restoredTerms)

        val near = AliasRestore.restore("the thirty firstly", items)
        assertEquals("the thirty firstly", near.text)
        assertFalse(near.text.contains("30frst"))
    }

    @Test
    fun `polish cannot introduce a tricky term or drop a protected one`() {
        val items = listOf(
            VocabularyItem(
                term = "30frst",
                vocabClass = VocabClass.TRICKY,
                spokenAlias = "thirty first",
                trusted = false
            ),
            VocabularyItem(
                term = "me@mail.com",
                vocabClass = VocabClass.TRICKY,
                trusted = false
            )
        )
        val invented = AliasRestore.guardPolishedText(
            before = "I told Grok bot",
            polished = "I told Grok bot 30frst me@mail.com",
            items = items
        )
        assertFalse(invented.contains("30frst"))
        assertFalse(invented.contains("me@mail.com"))
        assertTrue(invented.contains("Grok bot"))

        val kept = AliasRestore.guardPolishedText(
            before = "use 30frst today",
            polished = "Please use thirty-first today",
            items = items
        )
        assertEquals("use 30frst today", kept)

        val allowed = AliasRestore.guardPolishedText(
            before = "use 30frst today",
            polished = "Please use 30frst today.",
            items = items
        )
        assertEquals("Please use 30frst today.", allowed)
    }

    @Test
    fun `smart mode drops the vocabulary clause and verbatim keeps it`() {
        val terms = listOf("Prince Nii")
        assertEquals("", TranscriptAcceptance.vocabularyClauseForSetup(terms, smartMode = true))
        val verbatim = TranscriptAcceptance.vocabularyClauseForSetup(terms, smartMode = false)
        assertTrue(verbatim.contains("Prince Nii"))
        assertTrue(verbatim.contains("Never insert a term that was not spoken"))
    }
}

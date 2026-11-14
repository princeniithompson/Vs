package com.princeniithompson.rapcha.service.floating

import com.princeniithompson.rapcha.config.RapchaConfig
import com.princeniithompson.rapcha.data.VocabTermFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage2LongPolishTest {

    @Test
    fun `a very long transcript is split above 24k chars and a normal one stays one turn`() {
        assertEquals(listOf("Send the note."), Stage2TranscriptChunker.chunks("Send the note."))
        val medium = "Please polish this paragraph for the students. ".repeat(80).trim()
        assertEquals(1, Stage2TranscriptChunker.chunks(medium).size)

        val sentence = "Please polish this paragraph for the students. "
        val raw = sentence.repeat(700).trim()
        assertTrue(raw.length > Stage2TranscriptChunker.MAX_CHARS)
        val parts = Stage2TranscriptChunker.chunks(raw)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= Stage2TranscriptChunker.MAX_CHARS })
        assertTrue(parts.all { it.endsWith(".") || it.length < 20 })
        val joined = parts.reduce { acc, piece -> Stage2TranscriptChunker.join(acc, piece) }
        assertTrue(joined.contains("students"))
        assertFalse(joined.contains("  "))
    }

    @Test
    fun `long turns get more than six seconds and a stale warm socket is dropped`() {
        assertEquals(RapchaConfig.STAGE2_LIVE_TIMEOUT_MS, Stage2LiveBudget.firstTokenMs(200))
        assertEquals(RapchaConfig.STAGE2_LIVE_HARD_CAP_MS, Stage2LiveBudget.hardCapMs(0))
        assertTrue(Stage2LiveBudget.firstTokenMs(8_000) > 20_000L)
        assertTrue(Stage2LiveBudget.hardCapMs(8_000) > 20_000L)
        assertTrue(Stage2LiveBudget.hardCapMs(8_000) <= 90_000L)
        assertTrue(Stage2LiveBudget.warmEnough(2_000L))
        assertFalse(Stage2LiveBudget.warmEnough(545_000L))
    }

    @Test
    fun `spellable junk is rejected and an unusual name still passes`() {
        listOf("cedis", "txt", "MCP", "pdf", "api").forEach { term ->
            assertEquals(term, "spellable", VocabTermFilter.spellableReason(term))
            assertFalse(VocabTermFilter.isSuggestable(term))
        }
        assertTrue(VocabTermFilter.isUsable("want"))
        assertFalse(VocabTermFilter.isSuggestable("want"))
        assertTrue(VocabTermFilter.isUsable("Niithompson"))
        assertTrue(VocabTermFilter.isUsable("30frst"))
        assertTrue(VocabTermFilter.isUsable("prince@example.com"))
    }
}

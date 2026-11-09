package com.princeniithompson.rapcha.websocket

import com.princeniithompson.rapcha.data.LyraHandoff
import com.princeniithompson.rapcha.data.TextFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefinalBackupTest {

    @Test
    fun `latest preview replaces the open piece and a pause does not freeze it`() {
        val backup = PrefinalBackup()
        backup.onPreview("I need seven")
        backup.onPreview("I need seven crates of eggs um seven crates")
        backup.onPreview("I need seven crates of eggs. Actually, reduce it to three crates")
        val shown = backup.displayed()
        assertTrue(shown.contains("seven crates"))
        assertTrue(shown.contains("three crates"))
        assertFalse(shown.contains("I need seven crates of eggs um seven crates I need"))
        assertFalse(backup.isSealed)
    }

    @Test
    fun `seal freezes the display before a later smart final can replace it`() {
        val backup = PrefinalBackup()
        val events = mutableListOf<String>()
        backup.onPreview("I need seven crates of eggs. Actually, reduce it to three crates")
        events += "persist:" + backup.seal()
        events += "activityEnd"
        backup.onPreview("Three crates of eggs.")
        assertEquals(
            "I need seven crates of eggs. Actually, reduce it to three crates",
            backup.displayed()
        )
        assertEquals("persist:" + backup.displayed(), events[0])
        assertEquals("activityEnd", events[1])
        assertTrue(backup.isSealed)
    }

    @Test
    fun `long sessions keep every prefinal piece and not the formatted piece`() {
        val backup = PrefinalBackup()
        backup.onPreview("alpha um alpha")
        backup.freezeOpenPiece()
        backup.onPreview("Alpha.")
        backup.onPreview("beta um beta")
        val raw = backup.seal()
        assertEquals("alpha um alpha beta um beta", raw)
        assertFalse(raw.contains("Alpha."))
    }

    @Test
    fun `lyra is handed the completed smart text and not the frozen raw`() {
        val raw = "I need seven crates um actually three crates"
        val smart = "I need three crates of eggs."
        val sent = LyraHandoff.source(smart)
        assertTrue(LyraHandoff.matches(smart, sent))
        assertFalse(LyraHandoff.matches(raw, sent))
        val chunks = com.princeniithompson.rapcha.service.floating.Stage2TranscriptChunker.chunks(sent)
        val joined = chunks.fold("") { acc, piece ->
            com.princeniithompson.rapcha.service.floating.Stage2TranscriptChunker.join(acc, piece)
        }
        assertEquals(sent, joined)
        assertEquals(TextFingerprint.sha256(smart), TextFingerprint.sha256(sent))
        assertNotEquals(TextFingerprint.sha256(raw), TextFingerprint.sha256(sent))
    }
}

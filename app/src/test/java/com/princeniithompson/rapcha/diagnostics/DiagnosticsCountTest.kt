package com.princeniithompson.rapcha.diagnostics

import com.princeniithompson.rapcha.data.DiagnosticLogEntry
import com.princeniithompson.rapcha.data.DiagnosticNote
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.data.mergeDiagnosticEntries
import com.princeniithompson.rapcha.data.mergeDiagnosticNotes
import com.princeniithompson.rapcha.ui.components.diagnostics.diagnosticsCountText
import com.princeniithompson.rapcha.ui.components.diagnostics.groupedHistoryCount
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsCountTest {
    @Test
    fun historyCountMatchesTheSameEventList() {
        val entries = listOf(
            entry("a", 3_000L),
            entry("b", 2_000L),
            entry("c", 1_000L)
        )
        assertEquals(entries.size, groupedHistoryCount(entries))
        assertEquals("None", diagnosticsCountText(0))
        assertEquals("336", diagnosticsCountText(336))
    }

    @Test
    fun diskLoadKeepsEventsLoggedWhileTheFileWasReading() {
        val onDisk = listOf(entry("disk", 1_000L))
        val inMemory = listOf(entry("new", 2_000L), entry("disk", 1_000L))
        val merged = mergeDiagnosticEntries(inMemory, onDisk)
        assertEquals(listOf("new", "disk"), merged.map { it.id })
    }

    @Test
    fun diskLoadKeepsNotesLoggedWhileTheFileWasReading() {
        val onDisk = listOf(DiagnosticNote(id = "disk", timestamp = 1_000L, text = "old"))
        val inMemory = listOf(
            DiagnosticNote(id = "new", timestamp = 2_000L, text = "fresh"),
            DiagnosticNote(id = "disk", timestamp = 1_000L, text = "old")
        )
        val merged = mergeDiagnosticNotes(inMemory, onDisk)
        assertEquals(listOf("new", "disk"), merged.map { it.id })
    }

    private fun entry(id: String, timestamp: Long) = DiagnosticLogEntry(
        id = id,
        timestamp = timestamp,
        source = DiagnosticSource.APP,
        type = DiagnosticType.SESSION_START,
        message = id
    )
}
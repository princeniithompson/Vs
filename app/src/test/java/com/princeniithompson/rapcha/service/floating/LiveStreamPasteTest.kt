package com.princeniithompson.rapcha.service.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamPasteTest {

    @Test
    fun `cumulative polish chunks do not multiply the field`() {
        val updates = listOf(
            "Send the note",
            "Send the note to Prince",
            "Send the note to Prince tomorrow.",
            "Send the note to Prince tomorrow."
        )
        val visible = LiveStreamPaste.visibleText(updates)
        assertEquals(updates.last(), visible)
        val stacked = updates.joinToString("")
        assertTrue(stacked.length > visible.length)
        assertEquals(" to Prince", (LiveStreamPaste.plan(updates[0], updates[1]) as LiveStreamPaste.Edit.Append).suffix)
        assertEquals(LiveStreamPaste.Edit.Skip, LiveStreamPaste.plan(updates.last(), updates.last()))
    }

    @Test
    fun `a revised snapshot replaces the earlier paste instead of appending it`() {
        val updates = listOf("\"Hello", "Hello there")
        assertEquals("Draft: Hello there", LiveStreamPaste.visibleText(updates, prefix = "Draft: "))
        val edit = LiveStreamPaste.plan("\"Hello", "Hello there")
        assertEquals(LiveStreamPaste.Edit.Replace("\"Hello".length, "Hello there"), edit)
    }

    @Test
    fun `one complete string is committed once`() {
        assertEquals("See you at 10.", LiveStreamPaste.visibleText(listOf("See you at 10.")))
        assertEquals(LiveStreamPaste.Edit.Append("See you at 10."), LiveStreamPaste.plan("", "See you at 10."))
    }
}

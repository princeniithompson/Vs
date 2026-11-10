package com.princeniithompson.rapcha.ui

import com.princeniithompson.rapcha.ui.components.WordsBubblePreviewSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WordsBubblePreviewTest {
    @Test
    fun `words preview labels are only cancel and polish at a fixed height`() {
        assertEquals(listOf("Cancel", "Polish"), WordsBubblePreviewSpec.labels)
        assertFalse(WordsBubblePreviewSpec.labels.contains("Complete"))
        assertEquals(168, WordsBubblePreviewSpec.HEIGHT_DP)
    }
}

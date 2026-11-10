package com.princeniithompson.rapcha.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.ui.components.GlowAnimationCatalogue
import com.princeniithompson.rapcha.ui.components.WordsBubblePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class WordsBubblePreviewRenderTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `words preview is a fixed blank bubble with only cancel and polish`() {
        rule.setContent {
            Box(modifier = Modifier.width(360.dp)) {
                WordsBubblePreview(glowStyleId = GlowAnimationCatalogue.DEFAULT_ID)
            }
        }
        val texts = rule.onAllNodes(hasAnyText).fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        }.filter { it.isNotBlank() }
        assertEquals(listOf("Cancel", "Polish"), texts)
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("Polish").assertIsDisplayed()
        rule.onNodeWithText("Complete").assertDoesNotExist()

        val bubble = rule.onNodeWithTag("words-bubble-preview")
        val bubbleBounds = bubble.getUnclippedBoundsInRoot()
        assertEquals(168f, (bubbleBounds.bottom - bubbleBounds.top).value, 0.5f)

        listOf("Cancel", "Polish").forEach { label ->
            val node = rule.onNodeWithText(label)
            assertEquals(node.getUnclippedBoundsInRoot(), node.getBoundsInRoot())
            val bounds = node.getBoundsInRoot()
            assertTrue(bounds.top >= bubbleBounds.top)
            assertTrue(bounds.bottom <= bubbleBounds.bottom + 0.5.dp)
            assertTrue(bounds.left >= bubbleBounds.left)
            assertTrue(bounds.right <= bubbleBounds.right + 0.5.dp)
            assertTrue((bounds.bottom - bounds.top).value > 0f)
            assertTrue((bounds.right - bounds.left).value > 0f)
        }
    }

    private val hasAnyText = SemanticsMatcher("has text") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
    }
}

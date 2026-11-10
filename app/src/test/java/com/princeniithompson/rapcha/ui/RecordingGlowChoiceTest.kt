package com.princeniithompson.rapcha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.service.floating.BubbleChromeStyle
import com.princeniithompson.rapcha.ui.components.GlowAnimationCatalogue
import com.princeniithompson.rapcha.ui.components.WordsBubblePreview
import com.princeniithompson.rapcha.ui.components.WordsMiniSwitch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class RecordingGlowChoiceTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `words page can show while mini stays chosen`() {
        rule.setContent {
            Column(
                modifier = Modifier
                    .width(360.dp)
                    .background(Color(0xFFF3F6F4))
                    .padding(16.dp)
            ) {
                WordsBubblePreview(glowStyleId = GlowAnimationCatalogue.DEFAULT_ID)
                Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    WordsMiniSwitch(active = BubbleChromeStyle.COMPACT, onChoose = {})
                }
            }
        }
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("Polish").assertIsDisplayed()
        rule.onNodeWithText("Words").assertIsDisplayed()
        rule.onNodeWithText("Mini").assertIsDisplayed()
        rule.onNodeWithText("Complete").assertDoesNotExist()
        rule.onNodeWithText("Select as active").assertDoesNotExist()
    }
}

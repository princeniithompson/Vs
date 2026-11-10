package com.princeniithompson.rapcha.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.service.floating.BubbleChromeStyle
import com.princeniithompson.rapcha.service.floating.DynamicTonePolicy
import com.princeniithompson.rapcha.ui.components.RecordingGlowScreen
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h800dp")
class RecordingGlowScreenShotTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `words page keeps cancel and polish and lists every glow`() {
        show(page = 0, chrome = BubbleChromeStyle.ORIGINAL)
        rule.onNodeWithText("Recording glows").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("Polish").assertIsDisplayed()
        rule.onNodeWithText("Words").assertIsDisplayed()
        rule.onNodeWithText("Mini").assertIsDisplayed()
        rule.onNodeWithText("Luminous").assertIsDisplayed()
        rule.onNodeWithText("Deep").assertIsDisplayed()
        listOf(
            "Breathing Horizon",
            "Sunset Mirage",
            "Gentle Wave Slice",
            "Flashlight Core",
            "Electric Sunburst",
            "Bioluminescent Shimmer",
            "Liquid Specular Glass",
            "Velvet Ambient Calm"
        ).forEach { rule.onNodeWithText(it).fetchSemanticsNode() }
        rule.onNodeWithText("Select as active").assertDoesNotExist()
        rule.onNodeWithText("Soft band").assertDoesNotExist()
        assertNull(chosenStyle)
    }

    @Test
    fun `mini page uses the real compact bar`() {
        show(page = 1, chrome = BubbleChromeStyle.COMPACT)
        rule.onNodeWithText("00:00").assertIsDisplayed()
        rule.onNodeWithText("App").assertIsDisplayed()
        rule.onNodeWithText("Select as active").assertDoesNotExist()
    }

    private var chosenStyle: String? = null

    private fun show(page: Int, chrome: BubbleChromeStyle) {
        chosenStyle = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            RecordingGlowScreen(
                selectedStyleId = "velvet_aura",
                activeChrome = chrome,
                tone = DynamicTonePolicy.LUMINOUS,
                idle = false,
                onSelectStyle = { chosenStyle = it },
                onChooseChrome = {},
                onChooseTone = {},
                onDismiss = {},
                modifier = Modifier.width(360.dp).height(800.dp),
                initialPreviewPage = page
            )
        }
        rule.mainClock.advanceTimeByFrame()
    }
}

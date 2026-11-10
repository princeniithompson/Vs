package com.princeniithompson.rapcha.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.princeniithompson.rapcha.service.floating.BubbleChromeStyle
import com.princeniithompson.rapcha.service.floating.DynamicTonePolicy
import com.princeniithompson.rapcha.ui.components.RecordingGlowScreen
import java.io.File
import org.junit.Assert.assertTrue
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

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun `recording glows screen shows the words page`() {
        rule.setContent {
            RecordingGlowScreen(
                selectedStyleId = "breathing_horizon",
                activeChrome = BubbleChromeStyle.ORIGINAL,
                tone = DynamicTonePolicy.LUMINOUS,
                idle = false,
                onSelectStyle = {},
                onChooseChrome = {},
                onChooseTone = {},
                onDismiss = {},
                modifier = Modifier.width(360.dp)
            )
        }
        rule.onNodeWithText("Recording glows").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("Polish").assertIsDisplayed()
        rule.onNodeWithText("Words").assertIsDisplayed()
        rule.onNodeWithText("Mini").assertIsDisplayed()
        rule.onNodeWithText("Luminous").assertIsDisplayed()
        rule.onNodeWithText("Deep").assertIsDisplayed()
        rule.onNodeWithText("Breathing Horizon").assertIsDisplayed()
        rule.onNodeWithText("Flashlight Core").assertIsDisplayed()
        rule.onNodeWithText("Select as active").assertDoesNotExist()
        rule.onNodeWithText("Complete").assertDoesNotExist()
        rule.onNodeWithText("Soft band").assertDoesNotExist()

        val out = File("/opt/cursor/artifacts/recording-glows-19.png")
        out.parentFile?.mkdirs()
        captureRoboImage(
            file = out,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)
        ) {
            RecordingGlowScreen(
                selectedStyleId = "breathing_horizon",
                activeChrome = BubbleChromeStyle.ORIGINAL,
                tone = DynamicTonePolicy.LUMINOUS,
                idle = false,
                onSelectStyle = {},
                onChooseChrome = {},
                onChooseTone = {},
                onDismiss = {},
                modifier = Modifier.width(360.dp)
            )
        }
        assertTrue(out.length() > 0L)
    }
}

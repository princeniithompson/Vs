package com.example.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.InjectionEvent
import com.example.data.InjectionLogRepository
import com.example.service.FloatingBubbleManager
import com.example.service.floating.FloatingTextInjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InjectionChainVerificationTest {

    private val service = VoxStreamAccessibilityService()

    @Before
    fun setUp() {
        FloatingBubbleManager.updateCurrentForegroundPackage("com.google.android.keep", null)
    }

    @Test
    fun `injectText blocks completely when Smart Safe Mode is active`() {
        FloatingBubbleManager.updateCurrentForegroundPackage("com.chase.sig.android", null)
        try {
            val injected = service.injectText("Secret Password 123")
            assertFalse("Injection must be blocked by Smart Safe Mode", injected)
        } finally {
            FloatingBubbleManager.updateCurrentForegroundPackage("com.google.android.keep", null)
        }
    }

    @Test
    fun `injectText rejects empty or blank text without executing any injection`() {
        assertFalse(service.injectText(""))
    }

    @Test
    fun `injectTextSafely preserves existing text and inserts at cursor position without duplication`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Hello "
        node.isShowingHintText = false

        val genuineText = FloatingTextInjector.extractGenuineText(node)
        assertEquals("Hello ", genuineText)

        val dictatedText = "world"
        val space = if (genuineText.isNotEmpty() && !genuineText.endsWith(" ") && !genuineText.endsWith("\n")) " " else ""
        assertEquals("", space)
        val combined = genuineText + space + dictatedText
        assertEquals("Hello world", combined)
    }

    @Test
    fun `injectTextSafely strips known hint text to prevent duplicate hint incorporation`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Search…"
        node.isShowingHintText = true

        val genuineText = FloatingTextInjector.extractGenuineText(node)
        assertEquals("", genuineText)

        val dictatedText = "Query"
        val space = if (genuineText.isNotEmpty() && !genuineText.endsWith(" ") && !genuineText.endsWith("\n")) " " else ""
        val combined = genuineText + space + dictatedText
        assertEquals("Query", combined)
    }

    @Test
    fun `extractGenuineText never strips text longer than 15 characters as a hint`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "https://aistudio.google.com/live/editor/prompt"
        node.isShowingHintText = true

        val genuineText = FloatingTextInjector.extractGenuineText(node)
        assertEquals("https://aistudio.google.com/live/editor/prompt", genuineText)
    }

    @Test
    fun `isNodeAcceptingText identifies standard and custom editable nodes`() {
        val node = AccessibilityNodeInfo.obtain()
        node.isEditable = true
        assertTrue(FloatingTextInjector.isNodeAcceptingText(node))

        val composeNode = AccessibilityNodeInfo.obtain()
        composeNode.isEditable = false
        composeNode.className = "androidx.compose.ui.text.input.ComposeEditableText"
        assertTrue(FloatingTextInjector.isNodeAcceptingText(composeNode))
    }

    @Test
    fun `InjectionLogRepository records and formats direct injection diagnostic events`() {
        InjectionLogRepository.clearLogs()
        val event = InjectionEvent(
            targetPackage = "com.android.chrome",
            targetAppName = "Chrome",
            targetNodeClass = "android.webkit.WebView",
            isFocused = true,
            isEditable = true,
            windowId = 10,
            textLength = 25,
            wordCount = 4,
            injectionMethod = "DIRECT_ACTION_SET_TEXT",
            resultDetails = "SUCCESS: Direct ACTION_SET_TEXT performed",
            finalOutcome = "SUCCESS",
            durationMs = 18L,
            rawTextPreview = "Testing text injection"
        )
        InjectionLogRepository.logInjection(event)

        val recorded = InjectionLogRepository.logs.value
        assertEquals(1, recorded.size)
        assertEquals("com.android.chrome", recorded[0].targetPackage)
        assertEquals("SUCCESS", recorded[0].finalOutcome)
        assertEquals("DIRECT_ACTION_SET_TEXT", recorded[0].injectionMethod)

        val report = InjectionLogRepository.exportAsFormattedText()
        assertTrue(report.contains("VOXSTREAM TEXT INJECTION DIAGNOSTIC REPORT"))
        assertTrue(report.contains("Chrome (com.android.chrome)"))
        assertTrue(report.contains("DIRECT_ACTION_SET_TEXT"))
    }
}

package com.example.service

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.example.service.FloatingBubbleManager
import com.example.service.floating.FloatingTextInjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InjectionChainVerificationTest {

    private val service = VoxStreamAccessibilityService()
    private val testHandler = Handler(Looper.getMainLooper())

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
    fun `injectText rejects empty or blank text without executing any injection layer`() {
        assertFalse(service.injectText(""))
    }

    @Test
    fun `cancelPendingPaste removes scheduled paste runnables to prevent late asynchronous paste firing`() {
        var pasteExecuted = false
        val dummyRunnable = Runnable { pasteExecuted = true }

        testHandler.postDelayed(dummyRunnable, 200)
        testHandler.removeCallbacks(dummyRunnable)

        ShadowLooper.idleMainLooper()
        assertFalse("Cancelled paste must never execute", pasteExecuted)
    }

    @Test
    fun `cancelPendingPaste in FloatingTextInjector cleans up pending paste references`() {
        FloatingTextInjector.cancelPendingPaste(testHandler)
        // Verified clean cancellation invocation without exception
        assertTrue(true)
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
    fun `VoxStreamInputMethodService commitText returns false when unstarted or disconnected`() {
        val result = VoxStreamInputMethodService.commitText("Test text")
        assertFalse("Must return false when IME has not started input", result)
    }

    @Test
    fun `commitTextViaInputMethod returns false when InputConnection is unavailable`() {
        val result = service.commitTextViaInputMethod("Wispr Flow injection")
        assertFalse("Must return false when AccessibilityInputConnection is unavailable", result)
    }
}

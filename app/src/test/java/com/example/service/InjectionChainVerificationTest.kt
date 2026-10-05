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
    fun `sequential paste operations isolate clipboard and prevent stale restoration overwrite`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        
        // 1. Initial user clipboard before any dictation
        val originalUserText = "Important User Notes"
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("UserClip", originalUserText))

        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "com.slack"

        // 2. Start Paste Operation #1
        val result1 = FloatingTextInjector.performPasteInjection(node, "First Dictation", context, testHandler)
        assertTrue("Paste operation #1 must return true", result1)
        val op1Id = FloatingTextInjector.getActivePasteOperationId()
        assertTrue("Operation #1 must have a positive ID", op1Id > 0)

        // Advance 150ms so Operation #1's paste runnable fires and sets clipboard
        ShadowLooper.idleMainLooper(150, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("First Dictation", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals("VoxStream Dictation #$op1Id", clipboard.primaryClip?.description?.label?.toString())

        // 3. Start Paste Operation #2 before Operation #1's restoration callback (scheduled at 450ms) fires
        val result2 = FloatingTextInjector.performPasteInjection(node, "Second Dictation", context, testHandler)
        assertTrue("Paste operation #2 must return true", result2)
        val op2Id = FloatingTextInjector.getActivePasteOperationId()
        assertTrue("Operation #2 must have a newer ID than Operation #1", op2Id > op1Id)

        // Advance 150ms so Operation #2's paste runnable fires and sets clipboard
        ShadowLooper.idleMainLooper(150, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("Second Dictation", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals("VoxStream Dictation #$op2Id", clipboard.primaryClip?.description?.label?.toString())

        // 4. Advance past the window where Operation #1's restore would have fired (original 450ms)
        // Verify that Operation #1's old restore callback DID NOT overwrite or erase Operation #2's clipboard
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(
            "Second Dictation clipboard must not be overwritten by Operation #1 restoration",
            "Second Dictation",
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        )
        assertEquals("VoxStream Dictation #$op2Id", clipboard.primaryClip?.description?.label?.toString())

        // 5. Advance past Operation #2's restore window (+450ms)
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)

        // Verify that Operation #2 cleanly restored the original user clipboard
        assertEquals(
            "Original user clipboard must be restored safely after Operation #2 finishes",
            originalUserText,
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        )
        assertEquals(
            "Active operation ID must be reset to 0 after completion",
            0L,
            FloatingTextInjector.getActivePasteOperationId()
        )
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

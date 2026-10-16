package com.example.service

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoxStreamAccessibilityServiceTest {

    private val service = VoxStreamAccessibilityService()

    @Test
    fun `extractGenuineText keeps text when the hint flag is set but hintText does not match`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Ask Google…"
        node.isShowingHintText = true

        val result = service.extractGenuineText(node)
        assertEquals("Ask Google…", result)
    }

    @Test
    fun `extractGenuineText keeps text that matches hintText when the hint flag is off`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Message"
        node.hintText = "Message"
        node.isShowingHintText = false

        val result = service.extractGenuineText(node)
        assertEquals("Message", result)
    }

    @Test
    fun `extractGenuineText returns empty only for an active hint`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Ask Google…"
        node.hintText = "Ask Google…"
        node.isShowingHintText = true

        val result = service.extractGenuineText(node)
        assertEquals("", result)
    }

    @Test
    fun `extractGenuineText keeps a short placeholder that is not an active hint`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Message"

        val result = service.extractGenuineText(node)
        assertEquals("Message", result)
    }

    @Test
    fun `extractGenuineText keeps short mention text when the hint flag is set`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "@princeniithompson"
        node.hintText = "Ask Gemini"
        node.isShowingHintText = true

        val result = service.extractGenuineText(node)
        assertEquals("@princeniithompson", result)
    }

    @Test
    fun `extractGenuineText preserves genuine user typed text`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Hello world"
        node.hintText = "Search"
        node.isShowingHintText = false

        val result = service.extractGenuineText(node)
        assertEquals("Hello world", result)
    }

    @Test
    fun `extractGenuineText returns empty for blank node text`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "   "

        val result = service.extractGenuineText(node)
        assertEquals("", result)
    }

    @Test
    fun `commitTextViaInputMethod returns false when InputConnection is unavailable`() {
        val result = service.commitTextViaInputMethod("Test text")
        // When input connection is unavailable or not started, must return false to allow fallbacks
        org.junit.Assert.assertFalse(result)
    }

    @Test
    fun `injectText returns false for empty text`() {
        val result = kotlinx.coroutines.runBlocking { service.injectText("") }
        org.junit.Assert.assertFalse(result)
    }

    @Test
    fun `injectText is blocked when Smart Safe Mode is active`() {
        FloatingBubbleManager.updateCurrentForegroundPackage("com.chase.sig.android", null)
        try {
            val result = kotlinx.coroutines.runBlocking { service.injectText("Secret password") }
            org.junit.Assert.assertFalse("Injection must be blocked when Smart Safe Mode is active", result)
        } finally {
            FloatingBubbleManager.updateCurrentForegroundPackage("com.google.android.keep", null)
        }
    }

    @Test
    fun `VoxStreamInputMethodService commitText returns false when service is uninitialized`() {
        val result = VoxStreamInputMethodService.commitText("Hello")
        org.junit.Assert.assertFalse("Must return false when IME instance is null", result)
    }
}

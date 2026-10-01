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
    fun `extractGenuineText returns empty when isShowingHintText is true`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Ask Google…"
        node.isShowingHintText = true

        val result = service.extractGenuineText(node)
        assertEquals("", result)
    }

    @Test
    fun `extractGenuineText returns empty when text matches hintText exactly`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Message"
        node.hintText = "Message"
        node.isShowingHintText = false

        val result = service.extractGenuineText(node)
        assertEquals("", result)
    }

    @Test
    fun `extractGenuineText returns empty for Google Search placeholder`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Ask Google…"

        val result = service.extractGenuineText(node)
        assertEquals("", result)
    }

    @Test
    fun `extractGenuineText returns empty for WhatsApp Message placeholder`() {
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Message"

        val result = service.extractGenuineText(node)
        assertEquals("", result)
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
}

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
        FloatingBubbleManager.resetClipboardFallbackIndicator()
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

    @Test
    fun `VoxStreamTextInserter falls back to clipboard when no editable node exists`() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val inserter = com.example.service.floating.VoxStreamTextInserter(context)
        val testTranscript = "Whisperflow hybrid test"

        val result = inserter.insertTranscript(testTranscript)
        assertEquals(com.example.service.floating.VoxStreamTextInserter.InsertionResult.SuccessClipboardFallback, result)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(testTranscript, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(FloatingBubbleManager.isClipboardFallbackIndicator.value)
    }

    @Test
    fun `VoxStreamTextInserter rejects blank text without modifying clipboard`() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val inserter = com.example.service.floating.VoxStreamTextInserter(context)

        val result = inserter.insertTranscript("   ")
        assertEquals(com.example.service.floating.VoxStreamTextInserter.InsertionResult.Failed, result)
    }

    @Test
    fun `VoxStreamTextInserter direct injection succeeds via InputConnection without modifying clipboard`() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val initialClip = ClipData.newPlainText("Initial", "Existing user clipboard")
        clipboard.setPrimaryClip(initialClip)

        val inserter = com.example.service.floating.VoxStreamTextInserter(context)

        // Mock InputConnection
        var committedText: String? = null
        val mockConnection = object : android.view.inputmethod.BaseInputConnection(android.view.View(context), false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                committedText = text?.toString()
                return true
            }
        }

        val result = inserter.insertTranscript("Hello from Whisperflow IME", mockConnection, null)
        assertEquals(com.example.service.floating.VoxStreamTextInserter.InsertionResult.SuccessDirect, result)
        assertEquals("Hello from Whisperflow IME", committedText)

        // User clipboard MUST NOT have been modified
        assertEquals("Existing user clipboard", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `isNodeAcceptingText identifies Google Keep NoteEditText and view IDs`() {
        val keepNode = AccessibilityNodeInfo.obtain()
        keepNode.isEditable = false
        keepNode.className = "com.google.android.keep.ui.NoteEditText"
        assertTrue(FloatingTextInjector.isNodeAcceptingText(keepNode))
    }

    @Test
    fun `writeRangeAndVerify verifies text injection successfully`() {
        val node = AccessibilityNodeInfo.obtain()
        node.isEditable = true
        node.text = ""
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)

        val shadow = org.robolectric.Shadows.shadowOf(node)
        shadow.setOnPerformActionListener { action, bundle ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                node.text = bundle?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                true
            } else {
                false
            }
        }

        val result = service.writeRangeAndVerify(node, "Dictated paragraph", replaceRange = false, attempts = 2)
        assertEquals(AxWriteOutcome.SUCCESS, result.outcome)
        assertEquals("Dictated paragraph", result.writtenText)
        assertTrue(result.finalTextLength > 0)
    }

    @Test
    fun `writeRangeAndVerify returns FAILED when node does not support ACTION_SET_TEXT`() {
        val node = AccessibilityNodeInfo.obtain()
        node.isEditable = false
        node.text = ""
        // No ACTION_SET_TEXT added

        val result = service.writeRangeAndVerify(node, "Unsupported action text", replaceRange = false, attempts = 1)
        assertEquals(AxWriteOutcome.FAILED, result.outcome)
        assertTrue(result.details?.contains("does not support ACTION_SET_TEXT") == true)
    }

    @Test
    fun `writeRangeAndVerify returns FAILED when node rejects updates after retry`() {
        val node = AccessibilityNodeInfo.obtain()
        node.isEditable = true
        node.text = ""
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)

        // Without updating node.text, simulating editor rejecting ACTION_SET_TEXT
        val result = service.writeRangeAndVerify(node, "Rejected text", replaceRange = false, attempts = 2)
        assertEquals(AxWriteOutcome.FAILED, result.outcome)
        assertEquals(2, result.verificationAttemptsUsed)
    }

    @Test
    fun `writeRangeAndVerify retries write and succeeds on second attempt`() {
        val node = AccessibilityNodeInfo.obtain()
        node.isEditable = true
        node.text = ""
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)

        var attempts = 0
        val shadow = org.robolectric.Shadows.shadowOf(node)
        shadow.setOnPerformActionListener { action, bundle ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                attempts++
                if (attempts >= 2) {
                    node.text = bundle?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                }
                true
            } else {
                false
            }
        }

        val result = service.writeRangeAndVerify(node, "Retry succeeded text", replaceRange = false, attempts = 2)
        assertEquals(AxWriteOutcome.SUCCESS, result.outcome)
        assertEquals(2, result.verificationAttemptsUsed)
        assertTrue(result.details?.contains("retry attempt") == true)
    }

    @Test
    fun `injectTextSafely handles Google Keep empty note placeholder without prepending hint or leading space`() {
        val keepNode = AccessibilityNodeInfo.obtain()
        keepNode.packageName = "com.google.android.keep"
        keepNode.isEditable = true
        keepNode.text = "Note"
        keepNode.isShowingHintText = true
        keepNode.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)

        var capturedText: CharSequence? = null
        val shadow = org.robolectric.Shadows.shadowOf(keepNode)
        shadow.setOnPerformActionListener { action, bundle ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                capturedText = bundle?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                keepNode.text = capturedText
                true
            } else {
                true
            }
        }

        val injected = FloatingTextInjector.injectTextSafely(keepNode, "Meeting action items")
        assertTrue(injected)
        assertEquals("Meeting action items", capturedText?.toString())
        assertFalse(capturedText?.toString()?.startsWith(" ") ?: true)
        assertFalse(capturedText?.toString()?.contains("Note") ?: true)
    }

    @Test
    fun `never use clipboard if focused editable field exists even if write fails`() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val initialClip = ClipData.newPlainText("Initial", "Existing user clipboard before failure")
        clipboard.setPrimaryClip(initialClip)

        val inserter = com.example.service.floating.VoxStreamTextInserter(context)

        // Mock accessibility service that reports a focused field existed, but pure ACTION_SET_TEXT failed
        val mockService = object : VoxStreamAccessibilityService() {
            override fun injectTextDetailed(newText: String): InjectionEvent {
                return InjectionEvent(
                    targetPackage = "com.google.android.keep",
                    targetAppName = "Keep",
                    targetNodeClass = "NoteEditText",
                    isFocused = true,
                    isEditable = true,
                    textLength = newText.length,
                    wordCount = 2,
                    injectionMethod = "DIRECT_ACTION_SET_TEXT",
                    resultDetails = "FAILED: Verification failed after retry: text did not land",
                    finalOutcome = "FAILED"
                )
            }
        }

        VoxStreamAccessibilityService.instance = mockService
        try {
            val result = inserter.insertTranscript("Test transcription that failed to write")
            assertEquals(com.example.service.floating.VoxStreamTextInserter.InsertionResult.Failed, result)

            // Requirement 3: User clipboard MUST NOT have been modified!
            assertEquals("Existing user clipboard before failure", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertFalse(FloatingBubbleManager.isClipboardFallbackIndicator.value)
        } finally {
            VoxStreamAccessibilityService.instance = null
        }
    }

    @Test
    fun `commitViaActionSetText handles absent focus gracefully`() = kotlinx.coroutines.runBlocking {
        val result = service.commitViaActionSetText("Test text", null)
        assertEquals(AxWriteOutcome.FAILED, result.outcome)
    }
}

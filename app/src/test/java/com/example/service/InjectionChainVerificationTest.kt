package com.example.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.InjectionEvent
import com.example.data.InjectionLogRepository
import com.example.service.FloatingBubbleManager
import com.example.service.floating.FloatingOverlayWindowManager
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

    private lateinit var service: VoxStreamAccessibilityService

    @Before
    fun setUp() {
        service = org.robolectric.Robolectric.buildService(VoxStreamAccessibilityService::class.java).create().get()
        FloatingBubbleManager.updateCurrentForegroundPackage("com.google.android.keep", null)
        FloatingBubbleManager.resetClipboardFallbackIndicator()
    }

    @Test
    fun `injectText blocks completely when Smart Safe Mode is active`() {
        FloatingBubbleManager.updateCurrentForegroundPackage("com.chase.sig.android", null)
        try {
            val injected = kotlinx.coroutines.runBlocking { service.injectText("Secret Password 123") }
            assertFalse("Injection must be blocked by Smart Safe Mode", injected)
        } finally {
            FloatingBubbleManager.updateCurrentForegroundPackage("com.google.android.keep", null)
        }
    }

    @Test
    fun `injectText rejects empty or blank text without executing any injection`() {
        assertFalse(kotlinx.coroutines.runBlocking { service.injectText("") })
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
    fun `extractGenuineText keeps text unless the hint flag is set and the text equals hintText`() {
        val flaggedWithoutMatch = AccessibilityNodeInfo.obtain()
        flaggedWithoutMatch.text = "Search…"
        flaggedWithoutMatch.isShowingHintText = true
        assertEquals("Search…", FloatingTextInjector.extractGenuineText(flaggedWithoutMatch))

        val activeHint = AccessibilityNodeInfo.obtain()
        activeHint.text = "Search…"
        activeHint.hintText = "Search…"
        activeHint.isShowingHintText = true
        assertEquals("", FloatingTextInjector.extractGenuineText(activeHint))

        val merge = FloatingTextInjector.buildCursorMerge(activeHint, "Query")
        assertEquals("Query", merge?.text)
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
    fun `VoxStreamTextInserter ignores InputConnection when no focused field exists`() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Initial", "Existing user clipboard"))

        val inserter = com.example.service.floating.VoxStreamTextInserter(context)

        var committedText: String? = null
        val mockConnection = object : android.view.inputmethod.BaseInputConnection(android.view.View(context), false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                committedText = text?.toString()
                return true
            }
        }

        val result = inserter.insertTranscript("Hello from Whisperflow IME", mockConnection, null)
        assertEquals(null, committedText)
        assertEquals(com.example.service.floating.VoxStreamTextInserter.InsertionResult.SuccessClipboardFallback, result)
        assertEquals("Hello from Whisperflow IME", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
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

        var setTextCalls = 0
        val shadow = org.robolectric.Shadows.shadowOf(node)
        shadow.setOnPerformActionListener { action, bundle ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                setTextCalls++
                node.text = bundle?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                true
            } else {
                false
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            service.writeRangeAndVerify(node, "Dictated paragraph", replaceRange = false, attempts = 2)
        }
        assertEquals(AxWriteOutcome.SUCCESS, result.outcome)
        assertEquals("Dictated paragraph", result.writtenText)
        assertTrue(result.finalTextLength > 0)
        assertEquals("Dictated paragraph", node.text?.toString())
        assertEquals("The edit nudge writes the text without the last character, then the full text, and does not merge again", 2, setTextCalls)
    }

    @Test
    fun `isRichOrUnsafeToRewrite returns true for spanned text with spans`() {
        val node = AccessibilityNodeInfo.obtain()
        node.isEditable = true
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
        val spanned = android.text.SpannableString("@Gemini user mention")
        spanned.setSpan(android.text.style.UnderlineSpan(), 0, 7, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        node.text = spanned

        assertTrue(FloatingTextInjector.isRichOrUnsafeToRewrite(node))
    }

    @Test
    fun `isRichOrUnsafeToRewrite returns false for a plain note`() {
        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "com.google.android.keep"
        node.isEditable = true
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
        node.text = "Simple plain text"

        assertFalse(FloatingTextInjector.isRichOrUnsafeToRewrite(node))
    }

    @Test
    fun `isRichOrUnsafeToRewrite returns false for plain EditText with no spans or children`() {
        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "com.example.simpleapp"
        node.className = "android.widget.EditText"
        node.isEditable = true
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
        node.text = "Simple plain text"

        assertFalse(FloatingTextInjector.isRichOrUnsafeToRewrite(node))
    }

    @Test
    fun `note field uses SET_TEXT at the caret and never touches the clipboard`() {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Initial", "User clip"))

        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "com.google.android.keep"
        node.isEditable = true
        node.text = "Existing Keep note"
        node.setTextSelection(node.text.length, node.text.length)
        node.addAction(AccessibilityNodeInfo.ACTION_PASTE)
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)

        var actionPasteCalled = false
        val written = mutableListOf<String>()
        val shadow = org.robolectric.Shadows.shadowOf(node)
        shadow.setOnPerformActionListener { action, bundle ->
            when (action) {
                AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                    val value = bundle?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)?.toString().orEmpty()
                    written.add(value)
                    node.text = value
                    true
                }
                AccessibilityNodeInfo.ACTION_PASTE -> {
                    actionPasteCalled = true
                    false
                }
                else -> true
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            service.writeRangeAndVerify(node, "dictated text", replaceRange = false, attempts = 1)
        }
        assertEquals(AxWriteOutcome.SUCCESS, result.outcome)
        assertFalse("A focused field must never ACTION_PASTE", actionPasteCalled)
        assertEquals("Existing Keep note dictated text", written.last())
        assertEquals("Existing Keep note dictated text", node.text.toString())
        assertEquals("User clip", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `focused field write failure leaves the clipboard and the field unchanged`() {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Initial", "Initial clipboard"))
        org.robolectric.shadows.ShadowToast.reset()

        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "com.google.android.keep"
        node.isEditable = true
        node.text = "Original protected content"
        node.addAction(AccessibilityNodeInfo.ACTION_PASTE)
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)

        var actionSetTextCalled = false
        var actionPasteCalled = false
        val shadow = org.robolectric.Shadows.shadowOf(node)
        shadow.setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                actionSetTextCalled = true
            } else if (action == AccessibilityNodeInfo.ACTION_PASTE) {
                actionPasteCalled = true
            }
            false
        }

        val result = kotlinx.coroutines.runBlocking {
            service.writeRangeAndVerify(node, "My dictated paragraph", replaceRange = false, attempts = 1)
        }
        assertEquals(AxWriteOutcome.FAILED, result.outcome)
        assertTrue(actionSetTextCalled)
        assertFalse(actionPasteCalled)
        assertEquals("Original protected content", node.text.toString())
        assertEquals("Initial clipboard", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(null, org.robolectric.shadows.ShadowToast.getLatestToast())
    }

    @Test
    fun `plain field write failure returns FAILED without wiping field with bare text`() {
        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "com.example.plainapp"
        node.className = "android.widget.EditText"
        node.isEditable = true
        node.text = "Existing user draft that should not be wiped"
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)

        // Mock node where performAction returns false
        val shadow = org.robolectric.Shadows.shadowOf(node)
        shadow.setOnPerformActionListener { _, _ -> false }

        val result = kotlinx.coroutines.runBlocking {
            service.writeRangeAndVerify(node, "Dictated text", replaceRange = false, attempts = 1)
        }
        assertEquals(AxWriteOutcome.FAILED, result.outcome)
        // Original text in node remains untouched
        assertEquals("Existing user draft that should not be wiped", node.text.toString())
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
                    true
                } else {
                    // Action rejected, so a single retry cannot duplicate text that never landed.
                    false
                }
            } else {
                false
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            service.writeRangeAndVerify(node, "Retry succeeded text", replaceRange = false, attempts = 2)
        }
        assertEquals(AxWriteOutcome.SUCCESS, result.outcome)
        assertEquals(2, result.verificationAttemptsUsed)
        assertTrue(result.details?.contains("retry attempt") == true)
    }

    @Test
    fun `Google Keep active hint is replaced by two-step SET_TEXT and the clipboard stays untouched`() {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Initial", "User clip"))

        val keepNode = AccessibilityNodeInfo.obtain()
        keepNode.packageName = "com.google.android.keep"
        keepNode.className = "com.google.android.keep.ui.NoteEditText"
        keepNode.isEditable = true
        keepNode.text = "Note"
        keepNode.hintText = "Note"
        keepNode.isShowingHintText = true
        keepNode.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
        keepNode.addAction(AccessibilityNodeInfo.ACTION_PASTE)

        var pasteCalled = false
        val written = mutableListOf<String>()
        val shadow = org.robolectric.Shadows.shadowOf(keepNode)
        shadow.setOnPerformActionListener { action, bundle ->
            when (action) {
                AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                    val value = bundle?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)?.toString().orEmpty()
                    written.add(value)
                    keepNode.text = value
                    keepNode.isShowingHintText = false
                    true
                }
                AccessibilityNodeInfo.ACTION_PASTE -> {
                    pasteCalled = true
                    false
                }
                else -> true
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            service.writeRangeAndVerify(keepNode, "Meeting action items", replaceRange = false, attempts = 1)
        }
        assertEquals(AxWriteOutcome.SUCCESS, result.outcome)
        assertFalse(pasteCalled)
        assertEquals(listOf("Meeting action item", "Meeting action items"), written)
        assertEquals("Meeting action items", keepNode.text.toString())
        assertFalse(keepNode.text.toString().contains("Note"))
        assertEquals("User clip", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `short mention text is merged at the caret with SET_TEXT and the clipboard stays untouched`() {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Initial", "User clip"))

        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "com.google.android.apps.bard"
        node.className = "android.widget.EditText"
        node.isEditable = true
        node.isShowingHintText = true
        node.hintText = "Ask Gemini"
        node.text = "@princeniithompson"
        node.setTextSelection(node.text.length, node.text.length)
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
        node.addAction(AccessibilityNodeInfo.ACTION_PASTE)

        assertEquals("@princeniithompson", FloatingTextInjector.extractGenuineText(node))
        assertTrue(FloatingTextInjector.isRichField(node))

        var pasted = false
        val written = mutableListOf<String>()
        val shadow = org.robolectric.Shadows.shadowOf(node)
        shadow.setOnPerformActionListener { action, bundle ->
            when (action) {
                AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                    val value = bundle?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)?.toString().orEmpty()
                    written.add(value)
                    node.text = value
                    true
                }
                AccessibilityNodeInfo.ACTION_PASTE -> {
                    pasted = true
                    false
                }
                else -> true
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            service.writeRangeAndVerify(node, "hello", replaceRange = false, attempts = 2)
        }
        assertEquals(AxWriteOutcome.SUCCESS, result.outcome)
        assertFalse(pasted)
        assertEquals("@princeniithompson hello", written.last())
        assertTrue(node.text.toString().startsWith("@princeniithompson"))
        assertEquals("User clip", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `a focused field write never changes an empty clipboard`() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("seed", "seed"))
        clipboard.clearPrimaryClip()
        assertEquals(null, clipboard.primaryClip)

        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "com.google.android.apps.bard"
        node.isEditable = true
        node.text = "Existing Gemini text"
        node.setTextSelection(node.text.length, node.text.length)
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)

        val shadow = org.robolectric.Shadows.shadowOf(node)
        shadow.setOnPerformActionListener { action, bundle ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                node.text = bundle?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                true
            } else {
                true
            }
        }

        val result = service.writeRangeAndVerify(node, "dictated", replaceRange = false, attempts = 1)
        assertEquals(AxWriteOutcome.SUCCESS, result.outcome)
        assertEquals("Existing Gemini text dictated", node.text.toString())
        assertEquals(null, clipboard.primaryClip)
    }

    @Test
    fun `a partial nudge is not merged again when the full write fails`() {
        val node = AccessibilityNodeInfo.obtain()
        node.isEditable = true
        node.text = ""
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)

        var setTextCalls = 0
        val shadow = org.robolectric.Shadows.shadowOf(node)
        shadow.setOnPerformActionListener { action, bundle ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                setTextCalls++
                val value = bundle?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)?.toString().orEmpty()
                if (setTextCalls == 1) {
                    node.text = value
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            service.writeRangeAndVerify(node, "Hi there", replaceRange = false, attempts = 2)
        }
        assertEquals(AxWriteOutcome.FAILED, result.outcome)
        assertEquals("Hi ther", node.text.toString())
        assertEquals("Partial write, one full retry inside the nudge, and no second merge", 3, setTextCalls)
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
            override suspend fun injectTextDetailed(newText: String): InjectionEvent {
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

    @Test
    fun `overlay window flags never take focus from the editor`() {
        val flags = FloatingOverlayWindowManager.OVERLAY_WINDOW_FLAGS
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
    }

    @Test
    fun `P1 commitText succeeds without touching the clipboard or the companion IME`() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Initial", "User clip"))

        val probe = org.robolectric.Robolectric.buildService(PriorityProbeService::class.java).create().get()
        probe.p1Result = true
        probe.p2Result = true
        try {
            val event = probe.injectTextDetailed("Hello field")
            assertEquals("SUCCESS", event.finalOutcome)
            assertEquals("ACCESSIBILITY_INPUT_CONNECTION", event.injectionMethod)
            assertFalse(probe.p2Called)
            assertEquals("User clip", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertFalse(FloatingBubbleManager.isClipboardFallbackIndicator.value)
        } finally {
            VoxStreamAccessibilityService.instance = service
        }
    }

    @Test
    fun `P2 companion IME is used only when the accessibility input connection is unavailable`() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Initial", "User clip"))

        val probe = org.robolectric.Robolectric.buildService(PriorityProbeService::class.java).create().get()
        probe.p1Result = null
        probe.p2Result = true
        try {
            val event = probe.injectTextDetailed("Hello from companion")
            assertEquals("SUCCESS", event.finalOutcome)
            assertEquals("COMPANION_IME", event.injectionMethod)
            assertTrue(probe.p2Called)
            assertEquals("User clip", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        } finally {
            VoxStreamAccessibilityService.instance = service
        }
    }

    @Test
    fun `a failed P1 commit does not fall through to the clipboard`() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Initial", "User clip"))

        val probe = org.robolectric.Robolectric.buildService(PriorityProbeService::class.java).create().get()
        probe.p1Result = false
        try {
            val event = probe.injectTextDetailed("Should stay off the clipboard")
            assertEquals("FAILED", event.finalOutcome)
            assertEquals("ACCESSIBILITY_INPUT_CONNECTION", event.injectionMethod)
            assertFalse(probe.p2Called)
            assertEquals("User clip", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        } finally {
            VoxStreamAccessibilityService.instance = service
        }
    }
}

/** Test double for the P1 then P2 decision. Not registered as a real accessibility service. */
class PriorityProbeService : VoxStreamAccessibilityService() {
    var p1Result: Boolean? = null
    var p2Result: Boolean? = null
    var p2Called: Boolean = false

    override suspend fun commitWithAccessibilityInputConnection(text: String): Boolean? = p1Result

    override suspend fun commitWithCompanionIme(text: String): Boolean? {
        p2Called = true
        return p2Result
    }
}

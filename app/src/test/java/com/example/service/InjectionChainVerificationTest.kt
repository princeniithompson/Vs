package com.example.service

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.InjectionEvent
import com.example.data.InjectionLogRepository
import com.example.service.FloatingBubbleManager
import com.example.service.floating.FloatingTextInjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun idleClipboardRestore() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(VoxStreamAccessibilityService.CLIPBOARD_RESTORE_DELAY_MS + 30))
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
        assertEquals("A successful ACTION_SET_TEXT must not be written again", 1, setTextCalls)
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
    fun `isRichOrUnsafeToRewrite returns true for note app package`() {
        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "com.google.android.keep"
        node.isEditable = true
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
        node.text = "Simple plain text"

        assertTrue(FloatingTextInjector.isRichOrUnsafeToRewrite(node))
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
    fun `rich field uses PASTE path and never executes ACTION_SET_TEXT`() {
        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "com.google.android.keep"
        node.isEditable = true
        node.text = "Existing Keep note"
        node.addAction(AccessibilityNodeInfo.ACTION_PASTE)
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)

        var actionSetTextCalled = false
        var actionPasteCalled = false

        val shadow = org.robolectric.Shadows.shadowOf(node)
        shadow.setOnPerformActionListener { action, _ ->
            when (action) {
                AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                    actionSetTextCalled = true
                    true
                }
                AccessibilityNodeInfo.ACTION_PASTE -> {
                    actionPasteCalled = true
                    node.text = "Existing Keep note dictated text"
                    true
                }
                else -> true
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            service.writeRangeAndVerify(node, "dictated text", replaceRange = false, attempts = 1)
        }
        assertEquals(AxWriteOutcome.SUCCESS, result.outcome)
        assertTrue("ACTION_PASTE must be invoked for rich/note fields", actionPasteCalled)
        assertFalse("ACTION_SET_TEXT must NEVER be called on rich/note fields", actionSetTextCalled)
    }

    @Test
    fun `rich field paste failure restores the previous clipboard and never calls ACTION_SET_TEXT`() {
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
        var tempClipText: String? = null
        var tempClipSensitive = false
        val shadow = org.robolectric.Shadows.shadowOf(node)
        shadow.setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                actionSetTextCalled = true
                true
            } else if (action == AccessibilityNodeInfo.ACTION_PASTE) {
                val temp = clipboard.primaryClip
                tempClipText = temp?.getItemAt(0)?.text?.toString()
                tempClipSensitive = temp?.description?.extras
                    ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
                false
            } else {
                true
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            service.writeRangeAndVerify(node, "My dictated paragraph", replaceRange = false, attempts = 1)
        }
        assertEquals(AxWriteOutcome.FAILED, result.outcome)
        assertFalse("ACTION_SET_TEXT must NEVER be used as a fallback on rich fields", actionSetTextCalled)
        assertEquals("Original protected content", node.text.toString())
        assertEquals("My dictated paragraph", tempClipText)
        assertFalse("Temporary paste clip must not be marked sensitive", tempClipSensitive)
        idleClipboardRestore()
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
    fun `Google Keep placeholder is inserted with PASTE and never ACTION_SET_TEXT`() {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Initial", "User clip"))

        val keepNode = AccessibilityNodeInfo.obtain()
        keepNode.packageName = "com.google.android.keep"
        keepNode.className = "com.google.android.keep.ui.NoteEditText"
        keepNode.isEditable = true
        keepNode.text = "Note"
        keepNode.isShowingHintText = true
        keepNode.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
        keepNode.addAction(AccessibilityNodeInfo.ACTION_PASTE)

        var setTextCalled = false
        var pastedText: String? = null
        val shadow = org.robolectric.Shadows.shadowOf(keepNode)
        shadow.setOnPerformActionListener { action, _ ->
            when (action) {
                AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                    setTextCalled = true
                    true
                }
                AccessibilityNodeInfo.ACTION_PASTE -> {
                    pastedText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                    keepNode.text = pastedText
                    true
                }
                else -> true
            }
        }

        assertFalse(FloatingTextInjector.injectTextSafely(keepNode, "Meeting action items"))
        assertFalse(setTextCalled)

        val result = kotlinx.coroutines.runBlocking {
            service.writeRangeAndVerify(keepNode, "Meeting action items", replaceRange = false, attempts = 1)
        }
        assertEquals(AxWriteOutcome.SUCCESS, result.outcome)
        assertEquals("Meeting action items", pastedText)
        assertFalse(pastedText?.startsWith(" ") ?: true)
        assertFalse(pastedText?.contains("Note") ?: true)
        assertFalse("Keep must not be rewritten with ACTION_SET_TEXT", setTextCalled)
        idleClipboardRestore()
        assertEquals("User clip", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `short mention text is inserted at the caret and not rewritten`() {
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
        assertFalse(FloatingTextInjector.canSafelyRewriteWithSetText(node))

        var setTextArg: CharSequence? = null
        var pasted = false
        val shadow = org.robolectric.Shadows.shadowOf(node)
        shadow.setOnPerformActionListener { action, bundle ->
            when (action) {
                AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                    setTextArg = bundle?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                    true
                }
                AccessibilityNodeInfo.ACTION_PASTE -> {
                    pasted = true
                    node.text = "@princeniithompson hello"
                    true
                }
                else -> true
            }
        }

        val result = kotlinx.coroutines.runBlocking {
            service.writeRangeAndVerify(node, "hello", replaceRange = false, attempts = 2)
        }
        assertEquals(AxWriteOutcome.SUCCESS, result.outcome)
        assertTrue(pasted)
        assertEquals(null, setTextArg)
        assertTrue(node.text.toString().startsWith("@princeniithompson"))
        idleClipboardRestore()
        assertEquals("User clip", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `paste clears the clipboard when the user had no previous clip`() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        }

        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "com.google.android.keep"
        node.isEditable = true
        node.text = "Existing Keep note"
        node.addAction(AccessibilityNodeInfo.ACTION_PASTE)
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)

        val shadow = org.robolectric.Shadows.shadowOf(node)
        shadow.setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_PASTE) {
                node.text = "Existing Keep note dictated"
                true
            } else {
                true
            }
        }

        val result = service.writeRangeAndVerify(node, "dictated", replaceRange = false, attempts = 1)
        assertEquals(AxWriteOutcome.SUCCESS, result.outcome)
        assertEquals("dictated", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        idleClipboardRestore()
        assertNull(clipboard.primaryClip)
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
}

package com.example.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Temporary, in-memory context data captured from an AI application screen.
 * Strictly stored in RAM — never persisted to disk or flash storage.
 */
data class CapturedScreenContext(
    val packageName: String,
    val appName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val conversationSnippets: List<String> = emptyList(),
    val systemInstructions: String? = null,
    val focusedInputText: String? = null
) {
    /**
     * Formats the captured screen context into a clean, structured prompt section
     * ready to be injected into AI polish operations.
     */
    fun toPromptContext(): String {
        return buildString {
            append("Screen Context from $appName:\n")
            if (!systemInstructions.isNullOrBlank()) {
                append("Visible System/Instructions: $systemInstructions\n")
            }
            if (conversationSnippets.isNotEmpty()) {
                append("Recent Chat History:\n")
                conversationSnippets.takeLast(8).forEach { snippet ->
                    append("- $snippet\n")
                }
            }
            if (!focusedInputText.isNullOrBlank()) {
                append("Current Draft in Input Field: \"$focusedInputText\"\n")
            }
        }.trim()
    }
}

/**
 * In-memory repository for holding AI-app screen context.
 * Automatically cleared when a dictation session completes or when a new scan occurs.
 */
object ScreenContextRepository {

    private val _capturedContext = MutableStateFlow<CapturedScreenContext?>(null)
    val capturedContext: StateFlow<CapturedScreenContext?> = _capturedContext.asStateFlow()

    private val _isContextLoaded = MutableStateFlow(false)
    val isContextLoaded: StateFlow<Boolean> = _isContextLoaded.asStateFlow()

    fun setContext(context: CapturedScreenContext) {
        _capturedContext.value = context
        _isContextLoaded.value = true
        AppLogRepository.addLog(
            LogLevel.INFO,
            "ScreenContextRepo",
            "AI Screen Context captured from ${context.appName} (${context.conversationSnippets.size} messages)"
        )
    }

    fun clearContext() {
        if (_isContextLoaded.value) {
            _capturedContext.value = null
            _isContextLoaded.value = false
            AppLogRepository.addLog(
                LogLevel.INFO,
                "ScreenContextRepo",
                "AI Screen Context cleared from memory"
            )
        }
    }
}

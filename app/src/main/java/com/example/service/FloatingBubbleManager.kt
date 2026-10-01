package com.example.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates communication between:
 * - The UI / Settings
 * - VoxStreamAccessibilityService (detects text field focus & injects text)
 * - FloatingBubbleService (renders the floating 🛟 overlay & handles dictation)
 */
object FloatingBubbleManager {
    private const val TAG = "FloatingBubbleManager"
    private const val PREFS_NAME = "voxstream_bubble_prefs"
    private const val KEY_BUBBLE_ENABLED = "bubble_enabled"
    private const val KEY_GLOW_STYLE = "glow_animation_style"
    private const val KEY_FINISHING_STYLE = "finishing_animation_style"
    private const val KEY_COLOR_TONE = "dynamic_color_tone"
    private const val KEY_SMART_SAFE_MODE = "smart_safe_mode_enabled"
    const val DEFAULT_GLOW_STYLE_ID = "breathing_horizon"
    const val DEFAULT_FINISHING_STYLE_ID = "pixel_flourish"
    const val DEFAULT_COLOR_TONE = "luminous"

    private val _isBubbleEnabled = MutableStateFlow(false)
    val isBubbleEnabled: StateFlow<Boolean> = _isBubbleEnabled.asStateFlow()

    private val _isSmartSafeModeEnabled = MutableStateFlow(true)
    val isSmartSafeModeEnabled: StateFlow<Boolean> = _isSmartSafeModeEnabled.asStateFlow()

    private val _isCurrentAppSensitive = MutableStateFlow(false)
    val isCurrentAppSensitive: StateFlow<Boolean> = _isCurrentAppSensitive.asStateFlow()

    private val _selectedGlowStyleId = MutableStateFlow(DEFAULT_GLOW_STYLE_ID)
    val selectedGlowStyleId: StateFlow<String> = _selectedGlowStyleId.asStateFlow()

    private val _selectedFinishingStyleId = MutableStateFlow(DEFAULT_FINISHING_STYLE_ID)
    val selectedFinishingStyleId: StateFlow<String> = _selectedFinishingStyleId.asStateFlow()

    private val _selectedColorTone = MutableStateFlow(DEFAULT_COLOR_TONE)
    val selectedColorTone: StateFlow<String> = _selectedColorTone.asStateFlow()

    // Foreground package tracking & session locking:
    private val _currentForegroundPackage = MutableStateFlow<String?>(null)
    val currentForegroundPackage: StateFlow<String?> = _currentForegroundPackage.asStateFlow()

    private val _lockedSessionContext = MutableStateFlow<String?>(null)
    val lockedSessionContext: StateFlow<String?> = _lockedSessionContext.asStateFlow()

    private val _selectedAiPolishMode = MutableStateFlow(com.example.service.floating.AiPolishMode.CLEAN_MESSAGE)
    val selectedAiPolishMode: StateFlow<com.example.service.floating.AiPolishMode> = _selectedAiPolishMode.asStateFlow()

    private val _isCurrentAppAi = MutableStateFlow(false)
    val isCurrentAppAi: StateFlow<Boolean> = _isCurrentAppAi.asStateFlow()

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _isFieldActive = MutableStateFlow(false)
    val isFieldActive: StateFlow<Boolean> = _isFieldActive.asStateFlow()

    private val _isKeyboardVisible = MutableStateFlow(false)
    val isKeyboardVisible: StateFlow<Boolean> = _isKeyboardVisible.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isBubbleEnabled.value = prefs.getBoolean(KEY_BUBBLE_ENABLED, false)

        // Validate saved style; if previously set to one of the removed styles, automatically reset to Breathing Horizon
        val savedGlowStyle = prefs.getString(KEY_GLOW_STYLE, DEFAULT_GLOW_STYLE_ID) ?: DEFAULT_GLOW_STYLE_ID
        val validatedGlowStyle = if (com.example.ui.components.GlowAnimationCatalogue.styles.any { it.id == savedGlowStyle }) {
            savedGlowStyle
        } else {
            prefs.edit().putString(KEY_GLOW_STYLE, DEFAULT_GLOW_STYLE_ID).apply()
            DEFAULT_GLOW_STYLE_ID
        }
        _selectedGlowStyleId.value = validatedGlowStyle

        _selectedFinishingStyleId.value = prefs.getString(KEY_FINISHING_STYLE, DEFAULT_FINISHING_STYLE_ID) ?: DEFAULT_FINISHING_STYLE_ID
        _selectedColorTone.value = prefs.getString(KEY_COLOR_TONE, DEFAULT_COLOR_TONE) ?: DEFAULT_COLOR_TONE
        _isSmartSafeModeEnabled.value = prefs.getBoolean(KEY_SMART_SAFE_MODE, true)
        initialized = true

        if (_isBubbleEnabled.value && canDrawOverlays(context)) {
            startBubbleService(context)
        }
    }

    fun setColorTone(context: Context, toneId: String) {
        _selectedColorTone.value = toneId
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COLOR_TONE, toneId).apply()
    }

    fun setGlowStyle(context: Context, styleId: String) {
        val validStyle = if (com.example.ui.components.GlowAnimationCatalogue.styles.any { it.id == styleId }) {
            styleId
        } else {
            DEFAULT_GLOW_STYLE_ID
        }
        _selectedGlowStyleId.value = validStyle
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GLOW_STYLE, validStyle).apply()
    }

    fun setFinishingStyle(context: Context, styleId: String) {
        _selectedFinishingStyleId.value = styleId
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FINISHING_STYLE, styleId).apply()
    }

    fun setSmartSafeModeEnabled(context: Context, enabled: Boolean) {
        _isSmartSafeModeEnabled.value = enabled
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SMART_SAFE_MODE, enabled).apply()

        // Re-evaluate current package sensitivity with new setting
        val currentPkg = _currentForegroundPackage.value
        val isSensitive = if (enabled) {
            SafeModeClassifier.isSensitiveApp(context, currentPkg)
        } else {
            false
        }
        _isCurrentAppSensitive.value = isSensitive
        if (isSensitive) {
            FloatingBubbleService.instance?.onSensitiveAppEntered(currentPkg)
        }
    }

    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun setBubbleEnabled(context: Context, enabled: Boolean) {
        _isBubbleEnabled.value = enabled
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply()

        if (enabled) {
            if (canDrawOverlays(context)) {
                startBubbleService(context)
            }
        } else {
            stopBubbleService(context)
        }
    }

    fun startBubbleService(context: Context) {
        try {
            val intent = Intent(context, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FloatingBubbleService", e)
        }
    }

    fun stopBubbleService(context: Context) {
        try {
            val intent = Intent(context, FloatingBubbleService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop FloatingBubbleService", e)
        }
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _isAccessibilityConnected.value = connected
    }

    fun notifyFieldFocus(isFocused: Boolean) {
        _isFieldActive.value = isFocused
        FloatingBubbleService.instance?.onFieldFocusChanged(isFocused)
    }

    fun notifyKeyboardVisibility(visible: Boolean) {
        _isKeyboardVisible.value = visible
        Log.d(TAG, "notifyKeyboardVisibility: $visible")
        FloatingBubbleService.instance?.onKeyboardVisibilityChanged(visible)
    }

    fun setRecordingState(recording: Boolean) {
        _isRecording.value = recording
    }

    fun updateCurrentForegroundPackage(pkg: String?, context: Context? = null) {
        if (!AppContextResolver.isIgnoredPackage(context, pkg)) {
            _currentForegroundPackage.value = pkg
            _isCurrentAppAi.value = AppClassifier.isAiChatApp(pkg)

            val isSensitive = if (_isSmartSafeModeEnabled.value) {
                SafeModeClassifier.isSensitiveApp(context, pkg)
            } else {
                false
            }
            val wasSensitive = _isCurrentAppSensitive.value
            _isCurrentAppSensitive.value = isSensitive
            if (isSensitive && !wasSensitive) {
                FloatingBubbleService.instance?.onSensitiveAppEntered(pkg)
            }
        }
    }

    fun setAiPolishMode(mode: com.example.service.floating.AiPolishMode) {
        _selectedAiPolishMode.value = mode
    }

    /**
     * Smart Default Heuristic:
     * - Longer input (>= 12 words or >= 60 chars) or prompt-intent keywords -> OPTIMIZE_PROMPT
     * - Shorter / conversational follow-up -> CLEAN_MESSAGE
     */
    fun updateSmartDefaultPolishMode(transcript: String) {
        if (transcript.isBlank()) return
        val wordCount = transcript.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val charCount = transcript.length
        val lower = transcript.lowercase()
        val hasTaskIntent = listOf(
            "build", "create", "write code", "analyze", "draft", "explain", "research",
            "how to", "generate", "code", "design", "compose", "optimize", "rewrite",
            "refactor", "develop", "implement", "solve", "compare", "evaluate"
        ).any { lower.contains(it) }

        if (wordCount >= 12 || charCount >= 60 || hasTaskIntent) {
            _selectedAiPolishMode.value = com.example.service.floating.AiPolishMode.OPTIMIZE_PROMPT
        } else {
            _selectedAiPolishMode.value = com.example.service.floating.AiPolishMode.CLEAN_MESSAGE
        }
    }

    /**
     * Locks the detected foreground app + group for the entire duration of the dictation session.
     * Even if the user switches apps, the locked context remains unchanged until unlockSessionContext() is called.
     */
    fun lockSessionContext(context: Context) {
        if (_lockedSessionContext.value == null) {
            val a11y = VoxStreamAccessibilityService.instance
            val currentPkg = a11y?.getActivePackageName()
                ?: _currentForegroundPackage.value
            val activeWindow = a11y?.getActiveApplicationWindow()
            val rootNode = a11y?.rootInActiveWindow
            val resolved = AppContextResolver.resolve(
                context = context,
                packageName = currentPkg,
                windowInfo = activeWindow,
                rootNode = rootNode
            )
            _lockedSessionContext.value = resolved?.formatted
            Log.d(TAG, "Locked session context: ${resolved?.formatted} for package: $currentPkg")
        }
    }

    /**
     * Releases the session lock when the dictation session completes, is cancelled, or is dismissed.
     */
    fun unlockSessionContext() {
        _lockedSessionContext.value = null
        Log.d(TAG, "Unlocked session context")
    }

    /**
     * Attempts to inject text into the currently active text field via AccessibilityService.
     * If no active text field exists or injection fails, copies text to clipboard with a Toast.
     */
    fun injectOrFallbackToClipboard(context: Context, text: String): Boolean {
        if (text.isBlank()) return false

        val injected = VoxStreamAccessibilityService.instance?.injectText(text) ?: false
        val mainHandler = Handler(Looper.getMainLooper())

        if (injected) {
            mainHandler.post {
                Toast.makeText(context, "Text inserted into active field!", Toast.LENGTH_SHORT).show()
            }
            return true
        } else {
            // Fallback to clipboard
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                val clip = ClipData.newPlainText("VoxStream Transcription", text)
                clipboard.setPrimaryClip(clip)
            }
            mainHandler.post {
                Toast.makeText(
                    context,
                    "Copied to clipboard (no active text field found)",
                    Toast.LENGTH_LONG
                ).show()
            }
            return false
        }
    }
}

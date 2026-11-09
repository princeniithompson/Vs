package com.princeniithompson.rapcha.service.lens

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.service.RapchaAccessibilityService
import com.princeniithompson.rapcha.service.SafeModeClassifier
import com.princeniithompson.rapcha.service.floating.FloatingOverlayLifecycleOwner
import com.princeniithompson.rapcha.service.floating.LivePolishOutcome
import com.princeniithompson.rapcha.service.floating.LiveTextPolishClient
import com.princeniithompson.rapcha.service.floating.Stage2Request
import com.princeniithompson.rapcha.ui.lens.LensOverlay
import com.princeniithompson.rapcha.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

enum class LensPhase { HIDDEN, ARMED, DRAGGING, ACTIONS, GENERATING, RESULT, ERROR }

data class LensUiState(
    val phase: LensPhase = LensPhase.HIDDEN,
    val centerX: Float = 0f,
    val centerY: Float = 0f,
    val highlight: ScreenRect? = null,
    val selected: String = "",
    val editable: Boolean = false,
    val action: LensAction? = null,
    val option: String = "",
    val resultTitle: String = "",
    val resultText: String = "",
    val error: String = "",
    val tones: List<TonePreset> = LensSettings.DEFAULT_TONES
)

/**
 * Double-tap arms the lens. A later drag targets text. The model call is gemini-3.8-live only.
 */
object LensController {
    private const val TAG = "LensController"
    private val state = MutableStateFlow(LensUiState())
    private val epoch = AtomicLong(0)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var context: Context? = null
    private var apiKey: (() -> String)? = null
    private var bubbleAlpha: ((Float) -> Unit)? = null
    private var bubbleOrigin: (() -> Pair<Float, Float>)? = null
    private var windowManager: WindowManager? = null
    private var view: ComposeView? = null
    private var lifecycleOwner: FloatingOverlayLifecycleOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var fieldText: String = ""
    private var lastLookup = 0L
    private var source: LensSource? = null

    fun attach(
        context: Context,
        apiKey: () -> String,
        bubbleAlpha: (Float) -> Unit,
        bubbleOrigin: () -> Pair<Float, Float>
    ) {
        this.context = context.applicationContext
        this.apiKey = apiKey
        this.bubbleAlpha = bubbleAlpha
        this.bubbleOrigin = bubbleOrigin
    }

    fun isOpen(): Boolean = state.value.phase != LensPhase.HIDDEN

    fun onArmed() {
        val app = context ?: return
        if (!LensSettings.enabled(app.getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE))) return
        if (isOpen()) return
        val origin = bubbleOrigin?.invoke() ?: return
        epoch.incrementAndGet()
        fieldText = ""
        source = null
        state.value = LensUiState(
            phase = LensPhase.ARMED,
            centerX = origin.first,
            centerY = origin.second,
            tones = tones()
        )
        bubbleAlpha?.invoke(0f)
        ensureWindow()
        setTouchable(false)
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.LENS_START,
            "build=${com.princeniithompson.rapcha.BuildConfig.VERSION_NAME} gesture=double_tap"
        )
    }

    fun onDrag(dx: Float, dy: Float) {
        val current = state.value
        if (current.phase != LensPhase.DRAGGING && current.phase != LensPhase.ARMED) return
        state.value = current.copy(
            phase = LensPhase.DRAGGING,
            centerX = current.centerX + dx,
            centerY = current.centerY + dy
        )
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLookup > 90L) {
            lastLookup = now
            look()
        }
    }

    fun onDragEnd() {
        val current = state.value
        if (current.phase != LensPhase.DRAGGING) return
        look()
        if (state.value.selected.isBlank() && !LensProjection.hasConsent()) {
            requestConsent()
        }
        state.value = state.value.copy(phase = LensPhase.ACTIONS)
        setTouchable(true)
    }

    fun dismiss() {
        epoch.incrementAndGet()
        state.value = LensUiState()
        bubbleAlpha?.invoke(1f)
        removeWindow()
        stopCapture()
    }

    private fun tones(): List<TonePreset> {
        val app = context ?: return LensSettings.DEFAULT_TONES
        return LensSettings.tones(app.getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE))
    }

    private fun look() {
        val ui = state.value
        val x = ui.centerX.toInt()
        val y = ui.centerY.toInt()
        val pkg = FloatingBubbleManager.currentForegroundPackage.value
        if (SafeModeClassifier.isSensitiveApp(context, pkg) || FloatingBubbleManager.isCurrentAppSensitive.value) {
            state.value = ui.copy(highlight = null, selected = "", editable = false)
            return
        }
        val service = RapchaAccessibilityService.instance
        val a11y = if (service != null) LensSelection.pick(LensAccessibility.read(service), x, y) else null
        if (a11y != null) {
            source = LensSource.ACCESSIBILITY
            fieldText = if (a11y.editable) a11y.text else ""
            publishTarget(a11y)
            return
        }
        val crop = LensProjection.cropAround(x, y, 180)
        if (crop != null) {
            scope.launch(Dispatchers.Default) {
                val nodes = LensOcr.read(crop, x - crop.width / 2, y - crop.height / 2)
                val picked = LensSelection.pick(nodes, x, y)
                withContext(Dispatchers.Main) {
                    if (state.value.phase == LensPhase.HIDDEN) return@withContext
                    if (picked != null) {
                        source = LensSource.OCR
                        fieldText = ""
                        publishTarget(picked)
                    }
                }
            }
        }
    }

    private fun publishTarget(candidate: LensCandidate) {
        state.value = state.value.copy(
            highlight = candidate.bounds,
            selected = candidate.text,
            editable = candidate.editable && candidate.source == LensSource.ACCESSIBILITY
        )
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.LENS_TARGET,
            "source=${candidate.source.name.lowercase()} chars=${candidate.text.length}"
        )
    }

    private fun requestConsent() {
        val app = context ?: return
        val intent = Intent(app, LensConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        LensProjection.onReady = {
            main.post { if (state.value.phase == LensPhase.ACTIONS) look() }
        }
        try {
            app.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Lens consent could not start: ${e.message}")
        }
    }

    fun choose(action: LensAction) {
        when (action) {
            LensAction.GRAMMAR, LensAction.ASK, LensAction.POLISH -> run(action, action.name.lowercase(), "")
            else -> state.value = state.value.copy(action = action, phase = LensPhase.ACTIONS, option = "")
        }
    }

    fun chooseOption(action: LensAction, option: String) {
        val tone = tones().firstOrNull { it.name.equals(option, ignoreCase = true) }
        run(action, option, tone?.instruction.orEmpty())
    }

    private fun run(action: LensAction, option: String, toneInstruction: String) {
        val text = state.value.selected
        if (text.isBlank()) return
        val token = epoch.get()
        state.value = state.value.copy(phase = LensPhase.GENERATING, action = action, option = option, error = "")
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.LENS_ACTION,
            "action=${action.name} option_chars=${option.length} chars=${text.length} model=${LensPrompts.MODEL}"
        )
        val key = apiKey?.invoke().orEmpty()
        scope.launch(Dispatchers.IO) {
            val started = android.os.SystemClock.elapsedRealtime()
            val response = try {
                LiveTextPolishClient().polish(
                    Stage2Request(
                        apiKey = key,
                        systemInstruction = LensPrompts.system(action, option, toneInstruction),
                        userText = LensPrompts.user(text, action, option)
                    )
                )
            } catch (t: Throwable) {
                null
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            withContext(Dispatchers.Main) {
                if (token != epoch.get()) return@withContext
                val body = response?.text?.trim().orEmpty()
                if (body.isEmpty()) {
                    val detail = response?.error
                    val line = LivePolishOutcome.userLine(detail).removeSuffix(" Your words were inserted.")
                    state.value = state.value.copy(phase = LensPhase.ERROR, error = line)
                    AppLogRepository.logEvent(
                        DiagnosticSource.BUBBLE,
                        DiagnosticType.LENS_ERROR,
                        "action=${action.name} latency_ms=$elapsed reason=${detail?.take(80) ?: "empty"}"
                    )
                    if (detail?.contains("503") == true || detail?.contains("UNAVAILABLE") == true) {
                        context?.let { Toast.makeText(it, "Gemini is busy.", Toast.LENGTH_SHORT).show() }
                    }
                } else {
                    val title = if (action == LensAction.TRANSLATE) option else action.name.lowercase().replaceFirstChar { it.uppercase() }
                    state.value = state.value.copy(phase = LensPhase.RESULT, resultTitle = title, resultText = body)
                    AppLogRepository.logEvent(
                        DiagnosticSource.BUBBLE,
                        DiagnosticType.LENS_RESULT,
                        "action=${action.name} latency_ms=$elapsed chars=${body.length} model=${LensPrompts.MODEL}"
                    )
                }
            }
        }
    }

    fun copy() {
        val text = state.value.resultText
        val app = context ?: return
        if (text.isBlank()) return
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Rapcha Lens", text))
        Toast.makeText(app, "Copied", Toast.LENGTH_SHORT).show()
    }

    fun regenerate() {
        val action = state.value.action ?: return
        val option = state.value.option
        val tone = tones().firstOrNull { it.name.equals(option, ignoreCase = true) }
        run(action, option.ifBlank { action.name.lowercase() }, tone?.instruction.orEmpty())
    }

    fun down() {
        AppLogRepository.logEvent(DiagnosticSource.BUBBLE, DiagnosticType.LENS_ACTION, "feedback=down")
    }

    fun insert() {
        val ui = state.value
        if (!ui.editable || ui.resultText.isBlank()) return
        val service = RapchaAccessibilityService.instance ?: return
        val current = LensSelection.pick(
            LensAccessibility.read(service),
            ui.centerX.toInt(),
            ui.centerY.toInt()
        )
        if (current == null || !current.editable || current.password) {
            copy()
            return
        }
        val replacement = LensSelection.splice(current.text, ui.selected, ui.resultText) ?: ui.resultText
        val args = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
        }
        val node = findEditable(service, ui.centerX.toInt(), ui.centerY.toInt())
        val wrote = node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true
        if (!wrote) copy()
    }

    private fun findEditable(service: RapchaAccessibilityService, x: Int, y: Int): android.view.accessibility.AccessibilityNodeInfo? {
        val windows = service.windows ?: return null
        for (window in windows) {
            val root = window.root ?: continue
            val found = findAt(root, x, y)
            if (found != null) return found
        }
        return null
    }

    private fun findAt(node: android.view.accessibility.AccessibilityNodeInfo, x: Int, y: Int): android.view.accessibility.AccessibilityNodeInfo? {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val nested = findAt(child, x, y)
            if (nested != null) return nested
        }
        return if (node.isEditable && !node.isPassword) node else null
    }

    private fun stopCapture() {
        if (!LensProjection.hasConsent()) return
        LensProjection.release()
        context?.stopService(android.content.Intent(context, LensCaptureService::class.java))
    }

    private fun ensureWindow() {
        if (view != null) return
        val app = context ?: return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val owner = FloatingOverlayLifecycleOwner()
        lifecycleOwner = owner
        owner.onCreate()
        owner.onStart()
        owner.onResume()
        val compose = ComposeView(app).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val ui by state.collectAsState()
                MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                    LensOverlay(
                        state = ui,
                        onDismiss = { dismiss() },
                        onAction = { choose(it) },
                        onOption = { action, option -> chooseOption(action, option) },
                        onCopy = { copy() },
                        onRegenerate = { regenerate() },
                        onDown = { down() },
                        onInsert = { insert() }
                    )
                }
            }
        }
        owner.attachToView(compose)
        val type = if (RapchaAccessibilityService.instance != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        params = lp
        view = compose
        try {
            wm.addView(compose, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Lens window failed: ${e.message}")
        }
    }

    private fun setTouchable(touchable: Boolean) {
        val lp = params ?: return
        val compose = view ?: return
        val flags = if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        lp.flags = flags
        try {
            windowManager?.updateViewLayout(compose, lp)
        } catch (_: Exception) {
        }
    }

    private fun removeWindow() {
        val compose = view ?: return
        try {
            windowManager?.removeView(compose)
        } catch (_: Exception) {
        }
        lifecycleOwner?.onDestroy()
        view = null
        lifecycleOwner = null
        params = null
    }
}

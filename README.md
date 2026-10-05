# VoxStream

VoxStream is a native Android voice-typing system powered by Google's Gemini Multimodal Live API, delivering low-latency bidirectional PCM audio streaming, real-time transcription, context-aware AI text polishing, and direct text injection across any Android application.

---

## Architecture & Folder Structure

```text
app/src/main/java/com/example/
├── MainActivity.kt                          # Single-activity host with edge-to-edge configuration
├── audio/
│   ├── AudioRecorder.kt                     # 16kHz 16-bit Mono PCM AudioRecord capture engine (gentle 75Hz HPF, AEC/NS/AGC off)
│   └── HighPassFilter.kt                    # Gentle 75Hz first-order IIR filter for sub-bass/rumble reduction
├── config/
│   └── VoxStreamConfig.kt                   # Audio constants, model endpoints, and fallback logic
├── core/
│   └── ApiConfig.kt                         # Single source of truth for key resolution & placeholder validation
├── data/
│   ├── AppLogRepository.kt                  # Multi-day diagnostic logging for socket events and sessions
│   ├── AudioRecordingRepository.kt          # Manages local WAV recordings cache, playback, and export
│   ├── CustomVocabularyRepository.kt        # Repository for custom user words and phonetic replacements
│   ├── HistoryRepository.kt                 # Persistence repository for past dictation sessions
│   ├── Models.kt                            # Domain data models for LiveStats, ConnectionState, and LogEntries
│   └── ScreenContextRepository.kt           # In-memory store for active app package, title, and context
├── service/
│   ├── AppClassifier.kt                     # Contextual classification of active apps into tone profiles
│   ├── AppContextResolver.kt                # Resolves app identity and context from accessibility nodes
│   ├── AppDetector.kt                       # Dynamic app & browser URL detector using Gemini fallback
│   ├── AppRegistry.kt                       # Static mapping of known packages to tone and category
│   ├── FloatingBubbleManager.kt             # Bridge between UI and FloatingBubbleService state
│   ├── FloatingBubbleService.kt             # Foreground service hosting the floating overlay window
│   ├── LearnedAppRegistry.kt                # Local cache of previously classified apps
│   ├── SafeModeClassifier.kt                # Detects sensitive and password fields to protect user privacy
│   ├── SmartVocabularyService.kt            # Suggests contextual vocabulary based on active apps
│   ├── VoxStreamAccessibilityService.kt     # AccessibilityService detecting active input nodes & injecting text
│   ├── VoxStreamInputMethodService.kt       # Optional IME service fallback for direct input commit
│   └── floating/
│       ├── AiPolishMode.kt                  # Polish tone presets (Clean, Casual, Formal, Punchy, etc.)
│       ├── FloatingAudioRecorder.kt         # Audio recorder instance tied to floating session lifecycle
│       ├── FloatingDictationSessionManager.kt # Dictation state coordinator, socket bridge, and stats
│       ├── FloatingDragSnapHandler.kt       # Drag gesture and edge-snapping physics for floating bubble
│       ├── FloatingHapticManager.kt         # Haptic feedback triggers for dictation milestones
│       ├── FloatingNotificationManager.kt   # Ongoing foreground service notification builder
│       ├── FloatingOverlayContent.kt        # Compose view hierarchy displayed inside WindowManager
│       ├── FloatingOverlayLifecycleOwner.kt # Custom LifecycleOwner, SavedState, and ViewModelStore owner
│       ├── FloatingOverlayWindowManager.kt  # WindowManager layout params, window attachment, and drag/drop
│       ├── FloatingPolishClient.kt          # HTTP REST client for on-demand Gemini text polishing
│       ├── FloatingPolishCoordinator.kt     # Debounced coordination of transcript polishing
│       └── FloatingTextInjector.kt          # Text injection helper via accessibility actions and clipboard
├── ui/
│   ├── VoiceTypingScreen.kt                 # In-app dictation interface, visualizers, and state actions
│   ├── VoiceTypingViewModel.kt              # ViewModel managing audio recording state and socket connectivity
│   ├── components/                          # Reusable UI sheets, animated glow bars, and dialogs
│   │   ├── diagnostics/                     # Diagnostic live frame inspectors, event trackers, and notes
│   │   └── overlay/                         # Floating overlay composables (bubble, dock, action row)
│   ├── screens/
│   │   ├── DictionaryScreen.kt              # Custom vocabulary management screen
│   │   └── HomeScreen.kt                    # Dashboard with past sessions, statistics, and settings
│   └── theme/                               # Material 3 Expressive theme, typography, and brush gradients
├── util/
│   └── AppResolutionEngine.kt               # App name and website host resolution utilities
└── websocket/
    └── GeminiLiveWebSocketClient.kt         # OkHttp WebSocket client for Gemini Live Bidi streaming
```

---

## Background Services

- `FloatingBubbleService`: Displays a persistent floating overlay window (`WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`) over third-party applications and orchestrates microphone recording with foreground service lifecycle management.
- `VoxStreamAccessibilityService`: Listens for focus and text-change accessibility events across running apps to identify editable input nodes (`AccessibilityNodeInfo`). Injects text via a four-tier prioritized chain: Priority 1 native `AccessibilityInputConnection`, Priority 2 companion IME, Priority 3 direct node editing (`ACTION_SET_TEXT`), and Priority 4 targeted paste injection for custom rich-text editors with automated clipboard restoration.

---

## Permissions

- `RECORD_AUDIO`: Captures raw PCM microphone audio for live dictation and AI transcription.
- `SYSTEM_ALERT_WINDOW`: Displays the interactive floating voice bubble overlay on top of other running apps.
- `INTERNET`: Connects via secure WebSockets and HTTPS to Google Gemini Live API endpoints.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` / `FOREGROUND_SERVICE_SPECIAL_USE`: Ensures uninterrupted audio streaming and floating overlay operation when dictating outside the app.
- `POST_NOTIFICATIONS`: Displays the required ongoing notification while background recording is active.
- `VIBRATE`: Provides tactile haptic feedback when dictation starts, pauses, or completes.
- `WAKE_LOCK`: Keeps CPU awake during active live audio recording sessions.

---

## API Key Configuration

The Gemini API key is managed via a strict hierarchy through `ApiConfig.kt`:
1. **User Settings**: Custom key entered at runtime by the user in the app settings, stored securely in private app preferences.
2. **Environment Variable**: `BuildConfig.GEMINI_API_KEY` injected at build time from `.env` via secrets gradle plugin (and never checked into Git).
3. **Safe Fallback**: If neither is configured, the system cleanly defaults to empty string `""` without crashing.

No real API key is ever hardcoded in source files, comments, or repository defaults.

---

## Privacy Note

All voice audio captured during dictation is streamed directly over an encrypted TLS connection to Google's Gemini API strictly for real-time speech-to-text transcription and requested AI polishing. No audio, transcripts, or personal data are collected, sold, or shared with third parties. Recorded audio files stored locally on the device remain strictly within private app storage unless the user explicitly chooses to export or share them.

---

## Text Injection Architecture (The Wispr Flow Blueprint)

VoxStream achieves universal, non-destructive text injection across all Android apps—including rich-text and document editors like Google Keep, Google Docs, Chrome, and Notion—without replacing the user's default keyboard (Gboard) and without touching the system clipboard.

### The Underlying Problem with Traditional Accessibility Injection
1. **The "Empty Note Discarded" Bug in Rich Editors**:
   - Standard accessibility tools rely on `AccessibilityNodeInfo.performAction(ACTION_SET_TEXT)`.
   - While `ACTION_SET_TEXT` displays words visually on screen, apps with proprietary document models (like Google Keep) do not register this as genuine typing. Keep's internal state machine listens specifically to keyboard events through Android's `InputConnection`.
   - When exiting or backing out, Google Keep inspects its internal document state, sees that no keyboard inputs occurred, and silently deletes the draft with "Empty note discarded".
2. **The Clipboard Trap**:
   - Falling back to `ACTION_PASTE` requires writing to `ClipboardManager`, which pollutes the user's clipboard history and triggers mandatory, non-dismissible OS-level toasts on Android 12+ (*"VoxStream pasted from your clipboard"*).

### The Solution: Direct Native `AccessibilityInputConnection`
Starting in Android 13 (API 33), Android introduced `AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR` (`flagInputMethodEditor`). This grants the active `AccessibilityService` direct access to the system's live `AccessibilityInputConnection` for whichever editor currently has focus.

```text
[Dictated Speech] ──► [Gemini Live WebSocket / AI Polish]
                             │
                             ▼
               [VoxStreamAccessibilityService]
                             │
       ┌─────────────────────┴─────────────────────┐
       ▼                                           ▼
[Priority 1: Direct Pipe]               [Fallback: Standard Caret]
inputMethod.currentInputConnection       injectTextSafely (ACTION_SET_TEXT)
.commitText(text, 1, null)               with cursor bounds & hint safety
       │                                           │
       ▼                                           ▼
[Target App: Google Keep, Chrome, etc.]   [Standard Native Views]
   - Saved to internal document database     - Visually updated & caret positioned
   - Gboard remains untouched as default      - Zero clipboard usage
   - Zero clipboard pollution / paste toasts
```

### Hybrid Injection Priority Order in `VoxStreamAccessibilityService.kt`
- **Priority 1 (`commitTextViaInputMethod`)**:
  - Direct native injection via Android 13+ `AccessibilityInputConnection` (`ic.commitText(text, 1, null)`).
  - Whispers text directly through the active editor's native input connection at the caret without clipboard usage.
  - Used for rich document editors (Google Keep, Google Docs, Notion, Chrome, WhatsApp, Slack).
- **Priority 2 (`VoxStreamInputMethodService.commitText`)**:
  - Virtual input method fallback if enabled by the user.
  - Verifies `currentInputStarted` before committing text to prevent false dispatches or dead-end attempts.
- **Priority 3 (`injectTextSafely`)**:
  - Caret-level node action (`AccessibilityNodeInfo.performAction(ACTION_SET_TEXT)`).
  - Includes cursor bounds verification, automatic clean spacing, and hint-text preservation (never treats strings > 15 characters as hints).
- **Priority 4 (`performPasteInjection`)**:
  - Targeted fallback strictly restricted to custom/rich-text apps where `AppClassifier.isPasteRequired(pkg)` is true and Priority 3 direct node editing failed.
  - Temporarily sets the clipboard with a uniquely labeled dictation clip, requests focus, dispatches `ACTION_PASTE`, and automatically restores the user's original clipboard content after ~450ms.
- **No Active Text Field Fallback**:
  - If dictation completes when zero editable input fields are focused on screen, text is copied to the clipboard with an explicit user toast (`"Copied to clipboard (no active text field found)"`).

### Exactly-Once Injection & Race Prevention
- **Short-Circuiting Pipeline**: Higher priority tiers short-circuit the injection pipeline immediately upon success (`return true`). Lower tiers (including node edit and paste) are never executed if an earlier tier succeeds.
- **Smart Safe Mode Protection**: If `FloatingBubbleManager.isCurrentAppSensitive` is active (e.g., banking apps, password fields, secure credit card inputs), text injection is blocked immediately before any tier can execute.
- **Empty Text Rejection**: Blank or empty strings are rejected at the entry point of `injectText()`, preventing unintended mutations or clipboard resets.
- **Caret Splicing & Duplicate Avoidance**: In Priority 3, text is spliced precisely at the active selection cursor. Surrounding whitespace is checked to avoid duplicate boundary spacing, and known search hint texts are stripped to prevent merging placeholders into dictation.

### Asynchronous Paste & Clipboard Isolation
- **Disarmable Asynchronous Callbacks**: In Priority 4, the initial delayed paste, retry paste, and clipboard restoration callbacks are all actively tracked. Calling `cancelPendingPaste()` immediately cancels all pending runnables from the Handler queue.
- **Monotonically Increasing Operation IDs**: Each paste injection is assigned an atomic operation ID and unique clip label (`"VoxStream Dictation #$id"`).
- **Stale Callback Disarming**: Delayed callbacks verify that `activePasteOperationId == operationId` before dispatching. If a newer injection starts or cancels the pipeline, older callbacks recognize they are obsolete and abort without touching the clipboard.
- **Original Clipboard Preservation Across Rapid Dictations**: If a second paste injection begins while a previous dictation clip is still on the clipboard, `savedUserOriginalClip` preserves the user's true pre-dictation clipboard rather than adopting intermediate dictation text, ensuring clean restoration once the sequence completes.

### Troubleshooting & Regression Prevention Checklist
If text injection into Google Keep or rich editors ever fails in future refactorings, verify the following:
1. **XML Config**: Verify `android:accessibilityFlags` in `app/src/main/res/xml/accessibility_service_config.xml` includes `flagInputMethodEditor`.
2. **Runtime Service Info**: Ensure `serviceInfo.flags` in `VoxStreamAccessibilityService.onServiceConnected()` includes `AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR` for `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU`.
3. **Priority 1 Order**: Verify `commitTextViaInputMethod(newText)` is evaluated at the very top of `VoxStreamAccessibilityService.injectText()` before any node action fallbacks.
4. **Return Type**: Note that `AccessibilityInputConnection.commitText()` in the Android SDK returns `Unit` (void), unlike standard IME `InputConnection.commitText()` which returns `Boolean`. Check that `ic != null` before invoking `ic.commitText()`.

---

## Recent Hardening

- **Key Safety & Repository Cleanliness**: Extracted all key resolution and placeholder checking into `ApiConfig.kt` as the single source of truth, eliminated all hardcoded API tokens from source files, and ensured `.gitignore` strictly guards `.env`, `local.properties`, and keystores.
- **Race Condition in Injection Delay**: Resolved a race condition in `FloatingBubbleService.onConfirmClicked()` by tracking and assigning the 1400ms injection timeout to `pendingCompletionTimeoutJob`, ensuring user cancellation halts pending injections before execution.
- **Microphone Protection on Android 14+**: Assigned the 450ms FGS type demotion to `fgsDowngradeJob` and cancelled it upon `startVoiceTyping()`, preventing background service demotion from killing the microphone during rapid re-dictation.
- **WebSocket Auto-Reconnect Resilience**: Updated `GeminiLiveWebSocketClient.onClosed()` to preserve `lastApiKey` across normal server closures during active speech, ensuring automated exponential backoff reconnects seamlessly rather than aborting.
- **Direct Input Connection (Wispr Flow Architecture)**: Enabled `FLAG_INPUT_METHOD_EDITOR` (`flagInputMethodEditor`) on `VoxStreamAccessibilityService` and implemented `commitTextViaInputMethod()` using Android 13+ `AccessibilityInputConnection`. Transcribed text is whispered directly through the active editor's native input connection at the blinking cursor, ensuring apps like Google Keep immediately detect and persist typed notes without clipboard copying, paste toasts, or replacing Gboard.
- **Duplicate Injection & Fallback Audit (Step 2)**: Hardened `commitTextViaInputMethod()` so that clean dispatches across active connections on editors that do not implement `getSurroundingText()` are recognized as successes rather than false-negatives, preventing accidental duplicate injection by lower fallback layers. Additionally hardened `VoxStreamInputMethodService.commitText()` to verify `currentInputStarted` before attempting injection.
- **Injection Pipeline & Clipboard Isolation Hardening (Step 3 & 3.1)**: Unified the four-priority injection pipeline with strict short-circuiting. Tracked and cancelled delayed paste, retry, and clipboard-restoration runnables via `cancelPendingPaste()`. Isolated paste operations using atomic IDs and unique clip labels, and preserved true user clipboard state across rapid sequential paste dictations. Verified through comprehensive unit tests (`InjectionChainVerificationTest`).
- **Audio Capture Diagnostic Baseline**: Restored `VOICE_RECOGNITION` as the primary microphone source (moving `VOICE_COMMUNICATION` to fallback) and bypassed hardware `NoiseSuppressor`, `AutomaticGainControl`, and `AcousticEchoCanceler`.
- **Gentle 75 Hz High-Pass Filter Experiment**: Added a gentle first-order IIR high-pass filter with a 75 Hz cutoff to `AudioRecorder.kt`. The filter operates directly in-place on the raw 16kHz 16-bit mono PCM stream delivered identically to both Gemini Live and the diagnostic WAV logger. This attenuates environmental rumble and sub-bass fan noise while preserving vocal fundamentals and speech naturalness, keeping AEC, NoiseSuppressor, and AGC disabled.
- **Audio Pipeline API Cleanliness & Diagnostic String Audit**: Cleaned out dead/overridden `aecEnabled` and `noiseSuppressorEnabled` parameters across `AudioRecorder.kt`, `FloatingDictationSessionManager.kt`, and `FloatingBubbleService.kt` to enforce a clean, raw capture contract and prevent conflicting toggles. Updated the outdated diagnostic warning log in `checkAndLogNoiseConditions()` to reflect current active settings (`"High-pass 75Hz; hardware NS/AGC/AEC disabled"`).

# VoxStream

VoxStream is a native Android voice-typing system powered by Google's Gemini Multimodal Live API, delivering low-latency bidirectional PCM audio streaming, real-time transcription, context-aware AI text polishing, and direct text injection across any Android application.

---

## Architecture & Folder Structure

```text
app/src/main/java/com/example/
├── MainActivity.kt                          # Single-activity host with edge-to-edge configuration
├── audio/
│   ├── AudioRecorder.kt                     # 16kHz 16-bit Mono PCM AudioRecord microphone capture engine
│   └── HighPassFilter.kt                    # Pre-processing filter removing DC offset and mic rumble
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
- `VoxStreamAccessibilityService`: Listens for focus and text-change accessibility events across running apps to identify editable input nodes (`AccessibilityNodeInfo`). Injects text with Priority 1 using Android 13+ `AccessibilityInputConnection` directly into the active editor's native input connection (Wispr Flow architecture), falling back to caret-level node actions without clipboard pollution.

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
1. **Priority 1 (`commitTextViaInputMethod`)**:
   - Queries `inputMethod.currentInputConnection`.
   - Calls `ic.commitText(text, 1, null)` directly at the blinking cursor.
   - *Result*: Instant, native keystroke recognition by Google Keep, Google Docs, Notion, Chrome WebViews, WhatsApp, and Slack without clipboard involvement.
2. **Priority 2 (`VoxStreamInputMethodService.commitText`)**:
   - Dispatches via companion `InputMethodService` if enabled as an alternate virtual input connection.
3. **Priority 3 (`injectTextSafely`)**:
   - Dispatches caret-level `ACTION_SET_TEXT` with cursor bounds checking, hint-text preservation (never treats strings > 15 characters as hints), and selection repositioning.
4. **Strict Clipboard Policy**:
   - The system clipboard is **NEVER** touched when an editable text field is active.
   - Clipboard fallback is only triggered if dictation occurs with **zero** active input fields on screen (`"Copied to clipboard (no active text field found)"`).

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

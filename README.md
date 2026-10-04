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
- `VoxStreamAccessibilityService`: Listens for focus and text-change accessibility events across running apps to identify editable input nodes (`AccessibilityNodeInfo`) and directly injects finalized transcripts via `ACTION_SET_TEXT`.

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

## Recent Hardening

- **Key Safety & Repository Cleanliness**: Extracted all key resolution and placeholder checking into `ApiConfig.kt` as the single source of truth, eliminated all hardcoded API tokens from source files, and ensured `.gitignore` strictly guards `.env`, `local.properties`, and keystores.
- **Race Condition in Injection Delay**: Resolved a race condition in `FloatingBubbleService.onConfirmClicked()` by tracking and assigning the 1400ms injection timeout to `pendingCompletionTimeoutJob`, ensuring user cancellation halts pending injections before execution.
- **Microphone Protection on Android 14+**: Assigned the 450ms FGS type demotion to `fgsDowngradeJob` and cancelled it upon `startVoiceTyping()`, preventing background service demotion from killing the microphone during rapid re-dictation.
- **WebSocket Auto-Reconnect Resilience**: Updated `GeminiLiveWebSocketClient.onClosed()` to preserve `lastApiKey` across normal server closures during active speech, ensuring automated exponential backoff reconnects seamlessly rather than aborting.

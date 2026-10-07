# VoxStream

> **Native Android AI Voice Typing & Polishing System**

VoxStream is a native Android floating-bubble voice assistant designed for high-precision streaming transcription, context-aware AI text polishing via Gemini 3.5 Flash Lite, and deterministic direct text injection into active input fields without system clipboard corruption.

---

## Key Architectural Pillars

### A. Audio Pipeline (Acoustic Integrity, NoiseSuppressor & VAD Stream Gating)
- **Audio Source**: Configured with `MediaRecorder.AudioSource.VOICE_RECOGNITION` to bypass invasive carrier speech tuning.
- **Hardware NoiseSuppressor**: Android platform `NoiseSuppressor` is initialized on the active `audioSessionId` before recording starts to eliminate fan/background acoustic noise while keeping AEC and AGC disabled.
- **75Hz IIR High-Pass Filter (HPF)**: Applies a gentle first-order IIR HPF to remove low-frequency handling thuds and motor resonance while retaining vocal body and unvoiced whisper formants.
- **Client-Side VAD Stream Gating**: In `FloatingDictationSessionManager.kt`, audio chunks are only sent to the WebSocket when `isSpeechActive == true` or within a 700ms syllable hangover (`System.currentTimeMillis() - lastSustainedSpeechTimestamp < 700L`). When speech is inactive, raw fan/ambient noise is strictly suppressed; a 100ms zero-padding keep-alive chunk is sent at most once per second to maintain the Gemini Live connection without hallucinations, and a `NOISY_ENVIRONMENT` diagnostic event is emitted every 5 seconds of continuous non-speech gating.
- **Voice-Reactive Glow & Resting Pulse**: When speaking, the visualizer glow intensity tracks real vocal amplitude smoothly without over-clamping. When paused or noise-gated, a subtle resting breathing pulse (~0.08–0.18 amplitude) confirms active listening without going flatline.
- **Streaming Protocol**: Streams 16kHz 16-bit Mono PCM audio over a bidirectional WebSocket to the Gemini Multimodal Live API.

### B. Hybrid App Detection & 4-Signal WebAPK Cascade
- **Native Applications**: Resolved directly via `PackageManager` label inspection supported by `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />`.
- **Containerized Apps (Gemini)**: Performs surface-level activity class and window title inspection to differentiate the Gemini surface from the host `com.google.android.googlequicksearchbox` package.
- **WebAPKs & PWAs (e.g., Google AI Studio, Brain AI, Pinterest)**: Uses a 4-signal detection cascade:
  1. **Activity Class Fingerprint**: Detects `SameTaskWebApkActivity`, `WebApkActivity`, or `WebappActivity`.
  2. **URL-Bar Absence Check**: Verifies the absence of address bar UI elements (`url_bar`), confirming standalone display mode.
  3. **WebAPK Inventory**: Enumerates installed `org.chromium.webapk.*` shell packages and extracts metadata (`org.chromium.webapk.shell_apk.startUrl`).
  4. **UsageStats Recency Correlation**: Utilizes `PACKAGE_USAGE_STATS` to match empty browser windows to the most recently used WebAPK shell.
  5. **Sticky Session Hysteresis**: Implements a 60-second TTL sticky cache to maintain app identity when window titles temporarily drop during soft keyboard transitions.

### C. "Chef & Spice" Context-Aware AI Polishing
- **Polishing Engine**: Powered by Gemini 3.5 Flash Lite via REST API.
- **Layer 1 (Golden Rules - "Chef")**: Enforces a strict non-conversational editor contract. It strips vocal fillers, preserves numbers and uncertainty, and resolves verbal self-corrections (e.g., *"three, no, four"* -> *"Four"*).
- **Layer 2 (Category Modifier - "Spice")**: Dynamically adapts the polishing style based on target app context (`AI_CHAT` for structured prompt engineering, `MESSAGING` for concise natural chat, `EMAIL` for professional formatting, `NOTES` for scannable lists).
- **Session Origin Anchoring**: Locks the target app context at the start of dictation so switching apps mid-sentence retains the intended target formatting persona.

### D. Deterministic Direct Text Injection
- **Focused Node Injection**: Resolves active focused editable fields via `AccessibilityService` and injects polished text directly using `AccessibilityNodeInfo.ACTION_SET_TEXT`.
- **Zero Clipboard Tampering**: Preserves system clipboard contents during input field insertion. Text is copied to the clipboard only as a fallback when no editable text field is active on screen.

### E. Diagnostics & System Hubs
- **App Detection Diagnostics**: A dedicated diagnostic view providing live inspection of raw package names, window titles, extracted accessibility nodes, classification sources, and raw AI prompts/responses.
- **Audio Diagnostics**: On-device WAV recording verification for acoustic capture and high-pass filter auditing.
- **Keyboard-Tied Bubble Lifecycle**: Bubble visibility is governed strictly by the presence of the IME soft keyboard (`AccessibilityWindowInfo.TYPE_INPUT_METHOD`). Appears instantly on keyboard focus and fades out smoothly over 150ms when the keyboard closes.

---

## Detailed Project Structure

```
app/src/main/java/com/example/
├── MainActivity.kt
├── audio/
│   ├── AudioRecorder.kt
│   └── HighPassFilter.kt
├── config/
│   └── VoxStreamConfig.kt
├── core/
│   └── ApiConfig.kt
├── data/
│   ├── AppDetectionLogRepository.kt
│   ├── AppLogRepository.kt
│   ├── AudioRecordingRepository.kt
│   ├── CustomVocabularyRepository.kt
│   ├── HistoryRepository.kt
│   └── InjectionLogRepository.kt
├── service/
│   ├── AppClassifier.kt
│   ├── AppContextResolver.kt
│   ├── AppDetector.kt
│   ├── FloatingBubbleManager.kt
│   ├── FloatingBubbleService.kt
│   ├── LearnedAppRegistry.kt
│   ├── SafeModeClassifier.kt
│   ├── VoxStreamAccessibilityService.kt
│   ├── VoxStreamInputMethodService.kt
│   └── floating/
│       ├── FloatingDictationSessionManager.kt
│       ├── FloatingHapticManager.kt
│       ├── FloatingPolishClient.kt
│       ├── FloatingPolishCoordinator.kt
│       └── FloatingTextInjector.kt
├── ui/
│   ├── HomeScreen.kt
│   ├── VoiceTypingScreen.kt
│   ├── components/
│   │   ├── DiagnosticsBottomSheet.kt
│   │   ├── DiagnosticsSheet.kt
│   │   └── diagnostics/
│   │       ├── DiagnosticsAudioSection.kt
│   │       ├── DiagnosticsHistorySection.kt
│   │       ├── DiagnosticsLiveSection.kt
│   │       └── DiagnosticsNotesSection.kt
│   ├── screens/
│   │   ├── AppDetectionDiagnosticsScreen.kt
│   │   ├── SettingsScreen.kt
│   │   └── VocabularyScreen.kt
│   └── theme/
│       ├── Color.kt
│       ├── Gradients.kt
│       ├── Shape.kt
│       ├── Theme.kt
│       └── Type.kt
├── util/
│   └── AppResolutionEngine.kt
└── websocket/
    └── GeminiLiveWebSocketClient.kt
```

---

## Permissions & Technical Requirements

- **Target SDK**: Android 13+ (API 33+)
- **Required Permissions**:
  - `android.permission.RECORD_AUDIO`: Required for microphone capture.
  - `android.permission.SYSTEM_ALERT_WINDOW`: Required for floating overlay bubble controls.
  - `android.permission.QUERY_ALL_PACKAGES`: Required for full native app package visibility.
  - `android.permission.PACKAGE_USAGE_STATS`: Required for WebAPK recency correlation in Signal 4.
  - `android.permission.BIND_ACCESSIBILITY_SERVICE`: Required for accessibility node inspection (`flagRetrieveInteractiveWindows`, `flagReportViewIds`).
  - `android.permission.FOREGROUND_SERVICE` & `android.permission.FOREGROUND_SERVICE_MICROPHONE`: Required for continuous background dictation.

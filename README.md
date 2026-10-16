# Rapcha - Voice Typing Assistant

Rapcha is an advanced Android voice typing application featuring an Everywhere Floating Voice Ring, multi-signal evidence-based App Detection, AI Polish editing styles, and granular App Detection Diagnostics.

## Architecture & Folder Structure
- `com.princeniithompson.rapcha.service/`: Accessibility service, floating bubble service, app detector, and classifiers.
- `com.princeniithompson.rapcha.data/`: Local repositories (History, Custom Vocabulary, App Logs, Audio Recordings, App Detection Logs).
- `com.princeniithompson.rapcha.ui/`: Jetpack Compose screens (`NoiseCancellationScreen`, `HomeScreen`, `DictionaryScreen`), components, themes, and ViewModel.
- `com.princeniithompson.rapcha.util/`: App resolution engine and utilities.

## Key Features
- **Everywhere Floating Voice Ring**: Floats over all apps for instant voice dictation.
- **App Detection & Resolution Improvements**:
  - **Package Visibility (`QUERY_ALL_PACKAGES`)**: Added broad package query permissions in `AndroidManifest.xml` so `PackageManager.getApplicationLabel()` can resolve installed app titles and WebAPKs across Android 11+ sandboxes.
  - **Interactive Window Titles (`flagRetrieveInteractiveWindows`)**: Enabled interactive window retrieval in `accessibility_service_config.xml` and `RapchaAccessibilityService.kt` to extract real window titles.
  - **App Name Resolution**: Direct PackageManager label lookup for installed apps and WebAPKs, Gemini detection for Search/Bard/Gemini, and browser site product fallback.
  - **Log Deduplication**: Added deduplication in `AppDetectionLogRepository.kt` to skip logging redundant events unless package name, resolved app name, or window title changes.
- **App Detection Diagnostics**: Granular tracking of app detection metadata (raw package name, window title, accessibility node text snippets, classification source, and Gemini AI prompt/response traces).
- **Audio Recordings & History**: Persistent local tracking of transcriptions, audio WAV recordings, and custom vocabulary.
- **Smart Safe Mode**: Protects sensitive banking and credential apps.
- **Touch Pass-Through During Voice Dictation**: When the floating dictation popup expands, its overlay window wraps tightly around the visible panel height without claiming full-screen bounds or using unbounded layout limits. Touches outside the panel pass directly to underlying applications (WhatsApp, Pinterest, browsers) and soft keyboards so users can freely navigate and type while voice dictating.
- **Lifesaver App Icon & Adaptive Plate**: Configured the adaptive launcher icon following Pixel guidelines. Set `ic_launcher_background.xml` to a solid `#FFFFFF` canvas filling the full 108dp x 108dp plate (`M0,0h108v108h-108z`), and centered the Lifesaver buoy strictly inside the 66dp x 66dp safe zone in `ic_launcher_foreground.xml`. This prevents Android's circular launcher mask from clipping the buoy ring and ensures the entire ring with red segments and shadow is visible and centered on the home screen.
- **Accessibility Service Clean Binding & Hidden API Fix**: Removed unused `flagInputMethodEditor` / `FLAG_INPUT_METHOD_EDITOR` from `accessibility_service_config.xml` and `RapchaAccessibilityService.kt`. Replaced preview/unstable `androidx.core:core-ktx:1.18.0` with stable `1.15.0` in `gradle/libs.versions.toml`, eliminating experimental references to blocked hidden methods (`AccessibilityNodeInfo.getSelection` and `setSelection`) and preventing ART link-time denials. Text injection remains fully operational via public `ACTION_SET_TEXT`.
- **Wispr Flow Exact Onboarding & Editorial Typography Redesign**:
  - Implemented the exact multi-screen workflow from the Wispr Flow application:
    1. *Screen 1 (Intro)*: Soft pastel coral red card (`#FDE8E8`) with editorial serif headline (`Newsreader.ttf`) and decorative ribbon curve.
    2. *Screen 2 (How It Works)*: Centered Lifesaver bubble with animated radiating sprinkle rays.
    3. *Screens 3–7 (Interactive Sandbox)*: Real interactive chat input box triggering the user's actual native keyboard (Gboard, Samsung Keyboard, etc.), shaking Lifesaver bubble docking dynamically right above the keyboard with haptic feedback, immediate runtime `RECORD_AUDIO` permission prompt, and the exact production notched floating dictation container (`FloatingDictationPopup`) appearing directly above the keyboard with live aurora glow, transcript streaming, AI polish toggle modes (`Clean Message` / `Optimize Prompt`), and interactive Cancel, Polish, and Complete buttons before smoothly inserting text into the chat box (`Nice, that worked!`).
    4. *Screen 8 (App Ecosystem)*: Illustrated mock showcase demonstrating floating Lifesaver over WhatsApp, Instagram, Pinterest, TikTok, Notion, Gmail, and ChatGPT.
    5. *Screen 9 (Overlay Permission)*: Illustrated Android Settings toggle guide with curved directional arrow.
    6. *Screens 10 & 11 (Accessibility Walkthrough)*: Step-by-step visual cards (`Downloaded apps -> Rapcha On -> No shortcut`) and comprehensive transparency & safety disclosure sheet (`Accessibility Permissions Required`).
    7. *Screens 12 & 13 (Notifications & Completion)*: `Don't speak into the void` notification permission prompt and `You're all set!` final handoff.
  - Added `Newsreader` Google Font locally via `font-util` into `res/font/newsreader.ttf` for editorial serif display typography.
  - Sized and styled all onboarding action buttons ("Next", "Continue", "Finish") and interactive elements with dynamic adaptive colors (`MaterialTheme.colorScheme.primaryContainer` and `onPrimaryContainer`), with the top progress bar segments and highlighted text ("Tap") adopting the deep dynamic primary tone (`MaterialTheme.colorScheme.primary`).
  - **Permission Lifecycle State Detection & App Icon Scaling**: Added Android lifecycle (`ON_RESUME`) observers across `MainActivity`, `OnboardingScreen`, `OnboardingOverlayPermissionScreen`, and `OnboardingAccessibilityGuideScreen` so returning from system Settings immediately queries `Settings.canDrawOverlays` and updates the permission state, turning the toggle ON and updating the button from "Open Settings" to "Continue". Migrated `ic_launcher_foreground.xml` from a fixed `<layer-list>` to a native scalable VectorDrawable group with proportional viewport scaling (pivot 200, scale 0.62) to ensure the Lifesaver logo renders accurately across all system settings lists and dialogs without blanking.
  - **On-Device Real Speech Recognition in Sandbox (Zero Fabrication)**: Integrated `OnboardingSpeechManager` utilizing Android's built-in `SpeechRecognizer` exclusively for the onboarding sandbox. When the user taps the Lifesaver bubble during onboarding, whatever they speak into the microphone (e.g. "I am making a grocery list...") is transcribed live into the exact floating dictation container and native chat input without any fake placeholder text or fabricated sentences, while the main production app continues to use Gemini Live streaming independently.
  - **Smart Safe Mode Complete Auto-Hide (Banking & Password Screens)**: Implemented complete auto-hiding of the floating bubble when sensitive apps or screens are detected. Uses Android's official `ApplicationInfo.CATEGORY_FINANCE`, package tokens, and label analysis to identify 100% of banking, investment, fintech, and crypto applications. Additionally monitors `AccessibilityEvent` and `AccessibilityNodeInfo` hierarchy to detect sign-up flows, sign-in activities, credential forms, and password fields (`isPassword == true`, password input types, view IDs). When detected, the floating bubble immediately auto-hides from the screen, and restores automatically as soon as the user navigates away or leaves the password field.
- **Gemini Live Server VAD 2000ms & Silero VAD Complete Removal**:
  - `GeminiLiveWebSocketClient.kt`: Updated `SERVER_VAD_SILENCE_DURATION_MS` from 1600 to 2000 ms (2.0 seconds).
  - Deleted `SileroVadModelManager.kt` and purged onnxruntime dependencies and all VAD gating logic. The audio capture pipeline (`AudioRecorder`, `FloatingDictationSessionManager`) now streams audio chunks continuously without dependency on local VAD neural models.
- **Whisperflow Production Audio Capture Pipeline**:
  - Completely rewritten `AudioRecorder.kt` matching Wispr Flow's production audio capture:
    - AudioSource: Direct Hardware `MIC` (`MediaRecorder.AudioSource.MIC = 1`), bypassing aggressive device voice-recognition preprocessing filters.
    - Format: 16 kHz sample rate, mono, 16-bit linear PCM in clean 100 ms packets (3200 bytes).
    - Client DSP: Zero client-side gating or filtering in the critical path (deleted `HighPassFilter`, `AdaptiveNoiseGate`, `GeminiHearingProcessor`, and `GeminiHearingMode`).
    - Hardware Audio Effects: Zero hardware `NoiseSuppressor`, `AcousticEchoCanceler`, or `AutomaticGainControl` created or enabled.
    - Model-Driven Noise Rejection: Gemini Live server-side audio intelligence handles background noise rejection without client fighting.
    - UI Telemetry: Whisperflow-style RMS meter value calculation for smooth UI amplitude visualizers without touching or mutating the PCM bytes.
    - Analytics: Integrated non-destructive `HeadSilenceTracker` to observe speech onset delay.
    - Mic Lifecycle: Rock-solid start/stop/release lifecycle with explicit synchronization and non-cancellable resource teardown, preventing HAL microphone leaks and stuck green recording indicators.
- **Whisperflow Hybrid Text Inserter (`RapchaTextInserter`)**:
  - Implemented the production text insertion engine matching Wispr Flow's hybrid architecture:
    - **Mutex Protection**: Concurrency locking (`Mutex.withLock`) prevents race conditions during rapid dictation or overlapping speech events.
    - **Direct Caret Injection First**: Prioritizes direct text injection into the active text field via `AccessibilityNodeInfo` (`ACTION_SET_TEXT`, `ACTION_SET_SELECTION`) and `InputConnection.commitText`, with intelligent hint stripping and cursor preservation.
    - **100% Clipboard Cleanliness**: The system clipboard is strictly isolated and never touched or contaminated during normal voice typing.
    - **Conditional Clipboard Fallback**: Executes only as a last resort error handler when no active editable text field exists on screen.
    - **Silent Fallback Feedback**: Triggers a subtle warm amber breathing ring on the floating lifebuoy bubble (`isClipboardFallbackIndicator`) and a double micro-tick haptic pulse, eliminating disruptive system toast popups.

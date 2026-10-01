# VoxStream

[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-3DDC84?style=flat&logo=android&logoColor=white)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.09.00-4285F4?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Material 3](https://img.shields.io/badge/Material%203-1.3.1-FF6D00?style=flat)](https://m3.material.io)
[![Gemini Live API](https://img.shields.io/badge/Gemini%20Live-WebSocket%20STT-8E24AA?style=flat&logo=google&logoColor=white)](https://ai.google.dev)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

VoxStream is an Android voice-typing application providing real-time speech-to-text dictation and text polishing powered by Google's Gemini Live WebSocket and Gemini REST APIs. It operates via an in-app dictation interface or a floating overlay bubble that coordinates with an Accessibility Service to inject transcribed text into target applications.

---

## Features

- **Real-Time Streaming Transcription**: Captures microphone audio at 16 kHz 16-bit mono PCM and streams chunks over a bidirectional WebSocket connection to the Gemini Live service (`models/gemini-3.5-transcribe-live`) for live interim and finalized transcription.
- **Floating Overlay Bubble**: Renders a floating window (`WindowManager`) with drag physics, edge-snapping animations, an inactivity shrink timer (56dp to 45dp), and dynamic color theming extracted from system wallpaper accents (`system_accent1`, `system_accent2`, `system_accent3`).
- **AI-Aware App Classifier**: Detects the active foreground package and classifies it into one of four categories to adjust text polishing prompts:
  - **Social**: WhatsApp, Telegram, Messages, Instagram, Messenger, TikTok, X, Snapchat, Reddit, Discord, Pinterest, Signal, LinkedIn, Facebook, Threads, Viber, Line.
  - **Work**: Google Keep, Docs, Sheets, Slides, Gmail, Outlook, Slack, Teams, Notion, Trello, Asana, Zoom, Linear, Jira, Microsoft Office suite.
  - **AI**: Google AI Studio, ChatGPT, Claude, Grok, Gemini, Perplexity, Copilot, DeepSeek, Qwen/Tongyi, Poe, Character.AI.
  - **Other**: Default formatting for all other applications.
- **Multi-Model Polish Engine**: Sends raw transcripts to the Gemini REST API (`generateContent`) using a sticky last-successful model, falling back through:
  1. `gemini-3.5-flash-lite`
  2. `gemini-3.1-flash-lite`
  3. `gemini-2.5-flash-lite`
  4. `gemini-2.5-flash`
- **Safe Clipboard Paste Injection**: For model-driven and rich text editors requiring `ACTION_PASTE` (e.g., Google Keep, Docs, Notion, Obsidian), the service backs up the current clipboard, pastes dictated text, verifies injection via `AccessibilityNodeInfo.refresh()`, and checks that the clipboard still contains the temporary dictation clip before restoring previous contents.
- **Custom Vocabulary & Dictionary**: Stores custom terminology in local preferences to include with the Gemini Live setup payload. Identifies terms unused for 60 or more days and offers clean-up suggestions.
- **Diagnostics & Persistent Event Logging**: Records session start, session end, transcription, and polish events in a local JSON Lines file (`diagnostics_history.jsonl`) with options to view live frames, add notes, and export logs.

---

## Architecture & Codebase Structure

The project is structured in Kotlin and Jetpack Compose following MVVM architecture:

```
app/src/main/java/com/example/
├── MainActivity.kt                       # Single Activity hosting Jetpack Compose navigation
├── audio/
│   ├── AudioRecorder.kt                 # Manages AudioRecord hardware capture and amplitude calculation
│   └── HighPassFilter.kt                # Butterworth high-pass audio filter implementation
├── data/
│   ├── AppLogRepository.kt              # Durable JSONL diagnostics logger (diagnostics_history.jsonl)
│   ├── AudioRecordingRepository.kt      # Local WAV audio recording manager with 3-day / 500MB cleanup
│   ├── CustomVocabularyRepository.kt    # SharedPreferences storage for vocabulary terms and metadata
│   ├── HistoryRepository.kt             # SharedPreferences storage for past transcription history
│   └── Models.kt                        # Data models (ConnectionState, DiagnosticType, LogEntry, etc.)
├── service/
│   ├── AppClassifier.kt                 # Classifies foreground apps into Social, Work, AI, Other
│   ├── AppContextResolver.kt            # Resolves app names via package whitelist and window inspection
│   ├── FloatingBubbleManager.kt         # Global state holder for service status and keyboard visibility
│   ├── FloatingBubbleService.kt         # Foreground Service orchestrating the floating overlay lifecycle
│   ├── SmartVocabularyService.kt        # Vocabulary term extraction and frequency analysis
│   ├── VoxStreamAccessibilityService.kt # Accessibility Service for IME detection and text injection
│   └── floating/
│       ├── FloatingDictationSessionManager.kt # Manages AudioRecorder, WebSocket streaming, and timers
│       ├── FloatingHapticManager.kt           # Vibrator/VibratorManager feedback generator
│       ├── FloatingNotificationManager.kt     # NotificationChannel and foreground notification builder
│       ├── FloatingOverlayLifecycleOwner.kt   # Standalone LifecycleOwner for overlay ComposeView
│       ├── FloatingOverlayWindowManager.kt    # WindowManager overlay, drag gestures, and snap physics
│       └── FloatingPolishClient.kt            # HTTP REST client for multi-model Gemini text polishing
├── ui/
│   ├── VoiceTypingScreen.kt             # In-app recording screen, visualizers, and diagnostics panel
│   ├── VoiceTypingViewModel.kt          # ViewModel managing in-app dictation state and audio streaming
│   ├── components/
│   │   ├── DiagnosticsSheet.kt          # Bottom sheet displaying event logs and note-taking tools
│   │   ├── FinishingAnimationStyle.kt   # Animation styles for completion and polish states
│   │   ├── FloatingDictationBubbleOverlay.kt # Composable coordinating the floating popup overlay
│   │   ├── GlowAnimationStyle.kt        # Canvas draw functions for 21 ambient glow visualizer styles
│   │   ├── GlowStylesBottomSheet.kt     # Bottom sheet for selecting visualizer glow animations
│   │   ├── InAppVoiceTypingBubble.kt    # In-app voice typing card composable
│   │   ├── SmartVocabularyBottomSheet.kt# Bottom sheet for reviewing and cleaning vocabulary terms
│   │   ├── Visualizer.kt                # Audio waveform canvas composable
│   │   └── overlay/
│   │       ├── AuroraColorPalette.kt    # Dynamic system accent palette extraction
│   │       ├── FloatingActionRow.kt     # Action buttons (Cancel, Polish, Complete) and app badge
│   │       ├── FloatingCollapsedBubble.kt# Collapsed floating bubble composable
│   │       ├── FloatingDockedLifebuoy.kt # Docked draggable bubble ring
│   │       ├── FloatingGlowEffects.kt   # Background glow canvas renderer for overlay card
│   │       ├── FloatingTranscriptBox.kt # Scrollable transcript text box with cursor
│   │       └── NotchedOverlayShape.kt   # Custom Shape geometry with top-right cutout notch
│   ├── screens/
│   │   ├── DictionaryScreen.kt          # Custom vocabulary management screen
│   │   └── HomeScreen.kt                # Main screen with history list, usage stats, and settings
│   └── theme/
│       ├── Color.kt                     # Baseline Light and Dark Material 3 color schemes
│       ├── Theme.kt                     # Application theme with dynamic color support
│       └── Type.kt                      # Typography configurations
└── websocket/
    └── GeminiLiveWebSocketClient.kt     # OkHttp WebSocket client for Gemini Live Bidi streaming
```

---

## Build & Setup Instructions

### Prerequisites
- **Android Studio**: Ladybug (2024.2.1) or newer
- **JDK**: Java 11 or higher (`sourceCompatibility` and `targetCompatibility` set to `JavaVersion.VERSION_11`)
- **Android SDK**: `compileSdk = 36`, `minSdk = 24`, `targetSdk = 36`
- **Gemini API Key**: An API key from [Google AI Studio](https://aistudio.google.com/)

### API Key Configuration
The project uses the Secrets Gradle Plugin to inject `GEMINI_API_KEY` into `BuildConfig`.

Configure the key in a root `.env` file (copied from `.env.example`):
```properties
GEMINI_API_KEY=your_actual_gemini_api_key
```

### Build Commands
```bash
# Run unit tests
./gradlew testDebugUnitTest

# Build Debug APK (Signed automatically with debug keystore)
./gradlew assembleDebug

# Build Release APK
# Release builds require valid signing credentials supplied via environment variables.
# Keystore files (*.jks, *.keystore) and passwords must never be committed to source control.
export KEYSTORE_PATH="/path/to/your-release-key.jks"
export STORE_PASSWORD="your-keystore-password"
export KEY_PASSWORD="your-key-password"
export KEY_ALIAS="upload" # Optional, defaults to "upload"

./gradlew assembleRelease
```

---

## Android Permissions

The application declares the following permissions in `app/src/main/AndroidManifest.xml`:

| Permission | Usage in Code |
|---|---|
| `android.permission.RECORD_AUDIO` | Required by `AudioRecord` in `AudioRecorder.kt` to capture microphone input for live transcription. |
| `android.permission.INTERNET` | Required by `OkHttpClient` and `HttpURLConnection` to communicate with Gemini Live WebSocket and REST endpoints. |
| `android.permission.SYSTEM_ALERT_WINDOW` | Required by `WindowManager` to display the floating overlay bubble across applications. |
| `android.permission.FOREGROUND_SERVICE` | Required to maintain background execution while the floating service is active. |
| `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` | Declared on Android 14+ (API 34+) with subtype property for the floating overlay service. |
| `android.permission.FOREGROUND_SERVICE_MICROPHONE` | Upgraded at runtime when microphone capture is actively running. |
| `android.permission.POST_NOTIFICATIONS` | Required on Android 13+ (API 33+) to post the ongoing foreground service notification. |
| `android.permission.WAKE_LOCK` | Used via `PowerManager` during active dictation sessions to prevent CPU sleep. |
| `android.permission.VIBRATE` | Used by `FloatingHapticManager.kt` to trigger vibration feedback. |

---

## Google Play Store Privacy & Data Safety Disclosures

The following disclosure information reflects the actual implementation in this repository for use in the Google Play Console Data Safety declaration:

### 1. Audio Data
- **Collection & Transfer**: Voice audio is recorded via the microphone while dictation is active and streamed over an encrypted network connection (TLS/WSS) to Google's Gemini API for speech-to-text processing.
- **Local Storage**: When the "Save Audio Recordings" setting is enabled in the app preferences (`AudioRecordingRepository.kt`), recorded audio is saved locally to internal application storage (`filesDir/recordings/`) as 16 kHz WAV files. Recordings are automatically deleted after 3 days or when the total folder size exceeds 500 MB. Users can manually delete, share, or export recordings.

### 2. User Text & Transcriptions
- **Local Storage**: Dictation transcripts and usage metrics are saved locally on the device in private SharedPreferences (`HistoryRepository.kt`).
- **Cloud Processing**: If the user taps the "Polish ✨" button, the transcript text is transmitted to the Gemini REST API (`FloatingPolishClient.kt`) to perform text cleanup and formatting according to the detected app category.

### 3. Diagnostics & Logs
- **Local Storage**: Application diagnostic events (e.g., session durations, connection state changes, and error codes) are stored locally in JSON Lines format (`filesDir/diagnostics_history.jsonl`). Logs are not transmitted automatically and can be exported or cleared by the user.

### 4. Accessibility Service Usage (`BIND_ACCESSIBILITY_SERVICE`)
`VoxStreamAccessibilityService` is an Accessibility Service used for:
- Detecting when an editable text field is focused and whether the software keyboard (IME) is visible.
- Inserting transcribed or polished text directly into the focused input field via `ACTION_SET_TEXT` or `ACTION_PASTE`.
- The service does not log, harvest, or transmit user keystrokes, passwords, or unrelated screen content.

---

## License

```
Copyright 2026 Prince Nii Thompson & VoxStream Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

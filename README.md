# VoxStream

VoxStream is a native Android voice-typing system powered by Google's Gemini Multimodal Live API. It provides low-latency bidirectional PCM audio streaming, real-time transcription, context-aware text polishing, and direct text injection into any input field across Android via an unclipped floating overlay and an Accessibility Service.

---

## Architecture & Folder Structure

```text
app/src/main/java/com/example/
├── MainActivity.kt                          # Single-activity host with edge-to-edge configuration
├── core/
│   ├── ApiConfig.kt                         # Central Gemini API keys and default model configurations
│   ├── AppConfig.kt                         # Audio capture rates, buffer constraints, and feature flags
│   └── ToneEngine.kt                        # Tone presets, system instructions, and vocabulary prompt builder
├── data/
│   ├── AppLogRepository.kt                  # Persistent multi-day JSONL logger for events, frames, and notes
│   ├── AudioRecordingRepository.kt          # Manages local WAV recordings cache, sharing, and Downloads export
│   ├── Database.kt                          # Room database instance for local vocabulary and history
│   ├── HistoryDao.kt & HistoryEntity.kt     # Room DAO and entity for past dictation transcripts
│   ├── Models.kt                            # Data models for LiveStats, ConnectionState, and LogEntries
│   └── VocabularyDao.kt & Entity.kt         # Room DAO and entity for custom replacement vocabulary
├── service/
│   ├── FloatingBubbleService.kt             # Foreground service managing the system overlay window lifecycle
│   ├── VoxStreamAccessibilityService.kt     # AccessibilityService detecting active input nodes and injecting text
│   └── floating/
│       ├── FloatingAudioRecorder.kt         # Microphone capture pipeline (16kHz 16-bit PCM) for overlay
│       ├── FloatingOverlayContent.kt        # Compose view coordinator hosted inside WindowManager
│       └── SmartSafeModeEngine.kt           # Auto-detection for password and sensitive input fields
├── ui/
│   ├── VoiceTypingScreen.kt                 # In-app dictation screen and visualizers
│   ├── VoiceTypingViewModel.kt              # ViewModel managing audio recording state and socket connectivity
│   ├── components/
│   │   ├── DiagnosticsSheet.kt              # Bottom sheet coordinating tabs and document export
│   │   ├── FloatingDictationBubbleOverlay.kt# Composable assembling the floating popup overlay
│   │   ├── InAppVoiceTypingBubble.kt        # Main in-app voice card composable
│   │   ├── diagnostics/
│   │   │   ├── DiagnosticsAudioSection.kt   # Local WAV recording player, timeline, and share/downloads
│   │   │   ├── DiagnosticsClearConfirmDialog.kt # Gated confirmation alert dialog for log deletion
│   │   │   ├── DiagnosticsEventTracker.kt   # Diagnostics tracking methods & day-grouped log history
│   │   │   ├── DiagnosticsExportUtils.kt    # Clipboard copying and text document export utilities
│   │   │   ├── DiagnosticsHistorySection.kt # Multi-day collapsible history logs and event badges
│   │   │   ├── DiagnosticsLiveSection.kt    # Real-time socket monitor, counters, and live frames
│   │   │   ├── DiagnosticsNotesSection.kt   # Field notes creation and chronological notes list
│   │   │   └── DiagnosticsToolbarSection.kt # Top action toolbar and weekly vocabulary simulation card
│   │   └── overlay/
│   │       ├── AuroraColorPalette.kt        # Dynamic system accent palette extraction
│   │       ├── FloatingActionRow.kt         # Action buttons (Cancel, Polish, Complete) and app badge
│   │       ├── FloatingCollapsedBubble.kt   # Collapsed floating bubble composable
│   │       ├── FloatingDockedLifebuoy.kt     # Docked draggable bubble ring
│   │       ├── FloatingTranscriptBox.kt     # Scrollable transcript text box with cursor
│   │       ├── NotchedOverlayShape.kt       # Custom Shape geometry with top-right cutout notch
│   │       └── sections/
│   │           ├── FloatingAuroraGlowSection.kt     # Aurora glow bloom background renderer
│   │           ├── FloatingButtonRowSection.kt      # Action buttons (Cancel, Polish, Complete) section
│   │           ├── FloatingDictationPopupState.kt   # State holder for popup animations and transitions
│   │           ├── FloatingNotchedCutoutSection.kt  # Semicircular cutout notch & docked ring section
│   │           └── FloatingTranscriptSection.kt     # Transcript display with normalization and metrics
│   ├── screens/
│   │   ├── DictionaryScreen.kt              # Custom vocabulary management screen
│   │   └── HomeScreen.kt                    # Dashboard with history list, usage stats, and settings
│   └── theme/                               # Material 3 Expressive theme, typography, and brush gradients
└── websocket/
    └── GeminiLiveWebSocketClient.kt         # OkHttp WebSocket client for Gemini Live Bidi streaming
```

---

## Background Services

- `FloatingBubbleService`: Displays a persistent floating overlay window over other applications and orchestrates foreground voice capture.
- `VoxStreamAccessibilityService`: Detects focused editable text fields on screen and directly injects finalized voice transcriptions into them.

---

## Permissions

- `RECORD_AUDIO`: Captures raw PCM microphone audio for live dictation and AI transcription.
- `INTERNET`: Establishes real-time secure WebSockets to the Gemini Live Multimodal API endpoint.
- `SYSTEM_ALERT_WINDOW`: Draws the floating dictation bubble overlay on top of other running apps.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` / `FOREGROUND_SERVICE_SPECIAL_USE`: Maintains uninterrupted audio streaming when dictating outside the app.
- `POST_NOTIFICATIONS`: Displays required ongoing notifications while background overlay recording is active.
- `VIBRATE`: Provides haptic feedback when dictation starts, pauses, or finishes.
- `WAKE_LOCK`: Prevents CPU sleep while streaming audio in the background.

---

## Privacy Note

Voice audio recorded during dictation is streamed directly over an encrypted WebSocket to Google's Gemini API solely for real-time speech-to-text transcription and requested text polishing. No audio or transcript data is sold or stored on external servers. Audio recordings cached locally on the device remain in private app storage unless the user explicitly chooses to share or export them.

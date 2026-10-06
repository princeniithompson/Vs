# VoxStream - Voice Typing & App Context Diagnostics

VoxStream is an Android floating voice dictation and app context detection system built with Kotlin, Jetpack Compose, and Material 3.

## Overview & Architecture

- **`com.example.service`**:
  - `FloatingBubbleService.kt`: Core overlay service managing floating dictation controls and bubble interaction.
  - `VoxStreamAccessibilityService.kt`: Accessibility service capturing active window titles, text node hierarchies, and interactive input fields.
  - `AppDetector.kt`: Multi-stage hybrid detector analyzing packages, window titles, and screen text to classify target apps.
  - `AppClassifier.kt`: Classifies detected apps using static rules, cached learnings, or Gemini AI prompts.
  - `FloatingTextInjector.kt`: Injects dictation text directly into target fields via accessibility node actions.

- **`com.example.data`**:
  - `AppDetectionLogRepository.kt`: Persistent repository recording granular app detection and classification events.
  - `HistoryRepository.kt`: Local dictation session history.
  - `AudioRecordingRepository.kt`: Handles high-pass filtered audio recording for speech recognition.
  - `CustomVocabularyRepository.kt`: Manages user vocabulary replacements.

- **`com.example.ui`**:
  - `VoiceTypingScreen.kt` & `HomeScreen.kt`: Primary UI screens for voice controls, vocabulary, and history.
  - `components/DiagnosticsSheet.kt`: Primary Diagnostics Hub providing access to diagnostic tools.
  - `screens/AppDetectionDiagnosticsScreen.kt`: Dedicated App Detection Diagnostics screen inspecting raw package names, window titles, extracted node text, classification sources, and AI prompts/responses.

## Recent Updates
- Fixed Gemini detection in `AppDetector.kt` when running inside Google QuickSearchBox or Bard while preserving WebAPK cascade and native app detection.
- Replaced Text Injection Diagnostics with a dedicated App Detection Diagnostics Page.
- Implemented 4-Signal WebAPK Detection Cascade (Activity fingerprinting, URL bar presence check, WebAPK Inventory matching, and UsageStats recency correlation with sticky session hysteresis).
- Added `PACKAGE_USAGE_STATS` permission and package installation broadcast receiver to dynamically index WebAPKs (`org.chromium.webapk.*`).
- Integrated `AppDetectionLogRepository` to capture every bubble activation, window title query, and classification attempt.
- Added "Copy Event", "Copy All", and "Clear Logs" actions to the App Detection Diagnostics view.

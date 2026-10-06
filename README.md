# VoxStream - Voice Typing Assistant

VoxStream is an advanced Android voice typing application featuring an Everywhere Floating Voice Ring, multi-signal evidence-based App Detection, AI Polish editing styles, and granular App Detection Diagnostics.

## Architecture & Folder Structure
- `com.example.service/`: Accessibility service, floating bubble service, app detector, and classifiers.
- `com.example.data/`: Local repositories (History, Custom Vocabulary, App Logs, Audio Recordings, App Detection Logs).
- `com.example.ui/`: Jetpack Compose screens, components, themes, and ViewModel.
- `com.example.util/`: App resolution engine and utilities.

## Key Features
- **Everywhere Floating Voice Ring**: Floats over all apps for instant voice dictation.
- **App Detection & Resolution Improvements**:
  - **Package Visibility (`QUERY_ALL_PACKAGES`)**: Added broad package query permissions in `AndroidManifest.xml` so `PackageManager.getApplicationLabel()` can resolve installed app titles and WebAPKs across Android 11+ sandboxes.
  - **Interactive Window Titles (`flagRetrieveInteractiveWindows`)**: Enabled interactive window retrieval in `accessibility_service_config.xml` and `VoxStreamAccessibilityService.kt` to extract real window titles.
  - **App Name Resolution**: Direct PackageManager label lookup for installed apps and WebAPKs, Gemini detection for Search/Bard/Gemini, and browser site product fallback.
  - **Log Deduplication**: Added deduplication in `AppDetectionLogRepository.kt` to skip logging redundant events unless package name, resolved app name, or window title changes.
- **App Detection Diagnostics**: Granular tracking of app detection metadata (raw package name, window title, accessibility node text snippets, classification source, and Gemini AI prompt/response traces).
- **Audio Recordings & History**: Persistent local tracking of transcriptions, audio WAV recordings, and custom vocabulary.
- **Smart Safe Mode**: Protects sensitive banking and credential apps.

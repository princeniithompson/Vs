package com.example.service.floating

import com.example.audio.AudioRecorder

/**
 * FloatingAudioRecorder is the specialized audio capture pipeline for the floating dictation overlay.
 * Uses clean VOICE_RECOGNITION audio source with gentle 75Hz high-pass filter for diagnostic baseline.
 */
typealias FloatingAudioRecorder = AudioRecorder

package com.example.service.floating

import com.example.audio.AudioRecorder

/**
 * FloatingAudioRecorder is the specialized audio capture pipeline for the floating dictation overlay.
 * Uses VOICE_COMMUNICATION audio source with hardware AEC, NoiseSuppressor, and 120Hz high-pass filtering.
 */
typealias FloatingAudioRecorder = AudioRecorder

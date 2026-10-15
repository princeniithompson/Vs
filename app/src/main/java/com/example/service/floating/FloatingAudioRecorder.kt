package com.example.service.floating

import com.example.audio.AudioRecorder

/**
 * FloatingAudioRecorder is the audio capture pipeline for the floating dictation overlay.
 * Uses Whisperflow production pipeline with AudioSource.MIC and zero client DSP.
 */
typealias FloatingAudioRecorder = AudioRecorder

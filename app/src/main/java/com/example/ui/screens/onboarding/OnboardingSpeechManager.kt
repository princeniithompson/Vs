package com.example.ui.screens.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Lightweight on-device speech recognizer used strictly during onboarding sandbox.
 * Transcribes the user's real voice without needing any API key, completely eliminating fabricated text.
 */
class OnboardingSpeechManager(private val context: Context) {

    private val tag = "OnboardingSpeechManager"

    private var speechRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    fun startListening() {
        stopListening()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(tag, "SpeechRecognizer not available on device")
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(tag, "onReadyForSpeech")
                        _isListening.value = true
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(tag, "onBeginningOfSpeech")
                        _isListening.value = true
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        // Map rmsdB (typically -2 to 10) to 0.0..1.0 range
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        _amplitude.value = normalized
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(tag, "onEndOfSpeech")
                        _isListening.value = false
                        _amplitude.value = 0f
                    }

                    override fun onError(error: Int) {
                        Log.w(tag, "SpeechRecognizer error: $error")
                        _isListening.value = false
                        _amplitude.value = 0f
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull().orEmpty()
                        if (com.example.BuildConfig.DEBUG) {
                            Log.d(tag, "onResults: $text")
                        }
                        if (text.isNotBlank()) {
                            _liveTranscript.value = text
                        }
                        _isListening.value = false
                        _amplitude.value = 0f
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull().orEmpty()
                        if (com.example.BuildConfig.DEBUG) {
                            Log.d(tag, "onPartialResults: $partial")
                        }
                        if (partial.isNotBlank()) {
                            _liveTranscript.value = partial
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            speechRecognizer?.startListening(intent)
            _isListening.value = true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start on-device SpeechRecognizer", e)
            _isListening.value = false
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(tag, "Error destroying speech recognizer", e)
        } finally {
            speechRecognizer = null
            _isListening.value = false
            _amplitude.value = 0f
        }
    }

    fun clearTranscript() {
        _liveTranscript.value = ""
    }

    fun updateTranscript(text: String) {
        _liveTranscript.value = text
    }
}

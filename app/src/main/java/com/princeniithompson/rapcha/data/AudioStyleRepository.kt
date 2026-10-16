package com.princeniithompson.rapcha.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Microphone sensitivity capture modes.
 * Controls how close the phone must be to the mouth before the app reacts.
 */
enum class AudioStyleMode(
    val key: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val isDefault: Boolean,
    val openSnrDb: Float,
    val holdSnrDb: Float,
    val minOpenRms: Float,
    val minHoldRms: Float,
    val minSpeechAmplitude: Float,
    val noiseDeltaThreshold: Float,
    val gainMultiplier: Float,
    val hangoverMs: Long,
    val icon: ImageVector,
    val whisperMinRms: Float = 60.0f,
    val whisperMinZcr: Float = 0.20f,
    val whisperMinPeak: Float = 160.0f,
    val minCrestFactor: Float = 2.2f,
    val requiresPitchLock: Boolean = false
) {
    BALANCED(
        key = "balanced_guard",
        title = "Balanced Guard (Default)",
        subtitle = "Dynamic Noise Floor • Proximity & Whisper",
        description = "Dynamically tracks ambient room noise. Mutes background workplace chatter, typing, and room echo to complete silence. Detects natural voice and breathy whispers when close.",
        isDefault = true,
        openSnrDb = 10.0f,
        holdSnrDb = 2.0f,
        minOpenRms = 180.0f,
        minHoldRms = 30.0f,
        minSpeechAmplitude = 0.020f,
        noiseDeltaThreshold = 0.015f,
        gainMultiplier = 1.0f,
        hangoverMs = 300L,
        icon = Icons.Filled.Tune,
        whisperMinRms = 55.0f,
        whisperMinZcr = 0.18f,
        whisperMinPeak = 140.0f,
        minCrestFactor = 2.0f,
        requiresPitchLock = false
    ),
    CLOSE_TALK(
        key = "strict_blocker",
        title = "Strict Noise Blocker",
        subtitle = "Ultra Tight • Proximity & Breath Isolation",
        description = "Maximum acoustic isolation. Only triggers when speaking right next to the mic (1–4 cm) or whispering into the mic. Completely rejects nearby talkers, TV, and cafe noise.",
        isDefault = false,
        openSnrDb = 13.5f,
        holdSnrDb = 2.5f,
        minOpenRms = 400.0f,
        minHoldRms = 55.0f,
        minSpeechAmplitude = 0.040f,
        noiseDeltaThreshold = 0.020f,
        gainMultiplier = 1.0f,
        hangoverMs = 350L,
        icon = Icons.Filled.PhoneInTalk,
        whisperMinRms = 65.0f,
        whisperMinZcr = 0.20f,
        whisperMinPeak = 190.0f,
        minCrestFactor = 2.3f,
        requiresPitchLock = false
    ),
    SMART_VOICE(
        key = "smart_voice_blocker",
        title = "Smart Voice Blocker",
        subtitle = "Speaker Voice Lock • Pitch & Harmonic Filter",
        description = "Auto-locks onto your voice pitch on your first words. Holds your voice lock across 30s+ pauses. Blocks all background chatter, coworkers, and TV.",
        isDefault = false,
        openSnrDb = 11.5f,
        holdSnrDb = 2.0f,
        minOpenRms = 260.0f,
        minHoldRms = 40.0f,
        minSpeechAmplitude = 0.030f,
        noiseDeltaThreshold = 0.018f,
        gainMultiplier = 1.0f,
        hangoverMs = 350L,
        icon = Icons.Filled.RecordVoiceOver,
        whisperMinRms = 60.0f,
        whisperMinZcr = 0.19f,
        whisperMinPeak = 165.0f,
        minCrestFactor = 2.1f,
        requiresPitchLock = true
    );

    companion object {
        fun fromKey(key: String?): AudioStyleMode {
            return when {
                key.equals("smart_voice_blocker", ignoreCase = true) ||
                key.equals("smart_voice", ignoreCase = true) ||
                key.equals("smart_blocker", ignoreCase = true) -> SMART_VOICE
                key.equals("strict_blocker", ignoreCase = true) ||
                key.equals("close_talk", ignoreCase = true) -> CLOSE_TALK
                else -> BALANCED
            }
        }
    }
}

object AudioStyleRepository {
    private const val TAG = "AudioStyleRepo"
    private const val PREFS_NAME = "voxstream_settings"
    private const val KEY_AUDIO_STYLE = "audio_style_mode"

    private var prefs: SharedPreferences? = null

    private val _currentStyle = MutableStateFlow(AudioStyleMode.BALANCED)
    val currentStyle: StateFlow<AudioStyleMode> = _currentStyle.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = sp
            val savedKey = sp.getString(KEY_AUDIO_STYLE, AudioStyleMode.BALANCED.key)
            val restored = AudioStyleMode.fromKey(savedKey)
            _currentStyle.value = restored
            Log.d(TAG, "Initialized AudioStyleRepository with mode: ${restored.title}")
        }
    }

    fun setStyle(mode: AudioStyleMode) {
        _currentStyle.value = mode
        prefs?.edit()?.putString(KEY_AUDIO_STYLE, mode.key)?.apply()
        Log.i(TAG, "Audio Style changed to: ${mode.title} (openSnr=${mode.openSnrDb}dB, minOpenRms=${mode.minOpenRms})")
        AppLogRepository.logEvent(
            DiagnosticSource.APP,
            DiagnosticType.AUDIO_STYLE_CHANGED,
            "Audio Style set to ${mode.title} (${mode.subtitle})"
        )
    }

    fun getActiveStyle(): AudioStyleMode = _currentStyle.value
}

package com.example.service.floating

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

enum class FloatingHapticType {
    TRANSCRIPTION_START,
    FINAL_SENTENCE,
    TRANSCRIPTION_STOP,
    BUBBLE_HOLD,
    BUBBLE_MOVE,
    BUBBLE_SETTLE
}

object FloatingHapticManager {
    private const val TAG = "FloatingHaptic"

    fun trigger(context: Context, type: FloatingHapticType) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when (type) {
                    FloatingHapticType.TRANSCRIPTION_START -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                        } else {
                            VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                        }
                    }
                    FloatingHapticType.FINAL_SENTENCE -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                        } else {
                            VibrationEffect.createOneShot(20, 150)
                        }
                    }
                    FloatingHapticType.TRANSCRIPTION_STOP -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                        } else {
                            VibrationEffect.createWaveform(longArrayOf(0, 40, 40, 40), -1)
                        }
                    }
                    FloatingHapticType.BUBBLE_HOLD -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                        } else {
                            VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                        }
                    }
                    FloatingHapticType.BUBBLE_MOVE -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                        } else {
                            VibrationEffect.createOneShot(12, 100)
                        }
                    }
                    FloatingHapticType.BUBBLE_SETTLE -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                        } else {
                            VibrationEffect.createOneShot(40, 180)
                        }
                    }
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val millis = when (type) {
                    FloatingHapticType.TRANSCRIPTION_START -> 50L
                    FloatingHapticType.FINAL_SENTENCE -> 20L
                    FloatingHapticType.TRANSCRIPTION_STOP -> 60L
                    FloatingHapticType.BUBBLE_HOLD -> 30L
                    FloatingHapticType.BUBBLE_MOVE -> 12L
                    FloatingHapticType.BUBBLE_SETTLE -> 40L
                }
                vibrator.vibrate(millis)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error executing haptic vibration", e)
        }
    }
}

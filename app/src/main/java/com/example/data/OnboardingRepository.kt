package com.example.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages user onboarding state and smart defaults persistence.
 */
object OnboardingRepository {
    private const val PREFS_NAME = "voxstream_onboarding_prefs"
    private const val KEY_ONBOARDING_COMPLETED = "key_onboarding_completed"
    private const val KEY_PREFERRED_DOCK_SIDE = "key_preferred_dock_side"
    private const val KEY_VOICE_TEST_DONE = "key_voice_test_done"

    private var prefs: SharedPreferences? = null

    private val _isOnboardingCompleted = MutableStateFlow(false)
    val isOnboardingCompleted: StateFlow<Boolean> = _isOnboardingCompleted.asStateFlow()

    private val _preferredDockSide = MutableStateFlow("RIGHT")
    val preferredDockSide: StateFlow<String> = _preferredDockSide.asStateFlow()

    private val _hasCompletedVoiceTest = MutableStateFlow(false)
    val hasCompletedVoiceTest: StateFlow<Boolean> = _hasCompletedVoiceTest.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { sp ->
                _isOnboardingCompleted.value = sp.getBoolean(KEY_ONBOARDING_COMPLETED, false)
                _preferredDockSide.value = sp.getString(KEY_PREFERRED_DOCK_SIDE, "RIGHT") ?: "RIGHT"
                _hasCompletedVoiceTest.value = sp.getBoolean(KEY_VOICE_TEST_DONE, false)
            }
        }
    }

    fun setVoiceTestDone(done: Boolean) {
        _hasCompletedVoiceTest.value = done
        prefs?.edit()?.putBoolean(KEY_VOICE_TEST_DONE, done)?.apply()
    }

    fun setPreferredDockSide(side: String) {
        val validSide = if (side.equals("LEFT", ignoreCase = true)) "LEFT" else "RIGHT"
        _preferredDockSide.value = validSide
        prefs?.edit()?.putString(KEY_PREFERRED_DOCK_SIDE, validSide)?.apply()
    }

    fun completeOnboarding() {
        _isOnboardingCompleted.value = true
        prefs?.edit()?.putBoolean(KEY_ONBOARDING_COMPLETED, true)?.apply()
    }

    fun resetOnboarding() {
        _isOnboardingCompleted.value = false
        prefs?.edit()?.putBoolean(KEY_ONBOARDING_COMPLETED, false)?.apply()
    }
}

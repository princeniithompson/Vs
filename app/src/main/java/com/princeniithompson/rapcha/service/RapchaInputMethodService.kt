package com.princeniithompson.rapcha.service

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.InputConnection

/**
 * A silent, passive InputMethodService that acts as a text injection engine.
 * It never shows a visual UI and operates as part of the hybrid input engine.
 */
class RapchaInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "RapchaIME"

        @Volatile
        var instance: RapchaInputMethodService? = null
            private set

        val isServiceActive: Boolean
            get() = instance != null && instance?.currentInputStarted == true

        fun commitText(text: String): Boolean {
            val ime = instance ?: run {
                Log.d(TAG, "RapchaIME unavailable: instance is null")
                return false
            }
            if (!ime.currentInputStarted) {
                Log.d(TAG, "RapchaIME unavailable: currentInputStarted is false")
                return false
            }
            return try {
                val ic = ime.currentInputConnection ?: run {
                    Log.d(TAG, "RapchaIME unavailable: currentInputConnection is null")
                    return false
                }
                val success = ic.commitText(text, 1)
                Log.d(TAG, "RapchaIME commitText result: $success, len=${text.length}")
                success
            } catch (e: Exception) {
                Log.w(TAG, "RapchaIME commitText failed with exception: ${e.message}", e)
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "RapchaInputMethodService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "RapchaInputMethodService destroyed")
    }

    override fun onCreateInputView(): View? {
        // Return null to ensure no visual keyboard UI is ever shown
        return null
    }

    override fun onCreateCandidatesView(): View? {
        // Return null to ensure no candidates view is shown
        return null
    }
}

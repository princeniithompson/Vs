package com.example.service

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.InputConnection

/**
 * A silent, passive InputMethodService that acts as a text injection engine.
 * It never shows a visual UI and operates as part of the hybrid input engine.
 */
class VoxStreamInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "VoxStreamIME"

        @Volatile
        var instance: VoxStreamInputMethodService? = null
            private set

        fun commitText(text: String): Boolean {
            val ime = instance ?: return false
            return try {
                val ic = ime.currentInputConnection ?: return false
                val success = ic.commitText(text, 1)
                Log.d(TAG, "VoxStreamIME commitText result: $success, len=${text.length}")
                success
            } catch (e: Exception) {
                Log.w(TAG, "VoxStreamIME commitText failed: ${e.message}")
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "VoxStreamInputMethodService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "VoxStreamInputMethodService destroyed")
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

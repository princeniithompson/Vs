package com.princeniithompson.rapcha.service.floating

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notification actions for a vocabulary suggestion. The app does not add the word itself. */
class VocabSuggestionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val key = intent.getStringExtra(VocabSuggestions.EXTRA_KEY) ?: return
        val display = intent.getStringExtra(VocabSuggestions.EXTRA_DISPLAY).orEmpty()
        VocabSuggestions.handleAction(context, action, key, display)
    }
}

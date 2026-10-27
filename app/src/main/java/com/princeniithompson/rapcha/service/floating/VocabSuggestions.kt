package com.princeniithompson.rapcha.service.floating

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.princeniithompson.rapcha.MainActivity
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.data.VocabSuggestionPolicy
import com.princeniithompson.rapcha.data.VocabSuggestionStore

/**
 * Asks, after a finished session, whether a repeated spelling should be saved.
 * Yes is the only path that writes the dictionary. Edit opens the existing
 * add-word dialog so the user can fix the spelling first.
 */
object VocabSuggestions {
    private const val TAG = "VocabSuggestions"

    const val ACTION_YES = "com.princeniithompson.rapcha.vocab.YES"
    const val ACTION_EDIT = "com.princeniithompson.rapcha.vocab.EDIT"
    const val ACTION_NO = "com.princeniithompson.rapcha.vocab.NO"
    const val ACTION_DISMISS = "com.princeniithompson.rapcha.vocab.DISMISS"
    const val EXTRA_KEY = "vocab_suggestion_key"
    const val EXTRA_DISPLAY = "vocab_suggestion_display"

    /**
     * Call only after the final text has been injected, or after a Safe Mode
     * clipboard cutoff has copied it. Blank text is ignored.
     */
    fun onOutgoingText(context: Context, text: String) {
        if (text.isBlank()) return
        try {
            val app = context.applicationContext
            ensureReady(app)
            val now = System.currentTimeMillis()
            val suggestion = VocabSuggestionStore.mutate { records ->
                val result = VocabSuggestionPolicy.observe(
                    text = text,
                    records = records,
                    dictionaryTerms = CustomVocabularyRepository.getVocabulary(),
                    nowMs = now
                )
                result.records to result.suggestion
            } ?: return

            val posted = DictationAlerts.vocabularySuggestion(
                context = app,
                notificationId = notificationIdFor(suggestion.key),
                display = suggestion.display,
                sessionCount = suggestion.sessionCount,
                yes = broadcastIntent(app, ACTION_YES, suggestion),
                edit = editActivityIntent(app, suggestion),
                no = broadcastIntent(app, ACTION_NO, suggestion),
                dismiss = broadcastIntent(app, ACTION_DISMISS, suggestion)
            )
            if (!posted) {
                VocabSuggestionStore.save(
                    VocabSuggestionPolicy.clearPrompt(
                        VocabSuggestionStore.load(),
                        suggestion.key
                    )
                )
                return
            }
            Log.i(TAG, "Suggested a dictionary word after ${suggestion.sessionCount} sessions")
        } catch (t: Throwable) {
            Log.w(TAG, "Vocabulary suggestion was skipped", t)
        }
    }

    fun handleAction(context: Context, action: String, key: String, display: String) {
        if (key.isBlank()) return
        try {
            val app = context.applicationContext
            ensureReady(app)
            val now = System.currentTimeMillis()
            when (action) {
                ACTION_YES -> {
                    val term = display.trim().ifBlank { key }
                    if (term.isNotBlank()) {
                        CustomVocabularyRepository.addTerm(term)
                    }
                    VocabSuggestionStore.save(
                        VocabSuggestionPolicy.accept(VocabSuggestionStore.load(), key)
                    )
                    DictationAlerts.cancel(app, notificationIdFor(key))
                }
                ACTION_NO, ACTION_DISMISS -> {
                    VocabSuggestionStore.save(
                        VocabSuggestionPolicy.dismiss(VocabSuggestionStore.load(), key, now)
                    )
                    DictationAlerts.cancel(app, notificationIdFor(key))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Vocabulary suggestion action failed", t)
        }
    }

    fun notificationIdFor(key: String): Int = 3_000 + (key.hashCode() and 0x7FFF)

    /** Edit opened the dictionary. The word is still not saved. */
    fun onEditOpened(context: Context, key: String) {
        if (key.isBlank()) return
        try {
            val app = context.applicationContext
            ensureReady(app)
            VocabSuggestionStore.save(
                VocabSuggestionPolicy.noteEditOpened(
                    VocabSuggestionStore.load(),
                    key,
                    System.currentTimeMillis()
                )
            )
            DictationAlerts.cancel(app, notificationIdFor(key))
        } catch (t: Throwable) {
            Log.w(TAG, "Could not record an edit open", t)
        }
    }

    private fun ensureReady(context: Context) {
        CustomVocabularyRepository.init(context)
        VocabSuggestionStore.init(context)
    }

    private fun editActivityIntent(
        context: Context,
        suggestion: VocabSuggestionPolicy.Suggestion
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_KEY, suggestion.key)
            putExtra(MainActivity.EXTRA_DICTIONARY_DRAFT, suggestion.display)
        }
        return PendingIntent.getActivity(
            context,
            requestCode(ACTION_EDIT, suggestion.key),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun broadcastIntent(
        context: Context,
        action: String,
        suggestion: VocabSuggestionPolicy.Suggestion
    ): PendingIntent {
        val intent = Intent(context, VocabSuggestionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_KEY, suggestion.key)
            putExtra(EXTRA_DISPLAY, suggestion.display)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(action, suggestion.key),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCode(action: String, key: String): Int {
        return (action + "\u0000" + key).hashCode()
    }
}

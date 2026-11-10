package com.princeniithompson.rapcha.service.floating

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.princeniithompson.rapcha.data.HistoryRepository
import com.princeniithompson.rapcha.data.LyraRating

/** Notification Up / Down actions. An empty comment still saves the rating. */
class LyraReviewReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return
        val comment = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(EXTRA_COMMENT)
            ?.toString()
        val rating = when (action) {
            ACTION_UP -> LyraRating.UP
            ACTION_DOWN -> LyraRating.DOWN
            else -> return
        }
        HistoryRepository.init(context)
        HistoryRepository.updateLyraReview(sessionId, rating, comment = comment)
        LyraReviewPrompt.dismiss()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.cancel(LyraReviewPrompt.notificationId(sessionId))
    }

    companion object {
        const val ACTION_UP = "com.princeniithompson.rapcha.LYRA_UP"
        const val ACTION_DOWN = "com.princeniithompson.rapcha.LYRA_DOWN"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_COMMENT = "lyra_comment"
    }
}

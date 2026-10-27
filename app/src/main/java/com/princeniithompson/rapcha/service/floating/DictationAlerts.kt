package com.princeniithompson.rapcha.service.floating

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.princeniithompson.rapcha.R

/**
 * High-importance dictation alerts. These are separate from the ongoing
 * foreground-service notification. If notification permission is denied,
 * the matching haptic still runs.
 */
object DictationAlerts {
    const val CHANNEL_ID = "rapcha_dictation_alerts"
    const val NOTIFICATION_ID = 2002

    fun endingSoon(context: Context) {
        alert(context, "1 minute left in this session", FloatingHapticType.ENDING_SOON)
    }

    fun sessionEnded(context: Context, copied: Boolean) {
        val text = if (copied) {
            "Session ended at 10 minutes. Your text was copied"
        } else {
            "Session ended at 10 minutes. Your text was typed in"
        }
        alert(context, text, FloatingHapticType.ENDED)
    }

    fun connectionDropped(context: Context) {
        alert(context, "Connection dropped. Audio is not being sent.", FloatingHapticType.PROBLEM)
    }

    fun microphoneFailed(context: Context) {
        alert(context, "Microphone isn't capturing audio.", FloatingHapticType.PROBLEM)
    }

    fun finalTextLost(context: Context) {
        alert(context, "Final transcript was lost.", FloatingHapticType.PROBLEM)
    }

    fun placementFailed(context: Context) {
        alert(context, "Rapcha couldn't place your text.", FloatingHapticType.PROBLEM)
    }

    fun textCopied(context: Context) {
        alert(context, "Your text was copied.", FloatingHapticType.ENDED)
    }

    /**
     * A normal suggestion with Yes / Edit / No. No haptic: this is not an error.
     * Returns false when notification permission is missing so the caller can ask again later.
     */
    fun vocabularySuggestion(
        context: Context,
        notificationId: Int,
        display: String,
        sessionCount: Int,
        yes: PendingIntent,
        edit: PendingIntent,
        no: PendingIntent,
        dismiss: PendingIntent
    ): Boolean {
        if (!canPost(context)) return false
        createChannel(context)
        val text = "Add \"$display\" to custom vocabulary?"
        val detail = "You've used \"$display\" in $sessionCount sessions. Add it to your dictionary?"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rapcha")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(edit)
            .setDeleteIntent(dismiss)
            .addAction(NotificationCompat.Action(0, "Yes", yes))
            .addAction(NotificationCompat.Action(0, "Edit", edit))
            .addAction(NotificationCompat.Action(0, "No", no))
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
        return true
    }

    fun cancel(context: Context, notificationId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
    }

    private fun alert(context: Context, text: String, haptic: FloatingHapticType) {
        FloatingHapticManager.trigger(context, haptic)
        if (!canPost(context)) return
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rapcha")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dictation alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Warnings when a dictation is about to end or something goes wrong"
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}

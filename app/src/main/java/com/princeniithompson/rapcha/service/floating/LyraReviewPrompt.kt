package com.princeniithompson.rapcha.service.floating

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.data.LyraRating
import com.princeniithompson.rapcha.data.LyraReviewPolicy
import com.princeniithompson.rapcha.data.LyraReviewTag
import com.princeniithompson.rapcha.data.VoiceSessionRecord

/**
 * Asks for a Lyra rating after the text has already been inserted.
 * The card does not take focus. Ignoring it leaves the session unrated.
 */
object LyraReviewPrompt {
    const val CHANNEL_ID = "rapcha_lyra_review"
    private val main = Handler(Looper.getMainLooper())
    private var card: View? = null
    private var windowManager: WindowManager? = null
    private var hide: Runnable? = null

    fun show(context: Context, record: VoiceSessionRecord) {
        if (!LyraReviewPolicy.shouldPrompt(record)) return
        val app = context.applicationContext
        postNotification(app, record)
        main.post { showCard(context, record) }
    }

    fun dismiss() {
        hide?.let { main.removeCallbacks(it) }
        hide = null
        val view = card
        val manager = windowManager
        card = null
        windowManager = null
        if (view != null && manager != null) {
            runCatching { manager.removeView(view) }
        }
    }

    private fun postNotification(context: Context, record: VoiceSessionRecord) {
        if (!DictationAlerts.canPost(context)) return
        createChannel(context)
        val up = pending(context, record.sessionId, LyraReviewReceiver.ACTION_UP, 1)
        val down = pending(context, record.sessionId, LyraReviewReceiver.ACTION_DOWN, 2)
        val upInput = RemoteInput.Builder(LyraReviewReceiver.EXTRA_COMMENT)
            .setLabel("Optional comment")
            .build()
        val downInput = RemoteInput.Builder(LyraReviewReceiver.EXTRA_COMMENT)
            .setLabel(LyraReviewPolicy.DOWN_PROMPT)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("How did Lyra do?")
            .setContentText("Tap Up or Down. A comment is optional.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Lyra finished in ${record.appName}. Up or Down. ${LyraReviewPolicy.DOWN_PROMPT}"
            ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(
                NotificationCompat.Action.Builder(0, "Up", up)
                    .addRemoteInput(upInput)
                    .build()
            )
            .addAction(
                NotificationCompat.Action.Builder(0, "Down", down)
                    .addRemoteInput(downInput)
                    .build()
            )
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId(record.sessionId), notification)
    }

    private fun showCard(context: Context, record: VoiceSessionRecord) {
        dismiss()
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val selected = LinkedHashSet<LyraReviewTag>()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
            }
            elevation = dp(6).toFloat()
        }
        val title = TextView(context).apply {
            text = "How did Lyra do?"
            setTextColor(Color.parseColor("#1A202C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        }
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val chipViews = LyraReviewTag.entries.map { tag ->
            tag to TextView(context).apply {
                text = tag.label
                setTextColor(Color.parseColor("#334155"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = chipBackground(false)
                setOnClickListener {
                    if (!selected.add(tag)) selected.remove(tag)
                    background = chipBackground(tag in selected)
                    com.princeniithompson.rapcha.data.HistoryRepository.updateLyraReview(
                        sessionId = record.sessionId,
                        rating = LyraRating.DOWN,
                        tags = selected.toList()
                    )
                    arm(keepOpen = true)
                }
            }
        }
        chipViews.forEach { (_, view) ->
            chips.addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun rate(rating: LyraRating) {
            com.princeniithompson.rapcha.data.HistoryRepository.updateLyraReview(
                sessionId = record.sessionId,
                rating = rating,
                tags = selected.toList(),
                comment = null
            )
            if (rating == LyraRating.DOWN) {
                chips.visibility = View.VISIBLE
                arm(keepOpen = true)
            } else {
                cancelNotification(context, record.sessionId)
                dismiss()
            }
        }
        actions.addView(actionButton(context, "Up") { rate(LyraRating.UP) })
        actions.addView(actionButton(context, "Down") { rate(LyraRating.DOWN) })
        actions.addView(actionButton(context, "Dismiss") { dismiss() })
        root.addView(title)
        root.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        root.addView(chips)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }
        try {
            manager.addView(root, params)
        } catch (_: Exception) {
            return
        }
        card = root
        windowManager = manager
        arm(keepOpen = false)
    }

    private fun arm(keepOpen: Boolean) {
        hide?.let { main.removeCallbacks(it) }
        if (keepOpen) {
            hide = null
            return
        }
        val task = Runnable { dismiss() }
        hide = task
        main.postDelayed(task, LyraReviewPolicy.AUTO_HIDE_MS)
    }

    private fun actionButton(context: Context, label: String, onClick: () -> Unit): TextView {
        val density = context.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        return TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#1D4ED8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(pad, pad / 2, pad, pad / 2)
            setOnClickListener { onClick() }
        }
    }

    private fun chipBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 40f
            setColor(if (selected) Color.parseColor("#DBEAFE") else Color.parseColor("#F1F5F9"))
        }
    }

    private fun pending(context: Context, sessionId: String, action: String, salt: Int): PendingIntent {
        val intent = Intent(context, LyraReviewReceiver::class.java).apply {
            this.action = action
            putExtra(LyraReviewReceiver.EXTRA_SESSION, sessionId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId(sessionId) + salt,
            intent,
            flags
        )
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Lyra review",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Rate a Lyra polish after it is inserted"
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun notificationId(sessionId: String): Int = 21_000 + (sessionId.hashCode() and 0x0FFF)

    private fun cancelNotification(context: Context, sessionId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId(sessionId))
    }
}

package com.princeniithompson.rapcha.service.lens

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.princeniithompson.rapcha.service.floating.FloatingNotificationManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds one MediaProjection for this process. Consent is requested only when
 * the lens needs OCR and accessibility found no text.
 */
object LensProjection {
    private val gate = Any()
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    var onReady: (() -> Unit)? = null

    fun hasConsent(): Boolean = synchronized(gate) { projection != null }

    fun hold(context: Context, projection: MediaProjection) {
        synchronized(gate) {
            releaseLocked()
            this.projection = projection
            val metrics = metrics(context)
            thread = HandlerThread("lens-capture").also { it.start() }
            handler = Handler(thread!!.looper)
            reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            display = projection.createVirtualDisplay(
                "rapcha-lens",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,
                null,
                handler
            )
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    release()
                }
            }, handler)
        }
        onReady?.invoke()
    }

    fun release() {
        synchronized(gate) { releaseLocked() }
    }

    /**
     * Crops a square around the lens center from the latest captured frame.
     * Returns null when capture is not active or no frame has arrived.
     */
    fun cropAround(centerX: Int, centerY: Int, radiusPx: Int): Bitmap? {
        val image = synchronized(gate) { reader?.acquireLatestImage() } ?: return null
        return try {
            val width = image.width
            val height = image.height
            val plane = image.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val full = Bitmap.createBitmap(
                width + rowPadding / pixelStride.coerceAtLeast(1),
                height,
                Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(buffer)
            val left = (centerX - radiusPx).coerceIn(0, width - 1)
            val top = (centerY - radiusPx).coerceIn(0, height - 1)
            val size = (radiusPx * 2).coerceAtMost(width - left).coerceAtMost(height - top).coerceAtLeast(1)
            Bitmap.createBitmap(full, left, top, size, size)
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun releaseLocked() {
        try {
            display?.release()
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        display = null
        reader = null
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun metrics(context: Context): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
    }
}

class LensConsentActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val start = Intent(this, LensCaptureService::class.java).apply {
                putExtra(LensCaptureService.EXTRA_CODE, result.resultCode)
                putExtra(LensCaptureService.EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(this, start)
        }
        finish()
    }

    private val launched = AtomicBoolean(false)

    override fun onStart() {
        super.onStart()
        if (launched.compareAndSet(false, true)) {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(manager.createScreenCaptureIntent())
        }
    }
}

class LensCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FloatingNotificationManager.createChannel(this)
        val notification: Notification = FloatingNotificationManager.buildNotification(
            this,
            "Screen text lens is active"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data != null) {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(code, data)
            if (projection != null) LensProjection.hold(this, projection)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LensProjection.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "lens_result_code"
        const val EXTRA_DATA = "lens_result_data"
        private const val NOTIFICATION_ID = 2002
    }
}

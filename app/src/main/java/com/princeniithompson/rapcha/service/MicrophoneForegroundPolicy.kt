package com.princeniithompson.rapcha.service

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Microphone foreground-service type is only requested while the process is
 * already in the foreground or already running as a foreground service.
 * Callers still have to catch a rejected startForeground and stop recording.
 */
internal object MicrophoneForegroundPolicy {
    fun isAllowed(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val importance = processImportance(context) ?: return true
        return importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }

    private fun processImportance(context: Context): Int? {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val mine = activityManager.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }
        return mine?.importance
    }
}

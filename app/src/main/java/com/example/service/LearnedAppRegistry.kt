package com.example.service

import android.content.Context
import android.util.Log
import org.json.JSONObject

data class LearnedAppEntry(
    val appName: String,
    val category: String,
    val confirmCount: Int = 1,
    val source: String = "gemini",
    val lastUpdatedMs: Long = System.currentTimeMillis()
)

/**
 * Persisted registry for dynamically learned application names and categories.
 * When Gemini classifies or confirms an unknown app/WebAPK, the result is saved
 * permanently into SharedPreferences so that subsequent sessions resolve with zero network latency.
 */
object LearnedAppRegistry {
    private const val TAG = "LearnedAppRegistry"
    private const val PREFS_NAME = "voxstream_learned_apps"
    const val CONFIRM_THRESHOLD = 1

    val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.canary",
        "org.chromium.chrome",
        "com.brave.browser",
        "com.microsoft.emmx",
        "org.mozilla.firefox"
    )

    fun isBrowserPackage(key: String?): Boolean {
        if (key.isNullOrBlank()) return false
        val kLower = key.trim().lowercase()
        return kLower in BROWSER_PACKAGES || kLower.startsWith("org.chromium.chrome")
    }

    fun get(context: Context, key: String): LearnedAppEntry? {
        if (key.isBlank()) return null
        val normalizedKey = key.trim().lowercase()

        // Never look up by raw browser package name
        if (isBrowserPackage(normalizedKey)) {
            return null
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(normalizedKey, null) 
            ?: prefs.getString(key.trim(), null) 
            ?: return null
        return try {
            val json = JSONObject(jsonStr)
            LearnedAppEntry(
                appName = json.getString("appName"),
                category = json.getString("category"),
                confirmCount = json.optInt("confirmCount", 1),
                source = json.optString("source", "gemini"),
                lastUpdatedMs = json.optLong("lastUpdatedMs", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse learned entry for $key", e)
            null
        }
    }

    fun isConfirmed(context: Context, key: String): Boolean {
        val entry = get(context, key) ?: return false
        return entry.confirmCount >= CONFIRM_THRESHOLD
    }

    fun recordConfirmation(
        context: Context,
        key: String,
        appName: String,
        category: String,
        source: String = "gemini"
    ): LearnedAppEntry {
        val normalizedKey = key.trim().lowercase()
        val isBrowser = isBrowserPackage(normalizedKey)
        val existing = get(context, normalizedKey)
        val sameCategory = existing != null && existing.category.equals(category, ignoreCase = true)
        val sameName = existing != null && existing.appName.trim().equals(appName.trim(), ignoreCase = true)

        val newCount = if (sameCategory && sameName) {
            existing!!.confirmCount + 1
        } else {
            1
        }

        val updated = LearnedAppEntry(
            appName = appName.trim(),
            category = category.trim(),
            confirmCount = newCount,
            source = source,
            lastUpdatedMs = System.currentTimeMillis()
        )

        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = JSONObject().apply {
                put("appName", updated.appName)
                put("category", updated.category)
                put("confirmCount", updated.confirmCount)
                put("source", updated.source)
                put("lastUpdatedMs", updated.lastUpdatedMs)
            }
            val jsonString = json.toString()
            val editor = prefs.edit()
            if (!isBrowser) {
                editor.putString(normalizedKey, jsonString)
            }
            val appNameLower = appName.trim().lowercase()
            if (appNameLower.isNotBlank() && !isBrowserPackage(appNameLower) && appNameLower != "chrome" && appNameLower != "browser") {
                editor.putString(appNameLower, jsonString)
            }
            editor.apply()

            Log.d(TAG, "Learned entry saved: key='$normalizedKey', appName='${updated.appName}', category='${updated.category}', count=$newCount, source='${updated.source}'")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist learned entry for $key", e)
        }

        return updated
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

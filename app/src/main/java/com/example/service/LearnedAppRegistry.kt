package com.example.service

import android.content.Context
import android.util.Log
import org.json.JSONObject

data class LearnedAppEntry(
    val appName: String,
    val category: String,
    val confirmCount: Int,
    val lastUpdatedMs: Long = System.currentTimeMillis()
)

/**
 * Persisted registry for dynamically learned application names and categories.
 * When Gemini confirms the category for an unknown package or browser site 2-3 times,
 * the result is marked confirmed and subsequent detections resolve locally with zero network calls.
 */
object LearnedAppRegistry {
    private const val TAG = "LearnedAppRegistry"
    private const val PREFS_NAME = "voxstream_learned_apps"
    const val CONFIRM_THRESHOLD = 2

    fun get(context: Context, key: String): LearnedAppEntry? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(key, null) ?: return null
        return try {
            val json = JSONObject(jsonStr)
            LearnedAppEntry(
                appName = json.getString("appName"),
                category = json.getString("category"),
                confirmCount = json.optInt("confirmCount", 1),
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

    fun recordConfirmation(context: Context, key: String, appName: String, category: String): LearnedAppEntry {
        val existing = get(context, key)
        val newCount = if (existing != null && existing.category.equals(category, ignoreCase = true)) {
            existing.confirmCount + 1
        } else {
            1
        }

        val updated = LearnedAppEntry(
            appName = appName,
            category = category,
            confirmCount = newCount,
            lastUpdatedMs = System.currentTimeMillis()
        )

        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = JSONObject().apply {
                put("appName", updated.appName)
                put("category", updated.category)
                put("confirmCount", updated.confirmCount)
                put("lastUpdatedMs", updated.lastUpdatedMs)
            }
            prefs.edit().putString(key, json.toString()).apply()
            Log.d(TAG, "Learned key='$key' -> '${updated.appName}' ('${updated.category}'), count=$newCount, confirmed=${newCount >= CONFIRM_THRESHOLD}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist learned entry for $key", e)
        }

        return updated
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

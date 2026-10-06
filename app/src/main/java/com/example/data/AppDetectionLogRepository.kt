package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class AppDetectionEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val formattedTime: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp)),
    val rawPackageName: String = "Unknown",
    val rawWindowTitle: String? = null,
    val packageManagerLabel: String? = null,
    val topScreenTexts: List<String> = emptyList(),
    val resolvedAppName: String = "App",
    val classificationSource: String = "DEFAULT_FALLBACK", // "STATIC_DICTIONARY", "LEARNED_CACHE", "AI_MODEL", "DEFAULT_FALLBACK"
    val aiPromptSent: String? = null,
    val aiRawResponse: String? = null,
    val finalCategory: String = "OTHER"
)

object AppDetectionLogRepository {
    private const val TAG = "AppDetectionLogRepo"
    private const val PREFS_NAME = "voxstream_app_detection_logs"
    private const val KEY_LOGS = "saved_app_detection_logs"
    private const val MAX_SAVED_LOGS = 200

    private var prefs: SharedPreferences? = null
    private val _events = MutableStateFlow<List<AppDetectionEvent>>(emptyList())
    val events: StateFlow<List<AppDetectionEvent>> = _events.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadLogs()
        }
    }

    fun logEvent(event: AppDetectionEvent) {
        val current = _events.value.toMutableList()
        val lastEvent = current.firstOrNull()

        // Deduplication: skip if rawPackageName, resolvedAppName, and rawWindowTitle match the previous event
        if (lastEvent != null &&
            lastEvent.rawPackageName == event.rawPackageName &&
            lastEvent.resolvedAppName == event.resolvedAppName &&
            lastEvent.rawWindowTitle == event.rawWindowTitle
        ) {
            Log.d(TAG, "Skipping duplicate detection event: app='${event.resolvedAppName}', pkg='${event.rawPackageName}', title='${event.rawWindowTitle}'")
            return
        }

        current.add(0, event) // Newest first
        if (current.size > MAX_SAVED_LOGS) {
            current.removeAt(current.size - 1)
        }
        _events.value = current
        saveLogs(current)
        Log.d(TAG, "Logged app detection event: app='${event.resolvedAppName}' pkg='${event.rawPackageName}' title='${event.rawWindowTitle}' source='${event.classificationSource}' category='${event.finalCategory}'")
    }

    fun clearEvents() {
        _events.value = emptyList()
        saveLogs(emptyList())
        Log.d(TAG, "Cleared app detection logs")
    }

    fun exportAsFormattedText(): String = buildString {
        appendLine("=== VOXSTREAM APP DETECTION DIAGNOSTICS REPORT ===")
        appendLine("Exported at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        appendLine("Total Events: ${_events.value.size}")
        appendLine("==================================================")
        appendLine()
        _events.value.forEachIndexed { index, event ->
            appendLine("Event #${_events.value.size - index} [${event.formattedTime}]")
            appendLine("  Resolved App Name: ${event.resolvedAppName}")
            appendLine("  Final Category: ${event.finalCategory}")
            appendLine("  Classification Source: ${event.classificationSource}")
            appendLine("  Raw Package: ${event.rawPackageName}")
            appendLine("  Window Title: ${event.rawWindowTitle ?: "None"}")
            appendLine("  PackageManager Label: ${event.packageManagerLabel ?: "None"}")
            if (event.topScreenTexts.isNotEmpty()) {
                appendLine("  Top Screen Texts:")
                event.topScreenTexts.forEach { text ->
                    appendLine("    - $text")
                }
            }
            if (!event.aiPromptSent.isNullOrBlank()) {
                appendLine("  AI Prompt Sent:\n    ${event.aiPromptSent?.replace("\n", "\n    ")}")
            }
            if (!event.aiRawResponse.isNullOrBlank()) {
                appendLine("  AI Raw Response:\n    ${event.aiRawResponse?.replace("\n", "\n    ")}")
            }
            appendLine("--------------------------------------------------")
        }
    }

    private fun loadLogs() {
        val sp = prefs ?: return
        val rawJson = sp.getString(KEY_LOGS, null)
        if (rawJson.isNullOrBlank()) {
            _events.value = emptyList()
            return
        }

        try {
            val jsonArray = JSONArray(rawJson)
            val list = mutableListOf<AppDetectionEvent>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val textsJson = obj.optJSONArray("topScreenTexts")
                val texts = mutableListOf<String>()
                if (textsJson != null) {
                    for (j in 0 until textsJson.length()) {
                        texts.add(textsJson.getString(j))
                    }
                }
                val event = AppDetectionEvent(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    formattedTime = obj.optString("formattedTime", ""),
                    rawPackageName = obj.optString("rawPackageName", "Unknown"),
                    rawWindowTitle = if (obj.isNull("rawWindowTitle")) null else obj.optString("rawWindowTitle"),
                    packageManagerLabel = if (obj.isNull("packageManagerLabel")) null else obj.optString("packageManagerLabel"),
                    topScreenTexts = texts,
                    resolvedAppName = obj.optString("resolvedAppName", "App"),
                    classificationSource = obj.optString("classificationSource", "DEFAULT_FALLBACK"),
                    aiPromptSent = if (obj.isNull("aiPromptSent")) null else obj.optString("aiPromptSent"),
                    aiRawResponse = if (obj.isNull("aiRawResponse")) null else obj.optString("aiRawResponse"),
                    finalCategory = obj.optString("finalCategory", "OTHER")
                )
                list.add(event)
            }
            _events.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading app detection logs", e)
            _events.value = emptyList()
        }
    }

    private fun saveLogs(list: List<AppDetectionEvent>) {
        val sp = prefs ?: return
        try {
            val jsonArray = JSONArray()
            list.forEach { event ->
                val obj = JSONObject().apply {
                    put("id", event.id)
                    put("timestamp", event.timestamp)
                    put("formattedTime", event.formattedTime)
                    put("rawPackageName", event.rawPackageName)
                    put("rawWindowTitle", event.rawWindowTitle)
                    put("packageManagerLabel", event.packageManagerLabel)
                    put("topScreenTexts", JSONArray(event.topScreenTexts))
                    put("resolvedAppName", event.resolvedAppName)
                    put("classificationSource", event.classificationSource)
                    put("aiPromptSent", event.aiPromptSent)
                    put("aiRawResponse", event.aiRawResponse)
                    put("finalCategory", event.finalCategory)
                }
                jsonArray.put(obj)
            }
            sp.edit().putString(KEY_LOGS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving app detection logs", e)
        }
    }
}

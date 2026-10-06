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

/**
 * Data model for direct text-injection diagnostic events.
 */
data class InjectionEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val formattedTime: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp)),
    val targetPackage: String = "Unknown",
    val targetAppName: String = "App",
    val targetNodeClass: String? = null,
    val isFocused: Boolean = false,
    val isEditable: Boolean = false,
    val windowId: Int = -1,
    val textLength: Int = 0,
    val wordCount: Int = 0,
    val injectionMethod: String = "DIRECT_ACTION_SET_TEXT", // "DIRECT_ACTION_SET_TEXT", "NO_TARGET_NODE", "FALLBACK_CLIPBOARD"
    val resultDetails: String = "SUCCESS",
    val finalOutcome: String = "SUCCESS", // "SUCCESS" or "FAILED"
    val durationMs: Long = 0L,
    val rawTextPreview: String = ""
) {
    // Backwards compatibility helper property
    val tierAttempted: String get() = injectionMethod
    val tier1Result: String get() = resultDetails
    val tier2Result: String get() = "N/A"
    val tier3Result: String get() = resultDetails
    val tier4Result: String get() = "N/A"
}

/**
 * Repository to store, persist, and export text injection diagnostic logs.
 */
object InjectionLogRepository {
    private const val TAG = "InjectionLogRepo"
    private const val PREFS_NAME = "voxstream_injection_logs"
    private const val KEY_INJECTION_LOGS = "saved_injection_logs"
    private const val MAX_SAVED_LOGS = 200

    private var prefs: SharedPreferences? = null
    private val _logs = MutableStateFlow<List<InjectionEvent>>(emptyList())
    val logs: StateFlow<List<InjectionEvent>> = _logs.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadLogs()
        }
    }

    fun logInjection(event: InjectionEvent) {
        val current = _logs.value.toMutableList()
        current.add(0, event) // Add newest at beginning
        if (current.size > MAX_SAVED_LOGS) {
            current.removeAt(current.size - 1)
        }
        _logs.value = current
        saveLogs(current)
        Log.d(TAG, "Logged injection: [${event.finalOutcome}] method=${event.injectionMethod} pkg=${event.targetPackage} duration=${event.durationMs}ms")
    }

    fun clearLogs() {
        _logs.value = emptyList()
        saveLogs(emptyList())
        Log.d(TAG, "Cleared injection diagnostics logs")
    }

    private fun loadLogs() {
        val sp = prefs ?: return
        val rawJson = sp.getString(KEY_INJECTION_LOGS, null)
        if (rawJson.isNullOrBlank()) {
            _logs.value = emptyList()
            return
        }

        try {
            val jsonArray = JSONArray(rawJson)
            val list = mutableListOf<InjectionEvent>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val method = obj.optString("injectionMethod", obj.optString("tierAttempted", "DIRECT_ACTION_SET_TEXT"))
                val details = obj.optString("resultDetails", obj.optString("tier3Result", obj.optString("tier1Result", "SUCCESS")))
                val event = InjectionEvent(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    formattedTime = obj.optString("formattedTime", ""),
                    targetPackage = obj.optString("targetPackage", "Unknown"),
                    targetAppName = obj.optString("targetAppName", "App"),
                    targetNodeClass = if (obj.isNull("targetNodeClass")) null else obj.optString("targetNodeClass"),
                    isFocused = obj.optBoolean("isFocused", false),
                    isEditable = obj.optBoolean("isEditable", false),
                    windowId = obj.optInt("windowId", -1),
                    textLength = obj.optInt("textLength", 0),
                    wordCount = obj.optInt("wordCount", 0),
                    injectionMethod = method,
                    resultDetails = details,
                    finalOutcome = obj.optString("finalOutcome", "FAILED"),
                    durationMs = obj.optLong("durationMs", 0L),
                    rawTextPreview = obj.optString("rawTextPreview", "")
                )
                list.add(event)
            }
            _logs.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading injection logs", e)
            _logs.value = emptyList()
        }
    }

    private fun saveLogs(list: List<InjectionEvent>) {
        val sp = prefs ?: return
        try {
            val jsonArray = JSONArray()
            list.forEach { event ->
                val obj = JSONObject().apply {
                    put("id", event.id)
                    put("timestamp", event.timestamp)
                    put("formattedTime", event.formattedTime)
                    put("targetPackage", event.targetPackage)
                    put("targetAppName", event.targetAppName)
                    put("targetNodeClass", event.targetNodeClass)
                    put("isFocused", event.isFocused)
                    put("isEditable", event.isEditable)
                    put("windowId", event.windowId)
                    put("textLength", event.textLength)
                    put("wordCount", event.wordCount)
                    put("injectionMethod", event.injectionMethod)
                    put("resultDetails", event.resultDetails)
                    put("finalOutcome", event.finalOutcome)
                    put("durationMs", event.durationMs)
                    put("rawTextPreview", event.rawTextPreview)
                }
                jsonArray.put(obj)
            }
            sp.edit().putString(KEY_INJECTION_LOGS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving injection logs", e)
        }
    }

    /**
     * Generates a clean, readable ASCII diagnostic report of all injection events.
     */
    fun exportAsFormattedText(): String {
        val list = _logs.value
        if (list.isEmpty()) {
            return "====================================================\nVOXSTREAM TEXT INJECTION DIAGNOSTIC REPORT\n====================================================\nNo injection events recorded yet.\n"
        }

        val sb = StringBuilder()
        sb.append("====================================================\n")
        sb.append("VOXSTREAM TEXT INJECTION DIAGNOSTIC REPORT\n")
        sb.append("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        sb.append("Total Events: ${list.size}\n")
        sb.append("====================================================\n\n")

        list.forEachIndexed { index, event ->
            sb.append("----------------------------------------------------\n")
            sb.append("#${index + 1} | [${event.finalOutcome}] at ${event.formattedTime} (${event.durationMs} ms)\n")
            sb.append("Target: ${event.targetAppName} (${event.targetPackage})\n")
            sb.append("Node: Class=${event.targetNodeClass ?: "null"}, isFocused=${event.isFocused}, isEditable=${event.isEditable}, windowId=${event.windowId}\n")
            sb.append("Text: ${event.textLength} chars (${event.wordCount} words) -> \"${event.rawTextPreview}\"\n")
            sb.append("Method: ${event.injectionMethod}\n")
            sb.append("Result: ${event.resultDetails}\n")
            sb.append("----------------------------------------------------\n\n")
        }

        return sb.toString()
    }
}

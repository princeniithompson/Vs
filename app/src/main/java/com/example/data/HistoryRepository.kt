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

data class HistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val appContext: String = "AI · VoxStream",
    val wordCount: Int = text.split("\\s+".toRegex()).count { it.isNotBlank() },
    val durationSeconds: Int = 0
) {
    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun getFormattedDateHeader(): String {
        val now = System.currentTimeMillis()
        val itemDate = Date(timestamp)
        val nowDate = Date(now)

        val daySdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val itemDay = daySdf.format(itemDate)
        val nowDay = daySdf.format(nowDate)

        val diffDays = (now - timestamp) / (1000 * 60 * 60 * 24)

        return when {
            itemDay == nowDay -> "Today"
            diffDays == 1L || (nowDay.toIntOrNull() != null && itemDay.toIntOrNull() != null && nowDay.toInt() - itemDay.toInt() == 1) -> "Yesterday"
            else -> {
                val fullSdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                fullSdf.format(itemDate)
            }
        }
    }
}

object HistoryRepository {
    private const val TAG = "HistoryRepository"
    private const val PREFS_NAME = "history_repository_prefs"
    private const val KEY_HISTORY = "history_entries"

    private var prefs: SharedPreferences? = null
    private val _historyItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyItems: StateFlow<List<HistoryItem>> = _historyItems.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadHistory()
        }
    }

    private fun isMockSampleText(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("In Wispr Flow there is this that says") ||
               trimmed.startsWith("I am making a grocery list") ||
               trimmed.startsWith("This application was built by me and it doesn't work") ||
               trimmed.startsWith("I have uploaded an image which is going to let you know") ||
               trimmed.startsWith("African pastor morning prayer") ||
               trimmed == "Google AI Studio"
    }

    private fun loadHistory() {
        val sp = prefs ?: return
        val rawJson = sp.getString(KEY_HISTORY, null)
        if (rawJson.isNullOrBlank()) {
            _historyItems.value = emptyList()
            return
        }

        try {
            val jsonArray = JSONArray(rawJson)
            val list = mutableListOf<HistoryItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val text = obj.getString("text")
                // Filter out any legacy mock sample entries from previous builds
                if (isMockSampleText(text)) {
                    continue
                }
                val item = HistoryItem(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    text = text,
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    appContext = obj.optString("appContext", "AI · VoxStream"),
                    wordCount = obj.optInt("wordCount", 0),
                    durationSeconds = obj.optInt("durationSeconds", 0)
                )
                list.add(item)
            }
            _historyItems.value = list
            saveHistory(list)
            CustomVocabularyRepository.syncUsageFromHistory(list)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing history json", e)
            _historyItems.value = emptyList()
            saveHistory(emptyList())
        }
    }

    private fun saveHistory(list: List<HistoryItem>) {
        val sp = prefs ?: return
        try {
            val jsonArray = JSONArray()
            list.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("text", item.text)
                    put("timestamp", item.timestamp)
                    put("appContext", item.appContext)
                    put("wordCount", item.wordCount)
                    put("durationSeconds", item.durationSeconds)
                }
                jsonArray.put(obj)
            }
            sp.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving history json", e)
        }
    }

    fun addHistoryItem(text: String, appContext: String = "AI · VoxStream", durationSeconds: Int = 0): HistoryItem? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null

        val current = _historyItems.value.toMutableList()
        val newItem = HistoryItem(
            text = trimmed,
            timestamp = System.currentTimeMillis(),
            appContext = appContext,
            wordCount = trimmed.split("\\s+".toRegex()).count { it.isNotBlank() },
            durationSeconds = durationSeconds
        )
        current.add(0, newItem) // Add to top
        _historyItems.value = current
        saveHistory(current)
        CustomVocabularyRepository.recordUsageFromTranscript(trimmed)
        return newItem
    }

    fun deleteHistoryItem(id: String): Boolean {
        val current = _historyItems.value.toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            _historyItems.value = current
            saveHistory(current)
        }
        return removed
    }

    fun clearHistory() {
        _historyItems.value = emptyList()
        saveHistory(emptyList())
    }

    fun getTotalWordsSpoken(): Long {
        return _historyItems.value.sumOf { it.wordCount.toLong() }
    }

    fun getUniqueAppsCount(): Int {
        return _historyItems.value.map { it.appContext }.distinct().size
    }

    fun getAverageWpm(): Int {
        val items = _historyItems.value
        val itemsWithDuration = items.filter { it.durationSeconds > 0 && it.wordCount > 0 }
        if (itemsWithDuration.isEmpty()) {
            return 0
        }
        val totalWords = itemsWithDuration.sumOf { it.wordCount }
        val totalSeconds = itemsWithDuration.sumOf { it.durationSeconds }
        return if (totalSeconds > 0) ((totalWords.toDouble() / totalSeconds) * 60).toInt() else 0
    }
}

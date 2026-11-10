package com.princeniithompson.rapcha.data

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
    val appContext: String = "AI · Rapcha",
    val wordCount: Int = text.split("\\s+".toRegex()).count { it.isNotBlank() },
    val durationSeconds: Int = 0,
    val retryClipId: String? = null,
    val session: VoiceSessionRecord? = null
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

object HistoryOverflowMenu {
    enum class Action { COPY, RETRY, DELETE }

    val actions: List<Action> = listOf(Action.COPY, Action.RETRY, Action.DELETE)
    const val NO_AUDIO = "No audio saved for this section"

    fun retryEnabled(retryClipId: String?, audioExists: Boolean): Boolean {
        return !retryClipId.isNullOrBlank() && audioExists
    }
}

object HistoryRepository {
    private const val TAG = "HistoryRepository"
    private const val PREFS_NAME = "history_repository_prefs"
    private const val KEY_HISTORY = "history_entries"
    const val PENDING_RETRY_TEXT = "Offline dictation"

    private var prefs: SharedPreferences? = null
    private val _historyItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyItems: StateFlow<List<HistoryItem>> = _historyItems.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadHistory()
        }
    }

    internal fun clearForTest() {
        prefs = null
        _historyItems.value = emptyList()
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
                    appContext = obj.optString("appContext", "AI · Rapcha"),
                    wordCount = obj.optInt("wordCount", 0),
                    durationSeconds = obj.optInt("durationSeconds", 0),
                    retryClipId = obj.optString("retryClipId", "").ifBlank { null },
                    session = obj.optJSONObject("session")?.let { VoiceSessionJson.fromJson(it) }
                )
                list.add(item)
            }
            _historyItems.value = list
            saveHistory(list, durable = false)
            CustomVocabularyRepository.syncUsageFromHistory(list)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing history json", e)
            _historyItems.value = emptyList()
            saveHistory(emptyList())
        }
    }

    private fun saveHistory(list: List<HistoryItem>, durable: Boolean = false) {
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
                    if (!item.retryClipId.isNullOrBlank()) {
                        put("retryClipId", item.retryClipId)
                    }
                    item.session?.let { put("session", VoiceSessionJson.toJson(it)) }
                }
                jsonArray.put(obj)
            }
            val editor = sp.edit().putString(KEY_HISTORY, jsonArray.toString())
            if (durable) editor.commit() else editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving history json", e)
        }
    }

    fun addHistoryItem(text: String, appContext: String = "AI · Rapcha", durationSeconds: Int = 0): HistoryItem? {
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
        saveHistory(current, durable = false)
        // Discarded silence never reaches here: callers pass the accepted transcript only.
        CustomVocabularyRepository.recordUsageFromTranscript(trimmed)
        return newItem
    }

    /**
     * Convenience method to save a history entry with explicit mode and appName tags.
     */
    fun saveVoiceSession(record: VoiceSessionRecord, durationSeconds: Int = 0): HistoryItem? {
        val text = record.finalText.trim()
        if (text.isBlank()) return null
        val label = when (record.pipeline) {
            SessionPipeline.TWO_STEP -> "Two-step"
            SessionPipeline.THREE_STEP -> "Three-step"
            SessionPipeline.SMART_ONLY -> "Smart"
            SessionPipeline.DIRECT -> "Transcript"
        }
        val current = _historyItems.value.toMutableList()
        val item = HistoryItem(
            text = text,
            timestamp = record.timestamp,
            appContext = "${record.appName} · $label",
            wordCount = text.split("\\s+".toRegex()).count { it.isNotBlank() },
            durationSeconds = durationSeconds,
            session = record
        )
        current.add(0, item)
        _historyItems.value = current
        saveHistory(current, durable = false)
        CustomVocabularyRepository.recordUsageFromTranscript(text)
        return item
    }

    fun sessionById(sessionId: String): VoiceSessionRecord? {
        if (sessionId.isBlank()) return null
        return _historyItems.value.firstOrNull { it.session?.sessionId == sessionId }?.session
    }

    /**
     * Inserts or replaces one session and waits until the row is on disk.
     * The raw freeze uses this before activityEnd.
     */
    fun commitSession(record: VoiceSessionRecord, durationSeconds: Int = 0): HistoryItem? {
        val text = record.finalText.trim()
        if (text.isBlank() && record.stages.isEmpty()) return null
        val label = when (record.pipeline) {
            SessionPipeline.TWO_STEP -> "Two-step"
            SessionPipeline.THREE_STEP -> "Three-step"
            SessionPipeline.SMART_ONLY -> "Smart"
            SessionPipeline.DIRECT -> "Transcript"
        }
        val current = _historyItems.value.toMutableList()
        val index = current.indexOfFirst { it.session?.sessionId == record.sessionId }
        val item = if (index >= 0) {
            current[index].copy(
                text = text.ifBlank { current[index].text },
                appContext = "${record.appName} · $label",
                wordCount = text.split("\\s+".toRegex()).count { it.isNotBlank() },
                session = record
            )
        } else {
            HistoryItem(
                text = text,
                timestamp = record.timestamp,
                appContext = "${record.appName} · $label",
                wordCount = text.split("\\s+".toRegex()).count { it.isNotBlank() },
                durationSeconds = durationSeconds,
                session = record
            )
        }
        if (index >= 0) current[index] = item else current.add(0, item)
        _historyItems.value = current
        saveHistory(current, durable = true)
        return item
    }

    fun replaceVoiceSession(sessionId: String, record: VoiceSessionRecord) {
        if (sessionId.isBlank()) return
        val current = _historyItems.value.toMutableList()
        val index = current.indexOfFirst { it.session?.sessionId == sessionId }
        if (index < 0) return
        current[index] = current[index].copy(session = record, text = record.finalText.ifBlank { current[index].text })
        _historyItems.value = current
        saveHistory(current)
    }

    fun updateLyraReview(
        sessionId: String,
        rating: LyraRating,
        tags: List<LyraReviewTag>? = null,
        comment: String? = null
    ): VoiceSessionRecord? {
        if (sessionId.isBlank()) return null
        val current = _historyItems.value.toMutableList()
        val index = current.indexOfFirst { it.session?.sessionId == sessionId }
        if (index < 0) return null
        val existing = current[index].session ?: return null
        val updated = LyraReviewPolicy.apply(existing, rating, tags ?: existing.tags, comment)
        if (updated == existing) return existing
        current[index] = current[index].copy(session = updated)
        _historyItems.value = current
        saveHistory(current)
        return updated
    }

    fun lyraReviewSessions(): List<VoiceSessionRecord> {
        return _historyItems.value.mapNotNull { it.session }.filter { it.reviewable }
    }

    fun saveEntry(text: String, mode: String = "POLISHED", appName: String = "App", durationSeconds: Int = 0): HistoryItem? {
        val contextTag = if (mode.isNotBlank()) "$appName · $mode" else appName
        return addHistoryItem(text = text, appContext = contextTag, durationSeconds = durationSeconds)
    }

    /**
     * Home card for a clip that is waiting on Retry. Reuses the same row if the
     * clip was already linked.
     */
    fun upsertPendingRetry(
        clipId: String,
        text: String,
        appContext: String,
        durationSeconds: Int
    ) {
        if (clipId.isBlank()) return
        val display = text.trim().ifBlank { PENDING_RETRY_TEXT }
        val current = _historyItems.value.toMutableList()
        val index = current.indexOfFirst { it.retryClipId == clipId }
        val words = display.split("\\s+".toRegex()).count { it.isNotBlank() }
        if (index >= 0) {
            val existing = current[index]
            current[index] = existing.copy(
                text = display,
                appContext = appContext.ifBlank { existing.appContext },
                wordCount = words,
                durationSeconds = durationSeconds,
                retryClipId = clipId
            )
        } else {
            current.add(
                0,
                HistoryItem(
                    text = display,
                    appContext = appContext.ifBlank { "Rapcha" },
                    wordCount = words,
                    durationSeconds = durationSeconds,
                    retryClipId = clipId
                )
            )
        }
        _historyItems.value = current
        saveHistory(current)
    }

    /**
     * Discard keeps a real partial transcript and drops a placeholder row.
     * The clip id is cleared either way so Retry cannot point at deleted audio.
     */
    fun detachRetryClip(clipId: String) {
        if (clipId.isBlank()) return
        val current = _historyItems.value.toMutableList()
        val index = current.indexOfFirst { it.retryClipId == clipId }
        if (index < 0) return
        val item = current[index]
        if (item.text == PENDING_RETRY_TEXT) {
            current.removeAt(index)
        } else {
            current[index] = item.copy(retryClipId = null)
        }
        _historyItems.value = current
        saveHistory(current)
    }

    fun removePendingRetry(clipId: String) {
        if (clipId.isBlank()) return
        val current = _historyItems.value.toMutableList()
        val removed = current.removeAll { it.retryClipId == clipId }
        if (!removed) return
        _historyItems.value = current
        saveHistory(current)
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

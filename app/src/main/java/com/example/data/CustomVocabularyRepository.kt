package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class VocabularyItem(
    val term: String,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val lastUsedTimestamp: Long = System.currentTimeMillis(),
    val isKeepDismissed: Boolean = false
) {
    fun getDaysUnused(): Long {
        val diffMs = System.currentTimeMillis() - lastUsedTimestamp
        return (diffMs / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
    }
}

object CustomVocabularyRepository {
    private const val TAG = "CustomVocabRepo"
    private const val PREFS_NAME = "custom_vocabulary_prefs"
    private const val KEY_TERMS = "vocabulary_terms"
    private const val KEY_METADATA = "vocabulary_metadata_v2"
    
    const val UNUSED_THRESHOLD_DAYS = 60

    private var prefs: SharedPreferences? = null
    
    // Ordered list of active string terms (sent to Gemini Live)
    private val _vocabulary = MutableStateFlow<List<String>>(emptyList())
    val vocabulary: StateFlow<List<String>> = _vocabulary.asStateFlow()

    // Full items list with timestamps
    private val _vocabularyItems = MutableStateFlow<List<VocabularyItem>>(emptyList())
    val vocabularyItems: StateFlow<List<VocabularyItem>> = _vocabularyItems.asStateFlow()

    // Clean-up suggestions for terms unused for >= 60 days
    private val _cleanupSuggestions = MutableStateFlow<List<VocabularyItem>>(emptyList())
    val cleanupSuggestions: StateFlow<List<VocabularyItem>> = _cleanupSuggestions.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadTerms()
        }
    }

    private fun loadTerms() {
        val sp = prefs ?: return
        val rawJson = sp.getString(KEY_TERMS, null)
        val metaJson = sp.getString(KEY_METADATA, null)

        val termsList = mutableListOf<String>()
        if (rawJson.isNullOrBlank()) {
            val initial = listOf("prince niithompson", "princeniithompson@gmail.com")
            termsList.addAll(initial)
        } else {
            try {
                val jsonArray = JSONArray(rawJson)
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.optString(i, "").trim()
                    if (item.isNotEmpty() && !termsList.contains(item)) {
                        termsList.add(item)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing custom vocabulary json", e)
            }
        }

        // Parse metadata map
        val metaMap = mutableMapOf<String, VocabularyItem>()
        if (!metaJson.isNullOrBlank()) {
            try {
                val metaObj = JSONObject(metaJson)
                val keys = metaObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = metaObj.getJSONObject(key)
                    val termName = obj.optString("term", key)
                    val added = obj.optLong("addedTimestamp", System.currentTimeMillis())
                    val lastUsed = obj.optLong("lastUsedTimestamp", System.currentTimeMillis())
                    val dismissed = obj.optBoolean("isKeepDismissed", false)
                    metaMap[key.lowercase()] = VocabularyItem(
                        term = termName,
                        addedTimestamp = added,
                        lastUsedTimestamp = lastUsed,
                        isKeepDismissed = dismissed
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing vocabulary metadata", e)
            }
        }

        val itemsList = mutableListOf<VocabularyItem>()
        val now = System.currentTimeMillis()
        termsList.forEach { term ->
            val key = term.lowercase()
            val existing = metaMap[key]
            if (existing != null) {
                itemsList.add(existing)
            } else {
                itemsList.add(
                    VocabularyItem(
                        term = term,
                        addedTimestamp = now,
                        lastUsedTimestamp = now,
                        isKeepDismissed = false
                    )
                )
            }
        }

        _vocabulary.value = termsList
        _vocabularyItems.value = itemsList
        recomputeCleanupSuggestions(itemsList)
        saveAll(termsList, itemsList)
    }

    private fun recomputeCleanupSuggestions(items: List<VocabularyItem>) {
        val thresholdMs = UNUSED_THRESHOLD_DAYS * 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        _cleanupSuggestions.value = items.filter { item ->
            !item.isKeepDismissed && (now - item.lastUsedTimestamp >= thresholdMs)
        }
    }

    private fun saveAll(terms: List<String>, items: List<VocabularyItem>) {
        val sp = prefs ?: return
        try {
            val termsArray = JSONArray()
            terms.forEach { term ->
                if (term.isNotBlank()) termsArray.put(term.trim())
            }

            val metaObj = JSONObject()
            items.forEach { item ->
                val obj = JSONObject().apply {
                    put("term", item.term)
                    put("addedTimestamp", item.addedTimestamp)
                    put("lastUsedTimestamp", item.lastUsedTimestamp)
                    put("isKeepDismissed", item.isKeepDismissed)
                }
                metaObj.put(item.term.lowercase(), obj)
            }

            sp.edit()
                .putString(KEY_TERMS, termsArray.toString())
                .putString(KEY_METADATA, metaObj.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving custom vocabulary data", e)
        }
    }

    fun getVocabulary(): List<String> {
        return _vocabulary.value
    }

    fun syncUsageFromHistory(historyItems: List<HistoryItem>) {
        if (historyItems.isEmpty()) return
        val currentItems = _vocabularyItems.value.toMutableList()
        var updated = false

        currentItems.indices.forEach { idx ->
            val item = currentItems[idx]
            // Find newest history item containing this term
            val newestMatch = historyItems
                .filter { containsWordOrPhrase(it.text, item.term) }
                .maxByOrNull { it.timestamp }

            if (newestMatch != null && newestMatch.timestamp > item.lastUsedTimestamp) {
                currentItems[idx] = item.copy(
                    lastUsedTimestamp = newestMatch.timestamp,
                    isKeepDismissed = false
                )
                updated = true
            }
        }

        if (updated) {
            _vocabularyItems.value = currentItems
            recomputeCleanupSuggestions(currentItems)
            saveAll(_vocabulary.value, currentItems)
        }
    }

    fun recordUsageFromTranscript(transcriptText: String) {
        if (transcriptText.isBlank()) return
        val currentItems = _vocabularyItems.value.toMutableList()
        val now = System.currentTimeMillis()
        var updated = false

        currentItems.indices.forEach { idx ->
            val item = currentItems[idx]
            if (containsWordOrPhrase(transcriptText, item.term)) {
                currentItems[idx] = item.copy(
                    lastUsedTimestamp = now,
                    isKeepDismissed = false
                )
                updated = true
            }
        }

        if (updated) {
            _vocabularyItems.value = currentItems
            recomputeCleanupSuggestions(currentItems)
            saveAll(_vocabulary.value, currentItems)
        }
    }

    private fun containsWordOrPhrase(text: String, term: String): Boolean {
        if (text.isBlank() || term.isBlank()) return false
        val trimmedTerm = term.trim()
        val regex = ("\\b" + Regex.escape(trimmedTerm) + "\\b").toRegex(RegexOption.IGNORE_CASE)
        return regex.containsMatchIn(text) || text.contains(trimmedTerm, ignoreCase = true)
    }

    fun addTerm(term: String): Boolean {
        val trimmed = term.trim()
        if (trimmed.isBlank()) return false
        val currentTerms = _vocabulary.value.toMutableList()
        if (currentTerms.any { it.equals(trimmed, ignoreCase = true) }) {
            return false // Duplicate
        }

        val now = System.currentTimeMillis()
        val newItem = VocabularyItem(
            term = trimmed,
            addedTimestamp = now,
            lastUsedTimestamp = now,
            isKeepDismissed = false
        )

        currentTerms.add(0, trimmed)
        val currentItems = _vocabularyItems.value.toMutableList()
        currentItems.add(0, newItem)

        _vocabulary.value = currentTerms
        _vocabularyItems.value = currentItems
        recomputeCleanupSuggestions(currentItems)
        saveAll(currentTerms, currentItems)
        return true
    }

    fun editTerm(oldTerm: String, newTerm: String): Boolean {
        val trimmedNew = newTerm.trim()
        if (trimmedNew.isBlank()) return false
        val currentTerms = _vocabulary.value.toMutableList()
        val index = currentTerms.indexOfFirst { it.equals(oldTerm, ignoreCase = true) }
        if (index == -1) return false

        currentTerms[index] = trimmedNew
        val currentItems = _vocabularyItems.value.toMutableList()
        val itemIndex = currentItems.indexOfFirst { it.term.equals(oldTerm, ignoreCase = true) }
        if (itemIndex != -1) {
            val oldItem = currentItems[itemIndex]
            currentItems[itemIndex] = oldItem.copy(term = trimmedNew)
        }

        _vocabulary.value = currentTerms
        _vocabularyItems.value = currentItems
        recomputeCleanupSuggestions(currentItems)
        saveAll(currentTerms, currentItems)
        return true
    }

    fun keepTerm(term: String): Boolean {
        val currentItems = _vocabularyItems.value.toMutableList()
        val index = currentItems.indexOfFirst { it.term.equals(term, ignoreCase = true) }
        if (index == -1) return false

        currentItems[index] = currentItems[index].copy(isKeepDismissed = true)
        _vocabularyItems.value = currentItems
        recomputeCleanupSuggestions(currentItems)
        saveAll(_vocabulary.value, currentItems)
        return true
    }

    fun deleteTerm(term: String): Boolean {
        val currentTerms = _vocabulary.value.toMutableList()
        val removedTerm = currentTerms.removeAll { it.equals(term, ignoreCase = true) }

        val currentItems = _vocabularyItems.value.toMutableList()
        currentItems.removeAll { it.term.equals(term, ignoreCase = true) }

        if (removedTerm) {
            _vocabulary.value = currentTerms
            _vocabularyItems.value = currentItems
            recomputeCleanupSuggestions(currentItems)
            saveAll(currentTerms, currentItems)
        }
        return removedTerm
    }

    fun clearAll() {
        _vocabulary.value = emptyList()
        _vocabularyItems.value = emptyList()
        _cleanupSuggestions.value = emptyList()
        saveAll(emptyList(), emptyList())
    }
}

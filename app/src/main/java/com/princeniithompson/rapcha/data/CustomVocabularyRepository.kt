package com.princeniithompson.rapcha.data

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
    val isKeepDismissed: Boolean = false,
    val vocabClass: VocabClass = VocabClass.EASY,
    val classOverridden: Boolean = false,
    val spokenAlias: String = "",
    /**
     * Easy terms the user saved are trusted for Live. Tricky terms are not.
     * Live still requires both: Easy and trusted. A Tricky term is never trusted.
     */
    val trusted: Boolean = vocabClass == VocabClass.EASY
) {
    fun getDaysUnused(): Long {
        val diffMs = System.currentTimeMillis() - lastUsedTimestamp
        return (diffMs / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
    }

    fun isLiveEligible(): Boolean {
        return vocabClass == VocabClass.EASY && trusted && term.isNotBlank()
    }
}

object CustomVocabularyRepository {
    private const val TAG = "CustomVocabRepo"
    private const val PREFS_NAME = "custom_vocabulary_prefs"
    private const val KEY_TERMS = "vocabulary_terms"
    private const val KEY_METADATA_V2 = "vocabulary_metadata_v2"
    private const val KEY_METADATA_V3 = "vocabulary_metadata_v3"
    private const val KEY_SEEDED_IDENTITY_REMOVED = "seeded_identity_removed_v1"
    private val SEEDED_IDENTITY = setOf("prince niithompson", "princeniithompson@gmail.com")

    const val UNUSED_THRESHOLD_DAYS = 60
    const val SMART_LIVE_CAP = 25
    const val VERBATIM_LIVE_CAP = 60

    private var prefs: SharedPreferences? = null

    // Ordered list of active string terms (newest first). This is the dictionary, not the Live payload.
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

    internal fun clearForTest() {
        prefs = null
        _vocabulary.value = emptyList()
        _vocabularyItems.value = emptyList()
        _cleanupSuggestions.value = emptyList()
    }

    private fun loadTerms() {
        val sp = prefs ?: return
        val rawJson = sp.getString(KEY_TERMS, null)
        val v3Json = sp.getString(KEY_METADATA_V3, null)
        val v2Json = sp.getString(KEY_METADATA_V2, null)
        val metaJson = if (!v3Json.isNullOrBlank()) v3Json else v2Json

        val termsList = mutableListOf<String>()
        if (!rawJson.isNullOrBlank()) {
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

        val alreadyRemoved = sp.getBoolean(KEY_SEEDED_IDENTITY_REMOVED, false)
        if (!alreadyRemoved) {
            termsList.removeAll { isSeededIdentity(it) }
        }

        val metaMap = mutableMapOf<String, VocabularyItem>()
        if (!metaJson.isNullOrBlank()) {
            try {
                val metaObj = JSONObject(metaJson)
                val keys = metaObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = metaObj.optJSONObject(key) ?: continue
                    val termName = obj.optString("term", key)
                    metaMap[key.lowercase()] = itemFromMetadata(termName, obj)
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
                itemsList.add(existing.copy(term = existing.term.ifBlank { term }))
            } else {
                itemsList.add(newItem(term, now))
            }
        }

        if (!alreadyRemoved) {
            itemsList.removeAll { isSeededIdentity(it.term) }
            sp.edit().putBoolean(KEY_SEEDED_IDENTITY_REMOVED, true).apply()
        }

        _vocabulary.value = termsList
        _vocabularyItems.value = itemsList
        recomputeCleanupSuggestions(itemsList)
        saveAll(termsList, itemsList)
    }

    private fun itemFromMetadata(termName: String, obj: JSONObject): VocabularyItem {
        val added = obj.optLong("addedTimestamp", System.currentTimeMillis())
        val lastUsed = obj.optLong("lastUsedTimestamp", added)
        val dismissed = obj.optBoolean("isKeepDismissed", false)
        val alias = VocabClassifier.normalizeAlias(obj.optString("spokenAlias", ""))
        val storedAlias = if (alias.isEmpty() || VocabClassifier.isUsableAlias(alias)) alias else ""
        val overridden = obj.optBoolean("classOverridden", false)
        val parsed = parseStoredClass(obj.optString("vocabClass", ""))
        val vocabClass = if (overridden && parsed != null) parsed else VocabClassifier.classify(termName)
        return VocabularyItem(
            term = termName,
            addedTimestamp = added,
            lastUsedTimestamp = lastUsed,
            isKeepDismissed = dismissed,
            vocabClass = vocabClass,
            classOverridden = overridden && parsed != null,
            spokenAlias = storedAlias,
            trusted = vocabClass == VocabClass.EASY
        )
    }

    private fun parseStoredClass(raw: String): VocabClass? {
        return when (raw.trim().uppercase()) {
            "EASY", "P" -> VocabClass.EASY
            "TRICKY", "N" -> VocabClass.TRICKY
            else -> null
        }
    }

    private fun newItem(
        term: String,
        now: Long,
        spokenAlias: String = "",
        classOverride: VocabClass? = null
    ): VocabularyItem {
        val vocabClass = classOverride ?: VocabClassifier.classify(term)
        return VocabularyItem(
            term = term,
            addedTimestamp = now,
            lastUsedTimestamp = now,
            isKeepDismissed = false,
            vocabClass = vocabClass,
            classOverridden = classOverride != null,
            spokenAlias = spokenAlias,
            trusted = vocabClass == VocabClass.EASY
        )
    }

    private fun isSeededIdentity(term: String): Boolean {
        return term.trim().lowercase() in SEEDED_IDENTITY
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
                    put("vocabClass", item.vocabClass.name)
                    put("classOverridden", item.classOverridden)
                    put("spokenAlias", item.spokenAlias)
                    put("trusted", item.trusted)
                }
                metaObj.put(item.term.lowercase(), obj)
            }

            sp.edit()
                .putString(KEY_TERMS, termsArray.toString())
                .putString(KEY_METADATA_V3, metaObj.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving custom vocabulary data", e)
        }
    }

    fun getVocabulary(): List<String> {
        return _vocabulary.value
    }

    /**
     * Terms actually given to Gemini Live for this mode.
     * Tricky spellings are excluded. Easy trusted terms are capped:
     * SMART ~25, VERBATIM ~60, newest first. The snapshot is taken at connect.
     */
    fun liveTerms(smartMode: Boolean): List<String> {
        val cap = if (smartMode) SMART_LIVE_CAP else VERBATIM_LIVE_CAP
        return _vocabularyItems.value.asSequence()
            .filter { it.isLiveEligible() }
            .map { it.term.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(cap)
            .toList()
    }

    fun logLiveSelection(smartMode: Boolean, source: DiagnosticSource) {
        val items = _vocabularyItems.value
        val easy = items.count { it.vocabClass == VocabClass.EASY }
        val tricky = items.count { it.vocabClass == VocabClass.TRICKY }
        val sent = liveTerms(smartMode).size
        val cap = if (smartMode) SMART_LIVE_CAP else VERBATIM_LIVE_CAP
        val mode = if (smartMode) "SMART" else "VERBATIM"
        val clause = if (smartMode) "dropped" else "kept"
        AppLogRepository.logEvent(
            source,
            DiagnosticType.VOCAB_POLICY,
            "terms_sent_to_live=$sent cap=$cap mode=$mode class_easy=$easy class_tricky=$tricky clause=$clause"
        )
    }

    /**
     * Exact alias restore before history and inject. Logs a count only.
     * A confirmed restore is the strong usage signal for a Tricky term.
     */
    fun prepareOutgoingTranscript(text: String, source: DiagnosticSource): String {
        val result = AliasRestore.restore(text, _vocabularyItems.value)
        AppLogRepository.logEvent(
            source,
            DiagnosticType.VOCAB_POLICY,
            "would_restore=${result.replacementCount}"
        )
        if (result.restoredTerms.isNotEmpty()) {
            recordConfirmedAliasUse(result.restoredTerms)
        }
        return result.text
    }

    /**
     * Hides Tricky spellings and emails before a payload is written to diagnostics.
     * The WebSocket and Polish requests still send the real text.
     */
    fun redactForDiagnostics(text: String): String {
        var out = TranscriptPrivacy.maskSensitive(text)
        val needles = _vocabularyItems.value
            .map { it.term.trim() }
            .filter { it.length >= 2 && VocabClassifier.classify(it) == VocabClass.TRICKY }
            .distinctBy { it.lowercase() }
            .sortedByDescending { it.length }
        for (needle in needles) {
            out = out.replace(needle, "[vocab]", ignoreCase = true)
        }
        return out
    }

    fun syncUsageFromHistory(historyItems: List<HistoryItem>) {
        if (historyItems.isEmpty()) return
        val currentItems = _vocabularyItems.value.toMutableList()
        var updated = false

        currentItems.indices.forEach { idx ->
            val item = currentItems[idx]
            if (item.vocabClass == VocabClass.TRICKY) return@forEach
            val newestMatch = historyItems
                .filter { containsExactTerm(it.text, item.term) }
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

    /**
     * Live output of a Tricky term is not usage. A snap must not refresh
     * lastUsed and block the 60-day cleanup. Easy terms still match on a
     * whole word or phrase, with no loose substring fallback.
     */
    fun recordUsageFromTranscript(transcriptText: String) {
        if (transcriptText.isBlank()) return
        val currentItems = _vocabularyItems.value.toMutableList()
        val now = System.currentTimeMillis()
        var updated = false

        currentItems.indices.forEach { idx ->
            val item = currentItems[idx]
            if (item.vocabClass == VocabClass.TRICKY) return@forEach
            if (!containsExactTerm(transcriptText, item.term)) return@forEach
            currentItems[idx] = item.copy(
                lastUsedTimestamp = now,
                isKeepDismissed = false
            )
            updated = true
        }

        if (updated) {
            _vocabularyItems.value = currentItems
            recomputeCleanupSuggestions(currentItems)
            saveAll(_vocabulary.value, currentItems)
        }
    }

    fun recordConfirmedAliasUse(terms: Collection<String>) {
        if (terms.isEmpty()) return
        val wanted = terms.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return
        val currentItems = _vocabularyItems.value.toMutableList()
        val now = System.currentTimeMillis()
        var updated = false
        currentItems.indices.forEach { idx ->
            val item = currentItems[idx]
            if (item.term.trim().lowercase() !in wanted) return@forEach
            currentItems[idx] = item.copy(
                lastUsedTimestamp = now,
                isKeepDismissed = false
            )
            updated = true
        }
        if (updated) {
            _vocabularyItems.value = currentItems
            recomputeCleanupSuggestions(currentItems)
            saveAll(_vocabulary.value, currentItems)
        }
    }

    private fun containsExactTerm(text: String, term: String): Boolean {
        return AliasRestore.matchesExact(text, term)
    }

    fun addTerm(
        term: String,
        spokenAlias: String = "",
        classOverride: VocabClass? = null
    ): Boolean {
        val trimmed = term.trim()
        if (trimmed.isBlank()) return false
        val normalizedAlias = VocabClassifier.normalizeAlias(spokenAlias)
        if (normalizedAlias.isNotEmpty() && !VocabClassifier.isUsableAlias(normalizedAlias)) return false
        val currentTerms = _vocabulary.value.toMutableList()
        if (currentTerms.any { it.equals(trimmed, ignoreCase = true) }) {
            return false
        }

        val now = System.currentTimeMillis()
        val newVocabItem = newItem(
            term = trimmed,
            now = now,
            spokenAlias = normalizedAlias,
            classOverride = classOverride
        )

        currentTerms.add(0, trimmed)
        val currentItems = _vocabularyItems.value.toMutableList()
        currentItems.add(0, newVocabItem)

        _vocabulary.value = currentTerms
        _vocabularyItems.value = currentItems
        recomputeCleanupSuggestions(currentItems)
        saveAll(currentTerms, currentItems)
        return true
    }

    fun editTerm(
        oldTerm: String,
        newTerm: String,
        spokenAlias: String? = null,
        classOverride: VocabClass? = null,
        useAutoClass: Boolean = false
    ): Boolean {
        val trimmedNew = newTerm.trim()
        if (trimmedNew.isBlank()) return false
        val currentTerms = _vocabulary.value.toMutableList()
        val index = currentTerms.indexOfFirst { it.equals(oldTerm, ignoreCase = true) }
        if (index == -1) return false
        if (currentTerms.any { it.equals(trimmedNew, ignoreCase = true) && !it.equals(oldTerm, ignoreCase = true) }) {
            return false
        }

        currentTerms[index] = trimmedNew
        val currentItems = _vocabularyItems.value.toMutableList()
        val itemIndex = currentItems.indexOfFirst { it.term.equals(oldTerm, ignoreCase = true) }
        if (itemIndex != -1) {
            val oldItem = currentItems[itemIndex]
            val alias = when (spokenAlias) {
                null -> oldItem.spokenAlias
                else -> {
                    val normalized = VocabClassifier.normalizeAlias(spokenAlias)
                    when {
                        normalized.isEmpty() -> ""
                        VocabClassifier.isUsableAlias(normalized) -> normalized
                        else -> return false
                    }
                }
            }
            val (vocabClass, overridden) = when {
                useAutoClass -> VocabClassifier.classify(trimmedNew) to false
                classOverride != null -> classOverride to true
                oldItem.classOverridden -> oldItem.vocabClass to true
                else -> VocabClassifier.classify(trimmedNew) to false
            }
            currentItems[itemIndex] = oldItem.copy(
                term = trimmedNew,
                spokenAlias = alias,
                vocabClass = vocabClass,
                classOverridden = overridden,
                trusted = vocabClass == VocabClass.EASY
            )
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

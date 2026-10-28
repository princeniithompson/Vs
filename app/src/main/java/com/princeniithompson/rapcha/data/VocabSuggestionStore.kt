package com.princeniithompson.rapcha.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Local session counts for vocabulary suggestions. Separate from the dictionary
 * so a count never becomes a saved word on its own.
 */
object VocabSuggestionStore {
    private const val TAG = "VocabSuggestionStore"
    private const val PREFS_NAME = "vocab_suggestion_prefs"
    private const val KEY_RECORDS = "spelling_records_v1"
    private const val MAX_RECORDS = 400

    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private var memory: MutableMap<String, VocabSuggestionPolicy.SpellingRecord>? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs == null) {
                prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                memory = null
            }
        }
    }

    internal fun clearForTest() {
        synchronized(lock) {
            prefs = null
            memory = null
        }
    }

    fun load(): Map<String, VocabSuggestionPolicy.SpellingRecord> {
        synchronized(lock) {
            return current().toMap()
        }
    }

    fun save(records: Map<String, VocabSuggestionPolicy.SpellingRecord>) {
        synchronized(lock) {
            val capped = cap(records)
            memory = capped.toMutableMap()
            persist(capped)
        }
    }

    fun <T> mutate(
        block: (Map<String, VocabSuggestionPolicy.SpellingRecord>) -> Pair<Map<String, VocabSuggestionPolicy.SpellingRecord>, T>
    ): T {
        synchronized(lock) {
            val (next, value) = block(current().toMap())
            val capped = cap(next)
            memory = capped.toMutableMap()
            persist(capped)
            return value
        }
    }

    private fun current(): MutableMap<String, VocabSuggestionPolicy.SpellingRecord> {
        memory?.let { return it }
        val loaded = readPrefs()
        memory = loaded
        return loaded
    }

    private fun readPrefs(): MutableMap<String, VocabSuggestionPolicy.SpellingRecord> {
        val raw = prefs?.getString(KEY_RECORDS, null)
        if (raw.isNullOrBlank()) return mutableMapOf()
        return try {
            val root = JSONObject(raw)
            val parsed = mutableMapOf<String, VocabSuggestionPolicy.SpellingRecord>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = root.optJSONObject(key) ?: continue
                val votes = mutableMapOf<String, Int>()
                val casingObj = obj.optJSONObject("casings")
                if (casingObj != null) {
                    val casingKeys = casingObj.keys()
                    while (casingKeys.hasNext()) {
                        val casing = casingKeys.next()
                        val count = casingObj.optInt(casing, 0)
                        if (casing.isNotBlank() && count > 0) votes[casing] = count
                    }
                }
                parsed[key.lowercase()] = VocabSuggestionPolicy.SpellingRecord(
                    sessionCount = obj.optInt("sessionCount", 0).coerceAtLeast(0),
                    casingVotes = votes,
                    snoozeUntilMs = obj.optLong("snoozeUntilMs", 0L),
                    prompted = obj.optBoolean("prompted", false)
                )
            }
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Could not read vocabulary suggestion counts", e)
            mutableMapOf()
        }
    }

    private fun persist(records: Map<String, VocabSuggestionPolicy.SpellingRecord>) {
        val sp = prefs ?: return
        try {
            val root = JSONObject()
            for ((key, record) in records) {
                val casings = JSONObject()
                for ((casing, count) in record.casingVotes) {
                    if (casing.isNotBlank() && count > 0) casings.put(casing, count)
                }
                root.put(
                    key,
                    JSONObject().apply {
                        put("sessionCount", record.sessionCount)
                        put("snoozeUntilMs", record.snoozeUntilMs)
                        put("prompted", record.prompted)
                        put("casings", casings)
                    }
                )
            }
            sp.edit().putString(KEY_RECORDS, root.toString()).commit()
        } catch (e: Exception) {
            Log.e(TAG, "Could not save vocabulary suggestion counts", e)
        }
    }

    private fun cap(
        records: Map<String, VocabSuggestionPolicy.SpellingRecord>
    ): Map<String, VocabSuggestionPolicy.SpellingRecord> {
        if (records.size <= MAX_RECORDS) return records
        return records.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, VocabSuggestionPolicy.SpellingRecord>> { it.value.prompted }
                    .thenByDescending { it.value.snoozeUntilMs > 0L }
                    .thenByDescending { it.value.sessionCount }
            )
            .take(MAX_RECORDS)
            .associate { it.key to it.value }
    }
}

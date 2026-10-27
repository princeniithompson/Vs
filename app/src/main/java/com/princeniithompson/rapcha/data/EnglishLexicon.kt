package com.princeniithompson.rapcha.data

import android.content.Context

/**
 * Ordinary English for suggestion filtering.
 * SCOWL 2020.12.07 US English size 60 (`assets/scowl/en_US_60.txt`) plus
 * [SupplementaryCommonWords]. The upstream permission notice is
 * `assets/scowl/Copyright` and is not the MIT license.
 */
object EnglishLexicon {
    const val WORD_ASSET = "scowl/en_US_60.txt"
    const val COPYRIGHT_ASSET = "scowl/Copyright"

    private val lock = Any()
    @Volatile private var words: Set<String>? = null

    fun ensureLoaded(context: Context) {
        if (words != null) return
        synchronized(lock) {
            if (words != null) return
            val loaded = HashSet<String>(140_000)
            context.applicationContext.assets.open(WORD_ASSET).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val word = line.trim().lowercase()
                    if (word.isNotEmpty()) loaded.add(word)
                }
            }
            words = loaded
        }
    }

    fun isOrdinary(token: String): Boolean {
        val key = token.trim().lowercase()
        if (key.isEmpty()) return false
        if (SupplementaryCommonWords.contains(key)) return true
        return words?.contains(key) == true
    }

    internal fun loadedWordCount(): Int = words?.size ?: 0
}

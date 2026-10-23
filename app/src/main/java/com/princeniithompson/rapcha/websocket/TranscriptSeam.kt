package com.princeniithompson.rapcha.websocket

import java.util.Locale

/**
 * Joins finalized dictation pieces and drops the repeated seam left when the
 * last two seconds of audio are replayed into the next piece.
 */
object TranscriptSeam {
    data class Overlap(val text: String, val wordsRemoved: Int)

    fun append(existing: String, piece: String): String {
        val next = piece.trim()
        if (next.isEmpty()) return existing.trim()
        val previous = existing.trim()
        if (previous.isEmpty()) return next
        return "$previous $next"
    }

    /**
     * Lowercase and strip punctuation, then drop the longest run of at least
     * two words that the start of [next] shares with the end of [previous].
     */
    fun dropOverlap(previous: String, next: String, window: Int = 15): Overlap {
        val nextTokens = tokens(next)
        if (nextTokens.size < 2 || previous.isBlank()) {
            return Overlap(next.trim(), 0)
        }
        val prevNorm = tokens(previous).takeLast(window).map { normalize(it) }
        val nextNorm = nextTokens.take(window).map { normalize(it) }
        val limit = minOf(prevNorm.size, nextNorm.size)
        var best = 0
        for (length in limit downTo 2) {
            if (prevNorm.takeLast(length) == nextNorm.take(length)) {
                best = length
                break
            }
        }
        if (best < 2) return Overlap(next.trim(), 0)
        return Overlap(nextTokens.drop(best).joinToString(" "), best)
    }

    private fun tokens(text: String): List<String> {
        return text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private fun normalize(word: String): String {
        return word.lowercase(Locale.US).replace(Regex("[^\\p{L}\\p{N}]"), "")
    }
}

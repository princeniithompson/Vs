package com.princeniithompson.rapcha.service

/**
 * Where finished words go.
 * A normal Complete with a focused field always injects.
 * A session cut off before Complete, including Safe Mode, always copies.
 */
object SessionPlacement {
    enum class Path { INJECT, CLIPBOARD }

    fun forFinishedSession(fieldFocused: Boolean, cutOffBeforeComplete: Boolean): Path {
        if (cutOffBeforeComplete) return Path.CLIPBOARD
        return if (fieldFocused) Path.INJECT else Path.CLIPBOARD
    }

    /** Package and name hits hide the bubble immediately. Password flicker still waits. */
    fun holdsImmediately(verdict: SafeModeClassifier.Verdict): Boolean {
        return verdict.sensitive && (
            verdict.reason == SafeModeClassifier.Reason.PACKAGE_LIST ||
                verdict.reason == SafeModeClassifier.Reason.KEYWORD
            )
    }
}

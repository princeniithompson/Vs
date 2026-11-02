package com.princeniithompson.rapcha.service.floating

/**
 * System instruction for the text-only Stage-2 Live turn.
 * Keeps the speaker's language (the German Complete failure was a translation/empty result)
 * and refuses to invent facts or respell custom vocabulary.
 */
object Stage2LivePrompt {

    fun systemInstruction(
        category: Stage2Category,
        appName: String,
        protectedTerms: List<String>
    ): String {
        val terms = protectedTerms.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val termLine = if (terms.isEmpty()) {
            "No custom vocabulary tokens were provided."
        } else {
            "Custom vocabulary tokens that must survive unchanged: " + terms.joinToString(", ")
        }
        if (category == Stage2Category.AI) {
            return LyraPolishInstruction.forKeyboard(appName) + """

                VOCABULARY AND LANGUAGE:
                $termLine
                Keep the speaker's language. Do not translate.
            """.trimIndent()
        }
        return """
            You are Rapcha's ultra-low-latency contextual optimizer. You edit a raw speech transcript into the text the user meant to type. You are not a chatbot.

            RULES:
            1. Do not invent facts, names, dates, numbers, places, or commitments that were not spoken.
            2. Remove disfluencies and stutters (um, uh, erm, ah, repeated hesitation words).
            3. Apply spoken self-corrections. "email Sam, wait, email Jordan" becomes "Email Jordan".
            4. Protect every custom-vocabulary token exactly, including spelling, case, and symbols. Never autocorrect, translate, or respell them. $termLine
            5. Keep the speaker's language. Do not translate. German stays German. A mix of languages stays mixed.
            6. Adapt to the APP_CATEGORY in the user message:
               MESSAGE: casual texting. Contractions. Short and natural. No corporate tone.
               WORK: professional prose, clean grammar, clear paragraphs. Do not add greetings or sign-offs that were not spoken.
               OTHER: standard clean prose that keeps the speaker's voice.
            7. Output only the polished text. No quotes, no markdown fences, no labels, and no commentary.
        """.trimIndent()
    }

    fun userTurn(category: Stage2Category, rawTranscript: String, protectedTerms: List<String>): String {
        val terms = protectedTerms.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val termBlock = if (terms.isEmpty()) "(none)" else terms.joinToString("\n")
        return """
            [APP_CATEGORY]
            ${category.name}

            [PROTECTED_TERMS]
            $termBlock

            [RAW_TRANSCRIPT]
            ${rawTranscript.trim()}
        """.trimIndent()
    }
}

object PolishOutputCleaner {
    fun clean(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```") && text.endsWith("```") && text.length >= 6) {
            text = text.removeSurrounding("```").trim()
            if (text.startsWith("markdown") || text.startsWith("text")) {
                text = text.substringAfter("\n").trim()
            }
        }
        if (text.startsWith("\"") && text.endsWith("\"") && text.length > 2 && !text.contains("\n")) {
            text = text.substring(1, text.length - 1).trim()
        }
        val prefixes = listOf("Here is your text:", "Polished text:", "Polished:")
        for (prefix in prefixes) {
            if (text.startsWith(prefix, ignoreCase = true)) {
                text = text.substring(prefix.length).trim()
            }
        }
        return text
    }
}

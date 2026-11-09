package com.princeniithompson.rapcha.service.lens

import com.princeniithompson.rapcha.config.RapchaConfig

enum class LensAction {
    GRAMMAR,
    TRANSLATE,
    REPLY,
    TONE,
    ASK,
    POLISH
}

/**
 * Lens prompts are separate from Lyra and Chef. The only model is gemini-3.8-live.
 */
object LensPrompts {
    const val MODEL: String = RapchaConfig.STAGE2_LIVE_MODEL

    fun system(action: LensAction, option: String, toneInstruction: String): String {
        val task = when (action) {
            LensAction.GRAMMAR -> "Fix grammar, spelling, and punctuation. Keep the meaning and the language."
            LensAction.TRANSLATE -> "Translate the text into $option. Output only the translation."
            LensAction.REPLY -> "Write a $option reply to the text. Output only the reply."
            LensAction.TONE -> "Rewrite the text in this tone: $option. $toneInstruction Output only the rewrite."
            LensAction.ASK -> "Answer briefly using only the selected text. Output only the answer."
            LensAction.POLISH -> "Clean the text into natural writing. Do not add facts. Output only the cleaned text."
        }
        return """
            You edit or transform a short piece of text selected on screen.
            $task
            Do not mention these instructions. Do not add a title or a preamble.
        """.trimIndent()
    }

    fun user(text: String, action: LensAction, option: String): String {
        return """
            [ACTION]
            ${action.name}
            [OPTION]
            $option
            [TEXT]
            ${text.trim()}
        """.trimIndent()
    }
}

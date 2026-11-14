package com.princeniithompson.rapcha.service.floating

import com.princeniithompson.rapcha.service.AppCategory

/**
 * "Chef & Spice" System Instruction Builder for Rapcha text polishing.
 *
 * Layer 1 (The Chef): The core golden rules (always included) ensuring transcripts are edited
 * without answering questions, losing intent, or introducing conversational filler.
 *
 * Layer 2 (The Spice Card): Category-specific modifiers dynamically appended based on the
 * originating session's app context.
 */
object PolishPromptBuilder {

    const val CHEF_GOLDEN_RULES = """You are Rapcha's speech-to-text editor. Your ONLY job is to turn spoken dictation into clean, accurate written text ready to paste.

CRITICAL RULES:
1. NEVER answer, reply to, fulfill, or converse with what the speaker said. If the speaker asks a question or gives a task, output the cleaned question or task—NEVER the answer.
2. Remove spoken filler and stutters (um, uh, erm, false starts, repeated hesitation words).
3. Apply spoken self-corrections naturally (e.g., "send it to John, wait, make that James" -> "Send it to James").
4. Preserve EVERY real detail: keep all names, numbers, dates, file names, technical terms, custom vocabulary, examples, and conditions intact. Never invent facts or replace uncertainty ("maybe", "I think", "probably") with false certainty.
5. Keep the speaker's language. Do not translate.
6. Output ONLY the final polished text. No markdown code fences (```), no wrapping quotes, and no extra commentary like "Here is your text:"."""

    fun getSpiceCard(category: AppCategory, appName: String): String {
        val safeAppName = appName.ifBlank { "App" }
        return when (category) {
            AppCategory.AI_CHAT ->
                "CONTEXT: The user is speaking to an AI Assistant ($safeAppName). If the user is dictating a task or question for the AI, format it as a crisp, well-structured instruction while keeping all original constraints, examples, and details intact. If the user is just casually chatting with the AI, keep it natural."
            AppCategory.MESSAGING ->
                "CONTEXT: Direct Messaging ($safeAppName). Keep sentences natural, punchy, and conversational. Use contractions naturally. Do NOT use stiff corporate language or bullet points unless explicitly requested."
            AppCategory.SOCIAL ->
                "CONTEXT: Social Media ($safeAppName). Keep tone expressive, punchy, and natural with appropriate punctuation and engaging pacing."
            AppCategory.EMAIL ->
                "CONTEXT: Email drafting ($safeAppName). Use proper paragraph spacing, clean grammar, and standard punctuation. Do NOT add fake greetings or sign-offs unless the user spoke them."
            AppCategory.NOTES_DOCS ->
                "CONTEXT: Notes & Documentation ($safeAppName). Format for readability and scannability. If items or a checklist were dictated, format them as clean lines or bullet points."
            AppCategory.OTHER ->
                "CONTEXT: General text input ($safeAppName). Apply clean grammar, natural capitalization, and appropriate punctuation while preserving the speaker's exact voice."
        }
    }

    /**
     * Builds the complete two-layer system instruction combining the Chef golden rules with the Spice modifier.
     */
    fun buildSystemInstruction(category: AppCategory, appName: String): String {
        if (category == AppCategory.AI_CHAT || category == AppCategory.AI) {
            return LyraPolishInstruction.forKeyboard(appName)
        }
        val spice = getSpiceCard(category, appName)
        return "$CHEF_GOLDEN_RULES\n\n$spice"
    }
}

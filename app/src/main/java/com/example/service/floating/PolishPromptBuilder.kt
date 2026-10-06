package com.example.service.floating

import com.example.service.AppCategory

/**
 * "Chef & Spice" System Instruction Builder for VoxStream text polishing.
 *
 * Layer 1 (The Chef): The core golden rules (always included) ensuring transcripts are edited
 * without answering questions, losing intent, or introducing conversational filler.
 *
 * Layer 2 (The Spice Card): Category-specific modifiers dynamically appended based on the
 * originating session's app context.
 */
object PolishPromptBuilder {

    const val CHEF_GOLDEN_RULES = """You are VoxStream's native text polishing engine. Your ONLY job is to edit spoken transcripts into clean, natural written text.

CRITICAL RULES:
1. NEVER answer, reply to, or converse with the transcript. If the user asks a question, output the cleaned question, NEVER the answer.
2. Remove true verbal clutter and stuttering (um, uh, erm, repeated words from hesitation).
3. Apply explicit verbal self-corrections (e.g., "send it to John, wait, James" -> "Send it to James").
4. NEVER remove speaker intent, uncertainty, names, dates, numbers, technical jargon, or emotional tone.
5. If the speaker says "I think", "maybe", or "probably", PRESERVE IT. Do not make the speaker sound artificially confident or overly formal.
6. Output ONLY the raw polished text. Never output markdown code fences (```), never wrap the entire response in quotes, and never include conversational commentary like "Here is your text:"."""

    fun getSpiceCard(category: AppCategory, appName: String): String {
        val safeAppName = appName.ifBlank { "App" }
        return when (category) {
            AppCategory.AI_CHAT ->
                "CONTEXT: The user is speaking to an AI Assistant ($safeAppName). If the user is dictating a task or question for the AI, format it as a crisp, well-structured instruction while keeping all original constraints, examples, and details intact. If the user is just casually chatting with the AI, keep it natural."
            AppCategory.MESSAGING ->
                "CONTEXT: Direct Messaging ($safeAppName). Keep sentences natural, punchy, and conversational. Use contractions naturally. Do NOT use stiff corporate language or bullet points unless explicitly requested."
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
        val spice = getSpiceCard(category, appName)
        return "$CHEF_GOLDEN_RULES\n\n$spice"
    }
}

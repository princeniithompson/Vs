package com.example.service.floating

/**
 * Modes for AI-Aware Polish when operating inside an AI chat application
 * (e.g. ChatGPT, Claude, Gemini, Grok, Perplexity, Copilot, DeepSeek, Qwen).
 */
enum class AiPolishMode {
    /**
     * Clean Message:
     * Clear, natural, well-punctuated text that preserves the user's personal conversational voice.
     * Best for conversational follow-ups and continuing discussions.
     */
    CLEAN_MESSAGE,

    /**
     * Optimize as Prompt:
     * Structured, engineered prompt with clear goals, actionable context, constraints, and expected output.
     * Best for new task instructions, coding, research, and complex requests (Lyra style without meta-headers).
     */
    OPTIMIZE_PROMPT
}

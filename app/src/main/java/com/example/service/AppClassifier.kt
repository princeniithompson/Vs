package com.example.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

enum class AppCategory {
    SOCIAL,
    WORK,
    AI,
    OTHER
}

/**
 * AI-Aware App Classifier:
 * 1. Identifies model-driven / rich-text editors that strictly require ACTION_PASTE (e.g. Google Keep, Docs, Notion).
 * 2. Classifies active foreground applications into semantic categories (Social, Work, AI, Other).
 * 3. Provides tailored system instructions and formatting guidance based on the active app.
 */
object AppClassifier {

    private val PASTE_REQUIRED_PACKAGES = setOf(
        // Google Workspace & Productivity Editors
        "com.google.android.keep",
        "com.google.android.apps.docs",
        "com.google.android.apps.docs.editors.docs",
        "com.google.android.apps.docs.editors.sheets",
        "com.google.android.apps.docs.editors.slides",
        "com.google.android.apps.dynamite", // Google Chat rich inputs

        // Note-taking & Document Workspace Tools
        "notion.id",
        "md.obsidian",
        "com.evernote",
        "com.microsoft.office.onenote",
        "com.microsoft.office.word",
        "com.microsoft.office.excel",
        "com.microsoft.office.powerpoint",
        "com.microsoft.office.officehubrow",
        "com.samsung.android.app.notes",
        "com.craft.craftnotes",
        "com.quip.quip",
        "com.zoho.notebook",
        "com.simplenote",
        "com.superhuman.mail",
        "com.linear.android",

        // Web and Publishing Tools
        "com.medium.reader",
        "com.substack.app",
        "com.wordpress",
        "com.figma.mirror",
        "com.canva.editor"
    )

    fun isPasteRequired(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkgLower = packageName.lowercase()
        if (pkgLower in PASTE_REQUIRED_PACKAGES) return true
        if (pkgLower.contains(".docs.editors.") || pkgLower.contains(".keep")) return true
        if (pkgLower.contains("obsidian") || pkgLower.contains("onenote") || pkgLower.contains("notion")) return true
        return false
    }

    fun classify(packageName: String?, resolvedAppName: String? = null): AppCategory {
        if (packageName.isNullOrBlank()) return AppCategory.OTHER
        val pkgLower = packageName.lowercase()
        val nameLower = (resolvedAppName ?: "").lowercase()

        // 1. AI category
        if (pkgLower.contains("qwen") || pkgLower.contains("tongyi") ||
            pkgLower.contains("grok") || pkgLower.contains("x.ai") ||
            pkgLower.contains("openai") || pkgLower.contains("chatgpt") ||
            pkgLower.contains("anthropic") || pkgLower.contains("claude") ||
            pkgLower.contains("bard") || pkgLower.contains("gemini") ||
            pkgLower.contains("perplexity") || pkgLower.contains("copilot") ||
            pkgLower.contains("poe") || pkgLower.contains("character.ai") ||
            pkgLower.contains("deepseek") ||
            nameLower.contains("ai studio") || nameLower.contains("chatgpt") ||
            nameLower.contains("claude") || nameLower.contains("gemini") ||
            nameLower.contains("grok") || nameLower.contains("perplexity") ||
            nameLower.contains("deepseek") || nameLower.contains("copilot") ||
            nameLower.contains("qwen")
        ) {
            return AppCategory.AI
        }

        // 2. Social category
        if (pkgLower.contains("messaging") || pkgLower.contains("mms") ||
            pkgLower.contains("whatsapp") || pkgLower.contains("telegram") ||
            pkgLower.contains("instagram") || pkgLower.contains("messenger") ||
            pkgLower.contains("tiktok") || pkgLower.contains("twitter") ||
            pkgLower.contains("snapchat") || pkgLower.contains("reddit") ||
            pkgLower.contains("discord") || pkgLower.contains("pinterest") ||
            pkgLower.contains("signal") || pkgLower.contains("linkedin") ||
            pkgLower.contains("facebook") || pkgLower.contains("threads") ||
            pkgLower.contains("viber") || pkgLower.contains("line") ||
            nameLower.contains("whatsapp") || nameLower.contains("telegram") ||
            nameLower.contains("instagram") || nameLower.contains("tiktok") ||
            nameLower.contains("twitter") || nameLower.contains("discord") ||
            nameLower.contains("messages") || nameLower.contains("signal")
        ) {
            return AppCategory.SOCIAL
        }

        // 3. Work / Productivity category
        if (pkgLower.contains("github") || pkgLower.contains("gmail") ||
            pkgLower.contains("outlook") || pkgLower.contains("slack") ||
            pkgLower.contains("docs") || pkgLower.contains("sheets") ||
            pkgLower.contains("slides") || pkgLower.contains("teams") ||
            pkgLower.contains("notion") || pkgLower.contains("keep") ||
            pkgLower.contains("trello") || pkgLower.contains("asana") ||
            pkgLower.contains("zoom") || pkgLower.contains("linear") ||
            pkgLower.contains("jira") || pkgLower.contains("office") ||
            nameLower.contains("gmail") || nameLower.contains("outlook") ||
            nameLower.contains("slack") || nameLower.contains("docs") ||
            nameLower.contains("notion") || nameLower.contains("keep") ||
            nameLower.contains("teams") || nameLower.contains("github")
        ) {
            return AppCategory.WORK
        }

        return AppCategory.OTHER
    }

    /**
     * Builds dynamic prompt guidelines tailored to the active app category.
     */
    fun getCategoryPromptGuidelines(category: AppCategory, appName: String): String {
        return when (category) {
            AppCategory.SOCIAL -> """
The user is dictating into a social/chat app ($appName).
- Style: Conversational, fluid, and natural for instant messaging.
- Use natural sentence flow and clean punctuation without overly stiff or bureaucratic phrasing.
- If the user dictates hashtags, emojis, or shorthand, preserve them naturally.
""".trimIndent()

            AppCategory.WORK -> """
The user is dictating into a professional work/productivity app ($appName).
- Style: Professional, crisp, and well-structured.
- Ensure correct capitalization, proper punctuation, and polished business-appropriate phrasing.
- If multiple items or action points are named, organize them cleanly into concise markdown bullet points.
""".trimIndent()

            AppCategory.AI -> """
The user is crafting an AI prompt in $appName.
- Style: Precise, clear, and instruction-oriented.
- Preserve technical keywords, parameter names, coding terminology, and query structure accurately.
""".trimIndent()

            AppCategory.OTHER -> """
The user is typing in $appName.
- Style: Clean, well-punctuated, natural spoken text.
""".trimIndent()
        }
    }

    /**
     * Determines whether the given package or app represents an AI chat app.
     */
    fun isAiChatApp(packageName: String?, resolvedAppName: String? = null): Boolean {
        return classify(packageName, resolvedAppName) == AppCategory.AI
    }
}

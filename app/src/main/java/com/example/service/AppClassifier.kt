package com.example.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

enum class AppCategory {
    AI_CHAT,
    MESSAGING,
    EMAIL,
    NOTES_DOCS,
    OTHER;

    companion object {
        // Backwards compatibility aliases
        val AI = AI_CHAT
        val SOCIAL = MESSAGING
        val WORK = NOTES_DOCS
    }
}

/**
 * AI-Aware App Classifier:
 * 1. Identifies model-driven / rich-text editors that strictly require ACTION_PASTE (e.g. Google Keep, Docs, Notion).
 * 2. Classifies active foreground applications into semantic categories (AI_CHAT, MESSAGING, EMAIL, NOTES_DOCS, OTHER).
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
        if (packageName.isNullOrBlank() && resolvedAppName.isNullOrBlank()) return AppCategory.OTHER

        // Check AppRegistry native mapping
        if (!packageName.isNullOrBlank()) {
            AppRegistry.NATIVE_APP_MAP[packageName]?.let {
                return when (it.group) {
                    "AI" -> AppCategory.AI_CHAT
                    "Social" -> AppCategory.MESSAGING
                    "Work" -> AppCategory.NOTES_DOCS
                    "Email" -> AppCategory.EMAIL
                    else -> AppCategory.OTHER
                }
            }
        }

        // Check web app heuristics
        val candidateName = resolvedAppName ?: ""
        AppRegistry.matchWebApp(candidateName)?.let {
            return when (it.group) {
                "AI" -> AppCategory.AI_CHAT
                "Social" -> AppCategory.MESSAGING
                "Work" -> AppCategory.NOTES_DOCS
                "Email" -> AppCategory.EMAIL
                else -> AppCategory.OTHER
            }
        }

        val pkgLower = (packageName ?: "").lowercase()
        val nameLower = candidateName.lowercase()

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
            nameLower.contains("bard") || nameLower.contains("grok") ||
            nameLower.contains("perplexity") || nameLower.contains("deepseek") ||
            nameLower.contains("copilot") || nameLower.contains("qwen")
        ) {
            return AppCategory.AI_CHAT
        }

        // 2. Email category
        if (pkgLower.contains("gmail") || pkgLower.contains("outlook") ||
            pkgLower.contains("superhuman") || pkgLower.contains("protonmail") ||
            pkgLower.contains("email") || pkgLower.contains(".mail") ||
            nameLower.contains("gmail") || nameLower.contains("outlook") ||
            nameLower.contains("superhuman") || nameLower.contains("mail")
        ) {
            return AppCategory.EMAIL
        }

        // 3. Notes & Docs category
        if (pkgLower.contains("keep") || pkgLower.contains("notion") ||
            pkgLower.contains("obsidian") || pkgLower.contains("docs") ||
            pkgLower.contains("sheets") || pkgLower.contains("slides") ||
            pkgLower.contains("word") || pkgLower.contains("onenote") ||
            pkgLower.contains("notes") || pkgLower.contains("evernote") ||
            pkgLower.contains("linear") || pkgLower.contains("jira") ||
            pkgLower.contains("trello") || pkgLower.contains("asana") ||
            nameLower.contains("keep") || nameLower.contains("notion") ||
            nameLower.contains("obsidian") || nameLower.contains("docs") ||
            nameLower.contains("notes") || nameLower.contains("word")
        ) {
            return AppCategory.NOTES_DOCS
        }

        // 4. Messaging & Social category
        if (pkgLower.contains("messaging") || pkgLower.contains("mms") ||
            pkgLower.contains("whatsapp") || pkgLower.contains("telegram") ||
            pkgLower.contains("instagram") || pkgLower.contains("messenger") ||
            pkgLower.contains("tiktok") || pkgLower.contains("twitter") ||
            pkgLower.contains("snapchat") || pkgLower.contains("reddit") ||
            pkgLower.contains("discord") || pkgLower.contains("pinterest") ||
            pkgLower.contains("signal") || pkgLower.contains("linkedin") ||
            pkgLower.contains("facebook") || pkgLower.contains("threads") ||
            pkgLower.contains("viber") || pkgLower.contains("line") ||
            pkgLower.contains("slack") || pkgLower.contains("dynamite") ||
            nameLower.contains("whatsapp") || nameLower.contains("telegram") ||
            nameLower.contains("instagram") || nameLower.contains("tiktok") ||
            nameLower.contains("twitter") || nameLower.contains("discord") ||
            nameLower.contains("messages") || nameLower.contains("signal") ||
            nameLower.contains("slack")
        ) {
            return AppCategory.MESSAGING
        }

        return AppCategory.OTHER
    }

    /**
     * Determines whether the given package or app represents an AI chat app.
     */
    fun isAiChatApp(packageName: String?, resolvedAppName: String? = null): Boolean {
        return classify(packageName, resolvedAppName) == AppCategory.AI_CHAT
    }
}

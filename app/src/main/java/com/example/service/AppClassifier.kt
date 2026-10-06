package com.example.service

import android.content.Context

enum class AppCategory(val groupName: String) {
    AI_CHAT("AI"),
    MESSAGING("Social"),
    EMAIL("Email"),
    NOTES_DOCS("Work"),
    SOCIAL("Social"),
    OTHER("Other");

    companion object {
        // Backwards compatibility aliases
        val AI = AI_CHAT
        val WORK = NOTES_DOCS

        fun fromString(str: String?): AppCategory {
            if (str.isNullOrBlank()) return OTHER
            val upper = str.trim().uppercase()
            return when {
                upper.contains("AI") -> AI_CHAT
                upper.contains("MESSAG") || upper.contains("CHAT") -> MESSAGING
                upper.contains("EMAIL") || upper.contains("MAIL") -> EMAIL
                upper.contains("NOTE") || upper.contains("DOC") || upper.contains("WORK") -> NOTES_DOCS
                upper.contains("SOCIAL") -> SOCIAL
                else -> OTHER
            }
        }
    }
}

/**
 * AI-Aware App Classifier:
 * 1. Instant Static Dictionary (Zero Network Latency) for major AI, Messaging, Email, Notes, and Social apps.
 * 2. Persistent Learned Registry lookup.
 * 3. Identifies apps requiring specialized handling.
 */
object AppClassifier {

    // 1. Static Instant Dictionary (Zero Network Latency)
    private val AI_CHAT_NAMES = setOf(
        "gemini", "chatgpt", "claude", "grok", "copilot", "perplexity",
        "ai studio", "google ai studio", "deepseek", "kimi", "qwen",
        "poe", "character.ai", "v0", "notebooklm", "flow", "google flow"
    )

    private val MESSAGING_NAMES = setOf(
        "whatsapp", "whatsapp business", "telegram", "signal", "messages",
        "messenger", "slack", "discord", "google chat", "teams", "wechat"
    )

    private val EMAIL_NAMES = setOf(
        "gmail", "outlook", "spark", "superhuman", "protonmail",
        "yahoo mail", "mail", "email"
    )

    private val NOTES_DOCS_NAMES = setOf(
        "keep notes", "google keep", "keep", "notion", "obsidian",
        "docs", "sheets", "slides", "onenote", "evernote", "word",
        "excel", "powerpoint", "craft", "linear", "jira", "trello", "asana"
    )

    private val SOCIAL_NAMES = setOf(
        "instagram", "facebook", "tiktok", "x", "twitter", "reddit",
        "snapchat", "threads", "pinterest", "linkedin"
    )

    private val PASTE_REQUIRED_PACKAGES = setOf(
        "com.google.android.keep",
        "com.google.android.apps.docs",
        "com.google.android.apps.docs.editors.docs",
        "com.google.android.apps.docs.editors.sheets",
        "com.google.android.apps.docs.editors.slides",
        "com.google.android.apps.dynamite",
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

    fun isAiChatApp(packageName: String?, resolvedAppName: String? = null): Boolean {
        return classify(packageName, resolvedAppName) == AppCategory.AI_CHAT
    }

    /**
     * Classifies an app based on name and package:
     * 1. Check Instant Static Dictionary by name
     * 2. Check LearnedAppRegistry (if context provided)
     * 3. Check AppRegistry native package map
     * 4. Heuristic pattern fallback
     */
    fun classify(packageName: String?, resolvedAppName: String? = null, context: Context? = null): AppCategory {
        val nameLower = (resolvedAppName ?: "").trim().lowercase()

        // 1. Instant Static Dictionary (Zero Latency)
        if (nameLower.isNotEmpty()) {
            when {
                AI_CHAT_NAMES.any { nameLower == it || nameLower.contains(it) } -> return AppCategory.AI_CHAT
                MESSAGING_NAMES.any { nameLower == it || nameLower.contains(it) } -> return AppCategory.MESSAGING
                EMAIL_NAMES.any { nameLower == it || nameLower.contains(it) } -> return AppCategory.EMAIL
                NOTES_DOCS_NAMES.any { nameLower == it || nameLower.contains(it) } -> return AppCategory.NOTES_DOCS
                SOCIAL_NAMES.any { nameLower == it || nameLower.contains(it) } -> return AppCategory.SOCIAL
            }
        }

        // 2. Check Learned Registry if cached
        if (context != null) {
            val key = packageName ?: resolvedAppName ?: ""
            val learned = LearnedAppRegistry.get(context, key) ?: if (nameLower.isNotEmpty()) LearnedAppRegistry.get(context, nameLower) else null
            if (learned != null) {
                return AppCategory.fromString(learned.category)
            }
        }

        // 3. Check AppRegistry native package mapping
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

        // 4. Package name heuristics
        val pkgLower = (packageName ?: "").lowercase()
        return when {
            pkgLower.contains("gemini") || pkgLower.contains("bard") || pkgLower.contains("chatgpt") ||
            pkgLower.contains("openai") || pkgLower.contains("claude") || pkgLower.contains("anthropic") ||
            pkgLower.contains("grok") || pkgLower.contains("perplexity") || pkgLower.contains("deepseek") ||
            pkgLower.contains("copilot") || pkgLower.contains("qwen") || pkgLower.contains("kimi") -> AppCategory.AI_CHAT

            pkgLower.contains("whatsapp") || pkgLower.contains("telegram") || pkgLower.contains("signal") ||
            pkgLower.contains("messaging") || pkgLower.contains("mms") || pkgLower.contains("discord") ||
            pkgLower.contains("slack") -> AppCategory.MESSAGING

            pkgLower.contains("gmail") || pkgLower.contains("outlook") || pkgLower.contains("superhuman") ||
            pkgLower.contains("protonmail") || pkgLower.contains("email") || pkgLower.contains(".mail") -> AppCategory.EMAIL

            pkgLower.contains("keep") || pkgLower.contains("notion") || pkgLower.contains("obsidian") ||
            pkgLower.contains("docs") || pkgLower.contains("notes") || pkgLower.contains("onenote") -> AppCategory.NOTES_DOCS

            pkgLower.contains("instagram") || pkgLower.contains("facebook") || pkgLower.contains("twitter") ||
            pkgLower.contains("tiktok") || pkgLower.contains("reddit") || pkgLower.contains("pinterest") -> AppCategory.SOCIAL

            else -> AppCategory.OTHER
        }
    }
}

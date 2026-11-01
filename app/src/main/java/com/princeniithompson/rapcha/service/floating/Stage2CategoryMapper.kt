package com.princeniithompson.rapcha.service.floating

import com.princeniithompson.rapcha.service.AppRegistry

/**
 * Blueprint categories for Stage-2 polish. This is separate from [com.princeniithompson.rapcha.service.AppClassifier]
 * so Slack and Teams stay MESSAGE there and WORK here, and the existing polish spice cards do not move.
 */
enum class Stage2Category {
    MESSAGE,
    WORK,
    AI,
    OTHER
}

object Stage2CategoryMapper {

    private val AI_PACKAGES = setOf(
        "com.openai.chatgpt",
        "com.anthropic.claude",
        "com.google.android.apps.bard",
        "com.google.android.apps.gemini",
        "ai.x.grok"
    )

    private val MESSAGE_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thoughtcrime.securesms",
        "com.discord"
    )

    private val WORK_PACKAGES = setOf(
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.slack",
        "com.microsoft.teams",
        "com.microsoft.office.word",
        "notion.id"
    )

    fun map(packageName: String?, appName: String?, hint: String? = null): Stage2Category {
        val pkg = packageName?.trim()?.lowercase().orEmpty()
        val webName = AppRegistry.matchWebApp(hint)?.appName ?: AppRegistry.matchWebApp(appName)?.appName
        val name = listOf(appName, webName, hint)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .lowercase()

        return when {
            isAi(pkg, name) -> Stage2Category.AI
            isMessage(pkg, name) -> Stage2Category.MESSAGE
            isWork(pkg, name) -> Stage2Category.WORK
            else -> Stage2Category.OTHER
        }
    }

    private fun isAi(pkg: String, name: String): Boolean {
        if (pkg in AI_PACKAGES) return true
        if (pkg.contains("chatgpt") || pkg.contains("openai") || pkg.contains("claude") ||
            pkg.contains("anthropic") || pkg.contains("gemini") || pkg.contains(".bard") ||
            pkg.contains("grok")
        ) {
            return true
        }
        return containsToken(name, "chatgpt") ||
            containsToken(name, "claude") ||
            containsToken(name, "gemini") ||
            name.contains("grok")
    }

    private fun isMessage(pkg: String, name: String): Boolean {
        if (pkg in MESSAGE_PACKAGES) return true
        if (pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("securesms") ||
            pkg.contains("discord")
        ) {
            return true
        }
        return containsToken(name, "whatsapp") ||
            containsToken(name, "telegram") ||
            containsToken(name, "signal") ||
            containsToken(name, "discord")
    }

    private fun isWork(pkg: String, name: String): Boolean {
        if (pkg in WORK_PACKAGES) return true
        if (pkg.contains("outlook") || pkg.contains("slack") || pkg.contains(".teams") ||
            pkg.contains("office.word") || pkg.contains("notion")
        ) {
            return true
        }
        if (pkg == "com.google.android.gm") return true
        return containsToken(name, "gmail") ||
            containsToken(name, "outlook") ||
            containsToken(name, "slack") ||
            containsToken(name, "teams") ||
            containsToken(name, "notion") ||
            containsToken(name, "word")
    }

    private fun containsToken(haystack: String, token: String): Boolean {
        if (haystack.isEmpty()) return false
        val pattern = Regex("(^|[^a-z0-9])${Regex.escape(token)}([^a-z0-9]|$)")
        return pattern.containsMatchIn(haystack)
    }
}

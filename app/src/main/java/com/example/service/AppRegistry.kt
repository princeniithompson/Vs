package com.example.service

import com.example.service.AppContextResolver.AppContext

/**
 * Centralized App Registry and Heuristics Engine:
 * - Maps native package identifiers to verified product identities (e.g. com.google.android.apps.bard -> Gemini)
 * - Maps web domains, PWA signatures, and browser window titles to web app identities (e.g. aistudio.google.com -> Google AI Studio)
 * - Categorizes apps into semantic groups: AI, Work, Social, Other
 */
object AppRegistry {

    // Native Package Mapping
    val NATIVE_APP_MAP = mapOf(
        // AI Native Apps
        "com.google.android.apps.bard" to AppContext("AI", "Gemini", "AI · Gemini"),
        "com.google.android.apps.gemini" to AppContext("AI", "Gemini", "AI · Gemini"),
        "com.openai.chatgpt" to AppContext("AI", "ChatGPT", "AI · ChatGPT"),
        "com.anthropic.claude" to AppContext("AI", "Claude", "AI · Claude"),
        "ai.x.grok" to AppContext("AI", "Grok", "AI · Grok"),
        "ai.perplexity.app.android" to AppContext("AI", "Perplexity", "AI · Perplexity"),
        "com.microsoft.copilot" to AppContext("AI", "Copilot", "AI · Copilot"),
        "com.deepseek.chat" to AppContext("AI", "DeepSeek", "AI · DeepSeek"),
        "com.alibaba.tongyi.intl" to AppContext("AI", "Qwen", "AI · Qwen"),
        "com.alibaba.qwen.intl" to AppContext("AI", "Qwen", "AI · Qwen"),
        "com.aliyun.tongyi.intl" to AppContext("AI", "Qwen", "AI · Qwen"),
        "com.alibaba.tongyi" to AppContext("AI", "Qwen", "AI · Qwen"),
        "com.alibaba.qwen" to AppContext("AI", "Qwen", "AI · Qwen"),
        "com.qwen.ai" to AppContext("AI", "Qwen", "AI · Qwen"),
        "ai.qwen.chat" to AppContext("AI", "Qwen", "AI · Qwen"),
        "com.poe.android" to AppContext("AI", "Poe", "AI · Poe"),
        "ai.character.app" to AppContext("AI", "Character.AI", "AI · Character.AI"),

        // Social Native Apps
        "com.whatsapp" to AppContext("Social", "WhatsApp", "Social · WhatsApp"),
        "com.whatsapp.w4b" to AppContext("Social", "WhatsApp Business", "Social · WhatsApp Business"),
        "com.google.android.apps.messaging" to AppContext("Social", "Messages", "Social · Messages"),
        "com.android.mms" to AppContext("Social", "Messages", "Social · Messages"),
        "org.telegram.messenger" to AppContext("Social", "Telegram", "Social · Telegram"),
        "org.telegram.messenger.web" to AppContext("Social", "Telegram", "Social · Telegram"),
        "com.instagram.android" to AppContext("Social", "Instagram", "Social · Instagram"),
        "com.facebook.orca" to AppContext("Social", "Messenger", "Social · Messenger"),
        "com.facebook.katana" to AppContext("Social", "Facebook", "Social · Facebook"),
        "com.zhiliaoapp.musically" to AppContext("Social", "TikTok", "Social · TikTok"),
        "com.twitter.android" to AppContext("Social", "X", "Social · X"),
        "com.snapchat.android" to AppContext("Social", "Snapchat", "Social · Snapchat"),
        "com.reddit.frontpage" to AppContext("Social", "Reddit", "Social · Reddit"),
        "com.discord" to AppContext("Social", "Discord", "Social · Discord"),
        "com.pinterest" to AppContext("Social", "Pinterest", "Social · Pinterest"),
        "org.thoughtcrime.securesms" to AppContext("Social", "Signal", "Social · Signal"),
        "com.linkedin.android" to AppContext("Social", "LinkedIn", "Social · LinkedIn"),
        "com.instagram.barcelona" to AppContext("Social", "Threads", "Social · Threads"),

        // Work Native Apps
        "com.github.android" to AppContext("Work", "GitHub", "Work · GitHub"),
        "com.google.android.gm" to AppContext("Work", "Gmail", "Work · Gmail"),
        "com.microsoft.office.outlook" to AppContext("Work", "Outlook", "Work · Outlook"),
        "com.Slack" to AppContext("Work", "Slack", "Work · Slack"),
        "com.slack" to AppContext("Work", "Slack", "Work · Slack"),
        "com.google.android.apps.docs" to AppContext("Work", "Docs", "Work · Docs"),
        "com.google.android.apps.docs.editors.docs" to AppContext("Work", "Docs", "Work · Docs"),
        "com.google.android.apps.docs.editors.sheets" to AppContext("Work", "Sheets", "Work · Sheets"),
        "com.google.android.apps.docs.editors.slides" to AppContext("Work", "Slides", "Work · Slides"),
        "com.google.android.keep" to AppContext("Work", "Keep", "Work · Keep"),
        "com.microsoft.teams" to AppContext("Work", "Teams", "Work · Teams"),
        "notion.id" to AppContext("Work", "Notion", "Work · Notion"),
        "com.trello" to AppContext("Work", "Trello", "Work · Trello"),
        "com.asana.app" to AppContext("Work", "Asana", "Work · Asana"),
        "us.zoom.videomeetings" to AppContext("Work", "Zoom", "Work · Zoom"),

        // Other Popular Apps
        "com.sportybet.android.gp" to AppContext("Other", "SportyBet", "Other · SportyBet"),
        "com.sportybet.android.gh" to AppContext("Other", "SportyBet", "Other · SportyBet"),
        "com.sportybet.android.ng" to AppContext("Other", "SportyBet", "Other · SportyBet"),
        "com.sportybet.gp" to AppContext("Other", "SportyBet", "Other · SportyBet"),
        "com.sportybet.gh" to AppContext("Other", "SportyBet", "Other · SportyBet"),
        "com.sportybet" to AppContext("Other", "SportyBet", "Other · SportyBet")
    )

    // Web App Domain & Title Pattern Matchers
    data class WebAppDefinition(
        val keywords: List<String>,
        val appContext: AppContext
    )

    val WEB_APP_DEFINITIONS = listOf(
        WebAppDefinition(
            keywords = listOf(
                "aistudio.google.com",
                "aistudio",
                "google ai studio",
                "ai studio",
                "ais-dev-",
                "ais-pre-",
                "googleaistudio"
            ),
            appContext = AppContext("AI", "Google AI Studio", "AI · Google AI Studio")
        ),
        WebAppDefinition(
            keywords = listOf("chatgpt.com", "chat.openai.com", "openai.com/chat"),
            appContext = AppContext("AI", "ChatGPT", "AI · ChatGPT")
        ),
        WebAppDefinition(
            keywords = listOf("claude.ai", "anthropic.com"),
            appContext = AppContext("AI", "Claude", "AI · Claude")
        ),
        WebAppDefinition(
            keywords = listOf("grok.com", "x.com/i/grok"),
            appContext = AppContext("AI", "Grok", "AI · Grok")
        ),
        WebAppDefinition(
            keywords = listOf("deepseek.com", "chat.deepseek.com"),
            appContext = AppContext("AI", "DeepSeek", "AI · DeepSeek")
        ),
        WebAppDefinition(
            keywords = listOf("perplexity.ai"),
            appContext = AppContext("AI", "Perplexity", "AI · Perplexity")
        ),
        WebAppDefinition(
            keywords = listOf("v0.dev"),
            appContext = AppContext("AI", "v0", "AI · v0")
        ),
        WebAppDefinition(
            keywords = listOf("github.com"),
            appContext = AppContext("Work", "GitHub", "Work · GitHub")
        ),
        WebAppDefinition(
            keywords = listOf("notion.so"),
            appContext = AppContext("Work", "Notion", "Work · Notion")
        ),
        WebAppDefinition(
            keywords = listOf("figma.com"),
            appContext = AppContext("Work", "Figma", "Work · Figma")
        ),
        WebAppDefinition(
            keywords = listOf("canva.com"),
            appContext = AppContext("Work", "Canva", "Work · Canva")
        ),
        WebAppDefinition(
            keywords = listOf("web.whatsapp.com"),
            appContext = AppContext("Social", "WhatsApp", "Social · WhatsApp")
        ),
        WebAppDefinition(
            keywords = listOf("web.telegram.org"),
            appContext = AppContext("Social", "Telegram", "Social · Telegram")
        ),
        WebAppDefinition(
            keywords = listOf("twitter.com", "x.com"),
            appContext = AppContext("Social", "X", "Social · X")
        ),
        WebAppDefinition(
            keywords = listOf("reddit.com"),
            appContext = AppContext("Social", "Reddit", "Social · Reddit")
        ),
        WebAppDefinition(
            keywords = listOf("discord.com"),
            appContext = AppContext("Social", "Discord", "Social · Discord")
        )
    )

    fun matchWebApp(rawText: String?): AppContext? {
        if (rawText.isNullOrBlank()) return null
        val lower = rawText.lowercase()
        for (def in WEB_APP_DEFINITIONS) {
            if (def.keywords.any { lower.contains(it) }) {
                return def.appContext
            }
        }
        return null
    }
}

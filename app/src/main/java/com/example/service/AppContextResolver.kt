package com.example.service

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Production-ready Hybrid App Context Resolver:
 * Stage 1: Curated Fast-Path Whitelist (O(1) instant resolution for high-traffic apps)
 * Stage 2: Web App & Browser Inspector (Google AI Studio, PWAs, Chrome Standalone Windows)
 * Stage 3: Dynamic Resolution with OS ApplicationInfo.category, Intelligent Tokenizer, and UI Inspection
 * In-Memory LRU Cache for zero-lag 120Hz accessibility event processing
 */
object AppContextResolver {
    private const val TAG = "AppContextResolver"

    data class AppContext(
        val group: String,
        val appName: String,
        val formatted: String
    )

    // In-memory LRU Cache (Capacity: 150 entries for zero IPC lag)
    private val memoryCache = object : LruCache<String, AppContext>(150) {}

    // Stage 1: Curated Priority Whitelist Map
    private val PRIORITY_WHITELIST = mapOf(
        // Social
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

        // Work
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

        // AI (Qwen, Grok, ChatGPT, Claude, Gemini, DeepSeek, etc.)
        "com.alibaba.tongyi.intl" to AppContext("AI", "Qwen", "AI · Qwen"),
        "com.alibaba.qwen.intl" to AppContext("AI", "Qwen", "AI · Qwen"),
        "com.aliyun.tongyi.intl" to AppContext("AI", "Qwen", "AI · Qwen"),
        "com.alibaba.tongyi" to AppContext("AI", "Qwen", "AI · Qwen"),
        "com.alibaba.qwen" to AppContext("AI", "Qwen", "AI · Qwen"),
        "com.qwen.ai" to AppContext("AI", "Qwen", "AI · Qwen"),
        "ai.qwen.chat" to AppContext("AI", "Qwen", "AI · Qwen"),
        "ai.x.grok" to AppContext("AI", "Grok", "AI · Grok"),
        "com.openai.chatgpt" to AppContext("AI", "ChatGPT", "AI · ChatGPT"),
        "com.anthropic.claude" to AppContext("AI", "Claude", "AI · Claude"),
        "com.google.android.apps.bard" to AppContext("AI", "Gemini", "AI · Gemini"),
        "ai.perplexity.app.android" to AppContext("AI", "Perplexity", "AI · Perplexity"),
        "com.microsoft.copilot" to AppContext("AI", "Copilot", "AI · Copilot"),
        "com.poe.android" to AppContext("AI", "Poe", "AI · Poe"),
        "ai.character.app" to AppContext("AI", "Character.AI", "AI · Character.AI"),
        "com.deepseek.chat" to AppContext("AI", "DeepSeek", "AI · DeepSeek"),

        // Popular Services
        "com.sportybet.android.gp" to AppContext("Other", "SportyBet", "Other · SportyBet"),
        "com.sportybet.android.gh" to AppContext("Other", "SportyBet", "Other · SportyBet"),
        "com.sportybet.android.ng" to AppContext("Other", "SportyBet", "Other · SportyBet"),
        "com.sportybet.gp" to AppContext("Other", "SportyBet", "Other · SportyBet"),
        "com.sportybet.gh" to AppContext("Other", "SportyBet", "Other · SportyBet"),
        "com.sportybet" to AppContext("Other", "SportyBet", "Other · SportyBet")
    )

    // Comprehensive list of generic suffixes to strip (stores, distributions, platforms, builds, intl)
    private val GENERIC_SUFFIXES = setOf(
        "android", "mobile", "app", "beta", "dev", "staging", "client", "lite", "free",
        "main", "phone", "gp", "play", "googleplay", "store", "intl", "international",
        "global", "prod", "release", "live", "official", "web", "standalone", "en"
    )
    private val COUNTRY_TLDS = setOf(
        "gh", "ng", "us", "uk", "gb", "de", "fr", "in", "au", "ca", "za", "ke", "br",
        "mx", "es", "it", "nl", "ru", "jp", "kr", "cn", "id", "ph", "vn", "th", "my", "pk", "eg"
    )
    private val GENERIC_PREFIXES = setOf("com", "org", "net", "io", "ai", "co", "app", "me", "alibaba", "aliyun")

    fun isIgnoredPackage(context: Context?, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        val lower = packageName.lowercase()
        if (lower == "android" ||
            lower == "com.android.systemui" ||
            lower.contains("systemui") ||
            lower.contains("inputmethod") ||
            lower.contains(".keyboard") ||
            lower.endsWith(".ime") ||
            lower.contains("honeyboard") ||
            lower.contains("swiftkey") ||
            lower.startsWith("com.example")
        ) {
            return true
        }
        if (context != null) {
            if (packageName == context.packageName) return true
            // Ignore home launchers
            try {
                val pm = context.packageManager
                val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val resolveInfo = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                if (resolveInfo?.activityInfo?.packageName == packageName) {
                    return true
                }
            } catch (_: Throwable) {}
        }
        return false
    }

    /**
     * Resolves the AppContext using the Hybrid Pipeline + Web App/Browser Inspector + LRU Cache.
     */
    fun resolve(
        context: Context,
        packageName: String?,
        windowInfo: AccessibilityWindowInfo? = null,
        rootNode: AccessibilityNodeInfo? = null,
        className: String? = null
    ): AppContext? {
        if (packageName.isNullOrBlank() || isIgnoredPackage(context, packageName)) {
            return null
        }

        val pkgLower = packageName.lowercase()
        val a11y = VoxStreamAccessibilityService.instance
        val effectiveWin = windowInfo ?: a11y?.getActiveApplicationWindow()
        val effectiveRoot = rootNode ?: a11y?.rootInActiveWindow

        // 1. Check Specialized Container: Google Gemini inside Google Quick Search Box
        if (packageName.equals("com.google.android.googlequicksearchbox", ignoreCase = true)) {
            val isGemini = isGeminiSession(effectiveWin, effectiveRoot, className)
            return if (isGemini) {
                AppContext("AI", "Gemini", "AI · Gemini")
            } else {
                AppContext("Other", "Google", "Other · Google")
            }
        }

        // 2. Check Browsers & Web Apps (Google AI Studio, Chrome Standalone, WebAPKs, Samsung Internet, Edge, Brave, etc.)
        val isBrowserOrWebApk = pkgLower.startsWith("org.chromium.webapk") ||
                pkgLower.contains(".webapk") ||
                pkgLower == "com.android.chrome" ||
                pkgLower == "com.chrome.beta" ||
                pkgLower == "com.chrome.dev" ||
                pkgLower == "com.chrome.canary" ||
                pkgLower == "com.google.android.apps.chrome" ||
                pkgLower == "com.sec.android.app.sbrowser" ||
                pkgLower == "com.microsoft.emmx" ||
                pkgLower == "com.brave.browser" ||
                pkgLower == "org.mozilla.firefox"

        if (isBrowserOrWebApk) {
            val webAppContext = resolveWebOrBrowserApp(context, packageName, effectiveWin, effectiveRoot)
            if (webAppContext != null) {
                return webAppContext
            }
        }

        // 3. Stage 1: Curated Fast-Path Whitelist Match
        PRIORITY_WHITELIST[packageName]?.let {
            return it
        }

        // 4. Check LRU Cache
        val cached = memoryCache.get(packageName)
        if (cached != null) {
            return cached
        }

        // 5. Stage 3: Dynamic Resolution Engine
        val resolved = computeDynamicResolution(context, packageName)
        if (resolved != null) {
            memoryCache.put(packageName, resolved)
        }
        return resolved
    }

    private fun isGeminiSession(
        windowInfo: AccessibilityWindowInfo?,
        rootNode: AccessibilityNodeInfo?,
        className: String?
    ): Boolean {
        if (className != null && (className.contains("RobinActivity", ignoreCase = true) || className.contains("Gemini", ignoreCase = true))) {
            return true
        }
        val windowTitle = windowInfo?.title?.toString() ?: ""
        if (windowTitle.contains("Gemini", ignoreCase = true) || windowTitle.contains("Bard", ignoreCase = true)) {
            return true
        }
        val rootDesc = rootNode?.contentDescription?.toString() ?: ""
        if (rootDesc.contains("Gemini", ignoreCase = true)) {
            return true
        }
        return false
    }

    /**
     * Resolves Progressive Web Apps / WebAPKs or Web Pages running inside Chrome / Browser (e.g. Google AI Studio).
     */
    private fun resolveWebOrBrowserApp(
        context: Context,
        packageName: String,
        windowInfo: AccessibilityWindowInfo?,
        rootNode: AccessibilityNodeInfo?
    ): AppContext? {
        val pm = context.packageManager
        val pkgLower = packageName.lowercase()
        var appTitle = ""

        // 1. If WebAPK, inspect manifest metadata
        if (pkgLower.startsWith("org.chromium.webapk") || pkgLower.contains(".webapk")) {
            try {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.GET_META_DATA
                }
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(packageName, flags as PackageManager.ApplicationInfoFlags)
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(packageName, flags as Int)
                }

                val meta = appInfo.metaData
                if (meta != null) {
                    appTitle = meta.getString("org.chromium.webapk.shell_apk.name")
                        ?: meta.getString("org.chromium.webapk.shell_apk.shortName")
                        ?: ""
                }

                if (appTitle.isBlank()) {
                    val label = pm.getApplicationLabel(appInfo).toString()
                    if (label.isNotBlank() && !label.startsWith("org.chromium") && !label.equals("Web Application", ignoreCase = true)) {
                        appTitle = label
                    }
                }
            } catch (_: Throwable) {}
        }

        // 2. Inspect Window Title / Tab Title / Root Hierarchy for Web App Identity (Google AI Studio, etc.)
        if (appTitle.isBlank() || appTitle.equals("Chrome", ignoreCase = true) || appTitle.equals("Google Chrome", ignoreCase = true)) {
            val inspectedTitle = findBrowserPageTitle(rootNode, windowInfo)
            if (!inspectedTitle.isNullOrBlank()) {
                appTitle = inspectedTitle
            }
        }

        // 3. If standard browser with no specific web app found, resolve standard browser label
        if (appTitle.isBlank() || appTitle.equals("Chrome", ignoreCase = true) || appTitle.equals("Google Chrome", ignoreCase = true)) {
            if (!pkgLower.contains("webapk")) {
                val browserLabel = try {
                    val info = pm.getApplicationInfo(packageName, 0)
                    pm.getApplicationLabel(info).toString()
                } catch (_: Throwable) {
                    "Chrome"
                }
                return AppContext("Other", browserLabel, "Other · $browserLabel")
            }
            appTitle = "Web App"
        }

        // 4. Clean up recognized Web App name & classify category
        val cleanName = when {
            appTitle.contains("Google AI Studio", ignoreCase = true) || appTitle.contains("AI Studio", ignoreCase = true) -> "Google AI Studio"
            appTitle.contains("ChatGPT", ignoreCase = true) -> "ChatGPT"
            appTitle.contains("Claude", ignoreCase = true) -> "Claude"
            appTitle.contains("Grok", ignoreCase = true) -> "Grok"
            appTitle.contains("DeepSeek", ignoreCase = true) -> "DeepSeek"
            appTitle.contains("Perplexity", ignoreCase = true) -> "Perplexity"
            appTitle.contains("Midjourney", ignoreCase = true) -> "Midjourney"
            appTitle.contains("GitHub", ignoreCase = true) -> "GitHub"
            appTitle.contains("Notion", ignoreCase = true) -> "Notion"
            appTitle.contains("Figma", ignoreCase = true) -> "Figma"
            appTitle.contains("Canva", ignoreCase = true) -> "Canva"
            appTitle.contains("Slack", ignoreCase = true) -> "Slack"
            appTitle.contains("WhatsApp", ignoreCase = true) -> "WhatsApp"
            appTitle.contains("Telegram", ignoreCase = true) -> "Telegram"
            appTitle.contains("Twitter", ignoreCase = true) || appTitle.contains("X.com", ignoreCase = true) -> "X"
            appTitle.contains("Reddit", ignoreCase = true) -> "Reddit"
            appTitle.contains("Discord", ignoreCase = true) -> "Discord"
            else -> appTitle.substringBefore(" - ").substringBefore(" | ").trim()
        }

        val group = classifyGroup(packageName.lowercase(), cleanName, ApplicationInfo.CATEGORY_UNDEFINED)
        return AppContext(group, cleanName, "$group · $cleanName")
    }

    private fun findBrowserPageTitle(rootNode: AccessibilityNodeInfo?, windowInfo: AccessibilityWindowInfo?): String? {
        val winTitle = windowInfo?.title?.toString()
        if (!winTitle.isNullOrBlank() && !winTitle.equals("Chrome", ignoreCase = true) && !winTitle.equals("Google Chrome", ignoreCase = true)) {
            val lower = winTitle.lowercase()
            if (lower.contains("google ai studio") || lower.contains("ai studio")) return "Google AI Studio"
            if (lower.contains("chatgpt")) return "ChatGPT"
            if (lower.contains("claude")) return "Claude"
            if (lower.contains("grok")) return "Grok"
            if (lower.contains("deepseek")) return "DeepSeek"
            if (lower.contains("perplexity")) return "Perplexity"
            if (lower.contains("notion")) return "Notion"
            if (lower.contains("figma")) return "Figma"
            if (lower.contains("github")) return "GitHub"
        }

        if (rootNode == null) return null
        return searchNodeForTitle(rootNode, 0)
    }

    private fun searchNodeForTitle(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > 8) return null
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val candidate = if (text.isNotBlank()) text else desc

        if (candidate.isNotBlank()) {
            val lower = candidate.lowercase()
            if (lower.contains("google ai studio") || lower.contains("ai studio")) return "Google AI Studio"
            if (lower.contains("chatgpt")) return "ChatGPT"
            if (lower.contains("claude")) return "Claude"
            if (lower.contains("grok")) return "Grok"
            if (lower.contains("deepseek")) return "DeepSeek"
            if (lower.contains("perplexity")) return "Perplexity"
            if (lower.contains("notion")) return "Notion"
            if (lower.contains("figma")) return "Figma"
            if (lower.contains("github")) return "GitHub"
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchNodeForTitle(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun computeDynamicResolution(context: Context, packageName: String): AppContext {
        val pm = context.packageManager
        val pkgLower = packageName.lowercase()

        // 1. Retrieve true human label via Launcher Intent / ApplicationInfo
        var resolvedLabel = ""
        var appCategory: Int = ApplicationInfo.CATEGORY_UNDEFINED

        try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            resolvedLabel = pm.getApplicationLabel(appInfo).toString()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCategory = appInfo.category
            }
        } catch (_: Throwable) {}

        // Fallback: If label is missing, generic, or identical to packageName, run the Intelligent Tokenizer
        if (resolvedLabel.isBlank() || resolvedLabel.contains(".") || resolvedLabel.equals("Application", ignoreCase = true) || resolvedLabel.equals("Intl", ignoreCase = true)) {
            resolvedLabel = tokenizeBrandName(packageName)
        }

        // Clean up common boilerplate prefixes/suffixes
        val cleanName = cleanAppLabel(resolvedLabel, packageName)

        // 2. Classify into Four Groups: Social, Work, AI, Other
        val group = classifyGroup(pkgLower, cleanName, appCategory)

        return AppContext(group, cleanName, "$group · $cleanName")
    }

    /**
     * Intelligent Tokenizer: Strips country codes, generic suffixes, and prefixes.
     * Prioritizes known brand roots (qwen, tongyi, sportybet, etc.).
     */
    fun tokenizeBrandName(packageName: String): String {
        val pkgLower = packageName.lowercase()

        // Check for direct brand keywords embedded anywhere in the package
        when {
            pkgLower.contains("qwen") -> return "Qwen"
            pkgLower.contains("tongyi") -> return "Qwen"
            pkgLower.contains("sportybet") -> return "SportyBet"
            pkgLower.contains("github") -> return "GitHub"
            pkgLower.contains("chatgpt") -> return "ChatGPT"
            pkgLower.contains("claude") -> return "Claude"
            pkgLower.contains("deepseek") -> return "DeepSeek"
            pkgLower.contains("grok") -> return "Grok"
            pkgLower.contains("perplexity") -> return "Perplexity"
            pkgLower.contains("copilot") -> return "Copilot"
            pkgLower.contains("whatsapp") -> return "WhatsApp"
            pkgLower.contains("telegram") -> return "Telegram"
            pkgLower.contains("instagram") -> return "Instagram"
            pkgLower.contains("messenger") -> return "Messenger"
            pkgLower.contains("tiktok") -> return "TikTok"
            pkgLower.contains("discord") -> return "Discord"
            pkgLower.contains("pinterest") -> return "Pinterest"
            pkgLower.contains("signal") -> return "Signal"
            pkgLower.contains("slack") -> return "Slack"
            pkgLower.contains("gmail") -> return "Gmail"
            pkgLower.contains("outlook") -> return "Outlook"
        }

        val tokens = packageName.split(".").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return "App"

        val filtered = tokens.toMutableList()
        // Strip prefixes (com, org, alibaba, etc.)
        while (filtered.isNotEmpty() && filtered.first().lowercase() in GENERIC_PREFIXES) {
            filtered.removeAt(0)
        }
        // Strip suffixes & country TLDs (android, gh, gp, intl, mobile, etc.)
        while (filtered.isNotEmpty() && (filtered.last().lowercase() in GENERIC_SUFFIXES || filtered.last().lowercase() in COUNTRY_TLDS)) {
            filtered.removeAt(filtered.size - 1)
        }

        val brandToken = if (filtered.isNotEmpty()) {
            filtered.last()
        } else {
            tokens.getOrNull(tokens.size - 2) ?: tokens.last()
        }

        return formatBrandToken(brandToken)
    }

    private fun formatBrandToken(token: String): String {
        return when (token.lowercase()) {
            "sportybet" -> "SportyBet"
            "github" -> "GitHub"
            "chatgpt" -> "ChatGPT"
            "deepseek" -> "DeepSeek"
            "whatsapp" -> "WhatsApp"
            "tiktok" -> "TikTok"
            "youtube" -> "YouTube"
            "linkedin" -> "LinkedIn"
            "qwen" -> "Qwen"
            "tongyi" -> "Qwen"
            else -> token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun cleanAppLabel(rawLabel: String, packageName: String): String {
        val pkgLower = packageName.lowercase()
        return when {
            pkgLower.contains("qwen") || pkgLower.contains("tongyi") || rawLabel.contains("Qwen", ignoreCase = true) || rawLabel.contains("Tongyi", ignoreCase = true) -> "Qwen"
            pkgLower.contains("sportybet") || rawLabel.contains("SportyBet", ignoreCase = true) -> "SportyBet"
            pkgLower.contains("messaging") || pkgLower.contains(".mms") || rawLabel.equals("Messages", ignoreCase = true) || rawLabel.equals("Google Messages", ignoreCase = true) -> "Messages"
            rawLabel.equals("Google Docs", ignoreCase = true) -> "Docs"
            rawLabel.equals("Google Sheets", ignoreCase = true) -> "Sheets"
            rawLabel.equals("Google Slides", ignoreCase = true) -> "Slides"
            rawLabel.equals("Google Keep", ignoreCase = true) || rawLabel.equals("Keep Notes", ignoreCase = true) -> "Keep"
            rawLabel.equals("Google Drive", ignoreCase = true) -> "Drive"
            rawLabel.equals("Google Chrome", ignoreCase = true) -> "Chrome"
            rawLabel.equals("Microsoft Outlook", ignoreCase = true) -> "Outlook"
            rawLabel.equals("Microsoft Teams", ignoreCase = true) -> "Teams"
            else -> rawLabel
        }
    }

    private fun classifyGroup(pkgLower: String, cleanName: String, appCategory: Int): String {
        // AI Check (Google AI Studio, Qwen, Tongyi, Grok, ChatGPT, Claude, Gemini, DeepSeek, Perplexity, Copilot, etc.)
        if (pkgLower.contains("qwen") || pkgLower.contains("tongyi") ||
            pkgLower.contains("grok") || pkgLower.contains("x.ai") ||
            pkgLower.contains("openai") || pkgLower.contains("chatgpt") ||
            pkgLower.contains("anthropic") || pkgLower.contains("claude") ||
            pkgLower.contains("bard") || pkgLower.contains("gemini") ||
            pkgLower.contains("perplexity") || pkgLower.contains("copilot") ||
            pkgLower.contains("poe") || pkgLower.contains("character.ai") ||
            pkgLower.contains("deepseek") ||
            cleanName.contains("Google AI Studio", ignoreCase = true) ||
            cleanName.contains("AI Studio", ignoreCase = true) ||
            cleanName.contains("Qwen", ignoreCase = true) ||
            cleanName.contains("Tongyi", ignoreCase = true) ||
            cleanName.contains("Grok", ignoreCase = true) ||
            cleanName.contains("ChatGPT", ignoreCase = true) ||
            cleanName.contains("Claude", ignoreCase = true) ||
            cleanName.contains("Gemini", ignoreCase = true) ||
            cleanName.contains("Perplexity", ignoreCase = true) ||
            cleanName.contains("DeepSeek", ignoreCase = true) ||
            cleanName.contains("Copilot", ignoreCase = true) ||
            cleanName.contains("Midjourney", ignoreCase = true) ||
            cleanName.contains("Hugging Face", ignoreCase = true) ||
            cleanName.contains("Replicate", ignoreCase = true)
        ) {
            return "AI"
        }

        // Social Check
        if (pkgLower.contains("messaging") || pkgLower.contains("mms") ||
            pkgLower.contains("whatsapp") || pkgLower.contains("telegram") ||
            pkgLower.contains("instagram") || pkgLower.contains("messenger") ||
            pkgLower.contains("tiktok") || pkgLower.contains("twitter") ||
            pkgLower.contains("snapchat") || pkgLower.contains("reddit") ||
            pkgLower.contains("discord") || pkgLower.contains("pinterest") ||
            pkgLower.contains("signal") || pkgLower.contains("linkedin") ||
            pkgLower.contains("wechat") || pkgLower.contains("facebook") ||
            pkgLower.contains("threads") || pkgLower.contains("viber") ||
            pkgLower.contains("line") ||
            cleanName.equals("Messages", ignoreCase = true) ||
            cleanName.contains("WhatsApp", ignoreCase = true) ||
            cleanName.contains("Telegram", ignoreCase = true) ||
            cleanName.contains("Instagram", ignoreCase = true) ||
            cleanName.contains("Messenger", ignoreCase = true) ||
            cleanName.contains("TikTok", ignoreCase = true) ||
            cleanName.contains("Twitter", ignoreCase = true) ||
            cleanName.contains("Snapchat", ignoreCase = true) ||
            cleanName.contains("Reddit", ignoreCase = true) ||
            cleanName.contains("Discord", ignoreCase = true) ||
            cleanName.contains("Pinterest", ignoreCase = true) ||
            cleanName.contains("LinkedIn", ignoreCase = true) ||
            cleanName.contains("Threads", ignoreCase = true) ||
            cleanName.contains("Facebook", ignoreCase = true)
        ) {
            return "Social"
        }

        // Work Check
        if (pkgLower.contains("github") || pkgLower.contains("gmail") || pkgLower.contains("google.android.gm") ||
            pkgLower.contains("outlook") || pkgLower.contains("slack") ||
            pkgLower.contains("docs") || pkgLower.contains("sheets") ||
            pkgLower.contains("slides") || pkgLower.contains("teams") ||
            pkgLower.contains("notion") || pkgLower.contains("keep") ||
            pkgLower.contains("trello") || pkgLower.contains("asana") ||
            pkgLower.contains("zoom") || pkgLower.contains("linear") ||
            pkgLower.contains("jira") || pkgLower.contains("superhuman") ||
            pkgLower.contains("calendar") || pkgLower.contains("word") ||
            pkgLower.contains("excel") || pkgLower.contains("powerpoint") ||
            pkgLower.contains("office") ||
            cleanName.contains("GitHub", ignoreCase = true) ||
            cleanName.contains("Gmail", ignoreCase = true) ||
            cleanName.contains("Outlook", ignoreCase = true) ||
            cleanName.contains("Slack", ignoreCase = true) ||
            cleanName.contains("Docs", ignoreCase = true) ||
            cleanName.contains("Sheets", ignoreCase = true) ||
            cleanName.contains("Slides", ignoreCase = true) ||
            cleanName.contains("Teams", ignoreCase = true) ||
            cleanName.contains("Notion", ignoreCase = true) ||
            cleanName.contains("Keep", ignoreCase = true) ||
            cleanName.contains("Trello", ignoreCase = true) ||
            cleanName.contains("Asana", ignoreCase = true) ||
            cleanName.contains("Zoom", ignoreCase = true) ||
            cleanName.contains("Linear", ignoreCase = true) ||
            cleanName.contains("Jira", ignoreCase = true) ||
            cleanName.contains("Figma", ignoreCase = true) ||
            cleanName.contains("Canva", ignoreCase = true)
        ) {
            return "Work"
        }

        // Native Android OS Category Fallbacks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (appCategory == ApplicationInfo.CATEGORY_SOCIAL) return "Social"
            if (appCategory == ApplicationInfo.CATEGORY_PRODUCTIVITY) return "Work"
        }

        return "Other"
    }
}

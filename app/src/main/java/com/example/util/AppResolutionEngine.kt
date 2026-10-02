package com.example.util

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
 * AppResolutionEngine in package com.example.util:
 * - Uses android.util.LruCache to store resolved AppContext instances for O(1) retrieval.
 * - Maps native package names (e.g. com.google.android.apps.bard) to friendly product identities (Gemini).
 * - Hardened heuristic methods that inspect accessibility node titles, window titles, URL Omnibox bars, and DOM trees
 *   to ensure Gemini inside Google QuickSearchBox and Google AI Studio inside Chrome/browsers/WebAPKs are never mislabeled.
 */
class AppResolutionEngine(cacheSize: Int = 150) {

    companion object {
        private const val TAG = "AppResolutionEngine"

        val defaultInstance = AppResolutionEngine(150)

        // Native Package to Friendly App Identity Map
        private val NATIVE_APP_MAP = mapOf(
            // AI Applications
            "com.google.android.apps.bard" to AppContext(name = "Gemini", category = "AI", isAiApp = true),
            "com.google.android.apps.gemini" to AppContext(name = "Gemini", category = "AI", isAiApp = true),
            "com.openai.chatgpt" to AppContext(name = "ChatGPT", category = "AI", isAiApp = true),
            "com.anthropic.claude" to AppContext(name = "Claude", category = "AI", isAiApp = true),
            "ai.x.grok" to AppContext(name = "Grok", category = "AI", isAiApp = true),
            "ai.perplexity.app.android" to AppContext(name = "Perplexity", category = "AI", isAiApp = true),
            "com.microsoft.copilot" to AppContext(name = "Copilot", category = "AI", isAiApp = true),
            "com.deepseek.chat" to AppContext(name = "DeepSeek", category = "AI", isAiApp = true),
            "com.alibaba.tongyi.intl" to AppContext(name = "Qwen", category = "AI", isAiApp = true),
            "com.alibaba.qwen.intl" to AppContext(name = "Qwen", category = "AI", isAiApp = true),
            "com.aliyun.tongyi.intl" to AppContext(name = "Qwen", category = "AI", isAiApp = true),
            "com.alibaba.tongyi" to AppContext(name = "Qwen", category = "AI", isAiApp = true),
            "com.alibaba.qwen" to AppContext(name = "Qwen", category = "AI", isAiApp = true),
            "com.qwen.ai" to AppContext(name = "Qwen", category = "AI", isAiApp = true),
            "ai.qwen.chat" to AppContext(name = "Qwen", category = "AI", isAiApp = true),
            "com.poe.android" to AppContext(name = "Poe", category = "AI", isAiApp = true),
            "ai.character.app" to AppContext(name = "Character.AI", category = "AI", isAiApp = true),

            // Social Applications
            "com.whatsapp" to AppContext(name = "WhatsApp", category = "Social", isAiApp = false),
            "com.whatsapp.w4b" to AppContext(name = "WhatsApp Business", category = "Social", isAiApp = false),
            "com.google.android.apps.messaging" to AppContext(name = "Messages", category = "Social", isAiApp = false),
            "com.android.mms" to AppContext(name = "Messages", category = "Social", isAiApp = false),
            "org.telegram.messenger" to AppContext(name = "Telegram", category = "Social", isAiApp = false),
            "org.telegram.messenger.web" to AppContext(name = "Telegram", category = "Social", isAiApp = false),
            "com.instagram.android" to AppContext(name = "Instagram", category = "Social", isAiApp = false),
            "com.facebook.orca" to AppContext(name = "Messenger", category = "Social", isAiApp = false),
            "com.facebook.katana" to AppContext(name = "Facebook", category = "Social", isAiApp = false),
            "com.zhiliaoapp.musically" to AppContext(name = "TikTok", category = "Social", isAiApp = false),
            "com.twitter.android" to AppContext(name = "X", category = "Social", isAiApp = false),
            "com.snapchat.android" to AppContext(name = "Snapchat", category = "Social", isAiApp = false),
            "com.reddit.frontpage" to AppContext(name = "Reddit", category = "Social", isAiApp = false),
            "com.discord" to AppContext(name = "Discord", category = "Social", isAiApp = false),
            "com.pinterest" to AppContext(name = "Pinterest", category = "Social", isAiApp = false),
            "org.thoughtcrime.securesms" to AppContext(name = "Signal", category = "Social", isAiApp = false),
            "com.linkedin.android" to AppContext(name = "LinkedIn", category = "Social", isAiApp = false),
            "com.instagram.barcelona" to AppContext(name = "Threads", category = "Social", isAiApp = false),

            // Work & Productivity Applications
            "com.github.android" to AppContext(name = "GitHub", category = "Work", isAiApp = false),
            "com.google.android.gm" to AppContext(name = "Gmail", category = "Work", isAiApp = false),
            "com.microsoft.office.outlook" to AppContext(name = "Outlook", category = "Work", isAiApp = false),
            "com.Slack" to AppContext(name = "Slack", category = "Work", isAiApp = false),
            "com.slack" to AppContext(name = "Slack", category = "Work", isAiApp = false),
            "com.google.android.apps.docs" to AppContext(name = "Docs", category = "Work", isAiApp = false),
            "com.google.android.apps.docs.editors.docs" to AppContext(name = "Docs", category = "Work", isAiApp = false),
            "com.google.android.apps.docs.editors.sheets" to AppContext(name = "Sheets", category = "Work", isAiApp = false),
            "com.google.android.apps.docs.editors.slides" to AppContext(name = "Slides", category = "Work", isAiApp = false),
            "com.google.android.keep" to AppContext(name = "Keep", category = "Work", isAiApp = false),
            "com.microsoft.teams" to AppContext(name = "Teams", category = "Work", isAiApp = false),
            "notion.id" to AppContext(name = "Notion", category = "Work", isAiApp = false),
            "com.trello" to AppContext(name = "Trello", category = "Work", isAiApp = false),
            "com.asana.app" to AppContext(name = "Asana", category = "Work", isAiApp = false),
            "us.zoom.videomeetings" to AppContext(name = "Zoom", category = "Work", isAiApp = false),

            // Regional & Specific Brand Applications
            "com.sportybet.android.gp" to AppContext(name = "SportyBet", category = "Other", isAiApp = false),
            "com.sportybet.android.gh" to AppContext(name = "SportyBet", category = "Other", isAiApp = false),
            "com.sportybet.android.ng" to AppContext(name = "SportyBet", category = "Other", isAiApp = false),
            "com.sportybet.gp" to AppContext(name = "SportyBet", category = "Other", isAiApp = false),
            "com.sportybet.gh" to AppContext(name = "SportyBet", category = "Other", isAiApp = false),
            "com.sportybet" to AppContext(name = "SportyBet", category = "Other", isAiApp = false)
        )

        // Web App / PWA Heuristics Table
        private data class WebAppHeuristic(
            val keywords: List<String>,
            val context: AppContext
        )

        private val WEB_APP_HEURISTICS = listOf(
            WebAppHeuristic(
                keywords = listOf(
                    "aistudio.google.com",
                    "aistudio",
                    "google ai studio",
                    "ai studio",
                    "ais-dev-",
                    "ais-pre-",
                    "googleaistudio"
                ),
                context = AppContext(name = "Google AI Studio", category = "AI", isAiApp = true)
            ),
            WebAppHeuristic(
                keywords = listOf("chatgpt.com", "chat.openai.com", "openai.com/chat"),
                context = AppContext(name = "ChatGPT", category = "AI", isAiApp = true)
            ),
            WebAppHeuristic(
                keywords = listOf("claude.ai", "anthropic.com"),
                context = AppContext(name = "Claude", category = "AI", isAiApp = true)
            ),
            WebAppHeuristic(
                keywords = listOf("grok.com", "x.com/i/grok"),
                context = AppContext(name = "Grok", category = "AI", isAiApp = true)
            ),
            WebAppHeuristic(
                keywords = listOf("deepseek.com", "chat.deepseek.com"),
                context = AppContext(name = "DeepSeek", category = "AI", isAiApp = true)
            ),
            WebAppHeuristic(
                keywords = listOf("perplexity.ai"),
                context = AppContext(name = "Perplexity", category = "AI", isAiApp = true)
            ),
            WebAppHeuristic(
                keywords = listOf("v0.dev"),
                context = AppContext(name = "v0", category = "AI", isAiApp = true)
            ),
            WebAppHeuristic(
                keywords = listOf("github.com"),
                context = AppContext(name = "GitHub", category = "Work", isAiApp = false)
            ),
            WebAppHeuristic(
                keywords = listOf("notion.so"),
                context = AppContext(name = "Notion", category = "Work", isAiApp = false)
            ),
            WebAppHeuristic(
                keywords = listOf("figma.com"),
                context = AppContext(name = "Figma", category = "Work", isAiApp = false)
            ),
            WebAppHeuristic(
                keywords = listOf("canva.com"),
                context = AppContext(name = "Canva", category = "Work", isAiApp = false)
            ),
            WebAppHeuristic(
                keywords = listOf("web.whatsapp.com"),
                context = AppContext(name = "WhatsApp", category = "Social", isAiApp = false)
            ),
            WebAppHeuristic(
                keywords = listOf("web.telegram.org"),
                context = AppContext(name = "Telegram", category = "Social", isAiApp = false)
            ),
            WebAppHeuristic(
                keywords = listOf("twitter.com", "x.com"),
                context = AppContext(name = "X", category = "Social", isAiApp = false)
            ),
            WebAppHeuristic(
                keywords = listOf("reddit.com"),
                context = AppContext(name = "Reddit", category = "Social", isAiApp = false)
            ),
            WebAppHeuristic(
                keywords = listOf("discord.com"),
                context = AppContext(name = "Discord", category = "Social", isAiApp = false)
            )
        )

        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.google.android.apps.chrome",
            "com.sec.android.app.sbrowser",
            "com.microsoft.emmx",
            "com.brave.browser",
            "org.mozilla.firefox",
            "com.opera.browser",
            "com.vivaldi.browser"
        )
    }

    /**
     * Data class holding resolved application identity, category, and AI classification.
     */
    data class AppContext(
        val name: String,
        val category: String,
        val isAiApp: Boolean
    ) {
        val formatted: String get() = "$category · $name"
    }

    // LRU Cache for zero-lag O(1) resolution
    private val appCache = object : LruCache<String, AppContext>(cacheSize) {}

    /**
     * Resolves an AppContext for the given package name and accessibility UI tree.
     */
    fun resolve(
        context: Context,
        packageName: String?,
        windowInfo: AccessibilityWindowInfo? = null,
        rootNode: AccessibilityNodeInfo? = null,
        className: String? = null
    ): AppContext? {
        if (packageName.isNullOrBlank() || isSystemOrIme(context, packageName)) {
            return null
        }

        val pkgLower = packageName.lowercase()
        val a11y = com.example.service.VoxStreamAccessibilityService.instance
        val effectiveWin = windowInfo ?: a11y?.getActiveApplicationWindow()
        val effectiveRoot = rootNode ?: a11y?.rootInActiveWindow ?: effectiveWin?.root

        // 1. Native Gemini Resolution
        if (pkgLower == "com.google.android.apps.bard" || pkgLower == "com.google.android.apps.gemini") {
            return AppContext(name = "Gemini", category = "AI", isAiApp = true)
        }

        // 2. Google Search QuickSearchBox with Gemini session
        if (packageName.equals("com.google.android.googlequicksearchbox", ignoreCase = true)) {
            val isGemini = isGeminiSession(effectiveWin, effectiveRoot, className)
            return if (isGemini) {
                AppContext(name = "Gemini", category = "AI", isAiApp = true)
            } else {
                // If package is googlequicksearchbox, default to Gemini when user invokes voice typing
                // unless it's clearly standard web search with zero Gemini indicators.
                AppContext(name = "Gemini", category = "AI", isAiApp = true)
            }
        }

        // 3. Browser & PWA Heuristic Detection (Chrome, Brave, Samsung Internet, etc.)
        if (isBrowserOrPwa(pkgLower)) {
            val pwaContext = detectPwaHostedApp(context, packageName, effectiveWin, effectiveRoot)
            if (pwaContext != null) {
                Log.d(TAG, "[AppResolutionEngine] Detected PWA: ${pwaContext.formatted} ($packageName)")
                return pwaContext
            }
        }

        // 4. Fast-Path Whitelist Match
        NATIVE_APP_MAP[packageName]?.let {
            return it
        }

        // 5. O(1) LRU Cache Check
        val cached = appCache.get(packageName)
        if (cached != null) {
            return cached
        }

        // 6. Dynamic Package Inspection & Tokenization
        val resolved = computeDynamicResolution(context, packageName)
        if (resolved != null) {
            appCache.put(packageName, resolved)
        }
        return resolved
    }

    /**
     * Heuristic method to inspect accessibility node titles and window titles to identify
     * PWA-hosted apps (like Google AI Studio) inside browser packages.
     */
    fun detectPwaHostedApp(
        context: Context,
        packageName: String,
        windowInfo: AccessibilityWindowInfo?,
        rootNode: AccessibilityNodeInfo?
    ): AppContext? {
        val pm = context.packageManager
        val pkgLower = packageName.lowercase()
        val a11y = com.example.service.VoxStreamAccessibilityService.instance

        // 1. WebAPK Manifest Metadata Inspection
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
                val metaName = meta?.getString("org.chromium.webapk.shell_apk.name")
                    ?: meta?.getString("org.chromium.webapk.shell_apk.shortName")
                    ?: ""

                val label = pm.getApplicationLabel(appInfo).toString()
                val candidate = if (metaName.isNotBlank()) metaName else label

                val matched = matchPwaSignature(candidate)
                if (matched != null) return matched

                if (candidate.isNotBlank() && !candidate.startsWith("org.chromium") && !candidate.equals("Web Application", ignoreCase = true)) {
                    val isAi = isAiTitle(candidate)
                    val category = if (isAi) "AI" else "Other"
                    return AppContext(name = candidate, category = category, isAiApp = isAi)
                }
            } catch (_: Throwable) {}
        }

        // 2. Window Title Inspection across all active application windows
        val allWindows = try { a11y?.getWindows() } catch (_: Throwable) { null }
        if (!allWindows.isNullOrEmpty()) {
            for (w in allWindows) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val wTitle = w.title?.toString()
                    val wRoot = w.root
                    val wPkg = wRoot?.packageName?.toString() ?: ""
                    if (wPkg.isBlank() || wPkg.equals(packageName, ignoreCase = true) || isBrowserOrPwa(wPkg)) {
                        if (!wTitle.isNullOrBlank() && !isGenericBrowserTitle(wTitle)) {
                            val matched = matchPwaSignature(wTitle)
                            if (matched != null) return matched
                        }
                        if (wRoot != null) {
                            val urlBar = findBrowserUrl(wRoot)
                            if (!urlBar.isNullOrBlank()) {
                                val matchedUrl = matchPwaSignature(urlBar)
                                if (matchedUrl != null) return matchedUrl
                            }
                            val domMatch = scanNodeHierarchy(wRoot, 0, 8)
                            if (domMatch != null) return domMatch
                        }
                    }
                }
            }
        }

        // 3. Fallback to passed windowInfo & rootNode
        val winTitle = windowInfo?.title?.toString()
        if (!winTitle.isNullOrBlank() && !isGenericBrowserTitle(winTitle)) {
            val matched = matchPwaSignature(winTitle)
            if (matched != null) return matched
        }

        if (rootNode != null) {
            val urlText = findBrowserUrl(rootNode)
            if (!urlText.isNullOrBlank()) {
                val matched = matchPwaSignature(urlText)
                if (matched != null) return matched
            }

            val domMatched = scanNodeHierarchy(rootNode, 0, 8)
            if (domMatched != null) return domMatched
        }

        // Hardened AI Studio Fallback: If running inside a browser and any AI Studio signature / keyword is present anywhere in recent nodes
        if (rootNode != null) {
            val aiStudioFound = searchTreeForKeywords(rootNode, listOf("google ai studio", "ai studio", "aistudio", "api key", "system instructions"), 0, 6)
            if (aiStudioFound != null) {
                return AppContext(name = "Google AI Studio", category = "AI", isAiApp = true)
            }
        }

        // Fallback for generic browser
        if (!pkgLower.contains("webapk")) {
            val label = try {
                val info = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(info).toString()
            } catch (_: Throwable) {
                "Chrome"
            }
            return AppContext(name = label, category = "Other", isAiApp = false)
        }

        return AppContext(name = "Web App", category = "Other", isAiApp = false)
    }

    /**
     * Matches raw text against known PWA signatures.
     */
    fun matchPwaSignature(rawText: String?): AppContext? {
        if (rawText.isNullOrBlank()) return null
        val lower = rawText.lowercase()
        for (heuristic in WEB_APP_HEURISTICS) {
            if (heuristic.keywords.any { lower.contains(it) }) {
                return heuristic.context
            }
        }
        if (lower.contains("google ai studio") || lower.contains("ai studio") || lower.contains("aistudio")) {
            return AppContext(name = "Google AI Studio", category = "AI", isAiApp = true)
        }
        return null
    }

    private fun findBrowserUrl(rootNode: AccessibilityNodeInfo): String? {
        val urlViewIds = listOf(
            "com.android.chrome:id/url_bar",
            "com.chrome.beta:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.microsoft.emmx:id/url_bar",
            "org.mozilla.firefox:id/url_bar_title",
            "url_bar",
            "location_bar",
            "search_box_text"
        )
        for (id in urlViewIds) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        val text = node.text?.toString()?.trim()
                        if (!text.isNullOrBlank()) return text
                    }
                }
            } catch (_: Throwable) {}
        }

        // Search top 4 levels for any node containing URL syntax
        return searchNodeForUrl(rootNode, 0, 4)
    }

    private fun searchNodeForUrl(node: AccessibilityNodeInfo?, depth: Int, maxDepth: Int): String? {
        if (node == null || depth > maxDepth) return null
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotBlank()) {
            val lower = text.lowercase()
            if (lower.contains("aistudio") || lower.contains(".google.com") || lower.contains(".ai") || lower.contains(".com/") || lower.startsWith("http")) {
                return text
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchNodeForUrl(child, depth + 1, maxDepth)
            if (found != null) return found
        }
        return null
    }

    private fun scanNodeHierarchy(node: AccessibilityNodeInfo?, depth: Int, maxDepth: Int): AppContext? {
        if (node == null || depth > maxDepth) return null
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val candidate = if (text.isNotBlank()) text else desc

        if (candidate.isNotBlank()) {
            val matched = matchPwaSignature(candidate)
            if (matched != null) return matched
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = scanNodeHierarchy(child, depth + 1, maxDepth)
            if (found != null) return found
        }
        return null
    }

    private fun isGeminiSession(
        windowInfo: AccessibilityWindowInfo?,
        rootNode: AccessibilityNodeInfo?,
        className: String?
    ): Boolean {
        if (className != null && (
            className.contains("RobinActivity", ignoreCase = true) ||
            className.contains("Gemini", ignoreCase = true) ||
            className.contains("Bard", ignoreCase = true) ||
            className.contains("Assistant", ignoreCase = true) ||
            className.contains("Search", ignoreCase = true)
        )) {
            return true
        }

        val a11y = com.example.service.VoxStreamAccessibilityService.instance
        val allWindows = try { a11y?.getWindows() } catch (_: Throwable) { null }
        if (!allWindows.isNullOrEmpty()) {
            for (w in allWindows) {
                val wTitle = w.title?.toString() ?: ""
                val wRoot = w.root
                val wPkg = wRoot?.packageName?.toString() ?: ""
                if (wPkg.contains("googlequicksearchbox", ignoreCase = true) || wPkg.contains("bard", ignoreCase = true) || wPkg.contains("gemini", ignoreCase = true)) {
                    if (wTitle.contains("Gemini", ignoreCase = true) || wTitle.contains("Bard", ignoreCase = true) || wTitle.contains("Robin", ignoreCase = true) || wTitle.contains("Ask Gemini", ignoreCase = true)) {
                        return true
                    }
                    if (wRoot != null) {
                        val found = searchTreeForKeywords(wRoot, listOf("gemini", "bard", "ask gemini", "robin", "chat with gemini", "ask anything"), 0, 6)
                        if (found != null) return true
                    }
                }
            }
        }

        val windowTitle = windowInfo?.title?.toString() ?: ""
        if (windowTitle.contains("Gemini", ignoreCase = true) || windowTitle.contains("Bard", ignoreCase = true) || windowTitle.contains("Ask Gemini", ignoreCase = true)) {
            return true
        }
        val rootDesc = rootNode?.contentDescription?.toString() ?: ""
        if (rootDesc.contains("Gemini", ignoreCase = true) || rootDesc.contains("Bard", ignoreCase = true) || rootDesc.contains("Ask Gemini", ignoreCase = true)) {
            return true
        }
        if (rootNode != null) {
            val found = searchTreeForKeywords(rootNode, listOf("gemini", "bard", "ask gemini", "chat with gemini", "ask anything"), 0, 6)
            if (found != null) return true
        }

        // If googlequicksearchbox is active and no explicit web search non-AI result is shown, prefer Gemini as primary modern Android assistant host
        return true
    }

    private fun searchTreeForKeywords(node: AccessibilityNodeInfo?, keywords: List<String>, depth: Int, maxDepth: Int): String? {
        if (node == null || depth > maxDepth) return null
        val text = (node.text?.toString() ?: "").lowercase()
        val desc = (node.contentDescription?.toString() ?: "").lowercase()
        for (kw in keywords) {
            if (text.contains(kw) || desc.contains(kw)) {
                return kw
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchTreeForKeywords(child, keywords, depth + 1, maxDepth)
            if (found != null) return found
        }
        return null
    }

    private fun computeDynamicResolution(context: Context, packageName: String): AppContext {
        val pm = context.packageManager
        val pkgLower = packageName.lowercase()

        if (pkgLower.contains("bard") || pkgLower.contains("gemini") || pkgLower.contains("googlequicksearchbox")) {
            return AppContext(name = "Gemini", category = "AI", isAiApp = true)
        }

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

        if (resolvedLabel.isBlank() || resolvedLabel.contains(".") || resolvedLabel.equals("Application", ignoreCase = true)) {
            resolvedLabel = tokenize(packageName)
        }

        val isAi = isAiPackage(pkgLower) || isAiTitle(resolvedLabel)
        val category = when {
            isAi -> "AI"
            isSocialPackage(pkgLower, resolvedLabel) -> "Social"
            isWorkPackage(pkgLower, resolvedLabel) -> "Work"
            else -> "Other"
        }

        return AppContext(name = resolvedLabel, category = category, isAiApp = isAi)
    }

    private fun tokenize(packageName: String): String {
        val pkgLower = packageName.lowercase()
        when {
            pkgLower.contains("bard") || pkgLower.contains("gemini") || pkgLower.contains("googlequicksearchbox") -> return "Gemini"
            pkgLower.contains("qwen") || pkgLower.contains("tongyi") -> return "Qwen"
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
            pkgLower.contains("tiktok") -> return "TikTok"
            pkgLower.contains("discord") -> return "Discord"
        }
        val tokens = packageName.split(".").filter { it.isNotBlank() }
        val best = tokens.lastOrNull() ?: "App"
        return best.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun isBrowserOrPwa(pkgLower: String): Boolean {
        return pkgLower.startsWith("org.chromium.webapk") ||
                pkgLower.contains(".webapk") ||
                pkgLower in BROWSER_PACKAGES
    }

    private fun isGenericBrowserTitle(title: String): Boolean {
        val lower = title.lowercase().trim()
        return lower == "chrome" || lower == "google chrome" || lower == "brave" || lower == "samsung internet" || lower == "firefox" || lower == "edge"
    }

    private fun isAiTitle(title: String): Boolean {
        val lower = title.lowercase()
        return lower.contains("ai studio") || lower.contains("google ai studio") || lower.contains("chatgpt") ||
                lower.contains("claude") || lower.contains("gemini") || lower.contains("grok") ||
                lower.contains("perplexity") || lower.contains("deepseek") || lower.contains("copilot") ||
                lower.contains("qwen") || lower.contains("aistudio")
    }

    private fun isAiPackage(pkgLower: String): Boolean {
        return pkgLower.contains("bard") || pkgLower.contains("gemini") || pkgLower.contains("openai") ||
                pkgLower.contains("chatgpt") || pkgLower.contains("claude") || pkgLower.contains("anthropic") ||
                pkgLower.contains("grok") || pkgLower.contains("deepseek") || pkgLower.contains("perplexity") ||
                pkgLower.contains("copilot") || pkgLower.contains("qwen") || pkgLower.contains("tongyi") || pkgLower.contains("googlequicksearchbox")
    }

    private fun isSocialPackage(pkgLower: String, title: String): Boolean {
        return pkgLower.contains("whatsapp") || pkgLower.contains("telegram") || pkgLower.contains("instagram") ||
                pkgLower.contains("messaging") || pkgLower.contains("tiktok") || pkgLower.contains("twitter") ||
                pkgLower.contains("discord") || title.contains("WhatsApp", ignoreCase = true) || title.contains("Telegram", ignoreCase = true)
    }

    private fun isWorkPackage(pkgLower: String, title: String): Boolean {
        return pkgLower.contains("github") || pkgLower.contains("gmail") || pkgLower.contains("outlook") ||
                pkgLower.contains("slack") || pkgLower.contains("docs") || pkgLower.contains("notion") ||
                title.contains("GitHub", ignoreCase = true) || title.contains("Slack", ignoreCase = true)
    }

    fun isSystemOrIme(context: Context?, packageName: String?): Boolean {
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
        return false
    }
}

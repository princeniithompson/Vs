package com.example.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * AppResolutionEngine: Multi-Signal Evidence-Based Resolver for VoxStream.
 *
 * Evaluates foreground applications by collecting multi-signal AppEvidence
 * (package name, class name, window title, visible node texts, content descriptions,
 * URL/domain, WebAPK metadata, and app labels) and resolving them deterministically into:
 * - ResolvedIdentity (Exact known application, e.g. GEMINI, GOOGLE_AI_STUDIO, CHATGAT, WHATSAPP)
 * - ResolvedCategory (Semantic classification, e.g. AI_ASSISTANT, AI_DEVELOPER_TOOL, CHAT_MESSAGING, BROWSER)
 * - AppContext (Formatted user-facing representation: "AI · Gemini", "AI · Google AI Studio", etc.)
 */
class AppResolutionEngine(cacheSize: Int = 150) {

    /**
     * Conceptual internal evidence collection containing all accessibility signals.
     */
    data class AppEvidence(
        val packageName: String?,
        val className: String? = null,
        val windowTitle: String? = null,
        val visibleNodeTexts: List<String> = emptyList(),
        val contentDescriptions: List<String> = emptyList(),
        val urlOrDomain: String? = null,
        val webApkMetaName: String? = null,
        val appLabel: String? = null
    )

    /**
     * Exact known application identity.
     */
    enum class ResolvedIdentity {
        GEMINI,
        CHATGPT,
        CLAUDE,
        GROK,
        DEEPSEEK,
        PERPLEXITY,
        COPILOT,
        QWEN,
        POE,
        CHARACTER_AI,
        GOOGLE_AI_STUDIO,
        V0,
        WHATSAPP,
        MESSAGES,
        TELEGRAM,
        INSTAGRAM,
        MESSENGER,
        FACEBOOK,
        TIKTOK,
        X_TWITTER,
        SNAPCHAT,
        REDDIT,
        DISCORD,
        SIGNAL,
        LINKEDIN,
        GITHUB,
        GMAIL,
        OUTLOOK,
        SLACK,
        DOCS,
        SHEETS,
        SLIDES,
        KEEP,
        TEAMS,
        NOTION,
        FIGMA,
        CANVA,
        SPORTYBET,
        PINTEREST,
        THREADS,
        TRELLO,
        ASANA,
        ZOOM,
        SOCIAL,
        CHROME,
        GOOGLE_SEARCH,
        UNKNOWN
    }

    /**
     * Semantic application category classification.
     */
    enum class ResolvedCategory {
        AI_ASSISTANT,
        AI_DEVELOPER_TOOL,
        CHAT_MESSAGING,
        BROWSER,
        PRODUCTIVITY,
        SOCIAL,
        OTHER
    }

    /**
     * User-facing application context data class.
     */
    data class AppContext(
        val name: String,
        val category: String,
        val isAiApp: Boolean,
        val identity: ResolvedIdentity = ResolvedIdentity.UNKNOWN,
        val resolvedCategory: ResolvedCategory = ResolvedCategory.OTHER
    ) {
        val formatted: String get() = "$category · $name"
    }

    companion object {
        private const val TAG = "AppResolutionEngine"

        val defaultInstance = AppResolutionEngine(150)

        // Native Package to Exact Identity Map
        private val NATIVE_APP_MAP = mapOf(
            // AI Native Applications
            "com.google.android.apps.bard" to AppContext("Gemini", "AI", true, ResolvedIdentity.GEMINI, ResolvedCategory.AI_ASSISTANT),
            "com.google.android.apps.gemini" to AppContext("Gemini", "AI", true, ResolvedIdentity.GEMINI, ResolvedCategory.AI_ASSISTANT),
            "com.openai.chatgpt" to AppContext("ChatGPT", "AI", true, ResolvedIdentity.CHATGPT, ResolvedCategory.AI_ASSISTANT),
            "com.anthropic.claude" to AppContext("Claude", "AI", true, ResolvedIdentity.CLAUDE, ResolvedCategory.AI_ASSISTANT),
            "ai.x.grok" to AppContext("Grok", "AI", true, ResolvedIdentity.GROK, ResolvedCategory.AI_ASSISTANT),
            "ai.perplexity.app.android" to AppContext("Perplexity", "AI", true, ResolvedIdentity.PERPLEXITY, ResolvedCategory.AI_ASSISTANT),
            "com.microsoft.copilot" to AppContext("Copilot", "AI", true, ResolvedIdentity.COPILOT, ResolvedCategory.AI_ASSISTANT),
            "com.deepseek.chat" to AppContext("DeepSeek", "AI", true, ResolvedIdentity.DEEPSEEK, ResolvedCategory.AI_ASSISTANT),
            "com.alibaba.tongyi.intl" to AppContext("Qwen", "AI", true, ResolvedIdentity.QWEN, ResolvedCategory.AI_ASSISTANT),
            "com.alibaba.qwen.intl" to AppContext("Qwen", "AI", true, ResolvedIdentity.QWEN, ResolvedCategory.AI_ASSISTANT),
            "com.aliyun.tongyi.intl" to AppContext("Qwen", "AI", true, ResolvedIdentity.QWEN, ResolvedCategory.AI_ASSISTANT),
            "com.alibaba.tongyi" to AppContext("Qwen", "AI", true, ResolvedIdentity.QWEN, ResolvedCategory.AI_ASSISTANT),
            "com.alibaba.qwen" to AppContext("Qwen", "AI", true, ResolvedIdentity.QWEN, ResolvedCategory.AI_ASSISTANT),
            "com.qwen.ai" to AppContext("Qwen", "AI", true, ResolvedIdentity.QWEN, ResolvedCategory.AI_ASSISTANT),
            "ai.qwen.chat" to AppContext("Qwen", "AI", true, ResolvedIdentity.QWEN, ResolvedCategory.AI_ASSISTANT),
            "com.poe.android" to AppContext("Poe", "AI", true, ResolvedIdentity.POE, ResolvedCategory.AI_ASSISTANT),
            "ai.character.app" to AppContext("Character.AI", "AI", true, ResolvedIdentity.CHARACTER_AI, ResolvedCategory.AI_ASSISTANT),

            // Social Native Applications
            "com.whatsapp" to AppContext("WhatsApp", "Social", false, ResolvedIdentity.WHATSAPP, ResolvedCategory.CHAT_MESSAGING),
            "com.whatsapp.w4b" to AppContext("WhatsApp Business", "Social", false, ResolvedIdentity.WHATSAPP, ResolvedCategory.CHAT_MESSAGING),
            "com.google.android.apps.messaging" to AppContext("Messages", "Social", false, ResolvedIdentity.MESSAGES, ResolvedCategory.CHAT_MESSAGING),
            "com.android.mms" to AppContext("Messages", "Social", false, ResolvedIdentity.MESSAGES, ResolvedCategory.CHAT_MESSAGING),
            "org.telegram.messenger" to AppContext("Telegram", "Social", false, ResolvedIdentity.TELEGRAM, ResolvedCategory.CHAT_MESSAGING),
            "org.telegram.messenger.web" to AppContext("Telegram", "Social", false, ResolvedIdentity.TELEGRAM, ResolvedCategory.CHAT_MESSAGING),
            "com.instagram.android" to AppContext("Instagram", "Social", false, ResolvedIdentity.INSTAGRAM, ResolvedCategory.SOCIAL),
            "com.facebook.orca" to AppContext("Messenger", "Social", false, ResolvedIdentity.MESSENGER, ResolvedCategory.CHAT_MESSAGING),
            "com.facebook.katana" to AppContext("Facebook", "Social", false, ResolvedIdentity.FACEBOOK, ResolvedCategory.SOCIAL),
            "com.zhiliaoapp.musically" to AppContext("TikTok", "Social", false, ResolvedIdentity.TIKTOK, ResolvedCategory.SOCIAL),
            "com.twitter.android" to AppContext("X", "Social", false, ResolvedIdentity.X_TWITTER, ResolvedCategory.SOCIAL),
            "com.snapchat.android" to AppContext("Snapchat", "Social", false, ResolvedIdentity.SNAPCHAT, ResolvedCategory.SOCIAL),
            "com.reddit.frontpage" to AppContext("Reddit", "Social", false, ResolvedIdentity.REDDIT, ResolvedCategory.SOCIAL),
            "com.discord" to AppContext("Discord", "Social", false, ResolvedIdentity.DISCORD, ResolvedCategory.CHAT_MESSAGING),
            "com.pinterest" to AppContext("Pinterest", "Social", false, ResolvedIdentity.PINTEREST, ResolvedCategory.SOCIAL),
            "org.thoughtcrime.securesms" to AppContext("Signal", "Social", false, ResolvedIdentity.SIGNAL, ResolvedCategory.CHAT_MESSAGING),
            "com.linkedin.android" to AppContext("LinkedIn", "Social", false, ResolvedIdentity.LINKEDIN, ResolvedCategory.SOCIAL),
            "com.instagram.barcelona" to AppContext("Threads", "Social", false, ResolvedIdentity.THREADS, ResolvedCategory.SOCIAL),

            // Work & Productivity Applications
            "com.github.android" to AppContext("GitHub", "Work", false, ResolvedIdentity.GITHUB, ResolvedCategory.PRODUCTIVITY),
            "com.google.android.gm" to AppContext("Gmail", "Work", false, ResolvedIdentity.GMAIL, ResolvedCategory.PRODUCTIVITY),
            "com.microsoft.office.outlook" to AppContext("Outlook", "Work", false, ResolvedIdentity.OUTLOOK, ResolvedCategory.PRODUCTIVITY),
            "com.Slack" to AppContext("Slack", "Work", false, ResolvedIdentity.SLACK, ResolvedCategory.CHAT_MESSAGING),
            "com.slack" to AppContext("Slack", "Work", false, ResolvedIdentity.SLACK, ResolvedCategory.CHAT_MESSAGING),
            "com.google.android.apps.docs" to AppContext("Docs", "Work", false, ResolvedIdentity.DOCS, ResolvedCategory.PRODUCTIVITY),
            "com.google.android.apps.docs.editors.docs" to AppContext("Docs", "Work", false, ResolvedIdentity.DOCS, ResolvedCategory.PRODUCTIVITY),
            "com.google.android.apps.docs.editors.sheets" to AppContext("Sheets", "Work", false, ResolvedIdentity.SHEETS, ResolvedCategory.PRODUCTIVITY),
            "com.google.android.apps.docs.editors.slides" to AppContext("Slides", "Work", false, ResolvedIdentity.SLIDES, ResolvedCategory.PRODUCTIVITY),
            "com.google.android.keep" to AppContext("Keep", "Work", false, ResolvedIdentity.KEEP, ResolvedCategory.PRODUCTIVITY),
            "com.microsoft.teams" to AppContext("Teams", "Work", false, ResolvedIdentity.TEAMS, ResolvedCategory.CHAT_MESSAGING),
            "notion.id" to AppContext("Notion", "Work", false, ResolvedIdentity.NOTION, ResolvedCategory.PRODUCTIVITY),
            "com.trello" to AppContext("Trello", "Work", false, ResolvedIdentity.TRELLO, ResolvedCategory.PRODUCTIVITY),
            "com.asana.app" to AppContext("Asana", "Work", false, ResolvedIdentity.ASANA, ResolvedCategory.PRODUCTIVITY),
            "us.zoom.videomeetings" to AppContext("Zoom", "Work", false, ResolvedIdentity.ZOOM, ResolvedCategory.PRODUCTIVITY),

            // Other Specific Brand Applications
            "com.sportybet.android.gp" to AppContext("SportyBet", "Other", false, ResolvedIdentity.SPORTYBET, ResolvedCategory.OTHER),
            "com.sportybet.android.gh" to AppContext("SportyBet", "Other", false, ResolvedIdentity.SPORTYBET, ResolvedCategory.OTHER),
            "com.sportybet.android.ng" to AppContext("SportyBet", "Other", false, ResolvedIdentity.SPORTYBET, ResolvedCategory.OTHER),
            "com.sportybet.gp" to AppContext("SportyBet", "Other", false, ResolvedIdentity.SPORTYBET, ResolvedCategory.OTHER),
            "com.sportybet.gh" to AppContext("SportyBet", "Other", false, ResolvedIdentity.SPORTYBET, ResolvedCategory.OTHER),
            "com.sportybet" to AppContext("SportyBet", "Other", false, ResolvedIdentity.SPORTYBET, ResolvedCategory.OTHER)
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

    // In-Memory Session Cache keyed by (packageName + urlOrDomain + windowTitle)
    private class SimpleLruCache<K, V>(private val maxSize: Int) {
        private val map = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
                return size > maxSize
            }
        }

        @Synchronized
        fun get(key: K): V? = map[key]

        @Synchronized
        fun put(key: K, value: V) {
            map[key] = value
        }

        @Synchronized
        fun clear() {
            map.clear()
        }
    }

    private val appCache = SimpleLruCache<String, AppContext>(cacheSize)

    /**
     * Resolves an AppContext by collecting current AppEvidence from Android system state.
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

        val evidence = collectEvidence(context, packageName, windowInfo, rootNode, className)
        return resolveWithEvidence(evidence)
    }

    /**
     * Collects all available accessibility and system evidence into an AppEvidence instance.
     */
    fun collectEvidence(
        context: Context,
        packageName: String,
        windowInfo: AccessibilityWindowInfo? = null,
        rootNode: AccessibilityNodeInfo? = null,
        className: String? = null
    ): AppEvidence {
        val a11y = com.example.service.VoxStreamAccessibilityService.instance
        val effectiveWin = windowInfo ?: a11y?.getActiveApplicationWindow()
        val effectiveRoot = rootNode ?: a11y?.rootInActiveWindow ?: effectiveWin?.root

        val winTitle = effectiveWin?.title?.toString()
            ?: try {
                a11y?.getWindows()?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.title.isNullOrBlank() }?.title?.toString()
            } catch (_: Throwable) { null }

        val (nodeTexts, contentDescs) = collectNodeStrings(effectiveRoot)
        val urlOrDomain = findBrowserUrl(effectiveRoot)
        val webApkMetaName = getWebApkMetaName(context, packageName)
        val appLabel = getAppLabel(context, packageName)

        return AppEvidence(
            packageName = packageName,
            className = className,
            windowTitle = winTitle,
            visibleNodeTexts = nodeTexts,
            contentDescriptions = contentDescs,
            urlOrDomain = urlOrDomain,
            webApkMetaName = webApkMetaName,
            appLabel = appLabel
        )
    }

    /**
     * Evaluates AppEvidence deterministically to resolve the application identity and category.
     */
    fun resolveWithEvidence(evidence: AppEvidence): AppContext? {
        val pkg = evidence.packageName ?: return null
        if (isSystemOrIme(null, pkg)) return null

        val pkgLower = pkg.lowercase()

        // Session Cache Key: package + domain + title prevents stale identity leakage across session switches
        val cacheKey = "$pkg|${evidence.urlOrDomain ?: ""}|${evidence.windowTitle ?: ""}"
        val cached = appCache.get(cacheKey)
        if (cached != null) return cached

        // ----------------------------------------------------
        // PRIORITY 1: Exact Known Native Package Mapping
        // ----------------------------------------------------
        NATIVE_APP_MAP[pkg]?.let {
            appCache.put(cacheKey, it)
            return it
        }

        // ----------------------------------------------------
        // PRIORITY 2: QuickSearchBox Gemini Resolution
        // ----------------------------------------------------
        if (pkg.equals("com.google.android.googlequicksearchbox", ignoreCase = true)) {
            val isGemini = evaluateGeminiEvidence(evidence)
            val result = if (isGemini) {
                AppContext("Gemini", "AI", true, ResolvedIdentity.GEMINI, ResolvedCategory.AI_ASSISTANT)
            } else {
                AppContext("Google", "Other", false, ResolvedIdentity.GOOGLE_SEARCH, ResolvedCategory.OTHER)
            }
            appCache.put(cacheKey, result)
            return result
        }

        // ----------------------------------------------------
        // PRIORITY 3: Hosted Application Resolution (Chrome, Browsers, WebAPKs)
        // ----------------------------------------------------
        if (isBrowserOrPwa(pkgLower)) {
            val hostedApp = evaluateHostedAppEvidence(evidence)
            if (hostedApp != null) {
                appCache.put(cacheKey, hostedApp)
                return hostedApp
            }
            // Generic Browser Fallback
            val browserLabel = evidence.appLabel ?: "Chrome"
            val fallback = AppContext(browserLabel, "Other", false, ResolvedIdentity.CHROME, ResolvedCategory.BROWSER)
            appCache.put(cacheKey, fallback)
            return fallback
        }

        // ----------------------------------------------------
        // PRIORITY 4: Dynamic Category Classification for Unknown Apps
        // ----------------------------------------------------
        val unknownResult = evaluateUnknownAppCategory(evidence)
        appCache.put(cacheKey, unknownResult)
        return unknownResult
    }

    /**
     * Evaluates Gemini evidence inside QuickSearchBox.
     * Prevents false positives by requiring explicit Gemini text/title or Robin activity signals.
     */
    private fun evaluateGeminiEvidence(evidence: AppEvidence): Boolean {
        var score = 0

        val titleLower = (evidence.windowTitle ?: "").lowercase()
        val classLower = (evidence.className ?: "").lowercase()
        val allNodeText = (evidence.visibleNodeTexts + evidence.contentDescriptions).joinToString(" ").lowercase()

        // Very Strong Signals (+10)
        if (titleLower.contains("gemini") || titleLower.contains("ask gemini") || titleLower.contains("chat with gemini") || titleLower.contains("bard")) {
            score += 10
        }
        if (allNodeText.contains("ask gemini") || allNodeText.contains("chat with gemini") || allNodeText.contains("gemini advanced") || allNodeText.contains("ask anything with gemini")) {
            score += 10
        }

        // Strong Signals (+5)
        if (allNodeText.contains("gemini") || allNodeText.contains("bard")) {
            score += 5
        }
        if (classLower.contains("robinactivity") || classLower.contains("bard") || classLower.contains("geminiactivity")) {
            score += 5
        }

        // Weak Signals (+1) - Generic class names such as "Search" or "Assistant" alone DO NOT yield Gemini without supporting text
        if (classLower.contains("assistant") || classLower.contains("search")) {
            score += 1
        }

        // Threshold: Must have at least one strong Gemini signal (score >= 5)
        return score >= 5
    }

    /**
     * Evaluates hosted application identity inside browsers/WebAPKs (e.g. Google AI Studio, ChatGPT, Claude, Grok).
     */
    private fun evaluateHostedAppEvidence(evidence: AppEvidence): AppContext? {
        val domainLower = (evidence.urlOrDomain ?: "").lowercase()
        val titleLower = (evidence.windowTitle ?: "").lowercase()
        val metaLower = (evidence.webApkMetaName ?: "").lowercase()
        val allNodeText = (evidence.visibleNodeTexts + evidence.contentDescriptions).joinToString(" ").lowercase()

        // 1. Google AI Studio Hosted App Detection
        var aiStudioScore = 0
        if (domainLower.contains("aistudio.google.com")) aiStudioScore += 10
        if (domainLower.contains("aistudio") || domainLower.contains("ais-dev-") || domainLower.contains("ais-pre-")) aiStudioScore += 8
        if (titleLower.contains("google ai studio") || titleLower.contains("ai studio")) aiStudioScore += 10
        if (metaLower.contains("google ai studio") || metaLower.contains("ai studio")) aiStudioScore += 10

        // Developer UI phrases count as strong signals (+5 each)
        val devUiPhrases = listOf("system instructions", "prompt gallery", "get api key", "temperature", "top p", "safety settings", "create prompt")
        val devUiMatchCount = devUiPhrases.count { allNodeText.contains(it) }
        if (devUiMatchCount >= 2) {
            aiStudioScore += 10
        } else if (devUiMatchCount == 1) {
            aiStudioScore += 5
        }

        // Weak single word "AI Studio" in body text without domain/title or dev UI gives only +2 (prevents article false positives)
        if (allNodeText.contains("google ai studio") || allNodeText.contains("ai studio")) {
            aiStudioScore += 2
        }

        if (aiStudioScore >= 8) {
            return AppContext("Google AI Studio", "AI", true, ResolvedIdentity.GOOGLE_AI_STUDIO, ResolvedCategory.AI_DEVELOPER_TOOL)
        }

        // 2. ChatGPT Hosted Web App
        if (domainLower.contains("chatgpt.com") || domainLower.contains("chat.openai.com") || titleLower.contains("chatgpt")) {
            return AppContext("ChatGPT", "AI", true, ResolvedIdentity.CHATGPT, ResolvedCategory.AI_ASSISTANT)
        }

        // 3. Claude Hosted Web App
        if (domainLower.contains("claude.ai") || titleLower.contains("claude")) {
            return AppContext("Claude", "AI", true, ResolvedIdentity.CLAUDE, ResolvedCategory.AI_ASSISTANT)
        }

        // 4. Grok Hosted Web App
        if (domainLower.contains("grok.com") || domainLower.contains("x.com/i/grok") || titleLower.contains("grok")) {
            return AppContext("Grok", "AI", true, ResolvedIdentity.GROK, ResolvedCategory.AI_ASSISTANT)
        }

        // 5. Perplexity Hosted Web App
        if (domainLower.contains("perplexity.ai") || titleLower.contains("perplexity")) {
            return AppContext("Perplexity", "AI", true, ResolvedIdentity.PERPLEXITY, ResolvedCategory.AI_ASSISTANT)
        }

        // 6. DeepSeek Hosted Web App
        if (domainLower.contains("deepseek.com") || titleLower.contains("deepseek")) {
            return AppContext("DeepSeek", "AI", true, ResolvedIdentity.DEEPSEEK, ResolvedCategory.AI_ASSISTANT)
        }

        // 7. v0 Hosted Web App
        if (domainLower.contains("v0.dev") || titleLower.contains("v0.dev")) {
            return AppContext("v0", "AI", true, ResolvedIdentity.V0, ResolvedCategory.AI_DEVELOPER_TOOL)
        }

        // 8. Other Popular Hosted Web Apps
        if (domainLower.contains("web.whatsapp.com")) return AppContext("WhatsApp", "Social", false, ResolvedIdentity.WHATSAPP, ResolvedCategory.CHAT_MESSAGING)
        if (domainLower.contains("web.telegram.org")) return AppContext("Telegram", "Social", false, ResolvedIdentity.TELEGRAM, ResolvedCategory.CHAT_MESSAGING)
        if (domainLower.contains("github.com")) return AppContext("GitHub", "Work", false, ResolvedIdentity.GITHUB, ResolvedCategory.PRODUCTIVITY)
        if (domainLower.contains("notion.so")) return AppContext("Notion", "Work", false, ResolvedIdentity.NOTION, ResolvedCategory.PRODUCTIVITY)
        if (domainLower.contains("figma.com")) return AppContext("Figma", "Work", false, ResolvedIdentity.FIGMA, ResolvedCategory.PRODUCTIVITY)
        if (domainLower.contains("canva.com")) return AppContext("Canva", "Work", false, ResolvedIdentity.CANVA, ResolvedCategory.PRODUCTIVITY)

        return null
    }

    /**
     * Evaluates category classification for unknown/unmapped applications based on multiple signals.
     */
    private fun evaluateUnknownAppCategory(evidence: AppEvidence): AppContext {
        val allNodeText = (evidence.visibleNodeTexts + evidence.contentDescriptions).joinToString(" ").lowercase()
        val titleLower = (evidence.windowTitle ?: "").lowercase()
        val labelLower = (evidence.appLabel ?: "").lowercase()

        val strongAiPhrases = listOf("new chat", "ask anything", "send a message", "regenerate response", "select model", "conversation history")
        val strongAiMatchCount = strongAiPhrases.count { allNodeText.contains(it) || titleLower.contains(it) }

        val weakAiKeywords = listOf("ai", "chat", "assistant", "bot", "gpt", "api")
        val weakAiMatchCount = weakAiKeywords.count { allNodeText.contains(it) || titleLower.contains(it) }

        // Require at least 2 strong conversational phrases OR 1 strong phrase + multiple weak keywords to classify as AI
        if (strongAiMatchCount >= 2 || (strongAiMatchCount >= 1 && weakAiMatchCount >= 2)) {
            return AppContext("AI Assistant", "AI", true, ResolvedIdentity.UNKNOWN, ResolvedCategory.AI_ASSISTANT)
        }

        // Social / Chat signals
        if (labelLower.contains("chat") || labelLower.contains("message") || allNodeText.contains("type a message")) {
            return AppContext(evidence.appLabel ?: "Messaging", "Social", false, ResolvedIdentity.UNKNOWN, ResolvedCategory.CHAT_MESSAGING)
        }

        // Productivity signals
        if (labelLower.contains("note") || labelLower.contains("doc") || labelLower.contains("office")) {
            return AppContext(evidence.appLabel ?: "Productivity", "Work", false, ResolvedIdentity.UNKNOWN, ResolvedCategory.PRODUCTIVITY)
        }

        val brandName = evidence.appLabel ?: tokenizeBrand(evidence.packageName ?: "App")
        return AppContext(brandName, "Other", false, ResolvedIdentity.UNKNOWN, ResolvedCategory.OTHER)
    }

    private fun collectNodeStrings(rootNode: AccessibilityNodeInfo?): Pair<List<String>, List<String>> {
        if (rootNode == null) return Pair(emptyList(), emptyList())
        val texts = mutableListOf<String>()
        val descs = mutableListOf<String>()
        traverseNodes(rootNode, texts, descs, 0, 10)
        return Pair(texts, descs)
    }

    private fun traverseNodes(node: AccessibilityNodeInfo?, texts: MutableList<String>, descs: MutableList<String>, depth: Int, maxDepth: Int) {
        if (node == null || depth > maxDepth) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrBlank()) texts.add(text)
        if (!desc.isNullOrBlank()) descs.add(desc)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNodes(child, texts, descs, depth + 1, maxDepth)
        }
    }

    private fun findBrowserUrl(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
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
        return searchNodeForUrl(rootNode, 0, 6)
    }

    private fun searchNodeForUrl(node: AccessibilityNodeInfo?, depth: Int, maxDepth: Int): String? {
        if (node == null || depth > maxDepth) return null
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotBlank()) {
            val lower = text.lowercase()
            if (lower.contains("aistudio") || lower.contains("ais-dev-") || lower.contains("ais-pre-") || lower.contains(".google.com") || lower.contains(".ai") || lower.contains(".com/") || lower.startsWith("http")) {
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

    private fun getWebApkMetaName(context: Context, packageName: String): String? {
        if (!packageName.startsWith("org.chromium.webapk") && !packageName.contains(".webapk")) return null
        return try {
            val pm = context.packageManager
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
            meta?.getString("org.chromium.webapk.shell_apk.name")
                ?: meta?.getString("org.chromium.webapk.shell_apk.shortName")
        } catch (_: Throwable) { null }
    }

    private fun getAppLabel(context: Context, packageName: String): String? {
        return try {
            val pm = context.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            val label = pm.getApplicationLabel(appInfo).toString()
            if (label.isNotBlank() && !label.contains(".")) label else null
        } catch (_: Throwable) { null }
    }

    fun tokenizeBrand(packageName: String): String {
        val tokens = packageName.split(".").filter { it.isNotBlank() }
        val best = tokens.lastOrNull() ?: "App"
        return best.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun isBrowserOrPwa(pkgLower: String): Boolean {
        return pkgLower.startsWith("org.chromium.webapk") ||
                pkgLower.contains(".webapk") ||
                pkgLower in BROWSER_PACKAGES
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

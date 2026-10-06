package com.example.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.service.LearnedAppRegistry

/**
 * AppResolutionEngine: Multi-Signal Evidence-Based Resolver for VoxStream.
 *
 * Implements Stage A (synchronous local name + evidence extraction) and
 * Stage B (local category lookup via static maps, learned cache, and heuristics).
 */
class AppResolutionEngine(cacheSize: Int = 150) {

    data class AppEvidence(
        val packageName: String,
        val appLabel: String? = null,
        val webApkMetaName: String? = null,
        val webApkScopeUrl: String? = null,
        val windowTitle: String? = null,
        val className: String? = null,
        val urlOrDomain: String? = null,
        val domain: String? = null,
        val visibleNodeTexts: List<String> = emptyList(),
        val contentDescriptions: List<String> = emptyList(),
        val localDisplayName: String = "",
        val learnedRegistryKey: String = packageName
    ) {
        fun buildEvidencePromptString(): String = buildString {
            append("Android Package: ").append(packageName)
            append("\nLocal Display Name: ").append(localDisplayName)
            if (!webApkMetaName.isNullOrBlank()) {
                append("\nWebAPK Name: ").append(webApkMetaName)
            }
            if (!webApkScopeUrl.isNullOrBlank()) {
                append("\nWebAPK Scope: ").append(webApkScopeUrl)
            }
            if (!windowTitle.isNullOrBlank()) {
                append("\nWindow Title: ").append(windowTitle)
            }
            if (!className.isNullOrBlank()) {
                append("\nActivity: ").append(className)
            }
            if (!urlOrDomain.isNullOrBlank()) {
                append("\nURL: ").append(urlOrDomain)
            }
            if (!domain.isNullOrBlank()) {
                append("\nDomain: ").append(domain)
            }
            if (visibleNodeTexts.isNotEmpty() || contentDescriptions.isNotEmpty()) {
                append("\nVisible UI Samples:\n")
                val combined = (visibleNodeTexts + contentDescriptions).distinct().take(10)
                combined.forEach { sample ->
                    append("- ").append(sample.take(100)).append("\n")
                }
            }
        }
    }

    enum class ResolvedIdentity {
        GEMINI,
        CHATGPT,
        CLAUDE,
        GROK,
        DEEPSEEK,
        PERPLEXITY,
        COPILOT,
        QWEN,
        KIMI,
        FLOW,
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

    enum class ResolvedCategory {
        AI_ASSISTANT,
        AI_DEVELOPER_TOOL,
        CHAT_MESSAGING,
        BROWSER,
        PRODUCTIVITY,
        SOCIAL,
        OTHER
    }

    data class AppContext(
        val name: String,
        val category: String,
        val isAiApp: Boolean,
        val identity: ResolvedIdentity = ResolvedIdentity.UNKNOWN,
        val resolvedCategory: ResolvedCategory = ResolvedCategory.OTHER,
        val isLocallyResolved: Boolean = true
    ) {
        val formatted: String get() = "$category · $name"
    }

    companion object {
        private const val TAG = "AppResolutionEngine"

        val defaultInstance = AppResolutionEngine(150)

        // Native Package to Exact Identity Map (Stage B1)
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
            "com.moonshot.kimi" to AppContext("Kimi", "AI", true, ResolvedIdentity.KIMI, ResolvedCategory.AI_ASSISTANT),
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

    // In-Memory Session Cache
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

    fun clearCache() {
        appCache.clear()
    }

    /**
     * Resolves an AppContext synchronously by evaluating Stage A evidence and Stage B local category.
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
        return resolveWithEvidence(evidence, context)
    }

    /**
     * Evaluates AppEvidence deterministically into an AppContext.
     */
    fun resolveWithEvidence(evidence: AppEvidence, context: Context? = null): AppContext? {
        val pkg = evidence.packageName
        if (isSystemOrIme(context, pkg)) return null

        val resolvedEvidence = if (evidence.localDisplayName.isBlank()) {
            val name = computeLocalDisplayName(
                packageName = evidence.packageName,
                appLabel = evidence.appLabel,
                webApkMetaName = evidence.webApkMetaName,
                urlOrDomain = evidence.urlOrDomain,
                domain = evidence.domain ?: extractDomain(evidence.urlOrDomain),
                windowTitle = evidence.windowTitle
            )
            val isBrowser = isBrowserOrPwa(evidence.packageName.lowercase())
            val domain = evidence.domain ?: extractDomain(evidence.urlOrDomain)
            val learnedKey = if (isBrowser && !domain.isNullOrBlank()) {
                "${evidence.packageName}|$domain"
            } else {
                evidence.packageName
            }
            evidence.copy(localDisplayName = name, domain = domain, learnedRegistryKey = learnedKey)
        } else {
            evidence
        }

        val local = resolveLocalCategory(context, resolvedEvidence)
        if (local != null) return local

        val isAi = evaluateHeuristics(resolvedEvidence) != null
        val fallbackIdentity = if (isBrowserOrPwa(resolvedEvidence.packageName.lowercase())) ResolvedIdentity.CHROME else ResolvedIdentity.UNKNOWN
        val fallbackCat = when {
            isAi -> ResolvedCategory.AI_ASSISTANT
            isBrowserOrPwa(resolvedEvidence.packageName.lowercase()) -> ResolvedCategory.BROWSER
            else -> ResolvedCategory.OTHER
        }

        return AppContext(
            name = resolvedEvidence.localDisplayName,
            category = if (isAi) "AI" else "Other",
            isAiApp = isAi,
            identity = fallbackIdentity,
            resolvedCategory = fallbackCat,
            isLocallyResolved = true
        )
    }

    /**
     * STAGE A — Local name + evidence collection (Synchronous, zero network).
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
        val domain = extractDomain(urlOrDomain)
        val webApkMetaName = getWebApkMetaName(context, packageName)
        val webApkScopeUrl = getWebApkScopeUrl(context, packageName)
        val appLabel = getAppLabel(context, packageName)
        val rootNodeText = (nodeTexts + contentDescs).joinToString(" ")

        val localDisplayName = computeLocalDisplayName(
            packageName = packageName,
            appLabel = appLabel,
            webApkMetaName = webApkMetaName,
            urlOrDomain = urlOrDomain,
            domain = domain,
            windowTitle = winTitle,
            rootNodeText = rootNodeText
        )

        val isBrowser = isBrowserOrPwa(packageName.lowercase())
        val learnedKey = if (isBrowser && !domain.isNullOrBlank()) {
            "$packageName|$domain"
        } else if (isBrowser && !urlOrDomain.isNullOrBlank()) {
            "$packageName|${urlOrDomain.hashCode()}"
        } else {
            packageName
        }

        return AppEvidence(
            packageName = packageName,
            appLabel = appLabel,
            webApkMetaName = webApkMetaName,
            webApkScopeUrl = webApkScopeUrl,
            windowTitle = winTitle,
            className = className ?: a11y?.lastSeenClassName,
            urlOrDomain = urlOrDomain,
            domain = domain,
            visibleNodeTexts = nodeTexts,
            contentDescriptions = contentDescs,
            localDisplayName = localDisplayName,
            learnedRegistryKey = learnedKey
        )
    }

    /**
     * Computes the display name locally based on priority:
     * 1. Gemini special check (quicksearchbox / bard with Gemini title or on-screen content)
     * 2. WebAPK user-facing label from PackageManager / metadata (e.g. AI Studio, Pinterest)
     * 3. Browser site product name from domain/title
     * 4. Window title first segment for web tabs
     * 5. Standard PackageManager label
     * 6. Tokenized package fallback
     */
    fun computeLocalDisplayName(
        packageName: String,
        appLabel: String?,
        webApkMetaName: String?,
        urlOrDomain: String?,
        domain: String?,
        windowTitle: String?,
        rootNodeText: String? = null
    ): String {
        val pkgLower = packageName.lowercase()
        // Priority 1: WebAPK Check (e.g. org.chromium.webapk.* or in webApkInventory)
        if (pkgLower.startsWith("org.chromium.webapk") || pkgLower.contains(".webapk") || com.example.service.AppDetector.webApkInventory.containsKey(packageName)) {
            val registeredLabel = com.example.service.AppDetector.webApkInventory[packageName]?.label
            if (!registeredLabel.isNullOrBlank()) {
                return registeredLabel
            }
            if (!appLabel.isNullOrBlank()) {
                val cleanLabel = appLabel.trim()
                if (!cleanLabel.startsWith("org.chromium", ignoreCase = true) && !cleanLabel.equals("Chrome", ignoreCase = true)) {
                    return cleanLabel
                }
            }
            if (!webApkMetaName.isNullOrBlank()) {
                val cleanMeta = webApkMetaName.trim()
                if (!cleanMeta.startsWith("org.chromium", ignoreCase = true) && !cleanMeta.equals("Chrome", ignoreCase = true)) {
                    return cleanMeta
                }
            }
        }

        // Priority 2: Gemini Special Check (QuickSearchBox / Bard)
        if (pkgLower == "com.google.android.googlequicksearchbox" || pkgLower == "com.google.android.apps.bard" || pkgLower == "com.google.android.apps.gemini") {
            val titleLower = (windowTitle ?: "").lowercase()
            val textLower = (rootNodeText ?: "").lowercase()
            if (titleLower.contains("gemini") || textLower.contains("gemini") || textLower.contains("ask gemini") || textLower.contains("chat with gemini") || textLower.contains("bard")) {
                return "Gemini"
            }
            if (pkgLower == "com.google.android.apps.gemini" || pkgLower == "com.google.android.apps.bard") {
                return "Gemini"
            }
            return "Google"
        }

        // Priority 3: General Installed Native Applications (Non-browser apps return PackageManager label first)
        val isBrowserPackage = isBrowserOrPwa(pkgLower)
        if (!isBrowserPackage && !appLabel.isNullOrBlank()) {
            val labelTrimmed = appLabel.trim()
            if (labelTrimmed.isNotBlank()) {
                return labelTrimmed
            }
        }

        // Priority 4: Browser site product name if recognizable from domain/title
        val domainLower = (domain ?: urlOrDomain ?: "").lowercase()
        val titleLower = (windowTitle ?: "").lowercase()

        when {
            domainLower.contains("aistudio.google.com") || domainLower.contains("aistudio") || titleLower.contains("google ai studio") || titleLower.contains("ai studio") -> return "Google AI Studio"
            domainLower.contains("chatgpt.com") || domainLower.contains("chat.openai.com") || titleLower.contains("chatgpt") -> return "ChatGPT"
            domainLower.contains("claude.ai") || titleLower.contains("claude") -> return "Claude"
            domainLower.contains("grok.com") || domainLower.contains("x.com/i/grok") || titleLower.contains("grok") -> return "Grok"
            domainLower.contains("perplexity.ai") || titleLower.contains("perplexity") -> return "Perplexity"
            domainLower.contains("deepseek.com") || titleLower.contains("deepseek") -> return "DeepSeek"
            domainLower.contains("v0.dev") || titleLower.contains("v0.dev") -> return "v0"
            domainLower.contains("sportybet.com") || titleLower.contains("sportybet") -> return "SportyBet"
            domainLower.contains("github.com") -> return "GitHub"
            domainLower.contains("notion.so") -> return "Notion"
            domainLower.contains("figma.com") -> return "Figma"
            domainLower.contains("canva.com") -> return "Canva"
            domainLower.contains("web.whatsapp.com") -> return "WhatsApp"
            domainLower.contains("web.telegram.org") -> return "Telegram"
        }

        // Priority 5: Browser fallback label
        if (isBrowserPackage) {
            return if (!appLabel.isNullOrBlank() && !appLabel.startsWith("org.chromium", ignoreCase = true)) {
                appLabel.trim()
            } else {
                "Chrome"
            }
        }

        // Priority 6: Tokenized brand from package
        return tokenizeBrand(packageName)
    }

    /**
     * STAGE B — Local category resolution (Zero network).
     * Returns AppContext if category is deterministically known, or null if Gemini is needed.
     */
    fun resolveLocalCategory(context: Context? = null, evidence: AppEvidence): AppContext? {
        val pkg = evidence.packageName
        if (isSystemOrIme(context, pkg)) return null

        val cacheKey = if (pkg.equals("com.google.android.googlequicksearchbox", ignoreCase = true)) {
            "$pkg|${evidence.windowTitle ?: ""}|${evidence.visibleNodeTexts.joinToString(",")}"
        } else if (isBrowserOrPwa(pkg.lowercase())) {
            "$pkg|${evidence.urlOrDomain ?: ""}|${evidence.windowTitle ?: ""}"
        } else {
            evidence.learnedRegistryKey
        }
        val cached = appCache.get(cacheKey)
        if (cached != null) return cached

        // 0. Check 4-Signal PWA Cascade
        if (context != null) {
            val pwaCascadeInfo = com.example.service.AppDetector.resolvePwaCascade(
                context = context,
                packageName = evidence.packageName,
                className = evidence.className,
                windowTitle = evidence.windowTitle,
                visibleTexts = evidence.visibleNodeTexts + evidence.contentDescriptions
            )
            if (pwaCascadeInfo != null) {
                val isAi = pwaCascadeInfo.category.equals("AI", ignoreCase = true)
                val result = AppContext(
                    name = pwaCascadeInfo.appName,
                    category = pwaCascadeInfo.category,
                    isAiApp = isAi,
                    isLocallyResolved = true
                )
                appCache.put(cacheKey, result)
                return result
            }
        }

        // 1. Check Exact Known Native Package Mapping
        NATIVE_APP_MAP[pkg]?.let { mapped ->
            appCache.put(cacheKey, mapped)
            return mapped
        }

        // 2. Check QuickSearchBox Gemini vs Google Search
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

        // 3. Check WebAPK & Hosted Web App definitions (AI Studio, ChatGPT, SportyBet, etc.)
        val hostedApp = evaluateHostedAppEvidence(evidence)
        if (hostedApp != null) {
            appCache.put(cacheKey, hostedApp)
            return hostedApp
        }

        // 4. Check Persisted Learned Registry (confirmed entries)
        if (context != null) {
            val learned = LearnedAppRegistry.get(context, cacheKey) ?: LearnedAppRegistry.get(context, evidence.localDisplayName)
            if (learned != null && learned.confirmCount >= LearnedAppRegistry.CONFIRM_THRESHOLD) {
                val isAi = learned.category.equals("AI", ignoreCase = true)
                // PRECEDENCE: learned > Gemini fresh > local label. Always preserve learned.appName!
                val resolvedName = if (learned.source == "local" && evidence.localDisplayName.isNotBlank()) {
                    evidence.localDisplayName
                } else {
                    learned.appName.ifBlank { evidence.localDisplayName }
                }
                val result = AppContext(
                    name = resolvedName,
                    category = learned.category,
                    isAiApp = isAi,
                    isLocallyResolved = true
                )
                appCache.put(cacheKey, result)
                Log.d(TAG, "Stage B: Learned registry hit for key=$cacheKey -> appName='${result.name}' (learned='${learned.appName}', local='${evidence.localDisplayName}'), category='${result.category}', confirmCount=${learned.confirmCount}")
                return result
            }
        }

        // 5. Instant Static Dictionary Check (Zero Latency)
        val classified = com.example.service.AppClassifier.classify(evidence.packageName, evidence.localDisplayName, context)
        if (classified != com.example.service.AppCategory.OTHER) {
            val group = classified.groupName
            val isAi = (classified == com.example.service.AppCategory.AI_CHAT)
            val result = AppContext(
                name = evidence.localDisplayName,
                category = group,
                isAiApp = isAi,
                isLocallyResolved = true
            )
            appCache.put(cacheKey, result)
            return result
        }

        // 6. Heuristic check on package / name (e.g. chatgpt, claude, grok, kimi, qwen, deepseek)
        val heuristic = evaluateHeuristics(evidence)
        if (heuristic != null) {
            appCache.put(cacheKey, heuristic)
            return heuristic
        }

        // Unknown category -> Return null to trigger Stage C (Gemini)
        return null
    }

    private fun evaluateGeminiEvidence(evidence: AppEvidence): Boolean {
        var score = 0
        val titleLower = (evidence.windowTitle ?: "").lowercase()
        val classLower = (evidence.className ?: "").lowercase()
        val allNodeText = (evidence.visibleNodeTexts + evidence.contentDescriptions).joinToString(" ").lowercase()

        if (titleLower.contains("gemini") || titleLower.contains("ask gemini") || titleLower.contains("chat with gemini") || titleLower.contains("bard")) {
            score += 10
        }
        if (allNodeText.contains("ask gemini") || allNodeText.contains("chat with gemini") || allNodeText.contains("gemini advanced") || allNodeText.contains("ask anything with gemini")) {
            score += 10
        }
        if (allNodeText.contains("gemini") || allNodeText.contains("bard")) {
            score += 5
        }
        if (classLower.contains("robinactivity") || classLower.contains("bard") || classLower.contains("geminiactivity")) {
            score += 5
        }
        return score >= 5
    }

    private fun evaluateHostedAppEvidence(evidence: AppEvidence): AppContext? {
        val domainLower = (evidence.domain ?: evidence.urlOrDomain ?: "").lowercase()
        val titleLower = (evidence.windowTitle ?: "").lowercase()
        val metaLower = (evidence.webApkMetaName ?: "").lowercase()
        val allNodeText = (evidence.visibleNodeTexts + evidence.contentDescriptions).joinToString(" ").lowercase()

        // 1. Google AI Studio Hosted / WebAPK
        var aiStudioScore = 0
        if (domainLower.contains("aistudio.google.com")) aiStudioScore += 10
        if (domainLower.contains("aistudio") || domainLower.contains("ais-dev-") || domainLower.contains("ais-pre-")) aiStudioScore += 8
        if (titleLower.contains("google ai studio") || titleLower.contains("ai studio")) aiStudioScore += 10
        if (metaLower.contains("google ai studio") || metaLower.contains("ai studio")) aiStudioScore += 10

        val devUiPhrases = listOf("system instructions", "prompt gallery", "get api key", "temperature", "top p", "safety settings", "create prompt")
        val devUiMatchCount = devUiPhrases.count { allNodeText.contains(it) }
        if (devUiMatchCount >= 2) aiStudioScore += 10 else if (devUiMatchCount == 1) aiStudioScore += 5

        if (aiStudioScore >= 8) {
            return AppContext("Google AI Studio", "AI", true, ResolvedIdentity.GOOGLE_AI_STUDIO, ResolvedCategory.AI_DEVELOPER_TOOL)
        }

        // 2. Known AI Web Apps
        if (domainLower.contains("chatgpt.com") || domainLower.contains("chat.openai.com") || titleLower.contains("chatgpt")) {
            return AppContext("ChatGPT", "AI", true, ResolvedIdentity.CHATGPT, ResolvedCategory.AI_ASSISTANT)
        }
        if (domainLower.contains("claude.ai") || titleLower.contains("claude")) {
            return AppContext("Claude", "AI", true, ResolvedIdentity.CLAUDE, ResolvedCategory.AI_ASSISTANT)
        }
        if (domainLower.contains("grok.com") || domainLower.contains("x.com/i/grok") || titleLower.contains("grok")) {
            return AppContext("Grok", "AI", true, ResolvedIdentity.GROK, ResolvedCategory.AI_ASSISTANT)
        }
        if (domainLower.contains("perplexity.ai") || titleLower.contains("perplexity")) {
            return AppContext("Perplexity", "AI", true, ResolvedIdentity.PERPLEXITY, ResolvedCategory.AI_ASSISTANT)
        }
        if (domainLower.contains("deepseek.com") || titleLower.contains("deepseek")) {
            return AppContext("DeepSeek", "AI", true, ResolvedIdentity.DEEPSEEK, ResolvedCategory.AI_ASSISTANT)
        }
        if (domainLower.contains("v0.dev") || titleLower.contains("v0.dev")) {
            return AppContext("v0", "AI", true, ResolvedIdentity.V0, ResolvedCategory.AI_DEVELOPER_TOOL)
        }

        // 3. Known Productivity & Social Web Apps
        if (domainLower.contains("web.whatsapp.com")) return AppContext("WhatsApp", "Social", false, ResolvedIdentity.WHATSAPP, ResolvedCategory.CHAT_MESSAGING)
        if (domainLower.contains("web.telegram.org")) return AppContext("Telegram", "Social", false, ResolvedIdentity.TELEGRAM, ResolvedCategory.CHAT_MESSAGING)
        if (domainLower.contains("github.com")) return AppContext("GitHub", "Work", false, ResolvedIdentity.GITHUB, ResolvedCategory.PRODUCTIVITY)
        if (domainLower.contains("notion.so")) return AppContext("Notion", "Work", false, ResolvedIdentity.NOTION, ResolvedCategory.PRODUCTIVITY)
        if (domainLower.contains("figma.com")) return AppContext("Figma", "Work", false, ResolvedIdentity.FIGMA, ResolvedCategory.PRODUCTIVITY)
        if (domainLower.contains("canva.com")) return AppContext("Canva", "Work", false, ResolvedIdentity.CANVA, ResolvedCategory.PRODUCTIVITY)

        // 4. Other Specific Brands
        if (domainLower.contains("sportybet.com") || titleLower.contains("sportybet")) {
            return AppContext("SportyBet", "Other", false, ResolvedIdentity.SPORTYBET, ResolvedCategory.OTHER)
        }

        return null
    }

    private fun evaluateHeuristics(evidence: AppEvidence): AppContext? {
        val pkgLower = evidence.packageName.lowercase()
        val nameLower = evidence.localDisplayName.lowercase()
        val allNodeText = (evidence.visibleNodeTexts + evidence.contentDescriptions).joinToString(" ").lowercase()
        val titleLower = (evidence.windowTitle ?: "").lowercase()

        val strongAiPhrases = listOf("new chat", "ask anything", "send a message", "regenerate response", "select model", "conversation history")
        val strongAiMatchCount = strongAiPhrases.count { allNodeText.contains(it) || titleLower.contains(it) }

        val weakAiKeywords = listOf("ai", "chat", "assistant", "bot", "gpt", "api", "chatgpt", "claude", "grok", "kimi", "qwen", "deepseek", "perplexity", "copilot", "character.ai", "midjourney")
        val weakAiMatchCount = weakAiKeywords.count { allNodeText.contains(it) || titleLower.contains(it) || pkgLower.contains(it) || nameLower.contains(it) }

        if (strongAiMatchCount >= 2 || (strongAiMatchCount >= 1 && weakAiMatchCount >= 2) || (pkgLower.contains("ai") && strongAiMatchCount >= 1)) {
            val displayName = if (evidence.localDisplayName.isBlank() || evidence.localDisplayName == "App") "AI Assistant" else evidence.localDisplayName
            return AppContext(displayName, "AI", true, ResolvedIdentity.UNKNOWN, ResolvedCategory.AI_ASSISTANT)
        }

        if (weakAiKeywords.any { pkgLower.contains(it) || nameLower.contains(it) }) {
            return AppContext(evidence.localDisplayName, "AI", true, ResolvedIdentity.UNKNOWN, ResolvedCategory.AI_ASSISTANT)
        }

        return null
    }

    private fun collectNodeStrings(rootNode: AccessibilityNodeInfo?): Pair<List<String>, List<String>> {
        if (rootNode == null) return Pair(emptyList(), emptyList())
        val texts = mutableListOf<String>()
        val descs = mutableListOf<String>()
        traverseNodes(rootNode, texts, descs, 0, 8)
        return Pair(texts, descs)
    }

    private fun traverseNodes(node: AccessibilityNodeInfo?, texts: MutableList<String>, descs: MutableList<String>, depth: Int, maxDepth: Int) {
        if (node == null || depth > maxDepth || texts.size >= 12) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrBlank() && text.length > 2 && !texts.contains(text) && text.length <= 120) {
            texts.add(text)
        }
        if (!desc.isNullOrBlank() && desc.length > 2 && !descs.contains(desc) && desc.length <= 120) {
            descs.add(desc)
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            traverseNodes(child, texts, descs, depth + 1, maxDepth)
        }
    }

    private fun findBrowserUrl(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
        val urlViewIds = listOf(
            "com.android.chrome:id/url_bar",
            "com.chrome.beta:id/url_bar",
            "com.chrome.dev:id/url_bar",
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
            if (lower.contains("aistudio") || lower.contains("ais-dev-") || lower.contains("ais-pre-") ||
                lower.contains(".google.com") || lower.contains(".ai") || lower.contains(".com/") ||
                lower.startsWith("http://") || lower.startsWith("https://")) {
                return text
            }
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            val found = searchNodeForUrl(child, depth + 1, maxDepth)
            if (found != null) return found
        }
        return null
    }

    fun extractDomain(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://$clean"
        }
        return try {
            val uri = java.net.URI(clean)
            val host = uri.host
            if (!host.isNullOrBlank()) {
                host.removePrefix("www.")
            } else null
        } catch (_: Throwable) {
            val noProto = clean.substringAfter("://").substringBefore("/").substringBefore("?").removePrefix("www.")
            if (noProto.isNotBlank()) noProto else null
        }
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

    private fun getWebApkScopeUrl(context: Context, packageName: String): String? {
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
            meta?.getString("org.chromium.webapk.shell_apk.scope_url")
                ?: meta?.getString("org.chromium.webapk.shell_apk.start_url")
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
            val label = pm.getApplicationLabel(appInfo).toString().trim()
            if (label.isNotBlank()) label else null
        } catch (_: Throwable) { null }
    }

    fun tokenizeBrand(packageName: String): String {
        val tokens = packageName.split(".").filter { it.isNotBlank() }
        val best = tokens.lastOrNull() ?: "App"
        return best.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    fun isBrowserOrPwa(pkgLower: String): Boolean {
        return pkgLower.startsWith("org.chromium.webapk") ||
                pkgLower.contains(".webapk") ||
                pkgLower in BROWSER_PACKAGES ||
                pkgLower.contains("chrome") ||
                pkgLower.contains("browser") ||
                pkgLower.contains("firefox") ||
                pkgLower.contains("webkit")
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

package com.example.service

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.example.config.VoxStreamConfig
import com.example.util.AppResolutionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class AppInfo(
    val appName: String,
    val category: String,
    val isLocallyResolved: Boolean = false
)

data class WebApkInfo(
    val packageName: String,
    val label: String,
    val startUrl: String? = null,
    val lastUsed: Long = 0L
)

data class CachedPwaSession(
    val name: String,
    val category: String,
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis()
)

class GeminiApiException(val statusCode: Int, message: String) : IOException(message)

object AppDetector {
    private const val TAG = "AppDetector"
    private const val PRIMARY_MODEL = "gemini-3.5-flash-lite"
    private const val FALLBACK_MODEL = "gemini-3.8-flash"
    private const val STICKY_TTL_MS = 60_000L

    val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        "com.sec.android.app.sbrowser",
        "com.microsoft.emmx",
        "com.brave.browser",
        "org.mozilla.firefox",
        "com.opera.browser",
        "com.vivaldi.browser"
    )

    // WebAPK Inventory
    val webApkInventory = ConcurrentHashMap<String, WebApkInfo>()

    // Sticky PWA Cache
    val stickyPwaCache = ConcurrentHashMap<String, CachedPwaSession>()

    // In-Memory Session Cache (keyed by learnedRegistryKey)
    private val sessionCache = mutableMapOf<String, AppInfo>()

    // Singleton concurrency state lock
    private var activeJob: Job? = null
    private var currentDetectingKey: String? = null

    fun init(context: Context) {
        refreshWebApkInventory(context)
    }

    fun refreshWebApkInventory(context: Context) {
        try {
            val pm = context.packageManager
            val installed = try {
                pm.getInstalledPackages(PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                emptyList()
            }
            for (pkgInfo in installed) {
                val pkgName = pkgInfo.packageName
                if (pkgName.startsWith("org.chromium.webapk") || pkgName.contains(".webapk")) {
                    val appInfo = pkgInfo.applicationInfo
                    val label = appInfo?.loadLabel(pm)?.toString()?.trim() ?: pkgName
                    val startUrl = appInfo?.metaData?.getString("org.chromium.webapk.shell_apk.startUrl")
                    if (label.isNotBlank() && !label.startsWith("org.chromium", ignoreCase = true)) {
                        webApkInventory[pkgName] = WebApkInfo(pkgName, label, startUrl)
                        Log.d(TAG, "Indexed WebAPK: $pkgName -> '$label' (startUrl=$startUrl)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error refreshing WebAPK inventory: ${e.message}")
        }
    }

    fun isPlaceholderKey(key: String?): Boolean {
        return com.example.core.ApiConfig.isPlaceholder(key)
    }

    fun resolveApiKey(context: Context? = null): String {
        return com.example.core.ApiConfig.resolveApiKey(context)
    }

    fun mapCategoryForName(name: String): String {
        val nameLower = name.lowercase()
        return when {
            nameLower.contains("google ai studio") || nameLower.contains("ai studio") ||
            nameLower.contains("brain ai") || nameLower.contains("kimi") ||
            nameLower.contains("chatgpt") || nameLower.contains("claude") ||
            nameLower.contains("gemini") || nameLower.contains("perplexity") ||
            nameLower.contains("grok") || nameLower.contains("deepseek") ||
            nameLower.contains("v0") || nameLower.contains("notebooklm") -> "AI"

            nameLower.contains("pinterest") || nameLower.contains("twitter") ||
            nameLower.contains("x") || nameLower.contains("instagram") ||
            nameLower.contains("tiktok") || nameLower.contains("threads") ||
            nameLower.contains("facebook") || nameLower.contains("snapchat") ||
            nameLower.contains("reddit") || nameLower.contains("discord") ||
            nameLower.contains("telegram") || nameLower.contains("whatsapp") -> "Social"

            nameLower.contains("gmail") || nameLower.contains("outlook") ||
            nameLower.contains("mail") -> "Email"

            nameLower.contains("google keep") || nameLower.contains("keep notes") ||
            nameLower.contains("keep") || nameLower.contains("notion") ||
            nameLower.contains("obsidian") || nameLower.contains("docs") -> "Notes"

            else -> "Web App"
        }
    }

    /**
     * 4-Signal WebAPK Detection Cascade
     */
    fun resolvePwaCascade(
        context: Context,
        packageName: String,
        className: String? = null,
        windowTitle: String? = null,
        visibleTexts: List<String> = emptyList()
    ): AppInfo? {
        val pkgLower = packageName.lowercase()

        // 1. Special check for Gemini running inside Google QuickSearchBox or Bard:
        if (pkgLower == "com.google.android.googlequicksearchbox" || pkgLower == "com.google.android.apps.bard") {
            val titleStr = windowTitle ?: ""
            val classStr = className ?: ""

            val isGeminiSurface = titleStr.contains("Gemini", ignoreCase = true) ||
                                  classStr.contains("gemini", ignoreCase = true) ||
                                  classStr.contains("bard", ignoreCase = true) ||
                                  visibleTexts.any { it.contains("gemini", ignoreCase = true) || it.contains("bard", ignoreCase = true) }

            if (isGeminiSurface || pkgLower == "com.google.android.apps.bard") {
                Log.d(TAG, "[PWA Cascade] Special Gemini Match: $packageName -> Gemini (AI)")
                return AppInfo(appName = "Gemini", category = "AI", isLocallyResolved = true)
            }
        }

        val isBrowserPkg = BROWSER_PACKAGES.contains(pkgLower)
        val isWebApkPkg = pkgLower.startsWith("org.chromium.webapk") || pkgLower.contains(".webapk")

        // Native App Fast-Path: If packageName is NOT browser/WebAPK, preserve 100% native detection
        if (!isBrowserPkg && !isWebApkPkg) {
            return null
        }

        // Signal 3 Check A: Direct WebAPK package match
        if (isWebApkPkg || webApkInventory.containsKey(packageName)) {
            val directInfo = webApkInventory[packageName]
            val label = directInfo?.label ?: run {
                try {
                    val pm = context.packageManager
                    val info = pm.getApplicationInfo(packageName, 0)
                    pm.getApplicationLabel(info).toString().trim()
                } catch (_: Exception) { null }
            }
            if (!label.isNullOrBlank() && !label.startsWith("org.chromium", ignoreCase = true) && !label.equals("Chrome", ignoreCase = true)) {
                val cat = mapCategoryForName(label)
                val appInfo = AppInfo(appName = label, category = cat, isLocallyResolved = true)
                stickyPwaCache[packageName] = CachedPwaSession(label, cat, packageName)
                Log.d(TAG, "[PWA Cascade] Signal 3 Direct Match: $packageName -> $label ($cat)")
                return appInfo
            }
        }

        // Signal 1: Activity Class Fingerprint
        val isWebApkActivity = className?.let {
            it.contains("SameTaskWebApkActivity") || it.contains("WebApkActivity") || it.contains("WebappActivity")
        } == true

        // Signal 2: URL Bar Check (Absence of url_bar + webapp activity = standalone mode)
        val hasUrlBar = visibleTexts.any { it.contains("url_bar", ignoreCase = true) || it.contains("http://") || it.contains("https://") }
        val isStandaloneMode = isWebApkActivity && !hasUrlBar

        // Signal 3 Check B: Window Title Fuzzy Match against inventory and known PWA rules
        val cleanTitle = windowTitle?.trim()
        if (!cleanTitle.isNullOrBlank()) {
            for (info in webApkInventory.values) {
                if (cleanTitle.contains(info.label, ignoreCase = true) || info.label.contains(cleanTitle, ignoreCase = true)) {
                    val cat = mapCategoryForName(info.label)
                    val appInfo = AppInfo(appName = info.label, category = cat, isLocallyResolved = true)
                    stickyPwaCache[packageName] = CachedPwaSession(info.label, cat, packageName)
                    Log.d(TAG, "[PWA Cascade] Signal 3 Title Match: '$cleanTitle' -> '${info.label}' ($cat)")
                    return appInfo
                }
            }

            val pwaTitleMap = mapOf(
                "google ai studio" to Pair("Google AI Studio", "AI"),
                "ai studio" to Pair("Google AI Studio", "AI"),
                "brain ai" to Pair("Brain AI", "AI"),
                "chatgpt" to Pair("ChatGPT", "AI"),
                "claude" to Pair("Claude", "AI"),
                "perplexity" to Pair("Perplexity", "AI"),
                "grok" to Pair("Grok", "AI"),
                "deepseek" to Pair("DeepSeek", "AI"),
                "v0" to Pair("v0", "AI"),
                "kimi" to Pair("Kimi", "AI"),
                "pinterest" to Pair("Pinterest", "Social")
            )
            val titleLower = cleanTitle.lowercase()
            for ((key, pair) in pwaTitleMap) {
                if (titleLower.contains(key)) {
                    val (appName, cat) = pair
                    val appInfo = AppInfo(appName = appName, category = cat, isLocallyResolved = true)
                    stickyPwaCache[packageName] = CachedPwaSession(appName, cat, packageName)
                    Log.d(TAG, "[PWA Cascade] Signal 3 Direct Title Rule: '$cleanTitle' -> $appName ($cat)")
                    return appInfo
                }
            }
        }

        // Signal 4: UsageStats Recency Correlation
        if (cleanTitle.isNullOrBlank() && isBrowserPkg) {
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usm != null) {
                    val now = System.currentTimeMillis()
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 300_000, now)
                    if (!stats.isNullOrEmpty()) {
                        val recentWebApk = stats
                            .filter { it.packageName.startsWith("org.chromium.webapk") || it.packageName.contains(".webapk") }
                            .maxByOrNull { it.lastTimeUsed }
                        if (recentWebApk != null && (now - recentWebApk.lastTimeUsed) < 300_000) {
                            val webApkPkg = recentWebApk.packageName
                            val info = webApkInventory[webApkPkg]
                            val label = info?.label ?: run {
                                try {
                                    val pm = context.packageManager
                                    val appInf = pm.getApplicationInfo(webApkPkg, 0)
                                    pm.getApplicationLabel(appInf).toString().trim()
                                } catch (_: Exception) { null }
                            }
                            if (!label.isNullOrBlank() && !label.startsWith("org.chromium", ignoreCase = true)) {
                                val cat = mapCategoryForName(label)
                                val appInfo = AppInfo(appName = label, category = cat, isLocallyResolved = true)
                                stickyPwaCache[packageName] = CachedPwaSession(label, cat, packageName)
                                Log.d(TAG, "[PWA Cascade] Signal 4 UsageStats Match: $webApkPkg -> $label ($cat)")
                                return appInfo
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Signal 4 UsageStats query error: ${e.message}")
            }
        }

        // Sticky Session Hysteresis
        val cachedPwa = stickyPwaCache[packageName]
        if (cachedPwa != null && (System.currentTimeMillis() - cachedPwa.timestamp) < STICKY_TTL_MS) {
            Log.d(TAG, "[PWA Cascade] Sticky Session Cache Hit: ${cachedPwa.name} (${cachedPwa.category})")
            return AppInfo(appName = cachedPwa.name, category = cachedPwa.category, isLocallyResolved = true)
        }

        if (isWebApkPkg) {
            return AppInfo(appName = "Web App", category = "Web App", isLocallyResolved = true)
        }

        return null
    }

    fun resolve(
        context: Context,
        packageName: String,
        className: String? = null,
        windowTitle: String? = null
    ): AppInfo {
        val pkgLower = packageName.lowercase()
        // 1. Special check for Gemini running inside Google QuickSearchBox or Bard:
        if (pkgLower == "com.google.android.googlequicksearchbox" || pkgLower == "com.google.android.apps.bard") {
            val titleStr = windowTitle ?: ""
            val classStr = className ?: ""

            val isGeminiSurface = titleStr.contains("Gemini", ignoreCase = true) ||
                                  classStr.contains("gemini", ignoreCase = true) ||
                                  classStr.contains("bard", ignoreCase = true)

            if (isGeminiSurface || pkgLower == "com.google.android.apps.bard") {
                return AppInfo(appName = "Gemini", category = "AI", isLocallyResolved = true)
            }
        }

        val pwaInfo = resolvePwaCascade(
            context = context,
            packageName = packageName,
            className = className,
            windowTitle = windowTitle
        )
        if (pwaInfo != null) {
            return pwaInfo
        }

        val evidence = AppResolutionEngine.defaultInstance.collectEvidence(
            context = context,
            packageName = packageName,
            className = className
        )
        val localContext = AppResolutionEngine.defaultInstance.resolveLocalCategory(context, evidence)
        return if (localContext != null) {
            AppInfo(appName = localContext.name, category = localContext.category, isLocallyResolved = true)
        } else {
            AppInfo(appName = evidence.localDisplayName, category = "Other", isLocallyResolved = true)
        }
    }

    /**
     * Hybrid Detection Entrypoint:
     * - Stage A: Multi-signal evidence & local display name computed synchronously
     * - Stage B: Fast local lookup (static maps, learned cache, heuristics, PWA cascade) -> 0 network calls
     * - Stage C: Lightweight single-query classification with gemini-3.5-flash-lite saved permanently
     */
    suspend fun detectAppHybrid(
        evidence: AppResolutionEngine.AppEvidence,
        context: Context
    ): AppInfo = withContext(Dispatchers.IO) {
        val cacheKey = evidence.learnedRegistryKey

        // 0. PWA Cascade Check
        val pwaCascadeResult = resolvePwaCascade(
            context = context,
            packageName = evidence.packageName,
            className = evidence.className,
            windowTitle = evidence.windowTitle,
            visibleTexts = evidence.visibleNodeTexts + evidence.contentDescriptions
        )
        if (pwaCascadeResult != null) {
            com.example.data.AppDetectionLogRepository.logEvent(
                com.example.data.AppDetectionEvent(
                    rawPackageName = evidence.packageName,
                    rawWindowTitle = evidence.windowTitle,
                    packageManagerLabel = evidence.appLabel,
                    topScreenTexts = (evidence.visibleNodeTexts + evidence.contentDescriptions).distinct().take(5),
                    resolvedAppName = pwaCascadeResult.appName,
                    classificationSource = "WEBAPK_CASCADE",
                    finalCategory = pwaCascadeResult.category
                )
            )
            return@withContext pwaCascadeResult
        }

        // 1. In-memory session cache check
        synchronized(sessionCache) {
            val cached = sessionCache[cacheKey]
            if (cached != null) {
                Log.d(TAG, "[HybridDetector] Cache HIT (Session Cache): key='$cacheKey', appName='${cached.appName}', category='${cached.category}'")
                com.example.data.AppDetectionLogRepository.logEvent(
                    com.example.data.AppDetectionEvent(
                        rawPackageName = evidence.packageName,
                        rawWindowTitle = evidence.windowTitle,
                        packageManagerLabel = evidence.appLabel,
                        topScreenTexts = (evidence.visibleNodeTexts + evidence.contentDescriptions).distinct().take(5),
                        resolvedAppName = cached.appName,
                        classificationSource = "LEARNED_CACHE",
                        finalCategory = cached.category
                    )
                )
                return@withContext cached
            }
        }

        // 2. Stage B: Try local category resolution (Static maps, Learned registry, Heuristics)
        val localContext = AppResolutionEngine.defaultInstance.resolveLocalCategory(context, evidence)
        if (localContext != null) {
            val localResult = AppInfo(
                appName = localContext.name,
                category = localContext.category,
                isLocallyResolved = true
            )
            synchronized(sessionCache) {
                sessionCache[cacheKey] = localResult
            }
            com.example.data.AppDetectionLogRepository.logEvent(
                com.example.data.AppDetectionEvent(
                    rawPackageName = evidence.packageName,
                    rawWindowTitle = evidence.windowTitle,
                    packageManagerLabel = evidence.appLabel,
                    topScreenTexts = (evidence.visibleNodeTexts + evidence.contentDescriptions).distinct().take(5),
                    resolvedAppName = localResult.appName,
                    classificationSource = "STATIC_DICTIONARY",
                    finalCategory = localResult.category
                )
            )
            return@withContext localResult
        }

        Log.d(TAG, "[HybridDetector] Cache MISS: key='$cacheKey', localLabel='${evidence.localDisplayName}' -> Proceeding to Stage C with $PRIMARY_MODEL")

        // 3. Stage C: Category unknown locally -> Call gemini-3.5-flash-lite
        val coroutineJob = coroutineContext[Job]

        synchronized(this) {
            if (currentDetectingKey != null && currentDetectingKey != cacheKey) {
                Log.d(TAG, "Foreground target changed from $currentDetectingKey to $cacheKey. Cancelling stale job.")
                activeJob?.cancel()
                activeJob = null
                currentDetectingKey = null
            }

            if (activeJob?.isActive == true) {
                Log.d(TAG, "Detection already in-progress for $currentDetectingKey. Dropping duplicate request for $cacheKey.")
                throw CancellationException("Another detection already in progress")
            }

            currentDetectingKey = cacheKey
            activeJob = coroutineJob
        }

        val apiKey = resolveApiKey(context)
        if (apiKey.isEmpty() || isPlaceholderKey(apiKey)) {
            Log.w(TAG, "Gemini API Key missing/placeholder. Falling back to local name '${evidence.localDisplayName}' -> Other")
            val fallback = AppInfo(appName = evidence.localDisplayName, category = "Other", isLocallyResolved = true)
            synchronized(sessionCache) { sessionCache[cacheKey] = fallback }
            com.example.data.AppDetectionLogRepository.logEvent(
                com.example.data.AppDetectionEvent(
                    rawPackageName = evidence.packageName,
                    rawWindowTitle = evidence.windowTitle,
                    packageManagerLabel = evidence.appLabel,
                    topScreenTexts = (evidence.visibleNodeTexts + evidence.contentDescriptions).distinct().take(5),
                    resolvedAppName = fallback.appName,
                    classificationSource = "DEFAULT_FALLBACK",
                    finalCategory = fallback.category
                )
            )
            return@withContext fallback
        }

        val prompt = "Classify the application '${evidence.localDisplayName}' into exactly one category: AI_CHAT, MESSAGING, EMAIL, NOTES, SOCIAL, OTHER. Output ONLY the category name."
        var rawCategory = ""
        try {
            rawCategory = executeGeminiClassification(apiKey, prompt)
            val mappedCategory = mapCategoryStringToGroup(rawCategory)

            // Persist into LearnedAppRegistry permanently
            LearnedAppRegistry.recordConfirmation(
                context = context,
                key = cacheKey,
                appName = evidence.localDisplayName,
                category = mappedCategory,
                source = "gemini"
            )

            val result = AppInfo(
                appName = evidence.localDisplayName,
                category = mappedCategory,
                isLocallyResolved = false
            )

            synchronized(sessionCache) {
                sessionCache[cacheKey] = result
            }

            // Notify bubble manager to update UI seamlessly
            FloatingBubbleManager.updateLearnedAppContext(evidence.localDisplayName, mappedCategory)

            com.example.data.AppDetectionLogRepository.logEvent(
                com.example.data.AppDetectionEvent(
                    rawPackageName = evidence.packageName,
                    rawWindowTitle = evidence.windowTitle,
                    packageManagerLabel = evidence.appLabel,
                    topScreenTexts = (evidence.visibleNodeTexts + evidence.contentDescriptions).distinct().take(5),
                    resolvedAppName = result.appName,
                    classificationSource = "AI_MODEL",
                    aiPromptSent = prompt,
                    aiRawResponse = rawCategory,
                    finalCategory = result.category
                )
            )

            Log.d(TAG, "[HybridDetector] Stage C Learned successfully: appName='${result.appName}', category='${result.category}'")
            return@withContext result

        } catch (e: Exception) {
            Log.w(TAG, "Stage C Gemini call failed for $cacheKey: ${e.message}. Defaulting to Other.", e)
            val fallback = AppInfo(appName = evidence.localDisplayName, category = "Other", isLocallyResolved = true)
            synchronized(sessionCache) {
                sessionCache[cacheKey] = fallback
            }
            com.example.data.AppDetectionLogRepository.logEvent(
                com.example.data.AppDetectionEvent(
                    rawPackageName = evidence.packageName,
                    rawWindowTitle = evidence.windowTitle,
                    packageManagerLabel = evidence.appLabel,
                    topScreenTexts = (evidence.visibleNodeTexts + evidence.contentDescriptions).distinct().take(5),
                    resolvedAppName = fallback.appName,
                    classificationSource = "AI_MODEL_FALLBACK",
                    aiPromptSent = prompt,
                    aiRawResponse = "Error: ${e.message}",
                    finalCategory = fallback.category
                )
            )
            return@withContext fallback
        } finally {
            synchronized(this) {
                if (currentDetectingKey == cacheKey) {
                    activeJob = null
                    currentDetectingKey = null
                }
            }
        }
    }

    private fun mapCategoryStringToGroup(raw: String): String {
        val upper = raw.trim().uppercase()
        return when {
            upper.contains("AI") -> "AI"
            upper.contains("MESSAG") || upper.contains("CHAT") -> "Social"
            upper.contains("EMAIL") || upper.contains("MAIL") -> "Email"
            upper.contains("NOTE") || upper.contains("DOC") || upper.contains("WORK") -> "Notes"
            upper.contains("SOCIAL") -> "Social"
            else -> "Other"
        }
    }

    private suspend fun executeGeminiClassification(apiKey: String, prompt: String): String {
        return try {
            callGeminiREST(PRIMARY_MODEL, apiKey, prompt)
        } catch (e: Exception) {
            Log.w(TAG, "Primary model $PRIMARY_MODEL failed (${e.message}). Retrying with $FALLBACK_MODEL...")
            callGeminiREST(FALLBACK_MODEL, apiKey, prompt)
        }
    }

    private fun callGeminiREST(modelName: String, apiKey: String, prompt: String): String {
        val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=${apiKey.trim()}"
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    }
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 32)
                })
            }.toString()

            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("x-goog-api-key", apiKey.trim())
                connectTimeout = 10000
                readTimeout = 12000
                doOutput = true
            }

            connection.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                val rootJson = JSONObject(responseString)
                val candidates = rootJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text", "")
                        if (text.isNotBlank()) {
                            return text.trim()
                        }
                    }
                }
                throw IOException("Gemini returned HTTP 200 with empty text parts")
            } else {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Throwable) { "" }
                throw GeminiApiException(responseCode, "HTTP $responseCode: $errorBody")
            }
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun detectApp(
        packageName: String,
        extraEvidence: String? = null,
        context: Context? = null
    ): AppInfo {
        val ctx = context ?: FloatingBubbleService.instance?.applicationContext
        if (ctx == null) {
            return AppInfo(appName = packageName, category = "Other", isLocallyResolved = true)
        }
        val evidence = AppResolutionEngine.defaultInstance.collectEvidence(
            context = ctx,
            packageName = packageName
        )
        return detectAppHybrid(evidence, ctx)
    }

    fun clearCache() {
        synchronized(sessionCache) {
            sessionCache.clear()
        }
        webApkInventory.clear()
        stickyPwaCache.clear()
        AppResolutionEngine.defaultInstance.clearCache()
    }
}

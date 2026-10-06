package com.example.service

import android.content.Context
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

data class AppInfo(
    val appName: String,
    val category: String,
    val isLocallyResolved: Boolean = false
)

class GeminiApiException(val statusCode: Int, message: String) : IOException(message)

object AppDetector {
    private const val TAG = "AppDetector"
    private const val PRIMARY_MODEL = "gemini-3.5-flash-lite"
    private const val FALLBACK_MODEL = "gemini-3.8-flash"

    // In-Memory Session Cache (keyed by learnedRegistryKey)
    private val sessionCache = mutableMapOf<String, AppInfo>()

    // Singleton concurrency state lock
    private var activeJob: Job? = null
    private var currentDetectingKey: String? = null

    fun isPlaceholderKey(key: String?): Boolean {
        return com.example.core.ApiConfig.isPlaceholder(key)
    }

    fun resolveApiKey(context: Context? = null): String {
        return com.example.core.ApiConfig.resolveApiKey(context)
    }

    /**
     * Hybrid Detection Entrypoint:
     * - Stage A: Multi-signal evidence & local display name computed synchronously
     * - Stage B: Fast local lookup (static maps, learned cache, heuristics) -> 0 network calls
     * - Stage C: Lightweight single-query classification with gemini-3.5-flash-lite saved permanently
     */
    suspend fun detectAppHybrid(
        evidence: AppResolutionEngine.AppEvidence,
        context: Context
    ): AppInfo = withContext(Dispatchers.IO) {
        val cacheKey = evidence.learnedRegistryKey

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
        AppResolutionEngine.defaultInstance.clearCache()
    }
}

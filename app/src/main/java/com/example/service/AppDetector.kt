package com.example.service

import android.content.Context
import android.util.Log
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
    private const val MODEL_NAME = "gemini-3.1-flash-lite"

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
     * - Stage C: Live Google Search grounded Gemini classification only for unknown apps/sites
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
                Log.d(TAG, "[HybridDetector] Cache HIT (Session Cache): key='$cacheKey', appName='${cached.appName}', category='${cached.category}', localLabel='${evidence.localDisplayName}' -> Gemini called: FALSE")
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
            val learnedEntry = LearnedAppRegistry.get(context, cacheKey)
            Log.d(TAG, "[HybridDetector] Cache HIT (Stage B / Learned Registry): key='$cacheKey', appName='${localResult.appName}' (learned='${learnedEntry?.appName}', local='${evidence.localDisplayName}'), category='${localResult.category}', confirmCount=${learnedEntry?.confirmCount ?: 0} -> Gemini called: FALSE")
            return@withContext localResult
        }

        Log.d(TAG, "[HybridDetector] Cache MISS: key='$cacheKey', localLabel='${evidence.localDisplayName}' -> Proceeding to Stage C with Google Search Grounding")

        // 3. Stage C: Category unknown locally -> Call Gemini with Live Search Grounding
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
            return@withContext fallback
        }

        try {
            Log.d(TAG, "Stage C: Calling Gemini for $cacheKey with localDisplayName='${evidence.localDisplayName}' (Google Search Grounding ON) -> Gemini called: TRUE")

            val systemInstruction = """
You classify the Android foreground app/site. You may use Google Search.

Input: package name, localDisplayName (from PackageManager / WebAPK meta), optional URL, window title, activity, on-screen text.

Output JSON ONLY:
{ "appName": string, "category": "AI"|"Social"|"Work"|"Other" }

Rules:
- Prefer CURRENT product name from Search / Play / official sources over discontinued or old Labs names.
- Example: com.google.android.apps.labs.whisk and labels related to Whisk/Flow → appName "Google Flow" (or "Flow"), category "AI". Do NOT use the old standalone "Whisk" name if sources say it migrated into Flow.
- If localDisplayName is specific and non-generic (not Chrome, Browser, Web Application, App), prefer it unless Search clearly shows a rename/rebrand to a current product.
- category:
  - AI: assistants, LLM apps, AI image/video studios (Gemini, ChatGPT, Claude, Grok, Google AI Studio, Google Flow, Perplexity, Kimi, Qwen, DeepSeek, NotebookLM, v0, …)
  - Social: messaging/social
  - Work: productivity/dev/office
  - Other: browsers with no known site product, betting, games, utilities
- Do not label plain Google Search as AI.
- Do not label the browser as the product when URL/title names a site (AI Studio, SportyBet, etc.).
- Prefer short official names. No marketing blurbs.
""".trimIndent()

            val userContent = evidence.buildEvidencePromptString()

            val schemaJson = JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("appName", JSONObject().apply { put("type", "STRING") })
                    put("category", JSONObject().apply {
                        put("type", "STRING")
                        put("enum", JSONArray(listOf("AI", "Social", "Work", "Other")))
                    })
                })
                put("required", JSONArray(listOf("appName", "category")))
            }

            // Execute Grounding with automatic fallback chain if schema conflicts with search tools
            val (parsedName, parsedCategory) = executeStageCGeminiClassification(
                apiKey = apiKey,
                systemInstruction = systemInstruction,
                userContent = userContent,
                schemaJson = schemaJson,
                defaultName = evidence.localDisplayName
            )

            // Persist into LearnedAppRegistry with source = "gemini" and update confirmation count
            val learned = LearnedAppRegistry.recordConfirmation(
                context = context,
                key = cacheKey,
                appName = parsedName,
                category = parsedCategory,
                source = "gemini"
            )

            val result = AppInfo(
                appName = parsedName,
                category = parsedCategory,
                isLocallyResolved = false
            )

            synchronized(sessionCache) {
                sessionCache[cacheKey] = result
            }

            Log.d(TAG, "[HybridDetector] Stage C Succeeded: key='$cacheKey' -> learned.appName='${learned.appName}', category='${learned.category}', confirmCount=${learned.confirmCount}, source='${learned.source}', localLabel='${evidence.localDisplayName}' -> Gemini called: TRUE")
            return@withContext result

        } catch (e: Exception) {
            Log.w(TAG, "Stage C Gemini call failed for $cacheKey: ${e.message}. Falling back to local name.", e)
            val fallback = AppInfo(appName = evidence.localDisplayName, category = "Other", isLocallyResolved = true)
            synchronized(sessionCache) {
                sessionCache[cacheKey] = fallback
            }
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

    /**
     * Executes classification via Live Search Grounding with an ordered fallback chain:
     * 1. Primary: Grounding ON + JSON Mode + Schema
     * 2. Fallback A: Grounding ON + JSON Mode (no schema)
     * 3. Fallback B: Grounding ON + Plain Text Mode (regex/json extraction)
     * 4. Fallback C: Grounding OFF + Schema (offline knowledge fallback)
     */
    private suspend fun executeStageCGeminiClassification(
        apiKey: String,
        systemInstruction: String,
        userContent: String,
        schemaJson: JSONObject,
        defaultName: String
    ): Pair<String, String> {
        // 1. Primary Attempt: Grounding ON + JSON Mode + Schema
        try {
            val text = callGeminiWithRetry(
                apiKey = apiKey,
                systemInstruction = systemInstruction,
                userContent = userContent,
                enableGoogleSearch = true,
                responseMimeType = "application/json",
                responseSchema = schemaJson
            )
            val parsed = parseJsonAppInfo(text, defaultName)
            if (parsed != null) {
                Log.d(TAG, "Stage C Succeeded via Primary Path (Grounding ON + JSON Schema)")
                return parsed
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stage C Primary Path failed (${e.message}). Trying Fallback A (Grounding ON + JSON without schema)...")
        }

        // 2. Fallback A: Grounding ON + JSON Mode without Schema
        try {
            val text = callGeminiWithRetry(
                apiKey = apiKey,
                systemInstruction = systemInstruction,
                userContent = userContent,
                enableGoogleSearch = true,
                responseMimeType = "application/json",
                responseSchema = null
            )
            val parsed = parseJsonAppInfo(text, defaultName)
            if (parsed != null) {
                Log.d(TAG, "Stage C Succeeded via Fallback Path A (Grounding ON + JSON Mode)")
                return parsed
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stage C Fallback Path A failed (${e.message}). Trying Fallback B (Grounding ON + Plain Text)...")
        }

        // 3. Fallback B: Grounding ON + Plain Text Mode
        try {
            val text = callGeminiWithRetry(
                apiKey = apiKey,
                systemInstruction = systemInstruction,
                userContent = userContent,
                enableGoogleSearch = true,
                responseMimeType = null,
                responseSchema = null
            )
            val parsed = parseJsonAppInfo(text, defaultName)
            if (parsed != null) {
                Log.d(TAG, "Stage C Succeeded via Fallback Path B (Grounding ON + Plain Text)")
                return parsed
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stage C Fallback Path B failed (${e.message}). Trying Fallback C (Grounding OFF + Schema)...")
        }

        // 4. Fallback C: Grounding OFF + Schema (Offline knowledge)
        val text = callGeminiWithRetry(
            apiKey = apiKey,
            systemInstruction = systemInstruction,
            userContent = userContent,
            enableGoogleSearch = false,
            responseMimeType = "application/json",
            responseSchema = schemaJson
        )
        val parsed = parseJsonAppInfo(text, defaultName)
        if (parsed != null) {
            Log.d(TAG, "Stage C Succeeded via Fallback Path C (Grounding OFF + Schema)")
            return parsed
        }

        throw IOException("Failed to parse valid app info from Gemini response")
    }

    private fun parseJsonAppInfo(rawResponse: String?, defaultName: String): Pair<String, String>? {
        if (rawResponse.isNullOrBlank()) return null
        val trimmed = rawResponse.trim()

        // Direct JSON object attempt
        try {
            val obj = JSONObject(trimmed)
            val name = obj.optString("appName", "").trim().ifBlank { defaultName }
            val cat = obj.optString("category", "").trim().ifBlank { "Other" }
            return Pair(name, cat)
        } catch (_: Exception) {}

        // Regex JSON extraction from Markdown codeblocks or surrounding text
        try {
            val jsonPattern = Regex("""\{[\s\S]*?"appName"[\s\S]*?"category"[\s\S]*?\}""")
            val match = jsonPattern.find(trimmed)?.value
            if (match != null) {
                val obj = JSONObject(match)
                val name = obj.optString("appName", "").trim().ifBlank { defaultName }
                val cat = obj.optString("category", "").trim().ifBlank { "Other" }
                return Pair(name, cat)
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Backward-compatible convenience method.
     */
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

    private suspend fun callGeminiWithRetry(
        apiKey: String,
        systemInstruction: String,
        userContent: String,
        enableGoogleSearch: Boolean,
        responseMimeType: String?,
        responseSchema: JSONObject?
    ): String {
        val maxAttempts = 3
        var backoffMs = 1000L
        var lastException: Exception? = null

        for (attempt in 1..maxAttempts) {
            try {
                return callGeminiREST(
                    apiKey = apiKey,
                    systemInstruction = systemInstruction,
                    userContent = userContent,
                    enableGoogleSearch = enableGoogleSearch,
                    responseMimeType = responseMimeType,
                    responseSchema = responseSchema
                )
            } catch (e: GeminiApiException) {
                lastException = e
                val isTransient = e.statusCode == 429 || (e.statusCode in 500..599)
                if (isTransient && attempt < maxAttempts) {
                    Log.w(TAG, "Transient HTTP ${e.statusCode} on attempt $attempt. Retrying in ${backoffMs}ms: ${e.message}")
                    kotlinx.coroutines.delay(backoffMs)
                    backoffMs *= 2
                    continue
                } else {
                    throw e
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxAttempts) {
                    Log.w(TAG, "Network IOException on attempt $attempt. Retrying in ${backoffMs}ms: ${e.message}")
                    kotlinx.coroutines.delay(backoffMs)
                    backoffMs *= 2
                    continue
                } else {
                    throw e
                }
            }
        }
        throw lastException ?: IOException("Request failed after $maxAttempts attempts")
    }

    private fun callGeminiREST(
        apiKey: String,
        systemInstruction: String,
        userContent: String,
        enableGoogleSearch: Boolean,
        responseMimeType: String?,
        responseSchema: JSONObject?
    ): String {
        val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            val jsonBody = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                })
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", userContent)))
                    }
                ))

                if (enableGoogleSearch) {
                    put("tools", JSONArray().put(JSONObject().apply {
                        put("googleSearch", JSONObject())
                    }))
                }

                val generationConfig = JSONObject().apply {
                    put("temperature", 0.1)
                    if (responseMimeType != null) {
                        put("responseMimeType", responseMimeType)
                    }
                    if (responseSchema != null) {
                        put("responseSchema", responseSchema)
                    }
                }
                put("generationConfig", generationConfig)
            }.toString()

            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("x-goog-api-key", apiKey)
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
                        val sb = StringBuilder()
                        for (i in 0 until parts.length()) {
                            val partObj = parts.getJSONObject(i)
                            val text = partObj.optString("text", "")
                            if (text.isNotEmpty()) {
                                sb.append(text)
                            }
                        }
                        val resultText = sb.toString().trim()
                        if (resultText.isNotEmpty()) {
                            return resultText
                        }
                    }
                }
                throw IOException("Gemini returned HTTP 200 with no candidate text parts")
            } else {
                val errorStreamText = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (_: Throwable) { null }
                val finalBody = errorStreamText ?: ""
                val parsedMsg = parseGeminiErrorMessage(responseCode, finalBody)
                throw GeminiApiException(responseCode, parsedMsg)
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseGeminiErrorMessage(responseCode: Int, errorBody: String): String {
        if (errorBody.isBlank()) return "HTTP $responseCode (empty error body)"
        try {
            val root = JSONObject(errorBody)
            val errObj = root.optJSONObject("error")
            if (errObj != null) {
                val message = errObj.optString("message", "")
                val status = errObj.optString("status", "")
                return if (status.isNotEmpty() && message.isNotEmpty()) {
                    "HTTP $responseCode [$status]: $message"
                } else if (message.isNotEmpty()) {
                    "HTTP $responseCode: $message"
                } else {
                    "HTTP $responseCode: $errorBody"
                }
            }
        } catch (_: Exception) {
            // Not JSON
        }
        val snippet = if (errorBody.length > 200) errorBody.take(200) + "..." else errorBody
        return "HTTP $responseCode: $snippet"
    }

    fun clearCache() {
        synchronized(sessionCache) {
            sessionCache.clear()
        }
        AppResolutionEngine.defaultInstance.clearCache()
    }
}

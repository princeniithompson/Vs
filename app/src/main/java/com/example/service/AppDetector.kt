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
        if (key.isNullOrBlank()) return true
        val upper = key.trim().uppercase(java.util.Locale.US)
        return upper == "MY_GEMINI_API_KEY" ||
               upper.contains("PLACEHOLDER") ||
               upper.contains("YOUR_")
    }

    fun resolveApiKey(context: Context? = null): String {
        val ctx = context ?: FloatingBubbleService.instance?.applicationContext
        val prefs = ctx?.getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE)
        val customKey = prefs?.getString("custom_api_key", null)?.trim().orEmpty()
        if (customKey.isNotEmpty() && !isPlaceholderKey(customKey)) {
            return customKey
        }
        val buildKey = try {
            com.example.BuildConfig.GEMINI_API_KEY.trim()
        } catch (_: Throwable) {
            ""
        }
        if (buildKey.isNotEmpty() && !isPlaceholderKey(buildKey)) {
            return buildKey
        }
        return ""
    }

    /**
     * Hybrid Detection entrypoint:
     * - Stage A: Evidence & local display name already collected in AppEvidence
     * - Stage B: Checks local static maps, learned cache, and heuristics (zero network)
     * - Stage C: Calls Gemini only if still unconfirmed/unknown, then writes to LearnedAppRegistry
     */
    suspend fun detectAppHybrid(
        evidence: AppResolutionEngine.AppEvidence,
        context: Context
    ): AppInfo = withContext(Dispatchers.IO) {
        val cacheKey = evidence.learnedRegistryKey

        // 1. Check in-memory session cache first
        synchronized(sessionCache) {
            val cached = sessionCache[cacheKey]
            if (cached != null) {
                Log.d(TAG, "Session cache hit for key: $cacheKey -> ${cached.appName} (${cached.category})")
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
            Log.d(TAG, "Stage B hit (Zero network): $cacheKey -> ${localResult.appName} (${localResult.category})")
            return@withContext localResult
        }

        // 3. Stage C: Category unknown locally -> Call Gemini
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
            Log.d(TAG, "Stage C: Calling Gemini for $cacheKey with localDisplayName='${evidence.localDisplayName}'")

            val systemInstruction = """
You identify the exact application or website the user is currently using on Android and classify its category.
Input includes Android package name, locally resolved display name, and optional evidence (URL, window title, activity, on-screen text).

Rules:
- Output JSON ONLY matching schema: { "appName": string, "category": "AI"|"Social"|"Work"|"Other" }
- For appName: Prefer localDisplayName unless it is generic (e.g. Chrome, Browser, Web Application, App) and the URL/title/UI clearly names a specific product (e.g. Google AI Studio, SportyBet, ChatGPT, Claude, Grok, GitHub, Notion).
- For native apps (e.g. Flow / com.google.android.apps.labs.whisk): Keep the localDisplayName as appName.
- Category rules:
  - 'AI': AI assistants, AI studios, LLM apps, AI video/image generators (Gemini, ChatGPT, Claude, Grok, Google AI Studio, Perplexity, Kimi, Qwen, DeepSeek, Flow, NotebookLM, v0, etc.)
  - 'Social': Messaging, chat, social networking (WhatsApp, Telegram, Messages, Instagram, Twitter/X, Discord, Reddit, etc.)
  - 'Work': Productivity, docs, developer tools, office (Gmail, Docs, Sheets, GitHub, Notion, Figma, Canva, Slack, Teams, etc.)
  - 'Other': Generic browsers, betting apps (SportyBet), games, system utilities.
- Do NOT label plain Google Search / Google App as 'AI'.
- Do NOT label a browser as the product name when the website product is known from URL/title.
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

            val responseJsonText = callGeminiWithRetry(
                apiKey = apiKey,
                systemInstruction = systemInstruction,
                userContent = userContent,
                enableGoogleSearch = false,
                responseMimeType = "application/json",
                responseSchema = schemaJson
            )

            if (responseJsonText.isBlank()) {
                throw IOException("Gemini returned empty JSON response")
            }

            val json = JSONObject(responseJsonText.trim())
            var parsedName = json.optString("appName", "").trim()
            val parsedCategory = json.optString("category", "").trim()

            if (parsedName.isBlank()) {
                parsedName = evidence.localDisplayName
            }
            val finalCategory = if (parsedCategory.isNotBlank()) parsedCategory else "Other"

            // Persist into LearnedAppRegistry and update confirmation count
            val learned = LearnedAppRegistry.recordConfirmation(
                context = context,
                key = cacheKey,
                appName = parsedName,
                category = finalCategory
            )

            val result = AppInfo(
                appName = parsedName,
                category = finalCategory,
                isLocallyResolved = false
            )

            synchronized(sessionCache) {
                sessionCache[cacheKey] = result
            }

            Log.d(TAG, "Stage C Succeeded: $cacheKey -> ${result.appName} (${result.category}), learnedCount=${learned.confirmCount}")
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
        val maxAttempts = 2
        var backoffMs = 1200L
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

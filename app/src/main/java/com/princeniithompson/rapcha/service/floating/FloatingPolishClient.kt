package com.princeniithompson.rapcha.service.floating

import android.content.Context
import android.util.Log
import com.princeniithompson.rapcha.config.RapchaConfig
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.LogLevel
import com.princeniithompson.rapcha.service.AppCategory
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PolishResult(
    val text: String?,
    val errorDetail: String?
)

data class Stage2RestResult(
    val text: String?,
    val modelUsed: String,
    val errorDetail: String?,
    val quotaBypassed: Boolean = false,
    val rateLimited: Boolean = false,
    val totalTokens: Int = 0,
    val httpCode: Int = 0,
    val elapsedMs: Long = 0L,
    val exceptionClass: String? = null
)

object FloatingPolishClient {
    private const val TAG = "FloatingPolishClient"

    @Volatile
    private var lastSuccessfulPolishModel: String = RapchaConfig.POLISH_PRIMARY_MODEL

    fun polishTranscript(
        apiKey: String,
        rawTranscript: String,
        category: AppCategory = AppCategory.OTHER,
        appName: String = "App",
        aiPolishMode: AiPolishMode? = null,
        context: Context? = null
    ): PolishResult {
        if (RapchaConfig.isPlaceholderApiKey(apiKey)) {
            throw IllegalArgumentException("Gemini API Key is missing or placeholder. Please provide a valid key in Settings.")
        }
        val fullSystemInstruction = PolishPromptBuilder.buildSystemInstruction(category, appName)
        val userContentText = rawTranscript
        val res = executeGenerateContent(
            context = context,
            apiKey = apiKey,
            systemInstruction = fullSystemInstruction,
            userText = userContentText,
            logTag = "PolishAPI",
            logContext = "$appName ($category)",
            allowFallbackModels = true,
            maxOutputTokens = 8192
        )
        return PolishResult(res.text, res.errorDetail)
    }

    /**
     * Stage-2 polish targeting gemini-3.5-flash-lite with strict rate-limit monitoring
     * (15 RPM / 250K TPM / 500 RPD). If Flash Lite is in cooldown or hits 429, caller
     * automatically suspends Three-Step and falls back cleanly to standard 3.5 Smart.
     */
    fun polishStage2(
        context: Context?,
        apiKey: String,
        systemInstruction: String,
        userText: String,
        allowSecondaryFlashFallback: Boolean = false
    ): Stage2RestResult {
        if (RapchaConfig.isPlaceholderApiKey(apiKey)) {
            return Stage2RestResult(
                text = null,
                modelUsed = RapchaConfig.STAGE2_FLASH_LITE_MODEL,
                errorDetail = "Gemini API key missing"
            )
        }
        return executeGenerateContent(
            context = context,
            apiKey = apiKey,
            systemInstruction = systemInstruction,
            userText = userText,
            logTag = "Stage2FlashLite",
            logContext = "Stage-2 Lyra",
            allowFallbackModels = allowSecondaryFlashFallback,
            maxOutputTokens = 8192,
            respectStage2CooldownStrictly = !allowSecondaryFlashFallback
        )
    }

    private fun executeGenerateContent(
        context: Context?,
        apiKey: String,
        systemInstruction: String,
        userText: String,
        logTag: String,
        logContext: String,
        allowFallbackModels: Boolean,
        maxOutputTokens: Int,
        respectStage2CooldownStrictly: Boolean = false
    ): Stage2RestResult {
        val trimmedKey = apiKey.trim()
        val estimatedInputTokens = PolishQuotaMonitor.estimateTokens(systemInstruction) +
            PolishQuotaMonitor.estimateTokens(userText) + 128

        val baseModels = RapchaConfig.GEMINI_MODEL_FALLBACKS
        val ordered = listOf(lastSuccessfulPolishModel) + baseModels.filter { it != lastSuccessfulPolishModel }
        val primaryHealthy = context == null ||
            PolishQuotaMonitor.isPrimaryAvailable(context, projectedTokens = estimatedInputTokens)

        val modelsToTry = if (!allowFallbackModels) {
            listOf(RapchaConfig.STAGE2_FLASH_LITE_MODEL)
        } else if (!primaryHealthy) {
            ordered.filter { it != PolishQuotaMonitor.MODEL_FLASH_LITE }
                .ifEmpty { listOf("gemini-3.8-flash") }
        } else {
            ordered
        }

        if (respectStage2CooldownStrictly && context != null) {
            val gate = PolishQuotaMonitor.tryAcquire(context, estimatedInputTokens)
            if (!gate.available) {
                return Stage2RestResult(
                    text = null,
                    modelUsed = PolishQuotaMonitor.MODEL_FLASH_LITE,
                    errorDetail = "Quota cooldown (${gate.reason.label}, resets in ${(gate.cooldownRemainingMs / 1000L).coerceAtLeast(1L)}s)",
                    quotaBypassed = true
                )
            }
        }

        fun buildJsonBody(): String {
            return JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                })
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", userText)))
                    }
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.15)
                    put("maxOutputTokens", maxOutputTokens)
                })
            }.toString()
        }

        val errorsLog = mutableListOf<String>()
        var sawRateLimit = false
        var lastHttpCode = 0
        var lastExceptionClass: String? = null
        val callStartedMs = System.currentTimeMillis()

        for (modelName in modelsToTry) {
            if (!respectStage2CooldownStrictly &&
                modelName == PolishQuotaMonitor.MODEL_FLASH_LITE &&
                context != null
            ) {
                val gate = PolishQuotaMonitor.tryAcquire(context, estimatedInputTokens)
                if (!gate.available) {
                    errorsLog.add("$modelName cooldown (${gate.reason.label})")
                    continue
                }
            }

            var connection: HttpURLConnection? = null
            val attemptStartedMs = System.currentTimeMillis()
            try {
                val jsonBody = buildJsonBody()
                val url = URL(com.princeniithompson.rapcha.core.ApiConfig.geminiGenerateContentUrl(modelName))

                AppLogRepository.addLog(
                    LogLevel.SENT,
                    logTag,
                    "Sending POST request to model '$modelName' for $logContext",
                    jsonBody
                )

                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("x-goog-api-key", trimmedKey)
                    setRequestProperty("Connection", "keep-alive")
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = true
                }

                connection.outputStream.use { os ->
                    os.write(jsonBody.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                lastHttpCode = responseCode
                val attemptElapsedMs = (System.currentTimeMillis() - attemptStartedMs).coerceAtLeast(0L)
                if (responseCode == 200) {
                    val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                    if (com.princeniithompson.rapcha.BuildConfig.DEBUG) {
                        Log.d(TAG, "$logTag success response from $modelName")
                    }
                    AppLogRepository.addLog(
                        LogLevel.RECEIVED,
                        logTag,
                        "HTTP 200 OK from $modelName in ${attemptElapsedMs}ms",
                        responseString
                    )

                    val rootJson = JSONObject(responseString)
                    val totalTokens = parseTotalTokenCount(rootJson, responseString, estimatedInputTokens)
                    if (context != null && modelName == PolishQuotaMonitor.MODEL_FLASH_LITE) {
                        PolishQuotaMonitor.recordActualTokens(context, totalTokens, estimatedInputTokens)
                    }

                    val textResult = extractCandidateText(rootJson)
                    if (textResult.isNotBlank()) {
                        lastSuccessfulPolishModel = modelName
                        return Stage2RestResult(
                            text = textResult,
                            modelUsed = modelName,
                            errorDetail = null,
                            totalTokens = totalTokens,
                            httpCode = responseCode,
                            elapsedMs = attemptElapsedMs
                        )
                    }
                    errorsLog.add("$modelName returned empty candidates/parts")
                } else {
                    val errorStreamText = connection.errorStream?.bufferedReader()?.use { it.readText() }
                        ?: connection.inputStream?.bufferedReader()?.use { it.readText() }
                        ?: "No error body"
                    val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                    if (responseCode == 429) {
                        sawRateLimit = true
                        if (context != null && modelName == PolishQuotaMonitor.MODEL_FLASH_LITE) {
                            val daily = isDailyQuotaError(errorStreamText)
                            PolishQuotaMonitor.recordRateLimitError(
                                context = context,
                                isDailyQuota = daily,
                                retryAfterSeconds = retryAfter
                            )
                        }
                    }
                    val errLogMsg = "HTTP $responseCode ($modelName): $errorStreamText"
                    Log.e(TAG, "$logTag Error: $errLogMsg")
                    AppLogRepository.addLog(
                        LogLevel.ERROR,
                        logTag,
                        "HTTP $responseCode Error ($modelName) after ${attemptElapsedMs}ms",
                        errorStreamText
                    )
                    errorsLog.add(errLogMsg)
                }
            } catch (e: Exception) {
                lastExceptionClass = e.javaClass.simpleName
                val attemptElapsedMs = (System.currentTimeMillis() - attemptStartedMs).coerceAtLeast(0L)
                val excMsg = "Exception ($modelName/${e.javaClass.simpleName}): ${e.message}"
                Log.e(TAG, "Error in $logTag call for model $modelName", e)
                AppLogRepository.addLog(
                    LogLevel.ERROR,
                    logTag,
                    "Exception (${e.javaClass.simpleName}) connecting to $modelName after ${attemptElapsedMs}ms: ${e.message}",
                    e.stackTraceToString()
                )
                errorsLog.add(excMsg)
            } finally {
                connection?.disconnect()
            }
        }
        val totalElapsed = (System.currentTimeMillis() - callStartedMs).coerceAtLeast(0L)
        return Stage2RestResult(
            text = null,
            modelUsed = modelsToTry.lastOrNull() ?: RapchaConfig.STAGE2_FLASH_LITE_MODEL,
            errorDetail = errorsLog.joinToString(" | "),
            rateLimited = sawRateLimit,
            httpCode = lastHttpCode,
            elapsedMs = totalElapsed,
            exceptionClass = lastExceptionClass
        )
    }

    internal fun parseTotalTokenCount(
        rootJson: JSONObject,
        rawResponse: String,
        fallbackInputTokens: Int
    ): Int {
        val usage = rootJson.optJSONObject("usageMetadata")
        val reported = usage?.optInt("totalTokenCount", 0) ?: 0
        if (reported > 0) return reported
        return fallbackInputTokens + PolishQuotaMonitor.estimateTokens(rawResponse)
    }

    internal fun extractCandidateText(rootJson: JSONObject): String {
        val candidates = rootJson.optJSONArray("candidates") ?: return ""
        if (candidates.length() <= 0) return ""
        val content = candidates.getJSONObject(0).optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val partObj = parts.getJSONObject(i)
            val text = partObj.optString("text", "")
            if (text.isNotEmpty()) sb.append(text)
        }
        return PolishOutputCleaner.clean(sb.toString())
    }

    internal fun isDailyQuotaError(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("per_day") ||
            lower.contains("daily") ||
            lower.contains("rpd") ||
            lower.contains("requestsperday")
    }
}

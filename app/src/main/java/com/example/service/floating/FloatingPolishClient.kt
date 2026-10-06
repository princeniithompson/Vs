package com.example.service.floating

import android.util.Log
import com.example.config.VoxStreamConfig
import com.example.data.AppLogRepository
import com.example.data.LogLevel
import com.example.service.AppCategory
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PolishResult(
    val text: String?,
    val errorDetail: String?
)

object FloatingPolishClient {
    private const val TAG = "FloatingPolishClient"

    @Volatile
    private var lastSuccessfulPolishModel: String = VoxStreamConfig.POLISH_PRIMARY_MODEL

    fun polishTranscript(
        apiKey: String,
        rawTranscript: String,
        category: AppCategory = AppCategory.OTHER,
        appName: String = "App",
        aiPolishMode: AiPolishMode? = null
    ): PolishResult {
        if (VoxStreamConfig.isPlaceholderApiKey(apiKey)) {
            throw IllegalArgumentException("Gemini API Key is missing or placeholder. Please provide a valid key in Settings.")
        }
        val trimmedKey = apiKey.trim()

        val baseModels = VoxStreamConfig.GEMINI_MODEL_FALLBACKS
        val modelsToTry = listOf(lastSuccessfulPolishModel) + baseModels.filter { it != lastSuccessfulPolishModel }

        val fullSystemInstruction = PolishPromptBuilder.buildSystemInstruction(category, appName)
        val userContentText = rawTranscript

        fun buildJsonBody(modelName: String): String {
            return JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", fullSystemInstruction)))
                })
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", userContentText)))
                    }
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.15)
                    put("maxOutputTokens", 2048)
                })
            }.toString()
        }

        val errorsLog = mutableListOf<String>()

        for (modelName in modelsToTry) {
            var connection: HttpURLConnection? = null
            try {
                val jsonBody = try {
                    buildJsonBody(modelName)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed creating Polish JSON request body for $modelName", e)
                    return PolishResult(null, "JSON build error: ${e.message}")
                }

                val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$trimmedKey"
                val url = URL(urlString)

                AppLogRepository.addLog(
                    LogLevel.SENT,
                    "PolishAPI",
                    "Sending POST request to model '$modelName' for $appName ($category)",
                    jsonBody
                )

                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("x-goog-api-key", trimmedKey)
                    setRequestProperty("Connection", "keep-alive")
                    connectTimeout = 15000
                    readTimeout = 20000
                    doOutput = true
                }

                connection.outputStream.use { os ->
                    os.write(jsonBody.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                    Log.d(TAG, "Polish API success response from $modelName: $responseString")
                    AppLogRepository.addLog(
                        LogLevel.RECEIVED,
                        "PolishAPI",
                        "HTTP 200 OK from $modelName",
                        responseString
                    )

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
                            var textResult = sb.toString().trim()
                            // Clean any accidental markdown code fences or full-response quotes
                            if (textResult.startsWith("```") && textResult.endsWith("```")) {
                                textResult = textResult.removeSurrounding("```").trim()
                                if (textResult.startsWith("markdown") || textResult.startsWith("text")) {
                                    textResult = textResult.substringAfter("\n").trim()
                                }
                            }
                            if (textResult.startsWith("\"") && textResult.endsWith("\"") && textResult.length > 2) {
                                textResult = textResult.substring(1, textResult.length - 1).trim()
                            }

                            if (textResult.isNotBlank()) {
                                Log.d(TAG, "Polish call succeeded using model $modelName for app $appName ($category)")
                                lastSuccessfulPolishModel = modelName
                                return PolishResult(textResult, null)
                            }
                        }
                    }
                    errorsLog.add("$modelName returned empty candidates/parts")
                } else {
                    val errorStreamText = connection.errorStream?.bufferedReader()?.use { it.readText() }
                        ?: connection.inputStream?.bufferedReader()?.use { it.readText() }
                        ?: "No error body"
                    val errLogMsg = "HTTP $responseCode ($modelName): $errorStreamText"
                    Log.e(TAG, "Polish API Error: $errLogMsg")
                    AppLogRepository.addLog(
                        LogLevel.ERROR,
                        "PolishAPI",
                        "HTTP $responseCode Error ($modelName)",
                        errorStreamText
                    )
                    errorsLog.add(errLogMsg)
                }
            } catch (e: Exception) {
                val excMsg = "Exception ($modelName): ${e.message}"
                Log.e(TAG, "Error in Polish call for model $modelName", e)
                AppLogRepository.addLog(
                    LogLevel.ERROR,
                    "PolishAPI",
                    "Exception connecting to $modelName: ${e.message}",
                    e.stackTraceToString()
                )
                errorsLog.add(excMsg)
            } finally {
                connection?.disconnect()
            }
        }
        return PolishResult(null, errorsLog.joinToString(" | "))
    }
}

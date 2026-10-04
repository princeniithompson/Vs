package com.example.service

import android.util.Log
import com.example.core.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AppInfo(val appName: String, val category: String)

object AppDetector {
    private const val TAG = "AppDetector"
    private const val MODEL_NAME = "gemini-3.1-flash-lite-preview"

    suspend fun detectApp(packageName: String, extraEvidence: String? = null): AppInfo = withContext(Dispatchers.IO) {
        val apiKey = FloatingBubbleService.instance?.let {
            val prefs = it.getSharedPreferences("voxstream_settings", android.content.Context.MODE_PRIVATE)
            val customKey = prefs.getString("custom_api_key", "")?.trim() ?: ""
            ApiConfig.getEffectiveKey(customKey)
        } ?: ApiConfig.getEffectiveKey()

        if (ApiConfig.isPlaceholder(apiKey) || apiKey.isEmpty()) {
            throw Exception("API key is missing or default placeholder.")
        }

        // Step 1: Unstructured Information Retrieval
        val systemInstruction1 = "Identify the application associated with the provided package name. Use Google Search to find a detailed, plain-text description of the app's function and purpose. Cite verifiable sources whenever possible."
        val userContent1 = buildString {
            append("Package name: ").append(packageName)
            if (!extraEvidence.isNullOrBlank()) {
                append("\n").append(extraEvidence)
            }
        }

        Log.d(TAG, "Step 1: Unstructured Information Retrieval for $packageName")
        val step1Text = try {
            callGeminiWithRetry(
                apiKey = apiKey,
                systemInstruction = systemInstruction1,
                userContent = userContent1,
                enableGoogleSearch = true,
                responseMimeType = null,
                responseSchema = null
            )
        } catch (e: Exception) {
            // Grounding incompatible with structured output logging requirement
            val msg = e.message ?: ""
            if (msg.contains("grounding", ignoreCase = true) || msg.contains("structured", ignoreCase = true)) {
                Log.d(TAG, "Step 1 grounding error: Grounding and structured output are incompatible or conflict in config: $msg")
            }
            throw e
        }

        if (step1Text.isNullOrBlank()) {
            throw Exception("Step 1 returned empty or null description text")
        }

        Log.d(TAG, "Step 1 returned description text of length: ${step1Text.length}")

        // Step 2: Structured Data Extraction
        val systemInstruction2 = "You are given a description of an Android application. Extract the app's common name and classify its primary category as exactly one of: 'AI', 'Social', 'Work', or 'Other'. Return ONLY a JSON object matching the schema: { 'type': 'object', 'properties': { 'appName': { 'type': 'string' }, 'category': { 'type': 'string', 'enum': ['AI', 'Social', 'Work', 'Other'] } }, 'required': ['appName', 'category'] } }."
        val userContent2 = "Description: $step1Text\n\nExtract the app name and category."

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

        Log.d(TAG, "Step 2: Structured Data Extraction")
        val step2JsonText = callGeminiWithRetry(
            apiKey = apiKey,
            systemInstruction = systemInstruction2,
            userContent = userContent2,
            enableGoogleSearch = false,
            responseMimeType = "application/json",
            responseSchema = schemaJson
        )

        if (step2JsonText.isNullOrBlank()) {
            throw Exception("Step 2 returned empty or null JSON response")
        }

        Log.d(TAG, "Step 2 response text: $step2JsonText")

        try {
            val json = JSONObject(step2JsonText.trim())
            val appName = json.getString("appName").trim()
            val category = json.getString("category").trim()
            if (appName.isEmpty() || category.isEmpty()) {
                throw Exception("appName or category is empty")
            }
            AppInfo(appName = appName, category = category)
        } catch (e: Exception) {
            throw Exception("Structured data extraction failed to parse JSON: ${e.message}")
        }
    }

    private suspend fun callGeminiWithRetry(
        apiKey: String,
        systemInstruction: String,
        userContent: String,
        enableGoogleSearch: Boolean,
        responseMimeType: String?,
        responseSchema: JSONObject?
    ): String? {
        var attempts = 0
        val maxAttempts = 3
        var backoffMs = 1000L

        while (true) {
            attempts++
            try {
                return callGeminiREST(
                    apiKey = apiKey,
                    systemInstruction = systemInstruction,
                    userContent = userContent,
                    enableGoogleSearch = enableGoogleSearch,
                    responseMimeType = responseMimeType,
                    responseSchema = responseSchema
                )
            } catch (e: IOException) {
                val errorMsg = e.message ?: ""
                val is429 = errorMsg.contains("429") || errorMsg.contains("ResourceExhausted", ignoreCase = true)
                if (is429 && attempts < maxAttempts) {
                    Log.w(TAG, "HTTP 429 received on attempt $attempts. Retrying in ${backoffMs}ms...")
                    kotlinx.coroutines.delay(backoffMs)
                    backoffMs *= 2
                    continue
                }
                throw e
            }
        }
    }

    private fun callGeminiREST(
        apiKey: String,
        systemInstruction: String,
        userContent: String,
        enableGoogleSearch: Boolean,
        responseMimeType: String?,
        responseSchema: JSONObject?
    ): String? {
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
                connectTimeout = 8000
                readTimeout = 8000
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
                        return sb.toString().trim()
                    }
                }
            } else {
                val errorStreamText = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: connection.inputStream?.bufferedReader()?.use { it.readText() }
                    ?: "No error body"
                throw IOException("HTTP $responseCode: $errorStreamText")
            }
        } catch (e: Exception) {
            throw e
        } finally {
            connection?.disconnect()
        }
        return null
    }
}

package com.example.service.floating

import android.util.Log
import com.example.data.AppLogRepository
import com.example.data.LogLevel
import com.example.service.AppCategory
import com.example.service.AppClassifier
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
    private var lastSuccessfulPolishModel: String = "gemini-3.5-flash-lite"

    private const val BASE_SYSTEM_INSTRUCTION = """You are Flow, an AI transcript cleaner. Input: a raw spoken transcript. Output: ONLY the cleaned final text — nothing else.

Never answer questions asked in the transcript — transcribe them as questions, don't respond to them.
Never explain your changes.
Never copy any word, number, or item from the examples below into your output — those are for pattern reference only. Every number and item in your output must come from the transcript you are given, not from these examples.
Preserve whatever language(s) the speaker used — do not translate.
Add natural punctuation and capitalization to whatever text isn't otherwise changed by the rules below.

CORE RULES:
1. Remove filler words and verbal hesitations (um, uh, like, so, okay, yeah, yes yeah, I think, you know, kind of, sort of).
2. When the speaker corrects a stated value (actually, no wait, I mean, sorry, scratch that), replace it — never keep the original, incorrect version.
3. When the speaker states a quantity needed, then separately mentions an amount already owned/available, calculate the true remaining amount and output ONLY that final number.
4. When the speaker names 2 or more discrete items, output them cleanly.

EXAMPLE 1 (simple correction)
Raw: "Let's meet at 5, actually 6."
Output: Let's meet at 6.

EXAMPLE 2 (list + correction)
Raw: "I want to buy two no three books, a lamp, and a rug. Actually skip the rug."
Output: I want to buy:
- 3 books
- a lamp

EXAMPLE 3 (quantity adjustment)
Raw: "I need 10 chairs for the event. Wait, I already have 4 chairs at home, so I'd only need 6."
Output: I need 6 chairs for the event."""

    fun polishTranscript(
        apiKey: String,
        rawTranscript: String,
        category: AppCategory = AppCategory.OTHER,
        appName: String = "App",
        aiPolishMode: AiPolishMode? = null,
        conversationContext: String? = null
    ): PolishResult {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty() || trimmedKey.equals("MY_GEMINI_API_KEY", ignoreCase = true)) {
            throw IllegalArgumentException("Gemini API Key is missing or placeholder. Please provide a valid key in Settings.")
        }

        val baseModels = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash"
        )
        val modelsToTry = listOf(lastSuccessfulPolishModel) + baseModels.filter { it != lastSuccessfulPolishModel }

        val isAiApp = category == AppCategory.AI
        val fullSystemInstruction: String
        val userContentText: String

        if (isAiApp && aiPolishMode == AiPolishMode.OPTIMIZE_PROMPT) {
            // Lyra-style Structured Prompt Optimization
            fullSystemInstruction = """
You are Lyra, a master-level AI prompt optimization engine.
Your sole purpose is to transform the user's rough spoken thoughts into a high-yield, structured prompt suitable for an advanced language model (such as ChatGPT, Claude, Gemini, or DeepSeek).

CRITICAL RULES:
- Output ONLY the final ready-to-send prompt.
- NEVER include introductory chatter, greetings, commentary, explanations, or quotes (DO NOT write "Here is your prompt:").
- NEVER include meta-headers like "Deconstruct:", "Diagnose:", "Develop:", "Deliver:", or "Pro Tip:".
- Structure the prompt with precision: define a clear Objective, relevant Context, strict Constraints, and expected Output Format when helpful.
- Keep it sharp, direct, concise, and actionable—avoid robotic bloat or unnecessary length.
""".trimIndent()

            val contextSnippet = if (!conversationContext.isNullOrBlank()) {
                "\n\n<recent_conversation_context>\n$conversationContext\n</recent_conversation_context>"
            } else ""

            userContentText = """
<raw_input>
$rawTranscript
</raw_input>$contextSnippet
""".trimIndent()
        } else if (isAiApp) {
            // Clean Message Mode for conversational follow-ups in AI chats
            fullSystemInstruction = """
You are a skilled text editor for conversational AI chat ($appName).
Your sole purpose is to polish the user's input for an ongoing chat message.

CRITICAL RULES:
- Output ONLY the final polished text.
- Fix grammar, spelling, punctuation, and awkward phrasing.
- Strictly maintain the user's natural voice, tone, and personal conversational style.
- Keep it natural, human, and concise.
- Never answer questions asked in the transcript.
- Never include introductory chatter, commentary, or quotes.
""".trimIndent()

            val contextSnippet = if (!conversationContext.isNullOrBlank()) {
                "\n\n<recent_conversation_context>\n$conversationContext\n</recent_conversation_context>"
            } else ""

            userContentText = """
<raw_input>
$rawTranscript
</raw_input>$contextSnippet
""".trimIndent()
        } else {
            // Standard Polish for non-AI apps (WhatsApp, Gmail, Notes, etc.)
            val categoryGuidelines = AppClassifier.getCategoryPromptGuidelines(category, appName)
            fullSystemInstruction = """
$BASE_SYSTEM_INSTRUCTION

APP-AWARE CONTEXT GUIDELINES:
$categoryGuidelines
""".trimIndent()
            userContentText = rawTranscript
        }

        fun buildJsonBody(modelName: String): String {
            return JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", fullSystemInstruction)))
                })
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", userContentText)))
                    }
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", if (isAiApp && aiPolishMode == AiPolishMode.OPTIMIZE_PROMPT) 0.3 else 0.1)
                    if (modelName.contains("3.")) {
                        put("thinkingConfig", JSONObject().apply {
                            put("thinkingLevel", "MINIMAL")
                        })
                    } else {
                        put("thinkingConfig", JSONObject().apply {
                            put("thinkingBudget", 0)
                        })
                    }
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

                val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
                val url = URL(urlString)

                AppLogRepository.addLog(
                    LogLevel.SENT,
                    "PolishAPI",
                    "Sending POST request to model '$modelName' for $appName ($category) at $urlString",
                    jsonBody
                )

                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("x-goog-api-key", trimmedKey)
                    setRequestProperty("Connection", "keep-alive")
                    connectTimeout = 5000
                    readTimeout = 8000
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
                            val textResult = sb.toString().trim()
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

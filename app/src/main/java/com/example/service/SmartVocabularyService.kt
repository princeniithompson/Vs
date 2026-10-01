package com.example.service

import android.util.Log
import com.example.data.AppLogRepository
import com.example.data.HistoryItem
import com.example.data.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class SmartVocabularySuggestion(
    val id: String = UUID.randomUUID().toString(),
    val term: String,
    val category: String, // e.g. "Music & Artists", "Technical Term", "Proper Noun", "Brand", "Repeated Phrase"
    val reason: String,   // e.g. "Detected in Spotify · Did you mean Black Sherif?"
    val originalSnippet: String = ""
)

object SmartVocabularyService {
    private const val TAG = "SmartVocabService"

    private val candidateModels = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite-preview",
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash"
    )

    suspend fun analyzeWeeklyTranscripts(
        apiKey: String,
        transcripts: List<HistoryItem>,
        existingVocabulary: List<String>
    ): Result<List<SmartVocabularySuggestion>> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty() || trimmedKey.equals("MY_GEMINI_API_KEY", ignoreCase = true)) {
            return@withContext Result.failure(IllegalArgumentException("Gemini API key is missing or placeholder. Please provide a valid key in Settings."))
        }
        if (transcripts.isEmpty()) {
            return@withContext Result.success(emptyList())
        }

        // Format transcripts with their app contexts and timestamps
        val transcriptsFormatted = transcripts.joinToString("\n---\n") { item ->
            "App: ${item.appContext}\nDate: ${item.getFormattedDateHeader()} at ${item.getFormattedTime()}\nText: \"${item.text}\""
        }

        val existingTermsFormatted = if (existingVocabulary.isEmpty()) {
            "None"
        } else {
            existingVocabulary.joinToString(", ")
        }

        val systemPrompt = """
You are an expert personalized vocabulary analyzer for a voice dictation keyboard and typing application.
Your goal is to analyze the user's real saved voice transcripts from the past 7 days and recommend high-value candidate terms to add to their custom speech recognition dictionary.

CRITICAL INSTRUCTIONS:
1. Context-Aware Recognition & Correction:
   - Identify which application the user was dictating in (e.g. Music, Social, Messaging, Work, AI).
   - If the user was in a music app (e.g. Spotify, YouTube Music, Apple Music) and said a word that looks like a mis-heard, mis-spelled, or phonetically transcribed artist or song name, suggest the correct popular spelling with a clear confirmation note.
     Example: If the transcript in Spotify has an attempt at the Ghanaian artist "Black Sherif" (or phonetically similar), suggest "Black Sherif" with reason "Detected in Spotify · Did you mean Black Sherif?".
   - If in messaging or social apps (WhatsApp, Telegram, Instagram, Messages), detect proper names, nicknames, locations, or regional vocabulary.
   - If in work, developer, or AI apps (Slack, Google AI Studio, Docs, Gmail, GitHub), detect technical terms, library names, acronyms, APIs, and domain jargon.

2. Candidate Quality:
   - Only suggest proper nouns, brand names, technical terms, artist/creator names, or distinctive repeated domain phrases.
   - Strictly IGNORE common everyday English words (e.g. "application", "grocery", "water", "today", "problem", "button").
   - DO NOT suggest any terms that are already in the user's custom dictionary: [$existingTermsFormatted].

3. Brevity & Output Format:
   - Return a maximum of 5 to 8 suggestions.
   - You MUST output ONLY valid JSON as an array of objects, with no markdown code fences and no conversational text.
   - JSON structure:
   [
     {
       "term": "Term to Add",
       "category": "Music & Artists" | "Technical Term" | "Proper Noun" | "Brand" | "Phrase",
       "reason": "Clear explanation of why this was suggested and context",
       "originalSnippet": "Exact phrase from the transcript where this appeared"
     }
   ]
""".trimIndent()

        val userPrompt = """
Here are the user's real dictation transcripts from the past 7 days:

$transcriptsFormatted

Existing Custom Vocabulary (Do NOT duplicate):
$existingTermsFormatted

Analyze these transcripts and output the JSON list of 5-8 smart vocabulary suggestions:
""".trimIndent()

        val jsonRequestBody = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                }
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("responseMimeType", "application/json")
            })
        }.toString()

        val errorsLog = mutableListOf<String>()

        for (modelName in candidateModels) {
            var connection: HttpURLConnection? = null
            try {
                val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
                val url = URL(urlString)

                AppLogRepository.addLog(
                    LogLevel.SENT,
                    "SmartVocabAPI",
                    "Requesting smart vocabulary analysis from model '$modelName' (${transcripts.size} transcripts)",
                    userPrompt
                )

                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("x-goog-api-key", trimmedKey)
                    setRequestProperty("Connection", "keep-alive")
                    connectTimeout = 8000
                    readTimeout = 15000
                    doOutput = true
                }

                connection.outputStream.use { os ->
                    os.write(jsonRequestBody.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                    Log.d(TAG, "SmartVocab response from $modelName: $responseString")
                    AppLogRepository.addLog(
                        LogLevel.RECEIVED,
                        "SmartVocabAPI",
                        "HTTP 200 OK from $modelName",
                        responseString
                    )

                    val parsedSuggestions = parseGeminiResponse(responseString, existingVocabulary)
                    return@withContext Result.success(parsedSuggestions)
                } else {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                    val errLogMsg = "Model $modelName returned HTTP $responseCode: $errorBody"
                    Log.w(TAG, errLogMsg)
                    AppLogRepository.addLog(LogLevel.ERROR, "SmartVocabAPI", "HTTP $responseCode Error ($modelName)", errorBody)
                    errorsLog.add(errLogMsg)
                }
            } catch (e: Exception) {
                val errMsg = "Exception with $modelName: ${e.message}"
                Log.e(TAG, errMsg, e)
                AppLogRepository.addLog(LogLevel.ERROR, "SmartVocabAPI", errMsg, e.stackTraceToString())
                errorsLog.add(errMsg)
            } finally {
                connection?.disconnect()
            }
        }

        Result.failure(Exception("All candidate models failed: ${errorsLog.joinToString(" | ")}"))
    }

    private fun parseGeminiResponse(
        responseJsonString: String,
        existingVocabulary: List<String>
    ): List<SmartVocabularySuggestion> {
        val suggestions = mutableListOf<SmartVocabularySuggestion>()
        try {
            val rootObj = JSONObject(responseJsonString)
            val candidates = rootObj.optJSONArray("candidates") ?: return emptyList()
            if (candidates.length() == 0) return emptyList()

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content") ?: return emptyList()
            val parts = content.optJSONArray("parts") ?: return emptyList()
            if (parts.length() == 0) return emptyList()

            val text = parts.getJSONObject(0).optString("text", "").trim()
            if (text.isBlank()) return emptyList()

            // Clean any potential markdown wrapping
            val cleanedText = text
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val jsonArray = JSONArray(cleanedText)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val term = item.optString("term", "").trim()
                val category = item.optString("category", "Proper Noun").trim()
                val reason = item.optString("reason", "").trim()
                val snippet = item.optString("originalSnippet", "").trim()

                if (term.isNotBlank()) {
                    // Double-check term is not already present in existing custom vocabulary
                    val isDuplicate = existingVocabulary.any { it.equals(term, ignoreCase = true) }
                    if (!isDuplicate && !suggestions.any { it.term.equals(term, ignoreCase = true) }) {
                        suggestions.add(
                            SmartVocabularySuggestion(
                                term = term,
                                category = category,
                                reason = reason,
                                originalSnippet = snippet
                            )
                        )
                    }
                }
                if (suggestions.size >= 8) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Gemini suggestions JSON", e)
        }
        return suggestions
    }
}
